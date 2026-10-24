package io.github.dshpet.mobile.modules

import io.github.dshpet.mobile.DshClient
import io.github.dshpet.mobile.ModuleIds
import io.github.dshpet.mobile.PetHandle
import io.github.dshpet.mobile.PhraseBook
import kotlin.random.Random

/**
 * The speech bubble and the self-talk schedule. Disabled, no bubble window is
 * ever created and no per-frame timer is evaluated; the interval and duration
 * come from the phone settings (20–60 s and 3.2 s by default, like the desktop).
 */
class SpeechModule(private val random: Random = Random.Default) : HostBoundModule() {
    override val id = ModuleIds.SPEECH

    private val nextAt = HashMap<Int, Long>()
    private var spoken = 0L

    override fun onFrame(pet: PetHandle, dt: Double) {
        if (pet.isCompanion || pet.engine.paused) return
        // The current animation may be saying its own line right now; two
        // bubbles fighting for the same corner is worse than silence.
        if (host.options.clipLineActive(pet.engine.clip)) return
        val pool = host.effectivePhrases()
        if (pool.isEmpty()) return
        val now = host.now()
        val due = nextAt.getOrPut(pet.id) { now + host.options.speechDelayMs(random) }
        if (now < due) return
        nextAt[pet.id] = now + host.options.speechDelayMs(random)
        speak(pet, pool)
    }

    override fun onEvent(event: PetEvent) {
        if (event !is PetEvent.Clicked || event.pet.isCompanion) return
        // A tap animation that owns its line says it itself (the service shows
        // it as the clip starts), so the pool must not talk over it. With
        // ClickReply.DSH the tap clips have no speaking line and this falls
        // through to the whisper below, which is the whole point of that mode.
        if (host.options.clipLineActive(event.pet.engine.clip)) return
        speak(event.pet, host.effectivePhrases())
    }

    override fun onOptionsChanged() {
        // A new interval or a new pool applies from the next line, not the next session.
        nextAt.clear()
    }

    override fun onDisable() {
        host.pets.forEach { host.clearPhrase(it) }
        nextAt.clear()
    }

    override fun diagnostics() = "spoken=$spoken"

    private fun speak(pet: PetHandle, pool: List<String>) {
        val options = host.options
        if (options.dsh) {
            DshClient.whisperAsync(options.dshBaseUrl, options.dshToken, "main") { line ->
                val text = line ?: PhraseBook.pick(pool, random) ?: return@whisperAsync
                spoken++
                host.showPhrase(pet, text, options.speechDurationMs())
            }
            return
        }
        val line = PhraseBook.pick(pool, random) ?: return
        spoken++
        host.showPhrase(pet, line, options.speechDurationMs())
    }
}
