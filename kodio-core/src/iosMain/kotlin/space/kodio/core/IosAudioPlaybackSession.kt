package space.kodio.core

import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionOptionKey
import platform.AVFAudio.AVAudioSessionInterruptionOptionShouldResume
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeEnded
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.currentRoute
import platform.AVFAudio.outputNumberOfChannels
import platform.AVFAudio.sampleRate
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import platform.Foundation.NSNotification
import platform.Foundation.NSNumber
import space.kodio.core.AudioPlaybackSession.State
import space.kodio.core.util.namedLogger

private val log = namedLogger("IosAudioPlayback")

/**
 * IOS implementation for [AVAudioPlaybackSession].
 *
 * In IOS, we cannot control the output device, so we ignore it.
 *
 * An interruption (a call, an alarm) stops the engine, and the system does not start it
 * again. A playing sound is paused when the interruption begins. When it ends:
 * - with leave to resume, the sound is resumed on a restarted engine. The player node the
 *   engine stopped under has to be stopped before it plays again, and that stop drops what
 *   was scheduled on it: the sound goes on from the first buffer scheduled after the resume,
 *   and a sound that was scheduled whole (a short one) ends [State.Finished] there;
 * - without it, the play ends in [State.Error] with [AVAudioSessionException.Interrupted]:
 *   the sound did not reach its end, so [State.Finished] would be untrue, and a caller
 *   waiting in [Player.awaitComplete] learns why it stopped.
 *
 * The system does not promise an end to an interruption. Until one comes the sound stays
 * [State.Paused]: [resume] plays it again, and [stop] or [release] end a wait in
 * [Player.awaitComplete].
 */
class IosAudioPlaybackSession() : AVAudioPlaybackSession() {

    private val interruption = InterruptionPause(
        state = { state.value },
        pause = ::pause,
        resume = ::resume,
        end = ::endPlayback,
    )

    /** The handling of the last interruption notification, for tests to join. */
    internal var lastInterruption: Job? = null
        private set

    init {
        observeNotification(AVAudioSessionInterruptionNotification, AVAudioSession.sharedInstance()) { notification ->
            (this as? IosAudioPlaybackSession)?.onInterruption(notification)
        }
    }

    private fun onInterruption(notification: NSNotification) {
        val info = notification.userInfo
        val type = (info?.get(AVAudioSessionInterruptionTypeKey) as? NSNumber)?.unsignedLongValue
        val options = (info?.get(AVAudioSessionInterruptionOptionKey) as? NSNumber)?.unsignedLongValue ?: 0u
        // Off the notification's thread. The check of the state and the call that follows it
        // are one step against the caller's pause, resume and stop.
        lastInterruption = scope.launch {
            serialized {
                when (type) {
                    AVAudioSessionInterruptionTypeBegan -> interruption.began()
                    AVAudioSessionInterruptionTypeEnded ->
                        interruption.ended(shouldResume = options and AVAudioSessionInterruptionOptionShouldResume != 0uL)
                }
            }
        }
    }

    override fun onResume() = serialized {
        interruption.forget()
        super.onResume()
    }

    override fun onStop() = serialized {
        interruption.forget()
        super.onStop()
    }

    override fun configureAudioSession() {
        log.info { "configureAudioSession() - setting playback category" }
        val session = AVAudioSession.sharedInstance()
        session.configureCategoryPlayback()
        session.activate()
        log.info {
            "Audio session after playback config: category=${session.category}, " +
                "sampleRate=${session.sampleRate}, " +
                "outputChannels=${session.outputNumberOfChannels}, " +
                "currentRoute.outputs=${session.currentRoute.outputs}"
        }
    }
}

/**
 * Pauses a playing sound for an interruption and decides what its end does: [resume] with
 * the system's leave, otherwise [end] the play with [AVAudioSessionException.Interrupted].
 * Every call is made under the session's serialisation.
 */
internal class InterruptionPause(
    private val state: () -> State,
    private val pause: () -> Unit,
    private val resume: () -> Unit,
    private val end: (Throwable) -> Unit,
) {

    /**
     * A sound was playing when an interruption began, and it was paused for it. [forget] clears
     * it, at a stop and at a resume, so the end of the interruption neither resumes nor ends a
     * sound the caller stopped, or resumed and paused again.
     */
    private var pausedByInterruption = false

    fun began() {
        if (state() !is State.Playing) return
        log.info { "Interruption began, pausing" }
        pause()
        pausedByInterruption = state() is State.Paused
    }

    fun ended(shouldResume: Boolean) {
        val interrupted = pausedByInterruption && state() is State.Paused
        pausedByInterruption = false
        when {
            !interrupted -> Unit
            shouldResume -> {
                log.info { "Interruption ended, resuming" }
                resume()
            }
            else -> {
                log.info { "Interruption ended without leave to resume, ending the play" }
                end(AVAudioSessionException.Interrupted())
            }
        }
    }

    fun forget() {
        pausedByInterruption = false
    }
}
