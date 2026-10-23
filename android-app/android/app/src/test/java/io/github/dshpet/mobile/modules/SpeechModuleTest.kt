package io.github.dshpet.mobile.modules

import io.github.dshpet.mobile.ClipSpec
import io.github.dshpet.mobile.PetEngine
import io.github.dshpet.mobile.PetHandle
import io.github.dshpet.mobile.PetOptions
import io.github.dshpet.mobile.SourceBox
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechModuleTest {
    private fun fixture(options: PetOptions = PetOptions()): Pair<FakeHost, SpeechModule> {
        val host = FakeHost(options)
        host.pets += FakeHost.pet(0)
        host.pets += FakeHost.pet(1)
        val module = SpeechModule(Random(5))
        module.enable(host)
        return host to module
    }

    @Test
    fun aClickShowsOneOfTheUsersLines() {
        val (host, module) = fixture(PetOptions(phrases = listOf("只属于我的台词")))
        module.onEvent(PetEvent.Clicked(host.pets[0]))
        assertEquals(listOf("只属于我的台词"), host.phrases)
    }

    @Test
    fun anEmptyUserPoolFallsBackToTheBuiltInLines() {
        // The pool mechanism still exists; the shipping catalog simply has no
        // built-in lines any more, so in the app this branch says nothing.
        val (host, module) = fixture(PetOptions(phrases = emptyList()))
        module.onEvent(PetEvent.Clicked(host.pets[0]))
        assertEquals(listOf("好女孩……"), host.phrases)
    }

    @Test
    fun aClipWithItsOwnLineKeepsThePoolQuiet() {
        val host = FakeHost(PetOptions(phrases = listOf("池子里的台词")))
        val pet = PetHandle(0, PetEngine(listOf(
            ClipSpec("idle", "idle", 24.0, 4, crop = SourceBox(0.0, 0.0, 10.0, 10.0)),
            ClipSpec("click", "click", 24.0, 4, crop = SourceBox(0.0, 0.0, 10.0, 10.0),
                line = "别戳了！"),
        )))
        host.pets += pet
        val module = SpeechModule(Random(5))
        module.enable(host)
        // The tap animation carries a line: the service shows that one, so the
        // pool must not add a second bubble on top of it.
        pet.engine.click()
        module.onEvent(PetEvent.Clicked(pet))
        module.onFrame(pet, .016)
        assertEquals(0, host.phrases.size)
    }

    @Test
    fun nothingIsShownWhenEvenTheBuiltInsAreGone() {
        val host = FakeHost(PetOptions())
        host.builtinPhrases.clear()
        val module = SpeechModule(Random(5))
        module.enable(host)
        module.onFrame(FakeHost.pet(), .016)
        module.onEvent(PetEvent.Clicked(FakeHost.pet()))
        assertEquals(0, host.phrases.size)
    }

    @Test
    fun theConfiguredGapGatesTheTimedLines() {
        val (host, module) = fixture(PetOptions(speechMinSeconds = 10f, speechMaxSeconds = 10f))
        module.onFrame(host.pets[0], .016)
        assertEquals(0, host.phrases.size)
        host.clock += 9_000
        module.onFrame(host.pets[0], .016)
        assertEquals(0, host.phrases.size)
        host.clock += 1_500
        module.onFrame(host.pets[0], .016)
        assertEquals(1, host.phrases.size)
    }

    @Test
    fun companionsStayQuiet() {
        val (host, module) = fixture()
        module.onEvent(PetEvent.Clicked(host.pets[1]))
        module.onFrame(host.pets[1], .016)
        assertEquals(0, host.phrases.size)
    }

    @Test
    fun disablingClearsWhatIsOnScreenAndForgetsTheSchedule() {
        val (host, module) = fixture(PetOptions(speechMinSeconds = 5f, speechMaxSeconds = 5f))
        module.onFrame(host.pets[0], .016)
        host.clock += 6_000
        module.onFrame(host.pets[0], .016)
        assertEquals(1, host.phrases.size)
        module.disable()
        assertTrue(host.cleared.containsAll(listOf(0, 1)))
    }

    @Test
    fun aNewIntervalAppliesFromTheNextLine() {
        val (host, module) = fixture(PetOptions(speechMinSeconds = 600f, speechMaxSeconds = 600f))
        module.onFrame(host.pets[0], .016)
        host.clock += 601_000
        module.onFrame(host.pets[0], .016)
        assertEquals(1, host.phrases.size)
        host.options = host.options.copy(speechMinSeconds = 5f, speechMaxSeconds = 5f)
        module.onOptionsChanged()
        module.onFrame(host.pets[0], .016)
        assertEquals(1, host.phrases.size)
        host.clock += 6_000
        module.onFrame(host.pets[0], .016)
        assertEquals(2, host.phrases.size)
    }
}
