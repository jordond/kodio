package space.kodio.core

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import space.kodio.core.io.files.AudioFileFormat
import space.kodio.core.io.files.EncodedAudio
import java.lang.foreign.MemorySegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The encoded (MP3) path of [NativeMacosAudioPlaybackSession] never calls into the native
 * library, so it runs here with a fake backend and no native session.
 */
class NativeMacosEncodedSeekTest {

    private class FakeEncodedBackend : JvmEncodedPlaybackBackend {
        val startPositions = mutableListOf<Duration>()
        var resumeCount = 0

        override fun load(encodedAudio: EncodedAudio): Duration = 10.milliseconds
        override suspend fun playBlocking(startPosition: Duration) {
            startPositions += startPosition
            awaitCancellation()
        }
        override fun pause() = Unit
        override fun resume() {
            resumeCount++
        }
        override fun setPlaybackSpeed(speed: Float) = Unit
        override fun stop() = Unit
        override fun release() = Unit
    }

    @Test
    fun `resume after a paused MP3 seek plays again from the new position`() = runTest {
        val backend = FakeEncodedBackend()
        val session = NativeMacosAudioPlaybackSession(
            nativeMemSeq = MemorySegment.NULL,
            requestedDevice = null,
            mp3Backend = backend,
            completionDispatcher = StandardTestDispatcher(testScheduler),
        )
        session.load(EncodedAudio.fromBytes(byteArrayOf(1, 2, 3), AudioFileFormat.Mp3, "clip.mp3"))

        session.play()
        runCurrent()
        session.pause()
        session.seekTo(4.milliseconds)
        assertEquals(AudioPlaybackSession.State.Paused, session.state.value)

        session.resume()
        runCurrent()

        assertEquals(listOf(Duration.ZERO, 4.milliseconds), backend.startPositions)
        assertEquals(0, backend.resumeCount)
        assertEquals(AudioPlaybackSession.State.Playing, session.state.value)
        session.stop()
    }
}
