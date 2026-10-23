package io.github.dshpet.mobile.modules

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import io.github.dshpet.mobile.ModuleIds
import io.github.dshpet.mobile.PetHandle
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Shake the phone and the pet wobbles, gets thrown a little and settles back.
 *
 * Only the accelerometer is required (every phone has one); no permission is
 * involved. The sensor is registered only while the screen is unlocked, so an
 * idle phone costs nothing.
 */
class ShakeModule : HostBoundModule() {
    override val id = ModuleIds.SHAKE

    private lateinit var sensors: SensorManager
    private lateinit var power: PowerManager
    private var accel: Sensor? = null
    private var listening = false
    private var receiverRegistered = false

    private val gravity = FloatArray(3)
    private var energy = 0.0f
    private var tick = 0
    private var lastImpact = 0L
    private var leanTarget = 0.0
    private val random = Random.Default

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = syncSensor()
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
            val v = event.values
            for (i in 0..2) gravity[i] = gravity[i] * 0.82f + v[i] * 0.18f
            val lx = v[0] - gravity[0]
            val ly = v[1] - gravity[1]
            val lz = v[2] - gravity[2]
            val magnitude = sqrt(lx * lx + ly * ly + lz * lz)
            if (magnitude > energy) energy = magnitude
            // Sideways knock becomes a visual lean; it decays on its own.
            leanTarget = (lx / 9.81f * 1.05f).coerceIn(-0.60f, 0.60f).toDouble()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    override fun onEnable() {
        if (!::sensors.isInitialized) {
            sensors = host.context.getSystemService(SensorManager::class.java)
            power = host.context.getSystemService(PowerManager::class.java)
            accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        }
        if (accel == null) return
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(host.context, screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
        syncSensor()
    }

    override fun onDisable() {
        if (listening) {
            runCatching { sensors.unregisterListener(sensorListener) }
            listening = false
        }
        if (receiverRegistered) {
            runCatching { host.context.unregisterReceiver(screenReceiver) }
            receiverRegistered = false
        }
        host.pets.forEach { it.engine.tilt = 0.0; it.engine.tiltVelocity = 0.0 }
    }

    private fun syncSensor() {
        val want = accel != null && power.isInteractive
        if (want == listening) return
        if (want) {
            listening = runCatching {
                sensors.registerListener(sensorListener, accel, SensorManager.SENSOR_DELAY_GAME)
            }.getOrDefault(false)
        } else {
            runCatching { sensors.unregisterListener(sensorListener) }
            listening = false
            energy = 0f
            host.pets.forEach { it.engine.tilt = 0.0; it.engine.tiltVelocity = 0.0 }
        }
    }

    override fun onTick(dt: Double) {
        if (!listening) return
        energy *= exp(-2.8 * dt).toFloat()
        leanTarget *= exp(-1.5 * dt)
        tick++
        if (tick % 2 != 0) return
        val strength = host.options.shakeStrength.toDouble()
        // Roughly 0.35 g of quick movement: distinct from ordinary walking.
        if (energy < 2.0f || host.now() - lastImpact < 200) return
        lastImpact = host.now()
        energy *= 0.35f
        for (pet in host.pets) {
            if (pet.isCompanion && !host.options.companions) continue
            val kick = (160.0 + 560.0 * strength) * (if (random.nextBoolean()) 1 else -1)
            pet.engine.nudge(kick * 0.015, 0.0, kick, -(70.0 + 220.0 * strength))
            host.squash(pet)
        }
    }

    override fun onFrame(pet: PetHandle, dt: Double) {
        if (!listening) {
            pet.engine.tilt = 0.0
            return
        }
        val engine = pet.engine
        val target = (leanTarget * (0.8 + 1.0 * host.options.shakeStrength))
            .coerceIn(-0.55, 0.55)
        val stiffness = 70.0
        val damping = 8.5
        val accel = (target - engine.tilt) * stiffness - engine.tiltVelocity * damping
        engine.tiltVelocity += accel * dt
        engine.tilt += engine.tiltVelocity * dt
        if (abs(engine.tilt) < 1e-4 && abs(engine.tiltVelocity) < 1e-4) {
            engine.tilt = 0.0
            engine.tiltVelocity = 0.0
        }
    }

    /** Debug hook: pretend the phone was just shaken (no sensor involved). */
    fun simulateShake(strength: Float = 6f) {
        energy = maxOf(energy, strength)
        leanTarget = 0.38
    }

    override fun diagnostics(): String =
        "sensors=${if (accel == null) "none" else "accel"} listening=$listening energy=${"%.1f".format(energy)}"
}
