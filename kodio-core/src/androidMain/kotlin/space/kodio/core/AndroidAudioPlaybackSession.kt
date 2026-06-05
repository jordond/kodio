package space.kodio.core

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.media.AudioRecord
import android.media.AudioTrack
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import space.kodio.core.io.files.AudioFileFormat
import space.kodio.core.io.files.AudioFileReadError
import space.kodio.core.io.files.EncodedAudio
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import android.media.AudioFormat as AndroidAudioFormat

internal class AndroidAudioPlaybackSession(
    private val context: Context,
    private val requestedDevice: AudioDevice.Output?
) : BaseAudioPlaybackSession() {

    private var audioTrack: AudioTrack? = null
    private lateinit var preparedFormat: AudioFormat
    private var androidEncoding: Int = AndroidAudioFormat.ENCODING_INVALID
    private var androidChannelMask: Int = 0
    private var mediaPlayer: MediaPlayer? = null

    override suspend fun preparePlayback(format: AudioFormat): AudioFormat {
        ensureInterleaved(format) // AudioTrack expects interleaved frames

        // Derive Android constants
        androidChannelMask = format.channels.toAndroidChannelOutMask()
        androidEncoding = format.toAndroidEncoding()

        if (androidEncoding == AndroidAudioFormat.ENCODING_INVALID)
            error("$format is not supported by the device")

        // Min buffer size
        val minBufferSize = AudioTrack.getMinBufferSize(
            /* sampleRateInHz = */ format.sampleRate,
            /* channelConfig  = */ androidChannelMask,
            /* audioFormat    = */ androidEncoding
        )
        if (minBufferSize == AudioRecord.ERROR_BAD_VALUE) error("$format is not supported by the device")
        if (minBufferSize == AudioRecord.ERROR) error("Failed to get min buffer size")

        val playbackBufferSize = minBufferSize * 4 // a bit of headroom
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AndroidAudioFormat.Builder()
                    .setSampleRate(format.sampleRate)
                    .setChannelMask(androidChannelMask)
                    .setEncoding(@SuppressLint("WrongConstant") androidEncoding)
                    .build()
            )
            .setBufferSizeInBytes(playbackBufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (requestedDevice != null) setPreferredDevice(context, requestedDevice, track)

        // Keep old behavior: align playback rate to stream sample rate
        // (For modern apps, PlaybackParams is preferred; this preserves your logic.)
        track.playbackRate = format.sampleRate
        track.playbackParams = playbackParamsFor(playbackSpeed.value)
        track.setVolume(AudioTrack.getMaxVolume())

        this.audioTrack = track
        this.preparedFormat = format
        return format
    }

    override suspend fun playBlocking(audioFlow: AudioFlow) {
        val track = audioTrack ?: return
        track.play()

        when (preparedFormat.encoding) {
            is SampleEncoding.PcmInt -> {
                // Bytes are already in target PCM-int format; write directly.
                audioFlow.collect { chunk ->
                    // For 24-bit packed/32-bit, write(byte[]) also works on modern APIs.
                    track.write(chunk, 0, chunk.size)
                }
            }
            is SampleEncoding.PcmFloat -> {
                // Convert LE IEEE-754 bytes to float[] and use float write().
                // If upstream already produces float[], you can adapt the flow to emit float[] instead.
                require(androidEncoding == AndroidAudioFormat.ENCODING_PCM_FLOAT)
                audioFlow.collect { chunk ->
                    val floats = bytesToFloatArrayLE(chunk)
                    // WRITE_BLOCKING to keep behavior similar to your original
                    track.write(floats, 0, floats.size, AudioTrack.WRITE_BLOCKING)
                }
            }
        }
    }

    override suspend fun loadEncodedAudio(encodedAudio: EncodedAudio): Duration? {
        if (encodedAudio.fileFormat !is AudioFileFormat.Mp3) {
            throw AudioFileReadError.UnsupportedFormat(
                "Direct playback is only implemented for MP3 on Android."
            )
        }
        val bytes = encodedAudio.toByteArray()
        val player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            setDataSource(ByteArrayMediaDataSource(bytes))
            prepare()
        }
        if (requestedDevice != null) setPreferredDevice(context, requestedDevice, player)
        releaseLoadedEncodedAudio()
        mediaPlayer = player
        applyPlaybackSpeedToMediaPlayer(player, playbackSpeed.value)
        return player.duration.milliseconds
    }

    override suspend fun playEncodedAudioBlocking(encodedAudio: EncodedAudio, startPosition: Duration) {
        val player = mediaPlayer ?: return
        val finished = CompletableDeferred<Unit>()
        player.setOnCompletionListener { finished.complete(Unit) }
        player.setOnErrorListener { _, what, extra ->
            finished.completeExceptionally(RuntimeException("Android MediaPlayer error what=$what extra=$extra"))
            true
        }
        player.seekTo(startPosition.inWholeMilliseconds.toInt())
        player.start()
        finished.await()
    }

    override fun onPlaybackSpeedChanged(speed: Float) {
        audioTrack?.playbackParams = playbackParamsFor(speed)
        mediaPlayer?.let { applyPlaybackSpeedToMediaPlayer(it, speed) }
    }

    override fun onPause() {
        mediaPlayer?.pause()
        audioTrack?.pause()
    }

    override fun onResume() {
        mediaPlayer?.start()
        audioTrack?.play()
    }

    override fun onStop() {
        mediaPlayer?.let { player ->
            runCatching {
                if (player.isPlaying) player.pause()
                player.seekTo(0)
            }
        }
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }

    override fun releaseLoadedEncodedAudio() {
        mediaPlayer?.release()
        mediaPlayer = null
    }
}

private fun playbackParamsFor(speed: Float): PlaybackParams =
    PlaybackParams()
        .setSpeed(speed)
        .setPitch(1.0f)

private fun applyPlaybackSpeedToMediaPlayer(player: MediaPlayer, speed: Float) {
    player.playbackParams = playbackParamsFor(speed)
}

/* -------------------- Helpers used above -------------------- */

private fun ensureInterleaved(fmt: AudioFormat) {
    val ok = when (val e = fmt.encoding) {
        is SampleEncoding.PcmInt   -> e.layout == SampleLayout.Interleaved
        is SampleEncoding.PcmFloat -> e.layout == SampleLayout.Interleaved
    }
    require(ok) { "Android AudioTrack requires interleaved frames." }
}


private fun setPreferredDevice(context: Context, requestedDevice: AudioDevice.Output, audioTrack: AudioTrack) {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    val selectedDevice = devices.firstOrNull { it.id.toString() == requestedDevice.id }
    if (selectedDevice != null) audioTrack.preferredDevice = selectedDevice
}

private fun setPreferredDevice(context: Context, requestedDevice: AudioDevice.Output, mediaPlayer: MediaPlayer) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    val selectedDevice = devices.firstOrNull { it.id.toString() == requestedDevice.id }
    if (selectedDevice != null) mediaPlayer.preferredDevice = selectedDevice
}

private class ByteArrayMediaDataSource(private val bytes: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= bytes.size) return -1
        val length = minOf(size, bytes.size - position.toInt())
        bytes.copyInto(buffer, destinationOffset = offset, startIndex = position.toInt(), endIndex = position.toInt() + length)
        return length
    }

    override fun getSize(): Long = bytes.size.toLong()

    override fun close() = Unit
}

/** Convert little-endian IEEE-754 Float32 bytes to a FloatArray. */
private fun bytesToFloatArrayLE(bytes: ByteArray): FloatArray {
    require(bytes.size % 4 == 0) { "PCM Float32 byte length must be multiple of 4." }
    val out = FloatArray(bytes.size / 4)
    var i = 0
    var j = 0
    while (i < bytes.size) {
        val b0 = bytes[i].toInt() and 0xFF
        val b1 = bytes[i + 1].toInt() and 0xFF
        val b2 = bytes[i + 2].toInt() and 0xFF
        val b3 = bytes[i + 3].toInt() and 0xFF
        val bits = (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
        out[j++] = Float.fromBits(bits)
        i += 4
    }
    return out
}
