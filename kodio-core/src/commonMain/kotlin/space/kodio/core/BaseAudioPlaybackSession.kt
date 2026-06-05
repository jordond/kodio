package space.kodio.core

import space.kodio.core.AudioPlaybackSession.State
import space.kodio.core.io.convertAudio
import space.kodio.core.io.files.AudioFileReadError
import space.kodio.core.io.files.EncodedAudio
import space.kodio.core.util.namedLogger
import kotlinx.coroutines.CancellationException
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

    private var loadedRecording: AudioRecording? = null

    protected val scope = CoroutineScope(Dispatchers.Default) + SupervisorJob()

    abstract suspend fun preparePlayback(format: AudioFormat): AudioFormat

    abstract suspend fun playBlocking(audioFlow: AudioFlow)

    protected open suspend fun loadEncodedAudio(encodedAudio: EncodedAudio): Duration? =
        throw AudioFileReadError.UnsupportedFormat(
            "Direct playback is not supported for ${encodedAudio.fileFormat.extension} on this platform."
        )

    protected open suspend fun playEncodedAudioBlocking(encodedAudio: EncodedAudio, startPosition: Duration) {
        throw AudioFileReadError.UnsupportedFormat(
            "Direct playback is not supported for ${encodedAudio.fileFormat.extension} on this platform."
        )
    }

    protected open fun seekLoadedEncodedAudio(position: Duration) = Unit

    protected open fun onPlaybackSpeedChanged(speed: Float) = Unit

    protected open fun releaseLoadedEncodedAudio() = onStop()

    protected abstract fun onPause()
    protected abstract fun onResume()
    protected abstract fun onStop()

    final override suspend fun load(audioFlow: AudioFlow) {
        log.info { "load(): format=${audioFlow.format}" }
        stopCurrentPlayback()
        releaseLoadedEncodedIfIdle()
        loadedRecording = null
        _audioFlow.value = audioFlow
        _encodedAudio.value = null
        _position.value = Duration.ZERO
        _duration.value = null
        _canSeek.value = false
        _state.value = State.Ready
    }

    final override suspend fun load(recording: AudioRecording) {
        log.info { "load(recording): format=${recording.format}, duration=${recording.calculatedDuration}" }
        stopCurrentPlayback()
        releaseLoadedEncodedIfIdle()
        loadedRecording = recording
        _audioFlow.value = recording.asAudioFlow()
        _encodedAudio.value = null
        _position.value = Duration.ZERO
        _duration.value = recording.calculatedDuration
        _canSeek.value = true
        _state.value = State.Ready
    }

    final override suspend fun play() {
        val encodedAudio = _encodedAudio.value
        if (encodedAudio != null) {
            playEncoded(encodedAudio)
            return
        }

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
                    playbackJob = null
                    _state.value = State.Finished
                    log.info { "Playback finished" }
                }.onFailure {
                    if (it is CancellationException) return@onFailure
                    stopPositionTracking(updatePosition = true)
                    playbackJob = null
                    log.error(it) { "Playback failed: ${it.message}" }
                    _state.value = State.Error(it)
                }
            }
        } catch (e: Exception) {
            log.error(e) { "play() failed during preparation: ${e.message}" }
            _state.value = State.Error(e)
        }
    }

    final override suspend fun load(encodedAudio: EncodedAudio) {
        log.info { "load(encodedAudio): format=${encodedAudio.fileFormat.extension}, size=${encodedAudio.sizeInBytes}" }
        stopCurrentPlayback()
        val loadedDuration = runCatching { loadEncodedAudio(encodedAudio) }
            .getOrElse {
                _state.value = State.Error(it)
                throw it
            }
        loadedRecording = null
        _audioFlow.value = null
        _encodedAudio.value = encodedAudio
        _position.value = Duration.ZERO
        _duration.value = loadedDuration
        _canSeek.value = true
        _state.value = State.Ready
    }

    final override suspend fun seekTo(position: Duration) {
        log.info { "seekTo($position)" }
        val encodedAudio = _encodedAudio.value
        if (encodedAudio != null) {
            val targetPosition = normalizedEncodedSeekPosition(position)
            val wasPlaying = _state.value is State.Playing
            val wasPaused = _state.value is State.Paused

            if (wasPlaying || wasPaused) {
                stopCurrentPlayback()
            }

            _position.value = targetPosition
            seekLoadedEncodedAudio(targetPosition)
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
            return
        }

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

    final override fun setPlaybackSpeed(speed: Float) {
        validatePlaybackSpeed(speed)
        if (_playbackSpeed.value == speed) return

        val wasPlaying = _state.value is State.Playing
        if (wasPlaying) {
            stopPositionTracking(updatePosition = true)
        }

        _playbackSpeed.value = speed
        runCatching { onPlaybackSpeedChanged(speed) }
            .onFailure {
                log.error(it) { "Failed to set playback speed to $speed: ${it.message}" }
                _state.value = State.Error(it)
            }

        if (wasPlaying && _state.value is State.Playing) {
            startPositionTracking(_position.value)
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

    final override fun release() {
        log.info { "release()" }
        stop()
        releaseLoadedEncodedIfIdle()
        loadedRecording = null
        _audioFlow.value = null
        _encodedAudio.value = null
        _duration.value = null
        _canSeek.value = false
        _state.value = State.Idle
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

    private suspend fun playEncoded(encodedAudio: EncodedAudio) {
        if (_state.value is State.Finished) {
            _position.value = Duration.ZERO
        }
        val duration = _duration.value
        if (duration != null && _position.value >= duration) {
            _position.value = duration
            _state.value = State.Finished
            return
        }
        stopCurrentPlayback()
        val startPosition = _position.value
        _state.value = State.Playing
        startPositionTracking(startPosition)
        playbackJob = scope.launch {
            runCatching {
                playEncodedAudioBlocking(encodedAudio, startPosition)
                stopPositionTracking(updatePosition = true)
                _position.value = _duration.value ?: _position.value
                playbackJob = null
                _state.value = State.Finished
                log.info { "Encoded playback finished" }
            }.onFailure {
                if (it is CancellationException) return@onFailure
                stopPositionTracking(updatePosition = true)
                playbackJob = null
                log.error(it) { "Encoded playback failed: ${it.message}" }
                _state.value = State.Error(it)
            }
        }
    }

    private fun normalizedEncodedSeekPosition(position: Duration): Duration {
        if (position < Duration.ZERO) throw AudioError.InvalidSeekPosition(position)
        val duration = _duration.value
        return if (duration != null && position > duration) duration else position
    }

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

    private fun releaseLoadedEncodedIfIdle() {
        if (_encodedAudio.value != null && playbackJob == null) {
            runCatching { releaseLoadedEncodedAudio() }
                .onFailure { log.error(it) { "Failed to release encoded playback backend: ${it.message}" } }
        }
    }

    private fun startPositionTracking(startPosition: Duration) {
        stopPositionTracking(updatePosition = false)
        _position.value = startPosition
        positionAtStart = startPosition
        positionStartedAt = TimeSource.Monotonic.markNow()
        positionJob = scope.launch {
            while (true) {
                delay(50.milliseconds)
                updatePositionFromClock()
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
}
