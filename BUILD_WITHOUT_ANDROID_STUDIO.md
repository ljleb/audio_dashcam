# Build without Android Studio or adb

This project includes a GitHub Actions workflow that can build the APK in the cloud.

## Option A: Build using GitHub Actions

1. Create a GitHub account if you do not already have one.
2. Create a new private repository.
3. Upload all files from this project into the repository.
4. Open the repository's **Actions** tab.
5. Select **Build debug APK**.
6. Click **Run workflow**.
7. After the run finishes, open the completed run.
8. Download the artifact named **AudioDashcamSimple-debug-apk**.
9. Unzip the artifact. It contains `app-debug.apk`.

## Install the APK on your phone without adb

1. Transfer `app-debug.apk` to your phone using USB file transfer, Google Drive, Dropbox, email, or another file-transfer method.
2. Open the APK on the phone.
3. Android will ask whether to allow installation from that source.
4. Allow it for that source.
5. Install the app.
6. Open the app and grant microphone permission.

## Notes

- This is a debug APK, not a Play Store release.
- This build requires Android 16 / API 36 or newer.
- Android may show warnings because the APK is not from the Play Store.
- The app uses a foreground microphone service, so Android should show a persistent notification and microphone privacy indicator.
- The app preallocates about 5.15 GiB for the 8-hour float32 mono buffer.


## Where saved recordings appear

After pressing a save button in the app, open:

```text
Files app > Downloads > AudioDashcam
```

Each save has its own timestamped folder, and the files in that folder use the same timestamp prefix. By default it contains `<timestamp>_audio_float32le_mono_48000.wav`. Very large saves fall back to `<timestamp>_audio_float32le_mono_48000.raw` plus `<timestamp>_audio_float32le_mono_48000.json`.
