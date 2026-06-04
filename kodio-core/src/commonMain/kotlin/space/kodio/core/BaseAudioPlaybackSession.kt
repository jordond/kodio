package space.kodio.core

import space.kodio.core.AudioPlaybackSession.State
import space.kodio.core.io.convertAudio
import space.kodio.core.util.namedLogger
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

private val log = namedLogger("BasePlayback")

abstract class BaseAudioPlaybackSession : AudioPlaybackSession {

    private val _state = MutableStateFlow<State>(State.Idle)
    override val state: StateFlow<State> = _state.asStateFlow()

    private var playbackJob: Job? = null
    private var positionJob: Job? = null
    private var positionStartedAt: TimeSource.Monotonic.ValueTimeMark? = null
    private var positionAtStart: Duration = Duration.ZERO

    private val _audioFlow = MutableStateFlow<AudioFlow?>(null)
    override val audioFlow: StateFlow<AudioFlow?> = _audioFlow.asStateFlow()

    private val _position = MutableStateFlow(Duration.ZERO)
    override val position: StateFlow<Duration> = _position.asStateFlow()

    private val _duration = MutableStateFlow<Duration?>(null)
    override val duration: StateFlow<Duration?> = _duration.asStateFlow()

    private val _canSeek = MutableStateFlow(false)
    override val canSeek: StateFlow<Boolean> = _canSeek.asStateFlow()

    private var loadedRecording: AudioRecording? = null

    protected val scope = CoroutineScope(Dispatchers.Default) + SupervisorJob()

    abstract suspend fun preparePlayback(format: AudioFormat): AudioFormat

    abstract suspend fun playBlocking(audioFlow: AudioFlow)

    protected abstract fun onPause()
    protected abstract fun onResume()
    protected abstract fun onStop()

    final override suspend fun load(audioFlow: AudioFlow) {
        log.info { "load(): format=${audioFlow.format}" }
        stopCurrentPlayback()
        loadedRecording = null
        _audioFlow.value = audioFlow
        _position.value = Duration.ZERO
        _duration.value = null
        _canSeek.value = false
        _state.value = State.Ready
    }

    final override suspend fun load(recording: AudioRecording) {
        log.info { "load(recording): format=${recording.format}, duration=${recording.calculatedDuration}" }
        stopCurrentPlayback()
        loadedRecording = recording
        _audioFlow.value = recording.asAudioFlow()
        _position.value = Duration.ZERO
        _duration.value = recording.calculatedDuration
        _canSeek.value = true
        _state.value = State.Ready
    }

    final override suspend fun play() {
        if (_state.value is State.Finished && loadedRecording != null) {
            _position.value = Duration.ZERO
        }
        val audioFlow = playableAudioFlow()
        if (audioFlow == null) {
            log.warn { "play() called but audioFlow is null, returning" }
            return
        }
        val duration = _duration.value
        if (duration != null && _position.value >= duration) {
            _position.value = duration
            _state.value = State.Finished
            return
        }
        stopCurrentPlayback()
        log.info { "play(): audioFlow.format=${audioFlow.format}" }
        try {
            log.info { "Calling preparePlayback()" }
            val playbackFormat = preparePlayback(audioFlow.format)
            log.info { "preparePlayback() returned format: $playbackFormat" }
            val playbackAudioFlow = audioFlow.convertAudio(playbackFormat)
            log.info { "Audio conversion applied, starting playback" }
            val startPosition = _position.value
            _state.value = State.Playing
            startPositionTracking(startPosition)
            playbackJob = scope.launch {
                runCatching {
                    playBlocking(playbackAudioFlow)
                    stopPositionTracking(updatePosition = true)
                    _position.value = _duration.value ?: _position.value
                    _state.value = State.Finished
                    log.info { "Playback finished" }
                }.onFailure {
                    stopPositionTracking(updatePosition = true)
                    log.error(it) { "Playback failed: ${it.message}" }
                    _state.value = State.Error(it)
                }
            }
        } catch (e: Exception) {
            log.error(e) { "play() failed during preparation: ${e.message}" }
            _state.value = State.Error(e)
        }
    }

    final override suspend fun seekTo(position: Duration) {
        log.info { "seekTo($position)" }
        val recording = loadedRecording ?: throw AudioError.SeekUnsupported()
        val targetPosition = recording.normalizedSeekPosition(position)
        val wasPlaying = _state.value is State.Playing
        val wasPaused = _state.value is State.Paused

        if (wasPlaying || wasPaused) {
            stopCurrentPlayback()
        }

        _position.value = targetPosition
        val duration = _duration.value

        if (wasPlaying && (duration == null || targetPosition < duration)) {
            _state.value = State.Ready
            play()
        } else {
            _state.value = when {
                duration != null && targetPosition >= duration -> State.Finished
                wasPaused -> State.Paused
                else -> State.Ready
            }
        }
    }

    final override fun pause() {
        log.info { "pause()" }
        if (_state.value !is State.Playing) return
        stopPositionTracking(updatePosition = true)
        runAndUpdateState(State.Paused, ::onPause)
    }

    final override fun resume() {
        log.info { "resume()" }
        if (_state.value !is State.Paused) return
        if (_state.value is State.Paused && playbackJob == null && loadedRecording != null) {
            _state.value = State.Ready
            scope.launch { play() }
            return
        }
        runAndUpdateState(State.Playing, ::onResume)
        startPositionTracking(_position.value)
    }

    final override fun stop() {
        log.info { "stop()" }
        stopPositionTracking(updatePosition = false)
        _position.value = Duration.ZERO
        val shouldStopBackend = playbackJob != null || _state.value is State.Playing || _state.value is State.Paused
        runAndUpdateState(State.Idle) {
            if (shouldStopBackend) onStop()
            playbackJob?.cancel()
            playbackJob = null
        }
    }

    protected fun runAndUpdateState(newState: State, block: () -> Unit) {
        _state.value = runCatching {
            block()
            newState
        }.getOrElse {
            log.error(it) { "State transition to $newState failed: ${it.message}" }
            State.Error(it)
        }
    }

    private fun playableAudioFlow(): AudioFlow? =
        loadedRecording?.asAudioFlowFrom(_position.value) ?: audioFlow.value

    private suspend fun stopCurrentPlayback() {
        stopPositionTracking(updatePosition = false)
        val job = playbackJob
        if (job != null) {
            runCatching { onStop() }
                .onFailure { log.error(it) { "Failed to stop current playback: ${it.message}" } }
            job.cancelAndJoin()
            playbackJob = null
        }
    }

    private fun startPositionTracking(startPosition: Duration) {
        stopPositionTracking(updatePosition = false)
        positionAtStart = startPosition
        positionStartedAt = TimeSource.Monotonic.markNow()
        positionJob = scope.launch {
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
        val current = positionAtStart + startedAt.elapsedNow()
        val duration = _duration.value
        _position.value = if (duration != null && current > duration) duration else current
    }

}
