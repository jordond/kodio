package space.kodio.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.write
import space.kodio.core.MacosAudioQueueProperty.CurrentDevice
import platform.AVFAudio.AVAudioPlayer
import platform.Foundation.NSURL
import space.kodio.core.io.files.AudioFileFormat
import space.kodio.core.io.files.AudioFileReadError
import space.kodio.core.io.files.EncodedAudio
import space.kodio.core.util.namedLogger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val logger = namedLogger("PlaybackSession")

/**
 * Mac OS implementation for [AudioPlaybackSession] using Core Audio AudioQueue (output).
 */
@ExperimentalForeignApi
class MacosAudioPlaybackSession(
    private val requestedDevice: AudioDevice.Output? = null,
    private val bufferDurationSec: Double = 0.05, // ≈50ms buffer
    private val bufferCount: Int = 5              // 5 buffers for smoother playback
) : BaseAudioPlaybackSession() {

    private lateinit var audioQueue: MacosAudioQueue.Writable
    private var paused = false
    private var outputFormat: AudioFormat? = null
    private var encodedPlayer: AVAudioPlayer? = null
    private var encodedTempPath: Path? = null

    override suspend fun preparePlayback(format: AudioFormat): AudioFormat {
        logger.debug { "preparePlayback called with format: $format" }

        if (::audioQueue.isInitialized) {
            audioQueue.dispose(inImmediate = true)
        }

        val playbackFormat = toDevicePlaybackFormat(format)
        outputFormat = playbackFormat
        logger.debug { "Output format: $outputFormat" }
        
        audioQueue = MacosAudioQueue.createOutput(
            format = playbackFormat,
            bufferCount = bufferCount,
            bufferDurationSec = bufferDurationSec
        )
        audioQueue.setPlaybackRate(playbackSpeed.value)
        logger.debug { "AudioQueue created successfully" }
        
        if (requestedDevice != null) {
            logger.debug { "Setting device: ${requestedDevice.name} (${requestedDevice.id})" }
            audioQueue.setPropertyValue(CurrentDevice, requestedDevice.id)
        }

        if (isDeviceFriendly(format)) {
            // For well-supported formats, return the INPUT format to skip
            // the slow BigDecimal-based convertAudio(); playBlocking handles
            // fast mono-to-stereo duplication when needed.
            return format
        }
        // For formats CoreAudio AudioQueue may not handle reliably
        // (24-bit packed int, unsigned, big-endian, etc.), return the
        // normalized Float32 format so BaseAudioPlaybackSession.play()
        // runs convertAudio() to produce device-compatible bytes.
        return playbackFormat
    }

    /**
     * Float32 and signed-16-bit-LE are the two formats CoreAudio
     * AudioQueue handles without issue. Everything else gets
     * normalized to Float32 stereo.
     */
    private fun isDeviceFriendly(format: AudioFormat): Boolean = when (val enc = format.encoding) {
        is SampleEncoding.PcmFloat -> true
        is SampleEncoding.PcmInt ->
            enc.bitDepth == IntBitDepth.Sixteen &&
            enc.signed &&
            enc.endianness == Endianness.Little
    }

    private fun toDevicePlaybackFormat(format: AudioFormat): AudioFormat {
        val channels = if (format.channels == Channels.Mono) Channels.Stereo else format.channels
        if (isDeviceFriendly(format)) {
            return format.copy(channels = channels)
        }
        return AudioFormat(
            sampleRate = format.sampleRate,
            channels = channels,
            encoding = SampleEncoding.PcmFloat(FloatPrecision.F32, SampleLayout.Interleaved)
        )
    }

    override suspend fun playBlocking(audioFlow: AudioFlow) {
        logger.debug { "playBlocking: input=${audioFlow.format}, output=$outputFormat" }
        
        // Fast mono-to-stereo conversion (bypasses slow BigDecimal convertAudio)
        val playbackFlow = if (audioFlow.format.channels == Channels.Mono && outputFormat?.channels == Channels.Stereo) {
            logger.debug { "Doing fast mono-to-stereo conversion" }
            val bytesPerSample = audioFlow.format.bytesPerSample
            AudioFlow(
                format = outputFormat!!,
                data = audioFlow.map { chunk ->
                    // Duplicate each sample for left and right channels
                    val stereo = ByteArray(chunk.size * 2)
                    var src = 0
                    var dst = 0
                    while (src < chunk.size) {
                        // Copy sample to left channel
                        repeat(bytesPerSample) { b ->
                            stereo[dst + b] = chunk[src + b]
                        }
                        // Copy same sample to right channel
                        repeat(bytesPerSample) { b ->
                            stereo[dst + bytesPerSample + b] = chunk[src + b]
                        }
                        src += bytesPerSample
                        dst += bytesPerSample * 2
                    }
                    stereo
                }
            )
        } else {
            audioFlow
        }
        
        // Stream data to queue - start() will be called after initial buffers are primed
        logger.debug { "Starting stream (queue will start after priming)" }
        audioQueue.streamFromWithPriming(playbackFlow, bufferCount)
        logger.debug { "streamFrom completed" }
    }

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun loadEncodedAudio(encodedAudio: EncodedAudio): Duration? {
        if (encodedAudio.fileFormat !is AudioFileFormat.Mp3) {
            throw AudioFileReadError.UnsupportedFormat(
                "Direct playback is only implemented for MP3 on macOS."
            )
        }
        val bytes = encodedAudio.toByteArray()
        val path = writeEncodedTempFile(bytes, encodedAudio.fileFormat.extension)
        val url = NSURL.fileURLWithPath(path.toString())
        val player = runErrorCatching { errorVar ->
            AVAudioPlayer(contentsOfURL = url, error = errorVar)
        }.getOrElse {
            throw AudioFileReadError.InvalidFile("Unable to load MP3 data: ${it.message}", it)
        }
        if (!player.prepareToPlay()) {
            runCatching { SystemFileSystem.delete(path, mustExist = false) }
            throw AudioFileReadError.InvalidFile("Unable to prepare MP3 data for playback.")
        }
        player.enableRate = true
        player.rate = playbackSpeed.value
        releaseLoadedEncodedAudio()
        encodedPlayer = player
        encodedTempPath = path
        return player.duration.seconds
    }

    override suspend fun playEncodedAudioBlocking(encodedAudio: EncodedAudio, startPosition: Duration) {
        val player = encodedPlayer ?: return
        player.currentTime = startPosition.inWholeMilliseconds / 1000.0
        if (!player.play()) {
            throw AudioFileReadError.InvalidFile("Unable to start MP3 playback.")
        }
        while (player.isPlaying() || state.value is AudioPlaybackSession.State.Paused) {
            delay(50.milliseconds)
        }
    }

    override fun seekLoadedEncodedAudio(position: Duration) {
        encodedPlayer?.currentTime = position.inWholeMilliseconds / 1000.0
    }

    override fun onPlaybackSpeedChanged(speed: Float) {
        encodedPlayer?.let {
            it.enableRate = true
            it.rate = speed
        }
        if (::audioQueue.isInitialized) {
            audioQueue.setPlaybackRate(speed)
        }
    }

    override fun onPause() {
        encodedPlayer?.pause()
        if (paused) return
        if (!::audioQueue.isInitialized) return
        audioQueue.pause()
        paused = true
    }

    override fun onResume() {
        encodedPlayer?.play()
        if (!paused) return
        if (!::audioQueue.isInitialized) return
        audioQueue.start()
        paused = false
    }

    override fun onStop() {
        encodedPlayer?.let {
            it.stop()
            it.currentTime = 0.0
        }
        if (::audioQueue.isInitialized) {
            audioQueue.stop(inImmediate = true)
        }
    }

    override fun releaseLoadedEncodedAudio() {
        encodedPlayer?.stop()
        encodedPlayer = null
        encodedTempPath?.let {
            runCatching { SystemFileSystem.delete(it, mustExist = false) }
        }
        encodedTempPath = null
    }

    private fun writeEncodedTempFile(bytes: ByteArray, extension: String): Path {
        val path = Path(
            SystemTemporaryDirectory,
            "kodio-${kotlin.random.Random.nextLong().toString(16)}.$extension"
        )
        SystemFileSystem.sink(path).use { sink ->
            val buffer = Buffer().apply { write(bytes) }
            sink.write(buffer, buffer.size)
        }
        return path
    }
}
