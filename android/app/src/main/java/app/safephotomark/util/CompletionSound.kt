package app.safephotomark.util

import android.content.Context
import android.media.MediaPlayer
import app.safephotomark.R

object CompletionSound {
    fun play(context: Context) {
        try {
            val player = MediaPlayer.create(context, R.raw.completion_sound) ?: return
            player.setOnCompletionListener { it.release() }
            player.setOnErrorListener { mp, _, _ ->
                mp.release()
                true
            }
            player.start()
        } catch (_: Exception) {
        }
    }
}
