package com.example.audiodashcam;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import android.app.Activity;

public class MainActivity extends Activity {
    private static final int REQ_PERMS = 7;
    private RecorderService service;
    private boolean bound = false;
    private TextView status;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((RecorderService.LocalBinder) binder).service();
            bound = true;
            updateStatus();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            bound = false;
            service = null;
            updateStatus();
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
        subtitle.setText("48 kHz · mono · float32 PCM · 8h circular buffer");
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        subtitle.setPadding(0, 12, 0, 24);
        root.addView(subtitle);

        Button start = new Button(this);
        start.setText("Start / Resume Recording");
        start.setOnClickListener(v -> startRecorder());
        root.addView(start);

        Button stop = new Button(this);
        stop.setText("Stop Recording");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, RecorderService.class));
            updateStatusText("Stopped");
        });
        root.addView(stop);

        status = new TextView(this);
        status.setText("Idle");
        status.setPadding(0, 24, 0, 24);
        root.addView(status);

        long[] durations = {
                5, 15, 30, 60,
                5 * 60, 15 * 60, 30 * 60,
                60 * 60, 5 * 60 * 60, 8 * 60 * 60
        };

        for (long s : durations) {
            Button b = new Button(this);
            b.setText("Save last " + label(s));
            b.setOnClickListener(v -> save(s));
            root.addView(b);
        }

        TextView note = new TextView(this);
        note.setText("\nSaved recordings are written to Downloads/AudioDashcam. Each save contains one unsplit .raw file plus manifest.json. A .wav convenience copy is created only when it fits standard WAV limits.");
        root.addView(note);

        setContentView(scroll);
    }

    private void requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS
            }, REQ_PERMS);
        } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{ Manifest.permission.RECORD_AUDIO }, REQ_PERMS);
        } else {
            startRecorder();
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecorder();
        } else {
            updateStatusText("Microphone permission is required.");
        }
    }

    private void startRecorder() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionsIfNeeded();
            return;
        }

        Intent intent = new Intent(this, RecorderService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
        bindService(intent, conn, Context.BIND_AUTO_CREATE);
        updateStatusText("Starting...");
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
