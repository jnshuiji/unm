package pochita.data

object PreferenceKeys {
    const val PREFS_NAME = "config"

    // Existing rule configuration keys
    const val KEY_RULE_URL = "rule_url"
    const val KEY_CACHED_RULE_SCRIPT = "cached_rule_script"
    const val DEFAULT_RULE_URL = "https://raw.githubusercontent.com/jnshuiji/unm/refs/heads/main/rules/unm_rule.js"

    // Phase 2 Milestone 1 keys
    const val KEY_ENABLED_SOURCES = "enabled_sources"
    const val KEY_PREFERRED_QUALITY = "preferred_quality"
    const val KEY_ENABLE_TOAST = "enable_toast"
    const val KEY_ENABLE_PLAYER_BADGE = "enable_player_badge"
    const val KEY_ENABLE_ANTI_ROLL = "enable_anti_roll"

    // Default configuration values
    val DEFAULT_ENABLED_SOURCES: List<String> = listOf("bodian", "kuwo", "migu", "kugou")
    const val DEFAULT_PREFERRED_QUALITY: String = "320k"
    const val DEFAULT_ENABLE_TOAST: Boolean = true
    const val DEFAULT_ENABLE_PLAYER_BADGE: Boolean = true
    const val DEFAULT_ENABLE_ANTI_ROLL: Boolean = true

    // Audio Quality Options
    const val QUALITY_FLAC = "flac"
    const val QUALITY_320K = "320k"
    const val QUALITY_192K = "192k"
    const val QUALITY_128K = "128k"

    val SUPPORTED_QUALITIES: List<String> = listOf(QUALITY_320K, QUALITY_FLAC, QUALITY_192K, QUALITY_128K)

    // Known Sound Sources
    const val SOURCE_BODIAN = "bodian"
    const val SOURCE_KUWO = "kuwo"
    const val SOURCE_MIGU = "migu"
    const val SOURCE_KUGOU = "kugou"
    const val SOURCE_CUSTOMJS = "customjs"

    val ALL_KNOWN_SOURCES: List<String> = listOf(
        SOURCE_BODIAN,
        SOURCE_KUWO,
        SOURCE_MIGU,
        SOURCE_KUGOU,
        SOURCE_CUSTOMJS
    )

    fun normalizeSourceId(id: String): String = when (id.trim().lowercase()) {
        "js", "custom_js", "customjs" -> SOURCE_CUSTOMJS
        else -> id.trim().lowercase()
    }

    /**
     * Serializes a list of enabled sources to a comma-separated String preserving order.
     */
    fun serializeSources(sources: List<String>): String {
        return sources
            .map { normalizeSourceId(it) }
            .filter { it in ALL_KNOWN_SOURCES }
            .joinToString(",")
    }

    /**
     * Resiliently deserializes an enabled sources String.
     * Supports comma-separated format and JSON array format, with safe fallback.
     */
    fun deserializeSources(raw: String?): List<String> {
        if (raw.isNullOrBlank()) {
            return DEFAULT_ENABLED_SOURCES
        }
        val trimmed = raw.trim()
        if (trimmed.startsWith("[")) {
            try {
                val array = org.json.JSONArray(trimmed)
                val list = mutableListOf<String>()
                for (i in 0 until array.length()) {
                    val item = normalizeSourceId(array.optString(i, ""))
                    if (item.isNotBlank() && item in ALL_KNOWN_SOURCES && !list.contains(item)) {
                        list.add(item)
                    }
                }
                if (list.isNotEmpty()) return list
            } catch (_: Throwable) {
                // Fall back to comma separation or defaults
            }
        }
        val parsed = trimmed.split(",")
            .map { normalizeSourceId(it) }
            .filter { it.isNotEmpty() && it in ALL_KNOWN_SOURCES }
            .distinct()
        return if (parsed.isNotEmpty()) parsed else DEFAULT_ENABLED_SOURCES
    }
}
