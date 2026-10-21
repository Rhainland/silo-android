package org.siloserver.silo.repository

import org.siloserver.silo.model.section.ProfileSectionSetting
import org.siloserver.silo.model.section.SectionOverride

/**
 * The override set a profile saves for its Home page
 * (`PUT /api/v2/profile/sections?scope=home`), which replaces every override
 * the profile saved for Home.
 *
 * [rows] is the Home settings list (`/profile/sections/settings`) in the new
 * display order, with each row's chosen visibility. Every row stores its
 * list index as its position. A row the profile already saved an override
 * for keeps that override, its id and every other field (a rename, a
 * featured or item-limit change, a profile-built row's recipe and config),
 * with only position and hidden replaced. An admin row with no override
 * gets a new one from [newId]. A saved override whose row is not in [rows]
 * (a row the profile removed, or one it cannot currently see) is kept
 * unchanged, so a reorder never loses it.
 */
fun buildHomeSectionOverrides(
    rows: List<ProfileSectionSetting>,
    saved: List<SectionOverride>,
    newId: () -> String,
): List<SectionOverride> {
    // The server resolves the last saved override for a section, so that is
    // the one a row keeps.
    val savedByRow = LinkedHashMap<String, SectionOverride>()
    for (override in saved) {
        val key = override.rowKey() ?: continue
        savedByRow[key] = override
    }
    val matched = mutableSetOf<SectionOverride>()
    val rowOverrides = rows.mapIndexedNotNull { index, row ->
        val existing = savedByRow[row.rowKey()]
        if (existing != null) {
            matched += existing
            existing.copy(
                id = existing.id.ifBlank { newId() },
                position = index,
                hidden = row.hidden,
            )
        } else if (row.isCustom) {
            // A profile-built row only exists as its saved override; without
            // it there is no recipe to write back.
            null
        } else {
            SectionOverride(id = newId(), sectionId = row.id, position = index, hidden = row.hidden)
        }
    }
    // Unlisted overrides go first: should one duplicate a listed row's
    // section, the listed row's override is still the last and wins.
    val unlisted = saved.filter { it !in matched }
    return unlisted + rowOverrides
}

/** Admin rows are matched by section id, profile-built rows by override id. */
private fun ProfileSectionSetting.rowKey(): String = if (isCustom) "custom:$id" else "admin:$id"

private fun SectionOverride.rowKey(): String? = when {
    sectionId.isNotBlank() -> "admin:$sectionId"
    id.isNotBlank() -> "custom:$id"
    else -> null
}
