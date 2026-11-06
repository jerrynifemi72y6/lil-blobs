package com.jerrynifemi.blobs

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A bowl of liquid glass. Blobs drift, squish against the walls, collide, stretch
 * under your finger, fling, and burst into droplets when held.
 *
 * Physics runs off Choreographer with a clamped delta so a stalled frame cannot
 * explode the simulation.
 */
class BlobView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    class Blob(
        var x: Float,
        var y: Float,
        var vx: Float = 0f,
        var vy: Float = 0f,
        var r: Float,
        val color: Int,
        var sx: Float = 1f,
        var sy: Float = 1f,
        var svx: Float = 0f,
        var svy: Float = 0f,
        var phase: Float = 0f,
        var life: Float = 1f,
        var particle: Boolean = false,
        var spawn: Float = 1f
    ) {
        var shaderR: Float = -1f
        var body: RadialGradient? = null
        var shadow: RadialGradient? = null
        var bounce: RadialGradient? = null
        var spec: RadialGradient? = null
    }

    var onPop: (() -> Unit)? = null
    var onSpawn: ((Float) -> Unit)? = null
    var onBounce: ((Float) -> Unit)? = null

    // ------------------------------------------------------------- gyro tilt

    private val sensorManager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager?

    private var tiltX = 0f
    private var tiltY = 0f
    private var spin = 0f
    private var clock = 0f
    private var lastShoveAt = 0L

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    // portrait: +x is screen right, +y is screen down
                    tiltX = (-event.values[0] / 9.81f).coerceIn(-1.3f, 1.3f)
                    tiltY = (event.values[1] / 9.81f).coerceIn(-1.3f, 1.3f)
                }
                Sensor.TYPE_GYROSCOPE -> {
                    spin = spin * 0.86f + event.values[2] * 0.14f
                    if (hypot(event.values[0], event.values[1]) > 4.2f) shove()
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun startSensors() {
        val sm = sensorManager ?: return
        sm.registerListener(sensorListener, sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_GAME)
        sm.registerListener(sensorListener, sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE), SensorManager.SENSOR_DELAY_GAME)
    }

    private fun stopSensors() {
        sensorManager?.unregisterListener(sensorListener)
    }

    /** A quick twist of the wrist knocks the whole bowl around. */
    private fun shove() {
        val now = System.currentTimeMillis()
        if (now - lastShoveAt < 600) return
        lastShoveAt = now
        wobbleAll()
        playSound(2)
    }

    private val density = resources.displayMetrics.density
    private val GRAVITY = 620f
    private var lastTapAt = 0L
    private var lastTapBlob: Blob? = null
    private val palette = intArrayOf(
        Color.parseColor("#FF9E80"),
        Color.parseColor("#7FE3C0"),
        Color.parseColor("#8FC9FF"),
        Color.parseColor("#C0A6FF"),
        Color.parseColor("#FFD46E"),
        Color.parseColor("#FF9EC4"),
        Color.parseColor("#7ED9EA")
    )

    private val blobs = ArrayList<Blob>()
    private val particles = ArrayList<Blob>()

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()

    private var dragged: Blob? = null
    private var touchX = 0f
    private var touchY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var moved = false
    private var lastSoundAt = 0L

    private var lastFrameNanos = 0L
    private var running = false

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val dt = if (lastFrameNanos == 0L) 1f / 60f
            else ((frameTimeNanos - lastFrameNanos) / 1_000_000_000.0).toFloat().coerceIn(0.001f, 0.033f)
            lastFrameNanos = frameTimeNanos
            step(dt)
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        lastFrameNanos = 0L
        startSensors()
        Choreographer.getInstance().postFrameCallback(frame)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        running = false
        stopSensors()
        Choreographer.getInstance().removeFrameCallback(frame)
        backdrop?.recycle()
        backdrop = null
    }

    /** Fill the bowl with a few blobs, dropping them in from above. */
    fun seed() {
        if (width == 0) {
            post { seed() }
            return
        }
        blobs.clear()
        particles.clear()
        val count = 5
        for (i in 0 until count) {
            val r = dp(34f) + Random.nextFloat() * dp(26f)
            val b = Blob(
                x = width * (0.18f + 0.64f * Random.nextFloat()),
                y = height * (0.22f + 0.5f * Random.nextFloat()),
                vx = Random.nextFloat() * 90f - 45f,
                vy = Random.nextFloat() * 60f - 30f,
                r = r,
                color = palette[(i * 3 + 1) % palette.size]
            )
            b.spawn = -0.12f * i
            blobs.add(b)
        }
    }

    fun addBlob(x: Float = width / 2f, y: Float = height / 2f) {
        if (blobs.size >= 9) {
            burst(blobs.first())
            return
        }
        val b = Blob(
            x = x.coerceIn(dp(50f), max(dp(50f), width - dp(50f))),
            y = y.coerceIn(dp(120f), max(dp(120f), height - dp(140f))),
            vx = Random.nextFloat() * 120f - 60f,
            vy = Random.nextFloat() * 80f - 40f,
            r = dp(32f) + Random.nextFloat() * dp(24f),
            color = palette[Random.nextInt(palette.size)],
            spawn = 0f
        )
        b.sx = 0.2f
        b.sy = 0.2f
        blobs.add(b)
        onSpawn?.invoke(b.x / max(1f, width.toFloat()))
    }

    fun wobbleAll() {
        for (b in blobs) {
            b.vx += (Random.nextFloat() - 0.5f) * 700f
            b.vy += (Random.nextFloat() - 0.5f) * 700f
            b.sx = 1.2f
            b.sy = 0.82f
        }
        playSound(2)
    }

    private fun dp(value: Float): Float = value * density

    private fun step(dt: Float) {
        if (width == 0 || height == 0) return
        val w = width.toFloat()
        val h = height.toFloat()

        clock += dt
        spin *= 0.985f
        if (abs(spin) > 0.2f) {
            val cx = w / 2f
            val cy = h / 2f
            for (b in blobs) {
                val dx = b.x - cx
                val dy = b.y - cy
                val len = max(1f, hypot(dx, dy))
                b.vx += (-dy / len) * spin * 240f * dt
                b.vy += (dx / len) * spin * 240f * dt
            }
        }

        val move = blobs.toMutableList()
        for (b in move) {
            b.spawn = min(1f, b.spawn + dt * 3.2f)
            val target = dragged
            if (target === b) {
                // softer pull, so the blob lags the finger and stretches
                b.vx = (touchX - b.x) * 9f
                b.vy = (touchY - b.y) * 9f
            } else {
                b.vx *= 0.991f
                b.vy *= 0.991f
                b.vx += tiltX * GRAVITY * dt
                b.vy += tiltY * GRAVITY * dt
                // slow convection: a settled bowl should never look frozen
                b.vx += sin((clock * 0.6f + b.phase).toDouble()).toFloat() * 42f * dt
                b.vy += cos((clock * 0.5f + b.phase).toDouble()).toFloat() * 42f * dt
            }
            b.x += b.vx * dt
            b.y += b.vy * dt

            springScale(b, dt)

            val rx = b.r * max(b.sx, b.sy)
            if (b.x - rx < dp(6f)) {
                b.x = dp(6f) + rx
                if (abs(b.vx) > 120f) impact(b, 1f, 0f)
                b.vx = abs(b.vx) * 0.78f
            } else if (b.x + rx > w - dp(6f)) {
                b.x = w - dp(6f) - rx
                if (abs(b.vx) > 120f) impact(b, -1f, 0f)
                b.vx = -abs(b.vx) * 0.78f
            }
            if (b.y - rx < dp(6f)) {
                b.y = dp(6f) + rx
                if (abs(b.vy) > 120f) impact(b, 0f, 1f)
                b.vy = abs(b.vy) * 0.78f
            } else if (b.y + rx > h - dp(6f)) {
                b.y = h - dp(6f) - rx
                if (abs(b.vy) > 120f) impact(b, 0f, -1f)
                b.vy = -abs(b.vy) * 0.78f
            }
        }

        // blob against blob
        mergeLoop@ for (i in blobs.indices) {
            for (j in i + 1 until blobs.size) {
                val a = blobs[i]
                val b = blobs[j]
                if (a.spawn < 0.5f || b.spawn < 0.5f) continue
                val dx = b.x - a.x
                val dy = b.y - a.y
                val dist = hypot(dx, dy)
                val minDist = (a.r * max(a.sx, a.sy)) + (b.r * max(b.sx, b.sy))
                if (dist > 0.01f && dist < minDist) {
                    val held = dragged
                    if (held != null && (a === held || b === held) && dist < minDist * 0.55f) {
                        mergeInto(if (a === held) a else b, if (a === held) b else a)
                        break@mergeLoop
                    }
                    val nx = dx / dist
                    val ny = dy / dist
                    val push = (minDist - dist) * 0.5f
                    a.x -= nx * push
                    a.y -= ny * push
                    b.x += nx * push
                    b.y += ny * push
                    val rel = (b.vx - a.vx) * nx + (b.vy - a.vy) * ny
                    if (rel < 0f) {
                        val imp = rel * 0.85f
                        a.vx += imp * nx
                        a.vy += imp * ny
                        b.vx -= imp * nx
                        b.vy -= imp * ny
                        if (abs(rel) > 200f) {
                            impact(a, nx, ny)
                            impact(b, -nx, -ny)
                        }
                    }
                }
            }
        }

        // droplets
        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.vy += 900f * dt
            p.vx *= 0.99f
            p.life -= dt * 1.5f
            p.r *= 0.995f
            if (p.life <= 0f) it.remove()
        }
    }

    /** Double tap pinches one blob into two children that fly apart. */
    private fun split(b: Blob) {
        val half = sqrt(b.r * b.r / 2f).coerceAtLeast(dp(18f))
        val angle = Random.nextFloat() * Math.PI.toFloat() * 2f
        val dx = cos(angle) * half
        val dy = sin(angle) * half
        b.r = half
        b.vx = -cos(angle) * 340f
        b.vy = -sin(angle) * 340f
        b.sx = 1.15f
        b.sy = 0.88f
        if (blobs.size < 9) {
            blobs.add(
                Blob(
                    x = b.x + dx,
                    y = b.y + dy,
                    vx = cos(angle) * 340f,
                    vy = sin(angle) * 340f,
                    r = half,
                    color = b.color,
                    phase = Random.nextFloat() * 6.28f
                )
            )
        }
        playSound(2)
        onSpawn?.invoke(b.x / max(1f, width.toFloat()))
    }

    private fun mergeInto(keep: Blob, gone: Blob) {
        val merged = sqrt(keep.r * keep.r + gone.r * gone.r).coerceAtMost(dp(115f))
        keep.r = merged
        keep.x = (keep.x + gone.x) / 2f
        keep.y = (keep.y + gone.y) / 2f
        keep.sx = 1.2f
        keep.sy = 0.84f
        keep.svx = 0f
        keep.svy = 0f
        blobs.remove(gone)
        if (dragged === gone) dragged = keep
        playSound(4)
        onPop?.invoke()
    }

    private fun springScale(b: Blob, dt: Float) {
        val k = 240f
        val c = 17f
        b.svx += (k * (1f - b.sx) - c * b.svx) * dt
        b.svy += (k * (1f - b.sy) - c * b.svy) * dt
        b.sx += b.svx * dt
        b.sy += b.svy * dt
        b.sx = b.sx.coerceIn(0.55f, 1.6f)
        b.sy = b.sy.coerceIn(0.55f, 1.6f)
    }

    private fun impact(b: Blob, nx: Float, ny: Float) {
        b.sx = 1f + 0.18f * abs(ny) + 0.1f * abs(nx)
        b.sy = 1f + 0.18f * abs(nx) + 0.1f * abs(ny)
        b.svx = 0f
        b.svy = 0f
        playSound(1)
        onBounce?.invoke(b.x / max(1f, width.toFloat()))
    }

    // ---------------------------------------------------------------- sound

    var onSound: ((Int) -> Unit)? = null

    private fun playSound(id: Int) {
        val now = System.currentTimeMillis()
        if (id == 1 && now - lastSoundAt < 70) return
        lastSoundAt = now
        onSound?.invoke(id)
    }

    // ---------------------------------------------------------------- burst

    private var longPressed = false

    private val longPressRunnable = Runnable {
        val target = dragged
        if (target != null && !moved) {
            longPressed = true
            burst(target)
        }
    }

    private fun armLongPress() {
        longPressed = false
        removeCallbacks(longPressRunnable)
        postDelayed(longPressRunnable, 420)
    }

    private fun disarmLongPress() {
        removeCallbacks(longPressRunnable)
    }

    fun burst(b: Blob) {
        blobs.remove(b)
        if (dragged === b) dragged = null
        for (i in 0 until 8) {
            val angle = (Math.PI * 2.0 * i / 8.0).toFloat() + Random.nextFloat() * 0.4f
            val speed = 240f + Random.nextFloat() * 320f
            particles.add(
                Blob(
                    x = b.x,
                    y = b.y,
                    vx = kotlin.math.cos(angle) * speed,
                    vy = kotlin.math.sin(angle) * speed - 120f,
                    r = b.r * (0.15f + Random.nextFloat() * 0.13f),
                    color = b.color,
                    life = 1f,
                    particle = true
                )
            )
        }
        playSound(4)
        onPop?.invoke()
    }

    // ---------------------------------------------------------------- input

    private fun findBlob(x: Float, y: Float): Blob? {
        for (i in blobs.indices.reversed()) {
            val b = blobs[i]
            if (b.spawn < 0.3f) continue
            val reach = b.r * max(b.sx, b.sy)
            if (hypot(x - b.x, y - b.y) <= reach) return b
        }
        return null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                touchX = event.x
                touchY = event.y
                downAt = System.currentTimeMillis()
                moved = false
                val hit = findBlob(event.x, event.y)
                val now = System.currentTimeMillis()
                if (hit != null && hit === lastTapBlob && now - lastTapAt < 300L && hit.r > dp(30f)) {
                    split(hit)
                    lastTapAt = 0L
                    lastTapBlob = null
                    return true
                }
                lastTapAt = now
                lastTapBlob = hit
                dragged = hit
                if (hit != null) {
                    hit.sx = 1.07f
                    hit.sy = 0.95f
                    playSound(0)
                }
                armLongPress()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                touchX = event.x
                touchY = event.y
                if (hypot(event.x - downX, event.y - downY) > dp(12f)) {
                    moved = true
                    disarmLongPress()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val target = dragged
                dragged = null
                disarmLongPress()
                if (target != null) {
                    target.sx = 0.9f
                    target.sy = 1.12f
                    target.svx = 0f
                    target.svy = 0f
                    playSound(3)
                } else if (!moved && !longPressed) {
                    addBlob(event.x, event.y)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // ---------------------------------------------------------------- drawing

    private var bgShader: Shader? = null
    private var glowTop: RadialGradient? = null
    private var glowBottom: RadialGradient? = null
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bgShader = android.graphics.LinearGradient(
            0f, 0f, w * 0.4f, h.toFloat(),
            intArrayOf(Color.parseColor("#FFF7F2"), Color.parseColor("#EEF6FF"), Color.parseColor("#F6F1FF")),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        glowTop = RadialGradient(
            w * 0.15f, h * 0.08f, max(w, h) * 0.55f,
            intArrayOf(Color.argb(190, 255, 196, 168), Color.argb(0, 255, 196, 168)),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        glowBottom = RadialGradient(
            w * 0.9f, h * 0.92f, max(w, h) * 0.6f,
            intArrayOf(Color.argb(170, 150, 226, 214), Color.argb(0, 150, 226, 214)),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        bakeBackdrop(w, h)
        if (blobs.isEmpty()) seed()
    }

    private var backdrop: android.graphics.Bitmap? = null

    private fun bakeBackdrop(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        backdrop?.recycle()
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        bgPaint.shader = bgShader
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)
        glowTop?.let {
            bgPaint.shader = it
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)
        }
        glowBottom?.let {
            bgPaint.shader = it
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)
        }
        bgPaint.shader = null
        backdrop = bmp
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val baked = backdrop
        if (baked != null) {
            // the backdrop drifts against the tilt, so the bowl feels deep
            canvas.save()
            canvas.translate(width / 2f - tiltX * dp(16f), height / 2f - tiltY * dp(12f))
            canvas.scale(1.07f, 1.07f)
            canvas.translate(-width / 2f, -height / 2f)
            canvas.drawBitmap(baked, 0f, 0f, null)
            canvas.restore()
        } else {
            canvas.drawColor(Color.parseColor("#FFF7F2"))
        }
        for (b in blobs) drawBlob(canvas, b, 1f)
        for (p in particles) drawBlob(canvas, p, p.life.coerceIn(0f, 1f))
        dragged?.let { drawNeck(canvas, it) }
    }

    /** While you drag, a chain of shrinking glossy beads links blob to finger. */
    private fun drawNeck(canvas: Canvas, b: Blob) {
        val dx = touchX - b.x
        val dy = touchY - b.y
        val dist = hypot(dx, dy)
        if (dist < b.r * 1.05f) return
        val steps = 8
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            val x = b.x + dx * t
            val y = b.y + dy * t
            val r = (b.r * (1f - t) * 0.78f).coerceAtLeast(dp(4f) * (1f - t))
            if (r < 0.6f) continue
            neckPaint.shader = null
            neckPaint.color = lighten(b.color, 0.18f + 0.3f * t)
            neckPaint.alpha = (215 * (1f - t * 0.45f)).toInt()
            canvas.drawCircle(x, y, r, neckPaint)
        }
        neckPaint.color = lighten(b.color, 0.55f)
        neckPaint.alpha = 120
        canvas.drawCircle(touchX, touchY, dp(10f), neckPaint)
    }

    private val neckPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun ensureShaders(b: Blob) {
        if (b.shaderR == b.r) return
        b.shaderR = b.r
        val r = b.r
        b.shadow = RadialGradient(
            0f, r * 0.45f, r * 1.2f,
            intArrayOf(alphaOf(b.color, 80), alphaOf(b.color, 0)),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        b.body = RadialGradient(
            -r * 0.30f, -r * 0.36f, r * 1.5f,
            intArrayOf(lighten(b.color, 0.74f), lighten(b.color, 0.10f), darken(b.color, 0.12f)),
            floatArrayOf(0f, 0.52f, 1f), Shader.TileMode.CLAMP
        )
        b.bounce = RadialGradient(
            0f, r * 0.66f, r * 0.85f,
            intArrayOf(Color.argb(90, 255, 255, 255), Color.argb(0, 255, 255, 255)),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        b.spec = RadialGradient(
            0f, 0f, r * 0.44f,
            intArrayOf(Color.argb(235, 255, 255, 255), Color.argb(0, 255, 255, 255)),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
    }

    private fun drawBlob(canvas: Canvas, b: Blob, alphaMul: Float) {
        ensureShaders(b)
        val grow = 0.6f + 0.4f * easeOutBack(b.spawn.coerceIn(0f, 1f))
        val r = b.r * grow
        if (r < 1f) return
        val layer = (255 * alphaMul).toInt().coerceIn(0, 255)

        canvas.save()
        canvas.translate(b.x, b.y)
        canvas.scale(b.sx, b.sy)

        b.shadow?.let {
            shaderPaint.shader = it
            shaderPaint.alpha = (layer * 0.5f).toInt()
            canvas.drawCircle(0f, r * 0.45f, r * 1.2f, shaderPaint)
        }

        b.body?.let {
            shaderPaint.shader = it
            shaderPaint.alpha = layer
            canvas.drawCircle(0f, 0f, r, shaderPaint)
        }

        clip.reset()
        clip.addCircle(0f, 0f, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        b.bounce?.let {
            shaderPaint.shader = it
            shaderPaint.alpha = (layer * 0.9f).toInt()
            canvas.drawCircle(0f, 0f, r, shaderPaint)
        }
        canvas.restore()

        rimPaint.strokeWidth = dp(1.4f)
        rimPaint.shader = null
        rimPaint.color = Color.argb((layer * 0.8f).toInt(), 255, 255, 255)
        canvas.drawCircle(0f, 0f, r - dp(0.8f), rimPaint)

        cellShine(canvas, b, r, layer)

        canvas.restore()
    }

    private fun cellShine(canvas: Canvas, b: Blob, r: Float, layer: Int) {
        canvas.save()
        canvas.translate(-r * 0.30f, -r * 0.38f)
        b.spec?.let {
            shaderPaint.shader = it
            shaderPaint.alpha = layer
            canvas.drawCircle(0f, 0f, r * 0.44f, shaderPaint)
        }
        canvas.restore()

        glossPaint.color = Color.argb((layer * 0.85f).toInt(), 255, 255, 255)
        canvas.drawCircle(r * 0.28f, -r * 0.10f, r * 0.09f, glossPaint)
        canvas.drawCircle(r * 0.10f, r * 0.30f, r * 0.05f, glossPaint)
    }

    private fun easeOutBack(t: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val p = t - 1f
        return 1f + c3 * p * p * p + c1 * p * p
    }

    private fun alphaOf(color: Int, a: Int): Int =
        Color.argb(a.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun lighten(color: Int, amount: Float): Int {
        val r = (Color.red(color) + (255 - Color.red(color)) * amount).toInt().coerceIn(0, 255)
        val g = (Color.green(color) + (255 - Color.green(color)) * amount).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) + (255 - Color.blue(color)) * amount).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun darken(color: Int, amount: Float): Int {
        val r = (Color.red(color) * (1f - amount)).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * (1f - amount)).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * (1f - amount)).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }
}
