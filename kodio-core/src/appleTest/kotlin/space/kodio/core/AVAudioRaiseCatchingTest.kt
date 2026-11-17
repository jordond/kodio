package space.kodio.core

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioMixerNode
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPlayerNode
import space.kodio.core.objc.KodioEngineAttachNode
import space.kodio.core.objc.KodioEngineConnect
import space.kodio.core.objc.KodioEngineMainMixerNode
import space.kodio.core.objc.KodioEnginePause
import space.kodio.core.objc.KodioEngineReset
import space.kodio.core.objc.KodioPlayerNodeScheduleBuffer
import space.kodio.core.objc.KodioPlayerNodePlay
import space.kodio.core.objc.KodioRaise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The ObjC shim hands a raised `NSException` back as a value. Each of these would end the
 * test process if the exception crossed a Kotlin frame.
 */
@OptIn(ExperimentalForeignApi::class)
internal class AVAudioRaiseCatchingTest {

    @Test
    fun `a raised NSException comes back as a Kotlin exception`() {
        val thrown = assertFailsWith<AVAudioRaisedException> {
            KodioRaise("KodioTestException", "raised on purpose").throwIfRaised()
        }
        assertEquals("KodioTestException", thrown.name)
        assertEquals("raised on purpose", thrown.reason)
        assertEquals("KodioTestException: raised on purpose", thrown.message)
    }

    @Test
    fun `a player node that is in no engine raises on play and the raise is caught`() {
        val raised = KodioPlayerNodePlay(AVAudioPlayerNode())

        assertNotNull(raised)
        assertEquals(AVFAUDIO_EXCEPTION, raised.name)
    }

    @Test
    fun `a buffer of another channel count raises on schedule and the raise is caught`() {
        val engine = AVAudioEngine()
        val player = AVAudioPlayerNode()
        assertNull(KodioEngineAttachNode(engine, player))
        var mixer: AVAudioMixerNode? = null
        assertNull(KodioEngineMainMixerNode(engine) { mixer = it })
        val mono = AVAudioFormat(standardFormatWithSampleRate = 44100.0, channels = 1u)
        val stereo = AVAudioFormat(standardFormatWithSampleRate = 44100.0, channels = 2u)
        assertNull(KodioEngineConnect(engine, player, assertNotNull(mixer), mono))

        val raised = KodioPlayerNodeScheduleBuffer(player, AVAudioPCMBuffer(stereo, 441u), null)

        assertNotNull(raised)
        assertEquals(AVFAUDIO_EXCEPTION, raised.name)
        assertFailsWith<AVAudioRaisedException> { raised.throwIfRaised() }
    }

    @Test
    fun `attaching a node of one engine to another does not end the process`() {
        val player = AVAudioPlayerNode()
        assertNull(KodioEngineAttachNode(AVAudioEngine(), player))

        // What comes back is checked only for not ending the process: AVFAudio is free to accept this.
        KodioEngineAttachNode(AVAudioEngine(), player)
    }

    @Test
    fun `an engine pauses and resets through the shim`() {
        val engine = AVAudioEngine()

        assertNull(KodioEnginePause(engine))
        assertNull(KodioEngineReset(engine))
    }

    private companion object {
        const val AVFAUDIO_EXCEPTION = "com.apple.coreaudio.avfaudio"
    }
}
