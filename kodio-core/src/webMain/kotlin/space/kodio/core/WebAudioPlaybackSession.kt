@file:OptIn(ExperimentalWasmJsInterop::class)

package space.kodio.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import space.kodio.core.io.files.AudioFileFormat
import space.kodio.core.io.files.AudioFileReadError
import space.kodio.core.io.files.EncodedAudio
import space.kodio.core.AudioPlaybackSession.State
import web.audio.*
import web.events.EventHandler
import kotlin.coroutines.coroutineContext
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * TODO: support output device selection when setSinkId becomes widely adapted (https://developer.mozilla.org/en-US/docs/Web/API/AudioContext/setSinkId)
 */
class WebAudioPlaybackSession() : BaseAudioPlaybackSession() {

    private var audioContext: AudioContext? = null
    private var encodedElement: JsAny? = null
    private val activeSources = mutableSetOf<AudioBufferSourceNode>()

    override suspend fun preparePlayback(format: AudioFormat): AudioFormat {
        val contextOptions = createAudioContextOptions(
            latencyHint = AudioContextLatencyCategory.playback,
            sampleRate = format.sampleRate
        )
        val context = AudioContext(contextOptions)
        audioContext = context
        return toWebPlaybackFormat(format)
    }

    /**
     * Web Audio's [AudioBuffer] always stores samples as Float32 per channel, so we
     * normalize whatever the source produces to interleaved Float32 here. The
     * actual normalization is performed by [BaseAudioPlaybackSession.play] via
     * [convertAudio], which means [playBlocking] only ever sees Float32 LE bytes.
     */
    private fun toWebPlaybackFormat(format: AudioFormat): AudioFormat {
        val enc = format.encoding
        if (enc is SampleEncoding.PcmFloat &&
            enc.precision == FloatPrecision.F32 &&
            enc.layout == SampleLayout.Interleaved
        ) return format
        return AudioFormat(
            sampleRate = format.sampleRate,
            channels = format.channels,
            encoding = SampleEncoding.PcmFloat(FloatPrecision.F32, SampleLayout.Interleaved)
        )
    }

    override suspend fun playBlocking(audioFlow: AudioFlow) {
        @Suppress("MISSING_DEPENDENCY_SUPERCLASS_IN_TYPE_ARGUMENT")
        val audioFormat = audioFlow.format
        val context = audioContext ?: return
        var nextStartTime = context.currentTime
        val lastCompletable = audioFlow.map { audioData ->
            val jsAudioBufferFinishedIndicator = CompletableDeferred<Unit>()

            // Ensure context is not closed and we are still playing
            if (context.state != AudioContextState.running || state.value != State.Playing) {
                jsAudioBufferFinishedIndicator.complete(Unit)
                coroutineContext.cancel()
                return@map jsAudioBufferFinishedIndicator
            }

            // 2. Create AudioBuffer
            val audioBuffer = context.createBufferFrom(
                format = audioFormat,
                data = audioData,
            )
            // 3. Create a source and play it
            val source = context.createBufferSource()
            source.buffer = audioBuffer
            source.playbackRate.value = playbackSpeed.value
            source.onended = EventHandler {
                activeSources -= source
                jsAudioBufferFinishedIndicator.complete(Unit)
            }
            source.connect(context.destination)
            activeSources += source

            // Schedule playback. Wait if the context time hasn't caught up yet.
            val scheduleTime = if (nextStartTime < context.currentTime) context.currentTime else nextStartTime
            source.start(scheduleTime)

            // Update the start time for the next buffer
            nextStartTime = scheduleTime + audioBuffer.duration / playbackSpeed.value.toDouble()

            jsAudioBufferFinishedIndicator
        }.lastOrNull()
        lastCompletable?.await()
    }

    override suspend fun loadEncodedAudio(encodedAudio: EncodedAudio): Duration? {
        if (encodedAudio.fileFormat !is AudioFileFormat.Mp3) {
            throw AudioFileReadError.UnsupportedFormat(
                "Direct playback is only implemented for MP3 on web targets."
            )
        }
        val element = createEncodedAudioElement(encodedAudio.toByteArray(), encodedAudio.fileFormat.mimeType)
        loadEncodedAudioElement(element)
        try {
            withTimeout(5_000) {
                while (encodedAudioElementReadyState(element) < 1) {
                    delay(25.milliseconds)
                }
            }
        } catch (e: Throwable) {
            encodedAudioElementStopAndRelease(element)
            throw e
        }
        val duration = encodedAudioElementDuration(element)
        releaseLoadedEncodedAudio()
        encodedElement = element
        encodedAudioElementSetPlaybackRate(element, playbackSpeed.value)
        return if (duration.isFinite() && duration >= 0.0) duration.seconds else null
    }

    override suspend fun playEncodedAudioBlocking(encodedAudio: EncodedAudio, startPosition: Duration) {
        val element = encodedElement ?: return
        encodedAudioElementSetCurrentTime(element, startPosition.inWholeMilliseconds / 1000.0)
        encodedAudioElementPlay(element)
        while (!encodedAudioElementEnded(element)) {
            val playError = encodedAudioElementPlayError(element)
            if (playError != null) {
                throw AudioFileReadError.InvalidFile("Unable to start MP3 playback: $playError")
            }
            delay(50.milliseconds)
        }
    }

    override fun seekLoadedEncodedAudio(position: Duration) {
        encodedElement?.let {
            encodedAudioElementSetCurrentTime(it, position.inWholeMilliseconds / 1000.0)
        }
    }

    override fun onPlaybackSpeedChanged(speed: Float) {
        activeSources.forEach { it.playbackRate.value = speed }
        encodedElement?.let { encodedAudioElementSetPlaybackRate(it, speed) }
    }

    override fun onPause() {
        encodedElement?.let(::encodedAudioElementPause)
        scope.launch { audioContext?.suspend() }
    }

    override fun onResume() {
        encodedElement?.let(::encodedAudioElementPlay)
        scope.launch { audioContext?.resume() }
    }

    override fun onStop() {
        encodedElement?.let {
            encodedAudioElementPause(it)
            encodedAudioElementSetCurrentTime(it, 0.0)
        }
        val context = audioContext?:return
        audioContext = null
        activeSources.clear()
        scope.launch { context.close() }
    }

    override fun releaseLoadedEncodedAudio() {
        encodedElement?.let(::encodedAudioElementStopAndRelease)
        encodedElement = null
    }
}
