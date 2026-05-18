package com.example.audiodashcam;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
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

    interface ExportSession extends AutoCloseable {
        OutputStream openFile(String fileName, String mimeType) throws IOException;
        void commit() throws IOException;
        String locationDescription();
        @Override void close() throws IOException;
    }

    interface ExportSessionFactory {
        ExportSession open(String sessionName) throws IOException;
    }

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

    synchronized String exportLastSeconds(ExportSessionFactory sessionFactory, long requestedSeconds) throws IOException {
        long available = availableSeconds();
        long seconds = Math.min(requestedSeconds, available);
        if (seconds <= 0) throw new IOException("No audio available yet.");

        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss_SSS", Locale.US).format(new Date());
        String sessionName = stamp + "_last-" + seconds + "s";
        String fileStem = stamp + "_audio_float32le_mono_48000";
        long dataBytes = seconds * SAMPLE_RATE * BYTES_PER_FRAME;
        long absoluteStartByte = totalBytesWritten - dataBytes;
        byte[] copyBuffer = new byte[1024 * 1024];

        try (ExportSession session = sessionFactory.open(sessionName)) {
            String audioFileName;
            String container;

            if (dataBytes <= MAX_STANDARD_WAV_DATA_BYTES) {
                // Default path: one WAV file. This stores the same float32 PCM data with only a small header.
                audioFileName = fileStem + ".wav";
                container = "wav";
                try (OutputStream out = session.openFile(audioFileName, "audio/wav")) {
                    WavWriter.writeHeader(out, SAMPLE_RATE, CHANNELS, 32, true, dataBytes);
                    copyLogicalRangeToStream(absoluteStartByte, dataBytes, copyBuffer, out);
                }
            } else {
                // Fallback path: one raw file, because standard WAV cannot represent this much data.
                audioFileName = fileStem + ".raw";
                container = "raw";
                try (OutputStream out = session.openFile(audioFileName, "application/octet-stream")) {
                    copyLogicalRangeToStream(absoluteStartByte, dataBytes, copyBuffer, out);
                }

                String json = "{\n" +
                        "  \"container\": \"" + container + "\",\n" +
                        "  \"audioFile\": \"" + audioFileName + "\",\n" +
                        "  \"sampleRate\": 48000,\n" +
                        "  \"channels\": 1,\n" +
                        "  \"sampleFormat\": \"float32\",\n" +
                        "  \"endianness\": \"little\",\n" +
                        "  \"bytesPerSample\": 4,\n" +
                        "  \"bytesPerFrame\": 4,\n" +
                        "  \"durationSeconds\": " + seconds + ",\n" +
                        "  \"dataBytes\": " + dataBytes + ",\n" +
                        "  \"note\": \"This sidecar JSON is only written for raw exports because raw PCM is not self-describing.\"\n" +
                        "}\n";

                try (OutputStream manifest = session.openFile(fileStem + ".json", "application/json")) {
                    manifest.write(json.getBytes(StandardCharsets.UTF_8));
                }
            }

            session.commit();
            return session.locationDescription();
        }
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
