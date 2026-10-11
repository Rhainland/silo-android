package org.siloserver.silo.android.auth

import kotlinx.coroutines.withTimeoutOrNull
import org.siloserver.silo.common.profiles.ProfileLaunchPreferences
import org.siloserver.silo.common.settings.CardPresentationStore
import org.siloserver.silo.common.settings.EpisodeSpoilerStore
import org.siloserver.silo.common.settings.OverlayPrefsStore
import org.siloserver.silo.common.settings.PlayerSettingsStore
import org.siloserver.silo.common.settings.SeekIntervalStore
import org.siloserver.silo.common.settings.TitleArtStore
import org.siloserver.silo.model.profile.ActiveProfileStore
import org.siloserver.silo.repository.ProfileRepository

/**
 * Drops the active profile and everything cached for it, so whoever picks
 * next starts clean. Profile Selection uses it at launch, on a return to
 * Silo, and on a server switch; the per-profile caches are the ones Switch
 * Profile drops.
 */
class ActiveProfileReset(
    private val profileRepository: ProfileRepository,
    private val playerSettingsStore: PlayerSettingsStore,
    private val profileLaunchPreferences: ProfileLaunchPreferences,
    private val overlayPrefsStore: OverlayPrefsStore,
    private val activeProfileStore: ActiveProfileStore,
    private val cardPresentationStore: CardPresentationStore,
    private val seekIntervalStore: SeekIntervalStore,
    private val titleArtStore: TitleArtStore,
    private val episodeSpoilerStore: EpisodeSpoilerStore,
) {
    suspend fun clearActiveProfile() {
        // The flusher drops a write once its profile is no longer active, and
        // onStop's flush may still be in flight; push pending writes first,
        // bounded, as Switch Profile does (ProfileSwitchTeardown).
        withTimeoutOrNull(FLUSH_TIMEOUT_MS) { playerSettingsStore.flushPendingDeviceSettings() }
        // The profile id and its PIN proof go together, so a protected
        // profile asks for its PIN again.
        profileRepository.clearProfile()
        overlayPrefsStore.clear()
        activeProfileStore.reset()
        cardPresentationStore.clear()
        seekIntervalStore.clear()
        titleArtStore.clear()
        episodeSpoilerStore.clear()
    }

    /**
     * Profile Selection covers a server switch as it does a launch (silo-apple
     * design §7.5): when the setting would ask now, drop the newly active
     * server's saved profile so Who's Watching shows and a protected profile
     * needs its PIN. Call it after the switch, before choosing a destination.
     * Returns whether it cleared the profile.
     */
    suspend fun clearIfSelectionRequired(): Boolean {
        if (!profileLaunchPreferences.requiresSelectionAtLaunch()) return false
        clearActiveProfile()
        return true
    }

    private companion object {
        const val FLUSH_TIMEOUT_MS = 5_000L
    }
}
