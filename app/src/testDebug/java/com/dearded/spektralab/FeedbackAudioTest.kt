package com.dearded.spektralab

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FeedbackAudioTest {
    @Test fun everyCameraSoundUsesMediaRegardlessOfRingerMode() {
        val app = RuntimeEnvironment.getApplication()
        val view = View(app)
        Feedback.warm(app)
        val pool = ReflectionHelpers.getStaticField<SoundPool>(Feedback::class.java, "pool")
        val attributes = ReflectionHelpers.getField<AudioAttributes>(pool, "mAttributes")
        assertEquals(AudioAttributes.USAGE_MEDIA, attributes.usage)
        assertEquals(AudioManager.STREAM_MUSIC, attributes.volumeControlStream)

        val sounds = mapOf(
            Cue.Shutter to R.raw.snd_shutter, Cue.Ratchet to R.raw.snd_ratchet,
            Cue.Counter to R.raw.snd_counter, Cue.Agitate to R.raw.snd_agitate,
            Cue.Saved to R.raw.snd_saved, Cue.Rewind to R.raw.snd_rewind, Cue.Load to R.raw.snd_load,
        )
        val playback = shadowOf(pool)
        sounds.values.forEach { playback.notifyResourceLoaded(it, true) }
        val prefs = app.getSharedPreferences("settings", 0)
        val audio = app.getSystemService(AudioManager::class.java)!!
        prefs.edit().putBoolean(CAMERA_SOUND, true).apply()
        for (mode in listOf(AudioManager.RINGER_MODE_NORMAL, AudioManager.RINGER_MODE_VIBRATE, AudioManager.RINGER_MODE_SILENT)) {
            audio.ringerMode = mode
            playback.clearPlayed()
            for ((cue, res) in sounds) {
                Feedback.play(view, cue)
                assertTrue("$cue with ringer mode $mode", playback.wasResourcePlayed(res))
            }
        }

        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        prefs.edit().putBoolean(CAMERA_SOUND, false).apply()
        playback.clearPlayed()
        for ((cue, res) in sounds) {
            Feedback.play(view, cue)
            assertFalse("$cue while camera sound is off", playback.wasResourcePlayed(res))
        }
        prefs.edit().remove(CAMERA_SOUND).apply()
    }
}
