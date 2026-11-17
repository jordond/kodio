package space.kodio.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.channels.SendChannel
import platform.AVFAudio.AVAudioConverter
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioInputNode
import platform.Foundation.NSError
import space.kodio.core.objc.KodioEngineInputNode
import space.kodio.core.objc.KodioEnginePrepare
import space.kodio.core.objc.KodioEngineStart
import space.kodio.core.objc.KodioEngineStop
import space.kodio.core.objc.KodioNodeInstallTap
import space.kodio.core.objc.KodioNodeOutputFormat
import space.kodio.core.objc.KodioNodeRemoveTap
import space.kodio.core.io.convert
import space.kodio.core.io.toByteArray
import space.kodio.core.util.namedLogger

private val log = namedLogger("AVAudioRecording")

/**
 * Recording through an `AVAudioEngine`. The calls that raise an `NSException` (the input
 * node and its format with no usable input, a tap, the engine's start) go through the ObjC
 * shim and throw an [AVAudioRaisedException] instead, which Kotlin can catch.
 */
@OptIn(ExperimentalForeignApi::class)
abstract class AVAudioRecordingSession(
    private val format: AudioFormat = DefaultAppleRecordingAudioFormat
) : BaseAudioRecordingSession() {

    private val audioEngine = AVAudioEngine()
    private lateinit var resolvedAVFormat: AVAudioFormat
    private lateinit var converter: AVAudioConverter

    abstract fun prepareAudioSession()

    private fun inputNode(): AVAudioInputNode {
        var node: AVAudioInputNode? = null
        KodioEngineInputNode(audioEngine) { node = it }.throwIfRaised()
        return node ?: throw AVAudioEngineException.FailedToStart("The engine has no input node")
    }

    private fun AVAudioInputNode.hardwareFormat(): AVAudioFormat {
        var format: AVAudioFormat? = null
        KodioNodeOutputFormat(this, 0u) { format = it }.throwIfRaised()
        return format ?: throw AVAudioEngineException.FailedToStart("The input node has no format")
    }

    private fun startEngine(failure: String) {
        var failed = false
        var error: NSError? = null
        KodioEngineStart(audioEngine) {
            failed = true
            error = it
        }.throwIfRaised()
        if (failed) {
            val description = error?.localizedDescription ?: "Unknown error"
            log.error { "$failure: $description" }
            throw AVAudioEngineException.FailedToStart(description)
        }
    }

    override suspend fun prepareRecording(): AudioFormat {
        log.info { "prepareRecording() called with requested format: $format" }
        prepareAudioSession()

        resolvedAVFormat = try {
            format.toAVAudioFormat()
        } catch (e: Exception) {
            log.warn(e) {
                "Requested format $format is not supported by AVAudioFormat / converter; " +
                    "using ${DefaultAppleRecordingAudioFormat}"
            }
            DefaultAppleRecordingAudioFormat.toAVAudioFormat()
        }
        log.info {
            "Resolved AVAudioFormat: sampleRate=${resolvedAVFormat.sampleRate}, " +
                "channels=${resolvedAVFormat.channelCount}, commonFormat=${resolvedAVFormat.commonFormat}, " +
                "interleaved=${resolvedAVFormat.isInterleaved()}"
        }

        val hardwareIosAudioFormat = inputNode().hardwareFormat()
        log.info {
            "Hardware input format (inputNode.outputFormatForBus(0)): " +
                "sampleRate=${hardwareIosAudioFormat.sampleRate}, " +
                "channels=${hardwareIosAudioFormat.channelCount}, " +
                "commonFormat=${hardwareIosAudioFormat.commonFormat}, " +
                "interleaved=${hardwareIosAudioFormat.isInterleaved()}"
        }

        converter = AVAudioConverter(hardwareIosAudioFormat, resolvedAVFormat)
        log.info { "Created AVAudioConverter: hardware -> resolved" }

        KodioEnginePrepare(audioEngine).throwIfRaised()
        val resultFormat = resolvedAVFormat.toCommonAudioFormat()
        log.info { "Engine prepared, returning format: $resultFormat" }
        return resultFormat
    }

    override suspend fun startRecording(channel: SendChannel<ByteArray>) {
        val inputNode = inputNode()
        val hardwareIosAudioFormat = inputNode.hardwareFormat()
        log.info {
            "startRecording(): hardware format sampleRate=${hardwareIosAudioFormat.sampleRate}, " +
                "channels=${hardwareIosAudioFormat.channelCount}, " +
                "commonFormat=${hardwareIosAudioFormat.commonFormat}"
        }

        log.info { "Installing tap on bus 0 with bufferSize=1024" }
        KodioNodeInstallTap(inputNode, 0u, 1024u, hardwareIosAudioFormat) { buffer, _ ->
            if (buffer != null) {
                // A Kotlin exception must not leave the tap: AVFAudio calls it, and would abort.
                runCatching {
                    val bufferInTargetFormat = converter.convert(buffer, resolvedAVFormat)
                    val bufferData = bufferInTargetFormat.toByteArray()
                    if (bufferData != null && channel.trySend(bufferData).isFailure) {
                        log.warn { "Failed to emit ${bufferData.size} bytes" }
                    }
                }.onFailure { log.error(it) { "Dropped a recorded buffer: ${it.message}" } }
            }
        }.throwIfRaised()

        log.info { "Starting audio engine" }
        startEngine("Engine failed to start")
        log.info { "Audio engine started successfully" }
    }

    override fun cleanup() {
        log.info { "cleanup(): isRunning=${audioEngine.isRunning()}" }
        // Each step is tried whatever became of the one before, and none may throw: cleanup
        // also runs when the recording job is closed.
        runCatching {
            if (audioEngine.isRunning())
                KodioEngineStop(audioEngine).throwIfRaised()
        }.onFailure { log.error(it) { "Failed to stop the engine: ${it.message}" } }
        runCatching {
            val inputNode = inputNode()
            KodioNodeRemoveTap(inputNode, 0u).throwIfRaised()
        }.onFailure { log.error(it) { "Failed to remove the tap: ${it.message}" } }
        audioEngine.reset()
        log.info { "cleanup() complete" }
    }

    override suspend fun pauseRecording() {
        // Native pause: AVAudioEngine.pause() halts capture but keeps the
        // engine prepared and the tap installed, so resume is cheap.
        if (audioEngine.isRunning()) {
            log.info { "pauseRecording(): pausing AVAudioEngine" }
            audioEngine.pause()
        }
    }

    override suspend fun resumeRecording() {
        log.info { "resumeRecording(): restarting AVAudioEngine" }
        startEngine("Engine failed to restart after pause")
    }
}