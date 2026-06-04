package space.kodio.core

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.map
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.write
import platform.AVFAudio.AVAudioConverter
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMFormatFloat32
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioPlayerNode
import platform.Foundation.NSURL
import space.kodio.core.io.files.AudioFileFormat
import space.kodio.core.io.files.AudioFileReadError
import space.kodio.core.io.files.EncodedAudio
import space.kodio.core.io.convertSimple
import space.kodio.core.io.toIosAudioBuffer
import space.kodio.core.util.namedLogger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val log = namedLogger("AVAudioPlayback")

abstract class AVAudioPlaybackSession() : BaseAudioPlaybackSession() {
    
    private val engine = AVAudioEngine()
    private val player = AVAudioPlayerNode()
    private lateinit var standardAVFormat: AVAudioFormat
    private lateinit var interleavedAVFormat: AVAudioFormat
    private var deinterleaveConverter: AVAudioConverter? = null
    private var encodedPlayer: AVAudioPlayer? = null
    private var encodedTempPath: Path? = null

    init {
        engine.attachNode(player)
        log.info { "Attached player node to engine" }
    }

    abstract fun configureAudioSession()

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    override suspend fun preparePlayback(format: AudioFormat): AudioFormat {
        log.info { "preparePlayback() called with format: $format" }

        val playbackFormat = toNativePlaybackFormat(format)
        if (engine.isRunning() && ::standardAVFormat.isInitialized &&
            standardAVFormat.sampleRate == playbackFormat.sampleRate.toDouble() &&
            standardAVFormat.channelCount == playbackFormat.channels.count.toUInt()
        ) {
            log.info {
                "preparePlayback(): engine already running with matching format, skipping setup"
            }
            return playbackFormat
        }
        if (playbackFormat != format) {
            log.info { "Format not natively supported, will convert to: $playbackFormat" }
        }

        // Interleaved format matching what the conversion pipeline produces
        interleavedAVFormat = AVAudioFormat(
            commonFormat = AVAudioPCMFormatFloat32,
            sampleRate = playbackFormat.sampleRate.toDouble(),
            channels = playbackFormat.channels.count.toUInt(),
            interleaved = true
        )

        // AVAudioEngine on iOS requires the standard (non-interleaved Float32) format
        standardAVFormat = AVAudioFormat(
            standardFormatWithSampleRate = playbackFormat.sampleRate.toDouble(),
            channels = playbackFormat.channels.count.toUInt()
        )
        log.info {
            "Standard AVAudioFormat: sampleRate=${standardAVFormat.sampleRate}, " +
                "channels=${standardAVFormat.channelCount}, commonFormat=${standardAVFormat.commonFormat}, " +
                "interleaved=${standardAVFormat.isInterleaved()}, isStandard=${standardAVFormat.isStandard()}"
        }

        deinterleaveConverter = if (playbackFormat.channels.count > 1) {
            AVAudioConverter(fromFormat = interleavedAVFormat, toFormat = standardAVFormat).also {
                log.info { "Created AVAudioConverter for interleaved -> non-interleaved conversion" }
            }
        } else null

        val mainMixerOutputFormat = engine.mainMixerNode.outputFormatForBus(0u)
        log.info {
            "mainMixerNode outputFormatForBus(0): sampleRate=${mainMixerOutputFormat.sampleRate}, " +
                "channels=${mainMixerOutputFormat.channelCount}, commonFormat=${mainMixerOutputFormat.commonFormat}"
        }

        log.info { "Connecting player -> mainMixerNode with standard avFormat" }
        engine.connect(player, engine.mainMixerNode, standardAVFormat)

        log.info { "Configuring audio session" }
        configureAudioSession()

        log.info { "Starting engine" }
        runErrorCatching { errorVar ->
            engine.startAndReturnError(errorVar)
        }.onFailure {
            log.error(it) { "Engine failed to start: ${it.message}" }
            throw AVAudioEngineException.FailedToStart(it.message ?: "Unknown error")
        }
        log.info { "Engine started successfully" }
        return playbackFormat
    }

    /**
     * Always normalize to interleaved Float32 for the conversion pipeline.
     * The actual AVAudioEngine connection uses the standard (non-interleaved)
     * format; an AVAudioConverter handles the deinterleaving per-buffer.
     */
    private fun toNativePlaybackFormat(format: AudioFormat): AudioFormat {
        val enc = format.encoding
        if (enc is SampleEncoding.PcmFloat && enc.precision == FloatPrecision.F32) return format
        return AudioFormat(
            sampleRate = format.sampleRate,
            channels = format.channels,
            encoding = SampleEncoding.PcmFloat(FloatPrecision.F32, SampleLayout.Interleaved)
        )
    }

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    override suspend fun playBlocking(audioFlow: AudioFlow) {
        log.info { "playBlocking() called with format: ${audioFlow.format}" }
        player.play()
        val converter = deinterleaveConverter
        val bufferFormat = if (converter != null) interleavedAVFormat else standardAVFormat
        log.info {
            "Scheduling buffers: bufferFormat interleaved=${bufferFormat.isInterleaved()}, " +
                "converter=${converter != null}"
        }
        var bufferCount = 0
        val lastCompletable = audioFlow.map { bytes ->
            bufferCount++
            var iosAudioBuffer = bytes.toIosAudioBuffer(bufferFormat)
            if (converter != null) {
                iosAudioBuffer = converter.convertSimple(iosAudioBuffer, standardAVFormat)
            }
            if (bufferCount <= 3 || bufferCount % 50 == 0) {
                log.info { "Scheduling buffer #$bufferCount: ${bytes.size} bytes, ${iosAudioBuffer.frameLength} frames" }
            }
            val iosAudioBufferFinishedIndicator = CompletableDeferred<Unit>()
            player.scheduleBuffer(iosAudioBuffer) {
                iosAudioBufferFinishedIndicator.complete(Unit)
            }
            iosAudioBufferFinishedIndicator
        }.lastOrNull()
        log.info { "Awaiting last buffer (total scheduled: $bufferCount)" }
        lastCompletable?.await()
        log.info { "playBlocking() finished" }
    }

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun loadEncodedAudio(encodedAudio: EncodedAudio): Duration? {
        if (encodedAudio.fileFormat !is AudioFileFormat.Mp3) {
            throw AudioFileReadError.UnsupportedFormat(
                "Direct playback is only implemented for MP3 on Apple targets."
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
        configureAudioSession()
        if (!player.prepareToPlay()) {
            runCatching { SystemFileSystem.delete(path, mustExist = false) }
            throw AudioFileReadError.InvalidFile("Unable to prepare MP3 data for playback.")
        }
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

    override fun onPause() {
        log.info { "onPause()" }
        encodedPlayer?.pause()
        if (player.isPlaying())
            player.pause()
    }

    override fun onResume() {
        log.info { "onResume()" }
        encodedPlayer?.play()
        player.play()
    }

    override fun onStop() {
        log.info { "onStop()" }
        encodedPlayer?.let {
            it.stop()
            it.currentTime = 0.0
        }
        if (player.isPlaying())
            player.stop()
        engine.stop()
        engine.disconnectNodeOutput(player)
        log.info { "Engine stopped and nodes disconnected" }
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
