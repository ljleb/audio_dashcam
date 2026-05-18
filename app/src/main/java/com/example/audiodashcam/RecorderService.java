package com.example.audiodashcam;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.IBinder;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RecorderService extends Service {
    static final String CHANNEL_ID = "audio_dashcam_recording";
    private final LocalBinder binder = new LocalBinder();
    private final ExecutorService exportExecutor = Executors.newSingleThreadExecutor();

    private volatile boolean running = false;
    private Thread recordThread;
    private PcmRingBuffer ring;
    private String lastStatus = "Idle";

    public final class LocalBinder extends Binder {
        RecorderService service() { return RecorderService.this; }
    }

    @Override public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundServiceWork();
        return START_STICKY;
    }

    private void startForegroundServiceWork() {
        createNotificationChannel();
        startForeground(
                1,
                buildNotification("Recording rolling audio buffer"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        );

        if (!running) {
            try {
                File dir = new File(getFilesDir(), "ring");
                ring = new PcmRingBuffer(dir, PcmRingBuffer.DEFAULT_CAPACITY_SECONDS);
                startRecordingThread();
            } catch (Exception e) {
                lastStatus = "Start failed: " + e.getMessage();
                stopSelf();
            }
        }
    }

    private void startRecordingThread() {
        running = true;
        recordThread = new Thread(() -> {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                lastStatus = "Missing microphone permission";
                running = false;
                stopSelf();
                return;
            }

            int minBufferBytes = AudioRecord.getMinBufferSize(
                    PcmRingBuffer.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_FLOAT
            );

            int framesPerRead = Math.max(4096, minBufferBytes / 4);
            float[] buffer = new float[framesPerRead];

            AudioRecord recorder = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(PcmRingBuffer.SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .build())
                    .setBufferSizeInBytes(Math.max(minBufferBytes * 2, framesPerRead * 4))
                    .build();

            try {
                recorder.startRecording();
                lastStatus = "Recording";
                while (running) {
                    int n = recorder.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                    if (n > 0) {
                        ring.appendFloats(buffer, n);
                    }
                }
            } catch (Exception e) {
                lastStatus = "Recording error: " + e.getMessage();
                running = false;
                stopSelf();
            } finally {
                try { recorder.stop(); } catch (Exception ignored) {}
                recorder.release();
            }
        }, "AudioDashcamRecorder");

        recordThread.start();
    }

    public void saveLastSeconds(long seconds, SaveCallback callback) {
        PcmRingBuffer r = ring;
        if (r == null) {
            callback.done(false, "Recorder not ready");
            return;
        }

        exportExecutor.execute(() -> {
            try {
                String publishedPath = r.exportLastSeconds(
                        sessionName -> FilePublisher.openDownloadsSession(this, sessionName),
                        seconds
                );
                lastStatus = "Saved to " + publishedPath;
                callback.done(true, publishedPath);
            } catch (IOException e) {
                lastStatus = "Save failed: " + e.getMessage();
                callback.done(false, e.getMessage());
            }
        });
    }

    public long availableSeconds() {
        return ring == null ? 0 : ring.availableSeconds();
    }

    public boolean isRecording() {
        return running;
    }

    public String status() {
        return lastStatus + " - buffered " + availableSeconds() + "s";
    }

    @Override public void onDestroy() {
        running = false;
        if (recordThread != null) {
            try { recordThread.join(500); } catch (InterruptedException ignored) {}
        }
        if (ring != null) ring.close();
        exportExecutor.shutdownNow();
        super.onDestroy();
    }

    private Notification buildNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Audio Dashcam")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Recording",
                NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(channel);
    }

    public interface SaveCallback {
        void done(boolean ok, String message);
    }
}
