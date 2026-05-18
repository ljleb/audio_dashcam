package com.example.audiodashcam;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class PcmRingBuffer {
    static final int SAMPLE_RATE = 48_000;
    static final int CHANNELS = 1;
    static final int BYTES_PER_SAMPLE = 4; // float32
    static final int BYTES_PER_FRAME = CHANNELS * BYTES_PER_SAMPLE;
    static final long DEFAULT_CAPACITY_SECONDS = 8L * 60L * 60L;
    static final long DEFAULT_CAPACITY_BYTES = DEFAULT_CAPACITY_SECONDS * SAMPLE_RATE * BYTES_PER_FRAME;

    // Standard RIFF/WAV has 32-bit size fields. Stay comfortably below 4 GiB.
    private static final long MAX_STANDARD_WAV_DATA_BYTES = 0xFFFFFFFFL - 128L;

    private final File ringFile;
    private final RandomAccessFile raf;
    private final long capacityBytes;

    private long totalBytesWritten = 0;
    private long writePos = 0;

    PcmRingBuffer(File dir, long capacitySeconds) throws IOException {
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not create " + dir);
        this.ringFile = new File(dir, "audio_ring_float32_mono_48k.pcm");
        this.capacityBytes = capacitySeconds * SAMPLE_RATE * BYTES_PER_FRAME;

        long usable = dir.getUsableSpace();
        if (usable < capacityBytes + 512L * 1024L * 1024L) {
            throw new IOException("Not enough free storage. Need about " + human(capacityBytes) + " plus margin.");
        }

        this.raf = new RandomAccessFile(ringFile, "rw");
        if (raf.length() != capacityBytes) raf.setLength(capacityBytes);
    }

    synchronized void appendFloats(float[] samples, int count) throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) bb.putFloat(samples[i]);
        appendBytes(bb.array(), 0, count * 4);
    }

    private void appendBytes(byte[] src, int off, int len) throws IOException {
        int remaining = len;
        int srcOff = off;
        while (remaining > 0) {
            int n = (int) Math.min(remaining, capacityBytes - writePos);
            raf.seek(writePos);
            raf.write(src, srcOff, n);
            writePos = (writePos + n) % capacityBytes;
            totalBytesWritten += n;
            srcOff += n;
            remaining -= n;
        }
    }

    synchronized long availableSeconds() {
        long availableBytes = Math.min(totalBytesWritten, capacityBytes);
        return availableBytes / (SAMPLE_RATE * BYTES_PER_FRAME);
    }

    synchronized File exportLastSeconds(File outRoot, long requestedSeconds) throws IOException {
        long available = availableSeconds();
        long seconds = Math.min(requestedSeconds, available);
        if (seconds <= 0) throw new IOException("No audio available yet.");

        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
        File outDir = new File(outRoot, stamp + "_last-" + seconds + "s");
        if (!outDir.exists() && !outDir.mkdirs()) throw new IOException("Could not create " + outDir);

        long dataBytes = seconds * SAMPLE_RATE * BYTES_PER_FRAME;
        long absoluteStartByte = totalBytesWritten - dataBytes;

        byte[] copyBuffer = new byte[1024 * 1024];

        // Canonical export: one contiguous raw file, never split.
        File raw = new File(outDir, "audio_float32le_mono_48000.raw");
        try (OutputStream out = new FileOutputStream(raw)) {
            copyLogicalRangeToStream(absoluteStartByte, dataBytes, copyBuffer, out);
        }

        // Convenience WAV only when standard WAV can represent it as one file.
        boolean wroteWav = false;
        if (dataBytes <= MAX_STANDARD_WAV_DATA_BYTES) {
            File wav = new File(outDir, "audio_float32le_mono_48000.wav");
            try (WavWriter writer = new WavWriter(wav, SAMPLE_RATE, CHANNELS, 32, true)) {
                copyLogicalRangeToWav(absoluteStartByte, dataBytes, copyBuffer, writer);
            }
            wroteWav = true;
        }

        File manifest = new File(outDir, "manifest.json");
        String json = "{\n" +
                "  \"container\": \"raw\",\n" +
                "  \"canonicalFile\": \"audio_float32le_mono_48000.raw\",\n" +
                "  \"sampleRate\": 48000,\n" +
                "  \"channels\": 1,\n" +
                "  \"sampleFormat\": \"float32\",\n" +
                "  \"endianness\": \"little\",\n" +
                "  \"bytesPerSample\": 4,\n" +
                "  \"bytesPerFrame\": 4,\n" +
                "  \"durationSeconds\": " + seconds + ",\n" +
                "  \"dataBytes\": " + dataBytes + ",\n" +
                "  \"standardWavAlsoWritten\": " + wroteWav + ",\n" +
                "  \"note\": \"The .raw file is one contiguous unsplit export copied directly from the circular PCM buffer. Import as raw PCM float32 little-endian mono 48000 Hz.\"\n" +
                "}\n";

        try (OutputStream m = new FileOutputStream(manifest)) {
            m.write(json.getBytes("UTF-8"));
        }

        return outDir;
    }

    private void copyLogicalRangeToStream(long absoluteStartByte, long bytes, byte[] buf, OutputStream out) throws IOException {
        long copied = 0;
        while (copied < bytes) {
            long logical = absoluteStartByte + copied;
            long pos = mod(logical, capacityBytes);
            int n = (int) Math.min(Math.min(buf.length, bytes - copied), capacityBytes - pos);
            raf.seek(pos);
            raf.readFully(buf, 0, n);
            out.write(buf, 0, n);
            copied += n;
        }
    }

    private void copyLogicalRangeToWav(long absoluteStartByte, long bytes, byte[] buf, WavWriter writer) throws IOException {
        long copied = 0;
        while (copied < bytes) {
            long logical = absoluteStartByte + copied;
            long pos = mod(logical, capacityBytes);
            int n = (int) Math.min(Math.min(buf.length, bytes - copied), capacityBytes - pos);
            raf.seek(pos);
            raf.readFully(buf, 0, n);
            writer.write(buf, 0, n);
            copied += n;
        }
    }

    synchronized void close() {
        try { raf.close(); } catch (IOException ignored) {}
    }

    private static long mod(long x, long m) {
        long r = x % m;
        return r < 0 ? r + m : r;
    }

    static String human(long bytes) {
        double gib = bytes / Math.pow(1024, 3);
        return String.format(Locale.US, "%.2f GiB", gib);
    }
}
