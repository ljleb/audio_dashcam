# Audio Dashcam Simple

Minimal Android app that records a perpetual rolling audio buffer.

## Current behavior

- Android native Java app.
- Requires Android 16 / API 36 or newer.
- Foreground microphone service.
- 48 kHz mono float32 PCM.
- Preallocated 8-hour circular buffer.
- Approximate ring size: 5.15 GiB.
- Save buttons for 5s, 15s, 30s, 1m, 5m, 15m, 30m, 1h, 4h, 8h.
- Saves exactly one audio payload to public Downloads/AudioDashcam via MediaStore.
- Uses one float32 WAV by default.
- Falls back to one raw float32 file only when the save is too large for standard WAV.

## Build

Open this folder in Android Studio and run on a physical Android device.

## Important notes

- This is an MVP/prototype, not a Play Store-ready app.
- It writes a large preallocated file under internal app storage.
- Android must show an active microphone/privacy indicator and foreground-service notification.
- Some devices may reject `MediaRecorder.AudioSource.UNPROCESSED`; if that happens, change it to `MediaRecorder.AudioSource.MIC`.
- This build assumes Android 16 / API 36+, so older-platform compatibility code has been removed.

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

Each save folder and its files share the same timestamp prefix. By default the folder contains `<timestamp>_audio_float32le_mono_48000.wav`. Very large saves fall back to `<timestamp>_audio_float32le_mono_48000.raw` plus the sidecar metadata file `<timestamp>_audio_float32le_mono_48000.json`.


## Importing the raw file

If a save falls back to `.raw`, import it as raw PCM with these settings:

```text
Encoding/sample format: 32-bit float PCM
Endianness: little-endian
Channels: 1 / mono
Sample rate: 48000 Hz
Byte offset: 0
```
