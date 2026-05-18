package com.example.audiodashcam;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import android.app.Activity;

import java.util.ArrayList;

public class MainActivity extends Activity {
    private static final int REQ_PERMS = 7;
    private RecorderService service;
    private boolean bound = false;
    private boolean startPending = false;
    private boolean userStopped = false;
    private Button recordToggle;
    private TextView status;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((RecorderService.LocalBinder) binder).service();
            bound = true;
            startPending = false;
            userStopped = false;
            updateStatus();
            updateRecordingToggle();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            boolean failedWhileStarting = startPending;
            boolean stoppedByUser = userStopped;
            bound = false;
            service = null;
            startPending = false;
            userStopped = false;

            if (failedWhileStarting) {
                updateStatusText("Recording failed to start.");
            } else if (!stoppedByUser) {
                updateStatusText("Recording stopped.");
            }
            updateRecordingToggle();
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        requestPermissionsIfNeeded();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(36, 48, 36, 48);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Audio Dashcam");
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("48 kHz - mono - float32 PCM - 8h circular buffer");
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        subtitle.setPadding(0, 12, 0, 24);
        root.addView(subtitle);

        recordToggle = new Button(this);
        recordToggle.setText("Start Recording");
        recordToggle.setOnClickListener(v -> toggleRecorder());
        root.addView(recordToggle);

        status = new TextView(this);
        status.setText("Idle");
        status.setPadding(0, 24, 0, 24);
        root.addView(status);

        long[] durations = {
                5,
                15,
                30,
                60,

                5 * 60,
                15 * 60,
                30 * 60,

                60 * 60,
                4 * 60 * 60,
                8 * 60 * 60
        };

        for (long s : durations) {
            Button b = new Button(this);
            b.setText("Save last " + label(s));
            b.setOnClickListener(v -> save(s));
            root.addView(b);
        }

        TextView note = new TextView(this);
        note.setText("\nSaved recordings are written to Downloads/AudioDashcam. Folder and file names include the same timestamp. Each recording start begins a fresh circular-buffer session. Each save is one .wav by default. Very large saves fall back to .raw plus a matching .json sidecar.");
        root.addView(note);

        setContentView(scroll);
        updateRecordingToggle();
    }

    private void requestPermissionsIfNeeded() {
        ArrayList<String> missing = new ArrayList<>();

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO);
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        if (missing.isEmpty()) {
            startRecorder();
            return;
        }

        requestPermissions(missing.toArray(new String[0]), REQ_PERMS);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQ_PERMS) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startRecorder();
            } else {
                updateStatusText("Microphone permission is required.");
            }
        }
    }

    private void startRecorder() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionsIfNeeded();
            return;
        }

        userStopped = false;
        startPending = true;
        Intent intent = new Intent(this, RecorderService.class);
        startForegroundService(intent);
        if (!bindService(intent, conn, Context.BIND_AUTO_CREATE)) {
            startPending = false;
            updateStatusText("Recorder service failed to bind.");
            updateRecordingToggle();
            return;
        }
        updateStatusText("Recording...");
        updateRecordingToggle();
    }

    private void stopRecorder() {
        userStopped = true;
        startPending = false;
        stopService(new Intent(this, RecorderService.class));
        updateStatusText("Stopped. The next start begins a fresh buffer.");
        updateRecordingToggle();
    }

    private void toggleRecorder() {
        if (bound && service != null && service.isRecording()) {
            stopRecorder();
        } else if (!startPending) {
            startRecorder();
        }
    }

    private void save(long seconds) {
        if (!bound || service == null) {
            updateStatusText("Service not bound yet. Press Start first.");
            return;
        }

        updateStatusText("Saving last " + label(seconds) + "...");
        service.saveLastSeconds(seconds, (ok, msg) -> runOnUiThread(() -> updateStatusText((ok ? "Saved: " : "Error: ") + msg)));
    }

    private void updateStatus() {
        if (bound && service != null) updateStatusText(service.status());
        else updateStatusText("Not bound");
    }

    private void updateRecordingToggle() {
        if (recordToggle == null) return;

        if (startPending) {
            recordToggle.setEnabled(false);
            recordToggle.setText("Recording...");
            return;
        }

        if (userStopped) {
            recordToggle.setEnabled(false);
            recordToggle.setText("Stopped.");
            return;
        }

        recordToggle.setEnabled(true);
        if (bound && service != null && service.isRecording()) {
            recordToggle.setText("Stop Recording");
        } else {
            recordToggle.setText("Start Recording");
        }
    }

    private void updateStatusText(String s) {
        if (status != null) status.setText(s);
    }

    private static String label(long seconds) {
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + "m";
        return (seconds / 3600) + "h";
    }

    @Override protected void onDestroy() {
        if (bound) unbindService(conn);
        bound = false;
        super.onDestroy();
    }
}
