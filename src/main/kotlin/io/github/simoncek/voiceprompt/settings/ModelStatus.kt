package io.github.simoncek.voiceprompt.settings

import io.github.simoncek.voiceprompt.VoiceBundle

/** UI status shown on a model row in the settings panel. */
enum class ModelStatus(val label: String) {
    /** This model is the one currently used for transcription. */
    ACTIVE(VoiceBundle["model.status.active"]),

    /** Installed on disk, but not the active model. */
    INSTALLED(VoiceBundle["model.status.installed"]),

    /** The bundled model was removed from disk — restore it from the plugin jar without a network. */
    BUNDLED(VoiceBundle["model.status.bundled"]),

    /** Not installed yet — needs a download. */
    NOT_DOWNLOADED(VoiceBundle["model.status.notdownloaded"]),

    /** A download is currently in progress. */
    DOWNLOADING(VoiceBundle["model.status.downloading"]),
}
