package io.github.simoncek.voiceprompt.settings

/** How the Whisper model is executed. */
enum class ProcessingMode {
    /** Use the GPU (Vulkan) when one is available, otherwise fall back to CPU. */
    AUTOMATIC,

    /** Force CPU-only inference (GPU disabled). */
    CPU,

    /** Force GPU (Vulkan) inference; falls back to CPU when no GPU is present. */
    GPU,
}
