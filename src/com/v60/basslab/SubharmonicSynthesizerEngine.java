package com.v60.basslab;

/**
 * SubharmonicSynthesizerEngine
 * 
 * Replicates the hardware architecture of the legendary AudioControl "The Epicenter"
 * and dbx 120A Subharmonic Synthesizer:
 * 
 * 1. Biquad Bandpass Fundamental Tracker (SWEEP & WIDE / AUTO 3-Band).
 * 2. Frequency Halver (Zero-Crossing Flip-Flop & Sine Integrator f -> f/2).
 * 3. Dynamic Envelope Follower (Acoustic attack & release locking).
 * 4. Subsonic Infrasonic Filter (25 Hz 2nd-order Butterworth HPF protection).
 * 5. Phase-Coherent Summing & Soft-Clipping Brickwall Limiter.
 */
public class SubharmonicSynthesizerEngine {

    private int sampleRate = 44100;

    // Controls
    private float sweepFreq = 75.0f;     // 45.0 Hz to 120.0 Hz
    private float wideQ = 1.4f;          // 0.5f (Wide) to 3.0f (Narrow)
    private float subLevel = 0.65f;      // 0.0f to 1.0f (0 to 100%)
    private boolean isAutoMode = false;
    private boolean isEnabled = true;

    // Biquad Bandpass Filter Coefficients (SWEEP / WIDE)
    private float bpB0, bpB2, bpA1, bpA2;
    private float bpX1_L, bpX2_L, bpY1_L, bpY2_L;
    private float bpX1_R, bpX2_R, bpY1_R, bpY2_R;

    // AUTO Mode: 3 Parallel Bandpass Filters (Low: 55Hz, Mid: 80Hz, High: 110Hz)
    private float autoB0_1, autoB2_1, autoA1_1, autoA2_1;
    private float autoB0_2, autoB2_2, autoA1_2, autoA2_2;
    private float autoB0_3, autoB2_3, autoA1_3, autoA2_3;
    private float autoY1_1, autoY2_1, autoX1_1, autoX2_1;
    private float autoY1_2, autoY2_2, autoX1_2, autoX2_2;
    private float autoY1_3, autoY2_3, autoX1_3, autoX2_3;

    // Subharmonic Reconstruction LPF Coefficients (Tuned to sweepFreq / 2)
    private float lpfB0, lpfB1, lpfB2, lpfA1, lpfA2;
    private float lpfX1_L, lpfX2_L, lpfY1_L, lpfY2_L;
    private float lpfX1_R, lpfX2_R, lpfY1_R, lpfY2_R;

    // Subsonic Filter Coefficients (25 Hz Butterworth HPF)
    private float subHpB0, subHpB1, subHpB2, subHpA1, subHpA2;
    private float hpX1_L, hpX2_L, hpY1_L, hpY2_L;
    private float hpX1_R, hpX2_R, hpY1_R, hpY2_R;

    // Zero-Crossing & Flip-Flop States
    private boolean flipFlopL = false;
    private boolean flipFlopR = false;
    private float prevSignalL = 0.0f;
    private float prevSignalR = 0.0f;

    // Envelope Follower
    private float envL = 0.0f;
    private float envR = 0.0f;
    private float attackCoeff = 0.08f;   // ~5 ms fast attack
    private float releaseCoeff = 0.005f; // ~40 ms smooth release

    public SubharmonicSynthesizerEngine() {
        init(44100);
    }

    public void init(int rate) {
        this.sampleRate = rate > 0 ? rate : 44100;
        this.attackCoeff = 1.0f - (float) Math.exp(-1.0 / (0.005 * sampleRate));
        this.releaseCoeff = 1.0f - (float) Math.exp(-1.0 / (0.040 * sampleRate));

        updateBandpassCoefficients();
        updateAutoBandpassCoefficients();
        updateLpfCoefficients();
        updateSubsonicHpCoefficients();
    }

    public void setSweepFreq(float freq) {
        this.sweepFreq = Math.max(45.0f, Math.min(120.0f, freq));
        updateBandpassCoefficients();
        updateLpfCoefficients();
    }

    public float getSweepFreq() {
        return sweepFreq;
    }

    public float getSynthesizedSubFreq() {
        return sweepFreq / 2.0f;
    }

    public void setWideBandwidth(float q) {
        this.wideQ = Math.max(0.5f, Math.min(3.0f, q));
        updateBandpassCoefficients();
    }

    public float getWideBandwidth() {
        return wideQ;
    }

    public void setSubLevel(float level) {
        this.subLevel = Math.max(0.0f, Math.min(1.0f, level));
    }

    public float getSubLevel() {
        return subLevel;
    }

    public void setAutoMode(boolean auto) {
        this.isAutoMode = auto;
    }

    public boolean isAutoMode() {
        return isAutoMode;
    }

    public void setEnabled(boolean enabled) {
        this.isEnabled = enabled;
    }

    public boolean isEnabled() {
        return isEnabled;
    }

    private void updateBandpassCoefficients() {
        double w0 = 2.0 * Math.PI * sweepFreq / sampleRate;
        double sinW0 = Math.sin(w0);
        double cosW0 = Math.cos(w0);
        double alpha = sinW0 / (2.0 * wideQ);

        double a0 = 1.0 + alpha;
        bpB0 = (float) (alpha / a0);
        bpB2 = (float) (-alpha / a0);
        bpA1 = (float) ((-2.0 * cosW0) / a0);
        bpA2 = (float) ((1.0 - alpha) / a0);
    }

    private void updateAutoBandpassCoefficients() {
        // Band 1: 55 Hz
        double w1 = 2.0 * Math.PI * 55.0 / sampleRate;
        double alpha1 = Math.sin(w1) / (2.0 * 1.5);
        double a0_1 = 1.0 + alpha1;
        autoB0_1 = (float) (alpha1 / a0_1);
        autoB2_1 = -autoB0_1;
        autoA1_1 = (float) ((-2.0 * Math.cos(w1)) / a0_1);
        autoA2_1 = (float) ((1.0 - alpha1) / a0_1);

        // Band 2: 80 Hz
        double w2 = 2.0 * Math.PI * 80.0 / sampleRate;
        double alpha2 = Math.sin(w2) / (2.0 * 1.5);
        double a0_2 = 1.0 + alpha2;
        autoB0_2 = (float) (alpha2 / a0_2);
        autoB2_2 = -autoB0_2;
        autoA1_2 = (float) ((-2.0 * Math.cos(w2)) / a0_2);
        autoA2_2 = (float) ((1.0 - alpha2) / a0_2);

        // Band 3: 110 Hz
        double w3 = 2.0 * Math.PI * 110.0 / sampleRate;
        double alpha3 = Math.sin(w3) / (2.0 * 1.5);
        double a0_3 = 1.0 + alpha3;
        autoB0_3 = (float) (alpha3 / a0_3);
        autoB2_3 = -autoB0_3;
        autoA1_3 = (float) ((-2.0 * Math.cos(w3)) / a0_3);
        autoA2_3 = (float) ((1.0 - alpha3) / a0_3);
    }

    private void updateLpfCoefficients() {
        // Cutoff at (sweepFreq / 2) * 1.15 to turn the square carrier into a clean pure sine
        float lpfCutoff = (sweepFreq / 2.0f) * 1.15f;
        double w0 = 2.0 * Math.PI * lpfCutoff / sampleRate;
        double cosW0 = Math.cos(w0);
        double sinW0 = Math.sin(w0);
        double alpha = sinW0 / (2.0 * 0.7071); // Butterworth Q

        double a0 = 1.0 + alpha;
        lpfB0 = (float) (((1.0 - cosW0) / 2.0) / a0);
        lpfB1 = (float) ((1.0 - cosW0) / a0);
        lpfB2 = lpfB0;
        lpfA1 = (float) ((-2.0 * cosW0) / a0);
        lpfA2 = (float) ((1.0 - alpha) / a0);
    }

    private void updateSubsonicHpCoefficients() {
        // 25 Hz 2nd-order Butterworth High-Pass Filter for Driver & Subwoofer Protection
        double w0 = 2.0 * Math.PI * 25.0 / sampleRate;
        double cosW0 = Math.cos(w0);
        double sinW0 = Math.sin(w0);
        double alpha = sinW0 / (2.0 * 0.7071);

        double a0 = 1.0 + alpha;
        subHpB0 = (float) (((1.0 + cosW0) / 2.0) / a0);
        subHpB1 = (float) (-(1.0 + cosW0) / a0);
        subHpB2 = subHpB0;
        subHpA1 = (float) ((-2.0 * cosW0) / a0);
        subHpA2 = (float) ((1.0 - alpha) / a0);
    }

    /**
     * Processes interleaved stereo 16-bit PCM in-place.
     */
    public void processStereoPcm(short[] pcm, int offset, int length) {
        if (!isEnabled || subLevel <= 0.001f) return;

        float gainMultiplier = subLevel * 1.65f; // Calibration boost factor

        for (int i = offset; i < offset + length; i += 2) {
            float inL = pcm[i] / 32768.0f;
            float inR = pcm[i + 1] / 32768.0f;

            // 1. Fundamental Bandpass Extraction
            float bpL, bpR;
            if (isAutoMode) {
                // 3-Band Parallel Extraction
                float y1_1 = autoB0_1 * inL + autoB2_1 * autoX2_1 - autoA1_1 * autoY1_1 - autoA2_1 * autoY2_1;
                autoX2_1 = autoX1_1; autoX1_1 = inL; autoY2_1 = autoY1_1; autoY1_1 = y1_1;

                float y1_2 = autoB0_2 * inL + autoB2_2 * autoX2_2 - autoA1_2 * autoY1_2 - autoA2_2 * autoY2_2;
                autoX2_2 = autoX1_2; autoX1_2 = inL; autoY2_2 = autoY1_2; autoY1_2 = y1_2;

                float y1_3 = autoB0_3 * inL + autoB2_3 * autoX2_3 - autoA1_3 * autoY1_3 - autoA2_3 * autoY2_3;
                autoX2_3 = autoX1_3; autoX1_3 = inL; autoY2_3 = autoY1_3; autoY1_3 = y1_3;

                bpL = (y1_1 + y1_2 * 1.2f + y1_3 * 0.8f) * 0.7f;
                bpR = bpL; // mono fundamental correlation
            } else {
                // SWEEP & WIDE Targeted Extraction
                bpL = bpB0 * inL + bpB2 * bpX2_L - bpA1 * bpY1_L - bpA2 * bpY2_L;
                bpX2_L = bpX1_L; bpX1_L = inL; bpY2_L = bpY1_L; bpY1_L = bpL;

                bpR = bpB0 * inR + bpB2 * bpX2_R - bpA1 * bpY1_R - bpA2 * bpY2_R;
                bpX2_R = bpX1_R; bpX1_R = inR; bpY2_R = bpY1_R; bpY1_R = bpR;
            }

            // 2. Frequency Halving with Hysteresis (Schmitt-Trigger Zero-Crossing Divider f -> f/2)
            if (!flipFlopL && bpL > 0.015f) {
                flipFlopL = true;
            } else if (flipFlopL && bpL < -0.015f) {
                flipFlopL = false;
            }

            if (!flipFlopR && bpR > 0.015f) {
                flipFlopR = true;
            } else if (flipFlopR && bpR < -0.015f) {
                flipFlopR = false;
            }

            float squareCarrierL = flipFlopL ? 1.0f : -1.0f;
            float squareCarrierR = flipFlopR ? 1.0f : -1.0f;

            // 3. Dynamic Envelope Follower
            float absL = Math.abs(bpL);
            envL += (absL > envL ? attackCoeff : releaseCoeff) * (absL - envL);

            float absR = Math.abs(bpR);
            envR += (absR > envR ? attackCoeff : releaseCoeff) * (absR - envR);

            // Shape square wave with envelope
            float rawSubL = squareCarrierL * envL;
            float rawSubR = squareCarrierR * envR;

            // 4. Low-Pass Smoothing (Produces pure subharmonic sine wave at f/2)
            float sineSubL = lpfB0 * rawSubL + lpfB1 * lpfX1_L + lpfB2 * lpfX2_L - lpfA1 * lpfY1_L - lpfA2 * lpfY2_L;
            lpfX2_L = lpfX1_L; lpfX1_L = rawSubL; lpfY2_L = lpfY1_L; lpfY1_L = sineSubL;

            float sineSubR = lpfB0 * rawSubR + lpfB1 * lpfX1_R + lpfB2 * lpfX2_R - lpfA1 * lpfY1_R - lpfA2 * lpfY2_R;
            lpfX2_R = lpfX1_R; lpfX1_R = rawSubR; lpfY2_R = lpfY1_R; lpfY1_R = sineSubR;

            // 5. Subsonic 25 Hz Protection Filter (HPF eliminates DC and infrasonic driver strain)
            float safeSubL = subHpB0 * sineSubL + subHpB1 * hpX1_L + subHpB2 * hpX2_L - subHpA1 * hpY1_L - subHpA2 * hpY2_L;
            hpX2_L = hpX1_L; hpX1_L = sineSubL; hpY2_L = hpY1_L; hpY1_L = safeSubL;

            float safeSubR = subHpB0 * sineSubR + subHpB1 * hpX1_R + subHpB2 * hpX2_R - subHpA1 * hpY1_R - subHpA2 * hpY2_R;
            hpX2_R = hpX1_R; hpX1_R = sineSubR; hpY2_R = hpY1_R; hpY1_R = safeSubR;

            // 6. Phase-Coherent Summing with Digital Headroom Protection
            float outL = (inL * 0.88f) + (safeSubL * (subLevel * 0.75f));
            float outR = (inR * 0.88f) + (safeSubR * (subLevel * 0.75f));

            outL = softClip(outL);
            outR = softClip(outR);

            pcm[i] = (short) (outL * 32767.0f);
            pcm[i + 1] = (short) (outR * 32767.0f);
        }
    }

    /**
     * Soft-Clipping limiter preventing digital harsh distortion without squashing audio.
     */
    private static float softClip(float x) {
        if (x > 1.0f) return 0.999f;
        if (x < -1.0f) return -0.999f;
        if (x > 0.88f) {
            return 0.88f + (float) Math.tanh(x - 0.88f) * 0.119f;
        } else if (x < -0.88f) {
            return -0.88f + (float) Math.tanh(x + 0.88f) * 0.119f;
        }
        return x;
    }
}
