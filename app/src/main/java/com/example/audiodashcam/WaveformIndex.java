package com.example.audiodashcam;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * Lossy display-only peak pyramid. The source PCM ring remains untouched/lossless.
 * Each circular entry stores bucket index + min/max of a mono display mix.
 */
final class WaveformIndex implements AutoCloseable {
    private static final int ENTRY_BYTES = 16; // long bucketIndex + float min + float max
    private static final long MAX_MONO_FRAMES = PcmRingBuffer.CAPACITY_BYTES / 4L;
    private static final long[] BUCKET_FRAMES = {
            960L,       // 20 ms
            9_600L,     // 200 ms
            96_000L,    // 2 s
            960_000L    // 20 s
    };

    private final ArrayList<Level> levels = new ArrayList<>();

    private static final class Level implements AutoCloseable {
        final long bucketFrames;
        final long capacityEntries;
        final RandomAccessFile raf;
        long currentBucket = Long.MIN_VALUE;
        float currentMin;
        float currentMax;
        boolean currentSeen;

        Level(File file, long bucketFrames, long nextFrame) throws IOException {
            this.bucketFrames = bucketFrames;
            this.capacityEntries = (MAX_MONO_FRAMES + bucketFrames - 1) / bucketFrames + 2;
            this.raf = new RandomAccessFile(file, "rw");
            long length = capacityEntries * ENTRY_BYTES;
            if (raf.length() != length) raf.setLength(length);

            if (nextFrame > 0 && nextFrame % bucketFrames != 0) {
                long bucket = nextFrame / bucketFrames;
                Entry e = read(bucket);
                if (e != null) {
                    currentBucket = bucket;
                    currentMin = e.min;
                    currentMax = e.max;
                    currentSeen = true;
                }
            }
        }

        void add(float value, long frame) throws IOException {
            long bucket = frame / bucketFrames;
            if (bucket != currentBucket) {
                flushCurrent();
                currentBucket = bucket;
                currentMin = value;
                currentMax = value;
                currentSeen = true;
                return;
            }
            if (!currentSeen) {
                currentMin = currentMax = value;
                currentSeen = true;
            } else {
                if (value < currentMin) currentMin = value;
                if (value > currentMax) currentMax = value;
            }
        }

        void flushCurrent() throws IOException {
            if (!currentSeen || currentBucket == Long.MIN_VALUE) return;
            long slot = mod(currentBucket, capacityEntries);
            raf.seek(slot * ENTRY_BYTES);
            raf.writeLong(currentBucket);
            raf.writeFloat(currentMin);
            raf.writeFloat(currentMax);
        }

        Entry read(long bucket) throws IOException {
            long slot = mod(bucket, capacityEntries);
            raf.seek(slot * ENTRY_BYTES);
            long tag = raf.readLong();
            float min = raf.readFloat();
            float max = raf.readFloat();
            if (tag != bucket) return null;
            return new Entry(min, max);
        }

        @Override public void close() throws IOException {
            flushCurrent();
            raf.close();
        }
    }

    private static final class Entry {
        final float min;
        final float max;
        Entry(float min, float max) { this.min = min; this.max = max; }
    }

    WaveformIndex(File dir, long nextFrame) throws IOException {
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not create waveform index directory");
        for (int i = 0; i < BUCKET_FRAMES.length; i++) {
            levels.add(new Level(new File(dir, "peaks-" + i + ".bin"), BUCKET_FRAMES[i], nextFrame));
        }
    }

    synchronized void append(float[] samples, int sampleCount, int channels, long startFrame) throws IOException {
        int complete = sampleCount - (sampleCount % channels);
        int frames = complete / channels;
        for (int f = 0; f < frames; f++) {
            int base = f * channels;
            float mixed = 0f;
            for (int ch = 0; ch < channels; ch++) mixed += samples[base + ch];
            mixed /= channels;
            long frame = startFrame + f;
            for (Level level : levels) level.add(mixed, frame);
        }
        for (Level level : levels) level.flushCurrent();
    }

    synchronized float[] envelope(long startFrame, long endFrame, int columns) throws IOException {
        if (columns <= 0) return new float[0];
        float[] result = new float[columns * 2];
        if (endFrame <= startFrame) return result;
        double framesPerColumn = (endFrame - startFrame) / (double) columns;
        Level level = chooseLevel(framesPerColumn);
        if (level == null) return null; // caller should use source PCM at close zoom.

        for (int c = 0; c < columns; c++) {
            long a = startFrame + (long) Math.floor(c * framesPerColumn);
            long b = startFrame + (long) Math.floor((c + 1) * framesPerColumn);
            if (b <= a) b = a + 1;
            long first = a / level.bucketFrames;
            long last = (b - 1) / level.bucketFrames;
            float min = 0f;
            float max = 0f;
            boolean seen = false;
            for (long bucket = first; bucket <= last; bucket++) {
                Entry e = level.read(bucket);
                if (e == null) continue;
                if (!seen) {
                    min = e.min;
                    max = e.max;
                    seen = true;
                } else {
                    if (e.min < min) min = e.min;
                    if (e.max > max) max = e.max;
                }
            }
            result[c * 2] = min;
            result[c * 2 + 1] = max;
        }
        return result;
    }

    private Level chooseLevel(double framesPerColumn) {
        Level chosen = null;
        for (Level level : levels) {
            if (level.bucketFrames <= framesPerColumn) chosen = level;
            else break;
        }
        return chosen;
    }

    @Override public synchronized void close() {
        for (Level l : levels) {
            try { l.close(); } catch (IOException ignored) {}
        }
    }

    private static long mod(long x, long m) {
        long r = x % m;
        return r < 0 ? r + m : r;
    }
}
