package com.example.videotranslator.audio

import android.media.MediaPlayer
import android.media.PlaybackParams
import android.util.Log
import java.io.File

private const val TAG = "SegmentAudioPlayer"

/**
 * High-performance, pitch-preserved audio player for pre-rendered TTS speech segments.
 *
 * Uses Android's native `MediaPlayer` with `setPlaybackParams(PlaybackParams().setSpeed(ratio))`.
 * This scales playback duration to match the target segment timing while preserving natural voice pitch.
 */
class SegmentAudioPlayer {

    private var mediaPlayer: MediaPlayer? = null
    private var currentFile: File? = null

    fun playSegment(audioFile: File, speedRatio: Float, onComplete: (() -> Unit)? = null) {
        if (!audioFile.exists() || audioFile.length() == 0L) {
            Log.w(TAG, "Audio file does not exist or is empty: ${audioFile.absolutePath}")
            return
        }

        stop()

        try {
            val mp = MediaPlayer().apply {
                setDataSource(audioFile.absolutePath)
                prepare()

                // Apply pitch-preserved speed scaling (API 23+)
                val clampedRatio = speedRatio.coerceIn(0.75f, 1.5f)
                val params = PlaybackParams().apply {
                    speed = clampedRatio
                    pitch = 1.0f // Preserve natural pitch
                }
                playbackParams = params

                setOnCompletionListener {
                    stop()
                    onComplete?.invoke()
                }

                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                    stop()
                    true
                }

                start()
            }
            mediaPlayer = mp
            currentFile = audioFile
            Log.d(TAG, "Playing segment audio: ${audioFile.name} (speed=${"%.2f".format(speedRatio)}x, duration=${mp.duration}ms)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play segment audio file ${audioFile.absolutePath}", e)
            stop()
        }
    }

    fun stop() {
        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                reset()
                release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping MediaPlayer: ${e.message}")
        } finally {
            mediaPlayer = null
            currentFile = null
        }
    }

    fun pause() {
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error pausing MediaPlayer: ${e.message}")
        }
    }

    fun resume() {
        try {
            if (mediaPlayer != null && !mediaPlayer!!.isPlaying) {
                mediaPlayer?.start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error resuming MediaPlayer: ${e.message}")
        }
    }

    fun isPlaying(): Boolean {
        return try {
            mediaPlayer?.isPlaying == true
        } catch (_: Exception) {
            false
        }
    }

    fun release() {
        stop()
    }
}
