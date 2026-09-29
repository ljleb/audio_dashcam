package com.example.audiodashcam;

import java.io.IOException;
import java.io.OutputStream;

final class WavWriter {
    private WavWriter() {}

    static void writeHeader(OutputStream out, int sampleRate, int channels, int bitsPerSample,
                            boolean ieeeFloat, long dataLen) throws IOException {
        if (dataLen > 0xFFFFFFFFL - 44) {
            throw new IOException("Standard WAV cannot exceed ~4 GiB of payload");
        }

        int audioFormat = ieeeFloat ? 3 : 1;
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

    static long headerSizeFor(long dataLen) { return dataLen <= 0xFFFFFFFFL - 44 ? 44L : 80L; }

    static void writeLosslessHeader(OutputStream out, int sampleRate, int channels, long dataLen) throws IOException {
        if (dataLen <= 0xFFFFFFFFL - 44) {
            writeHeader(out, sampleRate, channels, 32, true, dataLen);
            return;
        }
        long sampleFrames = dataLen / (channels * 4L);
        long riffSize = dataLen + 72L;
        out.write(new byte[]{ 'R', 'F', '6', '4' });
        writeLE32(out, 0xFFFFFFFF);
        out.write(new byte[]{ 'W', 'A', 'V', 'E' });
        out.write(new byte[]{ 'd', 's', '6', '4' });
        writeLE32(out, 28);
        writeLE64(out, riffSize);
        writeLE64(out, dataLen);
        writeLE64(out, sampleFrames);
        writeLE32(out, 0);
        out.write(new byte[]{ 'f', 'm', 't', ' ' });
        writeLE32(out, 16);
        writeLE16(out, 3);
        writeLE16(out, channels);
        writeLE32(out, sampleRate);
        writeLE32(out, sampleRate * channels * 4);
        writeLE16(out, channels * 4);
        writeLE16(out, 32);
        out.write(new byte[]{ 'd', 'a', 't', 'a' });
        writeLE32(out, 0xFFFFFFFF);
    }

    private static void writeLE64(OutputStream out, long v) throws IOException {
        for (int i = 0; i < 8; i++) out.write((int) ((v >>> (8 * i)) & 0xff));
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
}
