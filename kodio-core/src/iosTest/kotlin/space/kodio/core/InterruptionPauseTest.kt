package space.kodio.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import space.kodio.core.AudioPlaybackSession.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The interruption's decisions on the shared session state machine, with no audio output. */
internal class InterruptionPauseTest {

    /** Wired to [InterruptionPause] as [IosAudioPlaybackSession] wires it; its playback waits for [held]. */
    private class InterruptedSession(dispatcher: CoroutineDispatcher) : BaseAudioPlaybackSession(dispatcher) {

        val held = CompletableDeferred<Unit>()
        var stops = 0

        val interruption = InterruptionPause(
            state = { state.value },
            pause = ::pause,
            resume = ::resume,
            end = ::endPlayback,
        )

        override suspend fun preparePlayback(format: AudioFormat) = format

        override suspend fun playBlocking(audioFlow: AudioFlow) {
            withContext(NonCancellable) { held.await() }
        }

        override fun onPause() = Unit

        override fun onResume() = interruption.forget()

        override fun onStop() {
            stops++
            interruption.forget()
        }
    }

    private val format = AudioFormat(
        sampleRate = 1000,
        channels = Channels.Mono,
        encoding = SampleEncoding.PcmInt(IntBitDepth.Eight),
    )

    private suspend fun TestScope.playingSession(): InterruptedSession {
        val session = InterruptedSession(StandardTestDispatcher(testScheduler))
        session.load(AudioRecording.fromBytes(format, byteArrayOf(0, 1, 2, 3)))
        session.play()
        runCurrent()
        return session
    }

    @Test
    fun `an interruption pauses a playing sound and its end with leave resumes it`() = runTest {
        val session = playingSession()

        session.interruption.began()
        assertEquals(State.Paused, session.state.value)

        session.interruption.ended(shouldResume = true)
        assertEquals(State.Playing, session.state.value)
        session.stop()
    }

    @Test
    fun `an interruption that ends without leave ends the play in an error`() = runTest {
        val session = playingSession()
        val completed = async { Player(session).awaitComplete() }

        session.interruption.began()
        session.interruption.ended(shouldResume = false)
        runCurrent()

        val ended = assertIs<State.Error>(session.state.value)
        assertIs<AVAudioSessionException.Interrupted>(ended.error)
        assertEquals(1, session.stops)
        assertTrue(completed.isCompleted)

        // The cancelled playback runs on to its end and does not overwrite the error.
        session.held.complete(Unit)
        runCurrent()
        assertIs<State.Error>(session.state.value)
    }

    @Test
    fun `a stop lets a caller waiting through an interruption that never ends go`() = runTest {
        val session = playingSession()
        val completed = async { Player(session).awaitComplete() }

        session.interruption.began()
        runCurrent()
        assertFalse(completed.isCompleted)

        session.stop()
        runCurrent()
        assertTrue(completed.isCompleted)
    }

    @Test
    fun `the end of an interruption leaves a sound stopped during it stopped`() = runTest {
        val session = playingSession()

        session.interruption.began()
        session.stop()
        session.interruption.ended(shouldResume = true)

        assertEquals(State.Idle, session.state.value)
        assertEquals(1, session.stops)
    }

    @Test
    fun `the end of an interruption leaves a sound the caller paused again paused`() = runTest {
        val session = playingSession()

        session.interruption.began()
        session.resume()
        session.pause()
        session.interruption.ended(shouldResume = true)
        assertEquals(State.Paused, session.state.value)

        session.interruption.ended(shouldResume = false)
        assertEquals(State.Paused, session.state.value)
        session.stop()
    }

    @Test
    fun `an interruption leaves a paused sound to the caller`() = runTest {
        val session = playingSession()
        session.pause()

        session.interruption.began()
        session.interruption.ended(shouldResume = true)

        assertEquals(State.Paused, session.state.value)
        session.stop()
    }
}
