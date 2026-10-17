package com.taksolutions.takbeacon.chat

import java.util.regex.Pattern

/**
 * Priority-alert keywords: which words or phrases in an incoming message sound the alarm.
 * Pure, so it is unit-tested.
 *
 * A keyword matches as a whole word or phrase, ignoring case: "fire" matches "Fire!" and
 * "structure fire" but not "firewall". Spaces inside a phrase match any run of whitespace.
 */
object KeywordAlert {

    const val MAX_KEYWORDS = 50
    const val MAX_LENGTH = 64

    /** The keyword list from what the user typed (or the MDM sent): comma, semicolon or line separated. */
    fun parse(raw: String): List<String> =
        raw.split(',', ';', '\n')
            .map { it.trim().replace(Regex("\\s+"), " ").take(MAX_LENGTH).trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .take(MAX_KEYWORDS)

    /** The first keyword found in [text], or null. */
    fun match(text: String, keywords: List<String>): String? =
        keywords.firstOrNull { k -> k.isNotBlank() && pattern(k).matcher(text).find() }

    private fun pattern(keyword: String): Pattern {
        val body = keyword.trim().split(Regex("\\s+")).joinToString("\\s+") { Pattern.quote(it) }
        return Pattern.compile("(?<![\\p{L}\\p{N}])$body(?![\\p{L}\\p{N}])", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
    }
}
