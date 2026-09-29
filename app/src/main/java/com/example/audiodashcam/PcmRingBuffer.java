package com.example.audiodashcam;

import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class PcmRingBuffer {
    static final int SAMPLE_RATE = 48_000;
    static final int BYTES_PER_SAMPLE = 4;

    // Fixed byte budget: exactly the old 8h/48k/mono/float32 allocation.
    static final long CAPACITY_BYTES = 8L * 60L * 60L * SAMPLE_RATE * BYTES_PER_SAMPLE;

    private static final long CHECKPOINT_INTERVAL_MS = 2_000;
    private static final int STATE_VERSION = 2;

    private final File ringFile;
    private final File stateFile;
    private final File stateTmpFile;
    private final RandomAccessFile raf;
    private final long capacityBytes;
    private final WaveformIndex waveformIndex;
    private final ArrayList<Segment> segments = new ArrayList<>();

    // Monotonic counters. Physical byte position is absoluteByteCursor % capacityBytes.
    private long absoluteByteCursor = 0;
    private long nextFrameIndex = 0;
    private long lastCheckpointElapsedMs = 0;
    private Segment activeSegment;

    static final class SegmentSnapshot {
        final long startFrame;
        final long endFrameExclusive;
        final long absoluteStartByte;
        final long absoluteEndByte;
        final int sampleRate;
        final int channels;
        final int bytesPerSample;
        final long wallStartMs;
        final long wallEndMs;
        final String profileId;
        final String cutReason;

        SegmentSnapshot(Segment s) {
            startFrame = s.startFrame;
            endFrameExclusive = s.endFrameExclusive;
            absoluteStartByte = s.absoluteStartByte;
            absoluteEndByte = s.absoluteEndByte;
            sampleRate = s.sampleRate;
            channels = s.channels;
            bytesPerSample = s.bytesPerSample;
            wallStartMs = s.wallStartMs;
            wallEndMs = s.wallEndMs;
            profileId = s.profileId;
            cutReason = s.cutReason;
        }

        long frameCount() { return Math.max(0, endFrameExclusive - startFrame); }
        int bytesPerFrame() { return channels * bytesPerSample; }
    }

    static final class AudioChunk {
        final long startFrame;
        final int frameCount;
        final int channels;
        final float[] interleaved;

        AudioChunk(long startFrame, int frameCount, int channels, float[] interleaved) {
            this.startFrame = startFrame;
            this.frameCount = frameCount;
            this.channels = channels;
            this.interleaved = interleaved;
        }
    }

    private static final class Segment {
        long startFrame;
        long endFrameExclusive;
        long absoluteStartByte;
        long absoluteEndByte;
        int sampleRate;
        int channels;
        int bytesPerSample;
        long wallStartMs;
        long wallEndMs;
        String profileId;
        String cutReason;

        int bytesPerFrame() { return channels * bytesPerSample; }
    }

    PcmRingBuffer(File dir) throws IOException {
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not create " + dir);
        ringFile = new File(dir, "audio_ring_float32.pcm");
        stateFile = new File(dir, "audio_ring_state.json");
        stateTmpFile = new File(dir, "audio_ring_state.json.tmp");
        capacityBytes = CAPACITY_BYTES;

        long usable = dir.getUsableSpace();
        if (!ringFile.exists() && usable < capacityBytes + 512L * 1024L * 1024L) {
            throw new IOException("Not enough free storage. Need about " + human(capacityBytes) + " plus margin.");
        }

        raf = new RandomAccessFile(ringFile, "rw");
        if (raf.length() != capacityBytes) raf.setLength(capacityBytes);
        loadState();
        waveformIndex = new WaveformIndex(new File(dir, "waveform"), nextFrameIndex);
    }

    synchronized void beginSegment(AudioProfile profile, long wallStartMs, String cutReason) throws IOException {
        if (activeSegment != null) endActiveSegment(wallStartMs);

        Segment s = new Segment();
        s.startFrame = nextFrameIndex;
        s.endFrameExclusive = nextFrameIndex;
        s.absoluteStartByte = absoluteByteCursor;
        s.absoluteEndByte = absoluteByteCursor;
        s.sampleRate = SAMPLE_RATE;
        s.channels = profile.channels;
        s.bytesPerSample = BYTES_PER_SAMPLE;
        s.wallStartMs = wallStartMs;
        s.wallEndMs = wallStartMs;
        s.profileId = profile.id;
        s.cutReason = cutReason == null ? "" : cutReason;
        segments.add(s);
        activeSegment = s;
        checkpoint(true);
    }

    synchronized void endActiveSegment(long wallEndMs) throws IOException {
        if (activeSegment == null) return;
        activeSegment.wallEndMs = wallEndMs;
        if (activeSegment.endFrameExclusive <= activeSegment.startFrame) {
            segments.remove(activeSegment);
        }
        activeSegment = null;
        checkpoint(true);
    }

    synchronized void appendFloats(float[] samples, int sampleCount, AudioProfile profile, long wallNowMs) throws IOException {
        if (sampleCount <= 0) return;
        int completeSamples = sampleCount - (sampleCount % profile.channels);
        if (completeSamples <= 0) return;
        int frameCount = completeSamples / profile.channels;
        int byteCount = completeSamples * BYTES_PER_SAMPLE;

        if (activeSegment == null || activeSegment.channels != profile.channels || !activeSegment.profileId.equals(profile.id)) {
            beginSegment(profile, wallNowMs, activeSegment == null ? "capture-start" : "format-change");
        }

        long appendStartFrame = nextFrameIndex;
        ByteBuffer bb = ByteBuffer.allocate(byteCount).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < completeSamples; i++) bb.putFloat(samples[i]);
        appendBytes(bb.array(), 0, byteCount, profile.bytesPerFrame());

        activeSegment.endFrameExclusive += frameCount;
        activeSegment.absoluteEndByte += byteCount;
        activeSegment.wallEndMs = wallNowMs;
        nextFrameIndex += frameCount;

        waveformIndex.append(samples, completeSamples, profile.channels, appendStartFrame);
        trimOverwrittenSegments();
        checkpoint(false);
    }

    synchronized long earliestFrame() {
        for (Segment s : segments) {
            if (s.endFrameExclusive > s.startFrame) return s.startFrame;
        }
        return nextFrameIndex;
    }

    synchronized long latestFrameExclusive() {
        return nextFrameIndex;
    }

    synchronized long retainedFrameCount() {
        return Math.max(0, latestFrameExclusive() - earliestFrame());
    }

    synchronized double retainedRecordedSeconds() {
        long frames = 0;
        for (Segment s : segments) frames += Math.max(0, s.endFrameExclusive - s.startFrame);
        return frames / (double) SAMPLE_RATE;
    }

    synchronized long usedBytes() {
        if (segments.isEmpty()) return 0;
        long oldest = segments.get(0).absoluteStartByte;
        return Math.min(capacityBytes, Math.max(0, absoluteByteCursor - oldest));
    }

    synchronized List<SegmentSnapshot> segmentSnapshots() {
        ArrayList<SegmentSnapshot> out = new ArrayList<>();
        for (Segment s : segments) {
            if (s.endFrameExclusive > s.startFrame) out.add(new SegmentSnapshot(s));
        }
        return Collections.unmodifiableList(out);
    }

    synchronized SegmentSnapshot segmentAt(long frame) {
        Segment s = findSegment(frame);
        return s == null ? null : new SegmentSnapshot(s);
    }

    synchronized boolean containsRange(long startFrame, long endFrameExclusive) {
        if (startFrame >= endFrameExclusive) return false;
        long cursor = startFrame;
        for (Segment s : segments) {
            if (s.endFrameExclusive <= cursor) continue;
            if (s.startFrame > cursor) return false;
            cursor = Math.min(endFrameExclusive, s.endFrameExclusive);
            if (cursor >= endFrameExclusive) return true;
        }
        return false;
    }

    synchronized AudioChunk readChunk(long startFrame, int maxFrames) throws IOException {
        if (maxFrames <= 0) return null;
        Segment s = findSegment(startFrame);
        if (s == null) return null;

        int frames = (int) Math.min(maxFrames, s.endFrameExclusive - startFrame);
        int samples = frames * s.channels;
        byte[] bytes = new byte[samples * BYTES_PER_SAMPLE];
        long frameOffset = startFrame - s.startFrame;
        long absoluteByte = s.absoluteStartByte + frameOffset * s.bytesPerFrame();
        readAbsoluteBytes(absoluteByte, bytes, 0, bytes.length);

        float[] out = new float[samples];
        ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < samples; i++) out[i] = bb.getFloat();
        return new AudioChunk(startFrame, frames, s.channels, out);
    }

    /**
     * Returns min/max pairs for a display window. For very wide windows it samples rather
     * than scanning every stored frame, keeping the overview responsive without reading GiB.
     */
    synchronized float[] envelope(long startFrame, long endFrameExclusive, int columns) throws IOException {
        if (columns <= 0) return new float[0];
        startFrame = Math.max(startFrame, earliestFrame());
        endFrameExclusive = Math.min(endFrameExclusive, latestFrameExclusive());
        float[] result = new float[columns * 2];
        if (endFrameExclusive <= startFrame) return result;

        float[] indexed = waveformIndex.envelope(startFrame, endFrameExclusive, columns);
        if (indexed != null) return indexed;

        double framesPerColumn = (endFrameExclusive - startFrame) / (double) columns;
        byte[] frameBytes = new byte[8];
        for (int c = 0; c < columns; c++) {
            long a = startFrame + (long) Math.floor(c * framesPerColumn);
            long b = startFrame + (long) Math.floor((c + 1) * framesPerColumn);
            if (b <= a) b = a + 1;
            b = Math.min(b, endFrameExclusive);

            long span = b - a;
            long stride = Math.max(1, span / 64L);
            float min = 0f;
            float max = 0f;
            boolean seen = false;

            for (long f = a; f < b; f += stride) {
                Segment s = findSegment(f);
                if (s == null) continue;
                int bpf = s.bytesPerFrame();
                if (frameBytes.length < bpf) frameBytes = new byte[bpf];
                long absolute = s.absoluteStartByte + (f - s.startFrame) * bpf;
                readAbsoluteBytes(absolute, frameBytes, 0, bpf);
                ByteBuffer bb = ByteBuffer.wrap(frameBytes, 0, bpf).order(ByteOrder.LITTLE_ENDIAN);
                float mixed = 0f;
                for (int ch = 0; ch < s.channels; ch++) mixed += bb.getFloat();
                mixed /= s.channels;
                if (!seen) {
                    min = max = mixed;
                    seen = true;
                } else {
                    if (mixed < min) min = mixed;
                    if (mixed > max) max = mixed;
                }
            }
            result[c * 2] = min;
            result[c * 2 + 1] = max;
        }
        return result;
    }

    synchronized void copyRangeToWav(java.io.OutputStream out, long startFrame, long endFrameExclusive,
                                     int expectedChannels) throws IOException {
        if (startFrame >= endFrameExclusive) throw new IOException("Empty selection");
        long cursor = startFrame;
        byte[] buf = new byte[1024 * 1024];
        while (cursor < endFrameExclusive) {
            Segment s = findSegment(cursor);
            if (s == null) throw new IOException("Selection contains unavailable audio at frame " + cursor);
            if (s.channels != expectedChannels) throw new IOException("Selection crosses a channel-format change");

            long frames = Math.min(endFrameExclusive, s.endFrameExclusive) - cursor;
            long bytes = frames * s.bytesPerFrame();
            long abs = s.absoluteStartByte + (cursor - s.startFrame) * s.bytesPerFrame();
            long copied = 0;
            while (copied < bytes) {
                int n = (int) Math.min(buf.length, bytes - copied);
                readAbsoluteBytes(abs + copied, buf, 0, n);
                out.write(buf, 0, n);
                copied += n;
            }
            cursor += frames;
        }
    }

    synchronized int channelsForUniformRange(long startFrame, long endFrameExclusive) {
        if (startFrame >= endFrameExclusive) return 0;
        int channels = 0;
        long cursor = startFrame;
        while (cursor < endFrameExclusive) {
            Segment s = findSegment(cursor);
            if (s == null) return 0;
            if (channels == 0) channels = s.channels;
            else if (channels != s.channels) return -1;
            cursor = Math.min(endFrameExclusive, s.endFrameExclusive);
        }
        return channels;
    }

    synchronized long wallClockAtFrame(long frame) {
        Segment s = findSegment(frame);
        if (s == null || s.endFrameExclusive <= s.startFrame) return 0;
        long offsetFrames = frame - s.startFrame;
        return s.wallStartMs + Math.round(offsetFrames * 1000.0 / s.sampleRate);
    }

    synchronized void close() {
        try {
            if (activeSegment != null) endActiveSegment(System.currentTimeMillis());
            else checkpoint(true);
        } catch (IOException ignored) {}
        try { waveformIndex.close(); } catch (Exception ignored) {}
        try { raf.close(); } catch (IOException ignored) {}
    }

    private void appendBytes(byte[] src, int off, int len, int bytesPerFrame) throws IOException {
        if ((len % bytesPerFrame) != 0) throw new IOException("Attempt to write partial audio frame");
        int remaining = len;
        int srcOff = off;
        while (remaining > 0) {
            long physical = absoluteByteCursor % capacityBytes;
            int n = (int) Math.min(remaining, capacityBytes - physical);
            n -= n % bytesPerFrame;
            if (n == 0) {
                // capacity is divisible by both supported frame sizes; this is defensive.
                absoluteByteCursor += capacityBytes - physical;
                continue;
            }
            raf.seek(physical);
            raf.write(src, srcOff, n);
            absoluteByteCursor += n;
            srcOff += n;
            remaining -= n;
        }
    }

    private void readAbsoluteBytes(long absoluteByte, byte[] dst, int off, int len) throws IOException {
        int remaining = len;
        int dstOff = off;
        long absolute = absoluteByte;
        while (remaining > 0) {
            long physical = mod(absolute, capacityBytes);
            int n = (int) Math.min(remaining, capacityBytes - physical);
            raf.seek(physical);
            raf.readFully(dst, dstOff, n);
            absolute += n;
            dstOff += n;
            remaining -= n;
        }
    }

    private Segment findSegment(long frame) {
        // Segment count is tiny in normal use. A binary search can replace this if needed.
        for (int i = segments.size() - 1; i >= 0; i--) {
            Segment s = segments.get(i);
            if (frame >= s.startFrame && frame < s.endFrameExclusive) return s;
            if (frame >= s.endFrameExclusive) break;
        }
        return null;
    }

    private void trimOverwrittenSegments() {
        long oldestAbsolute = Math.max(0, absoluteByteCursor - capacityBytes);
        while (!segments.isEmpty()) {
            Segment s = segments.get(0);
            if (s.absoluteEndByte <= oldestAbsolute) {
                if (s == activeSegment) activeSegment = null;
                segments.remove(0);
                continue;
            }
            if (s.absoluteStartByte < oldestAbsolute) {
                long dropBytes = oldestAbsolute - s.absoluteStartByte;
                int bpf = s.bytesPerFrame();
                long dropFrames = (dropBytes + bpf - 1L) / bpf;
                long alignedBytes = dropFrames * bpf;
                s.absoluteStartByte += alignedBytes;
                s.startFrame += dropFrames;
                if (s.startFrame >= s.endFrameExclusive) {
                    if (s == activeSegment) activeSegment = null;
                    segments.remove(0);
                    continue;
                }
            }
            break;
        }
    }

    private void loadState() throws IOException {
        if (!stateFile.exists()) return;
        try {
            String json = new String(Files.readAllBytes(stateFile.toPath()), StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(json);
            if (root.optInt("version", 0) != STATE_VERSION) return;
            if (root.optLong("capacityBytes", -1) != capacityBytes) return;

            absoluteByteCursor = root.optLong("absoluteByteCursor", 0);
            nextFrameIndex = root.optLong("nextFrameIndex", 0);
            JSONArray arr = root.optJSONArray("segments");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject j = arr.getJSONObject(i);
                    Segment s = new Segment();
                    s.startFrame = j.getLong("startFrame");
                    s.endFrameExclusive = j.getLong("endFrameExclusive");
                    s.absoluteStartByte = j.getLong("absoluteStartByte");
                    s.absoluteEndByte = j.getLong("absoluteEndByte");
                    s.sampleRate = j.optInt("sampleRate", SAMPLE_RATE);
                    s.channels = j.getInt("channels");
                    s.bytesPerSample = j.optInt("bytesPerSample", BYTES_PER_SAMPLE);
                    s.wallStartMs = j.optLong("wallStartMs", 0);
                    s.wallEndMs = j.optLong("wallEndMs", s.wallStartMs);
                    s.profileId = j.optString("profileId", s.channels == 2 ? AudioProfile.STEREO.id : AudioProfile.MONO.id);
                    s.cutReason = j.optString("cutReason", "");
                    if (s.endFrameExclusive > s.startFrame) segments.add(s);
                }
            }
            trimOverwrittenSegments();
            activeSegment = null; // every process/service start is an explicit cut.
        } catch (Exception e) {
            // Preserve the PCM bytes but do not trust malformed indexing metadata.
            absoluteByteCursor = 0;
            nextFrameIndex = 0;
            segments.clear();
            activeSegment = null;
            throw new IOException("Ring metadata is unreadable; PCM backing file was left untouched", e);
        }
    }

    private void checkpoint(boolean force) throws IOException {
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastCheckpointElapsedMs < CHECKPOINT_INTERVAL_MS) return;
        lastCheckpointElapsedMs = now;

        JSONObject root = new JSONObject();
        try {
            root.put("version", STATE_VERSION);
            root.put("capacityBytes", capacityBytes);
            root.put("absoluteByteCursor", absoluteByteCursor);
            root.put("nextFrameIndex", nextFrameIndex);
            JSONArray arr = new JSONArray();
            for (Segment s : segments) {
                if (s.endFrameExclusive <= s.startFrame && s != activeSegment) continue;
                JSONObject j = new JSONObject();
                j.put("startFrame", s.startFrame);
                j.put("endFrameExclusive", s.endFrameExclusive);
                j.put("absoluteStartByte", s.absoluteStartByte);
                j.put("absoluteEndByte", s.absoluteEndByte);
                j.put("sampleRate", s.sampleRate);
                j.put("channels", s.channels);
                j.put("bytesPerSample", s.bytesPerSample);
                j.put("wallStartMs", s.wallStartMs);
                j.put("wallEndMs", s.wallEndMs);
                j.put("profileId", s.profileId);
                j.put("cutReason", s.cutReason);
                arr.put(j);
            }
            root.put("segments", arr);
        } catch (JSONException e) {
            throw new IOException(e);
        }

        try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(stateTmpFile))) {
            out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
        try {
            Files.move(stateTmpFile.toPath(), stateFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(stateTmpFile.toPath(), stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static long mod(long x, long m) {
        long r = x % m;
        return r < 0 ? r + m : r;
    }

    static String human(long bytes) {
        double gib = bytes / Math.pow(1024, 3);
        return String.format(java.util.Locale.US, "%.2f GiB", gib);
    }
}
