package com.jerrynifemi.blobs

import android.app.Activity
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.sqrt

class MainActivity : Activity(), SensorEventListener {

    private lateinit var bowl: BlobView
    private var sensorManager: SensorManager? = null
    private var lastShakeAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        lightStatusBar()

        bowl = findViewById(R.id.bowl)
        val hint = findViewById<TextView>(R.id.hint)
        val fab = findViewById<View>(R.id.fabAdd)

        bowl.onSound = { id -> Sound.play(this, id) }
        bowl.onSpawn = { bowl.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
        bowl.onPop = { bowl.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
        bowl.onBounce = { }

        fab.setOnClickListener {
            val jitter = (Math.random() - 0.5).toFloat() * dp(160f)
            bowl.addBlob(width / 2f + jitter, height * 0.32f)
        }
        fab.alpha = 0f
        fab.scaleX = 0.6f
        fab.scaleY = 0.6f
        fab.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setStartDelay(380)
            .setDuration(620)
            .setInterpolator(Motion.spring(0.6f, 1.6f))
            .start()

        hint.alpha = 0f
        hint.translationY = -dp(12f)
        hint.animate().alpha(1f).translationY(0f)
            .setStartDelay(220)
            .setDuration(520)
            .setInterpolator(Motion.spring(0.8f, 1.8f))
            .start()
        hint.postDelayed({
            hint.animate().alpha(0f).translationY(-dp(14f))
                .setDuration(520)
                .withEndAction { hint.visibility = View.GONE }
                .start()
        }, 5200)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
    }

    override fun onResume() {
        super.onResume()
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val gx = event.values[0]
        val gy = event.values[1]
        val gz = event.values[2]
        val force = sqrt(gx * gx + gy * gy + gz * gz)
        val now = System.currentTimeMillis()
        if (force > 27f && now - lastShakeAt > 800) {
            lastShakeAt = now
            bowl.wobbleAll()
            bowl.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun lightStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
