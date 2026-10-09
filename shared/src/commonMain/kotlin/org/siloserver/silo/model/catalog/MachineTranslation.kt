package org.siloserver.silo.model.catalog

/**
 * Whether a row's localized text includes a machine-translated field (the
 * server's `machine_translated_fields` on a detail, season, episode or card).
 */
fun hasMachineTranslation(fields: List<String>?): Boolean = !fields.isNullOrEmpty()

/**
 * The language a season's episode rows are still missing, if any. A season's
 * translation job covers its episodes, so one pending row is enough to start it.
 */
fun List<EpisodeListItem>.pendingEpisodeTranslationLanguage(): String? =
    firstNotNullOfOrNull { episode -> episode.pendingTranslationLanguage?.takeIf { it.isNotBlank() } }
