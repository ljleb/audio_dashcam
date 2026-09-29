package com.example.audiodashcam;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.AudioTrack;
import android.os.Binder;
import android.os.IBinder;
import android.os.SystemClock;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RecorderService extends Service {
    static final String CHANNEL_ID = "audio_dashcam_recording";
    static final int NOTIFICATION_ID = 1;
    private static final String PREFS = "audio_dashcam";
    private static final String PREF_PROFILE = "capture_profile";

    private final LocalBinder binder = new LocalBinder();
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Object captureLock = new Object();

    private volatile boolean running;
    private volatile boolean restartCapture;
    private volatile long recordingStartedElapsedMs;
    private volatile String lastStatus = "Idle";
    private Thread recordThread;
    private PcmRingBuffer ring;
    private ClipRepository clips;
    private List<AudioProfile> supportedProfiles = Collections.singletonList(AudioProfile.MONO);
    private volatile AudioProfile selectedProfile = AudioProfile.MONO;

    private final Object playbackLock = new Object();
    private volatile boolean playbackRunning;
    private volatile long playbackFrame = -1;
    private Thread playbackThread;

    public final class LocalBinder extends Binder {
        RecorderService service() { return RecorderService.this; }
    }

    @Override public void onCreate() {
        super.onCreate();
        clips = new ClipRepository(this);
        try {
            ring = new PcmRingBuffer(new File(getFilesDir(), "ring-v2"));
        } catch (IOException e) {
            lastStatus = "Ring failed: " + e.getMessage();
        }

        supportedProfiles = AudioProfile.discoverSupported();
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        AudioProfile saved = AudioProfile.fromId(prefs.getString(PREF_PROFILE, AudioProfile.MONO.id));
        selectedProfile = supportedProfiles.contains(saved) ? saved : supportedProfiles.get(0);
    }

    @Override public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundServiceWork();
        return START_NOT_STICKY;
    }

    private void startForegroundServiceWork() {
        createNotificationChannel();
        startForeground(
                NOTIFICATION_ID,
                buildNotification("Rolling audio buffer active"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        );

        if (ring == null) {
            stopSelf();
            return;
        }
        synchronized (captureLock) {
            if (!running) startRecordingThread();
        }
    }

    private void startRecordingThread() {
        running = true;
        restartCapture = false;
        recordThread = new Thread(() -> {
            while (running) {
                AudioProfile profile = selectedProfile;
                AudioRecord recorder = null;
                try {
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        lastStatus = "Microphone permission required";
                        break;
                    }

                    int minBytes = AudioRecord.getMinBufferSize(
                            AudioProfile.SAMPLE_RATE,
                            profile.channelMask(),
                            AudioFormat.ENCODING_PCM_FLOAT
                    );
                    if (minBytes <= 0) throw new IOException("Unsupported audio configuration: " + profile.label);

                    int framesPerRead = Math.max(4096, minBytes / profile.bytesPerFrame());
                    float[] buffer = new float[framesPerRead * profile.channels];

                    recorder = new AudioRecord.Builder()
                            .setAudioSource(profile.audioSource)
                            .setAudioFormat(new AudioFormat.Builder()
                                    .setSampleRate(AudioProfile.SAMPLE_RATE)
                                    .setChannelMask(profile.channelMask())
                                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                                    .build())
                            .setBufferSizeInBytes(Math.max(minBytes * 2, buffer.length * 4))
                            .build();

                    if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                        throw new IOException("AudioRecord did not initialize for " + profile.label);
                    }

                    recorder.startRecording();
                    recordingStartedElapsedMs = SystemClock.elapsedRealtime();
                    lastStatus = "Recording • " + profile.label;
                    ring.beginSegment(profile, System.currentTimeMillis(), "service-start");
                    boolean wasSilenced = false;

                    while (running && !restartCapture) {
                        int n = recorder.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                        if (n < 0) throw new IOException("AudioRecord read failed: " + n);
                        if (n == 0) continue;

                        boolean silenced = false;
                        try {
                            AudioRecordingConfiguration cfg = recorder.getActiveRecordingConfiguration();
                            silenced = cfg != null && cfg.isClientSilenced();
                        } catch (Throwable ignored) {}

                        if (silenced) {
                            if (!wasSilenced) {
                                ring.endActiveSegment(System.currentTimeMillis());
                                lastStatus = "Microphone temporarily unavailable";
                            }
                            wasSilenced = true;
                            continue;
                        }

                        if (wasSilenced) {
                            ring.beginSegment(profile, System.currentTimeMillis(), "microphone-resumed");
                            lastStatus = "Recording • " + profile.label;
                            wasSilenced = false;
                        }

                        ring.appendFloats(buffer, n, profile, System.currentTimeMillis());
                    }
                } catch (Exception e) {
                    lastStatus = "Recording error: " + e.getMessage();
                    try { if (ring != null) ring.endActiveSegment(System.currentTimeMillis()); } catch (IOException ignored) {}
                    if (!restartCapture) {
                        running = false;
                        stopSelf();
                    }
                } finally {
                    if (recorder != null) {
                        try { recorder.stop(); } catch (Exception ignored) {}
                        recorder.release();
                    }
                }

                if (restartCapture && running) {
                    restartCapture = false;
                    try { ring.endActiveSegment(System.currentTimeMillis()); } catch (IOException ignored) {}
                    continue;
                }
                break;
            }
            recordingStartedElapsedMs = 0;
        }, "AudioDashcamRecorder");
        recordThread.start();
    }

    List<AudioProfile> supportedProfiles() {
        return supportedProfiles;
    }

    AudioProfile selectedProfile() {
        return selectedProfile;
    }

    void setSelectedProfile(AudioProfile profile) {
        if (profile == null || !supportedProfiles.contains(profile) || profile.id.equals(selectedProfile.id)) return;
        selectedProfile = profile;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_PROFILE, profile.id).apply();
        restartCapture = true;
        lastStatus = "Switching to " + profile.label + "…";
    }

    boolean isRecording() { return running; }

    long earliestFrame() { return ring == null ? 0 : ring.earliestFrame(); }
    long latestFrameExclusive() { return ring == null ? 0 : ring.latestFrameExclusive(); }
    List<PcmRingBuffer.SegmentSnapshot> segments() { return ring == null ? Collections.emptyList() : ring.segmentSnapshots(); }

    float[] envelope(long start, long end, int columns) {
        if (ring == null) return new float[Math.max(0, columns * 2)];
        try { return ring.envelope(start, end, columns); }
        catch (IOException e) { return new float[Math.max(0, columns * 2)]; }
    }

    void saveRange(long startFrame, long endFrameExclusive, SaveCallback callback) {
        if (ring == null) {
            callback.done(false, "Recorder not ready");
            return;
        }
        ioExecutor.execute(() -> {
            try {
                String result = clips.saveRange(ring, startFrame, endFrameExclusive);
                lastStatus = "Saved";
                callback.done(true, result);
            } catch (Exception e) {
                callback.done(false, e.getMessage());
            }
        });
    }

    void startPlayback(long startFrame) {
        stopPlayback();
        if (ring == null) return;
        playbackRunning = true;
        playbackFrame = Math.max(startFrame, ring.earliestFrame());
        playbackThread = new Thread(() -> {
            AudioTrack track = null;
            int currentChannels = 0;
            try {
                while (playbackRunning && playbackFrame < ring.latestFrameExclusive()) {
                    PcmRingBuffer.AudioChunk chunk = ring.readChunk(playbackFrame, 4096);
                    if (chunk == null || chunk.frameCount <= 0) break;

                    if (track == null || currentChannels != chunk.channels) {
                        if (track != null) {
                            try { track.stop(); } catch (Exception ignored) {}
                            track.release();
                        }
                        currentChannels = chunk.channels;
                        int outMask = currentChannels == 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
                        int min = AudioTrack.getMinBufferSize(AudioProfile.SAMPLE_RATE, outMask, AudioFormat.ENCODING_PCM_FLOAT);
                        track = new AudioTrack.Builder()
                                .setAudioAttributes(new AudioAttributes.Builder()
                                        .setUsage(AudioAttributes.USAGE_MEDIA)
                                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                        .build())
                                .setAudioFormat(new AudioFormat.Builder()
                                        .setSampleRate(AudioProfile.SAMPLE_RATE)
                                        .setChannelMask(outMask)
                                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                                        .build())
                                .setBufferSizeInBytes(Math.max(min, 4096 * currentChannels * 4))
                                .setTransferMode(AudioTrack.MODE_STREAM)
                                .build();
                        track.play();
                    }

                    int written = track.write(chunk.interleaved, 0, chunk.interleaved.length, AudioTrack.WRITE_BLOCKING);
                    if (written < 0) break;
                    playbackFrame += chunk.frameCount;
                }
            } catch (Exception e) {
                lastStatus = "Playback error: " + e.getMessage();
            } finally {
                if (track != null) {
                    try { track.stop(); } catch (Exception ignored) {}
                    track.release();
                }
                playbackRunning = false;
            }
        }, "AudioDashcamPlayback");
        playbackThread.start();
    }

    void stopPlayback() {
        playbackRunning = false;
        Thread t = playbackThread;
        if (t != null) {
            t.interrupt();
            try { t.join(250); } catch (InterruptedException ignored) {}
        }
        playbackThread = null;
    }

    boolean isPlaying() { return playbackRunning; }
    long playbackFrame() { return playbackFrame; }

    String status() {
        if (ring == null) return lastStatus;
        double seconds = ring.retainedRecordedSeconds();
        String duration = formatDuration((long) seconds);
        String bytes = PcmRingBuffer.human(ring.usedBytes());
        if (running && recordingStartedElapsedMs > 0) {
            long on = Math.max(0, (SystemClock.elapsedRealtime() - recordingStartedElapsedMs) / 1000);
            return lastStatus + " • session " + formatDuration(on) + " • retained " + duration + " / " + bytes;
        }
        return lastStatus + " • retained " + duration + " / " + bytes;
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        // android:stopWithTask handles the normal path; explicitly stopping makes the
        // user-visible contract unambiguous on OEM variants.
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        running = false;
        restartCapture = false;
        stopPlayback();
        Thread t = recordThread;
        if (t != null) {
            t.interrupt();
            try { t.join(500); } catch (InterruptedException ignored) {}
        }
        if (ring != null) ring.close();
        ioExecutor.shutdownNow();
        super.onDestroy();
    }

    private Notification buildNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Audio Dashcam")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Active recorder",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Required foreground-service indicator while Audio Dashcam records");
        channel.setSound(null, null);
        channel.enableVibration(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private static String formatDuration(long seconds) {
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        if (h > 0) return h + "h " + m + "m " + s + "s";
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }

    public interface SaveCallback {
        void done(boolean ok, String message);
    }
}
