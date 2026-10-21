package io.github.dshpet.mobile

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.dshpet.mobile.modules.ChatBridge
import java.util.concurrent.Executors

/**
 * The chat screen. It talks to the running service through [ChatBridge] when the
 * pet is up (so the answer also appears in the pet's bubble), and falls back to a
 * direct one-shot call when it is not.
 */
class ChatActivity : ComponentActivity() {
    companion object {
        const val EXTRA_PET_ID = "petId"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = PetPreferences(this)
        val petId = intent.getIntExtra(EXTRA_PET_ID, 0)
        setContent { ChatScreen(preferences, petId, ::finish) }
    }
}

private data class ChatLine(val role: String, val text: String)

@Composable
private fun ChatScreen(preferences: PetPreferences, petId: Int, onClose: () -> Unit) {
    var options by remember { mutableStateOf(preferences.options()) }
    val lines = remember {
        mutableStateListOf<ChatLine>().apply {
            ChatBridge.module?.history()?.forEach { add(ChatLine(it.role, it.content)) }
        }
    }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val worker = remember {
        Executors.newSingleThreadExecutor { r -> Thread(r, "dsh-pet-chat-ui").apply { isDaemon = true } }
    }
    val main = remember { Handler(Looper.getMainLooper()) }
    DisposableEffect(Unit) { onDispose { runCatching { worker.shutdownNow() } } }
    val scheme = if (isSystemInDarkTheme()) darkColorScheme(
        primary = Color(0xFF76D5BD), onPrimary = Color(0xFF00382F),
        background = Color(0xFF191C1C), surface = Color(0xFF191C1C),
        secondaryContainer = Color(0xFF374944), onSecondaryContainer = Color(0xFFDDE5E1),
        surfaceVariant = Color(0xFF3F4844), tertiary = Color(0xFFFFB4A2)) else lightColorScheme(
        primary = Color(0xFF006B5C), background = Color(0xFFF8FAF9), surface = Color(0xFFF8FAF9),
        secondaryContainer = Color(0xFFE1E9E5), onSecondaryContainer = Color(0xFF293A34),
        surfaceVariant = Color(0xFFE7ECE9), tertiary = Color(0xFFAD4C38))

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }

    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.chat_close))
                    }
                    Text(stringResource(R.string.chat_title),
                        style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        ChatBridge.module?.clearHistory()
                        lines.clear()
                    }) { Text(stringResource(R.string.chat_clear)) }
                }
                if (!options.chatConfigured) {
                    Text(stringResource(R.string.chat_not_configured),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium)
                }
                LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(lines) { line ->
                        Row(Modifier.fillMaxWidth(),
                            horizontalArrangement = if (line.role == "user")
                                Arrangement.End else Arrangement.Start) {
                            Box(Modifier.widthIn(max = 300.dp)
                                .background(if (line.role == "user")
                                    MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.secondaryContainer,
                                    RoundedCornerShape(14.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text(line.text, color = if (line.role == "user")
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSecondaryContainer,
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    if (busy) item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.chat_thinking),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = draft, onValueChange = { draft = it },
                        modifier = Modifier.weight(1f), maxLines = 4,
                        placeholder = { Text(stringResource(R.string.chat_placeholder)) })
                    IconButton(onClick = {
                        val text = draft.trim()
                        if (text.isEmpty() || busy) return@IconButton
                        draft = ""
                        error = null
                        busy = true
                        lines.add(ChatLine("user", text))
                        val module = ChatBridge.module
                        if (module != null) {
                            module.send(text, petId) { result ->
                                busy = false
                                result.onSuccess { lines.add(ChatLine("assistant", it)) }
                                    .onFailure { error = it.message ?: "" }
                            }
                        } else {
                            val snapshot = preferences.options()
                            worker.execute {
                                val reply = runCatching {
                                    ChatClient.complete(snapshot.chatBaseUrl, snapshot.chatApiKey,
                                        snapshot.chatModel, snapshot.chatSystem, emptyList(), text)
                                }
                                main.post {
                                    busy = false
                                    reply.onSuccess { lines.add(ChatLine("assistant", it)) }
                                        .onFailure { error = it.message ?: "" }
                                }
                            }
                        }
                    }, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.chat_send))
                    }
                }
                if (options.chatApiKey.isNotBlank()) {
                    Text(stringResource(R.string.chat_key_ready),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
