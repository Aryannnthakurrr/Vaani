package com.saathi.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saathi.app.core.AppContainer
import com.saathi.app.core.AssistantScreen
import com.saathi.app.core.AssistantScreen.Prompt
import com.saathi.app.core.voice.tr
import com.saathi.app.ui.theme.Amber
import com.saathi.app.ui.theme.Amber50
import com.saathi.app.ui.theme.Leaf
import com.saathi.app.ui.theme.Leaf50

/**
 * The assistant inside Saathi's own screens: the same [AssistantScreen] the overlays draw over other apps, as one
 * card at the bottom. A question dims the screen behind it, since it needs an answer; the "Done" card and the
 * result card don't. Only one card shows at a time, so nothing is said twice.
 */
@Composable
fun BoxScope.AssistantSheet(c: AppContainer) {
    val screen = c.assistant.screen
    val settings by c.settings.flow.collectAsState()
    val prompt by screen.prompt.collectAsState()
    val caption by screen.caption.collectAsState()
    val result by screen.result.collectAsState()
    val style = settings.replyStyle
    fun s(en: String, hi: String, hinglish: String) = tr(style, en, hi, hinglish)
    val doneCard = caption?.takeIf { it.button != null && it.onButton != null }

    AnimatedVisibility(prompt != null, Modifier.matchParentSize(), enter = fadeIn(tween(200)), exit = fadeOut(tween(160))) {
        // Taps on the dimmed screen do nothing: the question needs an answer or Cancel.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f))
            .clickable(remember { MutableInteractionSource() }, indication = null) {})
    }

    // One card at a time: a question first, then the Done card, then a result.
    val showing: Any? = prompt ?: doneCard ?: result
    // Keep drawing the last card while it slides away.
    val last = remember { mutableStateOf<Any?>(null) }
    if (showing != null) last.value = showing
    AnimatedVisibility(showing != null, Modifier.align(Alignment.BottomCenter),
        enter = fadeIn(tween(220)) + slideInVertically(tween(280, easing = Arrive)) { it / 3 },
        exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { it / 3 }) {
        Surface(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.background,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shadowElevation = 16.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = 20.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when (val card = last.value) {
                    is Prompt.Ask -> AskCard(card, c, ::s)
                    is Prompt.Confirm -> ConfirmCard(card, c, ::s)
                    is AssistantScreen.Caption -> DoneCard(card)
                    is AssistantScreen.Result -> ResultCard(card)
                }
            }
        }
    }
}

@Composable
private fun AskCard(p: Prompt.Ask, c: AppContainer, s: (String, String, String) -> String) {
    var text by remember(p) { mutableStateOf("") }
    fun send() {
        if (text.isNotBlank()) p.answer.complete(text.trim())
    }
    Text(p.question, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    VoiceWave({ c.assistant.voiceLevel() }, s("Speak, or type below", "बोलिए, या नीचे लिखिए", "Boliye, ya neeche likhiye"),
        MaterialTheme.colorScheme.primary)
    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodyLarge,
        shape = RoundedCornerShape(18.dp), singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { send() }),
        placeholder = { Text(s("Your answer", "आपका जवाब", "Aapka jawab")) })
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BigButton(s("OK", "ठीक है", "Theek hai"), { send() }, Modifier.weight(1f), enabled = text.isNotBlank())
        SecondaryButton(s("Cancel", "रद्द करें", "Cancel"), { p.answer.complete(null) }, Modifier.weight(1f))
    }
}

@Composable
private fun ConfirmCard(p: Prompt.Confirm, c: AppContainer, s: (String, String, String) -> String) {
    Text(p.summary, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    VoiceWave({ c.assistant.voiceLevel() }, s("Say \"yes\" or \"no\", or tap", "\"हाँ\" या \"नहीं\" बोलिए, या दबाइए", "\"Haan\" ya \"nahi\" boliye, ya dabaiye"),
        MaterialTheme.colorScheme.primary)
    // Saying "no" is never dangerous, so it is a calm grey, not red.
    BigButton(s("Yes", "हाँ", "Haan"), { p.answer.complete(true) }, color = Leaf)
    SecondaryButton(s("No", "नहीं", "Nahi"), { p.answer.complete(false) })
}

@Composable
private fun DoneCard(card: AssistantScreen.Caption) {
    if (card.status.isNotBlank()) Text(card.status, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    Text(card.text, style = MaterialTheme.typography.headlineMedium)
    BigButton(card.button.orEmpty(), { card.onButton?.invoke() }, color = Leaf)
}

@Composable
private fun ResultCard(r: AssistantScreen.Result) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
        Box(Modifier.size(64.dp).background(if (r.ok) Leaf50 else Amber50, CircleShape), contentAlignment = Alignment.Center) {
            Text(if (r.ok) "✓" else "!", fontSize = 32.sp, color = if (r.ok) Leaf else Amber, textAlign = TextAlign.Center)
        }
        Text(r.message, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
    }
}
