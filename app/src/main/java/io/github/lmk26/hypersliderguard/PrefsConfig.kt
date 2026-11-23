package io.github.lmk26.hypersliderguard

object PrefsConfig {
    const val PREFS_NAME = "hyper_slider_guard_prefs"

    const val MODE_DISABLE = 0
    const val MODE_CUSTOM_TIMEOUT = 1
    const val MODE_SYSTEM_DEFAULT = 2

    const val KEY_BRIGHTNESS_MODE = "brightness_mode"
    const val KEY_BRIGHTNESS_TIMEOUT_MS = "brightness_timeout_ms"
    const val KEY_VOLUME_MODE = "volume_mode"
    const val KEY_VOLUME_TIMEOUT_MS = "volume_timeout_ms"

    const val DEFAULT_MODE = MODE_DISABLE
    const val DEFAULT_TIMEOUT_MS = 1000
    const val MIN_TIMEOUT_MS = 400
    const val MAX_TIMEOUT_MS = 3000
    const val STEP_TIMEOUT_MS = 50
}