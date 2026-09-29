package com.example.audiodashcam;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_PERMS = 7;

    private RecorderService service;
    private boolean bound;
    private TimelineView timeline;
    private TextView status;
    private TextView selectionInfo;
    private Spinner profileSpinner;
    private Button playButton;
    private boolean updatingSpinner;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (bound && service != null) {
                status.setText(service.status());
                timeline.syncFromService(false);
                if (service.isPlaying()) {
                    timeline.setPlayhead(service.playbackFrame());
                    updateSelectionInfo();
                }
                playButton.setText(service.isPlaying() ? "Pause" : "Play");
            }
            status.postDelayed(this, 500);
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((RecorderService.LocalBinder) binder).service();
            bound = true;
            timeline.setService(service);
            populateProfiles();
            status.setText(service.status());
            BootReceiver.clearReminder(MainActivity.this);
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            bound = false;
            service = null;
            status.setText("Recorder disconnected");
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        requestPermissionsAndStart();
    }

    @Override protected void onResume() {
        super.onResume();
        status.post(ticker);
    }

    @Override protected void onPause() {
        status.removeCallbacks(ticker);
        super.onPause();
    }

    @Override protected void onDestroy() {
        status.removeCallbacks(ticker);
        if (bound) unbindService(connection);
        bound = false;
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setPadding(dp(8), dp(8), dp(8), dp(8));
        root.setBackgroundColor(0xff0b0b0b);

        LinearLayout left = sidebar();
        TextView title = text("Audio Dashcam", 20, Color.WHITE);
        left.addView(title);
        left.addView(spacer(8));

        status = text("Starting…", 12, 0xffcccccc);
        left.addView(status);
        left.addView(spacer(14));

        TextView micLabel = text("Capture", 12, 0xffaaaaaa);
        left.addView(micLabel);
        profileSpinner = new Spinner(this);
        left.addView(profileSpinner, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        TextView hint = text("Swipe the app away from Recents to stop recording. Opening the app resumes at the next logical audio frame.", 11, 0xff888888);
        hint.setPadding(0, dp(12), 0, 0);
        left.addView(hint);

        root.addView(left, new LinearLayout.LayoutParams(dp(190), ViewGroup.LayoutParams.MATCH_PARENT));

        timeline = new TimelineView(this);
        timeline.setListener(this::updateSelectionInfo);
        LinearLayout.LayoutParams timelineLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        timelineLp.setMargins(dp(8), 0, dp(8), 0);
        root.addView(timeline, timelineLp);

        LinearLayout right = sidebar();
        selectionInfo = text("No selection\nTap waveform to seek.", 13, Color.WHITE);
        right.addView(selectionInfo);
        right.addView(spacer(10));

        playButton = button("Play", () -> {
            if (!bound || service == null) return;
            if (service.isPlaying()) service.stopPlayback();
            else service.startPlayback(timeline.playhead());
            playButton.setText(service.isPlaying() ? "Pause" : "Play");
        });
        right.addView(playButton);

        right.addView(button("Set start", () -> timeline.setSelectionStartAtPlayhead()));
        right.addView(button("Set end", () -> timeline.setSelectionEndAtPlayhead()));
        right.addView(button("End = LIVE", () -> timeline.setSelectionEndLive()));
        right.addView(button("Go LIVE", timeline::jumpLive));

        Button save = button("Save selection", () -> {
            if (!bound || service == null) return;
            long start = timeline.selectionStart();
            long end = timeline.selectionEndResolved();
            if (start < 0 || end <= start) {
                selectionInfo.setText("Set a selection start first.\nEnd defaults to LIVE.");
                return;
            }
            selectionInfo.setText("Saving…");
            service.saveRange(start, end, (ok, message) -> runOnUiThread(() -> {
                selectionInfo.setText((ok ? "Saved\n" : "Save failed\n") + message);
            }));
        });
        right.addView(save);

        TextView gestures = text("Timeline\n• tap: seek\n• drag: pan\n• pinch: zoom\n• double tap: live\n\nOrange lines are cuts. Elapsed real time is metadata only; cuts consume no audio-axis width.", 11, 0xff999999);
        gestures.setPadding(0, dp(12), 0, 0);
        right.addView(gestures);

        root.addView(right, new LinearLayout.LayoutParams(dp(190), ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    private void requestPermissionsAndStart() {
        ArrayList<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO);
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQ_PERMS);
        } else {
            startAndBindRecorder();
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMS) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startAndBindRecorder();
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                status.setText("Recording can run, but reboot reminders are disabled because notification permission was denied.");
            }
        } else {
            status.setText("Microphone permission is required.");
        }
    }

    private void startAndBindRecorder() {
        if (bound) return;
        Intent intent = new Intent(this, RecorderService.class);
        startForegroundService(intent);
        if (!bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            status.setText("Could not bind recorder service");
        }
    }

    private void populateProfiles() {
        if (service == null) return;
        List<AudioProfile> profiles = service.supportedProfiles();
        ArrayAdapter<AudioProfile> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                profiles
        );
        updatingSpinner = true;
        profileSpinner.setAdapter(adapter);
        int selected = Math.max(0, profiles.indexOf(service.selectedProfile()));
        profileSpinner.setSelection(selected, false);
        updatingSpinner = false;
        profileSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int position, long id) {
                if (updatingSpinner || service == null) return;
                AudioProfile p = (AudioProfile) parent.getItemAtPosition(position);
                service.setSelectedProfile(p);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
    }

    private void updateSelectionInfo() {
        if (timeline == null) return;
        long start = timeline.selectionStart();
        long end = timeline.selectionEndResolved();
        if (start < 0 || end <= start) {
            selectionInfo.setText("No selection\nPlayhead: " + frameTime(timeline.playhead()));
            return;
        }
        long frames = end - start;
        selectionInfo.setText(
                "Selection " + frameDuration(frames) + "\n" +
                frameTime(start) + " → " + (timeline.selectionEndsLive() ? "LIVE" : frameTime(end)) + "\n" +
                "Playhead " + frameTime(timeline.playhead())
        );
    }

    private LinearLayout sidebar() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.TOP);
        l.setPadding(dp(8), dp(8), dp(8), dp(8));
        l.setBackgroundColor(0xff151515);
        return l;
    }

    private Button button(String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private TextView text(String value, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private TextView spacer(int dp) {
        TextView v = new TextView(this);
        v.setHeight(dp(dp));
        return v;
    }

    private int dp(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private static String frameTime(long frame) {
        long ms = Math.max(0, Math.round(frame * 1000.0 / AudioProfile.SAMPLE_RATE));
        long h = ms / 3_600_000;
        long m = (ms / 60_000) % 60;
        long s = (ms / 1000) % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    private static String frameDuration(long frames) {
        double sec = frames / (double) AudioProfile.SAMPLE_RATE;
        if (sec >= 3600) return String.format(Locale.US, "%.2fh", sec / 3600.0);
        if (sec >= 60) return String.format(Locale.US, "%.2fm", sec / 60.0);
        return String.format(Locale.US, "%.2fs", sec);
    }
}
