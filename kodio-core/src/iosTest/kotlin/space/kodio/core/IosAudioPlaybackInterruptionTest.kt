package space.kodio.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionOptionKey
import platform.AVFAudio.AVAudioSessionInterruptionOptionShouldResume
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeEnded
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import space.kodio.core.AudioPlaybackSession.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/** Posts the interruption notifications the system posts for a call. Needs an audio output. */
internal class IosAudioPlaybackInterruptionTest {

    private val format = AudioFormat(
        sampleRate = 44100,
        channels = Channels.Mono,
        encoding = SampleEncoding.PcmFloat(FloatPrecision.F32, SampleLayout.Interleaved),
    )

    private fun chunk() = ByteArray(format.sampleRate / 10 * Float.SIZE_BYTES)

    private fun postInterruption(type: ULong, options: ULong = 0u) {
        NSNotificationCenter.defaultCenter.postNotificationName(
            AVAudioSessionInterruptionNotification,
            AVAudioSession.sharedInstance(),
            mapOf<Any?, Any?>(
                AVAudioSessionInterruptionTypeKey to NSNumber(unsignedLong = type),
                AVAudioSessionInterruptionOptionKey to NSNumber(unsignedLong = options),
            ),
        )
    }

    private suspend fun IosAudioPlaybackSession.interrupt(type: ULong, options: ULong = 0u) {
        postInterruption(type, options)
        lastInterruption?.join()
    }

    /** Starts a sound whose flow holds its last chunk until [gate] completes. */
    private suspend fun playingSession(gate: CompletableDeferred<Unit>): IosAudioPlaybackSession {
        val session = IosAudioPlaybackSession()
        val firstScheduled = CompletableDeferred<Unit>()
        session.load(
            AudioFlow(
                format,
                flow {
                    emit(chunk())
                    firstScheduled.complete(Unit)
                    gate.await()
                    emit(chunk())
                },
            ),
        )
        session.play()
        firstScheduled.await()
        return session
    }

    private fun withAudioOutput(test: suspend CoroutineScope.() -> Unit) {
        if (!AVAudioPlaybackSessionTest().canStartAnEngine()) {
            println("No audio output on this host, skipped")
            return
        }
        runBlocking(block = test)
    }

    @Test
    fun `an interruption that ends without leave to resume ends the play in an error`() = withAudioOutput {
        val session = playingSession(CompletableDeferred())

        session.interrupt(AVAudioSessionInterruptionTypeBegan)
        assertEquals(State.Paused, session.state.value)

        session.interrupt(AVAudioSessionInterruptionTypeEnded)
        val ended = assertIs<State.Error>(session.state.value)
        assertIs<AVAudioSessionException.Interrupted>(ended.error)
        assertFalse(session.player.isPlaying())
        session.release()
    }

    @Test
    fun `the end of an interruption does not resume a sound stopped during it`() = withAudioOutput {
        val session = playingSession(CompletableDeferred())
        val player = Player(session)
        val completed = async { player.awaitComplete() }

        session.interrupt(AVAudioSessionInterruptionTypeBegan)
        // The interruption never ends on its own: the stop lets the waiting caller go.
        session.stop()
        completed.await()

        session.interrupt(AVAudioSessionInterruptionTypeEnded, AVAudioSessionInterruptionOptionShouldResume)
        assertEquals(State.Idle, session.state.value)
        assertFalse(session.player.isPlaying())
        session.release()
    }

    @Test
    fun `the end of an interruption leaves a sound paused again by the caller paused`() = withAudioOutput {
        val session = playingSession(CompletableDeferred())

        session.interrupt(AVAudioSessionInterruptionTypeBegan)
        session.resume()
        session.pause()

        session.interrupt(AVAudioSessionInterruptionTypeEnded, AVAudioSessionInterruptionOptionShouldResume)
        assertEquals(State.Paused, session.state.value)
        session.interrupt(AVAudioSessionInterruptionTypeEnded)
        assertEquals(State.Paused, session.state.value)
        session.release()
    }

    private suspend fun AudioPlaybackSession.awaitState(matches: (State) -> Boolean): State =
        withTimeout(15.seconds) { state.first(matches) }

    @Test
    fun `an interruption pauses a playing sound and its end resumes it`() {
        if (!AVAudioPlaybackSessionTest().canStartAnEngine()) {
            println("No audio output on this host, skipped")
            return
        }
        runBlocking {
            val session = IosAudioPlaybackSession()
            val firstScheduled = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            session.load(
                AudioFlow(
                    format,
                    flow {
                        emit(chunk())
                        firstScheduled.complete(Unit)
                        gate.await()
                        emit(chunk())
                    },
                ),
            )
            session.play()
            firstScheduled.await()

            postInterruption(AVAudioSessionInterruptionTypeBegan)
            assertEquals(State.Paused, session.awaitState { it !is State.Playing })
            assertFalse(session.player.isPlaying())

            postInterruption(AVAudioSessionInterruptionTypeEnded, AVAudioSessionInterruptionOptionShouldResume)
            session.awaitState { it is State.Playing }

            gate.complete(Unit)
            assertEquals(State.Finished, session.awaitState { it is State.Finished || it is State.Error })
            session.release()
        }
    }
}
