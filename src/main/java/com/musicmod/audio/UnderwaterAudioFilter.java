package com.musicmod.audio;

/**
 * 播放线程内的水下音效 DSP：低通 + 轻微短混响。
 * 只处理 16bit 小端 PCM；不支持时自动旁路。
 */
final class UnderwaterAudioFilter {
    private static final float CUTOFF_HZ = 600.0F;
    private static final float Q = 0.707F;
    private static final float RAMP_SECONDS = 0.03F;

    private boolean enabled;
    private int channels;
    private float sampleRate;
    private volatile boolean target;
    private volatile boolean caveTarget;
    private float current;
    private float caveCurrent;

    private final Biquad[][] filters = new Biquad[2][2];
    private float[] delayLeft = new float[0];
    private float[] delayRight = new float[0];
    private int delayPosLeft;
    private int delayPosRight;
    private float[] caveDelayLeft = new float[0];
    private float[] caveDelayRight = new float[0];
    private int cavePosLeft;
    private int cavePosRight;

    UnderwaterAudioFilter() {
        for (int c = 0; c < filters.length; c++) {
            filters[c][0] = new Biquad();
            filters[c][1] = new Biquad();
        }
    }

    synchronized void configure(float sampleRate, int channels, int sampleSizeBits, boolean bigEndian) {
        this.enabled = sampleSizeBits == 16 && channels >= 1 && channels <= 2 && !bigEndian;
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.current = 0.0F;
        this.caveCurrent = 0.0F;
        for (Biquad[] pair : filters) {
            for (Biquad b : pair) b.reset();
        }
        for (Biquad[] pair : filters) {
            for (Biquad b : pair) b.setLowPass(sampleRate, CUTOFF_HZ, Q);
        }
        int left = Math.max(1, (int) (sampleRate * 0.075F));
        int right = Math.max(1, (int) (sampleRate * 0.105F));
        delayLeft = new float[left];
        delayRight = new float[right];
        delayPosLeft = 0;
        delayPosRight = 0;
        int caveL = Math.max(1, (int) (sampleRate * 0.28F));
        int caveR = Math.max(1, (int) (sampleRate * 0.39F));
        caveDelayLeft = new float[caveL];
        caveDelayRight = new float[caveR];
        cavePosLeft = 0;
        cavePosRight = 0;
    }

    void setUnderwater(boolean underwater) {
        this.target = underwater;
    }

    void setCaveEcho(boolean cave) {
        this.caveTarget = cave;
    }

    synchronized void reset() {
        current = 0.0F;
        caveCurrent = 0.0F;
        for (Biquad[] pair : filters) {
            for (Biquad b : pair) b.reset();
        }
        java.util.Arrays.fill(delayLeft, 0.0F);
        java.util.Arrays.fill(delayRight, 0.0F);
        java.util.Arrays.fill(caveDelayLeft, 0.0F);
        java.util.Arrays.fill(caveDelayRight, 0.0F);
        delayPosLeft = 0;
        delayPosRight = 0;
        cavePosLeft = 0;
        cavePosRight = 0;
    }

    synchronized void process(byte[] pcm, int offset, int length) {
        if (!enabled || channels <= 0) return;
        int frameSize = channels * 2;
        int end = offset + (length - length % frameSize);
        if (end <= offset) return;
        if (!target && !caveTarget && current < 0.0005F && caveCurrent < 0.0005F) {
            current = 0.0F;
            caveCurrent = 0.0F;
            return;
        }

        final float ramp = 1.0F / Math.max(1.0F, sampleRate * RAMP_SECONDS);
        final float caveRamp = 1.0F / Math.max(1.0F, sampleRate * 0.35F);
        for (int i = offset; i < end; i += frameSize) {
            current += (target ? 1.0F - current : -current) * ramp;
            if (current < 0.0F) current = 0.0F;
            if (current > 1.0F) current = 1.0F;
            caveCurrent += (caveTarget ? 1.0F - caveCurrent : -caveCurrent) * caveRamp;
            if (caveCurrent < 0.0F) caveCurrent = 0.0F;
            if (caveCurrent > 1.0F) caveCurrent = 1.0F;
            float wet = current;

            for (int ch = 0; ch < channels; ch++) {
                int idx = i + ch * 2;
                int lo = pcm[idx] & 0xFF;
                int hi = pcm[idx + 1];
                float dry = (short) ((hi << 8) | lo) / 32768.0F;
                float filtered = filters[ch][0].process(filters[ch][1].process(dry));
                float echoed = filtered;
                if (ch == 0) {
                    int pos = delayPosLeft;
                    float delayed = delayLeft[pos];
                    delayLeft[pos] = filtered + delayed * 0.32F;
                    if (++delayPosLeft >= delayLeft.length) delayPosLeft = 0;
                    echoed = filtered * 0.72F + delayed * 0.28F;
                } else {
                    int pos = delayPosRight;
                    float delayed = delayRight[pos];
                    delayRight[pos] = filtered + delayed * 0.34F;
                    if (++delayPosRight >= delayRight.length) delayPosRight = 0;
                    echoed = filtered * 0.72F + delayed * 0.28F;
                }
                float out = dry + (echoed - dry) * wet;
                if (caveCurrent > 0.0005F) {
                    float caveOut;
                    if (ch == 0) {
                        int pos = cavePosLeft;
                        float delayed = caveDelayLeft[pos];
                        caveDelayLeft[pos] = out + delayed * 0.52F;
                        if (++cavePosLeft >= caveDelayLeft.length) cavePosLeft = 0;
                        caveOut = out * 0.56F + delayed * 0.44F;
                    } else {
                        int pos = cavePosRight;
                        float delayed = caveDelayRight[pos];
                        caveDelayRight[pos] = out + delayed * 0.50F;
                        if (++cavePosRight >= caveDelayRight.length) cavePosRight = 0;
                        caveOut = out * 0.56F + delayed * 0.44F;
                    }
                    out = out + (caveOut - out) * caveCurrent;
                }
                int s = (int) (out * 32767.0F);
                if (s > 32767) s = 32767;
                if (s < -32768) s = -32768;
                pcm[idx] = (byte) (s & 0xFF);
                pcm[idx + 1] = (byte) ((s >> 8) & 0xFF);
            }
        }
    }

    private static final class Biquad {
        private float b0, b1, b2, a1, a2;
        private float z1, z2;

        void setLowPass(float fs, float cutoff, float q) {
            double w0 = 2.0 * Math.PI * cutoff / fs;
            double cos = Math.cos(w0);
            double alpha = Math.sin(w0) / (2.0 * q);
            double a0 = 1.0 + alpha;
            b0 = (float) ((1.0 - cos) * 0.5 / a0);
            b1 = (float) ((1.0 - cos) / a0);
            b2 = b0;
            a1 = (float) (-2.0 * cos / a0);
            a2 = (float) ((1.0 - alpha) / a0);
            z1 = 0.0F;
            z2 = 0.0F;
        }

        float process(float x) {
            float y = b0 * x + z1;
            z1 = b1 * x - a1 * y + z2;
            z2 = b2 * x - a2 * y;
            return y;
        }

        void reset() {
            z1 = 0.0F;
            z2 = 0.0F;
        }
    }
}
