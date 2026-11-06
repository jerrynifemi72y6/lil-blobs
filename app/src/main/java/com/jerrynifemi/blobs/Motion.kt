package com.jerrynifemi.blobs

import android.view.animation.Interpolator
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** Damped-spring easing: overshoots a little, settles naturally. */
class SpringInterpolator(
    private val damping: Float = 0.62f,
    private val frequency: Float = 1.9f
) : Interpolator {

    override fun getInterpolation(t: Float): Float {
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f
        val w = (2.0 * Math.PI * frequency).toFloat()
        val z = damping.toDouble()
        return if (z < 1.0) {
            val wd = (w * sqrt((1.0 - z * z).toFloat()))
            val e = exp((-z * w * t).toDouble()).toFloat()
            (1f - e * (cos(wd * t) + ((z * w / wd).toFloat() * sin(wd * t))))
        } else {
            (1f - exp((-w * t).toDouble()).toFloat() * (1f + w * t))
        }
    }
}

object Motion {
    fun spring(damping: Float = 0.62f, frequency: Float = 1.9f): Interpolator =
        SpringInterpolator(damping, frequency)
}
