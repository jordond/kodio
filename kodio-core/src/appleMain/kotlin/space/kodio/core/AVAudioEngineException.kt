package space.kodio.core

sealed class AVAudioEngineException(message: String) : Exception(message) {

    class FailedToStart(errorDescription: String) : AVAudioEngineException(errorDescription)

    /** The player node took a play without starting, which it does on an engine that is not running. */
    class PlayerNodeNotStarted : AVAudioEngineException("The player node did not start: its engine is not running")
}