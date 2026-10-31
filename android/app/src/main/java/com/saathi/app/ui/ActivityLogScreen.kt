package com.saathi.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.saathi.app.core.AppContainer
import com.saathi.app.core.net.AgentAction
import com.saathi.app.core.voice.tr
import com.saathi.app.ui.theme.Amber
import com.saathi.app.ui.theme.Danger
import com.saathi.app.ui.theme.Leaf
import com.saathi.app.util.Packages
import com.saathi.app.util.SaathiJson
import java.text.DateFormat
import java.util.Date

private val talkActions = setOf(AgentAction.ASK_USER, AgentAction.HIGHLIGHT, AgentAction.CONFIRM)

/** Questions and "do this" steps get a speech icon. Read from the stored action, so it works in every language. */
private fun isQuestion(actionJson: String): Boolean =
    runCatching { SaathiJson.decodeFromString(AgentAction.serializer(), actionJson).action in talkActions }.getOrDefault(false)

/** Everything the agent did, in plain language, so family can review it (SPEC §6.8). */
@Composable
fun ActivityLogScreen(c: AppContainer, onBack: () -> Unit) {
    val rows by c.db.actionLog().recent().collectAsState(initial = emptyList())
    val style = c.settings.flow.collectAsState().value.replyStyle
    val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    SaathiScaffold(tr(style, "What Saathi did", "साथी ने क्या किया", "Saathi ne kya kiya"), onBack) {
        Text(tr(style, "Everything Saathi did for you, in order. Family can check this too.",
            "साथी ने आपके लिए जो कुछ किया, सब यहाँ है। परिवार भी इसे देख सकता है।",
            "Saathi ne aapke liye jo kuch kiya, sab yahan hai. Family bhi ise dekh sakti hai."), style = MaterialTheme.typography.bodyLarge)
        if (rows.isEmpty()) EmptyNote(tr(style, "Nothing yet.", "अभी तक कुछ नहीं।", "Abhi tak kuch nahi."))
        rows.forEach { r ->
            val (badge, color, icon) = when (r.outcome) {
                "blocked" -> Triple(tr(style, "Blocked for safety", "सुरक्षा के लिए रोका", "Safety ke liye roka"), Danger, "🛑")
                "cancelled" -> Triple(tr(style, "Stopped", "रोका गया", "Roka gaya"), Amber, "✋")
                "failed" -> Triple(tr(style, "Didn't work", "नहीं हुआ", "Nahi hua"), Amber, "⚠️")
                "done" -> Triple(tr(style, "Finished", "पूरा हुआ", "Poora hua"), Leaf, "🎉")
                else -> Triple(null, null, if (isQuestion(r.actionJson)) "💬" else "👆")
            }
            InfoCard(title = r.plain, body = "${Packages.displayName(r.pkg)} · ${relativeTime(r.ts, style)}",
                badge = badge, accent = color, icon = icon)
        }
    }
}
