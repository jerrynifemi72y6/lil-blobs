package com.jerrynifemi.blobs

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/** Six squishy noises, one pool. Ids are shared with BlobView. */
object Sound {

    const val GRAB = 0
    const val BOUNCE = 1
    const val WOBBLE = 2
    const val RELEASE = 3
    const val POP = 4
    const val SPAWN = 5

    private var pool: SoundPool? = null
    private val ids = HashMap<Int, Int>()

    private val res = intArrayOf(
        R.raw.sfx_grab,
        R.raw.sfx_bounce,
        R.raw.sfx_wobble,
        R.raw.sfx_release,
        R.raw.sfx_pop,
        R.raw.sfx_spawn
    )

    private fun ensure(context: Context): SoundPool {
        pool?.let { return it }
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val sp = SoundPool.Builder().setMaxStreams(6).setAudioAttributes(attrs).build()
        res.forEachIndexed { index, resId -> ids[index] = sp.load(context.applicationContext, resId, 1) }
        pool = sp
        return sp
    }

    fun play(context: Context, id: Int) {
        val sp = ensure(context)
        val soundId = ids[id] ?: return
        val volume = when (id) {
            BOUNCE -> 0.55f
            GRAB -> 0.5f
            else -> 0.85f
        }
        sp.play(soundId, volume, volume, 1, 0, 1f)
    }
}
