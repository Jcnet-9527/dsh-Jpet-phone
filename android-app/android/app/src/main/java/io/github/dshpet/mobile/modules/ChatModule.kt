package io.github.dshpet.mobile.modules

import android.content.Intent
import android.os.Handler
import android.os.Looper
import io.github.dshpet.mobile.ChatActivity
import io.github.dshpet.mobile.ChatClient
import io.github.dshpet.mobile.ChatReply
import io.github.dshpet.mobile.ChatTurn
import io.github.dshpet.mobile.ModuleIds
import io.github.dshpet.mobile.PetHandle
import io.github.dshpet.mobile.PetOptions
import io.github.dshpet.mobile.R
import io.github.dshpet.mobile.SpeechLines
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The optional AI chat. The module itself owns no window and no thread until a
 * request is actually made, and it makes no request the user did not ask for:
 * enabling it only adds two long-press entries.
 *
 * The conversation lives in this module (not in the activity) so it survives the
 * chat screen being closed, exactly like the desktop plugin's memory file.
 */
class ChatModule : HostBoundModule() {
    override val id = ModuleIds.CHAT

    private val history = ArrayList<ChatTurn>()
    private val worker: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "dsh-pet-chat").apply { isDaemon = true }
        }
    }
    private val main: Handler by lazy { Handler(Looper.getMainLooper()) }
    private val alive = java.util.concurrent.atomic.AtomicBoolean(true)

    /** Bumped on every answer, so a new reply stops the previous walk-through. */
    private var bubbleSeq = 0

    override fun onEnable() {
        ChatBridge.module = this
    }

    override fun onDisable() {
        alive.set(false)
        ChatBridge.module = null
        runCatching { worker.shutdownNow() }
    }

    override fun onOptionsChanged() {
        // A new persona or a smaller memory window applies to the next turn.
        synchronized(history) { trim(history, host.options) }
    }

    override fun menuItems(pet: PetHandle): List<ModuleMenu> {
        val entries = mutableListOf<ModuleMenu>()
        entries += ModuleMenu(io.github.dshpet.mobile.R.string.chat_talk) { openChat(pet.id) }
        if (host.options.chatBalance) {
            entries += ModuleMenu(io.github.dshpet.mobile.R.string.chat_balance) {
                balance(pet.id) {}
            }
        }
        return entries
    }

    override fun diagnostics(): String {
        val size = synchronized(history) { history.size }
        return "chat=$size"
    }

    /** The remembered conversation, newest last. */
    fun history(): List<ChatTurn> = synchronized(history) { ArrayList(history) }

    fun clearHistory() = synchronized(history) { history.clear() }

    /**
     * Opens the chat. In front of the pet this is the floating input bubble
     * (the desktop plugin's behaviour); without a running overlay, or when the
     * user opened it from the settings screen, it falls back to the chat screen.
     */
    fun openChat(petId: Int) {
        val prompt = ChatBridge.prompt
        if (prompt != null) {
            prompt(petId) { text -> send(text, petId) { } }
            return
        }
        val context = runCatching { host.context }.getOrNull() ?: return
        context.startActivity(Intent(context, ChatActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(ChatActivity.EXTRA_PET_ID, petId))
    }

    /**
     * Sends [text] and reports the reply on the main thread. The reply is also
     * shown as the pet's speech bubble so the pet answers in place.
     */
    fun send(text: String, petId: Int, onDone: (Result<String>) -> Unit) {
        val options = host.options
        if (!options.chatConfigured) {
            onDone(Result.failure(ChatClient.ChatException(missingText(options))))
            return
        }
        val past = synchronized(history) { ArrayList(history) }
        synchronized(history) { history.add(ChatTurn("user", text)) }
        worker.execute {
            val reply = runCatching {
                ChatClient.complete(options.chatBaseUrl, options.chatApiKey, options.chatModel,
                    options.chatSystem, past, text)
            }
            reply.getOrNull()?.let { answer ->
                synchronized(history) {
                    history.add(ChatTurn("assistant", answer.text))
                    trim(history, options)
                }
            }
            main.post {
                if (!alive.get()) return@post
                reply.getOrNull()?.let { announce(petId, it) }
                onDone(reply.map { it.text })
            }
        }
    }

    /** DeepSeek-only balance query, shown as a bubble and returned to the caller. */
    fun balance(petId: Int, onDone: (Result<String>) -> Unit) {
        val options = host.options
        if (options.chatApiKey.isBlank()) {
            val message = missingText(options)
            announce(petId, ChatReply(message))
            onDone(Result.failure(ChatClient.ChatException(message)))
            return
        }
        worker.execute {
            val result = runCatching { ChatClient.balance(options.chatBaseUrl, options.chatApiKey) }
            main.post {
                if (!alive.get()) return@post
                // A failure is spoken too: a silent menu entry looks broken.
                announce(petId, ChatReply(result.fold({ it }, { it.message ?: "查询失败" })))
                onDone(result)
            }
        }
    }

    private fun missingText(options: PetOptions): String {
        val context = runCatching { host.context }.getOrNull() ?: return "还没有配置接口"
        return context.getString(
            if (options.chatApiKey.isBlank()) io.github.dshpet.mobile.R.string.chat_need_key
            else io.github.dshpet.mobile.R.string.chat_need_endpoint)
    }

    /**
     * Says [reply] the way the desktop does: the whole answer is read out once,
     * then the bubble walks through it a sentence or two at a time.
     *
     * A 90-character cut used to be the whole display, so everything past it was
     * simply never shown - the pet said it would look something up and then went
     * quiet. The chat screen always had the full text; now the bubble does too,
     * without covering the pet for a minute at a time.
     */
    private fun announce(petId: Int, reply: ChatReply) {
        val pet = host.pets.firstOrNull { it.id == petId } ?: host.pets.firstOrNull() ?: return
        val pieces = SpeechLines.split(reply.text).toMutableList()
        if (reply.truncated) pieces += contextText(R.string.chat_truncated)
        if (pieces.isEmpty()) return
        // Voice mode reads the complete answer; the bubbles are only its display.
        if (reply.text.isNotBlank()) host.speak(reply.text)
        showPieces(pet, pieces)
    }

    private fun showPieces(pet: PetHandle, pieces: List<String>) {
        // A new answer replaces the one still being walked through.
        val seq = ++bubbleSeq
        fun step(index: Int) {
            if (!alive.get() || seq != bubbleSeq) return
            val piece = pieces[index]
            val dwell = dwellMs(piece)
            host.showPhrase(pet, piece, dwell, spoken = false)
            if (index + 1 < pieces.size) main.postDelayed({ step(index + 1) }, dwell)
        }
        step(0)
    }

    /** Long enough to read [piece], or to hear it when voice mode is on. */
    private fun dwellMs(piece: String): Long {
        val perChar = if (host.options.voiceSpeak) 190f else 110f
        val speed = host.options.voiceSpeed.coerceIn(0.5f, 2f)
        return (900L + (piece.length * perChar / speed).toLong()).coerceIn(2_000L, 11_000L)
    }

    private fun contextText(id: Int): String =
        runCatching { host.context.getString(id) }.getOrDefault("…")

    private fun trim(list: ArrayList<ChatTurn>, options: PetOptions) {
        val keep = options.chatMemory.coerceAtLeast(0) * 2
        if (list.size > keep) list.subList(0, list.size - keep).clear()
    }
}

/** The one link between the chat screen and the running service. */
object ChatBridge {
    @Volatile
    var module: ChatModule? = null

    /**
     * Set while the overlay is up: opens the floating input bubble next to the
     * pet (petId -> submit). The chat screen stays available from the settings.
     */
    @Volatile
    var prompt: ((Int, (String) -> Unit) -> Unit)? = null
}
