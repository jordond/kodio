package space.kodio.core

import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

/**
 * Represents an active playback session.
 */
interface AudioPlaybackSession {

    /** A flow that emits the current state of the playback. */
    val state: StateFlow<State>

    /** A flow that emits the current loaded audio data. */
    val audioFlow: StateFlow<AudioFlow?>

    /** Current playback position. */
    val position: StateFlow<Duration>

    /** Total duration of the loaded audio, if known. */
    val duration: StateFlow<Duration?>

    /** Whether the loaded audio can seek to arbitrary positions. */
    val canSeek: StateFlow<Boolean>

    /** Loads the given audio data. */
    suspend fun load(audioFlow: AudioFlow)

    /** Loads the given recording for seekable playback. */
    suspend fun load(recording: AudioRecording) {
        load(recording.asAudioFlow())
    }

    /** Starts playback of the given audio data. */
    suspend fun play()

    /** Seeks playback to [position]. */
    suspend fun seekTo(position: Duration)

    /** Pauses the playback. */
    fun pause()
    
    /** Resumes paused playback. */
    fun resume()

    /** Stops the playback entirely. */
    fun stop()

    /**
     * Represents the state of a playback session.
     */
    sealed class State {
        data object Idle : State()
        data object Ready : State()
        data object Playing : State()
        data object Paused : State()
        data object Finished : State()
        data class Error(val error: Throwable) : State()
    }
}
