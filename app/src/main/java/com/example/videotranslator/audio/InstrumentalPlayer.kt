package com.example.videotranslator.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "InstrumentalPlayer"

/** Sample rate matches what AudioExtractor produces */
private const val SAMPLE_RATE = 16_000

/** 50 ms of stereo audio per write chunk → 16000 * 2 channels * 0.05s = 1600 shorts */
private const val CHUNK_SHORTS = SAMPLE_RATE * 2 / 20

/**
 * Plays a centre-channel-cancelled stereo PCM track (produced by [AudioExtractor])
 * via [AudioTrack] in streaming mode, tightly synchronised to ExoPlayer.
 *
 * **Synchronisation contract** (called from ViewModel's Player.Listener):
 *  - [play]       — start playing from a given video position in milliseconds
 *  - [pause]      — stop AudioTrack immediately (called when ExoPlayer pauses)
 *  - [resumeFrom] — called when ExoPlayer resumes; seeks to current video position
 *  - [seekTo]     — called on user seek events; jumps to new position instantly
 *  - [stop]       — full stop (end of video or language switch back to Hindi)
 *  - [release]    — tear-down (ViewModel.onCleared)
 *
 * **Volume ducking**: [setVolume] lets the ViewModel duck the instrumental track
 * while TTS is speaking (e.g. 0.10f) then restore it (0.25f) when TTS finishes.
 */
class InstrumentalPlayer(private val scope: CoroutineScope) {

    // ── PCM data (loaded from disk once) ─────────────────────────────────────
    private var pcm: ShortArray = ShortArray(0)
    val isLoaded: Boolean get() = pcm.isNotEmpty()

    // ── AudioTrack ────────────────────────────────────────────────────────────
    private var audioTrack: AudioTrack? = null
    private val minBufBytes: Int = AudioTrack.getMinBufferSize(
        SAMPLE_RATE,
        AudioFormat.CHANNEL_OUT_STEREO,
        AudioFormat.ENCODING_PCM_16BIT
    ).coerceAtLeast(CHUNK_SHORTS * 2 * 2) // at least 2 fill-chunks

    // ── Coroutine fill job ────────────────────────────────────────────────────
    private var fillJob: Job? = null

    // ── State ─────────────────────────────────────────────────────────────────
    @Volatile private var currentVolume: Float = 0.25f

    // ─────────────────────────────────── public API ───────────────────────────

    /** Load stereo PCM from disk. Safe to call on main thread (fast for typical file sizes). */
    fun loadFromFile(file: File) {
        if (!file.exists()) { Log.w(TAG, "Instrumental file not found: $file"); return }
        val bytes = file.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        pcm = ShortArray(buf.remaining()).also { buf.get(it) }
        Log.d(TAG, "Loaded ${pcm.size} stereo shorts (~${pcm.size / (SAMPLE_RATE * 2)}s)")
    }

    /** Start playback from [positionMs]. Stops any current playback first. */
    fun play(positionMs: Long) {
        if (!isLoaded) return
        stopInternal()           // cancel fill job + stop/flush AudioTrack
        val at = getOrCreateTrack()
        at.play()
        val startSample = positionToSample(positionMs).coerceIn(0, pcm.size)
        startFill(at, startSample)
    }

    /** Pause immediately (ExoPlayer paused). */
    fun pause() {
        fillJob?.cancel()
        audioTrack?.pause()
        Log.d(TAG, "Paused")
    }

    /** Resume from exact video position (called on ExoPlayer resume). */
    fun resumeFrom(positionMs: Long) = play(positionMs)

    /** Jump to a new position (called on ExoPlayer seek). */
    fun seekTo(positionMs: Long) = play(positionMs)

    /** Full stop (end of video or language switch). */
    fun stop() {
        stopInternal()
        Log.d(TAG, "Stopped")
    }

    /** Duck / restore volume during TTS. Range [0f, 1f]. */
    fun setVolume(volume: Float) {
        currentVolume = volume
        audioTrack?.setVolume(volume)
    }

    /** Release AudioTrack resources (call from ViewModel.onCleared). */
    fun release() {
        fillJob?.cancel()
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        pcm = ShortArray(0)
        Log.d(TAG, "Released")
    }

    // ─────────────────────────────────── internals ────────────────────────────

    private fun stopInternal() {
        fillJob?.cancel()
        fillJob = null
        val at = audioTrack ?: return
        try {
            at.pause()   // pause first to unblock any write() calls
            at.flush()   // discard buffered data
        } catch (_: Exception) {}
    }

    private fun getOrCreateTrack(): AudioTrack {
        val existing = audioTrack
        if (existing != null && existing.state == AudioTrack.STATE_INITIALIZED) return existing

        existing?.release()
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(minBufBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also {
                it.setVolume(currentVolume)
                audioTrack = it
            }
    }

    /**
     * Coroutine that continuously feeds [CHUNK_SHORTS]-sized chunks of [pcm]
     * into [at], starting from sample index [from].
     *
     * `AudioTrack.write()` blocks when the internal buffer is full, which
     * gives us natural pacing without a sleep loop.
     */
    private fun startFill(at: AudioTrack, from: Int) {
        fillJob = scope.launch(Dispatchers.IO) {
            var idx = from
            while (isActive && idx < pcm.size) {
                val end = minOf(idx + CHUNK_SHORTS, pcm.size)
                val written = at.write(pcm, idx, end - idx)
                if (written <= 0) break   // track released or error
                idx += written
            }
            Log.d(TAG, "Fill done at sample $idx / ${pcm.size}")
        }
    }

    /** Convert video position (ms) → stereo sample index (accounts for 2 channels). */
    private fun positionToSample(positionMs: Long): Int =
        ((positionMs * SAMPLE_RATE / 1000L) * 2).toInt()  // ×2 for stereo interleave
}
