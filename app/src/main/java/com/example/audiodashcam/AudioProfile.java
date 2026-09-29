package com.example.audiodashcam;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class AudioProfile {
    static final int SAMPLE_RATE = 48_000;

    static final AudioProfile MONO = new AudioProfile(
            "mic_mono", "Standard mono", MediaRecorder.AudioSource.MIC, 1);
    static final AudioProfile STEREO = new AudioProfile(
            "mic_stereo", "Standard stereo", MediaRecorder.AudioSource.MIC, 2);
    static final AudioProfile RAW_STEREO = new AudioProfile(
            "raw_stereo", "Unprocessed stereo", MediaRecorder.AudioSource.UNPROCESSED, 2);
    static final AudioProfile RAW_MONO = new AudioProfile(
            "raw_mono", "Unprocessed mono", MediaRecorder.AudioSource.UNPROCESSED, 1);

    final String id;
    final String label;
    final int audioSource;
    final int channels;

    AudioProfile(String id, String label, int audioSource, int channels) {
        this.id = id;
        this.label = label;
        this.audioSource = audioSource;
        this.channels = channels;
    }

    int channelMask() {
        return channels == 2 ? AudioFormat.CHANNEL_IN_STEREO : AudioFormat.CHANNEL_IN_MONO;
    }

    int bytesPerFrame() {
        return channels * 4;
    }

    static AudioProfile fromId(String id) {
        if (STEREO.id.equals(id)) return STEREO;
        if (RAW_STEREO.id.equals(id)) return RAW_STEREO;
        if (RAW_MONO.id.equals(id)) return RAW_MONO;
        return MONO;
    }

    static List<AudioProfile> discoverSupported() {
        ArrayList<AudioProfile> result = new ArrayList<>();
        AudioProfile[] preferred = { MONO, STEREO, RAW_STEREO, RAW_MONO };
        for (AudioProfile p : preferred) {
            if (canInitialize(p)) result.add(p);
        }
        if (result.isEmpty()) result.add(MONO);
        return Collections.unmodifiableList(result);
    }

    private static boolean canInitialize(AudioProfile p) {
        int min = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                p.channelMask(),
                AudioFormat.ENCODING_PCM_FLOAT
        );
        if (min <= 0) return false;

        AudioRecord r = null;
        try {
            r = new AudioRecord.Builder()
                    .setAudioSource(p.audioSource)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(p.channelMask())
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .build())
                    .setBufferSizeInBytes(Math.max(min * 2, p.bytesPerFrame() * 4096))
                    .build();
            return r.getState() == AudioRecord.STATE_INITIALIZED;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (r != null) r.release();
        }
    }

    @Override public String toString() {
        return label;
    }
}
