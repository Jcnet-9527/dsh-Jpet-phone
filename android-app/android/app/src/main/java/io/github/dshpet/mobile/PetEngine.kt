package io.github.dshpet.mobile

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tanh
import kotlin.random.Random

enum class PetMode { IDLE, TURN, MOVE, CLICK, DRAG, THROWN }

data class ThrowStep(val x: Double, val y: Double, val vx: Double, val vy: Double, val bounced: Boolean)

/** Source-pixel units, matching pet.physics.throw_step's public behavior. */
object ThrowMath {
    /**
     * [left] and [top] default to zero for the desktop's reference behaviour;
     * the phone passes a negative left and a bottom past the screen edge so the
     * pet may hang off either side and off the bottom.
     */
    fun step(x: Double, y: Double, vx: Double, vy: Double, dt: Double,
             right: Double, bottom: Double, left: Double = 0.0, top: Double = 0.0): ThrowStep {
        var nx = x + vx * dt
        var nvy = vy + 1400.0 * dt
        var ny = y + nvy * dt
        var nvx = vx
        var bounced = false
        if (nx < left) { nx = left; nvx = abs(nvx) * .68; bounced = true }
        else if (nx > right) { nx = right; nvx = -abs(nvx) * .68; bounced = true }
        if (ny < top) { ny = top; nvy = abs(nvy) * .68; bounced = true }
        else if (ny >= bottom) {
            ny = bottom
            nvx *= max(0.0, 1 - 2.5 * dt)
            nvy = if (abs(nvy) < 120.0) 0.0 else -abs(nvy) * .68
            bounced = true
        }
        return ThrowStep(nx, ny, nvx, nvy, bounced)
    }
}

/** The service owns the clock; this model has no threads, Android calls, or I/O. */
class PetEngine(clips: List<ClipSpec>, private val random: Random = Random.Default) {
    private val groups = clips.groupBy { it.group }
    var clip: ClipSpec = groups.getValue("idle").first()
        private set
    var mode = PetMode.IDLE
        private set
    var x = 0.0
        private set
    var y = 0.0
        private set
    var vx = 0.0
        private set
    var vy = 0.0
        private set
    var facingRight = false
        private set
    var paused = false
    var wander = true

    /**
     * Clips whose frames are on the device. Null means "everything is local",
     * which is what the unit tests and a fully bundled build rely on.
     */
    var availability: ((ClipSpec) -> Boolean)? = null

    /** Called when a clip was wanted but is not downloaded yet. */
    var onMissing: ((ClipSpec) -> Unit)? = null

    private var right = 0.0
    private var bottom = 0.0
    /** How far the body may hang off the left/right edge (half a body by default). */
    private var allowX = 0.0
    /** How far the body may sink below the bottom edge; the top is never clipped. */
    private var allowBottom = 0.0
    private var elapsed = 0.0
    private var moveStart = 0.0
    private var moveTarget = 0.0
    private var pendingMove: ClipSpec? = null
    val normalizedX get() = if (right > 0) (x / right).coerceIn(-1.0, 2.0) else 0.0
    val normalizedY get() = if (bottom > 0) (y / bottom).coerceIn(-1.0, 2.0) else 0.0
    val frameIndex get() = (elapsed * clip.fps).toInt().coerceIn(0, clip.frames - 1)

    /** Parks the pet: used when the wander module is switched off mid-stride. */
    fun stopWandering() {
        wander = false
        if (mode == PetMode.MOVE || mode == PetMode.TURN) idle()
    }

    /** Source-pixel placement, for a freshly spawned companion. */
    fun placeAt(bodyX: Double, bodyY: Double) {
        if (bodyX.isFinite()) x = clampX(bodyX)
        if (bodyY.isFinite()) y = clampY(bodyY)
    }

    /** Position-only separation from a collision that carried no usable impulse. */
    fun shift(dx: Double, dy: Double) {
        if (paused) return
        if (dx.isFinite()) x = clampX(x + dx)
        if (dy.isFinite()) y = clampY(y + dy)
    }

    /** A real collision hit: move, take the impulse, and let gravity take over. */
    fun nudge(dx: Double, dy: Double, dvx: Double, dvy: Double) {
        if (paused || mode == PetMode.DRAG) return
        shift(dx, dy)
        if (!vx.isFinite() || !vy.isFinite() || !dvx.isFinite() || !dvy.isFinite()) return
        vx += dvx
        vy += dvy
        facingRight = vx > 0
        if (mode != PetMode.THROWN) enter(PetMode.THROWN, pick("drag"))
    }

    /**
     * [allowX] / [allowBottom] let the body hang off the sides and off the
     * bottom; the top stays hard-clamped so the pet is never half off-screen at
     * the top, where its head and its bubble would be cut off.
     */
    fun resize(width: Double, height: Double, allowX: Double = 0.0, allowBottom: Double = 0.0) {
        val rx = normalizedX; val ry = normalizedY
        right = width.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
        bottom = height.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
        this.allowX = allowX.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
        this.allowBottom = allowBottom.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
        restorePosition(rx, ry)
        if (mode == PetMode.MOVE || mode == PetMode.TURN || mode == PetMode.DRAG) idle()
    }

    fun restorePosition(normalizedX: Double, normalizedY: Double) {
        x = clampX((normalizedX.takeIf { it.isFinite() } ?: .7) * right)
        y = clampY((normalizedY.takeIf { it.isFinite() } ?: 1.0) * bottom)
    }

    private fun clampX(value: Double) = value.coerceIn(-allowX, right + allowX)
    private fun clampY(value: Double) = value.coerceIn(0.0, bottom + allowBottom)

    /**
     * Picks a clip the device can actually play. When nothing in the group is
     * downloaded yet the missing one is reported and a bundled clip plays
     * instead, so the pet never stalls on a network request.
     */
    private fun pick(group: String): ClipSpec {
        val pool = groups.getValue(group)
        val check = availability
        val ready = if (check == null) pool else pool.filter(check)
        if (ready.isNotEmpty()) return ready.random(random)
        requestMissing(pool)
        return pool.firstOrNull { it.bundled } ?: pool.random(random)
    }

    private fun requestMissing(pool: List<ClipSpec>) {
        val check = availability ?: return
        val absent = pool.filter { !check(it) }
        if (absent.isNotEmpty()) onMissing?.invoke(absent.random(random))
    }

    private fun enter(next: PetMode, animation: ClipSpec) {
        mode = next; clip = animation; elapsed = 0.0
    }
    private fun idle() {
        vx = 0.0; vy = 0.0; pendingMove = null
        enter(PetMode.IDLE, pick("idle"))
    }
    /**
     * Plays a fresh random idle clip right away. The idle pool holds every
     * play/小动作 animation, so this is the "do something" entry; a drag or a
     * throw is left alone.
     */
    fun playRandom() {
        if (mode == PetMode.DRAG || mode == PetMode.THROWN) return
        idle()
    }

    /**
     * Plays one chosen animation - the action menu's entry point. A walk clip
     * really walks (away from the nearer edge), everything else plays once in
     * place and then returns to the idle cycle.
     */
    fun play(chosen: ClipSpec) {
        if (mode == PetMode.DRAG || mode == PetMode.THROWN) return
        vx = 0.0; vy = 0.0; pendingMove = null
        if (chosen.group == "move") {
            val direction = if (x <= right * .5) 1 else -1
            moveStart = x
            moveTarget = clampX(x + direction * chosen.stride)
            facingRight = moveTarget > x
            enter(PetMode.MOVE, chosen)
            return
        }
        enter(PetMode.IDLE, chosen)
    }

    fun click() { vx = 0.0; vy = 0.0; pendingMove = null; enter(PetMode.CLICK, pick("click")) }
    fun beginDrag() { vx = 0.0; vy = 0.0; pendingMove = null; enter(PetMode.DRAG, pick("drag")) }
    fun cancelDrag() { if (mode == PetMode.DRAG) idle() }
    fun dragTo(bodyX: Double, bodyY: Double) {
        if (mode != PetMode.DRAG) return
        if (bodyX.isFinite()) x = clampX(bodyX)
        if (bodyY.isFinite()) y = clampY(bodyY)
    }
    fun release(velocityX: Double, velocityY: Double) {
        if (mode != PetMode.DRAG) return
        val speed = hypot(velocityX, velocityY)
        if (paused || !speed.isFinite() || speed < 500) { idle(); return }
        val capped = 4800 * tanh(speed / 4800)
        vx = velocityX * capped / speed
        vy = velocityY * capped / speed
        facingRight = vx > 0
        enter(PetMode.THROWN, pick("drag"))
    }

    fun advance(seconds: Double) {
        if (paused || !seconds.isFinite() || seconds <= 0) return
        val dt = min(seconds, .05)
        elapsed += dt
        when (mode) {
            PetMode.DRAG -> elapsed %= clip.duration
            PetMode.THROWN -> {
                elapsed %= clip.duration
                val step = ThrowMath.step(x, y, vx, vy, dt, right + allowX, bottom + allowBottom,
                    -allowX, 0.0)
                x = step.x; y = step.y; vx = step.vx; vy = step.vy
                if (abs(vx) > 15) facingRight = vx > 0
                if ((y >= bottom - 1 && abs(vy) < 1 && abs(vx) < 15) ||
                    (step.bounced && hypot(vx, vy) < 40 && abs(vy) < 1)) idle()
            }
            PetMode.MOVE -> {
                if (!wander) { idle(); return }
                val phase = (elapsed / clip.duration).coerceIn(0.0, 1.0)
                val progress = if (clip.curve.size < 2) phase else {
                    val offset = phase * (clip.curve.size - 1)
                    val i = offset.toInt()
                    val a = clip.curve[i]
                    val b = clip.curve[min(i + 1, clip.curve.lastIndex)]
                    a + (b - a) * (offset - i)
                }
                x = clampX(moveStart + (moveTarget - moveStart) * progress)
                if (elapsed >= clip.duration) idle()
            }
            PetMode.CLICK -> if (elapsed >= clip.duration) idle()
            PetMode.TURN -> if (elapsed >= clip.duration) {
                val next = pendingMove
                if (next != null && wander) {
                    facingRight = moveTarget > x
                    moveStart = x
                    enter(PetMode.MOVE, next)
                } else idle()
            }
            PetMode.IDLE -> if (elapsed >= clip.duration) {
                if (!wander || right == 0.0 || random.nextDouble() < .6) idle()
                else {
                    val animation = pick("move")
                    val direction = if (x < right * .2) 1 else if (x > right * .8) -1 else if (random.nextBoolean()) 1 else -1
                    moveTarget = clampX(x + direction * animation.stride)
                    moveStart = x
                    pendingMove = animation
                    if ((moveTarget > x) != facingRight) enter(PetMode.TURN, pick("turn"))
                    else enter(PetMode.MOVE, animation)
                }
            }
        }
    }
}
