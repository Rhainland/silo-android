package org.siloserver.silo.android.auth

import org.siloserver.silo.common.settings.CardPresentationStore
import org.siloserver.silo.common.settings.EpisodeSpoilerStore
import org.siloserver.silo.common.settings.OverlayPrefsStore
import org.siloserver.silo.common.settings.SeekIntervalStore
import org.siloserver.silo.common.settings.TitleArtStore
import org.siloserver.silo.model.profile.ActiveProfileStore
import org.siloserver.silo.repository.ProfileRepository

/**
 * Drops the active profile and everything cached for it, so whoever picks
 * next starts clean. Profile Selection uses it at launch and on a return to
 * Silo; the per-profile caches are the ones Switch Profile drops.
 */
class ActiveProfileReset(
    private val profileRepository: ProfileRepository,
    private val overlayPrefsStore: OverlayPrefsStore,
    private val activeProfileStore: ActiveProfileStore,
    private val cardPresentationStore: CardPresentationStore,
    private val seekIntervalStore: SeekIntervalStore,
    private val titleArtStore: TitleArtStore,
    private val episodeSpoilerStore: EpisodeSpoilerStore,
) {
    suspend fun clearActiveProfile() {
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
}
