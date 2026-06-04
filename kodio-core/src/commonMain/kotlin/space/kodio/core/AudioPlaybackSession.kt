package space.kodio.core

import kotlinx.coroutines.flow.StateFlow
import space.kodio.core.io.files.EncodedAudio
import kotlin.time.Duration

/**
 * Represents an active playback session.
 */
interface AudioPlaybackSession {

    /** A flow that emits the current state of the playback. */
    val state: StateFlow<State>

    /** A flow that emits the current loaded audio data. */
    val audioFlow: StateFlow<AudioFlow?>

    /** Encoded audio currently loaded for platform-native playback, if any. */
    val encodedAudio: StateFlow<EncodedAudio?>

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

    /** Loads encoded audio for platform-native playback. */
    suspend fun load(encodedAudio: EncodedAudio)

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
     * Releases resources associated with this session. The session should not be
     * used after release.
     */
    fun release() {
        stop()
    }

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
