package io.github.dshpet.mobile

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.io.File
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.dshpet.mobile.modules.ChatBridge
import io.github.dshpet.mobile.modules.ChatModule
import io.github.dshpet.mobile.modules.ClickSoundModule
import io.github.dshpet.mobile.modules.CollisionModule
import io.github.dshpet.mobile.modules.CompanionModule
import io.github.dshpet.mobile.modules.ModuleFactory
import io.github.dshpet.mobile.modules.ShakeModule
import io.github.dshpet.mobile.modules.ModuleHost
import io.github.dshpet.mobile.modules.ModuleRegistry
import io.github.dshpet.mobile.modules.PetEvent
import io.github.dshpet.mobile.modules.SpeechModule
import io.github.dshpet.mobile.modules.VoiceBridge
import io.github.dshpet.mobile.modules.VoiceModule
import io.github.dshpet.mobile.modules.WanderModule
import java.io.FileDescriptor
import java.io.PrintWriter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The single in-process overlay host. It owns the pets (0 is the mother, 1..4
 * are baby whales), their windows, the shared decoder and the module registry;
 * optional behaviour lives in modules that are only built while enabled.
 */
class PetOverlayService : Service(), Choreographer.FrameCallback, ModuleHost, PetView.Listener {
    companion object {
        const val START = "pet.START"
        const val PAUSE = "pet.PAUSE"
        const val RESUME = "pet.RESUME"
        const val STOP = "pet.STOP"
        const val RESET = "pet.RESET"
        const val SPAWN = "pet.SPAWN"
        const val EXIT = "pet.EXIT"
        /** Debug-only: pretend the phone was shaken (release builds ignore it). */
        const val SHAKE_TEST = "pet.SHAKE_TEST"
        /** Debug-only: make the main pet cling to the left edge. */
        const val WALL_TEST = "pet.WALL_TEST"
        /** Debug-only: the same, on the right edge. */
        const val WALL_TEST_RIGHT = "pet.WALL_TEST_RIGHT"
        private const val CHANNEL = "pet-running"
        private const val TAG = "PetOverlay"

        /** About 26 decoded frames; the cache is measured in bytes, not entries. */
        private const val FRAME_BUDGET_BYTES = 24L * 1024 * 1024

        /** Frames decoded ahead of every pet's play head. */
        private const val LOOKAHEAD = 4

        /** First frames of each tap animation, decoded while nothing is happening. */
        private const val WARM_FRAMES = 2

        /** One decode loop keeps a full house of five pets inside its budget. */
        private const val DECODE_THREADS = 1

        /** Long-press menu: a compact card, sized to what is on screen. */
        private const val MENU_WIDTH_DP = 180f
        private const val MENU_ROW_DP = 44f
        private const val MENU_MAX_ROWS = 6f
        private const val MENU_PADDING_DP = 6f
        private const val MENU_RADIUS_DP = 16f

        /** Menu chrome: translucent, so the pet stays visible behind the card. */
        private val MENU_BACKGROUND_DAY = Color.argb(0xF2, 0xFF, 0xFF, 0xFF)
        private val MENU_BACKGROUND_NIGHT = Color.argb(0xE6, 0x19, 0x1C, 0x1C)
        private val MENU_STROKE_DAY = Color.argb(0x33, 0x00, 0x00, 0x00)
        private val MENU_STROKE_NIGHT = Color.argb(0x2E, 0xFF, 0xFF, 0xFF)
        private val MENU_TEXT_DAY = Color.rgb(29, 42, 47)
        private val MENU_TEXT_NIGHT = Color.rgb(226, 235, 231)

        /** Menu page sentinel: "show the animation categories". */
        private const val CATEGORY_PAGE = "\u0000categories"

        /** Keep a tap's line and the idle line that follows it from fighting. */
        private const val CLIP_LINE_GAP_MS = 1_500L

        /** The same sentence twice inside this window is a repeat, not a line. */
        private const val CLIP_LINE_REPEAT_MS = 10_000L

        /**
         * Extra source-unit margin added around the frame while the pet leans
         * out of the edge: rotation swings its head past the clip's own box and
         * the window would cut it off.
         */
        private const val WALL_LEAN_PAD = 70.0

        fun command(context: Context, action: String) {
            try {
                val intent = Intent(context, PetOverlayService::class.java).setAction(action)
                when (action) {
                    STOP -> context.stopService(intent)
                    START -> ContextCompat.startForegroundService(context, intent)
                    else -> context.startService(intent)
                }
            } catch (e: RuntimeException) {
                Log.e(TAG, "Service command failed", e)
                PetRuntime.state.value = PetRuntime.state.value.copy(error = context.getString(R.string.start_failed))
            }
        }

        private val MODULE_FACTORIES = listOf(
            ModuleFactory(ModuleIds.WANDER) { WanderModule() },
            ModuleFactory(ModuleIds.SPEECH) { SpeechModule() },
            ModuleFactory(ModuleIds.CLICK_SOUND) { ClickSoundModule() },
            ModuleFactory(ModuleIds.COMPANION) { CompanionModule() },
            ModuleFactory(ModuleIds.COLLISION) { CollisionModule() },
            ModuleFactory(ModuleIds.CHAT) { ChatModule() },
            ModuleFactory(ModuleIds.VOICE) { VoiceModule() },
            ModuleFactory(ModuleIds.SHAKE) { ShakeModule() },
        )
    }

    /** Android-side attachment of a pet: window, view, bubble, decoder cursor. */
    private class Surface(val handle: PetHandle) {
        var view: PetView? = null
        /** The box this clip actually paints; the window is sized to it. */
        var stage: SourceBox? = null
        var params: WindowManager.LayoutParams? = null
        var cursor: FrameLoader.Cursor? = null
        var bubble: TextView? = null
        var scale = 1.0
        var dismissBubble: Runnable? = null
        /** Full-screen pane that catches the drag once the finger leaves. */
        var grab: View? = null
        /** The last [PetEngine.clipStarts] this surface has shown a line for. */
        var clipStart = -1L
        /** The last landing this surface has already squashed for. */
        var landings = 0L
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windows: WindowManager
    private lateinit var preferences: PetPreferences
    private lateinit var catalog: AssetCatalog
    private var loader: FrameLoader? = null
    private var registry: ModuleRegistry? = null
    private var settings = PetOptions()
    private val petMap = LinkedHashMap<Int, PetHandle>()
    private val surfaces = LinkedHashMap<Int, Surface>()
    private var motherScale = 1.0
    private var safe = Rect()
    private var started = false
    private var registered = false
    private var userPaused = false
    private var frameScheduled = false
    private var lastFrameNanos = 0L
    private var actions: View? = null
    private var actionsBody: LinearLayout? = null
    private var actionsPetId = -1
    private var packs: PackManager? = null
    private var prompt: ChatPrompt? = null
    private var voiceButton: VoiceButton? = null
    private var voiceButtonPet = -1
    /** The last animation line shown, and when; keeps a repeated idle pick quiet. */
    private var lastClipLine = ""
    private var lastClipLineAt = 0L
    private var packUrl = ""
    private var decodedTotal = 0L
    private var reusedTotal = 0L
    private var hitsTotal = 0L
    private var missesTotal = 0L
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key in PetPreferences.WATCHED_KEYS) handler.post { reloadOptions() }
    }
    private val permissionListener = AppOpsManager.OnOpChangedListener { op, pkg ->
        if (op == AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW && pkg == packageName) handler.post {
            if (started && !Settings.canDrawOverlays(this)) fail(R.string.permission_required)
        }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            preferences.log("screen ${intent.action}")
            if (intent.action == Intent.ACTION_SCREEN_OFF) WatchdogReceiver.schedule(this@PetOverlayService)
            updateVisibility()
        }
    }

    override fun onCreate() {
        super.onCreate()
        windows = getSystemService(WindowManager::class.java)
        preferences = PetPreferences(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent means the system restarted us after a kill (screen off, low memory,
        // battery saver). Rebuild the windows instead of silently going away.
        preferences.log("onStartCommand action=${intent?.action} started=$started")
        if (intent == null) {
            if (!started && preferences.wasRunning()) startPet()
            return START_STICKY
        }
        when (intent.action) {
            START -> startPet()
            STOP -> { preferences.setRunning(false); stopSelf() }
            SHAKE_TEST -> if (BuildConfig.DEBUG) {
                preferences.log("debug: simulate shake started=$started")
                if (!started) startPet()
                handler.postDelayed({
                    (registry?.instance(ModuleIds.SHAKE) as? ShakeModule)?.simulateShake()
                }, 1800)
            }
            WALL_TEST -> if (BuildConfig.DEBUG) wallTest(-1)
            WALL_TEST_RIGHT -> if (BuildConfig.DEBUG) wallTest(1)
            PAUSE -> if (started) { userPaused = true; updateVisibility(); updateNotification() }
            RESUME -> if (started) { userPaused = false; updateVisibility(); updateNotification() }
            RESET -> if (started) {
                petMap[0]?.engine?.restorePosition(.7, 1.0)
                exitAllCompanions()
                reloadOptions()
            }
            SPAWN -> if (started) spawnCompanion()
            EXIT -> if (started) petMap[0]?.let { exitCompanion(it) }
            else -> if (!started && !preferences.wasRunning()) stopSelf()
        }
        return START_STICKY
    }

    private fun startPet() {
        if (started) return
        preferences.log("startPet")
        WatchdogReceiver.schedule(this, 600_000L)
        handler.removeCallbacks(healTick)
        handler.postDelayed(healTick, 3000)
        if (!Settings.canDrawOverlays(this)) { fail(R.string.permission_required); return }
        try {
            preferences.setRunning(true)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL,
                getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW))
            val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            ServiceCompat.startForeground(this, 1, notification(), type)
            catalog = AssetCatalog.load(assets)
            settings = preferences.options()
            safe = safeBounds()
            motherScale = scaleFor(settings.size)
            openPacks()
            // Pets and their positions come back first: every module sees the
            // full household when it is enabled below.
            val mother = PetHandle(0, PetEngine(catalog.clips))
            mother.engine.wander = settings.wander
            configureEngine(mother.engine)
            mother.engine.bootstrap()
            resizeEngine(mother.engine, motherScale)
            val saved = preferences.position()
            mother.engine.restorePosition(saved.first, saved.second)
            petMap[0] = mother
            ChatBridge.prompt = ::chatPromptFor
            VoiceBridge.button = ::voiceButtonFor
            VoiceBridge.hide = ::hideVoiceButton
            VoiceBridge.opened = { voiceButton != null }
            started = true
            restoreCompanions()
            registry = ModuleRegistry(this, MODULE_FACTORIES).also { it.apply() }
            preferences.subscribe(preferenceListener)
            ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
            getSystemService(AppOpsManager::class.java).startWatchingMode(
                AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, permissionListener)
            publishState()
            updateVisibility()
        } catch (e: Exception) {
            Log.e(TAG, "Pet startup failed", e)
            fail(if (e is SecurityException) R.string.permission_required else R.string.assets_failed)
        }
    }

    private fun interactive() = getSystemService(PowerManager::class.java).isInteractive &&
        !getSystemService(KeyguardManager::class.java).isKeyguardLocked

    /** One place for the observable state the settings screen reads. */
    private fun publishState() {
        PetRuntime.state.value = RuntimeState(running = started, paused = userPaused,
            error = PetRuntime.state.value.error, companions = companionCount(),
            modules = registry?.activeIds()?.sorted() ?: emptyList())
    }

    private var lastInteractive: Boolean? = null

    /**
     * Cheap self-heal, every 3 s:
     *  - the windows can be lost (OEM cleanup, boot races) -> re-attach;
     *  - the unlock broadcast is not delivered reliably on some ROMs, so poll
     *    the interactive state and re-evaluate visibility on every change.
     */
    private val healTick = object : Runnable {
        override fun run() {
            if (started) {
                val nowInteractive = interactive()
                if (lastInteractive != nowInteractive) {
                    lastInteractive = nowInteractive
                    preferences.log("heal: interactive=$nowInteractive")
                    runCatching { updateVisibility() }
                }
                if (nowInteractive && surfaces.isEmpty()) {
                    preferences.log("heal: re-attach windows")
                    runCatching { attachPets() }
                }
            }
            handler.postDelayed(this, 3000)
        }
    }

    private fun updateVisibility() {
        if (!started) return
        if (!Settings.canDrawOverlays(this)) { fail(R.string.permission_required); return }
        val visible = interactive()
        val paused = userPaused || !visible
        petMap.values.forEach { it.engine.paused = paused }
        publishState()
        Choreographer.getInstance().removeFrameCallback(this)
        frameScheduled = false
        lastFrameNanos = 0L
        if (!visible) {
            // Keep the windows attached while the screen is off: they cost nothing
            // when nothing is composited, and tearing them down was what left the
            // pet missing after unlocking (the rebuild could fail silently).
            rememberPositions()
            petMap.values.forEach { it.engine.cancelDrag() }
            return
        }
        if (surfaces.isEmpty()) attachPets()
        if (surfaces.isEmpty()) return
        renderAll()
        if (!userPaused) scheduleFrame()
    }

    private fun scaleFor(size: Float) =
        112.0 * resources.displayMetrics.density * size / catalog.body.height

    private fun companionScale() = motherScale * CompanionCodec.COMPANION_SCALE

    private fun scaleOf(handle: PetHandle) = if (handle.isCompanion) companionScale() else motherScale

    /**
     * The pet may sit half off the left and right edges and half below the
     * bottom; the top is the only hard limit, so its head and its bubble are
     * always fully on screen. How much may hang off is a setting now - see
     * [PetOptions.edgeAllowance] - and it only has an effect because the pet
     * window carries [WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS], without
     * which the system quietly pushes the whole window back into the screen.
     */
    private fun resizeEngine(engine: PetEngine, scale: Double) {
        val share = settings.edgeAllowance.coerceIn(0f, 1f).toDouble()
        engine.resize(max(0.0, safe.width() / scale - catalog.body.width),
            max(0.0, safe.height() / scale - catalog.body.height),
            catalog.body.width * share, catalog.body.height * share)
    }

    /** (Re)opens the pack manager; a changed mirror takes effect immediately. */
    private fun openPacks() {
        val custom = settings.packBaseUrl.trim()
        val urls = (if (custom.isEmpty()) emptyList() else listOf(custom)) + catalog.packBaseUrls
        packUrl = custom
        packs = PackManager.shared(this, urls)
        for (handle in petMap.values) configureEngine(handle.engine)
    }

    /** Only clips that are on the device are played; missing ones are fetched. */
    private fun configureEngine(engine: PetEngine) {
        engine.availability = { clip -> packs?.isReady(clip) ?: true }
        engine.onMissing = { clip ->
            if (settings.packAuto) packs?.ensure(clip)
            else Toast.makeText(this, R.string.assets_missing, Toast.LENGTH_SHORT).show()
        }
    }

    private fun layout(width: Int, height: Int) = WindowManager.LayoutParams(
        width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            (if (settings.touchThrough) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0),
        PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
    }

    // ---- pets and surfaces -------------------------------------------------

    private fun attachPets() {
        if (loader == null) {
            val manager = packs
            loader = FrameLoader(assets, ::renderAll, { onFramesFailed(it) },
                FRAME_BUDGET_BYTES, LOOKAHEAD, DECODE_THREADS,
                if (manager == null) null else { clip, index -> manager.frameFile(clip, index) })
            warmFrames()
        }
        for (handle in petMap.values.toList()) {
            if (!attach(handle)) return
        }
    }

    /**
     * The window only needs to cover what the current clip paints. Keeping it at
     * the union of every clip made the window ~4x the pet's own area, so taps on
     * the transparent margin were swallowed instead of reaching the app below.
     */
    private fun stageFor(engine: PetEngine, clip: ClipSpec): SourceBox {
        val crop = clip.crop
        // Padding for the whole visit, not only while leaning: tying it to the
        // lean made the window grow and shrink on every little climbing wobble.
        if (engine.wallSide == 0) return crop
        val pad = WALL_LEAN_PAD
        return SourceBox(crop.left - pad, crop.top - pad, crop.right + pad, crop.bottom + pad)
    }

    /**
     * When the pet next goes to hang on an edge, and when it should let go.
     *
     * The feature is deliberately unhurried: the interval is minutes, the stay
     * half a minute to a minute and a half. A pet that vanished to the edge
     * every twenty seconds would be a nuisance, not companionship.
     */
    private var wallNextAt = 0L
    /** Whether the previous tick saw the pet on an edge, to reschedule on any departure. */
    private var wallWasOn = false
    private var loggedWallPhase = WallPhase.NONE
    private var loggedPeekAction = PeekAction.NORMAL

    private fun wallIntervalMs(): Long {
        val minutes = settings.wallMinutes.coerceIn(1, 30)
        // ±25% jitter so it never feels like a timer.
        val jitter = 0.75 + Math.random() * 0.5
        return (minutes * 60_000.0 * jitter).toLong()
    }

    /** Runs on the frame loop; cheap enough to check every tick. */
    private fun maybeWall() {
        if (!started || userPaused) return
        val handle = petMap[0] ?: return
        val engine = handle.engine
        val now = SystemClock.elapsedRealtime()
        if (engine.wallPhase != loggedWallPhase) {
            loggedWallPhase = engine.wallPhase
            preferences.log("wall: phase=${engine.wallPhase} x=${"%.1f".format(engine.x)} y=${"%.1f".format(engine.y)} lean=${"%.2f".format(engine.wallLean)}")
        }
        if (engine.peekAction != loggedPeekAction) {
            loggedPeekAction = engine.peekAction
            preferences.log("wall: peek=${engine.peekAction}")
        }
        if (engine.wallSide != 0) {
            wallWasOn = true
            // The performance has its own clock; the service only steps in when
            // the switch is turned off, so a pet never stays stranded outside.
            if (!settings.wall) {
                engine.leaveWall(fall = true)
                wallWasOn = false
                wallNextAt = now + wallIntervalMs()
                preferences.log("wall: aborted by setting")
            }
            return
        }
        if (wallWasOn) {
            // Pulled off by the user before the clock ran out: wait a full
            // interval before going back, or dragging it off would look like a
            // pet that immediately re-attaches.
            wallWasOn = false
            wallNextAt = now + wallIntervalMs()
        }
        if (!settings.wall) return
        if (engine.mode == PetMode.DRAG || engine.mode == PetMode.THROWN) return
        // The first visit waits a full interval too: a pet that darts off to the
        // edge twenty seconds after every restart feels like a glitch, not a habit.
        if (wallNextAt == 0L) { wallNextAt = now + wallIntervalMs(); return }
        if (now >= wallNextAt) enterWall(if (Math.random() < .5) -1 else 1)
    }

    /** Debug hook behind the two WALL_TEST intents. */
    private fun wallTest(side: Int) {
        preferences.log("debug: wall test side=$side started=$started")
        if (!started) startPet()
        handler.postDelayed({
            val engine = petMap[0]?.engine
            if (engine != null && engine.wallSide != 0) engine.leaveWall(fall = true)
            else enterWall(side)
        }, 1200)
    }

    /**
     * Starts a full visit: the pet walks to the edge, climbs up in steps, peeks
     * out, watches, then climbs back down. The service only provides geometry;
     * every position after this call belongs to the engine.
     *
     * Three x values: standing at the edge (fully visible), hanging while
     * climbing (leaning over the edge), and tucked in for the peek (the lean
     * brings the head back inside the screen).
     */
    private fun enterWall(side: Int) {
        val handle = petMap[0] ?: return
        val engine = handle.engine
        if (engine.mode == PetMode.DRAG) return
        val scale = scaleOf(handle)
        if (!scale.isFinite() || scale <= .1) return
        val body = catalog.body
        val peek = settings.wallPeek.toDouble().coerceIn(.3, .65)
        fun bodyX(visible: Double) = if (side < 0) {
            safe.left / scale - body.width * (1.0 - visible)
        } else {
            safe.right / scale - body.width * visible
        }
        val forced = PeekAction.entries.firstOrNull {
            it.name.equals(settings.wallPeekAction, ignoreCase = true)
        }
        engine.startWall(side, walkX = bodyX(1.0), climbX = bodyX(.58),
            peekX = bodyX(peek), bodyHeight = body.height,
            staySeconds = settings.wallStaySeconds, forcedPeek = forced)
        // A wall pet that is dragged off must not jump straight back on: the
        // next visit is scheduled from this start, not from the last finish.
        wallNextAt = SystemClock.elapsedRealtime() + wallIntervalMs()
        // The hold-to-talk button follows the pet and would hang half off the
        // screen; the menu reopens it. Bubbles still work while peeking.
        hideVoiceButton()
        preferences.log("wall: start side=$side walk=${bodyX(1.0)} climb=${bodyX(.58)} peek=${bodyX(peek)}")
    }

    private fun attach(handle: PetHandle): Boolean {
        if (surfaces.containsKey(handle.id)) return true
        val scale = scaleOf(handle)
        resizeEngine(handle.engine, scale)
        val engine = handle.engine
        val stage = stageFor(engine, engine.clip)
        val placement = WindowGeometry.placement(engine.x, engine.y, catalog.body,
            stage, scale, safe.left, safe.top)
        val surface = Surface(handle)
        surface.stage = stage
        surface.scale = scale
        surface.cursor = loader?.cursor(handle.id)
        surface.dismissBubble = Runnable { surface.bubble?.visibility = View.INVISIBLE }
        return try {
            val view = PetView(this, handle.id, engine, this)
            view.configure(stage, scale)
            val params = layout(placement.width, placement.height).apply {
                // Without this the window manager keeps the *whole* window on
                // screen, which is what stopped the pet reaching the edge.
                flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                x = placement.x
                y = placement.y
            }
            windows.addView(view, params)
            surface.view = view
            surface.params = params
            surfaces[handle.id] = surface
            PetRuntime.windowCount.set(surfaces.size)
            render(surface)
            true
        } catch (e: RuntimeException) {
            Log.e(TAG, "Cannot attach overlay", e)
            surface.cursor?.close()
            fail(R.string.permission_required)
            false
        }
    }

    private fun detachSurface(id: Int) {
        val surface = surfaces.remove(id) ?: return
        surface.dismissBubble?.let { handler.removeCallbacks(it) }
        removeAuxiliary(surface.bubble)
        surface.bubble = null
        releaseGrab(surface)
        removeAuxiliary(surface.view)
        surface.view = null
        surface.params = null
        surface.cursor?.close()
        surface.cursor = null
        if (actionsPetId == id) closeMenu()
        PetRuntime.windowCount.set(surfaces.size)
    }

    private fun removeWindows() {
        closeMenu()
        prompt?.close()
        prompt = null
        voiceButton?.close()
        voiceButton = null
        surfaces.keys.toList().forEach { detachSurface(it) }
        releaseLoader()
        PetRuntime.windowCount.set(0)
    }

    private fun releaseLoader() {
        loader?.let {
            decodedTotal += it.decoded.get()
            reusedTotal += it.reused.get()
            hitsTotal += it.cacheHits.get()
            missesTotal += it.cacheMisses.get()
            it.close()
        }
        loader = null
    }

    private fun warmFrames() {
        val manager = loader ?: return
        // Nothing that is not on the device yet: asking for it would look like a
        // broken install, which is exactly how a fresh install used to fail.
        val ready = (catalog.clipsIn("click") + catalog.clipsIn("drag"))
            .filter { packs?.isReady(it) ?: true }
        manager.warm(ready, WARM_FRAMES)
    }

    private fun onFramesFailed(error: Exception) {
        Log.e(TAG, "Frame decode failed", error)
        fail(R.string.assets_failed)
    }

    // ---- companions --------------------------------------------------------

    private fun companionIds() = petMap.keys.filter { it != 0 }.sorted()

    override fun companionCount() = companionIds().size

    override fun spawnCompanion(): Boolean {
        if (!started) return false
        if (companionCount() >= CompanionCodec.MAX_COMPANIONS) return false
        val mother = petMap[0] ?: return false
        val id = (1..CompanionCodec.MAX_COMPANIONS).firstOrNull { !petMap.containsKey(it) } ?: return false
        val engine = PetEngine(catalog.clips)
        engine.wander = settings.wander
        configureEngine(engine)
        engine.bootstrap()
        resizeEngine(engine, companionScale())
        val index = companionCount() + 1
        engine.placeAt(mother.engine.x + catalog.body.width + CompanionCodec.spawnOffset(index),
            mother.engine.y)
        val handle = PetHandle(id, engine)
        petMap[id] = handle
        if (surfaces.isNotEmpty() || interactive()) attach(handle)
        registry?.onEvent(PetEvent.Spawned(handle))
        rememberPositions()
        publishState()
        renderAll()
        return true
    }

    override fun exitCompanion(pet: PetHandle): Boolean {
        val id = if (pet.isCompanion) pet.id else companionIds().lastOrNull() ?: return false
        val handle = petMap.remove(id) ?: return false
        detachSurface(id)
        registry?.onEvent(PetEvent.Exited(handle))
        rememberPositions()
        publishState()
        renderAll()
        return true
    }

    private fun exitAllCompanions() {
        companionIds().toList().forEach { id -> petMap[id]?.let { exitCompanion(it) } }
    }

    private fun restoreCompanions() {
        if (!settings.companions) return
        for ((x, y) in preferences.companionLayout()) {
            val id = (1..CompanionCodec.MAX_COMPANIONS).firstOrNull { !petMap.containsKey(it) } ?: break
            val engine = PetEngine(catalog.clips)
            engine.wander = settings.wander
            configureEngine(engine)
            engine.bootstrap()
            resizeEngine(engine, companionScale())
            engine.restorePosition(x, y)
            petMap[id] = PetHandle(id, engine)
        }
    }

    private fun rememberPositions() {
        // onDestroy saves once before shutdown; module cleanup must not erase that household.
        if (!started) return
        petMap[0]?.let { preferences.savePosition(it.engine.normalizedX, it.engine.normalizedY) }
        preferences.saveCompanionLayout(companionIds().mapNotNull { id ->
            petMap[id]?.let { it.engine.normalizedX to it.engine.normalizedY }
        })
    }

    // ---- module host -------------------------------------------------------

    override val options: PetOptions get() = settings

    override val builtinPhrases: List<String> get() = catalog.phrases

    override val pets: List<PetHandle> get() = petMap.values.toList()

    override val bodyBox: SourceBox get() = catalog.body

    /** The collision module needs both pets in one space; the mother is the unit. */
    override fun scaleRatio(pet: PetHandle): Double {
        val mother = motherScale
        return if (mother > 0.0) scaleOf(pet) / mother else 1.0
    }

    override val context: Context get() = this

    override fun now(): Long = SystemClock.elapsedRealtime()

    override fun effectivePhrases(): List<String> =
        PhraseBook.resolve(settings.phrases, catalog.phrases)

    override fun showPhrase(pet: PetHandle, text: String, durationMs: Long, spoken: Boolean) {
        if (!started || userPaused) return
        val surface = surfaces[pet.id] ?: return
        val view = surface.bubble ?: createBubble(surface) ?: return
        view.text = text
        PetRuntime.lastPhrase.set(text)
        // One choke point: every line that reaches a bubble is spoken when the
        // voice mode is on, no matter whether it came from chat, from a tap or
        // from the balance query.
        if (spoken) speak(text)
        if (view.visibility != View.VISIBLE) {
            view.visibility = View.VISIBLE
            view.post { positionBubble(surface) }
        }
        val dismiss = surface.dismissBubble ?: return
        handler.removeCallbacks(dismiss)
        handler.postDelayed(dismiss, durationMs.coerceAtLeast(500))
        positionBubble(surface)
    }

    /** The voice half of [showPhrase], also usable on its own for batched bubbles. */
    override fun speak(text: String) {
        if (!started || userPaused) return
        if (settings.voiceSpeak && text.isNotBlank()) {
            VoiceBridge.speak?.invoke(text)
        }
    }

    override fun clearPhrase(pet: PetHandle) {
        val surface = surfaces[pet.id] ?: return
        surface.dismissBubble?.let { handler.removeCallbacks(it) }
        surface.bubble?.visibility = View.INVISIBLE
    }

    /** The desktop's Q 弹 pulse, triggered by a tap (in the view) or a real hit. */
    override fun squash(pet: PetHandle) {
        surfaces[pet.id]?.view?.squash()
    }

    private fun createBubble(surface: Surface): TextView? {
        val view = TextView(this).apply {
            setTextColor(Color.rgb(29, 42, 47)); textSize = 14f
            maxWidth = (220 * resources.displayMetrics.density).roundToInt()
            val pad = (12 * resources.displayMetrics.density).roundToInt()
            setPadding(pad, pad / 2, pad, pad / 2)
            background = GradientDrawable().apply {
                setColor(Color.WHITE); cornerRadius = 8 * resources.displayMetrics.density
                setStroke(1, Color.rgb(213, 224, 226))
            }
            visibility = View.INVISIBLE
        }
        val p = layout(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
        // Android 12+ only permits pass-through overlays below its opacity limit.
        p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        p.alpha = .8f
        return try {
            windows.addView(view, p)
            surface.bubble = view
            PetRuntime.bubbles.incrementAndGet()
            view
        } catch (e: RuntimeException) {
            Log.e(TAG, "Cannot attach bubble", e)
            fail(R.string.permission_required)
            null
        }
    }

    /** One bubble window per pet, created once and only retexted afterwards. */
    private fun positionBubble(surface: Surface) {
        val view = surface.bubble ?: return
        if (view.visibility != View.VISIBLE) return
        val p = view.layoutParams as? WindowManager.LayoutParams ?: return
        val engine = surface.handle.engine
        val x = (safe.left + engine.x * surface.scale).roundToInt()
            .coerceIn(safe.left, max(safe.left, safe.right - view.width))
        val above = (safe.top + engine.y * surface.scale - view.height -
            8 * resources.displayMetrics.density).roundToInt()
        val y = if (above >= safe.top) above else
            (safe.top + (engine.y + catalog.body.height) * surface.scale).roundToInt()
                .coerceAtMost(safe.bottom - view.height)
        if (p.x == x && p.y == y) return
        p.x = x; p.y = y
        try {
            windows.updateViewLayout(view, p)
            PetRuntime.windowOps.incrementAndGet()
        } catch (_: RuntimeException) {
            removeAuxiliary(view)
            surface.bubble = null
        }
    }

    // ---- frame loop --------------------------------------------------------

    private fun scheduleFrame() {
        if (!frameScheduled && started && !userPaused && surfaces.isNotEmpty()) {
            frameScheduled = true
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** 24 fps while an idle clip plays, 60 Hz while a pet is moving or flying. */
    private fun frameIntervalSeconds(): Double {
        val moving = surfaces.values.any {
            val mode = it.handle.engine.mode
            mode == PetMode.MOVE || mode == PetMode.THROWN
        }
        val fps = if (moving) 60.0 else surfaces.values.firstOrNull()?.handle?.engine?.clip?.fps ?: 24.0
        return 1.0 / fps.coerceIn(1.0, 60.0)
    }

    override fun doFrame(frameTimeNanos: Long) {
        frameScheduled = false
        if (!started || userPaused || surfaces.isEmpty()) return
        if (surfaces.values.first().handle.engine.paused) return
        if (lastFrameNanos == 0L) lastFrameNanos = frameTimeNanos
        val elapsed = (frameTimeNanos - lastFrameNanos) / 1e9
        if (elapsed >= frameIntervalSeconds()) {
            lastFrameNanos = frameTimeNanos
            // Collisions settle on the pre-integration snapshot, like the desktop.
            registry?.onTick(elapsed)
            maybeWall()
            for (surface in surfaces.values.toList()) {
                surface.handle.engine.advance(elapsed)
                render(surface)
                registry?.onFrame(surface.handle, elapsed)
            }
        }
        scheduleFrame()
    }

    private fun renderAll() {
        surfaces.values.toList().forEach { render(it) }
    }

    private fun render(surface: Surface) {
        val view = surface.view ?: return
        val engine = surface.handle.engine
        val clip = engine.clip
        val frame = surface.cursor?.request(clip, engine.frameIndex)
        if (frame == null) PetRuntime.staleFrames.incrementAndGet()
        // One window for everything, effects included. The stage covers every
        // crop, so a wide animation simply paints wider in the same window -
        // and it gets the same opacity as any other clip. Drawing effects in a
        // second, untouchable window is what made them look washed out: Android
        // caps the opacity of pass-through overlays.
        val stage = stageFor(engine, clip)
        if (surface.stage != stage) {
            surface.stage = stage
            view.configure(stage, surface.scale)
        }
        view.present(frame, clip.crop, engine.facingRight)
        // A landing gets a short squash so the jump down has weight.
        if (surface.landings != engine.landings) {
            surface.landings = engine.landings
            view.squash()
        }
        moveWindow(view, surface.params, stage, engine, surface.scale)
        sayClipLine(surface)
        positionBubble(surface)
        positionVoiceButton(surface)
    }

    /**
     * The line an animation carries, shown the moment it starts.
     *
     * A clip says its own line instead of the pet drawing unrelated sentences
     * from a pool every 20-60 seconds: the five tap responses and the self-talk
     * actions each come with one. The same clip played again says it again - the
     * engine's counter is compared, not the clip id - while the same sentence
     * back to back is skipped so a repeated idle pick does not stutter.
     *
     * [PetOptions.clickReply] decides who answers a tap: with [ClickReply.DSH]
     * the tap clips stay quiet and the whisper module speaks instead.
     */
    private fun sayClipLine(surface: Surface) {
        val engine = surface.handle.engine
        if (surface.clipStart == engine.clipStarts) return
        surface.clipStart = engine.clipStarts
        // Companions are props, not speakers: four bubbles at once is noise.
        if (surface.handle.isCompanion || !started || userPaused) return
        val line = engine.clip.line
        if (line.isBlank() || !settings.clipLineActive(engine.clip)) return
        val now = SystemClock.elapsedRealtime()
        // A tap's own clip ends by dropping back to an idle clip, which may
        // carry a line too: keep a gap so the two never fight over the bubble.
        if (now - lastClipLineAt < CLIP_LINE_GAP_MS) return
        if (line == lastClipLine && now - lastClipLineAt < CLIP_LINE_REPEAT_MS) return
        lastClipLine = line
        lastClipLineAt = now
        showPhrase(surface.handle, line, clipLineMs(line), true)
    }

    /** Long enough to read the line, or to hear it when voice mode is on. */
    private fun clipLineMs(line: String): Long =
        (2_500L + line.length * 90L).coerceIn(3_000L, 8_000L)

    /**
     * Puts a window where [stage] says, keeping the body anchor where the engine
     * left it. With the stage constant this only ever changes x and y - while a
     * pet is being dragged, one position update per move event is all it costs.
     */
    private fun moveWindow(view: View?, params: WindowManager.LayoutParams?,
                           stage: SourceBox, engine: PetEngine, scale: Double) {
        if (view == null || params == null) return
        val placement = WindowGeometry.placement(engine.x, engine.y, catalog.body,
            stage, scale, safe.left, safe.top)
        if (params.x == placement.x && params.y == placement.y &&
            params.width == placement.width && params.height == placement.height) return
        params.x = placement.x; params.y = placement.y
        params.width = placement.width; params.height = placement.height
        try {
            windows.updateViewLayout(view, params)
            PetRuntime.windowOps.incrementAndGet()
        } catch (e: RuntimeException) {
            fail(R.string.permission_required)
        }
    }

    // ---- the drag's safety net ----------------------------------------------

    /**
     * A transparent pane over the whole screen, alive only while a pet is held.
     * Normally Android keeps delivering a drag to the window that received the
     * press wherever the finger goes; some launchers and ROMs do not, and this
     * pane is what makes those behave. It forwards everything to the pet's view,
     * which measures movement from raw screen coordinates.
     *
     * The trade-off, agreed with the user: while dragging, touches meant for the
     * app underneath land on this pane instead.
     */
    private inner class GrabLayer(private val petId: Int) : View(this@PetOverlayService) {
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val view = surfaces[petId]?.view ?: return false
            if (event.actionMasked != MotionEvent.ACTION_DOWN) return view.forwardGesture(event)
            // Presses out here are not for the pet; let the app below have them.
            return false
        }
    }

    private fun openGrab(surface: Surface) {
        if (surface.grab != null) return
        val view = GrabLayer(surface.handle.id)
        val params = layout(safe.width(), safe.height()).apply {
            x = safe.left
            y = safe.top
        }
        try {
            windows.addView(view, params)
            surface.grab = view
        } catch (e: RuntimeException) {
            Log.e(TAG, "Cannot attach grab layer", e)
        }
    }

    private fun releaseGrab(surface: Surface) {
        val view = surface.grab ?: return
        surface.grab = null
        removeAuxiliary(view)
    }

    // ---- view callbacks ----------------------------------------------------

    override fun onChanged(petId: Int) {
        surfaces[petId]?.let { render(it) }
    }

    override fun onPressed(petId: Int) {
        surfaces[petId]?.let {
            registry?.onEvent(PetEvent.Pressed(it.handle))
            openGrab(it)
        }
    }

    /** Every gesture ends here - drag, tap or menu - so the pane always goes. */
    override fun onGestureEnd(petId: Int) {
        surfaces[petId]?.let { releaseGrab(it) }
    }

    override fun onReleased(petId: Int) {
        rememberPositions()
        closeMenu()
    }

    override fun onClicked(petId: Int) {
        surfaces[petId]?.let {
            registry?.onEvent(PetEvent.Clicked(it.handle))
            render(it)
        }
    }

    override fun onMenu(petId: Int) {
        showActions(petId)
    }

    // ---- long-press menu ---------------------------------------------------

    /**
     * The menu mirrors the desktop plugin: 动作 opens the full animation list
     * (categories first, then every clip by name), and picking one plays it.
     * Everything else - 对话, 查看余额, 暂停 … - stays one tap away.
     */
    private fun showActions(petId: Int) {
        closeMenu()
        val surface = surfaces[petId] ?: return
        val density = resources.displayMetrics.density
        val width = (MENU_WIDTH_DP * density).roundToInt()
        val night = nightMode
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroller = object : ScrollView(this) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) closeMenu()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
            // Transparent on purpose: the rounded card behind it owns the look.
            background = null
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        // The card is a view of its own, and it clips its children to the
        // rounded outline. Painting the background straight onto the scroller
        // used to leave the rows unclipped - their text ran out through the
        // corners as soon as the list scrolled.
        val card = FrameLayout(this).apply {
            val pad = (MENU_PADDING_DP * density).roundToInt()
            setPadding(0, pad, 0, pad)
            background = GradientDrawable().apply {
                setColor(if (night) MENU_BACKGROUND_NIGHT else MENU_BACKGROUND_DAY)
                cornerRadius = MENU_RADIUS_DP * density
                setStroke(max(1, (density / 2).roundToInt()),
                    if (night) MENU_STROKE_NIGHT else MENU_STROKE_DAY)
            }
            clipToOutline = true
            elevation = 6 * density
            addView(scroller, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT))
        }
        val p = layout(width, (MENU_ROW_DP * density).roundToInt())
        // A user-opened menu needs focus for Back and accessibility navigation.
        p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        p.flags = p.flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        // No FLAG_BLUR_BEHIND: the system blurs the whole window rectangle, so
        // the four rounded corners kept a blurred patch of their own - the
        // "second layer" outside the card. The card is translucent enough on
        // its own and now reads as one clean sheet.
        card.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) closeMenu()
            false
        }
        try {
            windows.addView(card, p)
            actions = card
            actionsBody = body
            actionsPetId = petId
            card.alpha = 0f
            card.scaleX = .96f
            card.scaleY = .96f
            card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(120).start()
            renderMenu(petId, null)
        } catch (e: RuntimeException) {
            fail(R.string.permission_required)
        }
    }

    /** [page] is null for the root, CATEGORY_PAGE for the categories, else a category. */
    private fun renderMenu(petId: Int, page: String?) {
        val body = actionsBody ?: return
        val surface = surfaces[petId] ?: return
        body.removeAllViews()
        when {
            page == null -> {
                row(body, getString(R.string.menu_actions)) { renderMenu(petId, CATEGORY_PAGE) }
                registry?.menuItems(surface.handle)?.forEach { item ->
                    row(body, getString(item.labelRes)) { closeMenu(); item.action() }
                }
                row(body, getString(if (userPaused) R.string.resume else R.string.pause)) {
                    closeMenu()
                    command(this, if (userPaused) RESUME else PAUSE)
                }
                row(body, getString(R.string.settings)) {
                    closeMenu()
                    startActivity(Intent(this, MainActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                }
                row(body, getString(R.string.stop)) { closeMenu(); preferences.setRunning(false); stopSelf() }
            }
            page == CATEGORY_PAGE -> {
                row(body, getString(R.string.menu_back)) { renderMenu(petId, null) }
                catalog.categories().forEach { category ->
                    val count = catalog.clipsInCategory(category).size
                    row(body, "$category（$count）") { renderMenu(petId, category) }
                }
            }
            else -> {
                row(body, getString(R.string.menu_back)) { renderMenu(petId, CATEGORY_PAGE) }
                catalog.clipsInCategory(page).forEach { clip ->
                    val downloaded = packs?.isReady(clip) ?: true
                    val label = clip.title + if (downloaded) "" else "  " + getString(R.string.pack_fetch_mark)
                    row(body, label) { closeMenu(); playClip(surface, clip) }
                }
            }
        }
        applyMenuHeight(body.childCount)
        positionMenu(petId, widthPx())
    }

    /** The card grows one row at a time and stops at six, then it scrolls. */
    private fun applyMenuHeight(rows: Int) {
        val view = actions ?: return
        val density = resources.displayMetrics.density
        val cap = ((MENU_ROW_DP * MENU_MAX_ROWS + 2 * MENU_PADDING_DP) * density).roundToInt()
        val wanted = ((rows * MENU_ROW_DP + 2 * MENU_PADDING_DP) * density).roundToInt()
            .coerceAtMost(cap).coerceAtMost(safe.height())
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        if (params.height == wanted) return
        params.height = wanted
        try {
            windows.updateViewLayout(view, params)
            PetRuntime.windowOps.incrementAndGet()
        } catch (_: RuntimeException) { }
    }

    /** Keeps the card beside the pet without running off any screen edge. */
    private fun positionMenu(petId: Int, width: Int) {
        val view = actions ?: return
        val surface = surfaces[petId] ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val height = params.height
        val engine = surface.handle.engine
        val x = (safe.left + engine.x * surface.scale).roundToInt()
            .coerceIn(safe.left, max(safe.left, safe.right - width))
        val y = (safe.top + engine.y * surface.scale).roundToInt().coerceIn(safe.top,
            max(safe.top, safe.bottom - height))
        if (params.x == x && params.y == y) return
        params.x = x; params.y = y
        try {
            windows.updateViewLayout(view, params)
            PetRuntime.windowOps.incrementAndGet()
        } catch (_: RuntimeException) { }
    }

    private fun widthPx() = (MENU_WIDTH_DP * resources.displayMetrics.density).roundToInt()

    private val nightMode: Boolean get() = resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun row(body: LinearLayout, label: String, onClick: () -> Unit) {
        val density = resources.displayMetrics.density
        body.addView(TextView(this).apply {
            text = label
            setTextColor(if (nightMode) MENU_TEXT_NIGHT else MENU_TEXT_DAY)
            textSize = 14f
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = android.view.Gravity.CENTER_VERTICAL
            val pad = (14 * density).roundToInt()
            setPadding(pad, 0, pad, 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                (MENU_ROW_DP * density).roundToInt())
            val styled = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            background = styled.getDrawable(0)
            styled.recycle()
            setOnClickListener { onClick() }
        })
    }

    /** Plays one chosen animation, downloading its pack first when needed. */
    private fun playClip(surface: Surface, clip: ClipSpec) {
        val manager = packs
        if (manager == null || manager.isReady(clip)) {
            surface.handle.engine.play(clip)
            return
        }
        Toast.makeText(this, getString(R.string.pack_fetching, clip.title), Toast.LENGTH_SHORT).show()
        manager.ensure(clip) { ok ->
            val target = surfaces[surface.handle.id] ?: return@ensure
            if (ok) target.handle.engine.play(clip)
            else Toast.makeText(this, R.string.pack_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** The floating input bubble, opened by the menu's 对话 entry. */
    private fun chatPromptFor(petId: Int, submit: (String) -> Unit) {
        closeMenu()
        val surface = surfaces[petId] ?: return
        val density = resources.displayMetrics.density
        val engine = surface.handle.engine
        val above = (safe.top + engine.y * surface.scale - 64 * density).roundToInt()
        val y = if (above >= safe.top) above else
            (safe.top + (engine.y + catalog.body.height) * surface.scale + 8 * density).roundToInt()
        val x = (safe.left + engine.x * surface.scale).roundToInt()
        prompt?.close()
        prompt = ChatPrompt(this, windows).apply {
            show(x, y, safe, submit) { prompt = null }
        }
    }

    /** The floating hold-to-talk button, opened by the menu's 语音对话 entry. */
    private fun voiceButtonFor(petId: Int, onPress: () -> Unit, onRelease: () -> Unit): Boolean {
        closeMenu()
        val surface = surfaces[petId] ?: return false
        val (x, y) = voiceButtonPlacement(surface)
        voiceButton?.close()
        val button = VoiceButton(this, windows)
        var open = true
        button.show(x, y, safe, onPress, onRelease) {
            open = false
            voiceButton = null
            voiceButtonPet = -1
        }
        if (!open) return false
        voiceButton = button
        voiceButtonPet = petId
        return true
    }

    /** Where the hold-to-talk button sits: beside the pet, inside the safe box. */
    private fun voiceButtonPlacement(surface: Surface): Pair<Int, Int> {
        val density = resources.displayMetrics.density
        val engine = surface.handle.engine
        val beside = (safe.top + (engine.y + catalog.body.height / 2) * surface.scale).roundToInt()
        val y = beside.coerceIn(safe.top, max(safe.top, safe.bottom - (56 * density).roundToInt()))
        val x = (safe.left + engine.x * surface.scale).roundToInt()
        return x to y
    }

    private fun hideVoiceButton() {
        voiceButton?.close()
        voiceButton = null
        voiceButtonPet = -1
    }

    /** Keeps the button glued to the pet while it is on screen. */
    private fun positionVoiceButton(surface: Surface) {
        if (voiceButtonPet != surface.handle.id) return
        val (x, y) = voiceButtonPlacement(surface)
        voiceButton?.reposition(x, y)
    }

    private fun closeMenu() {
        removeAuxiliary(actions)
        actions = null
        actionsBody = null
        actionsPetId = -1
    }

    // ---- preferences and system -------------------------------------------

    private fun reloadOptions() {
        if (!started) return
        settings = preferences.options()
        for (surface in surfaces.values.toList()) {
            val params = surface.params ?: continue
            val view = surface.view ?: continue
            val want = if (settings.touchThrough)
                params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            else params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            if (params.flags != want) {
                params.flags = want
                runCatching { windows.updateViewLayout(view, params) }
            }
        }
        if (settings.packBaseUrl.trim() != packUrl) openPacks()
        safe = safeBounds()
        motherScale = scaleFor(settings.size)
        for (handle in petMap.values.toList()) resizeEngine(handle.engine, scaleOf(handle))
        for (surface in surfaces.values.toList()) {
            surface.scale = scaleOf(surface.handle)
            surface.view?.configure(surface.stage ?: catalog.stageBox, surface.scale)
        }
        registry?.apply()
        if (!settings.speech) {
            surfaces.values.toList().forEach { surface ->
                surface.dismissBubble?.let { handler.removeCallbacks(it) }
                surface.bubble?.visibility = View.INVISIBLE
            }
        }
        publishState()
        renderAll()
    }

    @Suppress("DEPRECATION")
    private fun safeBounds(): Rect {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = windows.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            return Rect(metrics.bounds).apply {
                left += insets.left; top += insets.top; right -= insets.right; bottom -= insets.bottom
            }
        }
        val metrics = DisplayMetrics()
        windows.defaultDisplay.getRealMetrics(metrics)
        fun inset(name: String): Int {
            val id = resources.getIdentifier(name, "dimen", "android")
            return if (id == 0) 0 else resources.getDimensionPixelSize(id)
        }
        return Rect(0, inset("status_bar_height"), metrics.widthPixels,
            metrics.heightPixels - inset("navigation_bar_height"))
    }

    private fun notification(): Notification {
        fun pending(action: String) = PendingIntent.getService(this, action.hashCode(),
            Intent(this, PetOverlayService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(if (userPaused) R.string.paused else R.string.running))
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .addAction(if (userPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                getString(if (userPaused) R.string.resume else R.string.pause), pending(if (userPaused) RESUME else PAUSE))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.stop), pending(STOP))
            .build()
    }

    private fun updateNotification() = getSystemService(NotificationManager::class.java).notify(1, notification())

    private fun fail(message: Int) {
        preferences.setRunning(false)
        PetRuntime.state.value = RuntimeState(error = getString(message))
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        stopSelf()
    }

    private fun removeAuxiliary(view: View?) {
        if (view != null) try { windows.removeViewImmediate(view) } catch (_: RuntimeException) { }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (started) { reloadOptions(); rememberPositions() }
    }

    override fun onDestroy() {
        preferences.log("onDestroy started=$started")
        if (started) rememberPositions()
        started = false
        ChatBridge.prompt = null
        VoiceBridge.button = null
        // The downloader is shared with the settings screen; it is left running
        // so a download started there survives a pet restart.
        packs = null
        Choreographer.getInstance().removeFrameCallback(this)
        handler.removeCallbacksAndMessages(null)
        handler.removeCallbacks(healTick)
        preferences.unsubscribe(preferenceListener)
        if (registered) {
            unregisterReceiver(screenReceiver)
            getSystemService(AppOpsManager::class.java).stopWatchingMode(permissionListener)
        }
        registry?.shutdown()
        registry = null
        removeWindows()
        PetRuntime.state.value = RuntimeState(error = PetRuntime.state.value.error)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>) {
        val active = loader
        writer.println("running=$started paused=$userPaused interactive=${interactive()}")
        writer.println("pets=${petMap.size} windows=${PetRuntime.windowCount.get()} draws=${PetRuntime.draws.get()}")
        writer.println("windowOps=${PetRuntime.windowOps.get()} bubbles=${PetRuntime.bubbles.get()} " +
            "stale=${PetRuntime.staleFrames.get()} audio=${PetRuntime.audioPlays.get()}")
        writer.println("decoded=${decodedTotal + (active?.decoded?.get() ?: 0)} " +
            "reused=${reusedTotal + (active?.reused?.get() ?: 0)} " +
            "hits=${hitsTotal + (active?.cacheHits?.get() ?: 0)} " +
            "misses=${missesTotal + (active?.cacheMisses?.get() ?: 0)} " +
            "cached=${active?.cachedFrames() ?: 0} cacheKB=${(active?.cachedBytes() ?: 0) / 1024}")
        writer.println("modules=${registry?.activeIds() ?: emptySet()} " +
            "ticks=${registry?.ticksDispatched ?: 0} frames=${registry?.framesDispatched ?: 0} " +
            "customPhrases=${settings.phrases.size}")
        writer.println("moduleInfo=${registry?.diagnostics()}")
        if (started) petMap[0]?.let {
            writer.println("mode=${it.engine.mode} x=${it.engine.x} y=${it.engine.y} clip=${it.engine.clip.id}")
        }
        writer.println("phrase=${PetRuntime.lastPhrase.get()}")
        writer.println("gesture=${surfaces[0]?.view?.lastGesture}")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
