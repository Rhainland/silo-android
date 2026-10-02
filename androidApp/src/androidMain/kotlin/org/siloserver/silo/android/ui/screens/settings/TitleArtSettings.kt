package org.siloserver.silo.android.ui.screens.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import org.siloserver.silo.common.settings.TitleArtState
import org.siloserver.silo.common.settings.TitleArtStore

/**
 * "Show title art" and its "Apply to all devices" companion, as rows of the
 * Interface card. Renders nothing until the server has confirmed the key
 * (settings revision 16); older servers keep logos on, as before. The switches
 * stay disabled until this session's read lands. [SettingsScreen] refreshes
 * the store when it opens.
 */
@Composable
internal fun ColumnScope.TitleArtSettingsRows(store: TitleArtStore) {
    val state by store.state.collectAsState()
    val saveError by store.saveError.collectAsState()
    if (!state.isSupported) return

    val device = if (LocalConfiguration.current.smallestScreenWidthDp >= 600) "tablet" else "phone"
    SettingsSwitchRow(
        label = "Show title art",
        description = "Use logo artwork as the title when available.",
        checked = state.showTitleArt,
        onCheckedChange = store::setShowTitleArt,
        enabled = state.canEdit,
    )
    SettingsSwitchRow(
        label = "Apply to all devices",
        description = titleArtScopeDescription(state, device),
        checked = state.appliesToAllDevices,
        onCheckedChange = store::setAppliesToAllDevices,
        enabled = state.canEdit,
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
