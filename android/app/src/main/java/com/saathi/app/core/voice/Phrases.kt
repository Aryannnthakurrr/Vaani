package com.saathi.app.core.voice

import android.content.Context
import com.saathi.app.core.net.ReplyStyle
import com.saathi.app.util.SaathiJson
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * The safety phrase table (assets/phrases.json). Each phrase may also exist as a pre-rendered Bulbul clip
 * `res/raw/<key>_<style>`; [clipRes] finds it so safety speech never waits on the network.
 */
class Phrases(private val context: Context) {
    private val table: Map<String, Map<String, String>> by lazy {
        val text = context.assets.open("phrases.json").bufferedReader().use { it.readText() }
        SaathiJson.decodeFromString(MapSerializer(String.serializer(), MapSerializer(String.serializer(), String.serializer())), text)
    }

    fun text(key: String, style: ReplyStyle): String =
        table[key]?.let { it[style.wire] ?: it["en"] } ?: key

    /** Raw resource id of a pre-rendered clip, or 0 when render_alert_clips.py has not been run yet. */
    @Suppress("DiscouragedApi")
    fun clipRes(key: String, style: ReplyStyle): Int =
        context.resources.getIdentifier("${key}_${style.wire}", "raw", context.packageName)

    val keys: Set<String> get() = table.keys
}

/** Picks the UI/speech string for the user's style. Hinglish falls back to English when not given. */
fun tr(style: ReplyStyle, en: String, hi: String, hinglish: String = en): String = when (style) {
    ReplyStyle.EN -> en
    ReplyStyle.HI -> hi
    ReplyStyle.HINGLISH -> hinglish
}
