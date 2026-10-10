package com.example.copilotvoice.settings

/** UI status shown on a model row in the settings panel. */
enum class ModelStatus {
    /** This model is the one currently used for transcription. */
    ACTIVE,

    /** Installed on disk, but not the active model. */
    INSTALLED,

    /** Not installed yet — needs a download. */
    NOT_DOWNLOADED,
}
