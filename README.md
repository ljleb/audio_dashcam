# Audio Dashcam

Android audio dashcam with a fixed-size persistent circular PCM buffer and a cut-aware timeline editor.

## Current design

- Android native Java app, API 36+.
- Foreground microphone service starts automatically whenever the app is opened.
- Swiping the app task out of Recents stops the recorder (`stopWithTask=true`).
- After a reboot, Android does not allow a normal app to silently start a microphone foreground service; a boot receiver posts a reminder notification that opens the app and resumes recording.
- Fixed 5.15 GiB PCM ring budget (the same byte budget as the original 8-hour mono buffer).
- 48 kHz float32 samples are preserved without quantization.
- Mono consumes 4 bytes/frame (~8 hours at a full ring); stereo consumes 8 bytes/frame (~4 hours).
- Runtime capture-profile probing exposes only configurations that initialize on the current device (standard mono/stereo and unprocessed variants where available).
- Persistent segment metadata maps logical frame ranges to physical circular-file byte ranges, channel count, capture profile and wall-clock timestamps.
- Recorder stops/starts, microphone takeovers and format changes create zero-width cut markers. No synthetic silence is inserted and the logical frame index resumes exactly where it left off.
- Landscape-only editor with an all-history overview and a pinch-zoomable detailed waveform.
- Historical playback uses `AudioTrack` while capture continues.
- Selection start is explicit; selection end defaults to LIVE.
- Overlapping saves of the same PCM format are unioned into one replacement clip when the complete union remains in the ring.
- Standard float WAV is used up to the RIFF size limit; larger lossless exports use RF64/WAVE.

## Important Android constraint

A normal API 36 application cannot start a microphone foreground service directly from `BOOT_COMPLETED`. The reboot notification is therefore the recovery path: tap it once after boot, the activity becomes foreground, and recording resumes automatically.

The app still has to provide Android's mandatory foreground-service notification while recording. Its channel is low-importance, silent, non-vibrating and `setOnlyAlertOnce(true)`. `POST_NOTIFICATIONS` remains useful because it is required for the separate actionable reboot reminder.

## Timeline semantics

The horizontal axis is recorded audio, not wall-clock time. If capture stops for 20 minutes:

```text
samples before cut | samples after cut
-------------------|-------------------
                   +20m metadata
```

The 20 minutes consume no waveform width and no zero samples. The segment metadata retains the elapsed wall-clock duration and cut reason.

## Storage layout

Internal app storage:

```text
files/ring-v2/audio_ring_float32.pcm      fixed 5.15 GiB byte ring
files/ring-v2/audio_ring_state.json       persistent segment/cursor index
files/saved_clips.json                    saved interval / MediaStore index
```

Saved clips are published to:

```text
Downloads/AudioDashcam/
```

## Editor controls

- Tap detailed waveform: seek playhead.
- Drag: pan.
- Pinch: zoom from roughly one second to all retained audio.
- Double tap or `Go LIVE`: snap to the current recording edge.
- `Set start`: set selection start at playhead.
- `Set end`: set historical selection end at playhead.
- `End = LIVE`: make the selection follow the live edge.
- `Play/Pause`: listen to retained audio without stopping recording.

Orange vertical lines indicate capture cuts. At detailed zoom, elapsed real time is displayed next to the line when available.

## Format boundaries

Capture mode changes create segment boundaries. A save can concatenate cuts when the PCM file format is unchanged. A selection crossing a mono/stereo boundary is intentionally rejected rather than silently remixing or dropping information; split the selection at that cut to preserve the exact captured samples.
