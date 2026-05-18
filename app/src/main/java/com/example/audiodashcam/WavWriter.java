package com.example.audiodashcam;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;

final class WavWriter implements AutoCloseable {
    private final RandomAccessFile raf;
    private long dataBytes = 0;

    WavWriter(File file, int sampleRate, int channels, int bitsPerSample, boolean ieeeFloat) throws IOException {
        raf = new RandomAccessFile(file, "rw");
        raf.setLength(0);
        writeHeader(sampleRate, channels, bitsPerSample, ieeeFloat, 0);
    }

    void write(byte[] data, int offset, int length) throws IOException {
        raf.write(data, offset, length);
        dataBytes += length;
    }

    @Override public void close() throws IOException {
        long cur = raf.getFilePointer();
        raf.seek(0);
        writeHeader(48000, 1, 32, true, dataBytes);
        raf.seek(cur);
        raf.close();
    }

    static void writeHeader(OutputStream out, int sampleRate, int channels, int bitsPerSample,
                            boolean ieeeFloat, long dataLen) throws IOException {
        if (dataLen > 0xFFFFFFFFL - 44) {
            throw new IOException("Standard WAV cannot exceed ~4 GiB. Export smaller chunks.");
        }

        int audioFormat = ieeeFloat ? 3 : 1; // 3 = IEEE float, 1 = PCM integer
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        long riffSize = 36 + dataLen;

        out.write(new byte[]{ 'R', 'I', 'F', 'F' });
        writeLE32(out, (int) riffSize);
        out.write(new byte[]{ 'W', 'A', 'V', 'E' });
        out.write(new byte[]{ 'f', 'm', 't', ' ' });
        writeLE32(out, 16);
        writeLE16(out, audioFormat);
        writeLE16(out, channels);
        writeLE32(out, sampleRate);
        writeLE32(out, byteRate);
        writeLE16(out, blockAlign);
        writeLE16(out, bitsPerSample);
        out.write(new byte[]{ 'd', 'a', 't', 'a' });
        writeLE32(out, (int) dataLen);
    }

    private void writeHeader(int sampleRate, int channels, int bitsPerSample, boolean ieeeFloat, long dataLen) throws IOException {
        if (dataLen > 0xFFFFFFFFL - 44) {
            throw new IOException("Standard WAV cannot exceed ~4 GiB. Export smaller chunks.");
        }

        int audioFormat = ieeeFloat ? 3 : 1; // 3 = IEEE float, 1 = PCM integer
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        long riffSize = 36 + dataLen;

        raf.writeBytes("RIFF");
        writeLE32((int) riffSize);
        raf.writeBytes("WAVE");
        raf.writeBytes("fmt ");
        writeLE32(16);
        writeLE16(audioFormat);
        writeLE16(channels);
        writeLE32(sampleRate);
        writeLE32(byteRate);
        writeLE16(blockAlign);
        writeLE16(bitsPerSample);
        raf.writeBytes("data");
        writeLE32((int) dataLen);
    }

    private static void writeLE16(OutputStream out, int v) throws IOException {
        out.write(v & 0xff);
        out.write((v >>> 8) & 0xff);
    }

    private static void writeLE32(OutputStream out, int v) throws IOException {
        out.write(v & 0xff);
        out.write((v >>> 8) & 0xff);
        out.write((v >>> 16) & 0xff);
        out.write((v >>> 24) & 0xff);
    }

    private void writeLE16(int v) throws IOException {
        raf.write(v & 0xff);
        raf.write((v >>> 8) & 0xff);
    }

    private void writeLE32(int v) throws IOException {
        raf.write(v & 0xff);
        raf.write((v >>> 8) & 0xff);
        raf.write((v >>> 16) & 0xff);
        raf.write((v >>> 24) & 0xff);
    }
}
