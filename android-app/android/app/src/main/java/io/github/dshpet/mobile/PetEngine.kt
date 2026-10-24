package io.github.dshpet.mobile

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

enum class PetMode { IDLE, TURN, MOVE, CLICK, DRAG, THROWN }

/** The stages of one visit to the screen edge. */
enum class WallPhase { NONE, WALK, GRAB, CLIMB_UP, TOP_PAUSE, PEEK_OUT, PEEK_HOLD, PEEK_BACK, RELEASE, CLIMB_DOWN, LEAVE }

/** What the pet does while it watches from behind the edge. */
enum class PeekAction { NORMAL, TILT, WAVE, BLINK, LOOK, HIDE, SHAKE }

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

    /**
     * What the pet picks for itself: every animation except the extra-wide
     * effects, which the stage is too small for. They are still playable - that
     * is what [play] is for - they just never turn up by surprise.
     */
    private val pools = clips.filter { !it.fx }.groupBy { it.group }
    var clip: ClipSpec = (pools["idle"] ?: groups.getValue("idle")).first()
        private set

    /**
     * Bumped every time an animation starts, even when it is the same clip that
     * was already playing. The service watches this to show the line a clip
     * carries: comparing clip ids would miss "play that one again".
     */
    var clipStarts: Long = 0L
        private set

    /**
     * 0 = free, -1 = on the left edge, +1 on the right one.
     *
     * A visit is a small performance, not a position: walk to the edge, grab,
     * climb in discrete steps, lean out to peek, watch, then climb back down.
     * [wallPhase] says where in that performance the pet is; [wallLean] is the
     * screen-space head tilt used while peeking. Dragging it or [leaveWall]
     * ends the performance and hands the pet back to gravity.
     */
    var wallSide: Int = 0
        private set

    var wallPhase = WallPhase.NONE
        private set

    /** Screen-space lean in radians (positive = clockwise), used while peeking. */
    var wallLean = 0.0
        private set

    private var wallWalkX = 0.0
    private var wallClimbX = 0.0
    private var wallPeekX = 0.0
    private var wallTargetY = 0.0

    /** The sprite's own height, so a peeking pet stays inside the middle band. */
    private var wallBodyHeight = 0.0
    private var wallTimer = 0.0
    private var wallStay = 0.0
    private var wallLeanTarget = 0.0
    private var wallStepFrom = 0.0
    private var wallStepTo = 0.0
    private var wallStepClock = 0.0
    private var wallStepDuration = 0.0
    private var wallPeekClock = 0.0

    /** Extra lean while a tap is answered: it leans further out, then settles. */
    private var wallLeanBoost = 0.0

    /** Debug hooks: a fixed watch length and a forced routine, 0/null = normal. */
    private var wallStayOverride = 0
    private var forcedPeekAction: PeekAction? = null

    /** The little show it puts on while watching; picked per visit. */
    var peekAction = PeekAction.NORMAL
        private set
    private var peekActionClock = 0.0
    private var peekWaveOn = false

    /** Bumped every time the pet settles after a fall, so the view can squash. */
    var landings: Long = 0L
        private set

    /** A sub-range of the current clip to loop over, in seconds; 0/0 means the whole clip. */
    private var loopStart = 0.0
    private var loopEnd = 0.0
    var mode = PetMode.IDLE
        private set
    var x = 0.0
        private set
    var y = 0.0
        private set
    var vx = 0.0
        private set

    /** Visual lean in radians (positive = clockwise), driven by the shake module. */
    var tilt = 0.0
    var tiltVelocity = 0.0

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
        // A wall pet is holding on: collisions must not knock it off.
        if (paused || mode == PetMode.DRAG || wallSide != 0) return
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
        // A hanging pet sits past the edge on purpose: resizing must not pull it
        // back inside the screen, only re-solve its height.
        if (wallSide != 0) y = clampY((ry.takeIf { it.isFinite() } ?: 1.0) * bottom)
        else restorePosition(rx, ry)
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
        val pool = pools[group]?.takeIf { it.isNotEmpty() } ?: groups.getValue(group)
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

    /** Walking pace towards the edge, source px per second. */
    private val WALK_SPEED = 330.0

    /** The hop off the wall: sideways push and upward kick, source units. */
    private val JUMP_VX = 220.0
    private val JUMP_VY = 170.0

    /** Inward shove when a pet lets go, source pixels per second. */
    private val WALL_EJECT = 260.0

    private fun enter(next: PetMode, animation: ClipSpec) {
        mode = next; clip = animation; elapsed = 0.0; clipStarts++
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

    fun click() {
        // A tap while peeking makes the pet lean a little further out before
        // it settles back into watching.
        if (wallSide != 0 && (wallPhase == WallPhase.PEEK_OUT || wallPhase == WallPhase.PEEK_HOLD)) {
            wallLeanBoost = .10
        }
        vx = 0.0; vy = 0.0; pendingMove = null
        enter(PetMode.CLICK, pick("click"))
    }

    /**
     * Starts one visit to the edge. [walkX] is where the body stands when it
     * reaches the edge, [climbX] where it sits while climbing (most of the body
     * behind the edge) and [peekX] the tucked-in spot it leans out from.
     * The caller converts screen geometry into source units.
     */
    fun startWall(side: Int, walkX: Double, climbX: Double, peekX: Double, bodyHeight: Double,
                  staySeconds: Int = 0, forcedPeek: PeekAction? = null) {
        // A held pet cannot start climbing; anything else (walk, idle, throw) can.
        if (mode == PetMode.DRAG) return
        wallSide = if (side < 0) -1 else 1
        wallPhase = WallPhase.WALK
        if (walkX.isFinite()) wallWalkX = walkX
        if (climbX.isFinite()) wallClimbX = climbX
        if (peekX.isFinite()) wallPeekX = peekX
        if (bodyHeight.isFinite() && bodyHeight > 0) wallBodyHeight = bodyHeight
        wallLean = 0.0; wallLeanTarget = 0.0
        wallStayOverride = staySeconds.coerceIn(0, 600)
        forcedPeekAction = forcedPeek
        wallStepDuration = 0.0
        vx = 0.0; vy = 0.0; pendingMove = null
        // It walks there: moving left means facing left.
        facingRight = wallSide > 0
        // The run clip turns to the side and runs for a couple of seconds, then
        // trips. Loop only its running section so the walk never falls over.
        val run = namedClip("move", "奔跑")
        if (run != null && run.duration > 3.8) enterLoop(PetMode.MOVE, run, 2.1, 3.7)
        else enter(PetMode.MOVE, run ?: pick("move"))
    }

    /**
     * A height for the head that keeps the whole sprite in the middle 25%-55%
     * of the screen, so it still has floor to fall back to.
     */
    private fun nextWallY(): Double {
        val screenHeight = bottom + wallBodyHeight
        return clampY(screenHeight * (.25 + random.nextDouble() * .30))
    }

    private fun leanSign(): Double = if (wallSide < 0) 1.0 else -1.0

    /** Moves [current] towards [target] by at most [maxDelta]. */
    private fun ease(current: Double, target: Double, maxDelta: Double): Double {
        val delta = target - current
        return if (abs(delta) <= maxDelta) target
        else current + if (delta > 0) maxDelta else -maxDelta
    }

    /** Picks a named clip from a group when it is on the device, else null. */
    private fun namedClip(group: String, word: String): ClipSpec? {
        val pool = pools[group] ?: return null
        val check = availability
        return pool.firstOrNull { it.label.contains(word) && (check == null || check(it)) }
    }

    private fun dragClip(): ClipSpec = namedClip("drag", "悬空") ?: pick("drag")

    private fun turnClip(): ClipSpec = namedClip("turn", "东张西望") ?: pick("turn")

    private fun waveClip(): ClipSpec? = namedClip("click", "挥手")

    private fun enterLoop(next: PetMode, animation: ClipSpec, start: Double, endSec: Double) {
        val s = start.coerceIn(0.0, animation.duration)
        val e = endSec.coerceIn(s, animation.duration)
        if (e - s > .2) {
            loopStart = s; loopEnd = e
        } else {
            loopStart = 0.0; loopEnd = 0.0
        }
        mode = next; clip = animation; elapsed = loopStart; clipStarts++
    }

    /** Advances the current clip, honouring an optional loop sub-range. */
    private fun advanceClip(dt: Double) {
        elapsed += dt
        if (loopEnd > loopStart) {
            if (elapsed >= loopEnd) {
                val span = loopEnd - loopStart
                elapsed = loopStart + (elapsed - loopEnd) % span
            }
        } else if (elapsed >= clip.duration) {
            elapsed %= clip.duration
        }
    }

    /** The clip that belongs to the current phase, used when a tap finishes. */
    private fun wallClipForPhase(): ClipSpec = when (wallPhase) {
        WallPhase.WALK, WallPhase.CLIMB_UP -> namedClip("move", "奔跑") ?: pick("move")
        WallPhase.PEEK_OUT, WallPhase.PEEK_HOLD, WallPhase.PEEK_BACK -> turnClip()
        else -> dragClip()
    }

    private fun resumeWallClip() {
        val c = wallClipForPhase()
        when {
            c.group == "move" && c.duration > 3.8 -> enterLoop(PetMode.IDLE, c, 2.1, 3.7)
            c.group == "turn" -> enterLoop(PetMode.IDLE, c, 0.0, c.duration)
            else -> enter(PetMode.IDLE, c)
        }
    }

    private fun resetWall() {
        wallSide = 0
        wallPhase = WallPhase.NONE
        wallLean = 0.0; wallLeanTarget = 0.0
        wallStepDuration = 0.0
        loopStart = 0.0; loopEnd = 0.0
    }

    /** Lets go: [fall] hands the pet back to gravity, otherwise it stays put. */
    fun leaveWall(fall: Boolean = true) {
        if (wallSide == 0) return
        // Letting go of the edge throws the pet a little towards the middle:
        // falling straight down would land it still half outside the screen.
        val eject = if (wallSide < 0) WALL_EJECT else -WALL_EJECT
        resetWall()
        vx = if (fall) eject else 0.0; vy = 0.0
        if (fall) enter(PetMode.THROWN, pick("drag")) else idle()
    }

    /** One frame of the whole performance. */
    private fun advanceWall(dt: Double) {
        wallLeanBoost = (wallLeanBoost - dt * .08).coerceAtLeast(0.0)
        wallLean = ease(wallLean, wallLeanTarget, dt * 8.0)
        if (mode == PetMode.CLICK) {
            // A tap plays once, then the performance picks up where it was.
            elapsed += dt
            if (elapsed >= clip.duration) resumeWallClip()
            return
        }
        when (wallPhase) {
            WallPhase.WALK -> advanceWallWalk(dt)
            WallPhase.GRAB -> advanceWallGrab(dt)
            WallPhase.CLIMB_UP -> advanceWallClimb(dt, up = true)
            WallPhase.TOP_PAUSE -> advanceWallTopPause(dt)
            WallPhase.PEEK_OUT -> advanceWallPeekOut(dt)
            WallPhase.PEEK_HOLD -> advanceWallPeekHold(dt)
            WallPhase.PEEK_BACK -> advanceWallPeekBack(dt)
            WallPhase.RELEASE -> advanceWallRelease(dt)
            WallPhase.CLIMB_DOWN -> advanceWallClimb(dt, up = false)
            WallPhase.LEAVE -> advanceWallLeave(dt)
            WallPhase.NONE -> resetWall()
        }
    }

    /** Walks along the floor to the edge instead of being teleported there. */
    private fun advanceWallWalk(dt: Double) {
        val dx = wallWalkX - x
        val stride = WALK_SPEED * dt
        x = if (abs(dx) <= stride) wallWalkX else x + if (dx > 0) stride else -stride
        y = ease(y, bottom, dt * 900.0)
        advanceClip(dt)
        if (abs(wallWalkX - x) < 0.6) {
            x = wallWalkX
            // Stop, turn to the screen and reach for the edge before climbing.
            wallPhase = WallPhase.GRAB
            wallTimer = .38
            facingRight = wallSide < 0
            enter(PetMode.IDLE, dragClip())
        }
    }

    /** Swings the body behind the edge, then starts the first step. */
    private fun advanceWallGrab(dt: Double) {
        wallTimer -= dt
        x = ease(x, wallClimbX, dt * 420.0)
        advanceClip(dt)
        if (wallTimer <= 0.0) {
            wallTargetY = nextWallY()
            wallPhase = WallPhase.CLIMB_UP
            wallStepDuration = 0.0
            // Climbing is shown with the running cycle seen from the side: legs
            // and arms move while the body scrambles up the edge. It keeps
            // facing into the screen so the face stays visible.
            val run = namedClip("move", "奔跑")
            if (run != null && run.duration > 3.8) enterLoop(PetMode.IDLE, run, 2.1, 3.7)
            else enter(PetMode.IDLE, dragClip())
            startStep(up = true)
        }
    }

    /**
     * One grab-and-pull. Position only changes while a step is in flight; the
     * gaps between steps are what make it read as climbing instead of rising.
     */
    private fun startStep(up: Boolean) {
        val reach = if (up) 12.0 + random.nextDouble() * 10.0
        else 16.0 + random.nextDouble() * 10.0
        wallStepFrom = y
        wallStepTo = if (up) max(wallTargetY, y - reach) else min(bottom, y + reach)
        wallStepClock = 0.0
        wallStepDuration = if (up) .09 + random.nextDouble() * .07
        else .07 + random.nextDouble() * .05
        wallTimer = if (up) .14 + random.nextDouble() * .16
        else .09 + random.nextDouble() * .12
        // Not every pull is followed by the next one immediately: sometimes it
        // hangs there for a moment to catch its breath.
        if (random.nextDouble() < .18) wallTimer += .08 + random.nextDouble() * .18
        wallLeanTarget = (if (random.nextBoolean()) .02 else -.02) * leanSign()
        x = wallClimbX + (random.nextDouble() - .5) * 2.0
    }

    private fun advanceWallClimb(dt: Double, up: Boolean) {
        advanceClip(dt)
        if (wallStepDuration > 0.0) {
            wallStepClock += dt
            val t = (wallStepClock / wallStepDuration).coerceAtMost(1.0)
            val eased = t * t * (3.0 - 2.0 * t)
            y = wallStepFrom + (wallStepTo - wallStepFrom) * eased
            if (t >= 1.0) {
                y = wallStepTo
                wallStepDuration = 0.0
                wallLeanTarget = 0.0
            }
            return
        }
        wallTimer -= dt
        if (wallTimer > 0.0) return
        if (up) {
            if (y <= wallTargetY + 1.0) {
                y = wallTargetY
                // Reached the spot: stop scrambling, hold on, settle, then peek.
                wallPhase = WallPhase.TOP_PAUSE
                wallTimer = .35 + random.nextDouble() * .45
                wallLeanTarget = 0.0
                enter(PetMode.IDLE, dragClip())
            } else startStep(up = true)
        } else {
            if (y >= bottom - 1.0) {
                y = bottom
                wallPhase = WallPhase.LEAVE
                wallLeanTarget = 0.0
            } else startStep(up = false)
        }
    }

    private fun advanceWallTopPause(dt: Double) {
        wallTimer -= dt
        x = ease(x, wallPeekX, dt * 240.0)
        advanceClip(dt)
        if (wallTimer <= 0.0) {
            wallPhase = WallPhase.PEEK_OUT
            wallTimer = 0.0
            // Turn away from the wall and look at the user before leaning out.
            facingRight = wallSide < 0
            val turn = turnClip()
            enterLoop(PetMode.IDLE, turn, 0.0, turn.duration)
        }
    }

    /** Two small leans with a pause between, then the full peek. */
    private fun advanceWallPeekOut(dt: Double) {
        wallTimer += dt
        val target = when {
            wallTimer < .26 -> .10
            wallTimer < .52 -> .26
            wallTimer < .78 -> .40
            else -> .46
        }
        wallLeanTarget = (target + wallLeanBoost) * leanSign()
        advanceClip(dt)
        if (wallTimer >= 1.05) {
            wallPhase = WallPhase.PEEK_HOLD
            wallTimer = if (wallStayOverride > 0) wallStayOverride.toDouble()
            else 30.0 + random.nextDouble() * 60.0
            wallPeekClock = 0.0
            startPeekAction()
        }
    }

    /**
     * Watching. Every visit picks one small routine from the pool; after it
     * finishes the pet settles back into the plain breath-and-look loop.
     */
    private fun advanceWallPeekHold(dt: Double) {
        wallTimer -= dt
        wallPeekClock += dt
        peekActionClock += dt
        wallLeanTarget = (peekLeanTarget() + wallLeanBoost) * leanSign()
        advanceClip(dt)
        if (peekAction == PeekAction.WAVE) {
            if (!peekWaveOn && peekActionClock > .8) {
                peekWaveOn = true
                val wave = waveClip()
                if (wave != null) enterLoop(PetMode.IDLE, wave, 2.6, 4.2)
            }
            if (peekWaveOn && peekActionClock > 3.6) {
                peekWaveOn = false
                peekAction = PeekAction.NORMAL
                val turn = turnClip()
                enterLoop(PetMode.IDLE, turn, 0.0, turn.duration)
            }
        } else if (peekAction != PeekAction.NORMAL && peekActionClock > peekActionLength()) {
            peekAction = PeekAction.NORMAL
        }
        if (wallTimer <= 0.0) {
            wallPhase = WallPhase.PEEK_BACK
            wallTimer = .55
            wallLeanTarget = 0.0
        }
    }

    private fun startPeekAction() {
        peekAction = forcedPeekAction ?: pickPeekAction()
        peekActionClock = 0.0
        peekWaveOn = false
    }

    private fun pickPeekAction(): PeekAction {
        val r = random.nextDouble() * 100.0
        return when {
            r < 30.0 -> PeekAction.NORMAL
            r < 45.0 -> PeekAction.TILT
            r < 60.0 -> PeekAction.WAVE
            r < 75.0 -> PeekAction.BLINK
            r < 85.0 -> PeekAction.LOOK
            r < 95.0 -> PeekAction.HIDE
            else -> PeekAction.SHAKE
        }
    }

    private fun peekActionLength(): Double = when (peekAction) {
        PeekAction.TILT -> 3.4
        PeekAction.BLINK -> 2.0
        PeekAction.LOOK -> 4.0
        PeekAction.HIDE -> 2.4
        PeekAction.SHAKE -> 2.2
        else -> 0.0
    }

    /** Target lean for the current little routine, in radians. */
    private fun peekLeanTarget(): Double {
        val t = peekActionClock
        val base = .46 + .012 * sin(wallPeekClock * 1.3)
        return when (peekAction) {
            PeekAction.NORMAL -> base
            PeekAction.TILT -> when {
                t < .8 -> base
                t < 2.2 -> .62
                t < 3.4 -> .30
                else -> base
            }
            PeekAction.WAVE -> base
            PeekAction.BLINK -> when {
                t < 1.0 -> base
                t < 1.25 -> base - .07
                t < 1.45 -> base
                t < 1.7 -> base - .07
                else -> base
            }
            PeekAction.LOOK -> base + .06 * sin(t * 2.4)
            PeekAction.HIDE -> when {
                t < .45 -> base
                t < .8 -> .10
                t < 1.9 -> .10
                t < 2.4 -> base
                else -> base
            }
            PeekAction.SHAKE -> base + .035 * sin(t * 9.0)
        }
    }

    /** Withdrawing is its own beat: the head comes back in, then it lets go. */
    private fun advanceWallPeekBack(dt: Double) {
        wallTimer -= dt
        wallLean = ease(wallLean, 0.0, dt * 1.6)
        advanceClip(dt)
        if (wallTimer <= 0.0) {
            wallLean = 0.0
            wallPhase = WallPhase.RELEASE
            wallTimer = .16
        }
    }

    /** A beat of hanging by the fingertips, then it pushes off the wall. */
    private fun advanceWallRelease(dt: Double) {
        wallTimer -= dt
        wallLean = ease(wallLean, 0.0, dt * 10.0)
        advanceClip(dt)
        if (wallTimer <= 0.0) jumpOffWall()
    }

    /**
     * Jumping down from the height it peeked at: a short push away from the
     * edge and up, then ordinary gravity carries it to the floor.
     */
    private fun jumpOffWall() {
        val side = wallSide
        val inward = if (side < 0) JUMP_VX else -JUMP_VX
        resetWall()
        vx = inward
        vy = -JUMP_VY
        facingRight = side < 0
        enter(PetMode.THROWN, dragClip())
    }

    private fun advanceWallLeave(dt: Double) {
        x = ease(x, wallWalkX, dt * 260.0)
        advanceClip(dt)
        if (abs(x - wallWalkX) < 1.0) {
            x = wallWalkX
            resetWall()
            vx = 0.0; vy = 0.0
            idle()
        }
    }

    /**
     * The opening animation. The constructor takes the first idle clip blindly,
     * which only happens to be right while that clip ships inside the APK;
     * once availability is known this re-picks one the device can really play.
     */
    fun bootstrap() {
        elapsed = 0.0
        enter(PetMode.IDLE, pick("idle"))
    }
    fun beginDrag() {
        // A finger landing on a peeking pet may still be a tap: do not tear the
        // performance down yet. The first real movement (dragTo) detaches it.
        if (wallSide != 0) return
        vx = 0.0; vy = 0.0; pendingMove = null; enter(PetMode.DRAG, pick("drag"))
    }
    fun cancelDrag() { if (mode == PetMode.DRAG) idle() }
    fun dragTo(bodyX: Double, bodyY: Double) {
        if (wallSide != 0) {
            // Pulled off the edge: the whole performance ends here.
            resetWall()
            vx = 0.0; vy = 0.0; pendingMove = null
            enter(PetMode.DRAG, pick("drag"))
        }
        if (mode != PetMode.DRAG) return
        if (bodyX.isFinite()) x = clampX(bodyX)
        if (bodyY.isFinite()) y = clampY(bodyY)
    }
    fun release(velocityX: Double, velocityY: Double) {
        if (mode != PetMode.DRAG) return
        if (paused) { idle(); return }
        val speed = hypot(velocityX, velocityY)
        if (!speed.isFinite()) { idle(); return }
        // Letting go in the air always falls: a gentle drop used to leave the
        // pet floating where the finger left it.
        if (speed < 500 && y >= bottom - 1.0) { idle(); return }
        val capped = if (speed > 0) 4800 * tanh(speed / 4800) else 0.0
        vx = if (speed > 0) velocityX * capped / speed else 0.0
        vy = if (speed > 0) velocityY * capped / speed else 0.0
        facingRight = vx > 0
        enter(PetMode.THROWN, pick("drag"))
    }

    fun advance(seconds: Double) {
        if (paused || !seconds.isFinite() || seconds <= 0) return
        val dt = min(seconds, .05)
        if (wallSide != 0) { advanceWall(dt); return }
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
                    (step.bounced && hypot(vx, vy) < 40 && abs(vy) < 1)) {
                    landings++
                    idle()
                }
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
                if (wallSide != 0 || !wander || right == 0.0 || random.nextDouble() < .6) idle()
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
