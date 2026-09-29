package com.example.audiodashcam;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

final class ClipRepository {
    private final Context context;
    private final File indexFile;
    private final File tmpFile;
    private final ArrayList<Clip> clips = new ArrayList<>();

    private static final class Clip {
        long startFrame;
        long endFrameExclusive;
        int channels;
        String uri;
        String fileName;
    }

    ClipRepository(Context context) {
        this.context = context.getApplicationContext();
        indexFile = new File(context.getFilesDir(), "saved_clips.json");
        tmpFile = new File(context.getFilesDir(), "saved_clips.json.tmp");
        load();
    }

    synchronized String saveRange(PcmRingBuffer ring, long requestedStart, long requestedEnd) throws IOException {
        long start = Math.max(requestedStart, ring.earliestFrame());
        long end = Math.min(requestedEnd, ring.latestFrameExclusive());
        if (start >= end) throw new IOException("No retained audio in selection");

        int channels = ring.channelsForUniformRange(start, end);
        if (channels == -1) {
            throw new IOException("Selection crosses a mono/stereo format boundary. Split the selection at the cut to preserve samples exactly.");
        }
        if (channels <= 0) throw new IOException("Selection contains unavailable audio");

        ArrayList<Clip> overlaps = new ArrayList<>();
        boolean expanded;
        do {
            expanded = false;
            for (Clip c : clips) {
                if (c.channels != channels || overlaps.contains(c)) continue;
                if (c.endFrameExclusive < start || c.startFrame > end) continue;
                overlaps.add(c);
                long ns = Math.min(start, c.startFrame);
                long ne = Math.max(end, c.endFrameExclusive);
                if (ns != start || ne != end) expanded = true;
                start = ns;
                end = ne;
            }
        } while (expanded);

        // Overlap replacement is intentionally regenerated from the lossless ring. If the
        // oldest overlapping samples have already been evicted, keep the old file rather
        // than deleting information that can no longer be reconstructed.
        if (!ring.containsRange(start, end)) {
            throw new IOException("Overlap reaches audio already evicted from the ring; existing saved clip was left unchanged.");
        }

        long frames = end - start;
        long dataBytes = frames * channels * 4L;
        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss_SSS", Locale.US).format(new Date());
        String ext = dataBytes <= 0xFFFFFFFFL - 44 ? ".wav" : ".rf64.wav";
        String fileName = stamp + "_frames-" + start + "-" + end + "_float32_" +
                (channels == 2 ? "stereo" : "mono") + "_48000" + ext;

        FilePublisher.PendingFile pending = FilePublisher.createAudio(context, fileName, "audio/wav");
        try (FilePublisher.PendingFile pf = pending) {
            try (OutputStream out = pf.open()) {
                WavWriter.writeLosslessHeader(out, PcmRingBuffer.SAMPLE_RATE, channels, dataBytes);
                ring.copyRangeToWav(out, start, end, channels);
            }
            pf.commit();
        }

        Clip replacement = new Clip();
        replacement.startFrame = start;
        replacement.endFrameExclusive = end;
        replacement.channels = channels;
        replacement.uri = pending.uri.toString();
        replacement.fileName = fileName;

        clips.removeAll(overlaps);
        clips.add(replacement);
        saveIndex();

        // Delete superseded files only after replacement publication and index commit.
        for (Clip old : overlaps) FilePublisher.delete(context, old.uri);

        return pending.location + (overlaps.isEmpty() ? "" : " (merged " + overlaps.size() + " overlapping saved clip(s))");
    }

    private void load() {
        if (!indexFile.exists()) return;
        try {
            JSONObject root = new JSONObject(new String(Files.readAllBytes(indexFile.toPath()), StandardCharsets.UTF_8));
            JSONArray arr = root.optJSONArray("clips");
            if (arr == null) return;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject j = arr.getJSONObject(i);
                Clip c = new Clip();
                c.startFrame = j.getLong("startFrame");
                c.endFrameExclusive = j.getLong("endFrameExclusive");
                c.channels = j.getInt("channels");
                c.uri = j.getString("uri");
                c.fileName = j.optString("fileName", "");
                clips.add(c);
            }
        } catch (Exception ignored) {
            clips.clear();
        }
    }

    private void saveIndex() throws IOException {
        try {
            JSONObject root = new JSONObject();
            JSONArray arr = new JSONArray();
            for (Clip c : clips) {
                JSONObject j = new JSONObject();
                j.put("startFrame", c.startFrame);
                j.put("endFrameExclusive", c.endFrameExclusive);
                j.put("channels", c.channels);
                j.put("uri", c.uri);
                j.put("fileName", c.fileName);
                arr.put(j);
            }
            root.put("clips", arr);
            Files.write(tmpFile.toPath(), root.toString().getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmpFile.toPath(), indexFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmpFile.toPath(), indexFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            throw new IOException("Could not persist saved clip index", e);
        }
    }
}
