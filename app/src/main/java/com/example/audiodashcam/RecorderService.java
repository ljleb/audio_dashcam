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
                    lastStatus = "Recording â€¢ " + profile.label;
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
                            continue; // Never write synthetic zero samples to the timeline.
                        }

                        if (wasSilenced) {
                            ring.beginSegment(profile, System.currentTimeMillis(), "microphone-resumed");
                            lastStatus = "Recording â€¢ " + profile.label;
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
        lastStatus = "Switching to " + profile.label + "â€¦";
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
        if (ring == null¤ì(€€€€€€€€€€€…±±‰…¬¹‘½¹”¡™…±Í”°€‰I•½É‘•È¹½ĞÉ•…‘äˆ¤ì(€€€€€€€€€€€É•ÑÕÉ¸ì(€€€€€€€ô(€€€€€€€¥½á•ÕÑ½È¹•á•ÕÑ”  ¤€´øì(€€€€€€€€€€€ÑÉäì(€€€€€€€€€€€€€€€MÑÉ¥¹œÉ•ÍÕ±Ğ€ô±¥ÁÌ¹Í…Ù•I…¹”¡É¥¹œ°ÍÑ…ÉÑÉ…µ”°•¹‘É…µ•á±ÕÍ¥Ù”¤ì(€€€€€€€€€€€€€€€±…ÍÑMÑ…ÑÕÌ€ô€‰M…Ù•ˆì(€€€€€€€€€€€€€€€…±±‰…¬¹‘½¹”¡ÑÉÕ”°É•ÍÕ±Ğ¤ì(€€€€€€€€€€€ô…Ñ €¡á•ÁÑ¥½¸”¤ì(€€€€€€€€€€€€€€€…±±‰…¬¹‘½¹”¡™…±Í”°”¹•Ñ5•ÍÍ…” ¤¤ì(€€€€€€€€€€€ô(€€€€€€€ô¤ì(€€€ô((€€€Ù½¥ÍÑ…ÉÑA±…å‰…¬¡±½¹œÍÑ…ÉÑÉ…µ”¤ì(€€€€€€€ÍÑ½ÁA±…å‰…¬ ¤ì(€€€€€€€¥˜€¡É¥¹œ€ôô¹Õ±°¤É•ÑÕÉ¸ì(€€€€€€€Á±…å‰…­IÕ¹¹¥¹œ€ôÑÉÕ”ì(€€€€€€€Á±…å‰…­É…µ”€ô5…Ñ ¹µ…à¡ÍÑ…ÉÑÉ…µ”°É¥¹œ¹•…É±¥•ÍÑÉ…µ” ¤¤ì(€€€€€€€Á±…å‰…­Q¡É•…€ô¹•ÜQ¡É•…  ¤€´øì(€€€€€€€€€€€Õ‘¥½QÉ…¬ÑÉ…¬€ô¹Õ±°ì(€€€€€€€€€€€¥¹ĞÕÉÉ•¹Ñ¡…¹¹•±Ì€ô€Àì(€€€€€€€€€€€ÑÉäì(€€€€€€€€€€€€€€€İ¡¥±”€¡Á±…å‰…­IÕ¹¹¥¹œ€˜˜Á±…å‰…­É…µ”€ğÉ¥¹œ¹±…Ñ•ÍÑÉ…µ•á±ÕÍ¥Ù” ¤¤ì(€€€€€€€€€€€€€€€€€€€AµI¥¹	Õ™™•È¹Õ‘¥½¡Õ¹¬¡Õ¹¬€ôÉ¥¹œ¹É•…‘¡Õ¹¬¡Á±…å‰…­É…µ”°€ĞÀäØ¤ì(€€€€€€€€€€€€€€€€€€€¥˜€¡¡Õ¹¬€ôô¹Õ±°ñğ¡Õ¹¬¹™É…µ•½Õ¹Ğ€ğô€À¤‰É•…¬ì((€€€€€€€€€€€€€€€€€€€¥˜€¡ÑÉ…¬€ôô¹Õ±°ñğÕÉÉ•¹Ñ¡…¹¹•±Ì€„ô¡Õ¹¬¹¡…¹¹•±Ì¤ì(€€€€€€€€€€€€€€€€€€€€€€€¥˜€¡ÑÉ…¬€„ô¹Õ±°¤ì(€€€€€€€€€€€€€€€€€€€€€€€€€€€ÑÉäìÑÉ…¬¹ÍÑ½À ¤ìô…Ñ €¡á•ÁÑ¥½¸¥¹½É•¤íô(€€€€€€€€€€€€€€€€€€€€€€€€€€€ÑÉ…¬¹É•±•…Í” ¤ì(€€€€€€€€€€€€€€€€€€€€€€€ô(€€€€€€€€€€€€€€€€€€€€€€€ÕÉÉ•¹Ñ¡…¹¹•±Ì€ô¡Õ¹¬¹¡…¹¹•±Ìì(€€€€€€€€€€€€€€€€€€€€€€€¥¹Ğ½ÕÑ5…Í¬€ôÕÉÉ•¹Ñ¡…¹¹•±Ì€ôô€È€üÕ‘¥½½Éµ…Ğ¹!991}=UQ}MQI<€èÕ‘¥½½Éµ…Ğ¹!991}=UQ}5=9<ì(€€€€€€€€€€€€€€€€€€€€€€€¥¹Ğµ¥¸€ôÕ‘¥½QÉ…¬¹•Ñ5¥¹	Õ™™•ÉM¥é”¡Õ‘¥½AÉ½™¥±”¹M5A1}IQ°½ÕÑ5…Í¬°Õ‘¥½½Éµ…Ğ¹9=%9}A5}1=P¤ì(€€€€€€€€€€€€€€€€€€€€€€€ÑÉ…¬€ô¹•ÜÕ‘¥½QÉ…¬¹	Õ¥±‘•È ¤(€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€¹Í•ÑÕ‘¥½ÑÑÉ¥‰ÕÑ•Ì¡¹•ÜÕ‘¥½ÑÑÉ¥‰ÕÑ•Ì¹	Õ¥±‘•È ¤(€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€¹Í•ÑUÍ…”¡Õ‘¥½ÑÑÉ¥‰ÕÑ•Ì¹UM}5%¤(€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€¹Í•Ñ½¹Ñ•¹ÑQåÁ”¡Õ‘¥½ÑÑÉ¥‰ÕÑ•Ì¹=9Q9Q}QeA}5UM%¤(€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€¹‰Õ¥± ¤¤(€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€¹Í•ÑÕ‘¥½½Éµ…Ğ¡¹•ÜÕ‘¥½½Éµ…Ğ¹	Õ¥±‘•È ¤(€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€¹Í•ÑM…µÁ±•I…Ñ”¡Õ‘¥½AÉ½™¥±”¹M5A1}IQ¤(€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€¹Í•Ñ¡…¹¹•±5…Í¬¡½ÕÑ5…Í¬¤(€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€¹Í•Ñ¹½‘¥¹œ¡Õ‘¥½½Éµ…Ğ¹9=%9}A5}1=P¤(€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€¹‰Õ¥± ¤¤(€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€¹Í•Ñ	Õ™™•ÉM¥é•%¹	åÑ•Ì¡5…Ñ ¹µ…à¡µ¥¸°€ĞÀäØ€¨ÕÉÉ•¹Ñ¡…¹¹•±Ì€¨€Ğ¤¤(€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€¹Í•ÑQÉ…¹Í™•É5½‘”¡Õ‘¥½QÉ…¬¹5=}MQI4¤(€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€¹‰Õ¥± ¤ì(€€€€€€€€€€€€€€€€€€€€€€€ÑÉ…¬¹Á±…ä ¤ì(€€€€€€€€€€€€€€€€€€€ô((€€€€€€€€€€€€€€€€€€€¥¹ĞİÉ¥ÑÑ•¸€ôÑÉ…¬¹İÉ¥Ñ”¡¡Õ¹¬¹¥¹Ñ•É±•…Ù•°€À°¡Õ¹¬¹¥¹Ñ•É±•…Ù•¹±•¹Ñ °Õ‘¥½QÉ…¬¹]I%Q}	1=-%9¤ì(€€€€€€€€€€€€€€€€€€€¥˜€¡İÉ¥ÑÑ•¸€ğ€À¤‰É•…¬ì(€€€€€€€€€€€€€€€€€€€Á±…å‰…­É…µ”€¬ô¡Õ¹¬¹™É…µ•½Õ¹Ğì(€€€€€€€€€€€€€€€ô(€€€€€€€€€€€ô…Ñ €¡á•ÁÑ¥½¸”¤ì(€€€€€€€€€€€€€€€±…ÍÑMÑ…ÑÕÌ€ô€‰A±…å‰…¬•ÉÉ½Èè€ˆ€¬”¹•Ñ5•ÍÍ…” ¤ì(€€€€€€€€€€€ô™¥¹…±±äì(€€€€€€€€€€€€€€€¥˜€¡ÑÉ…¬€„ô¹Õ±°¤ì(€€€€€€€€€€€€€€€€€€€ÑÉäìÑÉ…¬¹ÍÑ½À ¤ìô…Ñ €¡á•ÁÑ¥½¸¥¹½É•¤íô(€€€€€€€€€€€€€€€€€€€ÑÉ…¬¹É•±•…Í” ¤ì(€€€€€€€€€€€€€€€ô(€€€€€€€€€€€€€€€Á±…å‰…­IÕ¹¹¥¹œ€ô™…±Í”ì(€€€€€€€€€€€ô(€€€€€€€ô°€‰Õ‘¥½…Í¡…µA±…å‰…¬ˆ¤ì(€€€€€€€Á±…å‰…­Q¡É•…¹ÍÑ…ÉĞ ¤ì(€€€ô((€€€Ù½¥ÍÑ½ÁA±…å‰…¬ ¤ì(€€€€€€€Á±…å‰…­IÕ¹¹¥¹œ€ô™…±Í”ì(€€€€€€€Q¡É•…Ğ€ôÁ±…å‰…­Q¡É•…ì(€€€€€€€¥˜€¡Ğ€„ô¹Õ±°¤ì(€€€€€€€€€€€Ğ¹¥¹Ñ•ÉÉÕÁĞ ¤ì(€€€€€€€€€€€ÑÉäìĞ¹©½¥¸ ÈÔÀ¤ìô…Ñ €¡%¹Ñ•ÉÉÕÁÑ•‘á•ÁÑ¥½¸¥¹½É•¤íô(€€€€€€€ô(€€€€€€€Á±…å‰…­Q¡É•…€ô¹Õ±°ì(€€€ô((€€€‰½½±•…¸¥ÍA±…å¥¹œ ¤ìÉ•ÑÕÉ¸Á±…å‰…­IÕ¹¹¥¹œìô(€€€±½¹œÁ±…å‰…­É…µ” ¤ìÉ•ÑÕÉ¸Á±…å‰…­É…µ”ìô((€€€MÑÉ¥¹œÍÑ…ÑÕÌ ¤ì(€€€€€€€¥˜€¡É¥¹œ€ôô¹Õ±°¤É•ÑÕÉ¸±…ÍÑMÑ…ÑÕÌì(€€€€€€€‘½Õ‰±”Í•½¹‘Ì€ôÉ¥¹œ¹É•Ñ…¥¹•‘I•½É‘•‘M•½¹‘Ì ¤ì(€€€€€€€MÑÉ¥¹œ‘ÕÉ…Ñ¥½¸€ô™½Éµ…ÑÕÉ…Ñ¥½¸ ¡±½¹œ¤Í•½¹‘Ì¤ì(€€€€€€€MÑÉ¥¹œ‰åÑ•Ì€ôAµI¥¹	Õ™™•È¹¡Õµ…¸¡É¥¹œ¹ÕÍ•‘	åÑ•Ì ¤¤ì(€€€€€€€¥˜€¡ÉÕ¹¹¥¹œ€˜˜É•½É‘¥¹MÑ…ÉÑ•‘±…ÁÍ•‘5Ì€ø€À¤ì(€€€€€€€€€€€±½¹œ½¸€ô5…Ñ ¹µ…à À°€¡MåÍÑ•µ±½¬¹•±…ÁÍ•‘I•…±Ñ¥µ” ¤€´É•½É‘¥¹MÑ…ÉÑ•‘±…ÁÍ•‘5Ì¤€¼€ÄÀÀÀ¤ì(€€€€€€€€€€€É•ÑÕÉ¸±…ÍÑMÑ…ÑÕÌ€¬€ˆƒŠˆÍ•ÍÍ¥½¸€ˆ€¬™½Éµ…ÑÕÉ…Ñ¥½¸¡½¸¤€¬€ˆƒŠˆÉ•Ñ…¥¹•€ˆ€¬‘ÕÉ…Ñ¥½¸€¬€ˆ€¼€ˆ€¬‰åÑ•Ìì(€€€€€€€ô(€€€€€€€É•ÑÕÉ¸±…ÍÑMÑ…ÑÕÌ€¬€ˆƒŠˆÉ•Ñ…¥¹•€ˆ€¬‘ÕÉ…Ñ¥½¸€¬€ˆ€¼€ˆ€¬‰åÑ•Ìì(€€€ô((€€€=Ù•ÉÉ¥‘”ÁÕ‰±¥ŒÙ½¥½¹Q…Í­I•µ½Ù•¡%¹Ñ•¹ĞÉ½½Ñ%¹Ñ•¹Ğ¤ì(€€€€€€€€¼¼…¹‘É½¥éÍÑ½Á]¥Ñ¡Q…Í¬¡…¹‘±•ÌÑ¡”¹½Éµ…°Á…Ñ ì•áÁ±¥¥Ñ±äÍÑ½ÁÁ¥¹œµ…­•ÌÑ¡”(€€€€€€€€¼¼ÕÍ•ÈµÙ¥Í¥‰±”½¹ÑÉ…ĞÕ¹…µ‰¥Õ½ÕÌ½¸=4Ù…É¥…¹ÑÌ¸(€€€€€€€ÍÑ½ÁM•±˜ ¤ì(€€€€€€€ÍÕÁ•È¹½¹Q…Í­I•µ½Ù•¡É½½Ñ%¹Ñ•¹Ğ¤ì(€€€ô((€€€=Ù•ÉÉ¥‘”ÁÕ‰±¥ŒÙ½¥½¹•ÍÑÉ½ä ¤ì(€€€€€€€ÉÕ¹¹¥¹œ€ô™…±Í”ì(€€€€€€€É•ÍÑ…ÉÑ…ÁÑÕÉ”€ô™…±Í”ì(€€€€€€€ÍÑ½ÁA±…å‰…¬ ¤ì(€€€€€€€Q¡É•…Ğ€ôÉ•½É‘Q¡É•…ì(€€€€€€€¥˜€¡Ğ€„ô¹Õ±°¤ì(€€€€€€€€€€€Ğ¹¥¹Ñ•ÉÉÕÁĞ ¤ì(€€€€€€€€€€€ÑÉäìĞ¹©½¥¸ ÔÀÀ¤ìô…Ñ €¡%¹Ñ•ÉÉÕÁÑ•‘á•ÁÑ¥½¸¥¹½É•¤íô(€€€€€€€ô(€€€€€€€¥˜€¡É¥¹œ€„ô¹Õ±°¤É¥¹œ¹±½Í” ¤ì(€€€€€€€¥½á•ÕÑ½È¹Í¡ÕÑ‘½İ¹9½Ü ¤ì(€€€€€€€ÍÕÁ•È¹½¹•ÍÑÉ½ä ¤ì(€€€ô((€€€ÁÉ¥Ù…Ñ”9½Ñ¥™¥…Ñ¥½¸‰Õ¥±‘9½Ñ¥™¥…Ñ¥½¸¡MÑÉ¥¹œÑ•áĞ¤ì(€€€€€€€%¹Ñ•¹Ğ¥¹Ñ•¹Ğ€ô¹•Ü%¹Ñ•¹Ğ¡Ñ¡¥Ì°5…¥¹Ñ¥Ù¥Ñä¹±…ÍÌ¤ì(€€€€€€€A•¹‘¥¹%¹Ñ•¹ĞÁ¤€ôA•¹‘¥¹%¹Ñ•¹Ğ¹•ÑÑ¥Ù¥Ñä¡Ñ¡¥Ì°€À°¥¹Ñ•¹Ğ°(€€€€€€€€€€€€€€€A•¹‘¥¹%¹Ñ•¹Ğ¹1}%55UQ	1ğA•¹‘¥¹%¹Ñ•¹Ğ¹1}UAQ}UII9P¤ì(€€€€€€€É•ÑÕÉ¸¹•Ü9½Ñ¥™¥…Ñ¥½¸¹	Õ¥±‘•È¡Ñ¡¥Ì°!991}%¤(€€€€€€€€€€€€€€€€¹Í•Ñ½¹Ñ•¹ÑQ¥Ñ±” ‰Õ‘¥¼…Í¡…´ˆ¤(€€€€€€€€€€€€€€€€¹Í•Ñ½¹Ñ•¹ÑQ•áĞ¡Ñ•áĞ¤(€€€€€€€€€€€€€€€€¹Í•ÑMµ…±±%½¸¡…¹‘É½¥¹H¹‘É…İ…‰±”¹¥}‰Ñ¹}ÍÁ•…­}¹½Ü¤(€€€€€€€€€€€€€€€€¹Í•Ñ½¹Ñ•¹Ñ%¹Ñ•¹Ğ¡Á¤¤(€€€€€€€€€€€€€€€€¹Í•Ñ=¹½¥¹œ¡ÑÉÕ”¤(€€€€€€€€€€€€€€€€¹Í•Ñ=¹±å±•ÉÑ=¹”¡ÑÉÕ”¤(€€€€€€€€€€€€€€€€¹Í•Ñ…Ñ•½Éä¡9½Ñ¥™¥…Ñ¥½¸¹Q=Ie}MIY%¤(€€€€€€€€€€€€€€€€¹‰Õ¥± ¤ì(€€€ô((€€€ÁÉ¥Ù…Ñ”Ù½¥É•…Ñ•9½Ñ¥™¥…Ñ¥½¹¡…¹¹•° ¤ì(€€€€€€€9½Ñ¥™¥…Ñ¥½¹¡…¹¹•°¡…¹¹•°€ô¹•Ü9½Ñ¥™¥…Ñ¥½¹¡…¹¹•° (€€€€€€€€€€€€€€€!991}%°(€€€€€€€€€€€€€€€€‰Ñ¥Ù”É•½É‘•Èˆ°(€€€€€€€€€€€€€€€9½Ñ¥™¥…Ñ¥½¹5…¹…•È¹%5A=IQ9}1=\(€€€€€€€€¤ì(€€€€€€€¡…¹¹•°¹Í•Ñ•ÍÉ¥ÁÑ¥½¸ ‰I•ÅÕ¥É•™½É•É½Õ¹µÍ•ÉÙ¥”¥¹‘¥…Ñ½Èİ¡¥±”Õ‘¥¼…Í¡…´É•½É‘Ìˆ¤ì(€€€€€€€¡…¹¹•°¹Í•ÑM½Õ¹¡¹Õ±°°¹Õ±°¤ì(€€€€€€€¡…¹¹•°¹•¹…‰±•Y¥‰É…Ñ¥½¸¡™…±Í”¤ì(€€€€€€€•ÑMåÍÑ•µM•ÉÙ¥”¡9½Ñ¥™¥…Ñ¥½¹5…¹…•È¹±…ÍÌ¤¹É•…Ñ•9½Ñ¥™¥…Ñ¥½¹¡…¹¹•°¡¡…¹¹•°¤ì(€€€ô((€€€ÁÉ¥Ù…Ñ”ÍÑ…Ñ¥ŒMÑÉ¥¹œ™½Éµ…ÑÕÉ…Ñ¥½¸¡±½¹œÍ•½¹‘Ì¤ì(€€€€€€€±½¹œ €ôÍ•½¹‘Ì€¼€ÌØÀÀì(€€€€€€€±½¹œ´€ô€¡Í•½¹‘Ì€”€ÌØÀÀ¤€¼€ØÀì(€€€€€€€±½¹œÌ€ôÍ•½¹‘Ì€”€ØÀì(€€€€€€€¥˜€¡ €ø€À¤É•ÑÕÉ¸ €¬€‰ €ˆ€¬´€¬€‰´€ˆ€¬Ì€¬€‰Ìˆì(€€€€€€€¥˜€¡´€ø€À¤É•ÑÕÉ¸´€¬€‰´€ˆ€¬Ì€¬€‰Ìˆì(€€€€€€€É•ÑÕÉ¸Ì€¬€‰Ìˆì(€€€ô((€€€ÁÕ‰±¥Œ¥¹Ñ•É™…”M…Ù•…±±‰…¬ì(€€€€€€€Ù½¥‘½¹”¡‰½½±•…¸½¬°MÑÉ¥¹œµ•ÍÍ…”¤ì(€€€ô)ô