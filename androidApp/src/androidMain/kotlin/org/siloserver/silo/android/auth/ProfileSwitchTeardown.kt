package org.siloserver.silo.android.auth

import kotlinx.coroutines.withTimeoutOrNull
import org.siloserver.silo.common.settings.CardPresentationStore
import org.siloserver.silo.common.settings.OverlayPrefsStore
import org.siloserver.silo.common.settings.PlayerSettingsStore
import org.siloserver.silo.common.settings.SeekIntervalStore
import org.siloserver.silo.common.settings.TitleArtStore
import org.siloserver.silo.model.profile.ActiveProfileStore

/**
 * The phone's "Switch Profile", shared by the profile menu and Settings.
 *
 * Settings writes wait in the flusher's debounce for a moment, and the flusher
 * drops a write once its profile is no longer active. So the pending writes are
 * pushed first, while the profile they were made on is still the active one
 * (the TV switch does the same). Then the shell is left and the per-profile
 * caches are dropped, or the next profile keeps rendering, and writing back,
 * the previous one's values.
 */
class ProfileSwitchTeardown(
    private val playerSettingsStore: PlayerSettingsStore,
    private val overlayPrefsStore: OverlayPrefsStore,
    private val activeProfileStore: ActiveProfileStore,
    private val cardPresentationStore: CardPresentationStore,
    private val seekIntervalStore: SeekIntervalStore,
    private val titleArtStore: TitleArtStore,
    private val flushTimeoutMs: Long = FLUSH_TIMEOUT_MS,
) {
    /**
     * Push pending settings, then call [leaveShell] and drop per-profile state.
     *
     * The push is bounded: against an unreachable server each write can wait
     * out a connect timeout, and the switch must not hang on that. A write
     * still unsent when the bound passes stays queued under the old profile,
     * as it would have without this flush.
     */
    suspend fun switchProfile(leaveShell: () -> Unit) {
        withTimeoutOrNull(flushTimeoutMs) { playerSettingsStore.flushPendingDeviceSettings() }
        // Navigate before clearing: clearing while the shell is still composed
        // repaints it with default cards behind the picker.
        leaveShell()
        overlayPrefsStore.clear()
        activeProfileStore.reset()
        cardPresentationStore.clear()
        seekIntervalStore.clear()
        titleArtStore.clear()
    }

    private companion object {
        const val FLUSH_TIMEOUT_MS = 5_000L
    }
}
