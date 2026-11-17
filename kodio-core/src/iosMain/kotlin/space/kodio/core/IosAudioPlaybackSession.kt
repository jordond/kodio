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
import kotlinx.coroutines.launch
import platform.Foundation.NSNotification
import platform.Foundation.NSNumber
import space.kodio.core.util.namedLogger

private val log = namedLogger("IosAudioPlayback")

/**
 * IOS implementation for [AVAudioPlaybackSession].
 *
 * In IOS, we cannot control the output device, so we ignore it.
 */
class IosAudioPlaybackSession() : AVAudioPlaybackSession() {

    /** A sound was playing when an interruption began, and this session paused it. */
    private var pausedByInterruption = false

    init {
        // The system stops the engine for an interruption (a call, an alarm) and does not
        // start it again. Left Playing, the session would wait on buffers that never end, so
        // the sound is paused; it goes on when the interruption ends and the system says it
        // may, or at the next resume, which starts the engine again.
        observeNotification(AVAudioSessionInterruptionNotification, AVAudioSession.sharedInstance()) { notification ->
            (this as? IosAudioPlaybackSession)?.onInterruption(notification)
        }
    }

    private fun onInterruption(notification: NSNotification) {
        val info = notification.userInfo
        val type = (info?.get(AVAudioSessionInterruptionTypeKey) as? NSNumber)?.unsignedLongValue
        val options = (info?.get(AVAudioSessionInterruptionOptionKey) as? NSNumber)?.unsignedLongValue ?: 0u
        // Off the notification's thread.
        scope.launch {
            when (type) {
                AVAudioSessionInterruptionTypeBegan -> if (state.value is AudioPlaybackSession.State.Playing) {
                    log.info { "Interruption began, pausing" }
                    pausedByInterruption = true
                    pause()
                }
                AVAudioSessionInterruptionTypeEnded -> {
                    val resume = pausedByInterruption &&
                        options and AVAudioSessionInterruptionOptionShouldResume != 0uL
                    pausedByInterruption = false
                    if (resume) {
                        log.info { "Interruption ended, resuming" }
                        resume()
                    }
                }
            }
        }
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