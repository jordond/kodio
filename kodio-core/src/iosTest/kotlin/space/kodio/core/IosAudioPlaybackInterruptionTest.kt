package space.kodio.core

import kotlinx.coroutines.CompletableDeferred
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
