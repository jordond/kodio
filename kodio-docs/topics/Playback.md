[//]: # (title: Playback)

<show-structure for="chapter" depth="2"/>
<primary-label ref="core"/>

<tldr>
<p><b>Play audio</b> with <code>recording.play()</code> for simple playback or <code>Kodio.player()</code> for full control.</p>
</tldr>

Kodio provides flexible audio playback APIs, from a simple one-liner to a full-featured `Player` class with pause, resume, and device selection.

## Simple playback {id="simple"}

The easiest way to play audio is directly on a recording. This suspends until playback completes:

```kotlin
recording.play()
```

MP3 files can be played directly as encoded audio:

```kotlin
val mp3 = EncodedAudio.fromBytes(mp3Bytes, AudioFileFormat.Mp3, "song.mp3")
Kodio.play(mp3)
```

## Playback with controls {id="with-controls"}

When you need to pause, resume, seek, or monitor playback, use `Kodio.play()` with a lambda:

```kotlin
Kodio.play(recording) { player ->
    player.start()
    
    // Pause after 2 seconds
    delay(2.seconds)
    player.pause()
    
    // Resume after 1 second
    delay(1.seconds)
    player.resume()

    // Jump to the halfway point
    player.seekTo(recording.calculatedDuration / 2)
    
    // Wait for playback to finish
    player.awaitComplete()
}
```

The lambda receives a `Player` instance that gives you full control over playback.

Seeking is supported for `AudioRecording` sources loaded with `player.load(recording)`.
Raw streaming `AudioFlow` sources loaded with `player.loadAudioFlow(audioFlow)` are not
seekable because the stream may not be replayable.

MP3 sources loaded with `player.load(encodedAudio)` use the platform media decoder
directly instead of converting the file into an `AudioRecording`. JVM desktop
uses JLayer for MP3 decode/playback through JavaSound.

## Using Player directly {id="player"}

For maximum flexibility, create a `Player` instance directly. This is useful for:
- Loading different recordings into the same player
- Managing the player's lifecycle explicitly
- Building custom playback UIs

```kotlin
val player = Kodio.player()

player.use { p ->
    p.load(recording)
    p.start()
    p.awaitComplete()
}
```

## Output device selection {id="device"}

Play audio to a specific output device (headphones, speakers, etc.):

```kotlin
// List available output devices
val outputs = Kodio.listOutputDevices()
println(outputs.map { it.name })  // ["Built-in Speakers", "AirPods Pro", ...]

// Play to a specific device
val headphones = outputs.find { it.name.contains("AirPods") }
Kodio.play(recording, device = headphones)
```

> Device selection is fully supported on JVM, iOS, and macOS. On Android, the system manages audio routing. On Web, it depends on browser support.
>
{style="note"}

## Player API reference {id="api-reference"}

### Properties {id="properties" collapsible="true"}

<deflist type="medium">
<def title="isPlaying: Boolean">
<code>true</code> while audio is actively playing.
</def>
<def title="isPaused: Boolean">
<code>true</code> if playback was started and then paused.
</def>
<def title="isReady: Boolean">
<code>true</code> when audio is loaded and ready to play.
</def>
<def title="isFinished: Boolean">
<code>true</code> after playback has completed.
</def>
<def title="canSeek: Boolean">
<code>true</code> when the loaded source supports <code>seekTo()</code>.
</def>
<def title="position: Duration">
The current playback position.
</def>
<def title="duration: Duration?">
The loaded recording duration, if known.
</def>
<def title="stateFlow: StateFlow<State>">
Observable state changes for reactive UIs.
</def>
</deflist>

### Methods {id="methods" collapsible="true"}

<deflist type="medium">
<def title="load(recording)">
Load an <code>AudioRecording</code> for playback.
</def>
<def title="load(encodedAudio)">
Load encoded audio, such as an MP3, for direct native playback.
</def>
<def title="start()">
Begin or resume playback.
</def>
<def title="pause()">
Pause playback. Use <code>resume()</code> to continue.
</def>
<def title="resume()">
Continue playback after pausing.
</def>
<def title="seekTo(position)">
Jump to a playback position. Works for loaded <code>AudioRecording</code> sources.
</def>
<def title="stop()">
Stop playback and reset to the beginning.
</def>
<def title="toggle()">
Play if stopped/paused, pause if playing. Convenient for single-button UIs.
</def>
<def title="release()">
Release all resources. Called automatically when using <code>use {}</code>.
</def>
<def title="awaitComplete()">
Suspend until playback finishes.
</def>
</deflist>

<seealso style="cards">
    <category ref="core-api">
        <a href="Recording.md" summary="Record audio">Recording</a>
        <a href="File-IO.md" summary="Save and load audio files">Audio File I/O</a>
        <a href="Device-Selection.md" summary="Choose input/output devices">Device Selection</a>
        <a href="Error-Handling.md" summary="Handle playback errors">Error Handling</a>
    </category>
</seealso>
