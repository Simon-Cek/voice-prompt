package com.example.copilotvoice.stt

/**
 * Languages whisper.cpp can transcribe. [code] is the value passed to the CLI's `-l` flag (a 2-letter
 * code, or "auto" for auto-detection); [displayName] is what the settings UI shows.
 */
enum class WhisperLanguage(val code: String, val displayName: String) {
    AUTO("auto", "Auto"),
    ENGLISH("en", "English"),
    SPANISH("es", "Spanish"),
    GERMAN("de", "German"),
    FRENCH("fr", "French"),
    ITALIAN("it", "Italian"),
    PORTUGUESE("pt", "Portuguese"),
    DUTCH("nl", "Dutch"),
    SWEDISH("sv", "Swedish"),
    NORWEGIAN("no", "Norwegian"),
    DANISH("da", "Danish"),
    FINNISH("fi", "Finnish"),
    POLISH("pl", "Polish"),
    CZECH("cs", "Czech"),
    HUNGARIAN("hu", "Hungarian"),
    ROMANIAN("ro", "Romanian"),
    RUSSIAN("ru", "Russian"),
    TURKISH("tr", "Turkish"),
    GREEK("el", "Greek"),
    ARABIC("ar", "Arabic"),
    HINDI("hi", "Hindi"),
    JAPANESE("ja", "Japanese"),
    KOREAN("ko", "Korean"),
    CHINESE("zh", "Chinese"),
    THAI("th", "Thai"),
    VIETNAMESE("vi", "Vietnamese"),
    ;

    companion object {
        fun fromCode(code: String): WhisperLanguage = values().firstOrNull { it.code == code } ?: AUTO
        fun codeOf(displayName: String): String = values().firstOrNull { it.displayName == displayName }?.code ?: AUTO.code
    }
}
