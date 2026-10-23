package io.github.dshpet.mobile

import android.content.Context
import android.content.SharedPreferences
import kotlin.random.Random

/** Phone-side preferences. Every optional capability has exactly one enable bit
 *  here, and that bit is what the module registry reads. */
data class PetOptions(
    val size: Float = 1f,
    val wander: Boolean = true,
    val speech: Boolean = true,
    val phrases: List<String> = emptyList(),
    val speechMinSeconds: Float = DEFAULT_MIN_SECONDS,
    val speechMaxSeconds: Float = DEFAULT_MAX_SECONDS,
    val speechSeconds: Float = DEFAULT_SPEECH_SECONDS,
    val clickSound: Boolean = true,
    val clickSoundVolume: Float = .7f,
    val clickSoundUri: String? = null,
    val companions: Boolean = true,
    val collision: Boolean = true,
    val collisionRestitution: Float = DEFAULT_RESTITUTION,
    val chat: Boolean = false,
    val chatBaseUrl: String = DEFAULT_CHAT_BASE_URL,
    val chatApiKey: String = "",
    val chatModel: String = DEFAULT_CHAT_MODEL,
    val chatSystem: String = DEFAULT_CHAT_SYSTEM,
    val chatMemory: Int = DEFAULT_CHAT_MEMORY,
    val chatBalance: Boolean = true,
    /** Shake the phone and the pet sways, bounces and settles. */
    val shake: Boolean = true,
    val shakeStrength: Float = .5f,

    /** Use the DSH running on this device for chat, whispers and balance. */
    val dsh: Boolean = false,
    val dshBaseUrl: String = DEFAULT_DSH_BASE_URL,
    val dshToken: String = "",
    /** Download a missing animation pack the first time it is wanted. */
    val packAuto: Boolean = true,
    /** Extra remote root; blank means "use the ones baked into the manifest". */
    val packBaseUrl: String = "",
    /** 0..1: how much of the body may hang off the left, right and bottom edges. */
    val edgeAllowance: Float = DEFAULT_EDGE_ALLOWANCE,
    /** The always-available microphone button (voice input). */
    val voiceChat: Boolean = true,
    /** Voice mode: every line that lands in the pet's bubble is spoken. */
    val voiceSpeak: Boolean = true,
    /** TTS timbre, one of [TIMBRES]. */
    val voiceTimbre: String = TIMBRE_BAKER,
    /** TTS speed, 0.5..2.0. */
    val voiceSpeed: Float = DEFAULT_VOICE_SPEED,
) {
    companion object {
        const val DEFAULT_MIN_SECONDS = 20f
        const val DEFAULT_MAX_SECONDS = 60f
        const val DEFAULT_SPEECH_SECONDS = 3.2f
        const val MIN_SECONDS = 5f
        const val MAX_SECONDS = 600f
        const val MIN_SPEECH_SECONDS = 1f
        const val MAX_SPEECH_SECONDS = 30f

        /** DeepSeek's OpenAI-compatible endpoint; the default chat provider. */
        const val DEFAULT_CHAT_BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_DSH_BASE_URL = "http://127.0.0.1:3081"
        const val DEFAULT_CHAT_MODEL = "deepseek-chat"
        const val DEFAULT_CHAT_SYSTEM = "你是「深深」，一个住在用户手机屏幕上的 Q 版蓝发小女仆桌宠。" +
            "你说话简短、口语化，可以撒娇、吐槽、关心用户，但每次都要把话说完整，不要说到一半就停。" +
            "你没有联网能力，也没有任何工具，查不了天气、新闻、股价这类实时信息：" +
            "遇到这类问题就直接说明你看不到实时信息，再凭常识给一个能用的建议，" +
            "绝不要说“我去查一下”“稍等我看看”这种话。" +
            "不要用 Markdown、列表或长段落，不要自称 AI 或语言模型。"

        /**
         * The persona shipped before 0.6.2. It is still on disk for anyone who
         * ever opened the chat settings, so [PetPreferences] recognises it and
         * keeps using the current one instead.
         */
        const val LEGACY_CHAT_SYSTEM = "你是「深深」，一个住在用户手机屏幕上的 Q 版蓝发小女仆桌宠。" +
            "你说话简短可爱，每次只说一两句话，可以撒娇、吐槽、关心用户。" +
            "不要用 Markdown、列表或长段落，不要自称 AI 或语言模型。"
        /** Chat rounds kept in the prompt; the desktop plugin keeps the last 5 too. */
        const val DEFAULT_CHAT_MEMORY = 5
        const val MIN_CHAT_MEMORY = 0
        const val MAX_CHAT_MEMORY = 20

        /** The desktop's `collision_restitution` default. */
        const val DEFAULT_RESTITUTION = Collision.RESTITUTION.toFloat()

        /** Half a body off an edge by default; 0 keeps the pet fully on screen. */
        const val DEFAULT_EDGE_ALLOWANCE = .5f
        const val MIN_EDGE_ALLOWANCE = 0f
        const val MAX_EDGE_ALLOWANCE = .9f

        const val DEFAULT_VOICE_SPEED = 1f
        const val MIN_VOICE_SPEED = .5f
        const val MAX_VOICE_SPEED = 2f

        /** The matcha baker (sweet female, 22.05 kHz) shipped first. */
        const val TIMBRE_BAKER = "baker"
        /** Piper zh_CN voices, both 22.05 kHz and small enough to fetch quickly. */
        const val TIMBRE_XIAOYA = "xiaoya"
        const val TIMBRE_CHAOWEN = "chaowen"
        val TIMBRES = listOf(TIMBRE_BAKER, TIMBRE_XIAOYA, TIMBRE_CHAOWEN)
    }

    /** A configured interval can never invert: min ≤ max, both inside range. */
    fun normalized(): PetOptions {
        val min = finite(speechMinSeconds, DEFAULT_MIN_SECONDS).coerceIn(MIN_SECONDS, MAX_SECONDS)
        val max = finite(speechMaxSeconds, DEFAULT_MAX_SECONDS).coerceIn(MIN_SECONDS, MAX_SECONDS)
        return copy(
            size = finite(size, 1f).coerceIn(.6f, 1.6f),
            clickSoundVolume = finite(clickSoundVolume, .7f).coerceIn(0f, 1f),
            speechMinSeconds = minOf(min, max),
            speechMaxSeconds = maxOf(min, max),
            speechSeconds = finite(speechSeconds, DEFAULT_SPEECH_SECONDS)
                .coerceIn(MIN_SPEECH_SECONDS, MAX_SPEECH_SECONDS),
            collisionRestitution = finite(collisionRestitution, DEFAULT_RESTITUTION)
                .coerceIn(0f, 1f),
            chatMemory = chatMemory.coerceIn(MIN_CHAT_MEMORY, MAX_CHAT_MEMORY),
            shakeStrength = finite(shakeStrength, .5f).coerceIn(0f, 1f),
            edgeAllowance = finite(edgeAllowance, DEFAULT_EDGE_ALLOWANCE)
                .coerceIn(MIN_EDGE_ALLOWANCE, MAX_EDGE_ALLOWANCE),
            voiceTimbre = voiceTimbre.takeIf { it in TIMBRES } ?: TIMBRE_BAKER,
            voiceSpeed = finite(voiceSpeed, DEFAULT_VOICE_SPEED)
                .coerceIn(MIN_VOICE_SPEED, MAX_VOICE_SPEED),
        )
    }

    fun speechDelayMs(random: Random = Random.Default): Long {
        val normalized = normalized()
        val low = (normalized.speechMinSeconds * 1000).toLong()
        val high = (normalized.speechMaxSeconds * 1000).toLong()
        return if (high <= low) low else low + random.nextLong(high - low + 1)
    }

    fun speechDurationMs(): Long = (normalized().speechSeconds * 1000).toLong()

    /** The single enable bit for each module; the module list and the user
     *  switches cannot disagree because both read this. */
    fun enables(moduleId: String): Boolean = when (moduleId) {
        ModuleIds.WANDER -> wander
        ModuleIds.SPEECH -> speech
        ModuleIds.CLICK_SOUND -> clickSound
        ModuleIds.COMPANION -> companions
        ModuleIds.COLLISION -> collision
        ModuleIds.CHAT -> chat
        // Either half of the voice feature keeps the module alive: voice mode
        // (speaking every bubble) must not die just because the microphone is off.
        ModuleIds.VOICE -> chat && (voiceChat || voiceSpeak)
        ModuleIds.SHAKE -> shake
        else -> false
    }

    /** A chat module with no endpoint or no key can only tell the user to configure one. */
    val chatConfigured: Boolean
        get() = dsh || (chatApiKey.isNotBlank() && chatBaseUrl.isNotBlank() && chatModel.isNotBlank())

    private fun finite(value: Float, fallback: Float) = if (value.isFinite()) value else fallback
}

class PetPreferences(private val context: Context) {
    companion object {
        // Module enable bits reuse the feature keys that already existed.
        const val KEY_WANDER = "wander"
        const val KEY_SPEECH = "speech"
        const val KEY_SIZE = "size"
        private const val KEY_PHRASES = "phrases"
        private const val KEY_SPEECH_MIN = "speechMin"
        private const val KEY_SPEECH_MAX = "speechMax"
        private const val KEY_SPEECH_SECONDS = "speechSeconds"
        private const val KEY_CLICK_SOUND = "clickSound"
        private const val KEY_CLICK_VOLUME = "clickVolume"
        private const val KEY_CLICK_URI = "clickUri"
        private const val KEY_COMPANIONS = "companions"
        private const val KEY_COMPANION_LAYOUT = "companionLayout"
        private const val KEY_COLLISION = "collision"
        private const val KEY_COLLISION_RESTITUTION = "collisionRestitution"
        private const val KEY_CHAT = "chat"
        private const val KEY_CHAT_BASE_URL = "chatBaseUrl"
        private const val KEY_CHAT_API_KEY = "chatApiKey"
        private const val KEY_CHAT_MODEL = "chatModel"
        private const val KEY_CHAT_SYSTEM = "chatSystem"
        private const val KEY_CHAT_MEMORY = "chatMemory"
        private const val KEY_CHAT_BALANCE = "chatBalance"
        private const val KEY_SHAKE = "shake"
        private const val KEY_SHAKE_STRENGTH = "shakeStrength"
        private const val KEY_DSH = "dsh"
        private const val KEY_DSH_BASE_URL = "dshBaseUrl"
        private const val KEY_DSH_TOKEN = "dshToken"
        private const val KEY_POSITION_X = "x"
        private const val KEY_POSITION_Y = "y"
        private const val KEY_PACK_AUTO = "packAuto"
        private const val KEY_PACK_BASE_URL = "packBaseUrl"
        private const val KEY_EDGE_ALLOWANCE = "edgeAllowance"
        private const val KEY_VOICE_CHAT = "voiceChat"
        private const val KEY_VOICE_SPEAK = "voiceSpeak"
        private const val KEY_VOICE_TIMBRE = "voiceTimbre"
        private const val KEY_VOICE_SPEED = "voiceSpeed"

        val WATCHED_KEYS = setOf(KEY_SIZE, KEY_WANDER, KEY_SPEECH, KEY_PHRASES, KEY_SPEECH_MIN,
            KEY_SPEECH_MAX, KEY_SPEECH_SECONDS, KEY_CLICK_SOUND, KEY_CLICK_VOLUME, KEY_CLICK_URI,
            KEY_COMPANIONS, KEY_COLLISION, KEY_COLLISION_RESTITUTION, KEY_CHAT, KEY_CHAT_BASE_URL,
            KEY_CHAT_API_KEY, KEY_CHAT_MODEL, KEY_CHAT_SYSTEM, KEY_CHAT_MEMORY, KEY_CHAT_BALANCE,
            KEY_PACK_AUTO, KEY_PACK_BASE_URL, KEY_EDGE_ALLOWANCE,
            KEY_VOICE_CHAT, KEY_VOICE_SPEAK, KEY_VOICE_TIMBRE, KEY_VOICE_SPEED)
    }

    private val prefs = context.getSharedPreferences("pet", Context.MODE_PRIVATE)

    /** Whether the user wants the pet running; used to resume after the system kills us. */
    fun setRunning(value: Boolean) = prefs.edit().putBoolean("running", value).apply()

    fun wasRunning(): Boolean = prefs.getBoolean("running", false)

    /** Append one line to files/pet-events.log (best effort, never throws). */
    fun log(line: String) {
        runCatching {
            val file = java.io.File(context.filesDir, "pet-events.log")
            if (file.length() > 256 * 1024) file.delete()
            val stamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
            file.appendText("$stamp $line\n")
        }
    }

    fun options() = PetOptions(
        size = prefs.getFloat(KEY_SIZE, 1f),
        wander = prefs.getBoolean(KEY_WANDER, true),
        speech = prefs.getBoolean(KEY_SPEECH, true),
        phrases = PhraseBook.clean(prefs.getString(KEY_PHRASES, "").orEmpty().split('\n')),
        speechMinSeconds = prefs.getFloat(KEY_SPEECH_MIN, PetOptions.DEFAULT_MIN_SECONDS),
        speechMaxSeconds = prefs.getFloat(KEY_SPEECH_MAX, PetOptions.DEFAULT_MAX_SECONDS),
        speechSeconds = prefs.getFloat(KEY_SPEECH_SECONDS, PetOptions.DEFAULT_SPEECH_SECONDS),
        clickSound = prefs.getBoolean(KEY_CLICK_SOUND, true),
        clickSoundVolume = prefs.getFloat(KEY_CLICK_VOLUME, .7f),
        clickSoundUri = prefs.getString(KEY_CLICK_URI, null),
        companions = prefs.getBoolean(KEY_COMPANIONS, true),
        collision = prefs.getBoolean(KEY_COLLISION, true),
        collisionRestitution = prefs.getFloat(KEY_COLLISION_RESTITUTION,
            PetOptions.DEFAULT_RESTITUTION),
        chat = prefs.getBoolean(KEY_CHAT, false),
        chatBaseUrl = prefs.getString(KEY_CHAT_BASE_URL, null)
            ?.takeIf { it.isNotBlank() } ?: PetOptions.DEFAULT_CHAT_BASE_URL,
        chatApiKey = prefs.getString(KEY_CHAT_API_KEY, null) ?: "",
        chatModel = prefs.getString(KEY_CHAT_MODEL, null)
            ?.takeIf { it.isNotBlank() } ?: PetOptions.DEFAULT_CHAT_MODEL,
        chatSystem = prefs.getString(KEY_CHAT_SYSTEM, null)
            // A stored copy of the *old* default is not a custom persona: it is
            // what the settings screen saved on first open, and keeping it would
            // hide every future improvement to the prompt.
            ?.takeIf { it.isNotBlank() && it != PetOptions.LEGACY_CHAT_SYSTEM }
            ?: PetOptions.DEFAULT_CHAT_SYSTEM,
        chatMemory = prefs.getInt(KEY_CHAT_MEMORY, PetOptions.DEFAULT_CHAT_MEMORY),
        chatBalance = prefs.getBoolean(KEY_CHAT_BALANCE, true),
        shake = prefs.getBoolean(KEY_SHAKE, true),
        shakeStrength = prefs.getFloat(KEY_SHAKE_STRENGTH, .5f),
        dsh = prefs.getBoolean(KEY_DSH, false),
        dshBaseUrl = prefs.getString(KEY_DSH_BASE_URL, null)?.takeIf { it.isNotBlank() }
            ?: PetOptions.DEFAULT_DSH_BASE_URL,
        dshToken = prefs.getString(KEY_DSH_TOKEN, null) ?: "",
        packAuto = prefs.getBoolean(KEY_PACK_AUTO, true),
        packBaseUrl = prefs.getString(KEY_PACK_BASE_URL, null)?.trim() ?: "",
        edgeAllowance = prefs.getFloat(KEY_EDGE_ALLOWANCE, PetOptions.DEFAULT_EDGE_ALLOWANCE),
        voiceChat = prefs.getBoolean(KEY_VOICE_CHAT, true),
        voiceSpeak = prefs.getBoolean(KEY_VOICE_SPEAK, true),
        voiceTimbre = prefs.getString(KEY_VOICE_TIMBRE, PetOptions.TIMBRE_BAKER)
            ?: PetOptions.TIMBRE_BAKER,
        voiceSpeed = prefs.getFloat(KEY_VOICE_SPEED, PetOptions.DEFAULT_VOICE_SPEED),
    ).normalized()

    fun save(options: PetOptions) {
        val normalized = options.normalized()
        prefs.edit()
            .putFloat(KEY_SIZE, normalized.size)
            .putBoolean(KEY_WANDER, normalized.wander)
            .putBoolean(KEY_SPEECH, normalized.speech)
            .putString(KEY_PHRASES, PhraseBook.asText(PhraseBook.clean(normalized.phrases)))
            .putFloat(KEY_SPEECH_MIN, normalized.speechMinSeconds)
            .putFloat(KEY_SPEECH_MAX, normalized.speechMaxSeconds)
            .putFloat(KEY_SPEECH_SECONDS, normalized.speechSeconds)
            .putBoolean(KEY_CLICK_SOUND, normalized.clickSound)
            .putFloat(KEY_CLICK_VOLUME, normalized.clickSoundVolume)
            .putString(KEY_CLICK_URI, normalized.clickSoundUri)
            .putBoolean(KEY_COMPANIONS, normalized.companions)
            .putBoolean(KEY_COLLISION, normalized.collision)
            .putFloat(KEY_COLLISION_RESTITUTION, normalized.collisionRestitution)
            .putBoolean(KEY_CHAT, normalized.chat)
            .putString(KEY_CHAT_BASE_URL, normalized.chatBaseUrl)
            .putString(KEY_CHAT_API_KEY, normalized.chatApiKey)
            .putString(KEY_CHAT_MODEL, normalized.chatModel)
            .putString(KEY_CHAT_SYSTEM, normalized.chatSystem)
            .putInt(KEY_CHAT_MEMORY, normalized.chatMemory)
            .putBoolean(KEY_CHAT_BALANCE, normalized.chatBalance)
            .putBoolean(KEY_SHAKE, normalized.shake)
            .putFloat(KEY_SHAKE_STRENGTH, normalized.shakeStrength)
            .putBoolean(KEY_DSH, normalized.dsh)
            .putString(KEY_DSH_BASE_URL, normalized.dshBaseUrl)
            .putString(KEY_DSH_TOKEN, normalized.dshToken)
            .putBoolean(KEY_PACK_AUTO, normalized.packAuto)
            .putString(KEY_PACK_BASE_URL, normalized.packBaseUrl.trim())
            .putFloat(KEY_EDGE_ALLOWANCE, normalized.edgeAllowance)
            .putBoolean(KEY_VOICE_CHAT, normalized.voiceChat)
            .putBoolean(KEY_VOICE_SPEAK, normalized.voiceSpeak)
            .putString(KEY_VOICE_TIMBRE, normalized.voiceTimbre)
            .putFloat(KEY_VOICE_SPEED, normalized.voiceSpeed)
            .apply()
    }

    /**
     * The pet may rest half off a side or below the bottom edge, so a saved
     * position is a little outside 0..1 on purpose; the engine clamps again on
     * restore, which keeps a rotated or resized screen honest.
     */
    fun position(): Pair<Double, Double> {
        fun coordinate(key: String, fallback: Float) =
            (prefs.getFloat(key, fallback).takeIf { it.isFinite() } ?: fallback)
                .coerceIn(-1f, 2f).toDouble()
        return coordinate(KEY_POSITION_X, .7f) to coordinate(KEY_POSITION_Y, 1f)
    }

    fun savePosition(x: Double, y: Double) {
        if (x.isFinite() && y.isFinite()) {
            prefs.edit().putFloat(KEY_POSITION_X, x.coerceIn(-1.0, 2.0).toFloat())
                .putFloat(KEY_POSITION_Y, y.coerceIn(-1.0, 2.0).toFloat()).apply()
        }
    }

    fun companionLayout(): List<Pair<Double, Double>> =
        CompanionCodec.decode(prefs.getString(KEY_COMPANION_LAYOUT, null))

    fun saveCompanionLayout(layout: List<Pair<Double, Double>>) {
        prefs.edit().putString(KEY_COMPANION_LAYOUT, CompanionCodec.encode(layout)).apply()
    }

    fun reset() = prefs.edit().clear().apply()

    fun subscribe(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unsubscribe(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
}
