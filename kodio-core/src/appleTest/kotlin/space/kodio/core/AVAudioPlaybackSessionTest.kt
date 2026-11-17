package space.kodio.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioEngineConfigurationChangeNotification
import platform.Foundation.NSNotificationCenter
import platform.AVFAudio.AVAudioMixerNode
import platform.AVFAudio.AVAudioPlayerNode
import space.kodio.core.AudioPlaybackSession.State
import space.kodio.core.objc.KodioEngineAttachNode
import space.kodio.core.objc.KodioEngineConnect
import space.kodio.core.objc.KodioEngineMainMixerNode
import space.kodio.core.objc.KodioEngineStart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Plays silence through a real `AVAudioEngine`, so each test needs an audio output. On a
 * host that has none the tests return early: [AVAudioRaiseCatchingTest] still covers the catching.
 */
@OptIn(ExperimentalForeignApi::class)
internal class AVAudioPlaybackSessionTest {

    private class TestSession : AVAudioPlaybackSession() {

        /** How many of the next node starts find the engine stopped under them. */
        var engineStopsBeforeNodeStart = 0

        /** The next node start finds the node taken out of the engine, where a play raises. */
        var detachNodeBeforeNodeStart = false

        /** Runs on the playback job, under the engine lock, before each buffer is scheduled. */
        var beforeSchedule: (TestSession.() -> Unit)? = null

        override fun configureAudioSession() = Unit

        override fun onBeforeSchedule() {
            beforeSchedule?.invoke(this)
        }

        override fun onBeforeNodeStart() {
            if (engineStopsBeforeNodeStart > 0) {
                engineStopsBeforeNodeStart--
                engine.stop()
            }
            if (detachNodeBeforeNodeStart) {
                detachNodeBeforeNodeStart = false
                engine.detachNode(player)
            }
        }
    }

    private val format = AudioFormat(
        sampleRate = 44100,
        channels = Channels.Mono,
        encoding = SampleEncoding.PcmFloat(FloatPrecision.F32, SampleLayout.Interleaved),
    )

    private fun chunk() = ByteArray(format.sampleRate / 10 * Float.SIZE_BYTES)

    /** Silence, in chunks of a tenth of a second. */
    private fun silence(chunks: Int) = AudioFlow(format, flow { repeat(chunks) { emit(chunk()) } })

    /**
     * A chunk of silence, then [firstScheduled] completes and the flow waits for [gate]
     * before its last chunk: the test acts while the first chunk is scheduled.
     */
    private fun gatedSilence(
        firstScheduled: CompletableDeferred<Unit>,
        gate: CompletableDeferred<Unit>,
    ) = AudioFlow(
        format,
        flow {
            emit(chunk())
            firstScheduled.complete(Unit)
            gate.await()
            emit(chunk())
        },
    )

    private suspend fun TestSession.awaitState(what: String, matches: (State) -> Boolean): State =
        try {
            withTimeout(15.seconds) { state.first(matches) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Timed out waiting for $what, the state is ${state.value}", e)
        }

    private suspend fun TestSession.awaitEnd(): State =
        awaitState("the end of the playback") { it is State.Finished || it is State.Error }

    @Test
    fun `a sound plays to its end`() = withAudioOutput {
        val session = TestSession()
        session.load(silence(chunks = 2))

        session.play()

        assertEquals(State.Finished, session.awaitEnd())
        session.release()
    }

    @Test
    fun `a sound whose node raises on play ends in an error and the next sound plays`() = withAudioOutput {
        val session = TestSession()
        session.load(silence(chunks = 2))

        // Out of its engine the node raises "required condition is false: _engine != nil" on
        // play, at the first start and at the one retry.
        session.detachNodeBeforeNodeStart = true
        session.play()

        val failed = session.awaitEnd()
        assertIs<State.Error>(failed)
        val raised = assertIs<AVAudioRaisedException>(failed.error)
        assertEquals("com.apple.coreaudio.avfaudio", raised.name)

        // The fault clears, and the session plays as before.
        assertNull(KodioEngineAttachNode(session.engine, session.player))
        session.play()

        assertEquals(State.Finished, session.awaitEnd())
        session.release()
    }

    @Test
    fun `a sound whose engine is lost under every start ends in an error and the next sound plays`() = withAudioOutput {
        val session = TestSession()
        session.load(silence(chunks = 2))

        // Lost under the first start and again under the one retry.
        session.engineStopsBeforeNodeStart = 2
        session.play()

        val failed = session.awaitEnd()
        assertIs<State.Error>(failed)
        assertTrue(
            failed.error is AVAudioEngineException || failed.error is AVAudioRaisedException,
            "Unexpected error: ${failed.error}",
        )
        assertEquals(0, session.engineStopsBeforeNodeStart)

        session.play()

        assertEquals(State.Finished, session.awaitEnd())
        session.release()
    }

    @Test
    fun `a sound ends in an error on a host whose engine cannot start`() {
        if (canStartAnEngine()) return
        runBlocking {
            val session = TestSession()
            session.load(silence(chunks = 2))

            session.play()

            val failed = session.awaitEnd()
            assertIs<State.Error>(failed)
            session.play()
            assertIs<State.Error>(session.awaitEnd())
            session.release()
        }
    }

    @Test
    fun `a node that does not start once is started again on a restarted engine`() = withAudioOutput {
        val session = TestSession()
        session.load(silence(chunks = 2))

        session.engineStopsBeforeNodeStart = 1
        session.play()

        assertEquals(State.Finished, session.awaitEnd())
        assertEquals(0, session.engineStopsBeforeNodeStart)
        session.release()
    }

    @Test
    fun `an engine stopped between two sounds is started for the second`() = withAudioOutput {
        val session = TestSession()
        session.load(silence(chunks = 2))
        session.play()
        assertEquals(State.Finished, session.awaitEnd())

        session.engine.stop()
        session.play()

        assertEquals(State.Finished, session.awaitEnd())
        session.release()
    }

    @Test
    fun `a sound goes on to its end when the engine stopped under it is recovered`() = withAudioOutput {
        val session = TestSession()
        val firstScheduled = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        session.load(gatedSilence(firstScheduled, gate))
        session.play()
        firstScheduled.await()

        // What a configuration change does: the engine stops, then the notification comes.
        session.engine.stop()
        NSNotificationCenter.defaultCenter.postNotificationName(
            AVAudioEngineConfigurationChangeNotification,
            session.engine,
        )
        gate.complete(Unit)

        assertEquals(State.Finished, session.awaitEnd())
        assertTrue(session.engine.isRunning())
        session.release()
    }

    @Test
    fun `a stop that lands before a buffer is scheduled neither schedules nor plays it`() = withAudioOutput {
        val session = TestSession()
        val stopped = CompletableDeferred<Unit>()
        session.load(silence(chunks = 3))
        // The stop runs on the playback job's thread just before the schedule, the window in
        // which an unguarded play raised "player started when engine not running".
        session.beforeSchedule = {
            beforeSchedule = null
            stop()
            stopped.complete(Unit)
        }
        session.play()
        stopped.await()

        assertEquals(State.Idle, session.state.value)
        assertFalse(session.player.isPlaying())
        assertFalse(session.engine.isRunning())

        session.play()
        assertEquals(State.Finished, session.awaitEnd())
        session.release()
    }

    @Test
    fun `a pause before the first buffer is scheduled keeps the node paused until resume`() = withAudioOutput {
        val session = TestSession()
        val firstScheduled = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        session.load(gatedSilence(firstScheduled, gate))
        session.beforeSchedule = {
            beforeSchedule = null
            pause()
        }
        session.play()
        firstScheduled.await()

        assertEquals(State.Paused, session.state.value)
        assertFalse(session.player.isPlaying())

        session.resume()
        assertEquals(State.Playing, session.state.value)
        assertTrue(session.player.isPlaying())

        gate.complete(Unit)
        assertEquals(State.Finished, session.awaitEnd())
        session.release()
    }

    @Test
    fun `pause and resume go on to the end`() = withAudioOutput {
        val session = TestSession()
        val firstScheduled = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        session.load(gatedSilence(firstScheduled, gate))
        session.play()
        firstScheduled.await()

        session.pause()
        assertEquals(State.Paused, session.state.value)
        assertFalse(session.player.isPlaying())
        session.resume()
        assertEquals(State.Playing, session.state.value)
        assertTrue(session.player.isPlaying())

        gate.complete(Unit)
        assertEquals(State.Finished, session.awaitEnd())
        session.release()
    }

    @Test
    fun `stop during a sound leaves the session ready for the next`() = withAudioOutput {
        val session = TestSession()
        val firstScheduled = CompletableDeferred<Unit>()
        session.load(gatedSilence(firstScheduled, CompletableDeferred()))
        session.play()
        firstScheduled.await()

        session.stop()
        assertEquals(State.Idle, session.state.value)

        session.load(silence(chunks = 2))
        session.play()
        assertEquals(State.Finished, session.awaitEnd())
        session.release()
    }

    /** Runs [test] when an engine can be started on this host, which needs an audio output. */
    private fun withAudioOutput(test: suspend () -> Unit) {
        if (!canStartAnEngine()) {
            println("No audio output on this host, skipped")
            return
        }
        runBlocking { test() }
    }

    internal fun canStartAnEngine(): Boolean {
        val engine = AVAudioEngine()
        val player = AVAudioPlayerNode()
        var mixer: AVAudioMixerNode? = null
        var started = true
        val raised = KodioEngineAttachNode(engine, player)
            ?: KodioEngineMainMixerNode(engine) { mixer = it }
            ?: KodioEngineConnect(engine, player, mixer ?: return false, null)
            ?: KodioEngineStart(engine) { started = false }
        engine.stop()
        return raised == null && started
    }
}
