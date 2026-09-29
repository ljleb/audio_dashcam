# Implementation notes

This tree is based on `ljleb/audio_dashcam` commit `f8dc21ec2a33e8238ae10be2c6040d5fc99cf396`.

## Implemented

- Fixed 5.15 GiB float32 PCM byte budget; stereo therefore retains about half as much recorded time as mono.
- Persistent segment index recording logical frame range, physical byte range, sample rate, channel count, capture profile and wall-clock bounds.
- Sample-contiguous cut semantics: stopping/restarting, microphone silencing and capture profile changes insert metadata boundaries without adding zero samples or elapsed-time space.
- Low-overhead persistent waveform peak pyramid (20 ms / 200 ms / 2 s / 20 s display buckets).
- Landscape two-level timeline: all-history overview plus zoomable detailed waveform.
- Tap-to-seek historical playback while capture continues.
- Start selection + optional end selection; end defaults to LIVE.
- Runtime probing of mono/stereo and unprocessed capture configurations.
- Overlap-aware saved clip catalog. Overlapping same-format ranges are exported as their union and superseded MediaStore objects are deleted only after the replacement commits.
- Float32 WAV and RF64/WAVE exports; no PCM16 conversion.
- No explicit Start/Stop button. Opening the app starts the recorder after permission is available.
- `stopWithTask=true` plus `onTaskRemoved()` so removing the task from Recents stops capture.
- Reboot receiver posts an actionable resume reminder rather than illegally starting a microphone foreground service from the background.

## Deliberate safety/format behavior

- A selection crossing mono/stereo boundaries is rejected instead of remixing channels or inventing samples. Split at the format cut for exact exports.
- If an overlap includes saved audio that has already fallen out of the circular ring, the existing saved clip is retained and the merge is rejected. This avoids deleting irreplaceable samples. A future containerized clip format could support lossless merges from both old saved assets and the live ring.
- The waveform index is display-only. It stores min/max summaries; source/export PCM stays float32 and lossless.

## Validation status

The environment used to prepare this tree does not contain the Android SDK or Gradle, and outbound network access is disabled, so `assembleDebug` could not be run locally. A Java syntax pass was performed; expected unresolved Android/`org.json` symbols were present because `android.jar` is unavailable, and no Java syntax errors were detected.

The revision was prepared locally first. Repository write access was later enabled and the same tree was uploaded to the `timeline-editor` branch.
