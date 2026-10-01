package org.siloserver.silo.android.ui.screens.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import org.siloserver.silo.common.settings.TitleArtState
import org.siloserver.silo.common.settings.TitleArtStore

/**
 * "Show title art" and its "Apply to all devices" companion, as rows of the
 * Interface card. Renders nothing until the server has confirmed the key
 * (settings revision 16); older servers keep logos on, as before.
 */
@Composable
internal fun ColumnScope.TitleArtSettingsRows(store: TitleArtStore) {
    // Opening Settings is a refresh edge, so a choice made on another device
    // shows here without waiting for the next foreground.
    LaunchedEffect(store) { store.refresh() }
    val state by store.state.collectAsState()
    val saveError by store.saveError.collectAsState()
    if (!state.isSupported) return

    val device = if (LocalConfiguration.current.smallestScreenWidthDp >= 600) "tablet" else "phone"
    SettingsSwitchRow(
        label = "Show title art",
        description = "Use logo artwork as the title when available.",
        checked = state.showTitleArt,
        onCheckedChange = store::setShowTitleArt,
    )
    SettingsSwitchRow(
        label = "Apply to all devices",
        description = titleArtScopeDescription(state, device),
        checked = state.appliesToAllDevices,
        onCheckedChange = store::setAppliesToAllDevices,
    )
    if (state.appliesToAllDevices) {
        SettingsProse(body = titleArtAllDevicesNote(state, device))
    }
    saveError?.let { SettingsProse(body = it) }
}

internal fun titleArtScopeDescription(state: TitleArtState, device: String): String =
    if (state.appliesToAllDevices) {
        "On: every device on this profile uses this choice."
    } else {
        "Off: only affects this $device. Your other devices keep their own setting."
    }

internal fun titleArtAllDevicesNote(state: TitleArtState, device: String): String =
    "Title art is ${if (state.showTitleArt) "on" else "off"} on every device signed into " +
        "this profile. Changing it here changes it everywhere. Turn off “Apply to all " +
        "devices” to choose for this $device only."
