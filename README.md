# Audio Dashcam Simple

Minimal Android app that records a perpetual rolling audio buffer.

## Current behavior

- Android native Java app.
- Foreground microphone service.
- 48 kHz mono float32 PCM.
- Preallocated 8-hour circular buffer.
- Approximate ring size: 5.15 GiB.
- Save buttons for 5s, 15s, 30s, 1m, 5m, 15m, 30m, 1h, 5h, 8h.
- Saves one unsplit raw float32 file plus `manifest.json` to public Downloads/AudioDashcam via MediaStore.
- Also writes a single convenience WAV only when the export fits standard WAV limits.

## Build

Open this folder in Android Studio and run on a physical Android device.

## Important notes

- This is an MVP/prototype, not a Play Store-ready app.
- It writes a large preallocated file under internal app storage.
- Android must show an active microphone/privacy indicator and foreground-service notification.
- Some devices may reject `MediaRecorder.AudioSource.UNPROCESSED`; if that happens, change it to `MediaRecorder.AudioSource.MIC`.
- Android 14+ requires foreground service type declarations and permissions for microphone services.

## Files

- `MainActivity.java`: simple button UI.
- `RecorderService.java`: foreground service and AudioRecord loop.
- `PcmRingBuffer.java`: raw PCM circular buffer and export.
- `WavWriter.java`: minimal IEEE-float WAV writer.


## Saved file location

Open the Android Files app, then go to:

```text
Downloads/AudioDashcam/<timestamp>_last-<duration>s/
```

Each save contains `audio_float32le_mono_48000.raw` plus `manifest.json`. For shorter saves, it may also contain `audio_float32le_mono_48000.wav`.


## Importing the raw file

Import `audio_float32le_mono_48000.raw` as raw PCM with these settings:

```text
Encoding/sample format: 32-bit float PCM
Endianness: little-endian
Channels: 1 / mono
Sample rate: 48000 Hz
Byte offset: 0
```
