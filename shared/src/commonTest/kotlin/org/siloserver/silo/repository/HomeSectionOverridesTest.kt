package org.siloserver.silo.repository

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.siloserver.silo.model.section.ProfileSectionSetting
import org.siloserver.silo.model.section.SectionOverride
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeSectionOverridesTest {
    private val config = JsonObject(mapOf("genre" to JsonPrimitive("drama")))
    private val renamed = SectionOverride(
        id = "ov-recent", sectionId = "recent", position = 0, hidden = false,
        title = "New this week", featured = true, itemLimit = 12, config = config,
    )
    private val custom = SectionOverride(
        id = "ov-custom", position = 1, isUserAdded = true,
        userSectionType = "hidden_gems", userTitle = "Gems", userConfig = JsonObject(emptyMap()),
    )
    private val removed = SectionOverride(id = "ov-gone", sectionId = "gone", position = 4, removed = true)

    private fun row(id: String, hidden: Boolean = false, isCustom: Boolean = false) =
        ProfileSectionSetting(id = id, title = id, hidden = hidden, isCustom = isCustom)

    @Test
    fun reorderKeepsRenamesAddedAndRemovedRowsAndWritesEveryPosition() {
        val ids = ArrayDeque(listOf("new-1"))
        val built = buildHomeSectionOverrides(
            rows = listOf(row("continue", hidden = true), row("ov-custom", isCustom = true), row("recent")),
            saved = listOf(renamed, custom, removed),
            newId = { ids.removeFirst() },
        )

        assertEquals(
            listOf(
                removed,
                SectionOverride(id = "new-1", sectionId = "continue", position = 0, hidden = true),
                custom.copy(position = 1, hidden = false),
                renamed.copy(position = 2, hidden = false),
            ),
            built,
        )
    }

    @Test
    fun hidingARowWithASavedOverrideKeepsItsOtherFields() {
        val built = buildHomeSectionOverrides(
            rows = listOf(row("recent", hidden = true)),
            saved = listOf(renamed),
            newId = { error("no new id expected") },
        )

        assertEquals(listOf(renamed.copy(position = 0, hidden = true)), built)
    }

    @Test
    fun aSavedOverrideWithoutAnIdGetsANewOne() {
        val built = buildHomeSectionOverrides(
            rows = listOf(row("recent")),
            saved = listOf(renamed.copy(id = "")),
            newId = { "fresh" },
        )

        assertEquals(listOf(renamed.copy(id = "fresh", position = 0)), built)
    }
}
