package org.siloserver.silo.android.ui.screens.player

import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.player.formatSubtitleTrackDisplayLabel
import kotlin.test.Test
import kotlin.test.assertEquals

class SubtitleTrackDisplayLabelTest {

    @Test
    fun mobileTrackSheetUsesFriendlySubtitleLabels() {
        val label = subtitleTrackLabel(
            sub = PlayerSubtitleInfo(
                index = 0,
                language = "eng",
                codec = null,
                label = "SUBRIP",
                forced = null,
                url = "/stream/subtitles/0.srt",
            ),
            index = 0,
        )

        assertEquals("English • SRT", label)
    }

    @Test
    fun sharedFormatterMarksAiTranslations() {
        val label = formatSubtitleTrackDisplayLabel(
            rawLabel = "English → Dutch (AI) (translated)",
            language = "nl",
            codecOrMime = "srt",
            isForced = false,
            index = 1,
        )

        assertEquals("Dutch • AI Translation • SRT", label)
    }

    @Test
    fun sharedFormatterMarksAiTranscriptions() {
        val label = formatSubtitleTrackDisplayLabel(
            rawLabel = "English (AI transcribed) (transcribed)",
            language = "en",
            codecOrMime = "srt",
            isForced = false,
            index = 2,
        )

        assertEquals("English • AI Transcription • SRT", label)
    }

    @Test
    fun trackSheetChipsTellTranslationsFromTranscriptions() {
        fun chips(label: String, language: String) = subtitleTrackPresentation(
            PlayerSubtitleInfo(index = 3, language = language, codec = "srt", label = label, forced = null, url = "/s.vtt"),
            index = 3,
        ).chips

        assertEquals(listOf("AI Translation"), chips("English → German (AI) (translated)", "de"))
        assertEquals(listOf("AI Transcription"), chips("English (AI transcribed) (transcribed)", "en"))
        assertEquals(listOf("AI Transcription"), chips("English (AI transcribed)", "en"))
        assertEquals(emptyList(), chips("English (opensubtitles)", "en"))
    }
}
