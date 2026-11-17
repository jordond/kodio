package space.kodio.core

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.io.readByteArray
import space.kodio.core.io.collectAsSource
import space.kodio.core.io.convertAudio
import space.kodio.core.io.encodeToByteArray
import space.kodio.core.io.files.EncodedAudio
import space.kodio.core.util.namedLogger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

private val logger = namedLogger("NativeMacosPlayback")

/**
 * Native macOS playback session implementation using Panama FFI.
 *
 * Implements [AudioPlaybackSession] directly rather than extending [BaseAudioPlaybackSession]
 * because the native layer is callback/upcall-driven and owns state transitions via FFI.
 * That model is incompatible with the base class pull-loop contract and would create dual
 * state ownership, risking the SIGSEGV/lifecycle issues this code was written to avoid.
 *
 * State transitions are managed on the JVM side. A background coroutine on [Dispatchers.IO]
 * awaits the native terminal state via a blocking downcall, avoiding Panama upcalls from
 * Kotlin/Native GCD threads (which cause SIGSEGV due to JVM stack-walking failures).
 */
internal class NativeMacosAudioPlaybackSession(
    private val nativeMemSeq: MemorySegment,
    requestedDevice: AudioDevice.Output?,
) : AudioPlaybackSession {

    private val _state = MutableStateFlow<AudioPlaybackSession.State>(AudioPlaybackSession.State.Idle)
    override val state: StateFlow<AudioPlaybackSession.State> = _state.asStateFlow()

    private val _audioFlowHolder = MutableStateFlow<AudioFlow?>(null)
    override val audioFlow: StateFlow<AudioFlow?> = _audioFlowHolder.asStateFlow()

    private val _encodedAudio = MutableStateFlow<EncodedAudio?>(null)
    override val encodedAudio: StateFlow<EncodedAudio?> = _encodedAudio.asStateFlow()

    private val _position = MutableStateFlow(Duration.ZERO)
    override val position: StateFlow<Duration> = _position.asStateFlow()

    private val _duration = MutableStateFlow<Duration?>(null)
    override val duration: StateFlow<Duration?> = _duration.asStateFlow()

    private val _canSeek = MutableStateFlow(false)
    override val canSeek: StateFlow<Boolean> = _canSeek.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(AudioPlaybackSession.DEFAULT_PLAYBACK_SPEED)
    override val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private var runtimeArena: Arena? = null
    private var loaded = false
    private var loadedRecording: AudioRecording? = null
    private var resumeShouldPlayFromSeek = false
    private var completionJob: Job? = null
    private var positionJob: Job? = null
    private var positionStartedAt: TimeSource.Monotonic.ValueTimeMark? = null
    private var positionAtStart: Duration = Duration.ZERO
    private val completionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mp3Backend = JvmMp3PlaybackBackend(requestedDevice)

    override suspend fun load(audioFlow: AudioFlow) {
        stopPositionTracking(updatePosition = false)
        completionJob?.cancel()
        mp3Backend.release()
        _audioFlowHolder.value = audioFlow
        _encodedAudio.value = null
        loadedRecording = null
        resumeShouldPlayFromSeek = false
        _position.value = Duration.ZERO
        _duration.value = null
        _canSeek.value = false
        loadNative(audioFlow)
    }

    override suspend fun load(recording: AudioRecording) {
        stopPositionTracking(updatePosition = false)
        completionJob?.cancel()
        mp3Backend.release()
        loadedRecording = recording
        resumeShouldPlayFromSeek = false
        _audioFlowHolder.value = recording.asAudioFlow()
        _encodedAudio.value = null
        _position.value = Duration.ZERO
        _duration.value = recording.calculatedDuration
        _canSeek.value = true
        loadNative(recording.asAudioFlow())
    }

    override suspend fun load(encodedAudio: EncodedAudio) {
        stop()
        closePreviousArena()
        val loadedDuration = runCatching { mp3Backend.load(encodedAudio) }
            .getOrElse {
                _state.value = AudioPlaybackSession.State.Error(it)
                throw it
            }
        mp3Backend.setPlaybackSpeed(_playbackSpeed.value)
        loaded = false
        loadedRecording = null
        resumeShouldPlayFromSeek = false
        _audioFlowHolder.value = null
        _encodedAudio.value = encodedAudio
        _position.value = Duration.ZERO
        _duration.value = loadedDuration
        _canSeek.value = true
        _state.value = AudioPlaybackSession.State.Ready
    }

    /**
     * Streaming playback (feeding audio incrementally) is deferred. The native
     * library only exposes a whole-buffer [NativeMacosLib.macos_playback_session_load]
     * entry point, so streaming would require a new native enqueue export and
     * rebuilding kodio-native, which is out of scope. This method therefore
     * collects the full recording into a single buffer by design.
     */
    private suspend fun loadNative(audioFlow: AudioFlow) {
        closePreviousArena()
        val arena = Arena.ofShared().also { runtimeArena = it }

        val nativeFlow = normalizeForPlayback(audioFlow)

        val formatData = nativeFlow.format.encodeToByteArray()
        val formatDataSeq = arena.allocate(formatData.size.toLong())
        formatDataSeq.asSlice(0, formatData.size.toLong()).copyFrom(MemorySegment.ofArray(formatData))

        val audioSource = nativeFlow.collectAsSource()
        val audioData = audioSource.source.readByteArray()
        val audioDataSeq = arena.allocate(audioData.size.toLong())
        audioDataSeq.asSlice(0, audioData.size.toLong()).copyFrom(MemorySegment.ofArray(audioData))

        NativeMacosLib.macos_playback_session_load.invokeExact(
            nativeMemSeq,
            formatData.size,
            formatDataSeq,
            audioData.size,
            audioDataSeq
        )
        NativeMacosLib.macos_playback_session_set_playback_speed.invokeExact(nativeMemSeq, _playbackSpeed.value)

        loaded = true
        _state.value = AudioPlaybackSession.State.Ready
    }

    private fun normalizeForPlayback(audioFlow: AudioFlow): AudioFlow {
        val fmt = audioFlow.format
        val enc = fmt.encoding
        val needsConversion = when (enc) {
            is SampleEncoding.PcmFloat -> false
            is SampleEncoding.PcmInt ->
                enc.bitDepth != IntBitDepth.Sixteen ||
                !enc.signed ||
                enc.endianness != Endianness.Little
        }
        if (!needsConversion) return audioFlow
        val targetFormat = AudioFormat(
            sampleRate = fmt.sampleRate,
            channels = fmt.channels,
            encoding = SampleEncoding.PcmFloat(FloatPrecision.F32, SampleLayout.Interleaved)
        )
        logger.debug { "Normalizing playback format: $fmt -> $targetFormat" }
        return audioFlow.convertAudio(targetFormat)
    }

    override suspend fun play() {
        val encodedAudio = _encodedAudio.value
        if (encodedAudio != null) {
            playEncoded(encodedAudio)
            return
        }

        if (!loaded) return

        val duration = _duration.value
        if (duration != null && _position.value >= duration) {
            val recording = loadedRecording ?: run {
                _position.value = duration
                _state.value = AudioPlaybackSession.State.Finished
                return
            }
            _position.value = Duration.ZERO
            loadNative(recording.asAudioFlow())
        }

        val startPosition = _position.value
        _state.value = AudioPlaybackSession.State.Playing
        startPositionTracking(startPosition)

        NativeMacosLib.macos_playback_session_play.invokeExact(nativeMemSeq)

        completionJob = completionScope.launch {
            val result = NativeMacosLib.macos_playback_session_await_completion
                .invokeExact(nativeMemSeq) as Int
            ensureActive()
            _state.value = when (result) {
                0 -> {
                    stopPositionTracking(updatePosition = true)
                    _position.value = _duration.value ?: _position.value
                    AudioPlaybackSession.State.Finished
                }
                1 -> {
                    stopPositionTracking(updatePosition = true)
                    AudioPlaybackSession.State.Error(RuntimeException("Native playback error"))
                }
                else -> {
                    stopPositionTracking(updatePosition = false)
                    AudioPlaybackSession.State.Idle
                }
            }
        }
    }

    override suspend fun seekTo(position: Duration) {
        val encodedAudio = _encodedAudio.value
        if (encodedAudio != null) {
            val targetPosition = normalizedEncodedSeekPosition(position)
            val wasPlaying = _state.value is AudioPlaybackSession.State.Playing
            val wasPaused = _state.value is AudioPlaybackSession.State.Paused

            if (wasPlaying || wasPaused) {
                stopPositionTracking(updatePosition = false)
                completionJob?.cancel()
                mp3Backend.stop()
            }

            _position.value = targetPosition
            val duration = _duration.value
            if (wasPlaying && (duration == null || targetPosition < duration)) {
                _state.value = AudioPlaybackSession.State.Ready
                play()
            } else {
                _state.value = when {
                    duration != null && targetPosition >= duration -> AudioPlaybackSession.State.Finished
                    wasPaused -> AudioPlaybackSession.State.Paused
                    else -> AudioPlaybackSession.State.Ready
                }
            }
            return
        }

        val recording = loadedRecording ?: throw AudioError.SeekUnsupported()
        val targetPosition = recording.normalizedSeekPosition(position)
        val wasPlaying = _state.value is AudioPlaybackSession.State.Playing
        val wasPaused = _state.value is AudioPlaybackSession.State.Paused

        if (_state.value is AudioPlaybackSession.State.Playing || _state.value is AudioPlaybackSession.State.Paused) {
            stopPositionTracking(updatePosition = false)
            completionJob?.cancel()
            NativeMacosLib.macos_playback_session_stop.invokeExact(nativeMemSeq)
        }

        _position.value = targetPosition
        loadNative(recording.asAudioFlowFrom(targetPosition))

        val duration = _duration.value
        if (duration != null && targetPosition >= duration) {
            _state.value = AudioPlaybackSession.State.Finished
            return
        }

        if (wasPlaying) play()
        if (wasPaused) {
            resumeShouldPlayFromSeek = true
            _state.value = AudioPlaybackSession.State.Paused
        }
    }

    override fun setPlaybackSpeed(speed: Float) {
        validatePlaybackSpeed(speed)
        if (_playbackSpeed.value == speed) return

        val wasPlaying = _state.value is AudioPlaybackSession.State.Playing
        if (wasPlaying) stopPositionTracking(updatePosition = true)

        _playbackSpeed.value = speed
        if (_encodedAudio.value != null) {
            mp3Backend.setPlaybackSpeed(speed)
        } else if (loaded) {
            NativeMacosLib.macos_playback_session_set_playback_speed.invokeExact(nativeMemSeq, speed)
        }

        if (wasPlaying && _state.value is AudioPlaybackSession.State.Playing) {
            startPositionTracking(_position.value)
        }
    }

    override fun pause() {
        if (_state.value !is AudioPlaybackSession.State.Playing) return
        stopPositionTracking(updatePosition = true)
        if (_encodedAudio.value != null) {
            mp3Backend.pause()
            _state.value = AudioPlaybackSession.State.Paused
            return
        }
        NativeMacosLib.macos_playback_session_pause.invokeExact(nativeMemSeq)
        _state.value = AudioPlaybackSession.State.Paused
    }

    override fun resume() {
        if (_state.value !is AudioPlaybackSession.State.Paused) return
        if (_encodedAudio.value != null) {
            mp3Backend.resume()
            _state.value = AudioPlaybackSession.State.Playing
            startPositionTracking(_position.value)
            return
        }
        if (resumeShouldPlayFromSeek) {
            resumeShouldPlayFromSeek = false
            completionScope.launch { play() }
            return
        }
        NativeMacosLib.macos_playback_session_resume.invokeExact(nativeMemSeq)
        _state.value = AudioPlaybackSession.State.Playing
        startPositionTracking(_position.value)
    }

    override fun stop() {
        stopPositionTracking(updatePosition = false)
        completionJob?.cancel()
        mp3Backend.stop()
        if (loaded) NativeMacosLib.macos_playback_session_stop.invokeExact(nativeMemSeq)
        resumeShouldPlayFromSeek = false
        _position.value = Duration.ZERO
        _state.value = AudioPlaybackSession.State.Idle
    }

    override fun release() {
        stop()
        mp3Backend.release()
        closePreviousArena()
        loaded = false
        loadedRecording = null
        _audioFlowHolder.value = null
        _encodedAudio.value = null
        _duration.value = null
        _canSeek.value = false
        _state.value = AudioPlaybackSession.State.Idle
    }

    private fun playEncoded(encodedAudio: EncodedAudio) {
        if (_state.value is AudioPlaybackSession.State.Finished) {
            _position.value = Duration.ZERO
        }
        val duration = _duration.value
        if (duration != null && _position.value >= duration) {
            _position.value = duration
            _state.value = AudioPlaybackSession.State.Finished
            return
        }

        stopPositionTracking(updatePosition = false)
        completionJob?.cancel()
        mp3Backend.stop()

        val startPosition = _position.value
        _state.value = AudioPlaybackSession.State.Playing
        startPositionTracking(startPosition)

        completionJob = completionScope.launch {
            runCatching {
                mp3Backend.playBlocking(startPosition)
                stopPositionTracking(updatePosition = true)
                _position.value = _duration.value ?: _position.value
                AudioPlaybackSession.State.Finished
            }.getOrElse {
                if (it is CancellationException) return@launch
                stopPositionTracking(updatePosition = true)
                AudioPlaybackSession.State.Error(it)
            }.also {
                _state.value = it
            }
        }
    }

    private fun normalizedEncodedSeekPosition(position: Duration): Duration {
        if (position < Duration.ZERO) throw AudioError.InvalidSeekPosition(position)
        val duration = _duration.value
        return if (duration != null && position > duration) duration else position
    }

    private fun startPositionTracking(startPosition: Duration) {
        stopPositionTracking(updatePosition = false)
        positionAtStart = startPosition
        positionStartedAt = TimeSource.Monotonic.markNow()
        positionJob = completionScope.launch {
            while (true) {
                updatePositionFromClock()
                delay(50.milliseconds)
            }
        }
    }

    private fun stopPositionTracking(updatePosition: Boolean) {
        if (updatePosition) updatePositionFromClock()
        positionJob?.cancel()
        positionJob = null
        positionStartedAt = null
    }

    private fun updatePositionFromClock() {
        val startedAt = positionStartedAt ?: return
        val current = positionAtStart + startedAt.elapsedNow() * _playbackSpeed.value.toDouble()
        val duration = _duration.value
        _position.value = if (duration != null && current > duration) duration else current
    }

    private fun validatePlaybackSpeed(speed: Float) {
        if (!speed.isFinite() ||
            speed < AudioPlaybackSession.MIN_PLAYBACK_SPEED ||
            speed > AudioPlaybackSession.MAX_PLAYBACK_SPEED
        ) {
            throw AudioError.InvalidPlaybackSpeed(speed)
        }
    }

    private fun closePreviousArena() {
        runtimeArena?.close()
        runtimeArena = null
    }
}
