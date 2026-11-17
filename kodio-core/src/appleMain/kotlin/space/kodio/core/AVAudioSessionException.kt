package space.kodio.core

sealed class AVAudioSessionException(message: String) : Exception(message) {

    class FailedToSetCategory(errorDescription: String) : AVAudioSessionException("Failed to set category: $errorDescription")
    class FailedToActivate(errorDescription: String) : AVAudioSessionException("Failed to activate session: $errorDescription")
    class InputNotFound(input: AudioDevice.Input) : AVAudioSessionException("Input $input not found.")
    /** An interruption (a call, an alarm) took the sound, and ended without leave to resume it. */
    class Interrupted : AVAudioSessionException("The sound was interrupted and the system did not let it resume")
    class FailedToSetPreferredInput(input: AudioDevice.Input, errorDescription: String) : AVAudioSessionException("Failed to set preferred input ($input): $errorDescription")
}