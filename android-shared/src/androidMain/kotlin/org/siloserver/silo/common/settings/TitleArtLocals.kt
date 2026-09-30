package org.siloserver.silo.common.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import org.siloserver.silo.domain.settings.TitleArtController

/**
 * Whether title surfaces may name a title with its logo artwork
 * (`ui.title_art`). When false they always show the text title.
 *
 * Published once near each app shell by [ProvideTitleArt]; anything rendered
 * outside it keeps the contract default (logos on).
 */
val LocalShowTitleArt: ProvidableCompositionLocal<Boolean> =
    staticCompositionLocalOf { TitleArtController.DEFAULT_SHOW_TITLE_ART }

/**
 * Hydrates [store] for [sessionKey] (the active profile id; null before sign-in)
 * and publishes its value via [LocalShowTitleArt] for [content]. Same shape as
 * `ProvideCardPresentation`.
 */
@Composable
fun ProvideTitleArt(
    store: TitleArtStore,
    sessionKey: Any? = null,
    content: @Composable () -> Unit,
) {
    LaunchedEffect(store, sessionKey) {
        if (sessionKey != null) store.hydrateIfNeeded()
    }
    val state by store.state.collectAsState()
    CompositionLocalProvider(LocalShowTitleArt provides state.showTitleArt, content = content)
}
