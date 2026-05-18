package com.example.audiodashcam;

import java.io.File;
import java.io.IOException;
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

        // Split into <= 1h WAVs. This avoids the RIFF/WAV 4 GiB limit and keeps files easy to handle.
        long remainingSeconds = seconds;
        long absoluteEndByte = totalBytesWritten;
        long absoluteStartByte = totalBytesWritten - seconds * SAMPLE_RATE * BYTES_PER_FRAME;

        int part = 1;
        byte[] copyBuffer = new byte[1024 * 1024];

        while (remainingSeconds > 0) {
            long partSeconds = Math.min(remainingSeconds, 3600);
            long partBytes = partSeconds * SAMPLE_RATE * BYTES_PER_FRAME;
            File wav = new File(outDir, String.format(Locale.US, "part_%02d_%ds_float32_mono_48k.wav", part, partSeconds));

            try (WavWriter writer = new WavWriter(wav, SAMPLE_RATE, CHANNELS, 32, true)) {
                copyLogicalRange(absoluteStartByte, partBytes, copyBuffer, writer);
            }

            absoluteStartByte += partBytes;
            remainingSeconds -= partSeconds;
            part++;
        }

        File manifest = new File(outDir, "manifest.txt");
        try (RandomAccessFile m = new RandomAccessFile(manifest, "rw")) {
            m.setLength(0);
            m.write(("sample_rate=48000\nchannels=1\nformat=float32_le\nseconds=" + seconds + "\n").getBytes());
        }

        return outDir;
    }

    private void copyLogicalRange(long absoluteStartByte, long bytes, byte[] buf, WavWriter writer) throws IOException {
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
