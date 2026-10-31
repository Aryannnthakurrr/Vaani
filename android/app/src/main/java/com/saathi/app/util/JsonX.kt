package com.saathi.app.util

import kotlinx.serialization.json.Json

/** One lenient JSON configuration shared by the network layer, fixtures and the action parser. */
val SaathiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = true
    coerceInputValues = true
}
