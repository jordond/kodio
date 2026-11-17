package space.kodio.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RecordingStartFailureTest {

    @Test
    fun `a recording whose device cannot start ends in an error and is cleaned up`() = runTest {
        val failure = IllegalStateException("The input device did not start")
        var cleanedUp = false
        val session = object : BaseAudioRecordingSession() {
            override suspend fun prepareRecording(): AudioFormat = AudioQuality.Standard.format
            override suspend fun startRecording(channel: SendChannel<ByteArray>) = throw failure
            override fun cleanup() {
                cleanedUp = true
            }
        }

        session.start()

        // The recording job runs on the session's own scope, in real time.
        val state = withContext(Dispatchers.Default) {
            withTimeout(10.seconds) { session.state.first { it is AudioRecordingSession.State.Error } }
        }
        assertIs<AudioRecordingSession.State.Error>(state)
        assertSame(failure, state.error)
        assertTrue(cleanedUp)
    }
}
