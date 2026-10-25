package com.v60.basslab;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.audiofx.BassBoost;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.Visualizer;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

public class AudioDspService extends Service {

    private static final String TAG = "V60_AudioDspService";
    private static final String CHANNEL_ID = "v60_bass_lab_channel";
    private static final int NOTIFICATION_ID = 6001;

    private final IBinder binder = new LocalBinder();

    private DynamicsProcessing dynamicsProcessing;
    private BassBoost bassBoost;
    private Visualizer visualizer;
    private final AiAudioAnalyzer aiAudioAnalyzer = new AiAudioAnalyzer();

    private boolean isMasterEnabled = true;
    private boolean isLimiterEnabled = true;

    // 5-Band Hardware-Calibrated Gains (dB):
    // Band 0: 65 Hz  (Sub-Bass / 32-63Hz TDA7468D / 17cm SAW Super Woofer)
    // Band 1: 200 Hz (Punch / 90-180Hz Upper-Bass / 20cm HOP Woofer)
    // Band 2: 600 Hz (250Hz Low-Mid Warmth & Body)
    // Band 3: 4000 Hz (1kHz-4kHz Vocal & Instrument Presence)
    // Band 4: 20000 Hz (10kHz-16kHz Super Tweeter & Brilliance)
    private float currentSubBassDb = 8.5f;
    private float currentPunchDb = 6.5f;
    private float currentClarityDb = 0.0f;
    private float currentMidPresenceDb = 1.5f;
    private float currentAirDb = 2.5f;
    private int currentBassBoost = 450;
    private int masterPercent = 60;

    // MBC Post-Gain for Bass ALC (TDA7468D) & DBFB (Sony M62427FP)
    private float currentMbcSubPostGainDb = 1.5f;
    private float currentMbcPunchPostGainDb = 1.0f;
    private float currentPreampGainDb = 0.0f; // Pre-Amplifier / Headroom Gain (-6.0 dB to +3.0 dB)

    // Hardware IPC Delta-Thresholding (Deadband) to eliminate redundant Binder IPC calls
    private static final float EQ_DEADBAND_DB = 0.06f;
    private static final int BOOST_DEADBAND_UNITS = 5;
    private float lastAppliedPreampDb = Float.NaN;
    private float lastAppliedSubDb = Float.NaN;
    private float lastAppliedPunchDb = Float.NaN;
    private float lastAppliedClarityDb = Float.NaN;
    private float lastAppliedMidDb = Float.NaN;
    private float lastAppliedAirDb = Float.NaN;
    private float lastAppliedMbcSubDb = Float.NaN;
    private float lastAppliedMbcPunchDb = Float.NaN;
    private int lastAppliedBassBoost = -1;

    // Global Sub-Harmonic Resonator (Disabled & hidden for later: 0.0f)
    private float subSynthSweepHz = 65.0f;
    private float subSynthWideQ = 1.2f;
    private float subSynthLevel = 0.0f; // Disabled (0.0f)
    private boolean subSynthAutoMode = false;

    // Philips FW-M576 (TDA7468D) & Sony LBT-XB66 (M62427FP) Hardware States
    private boolean isMaxSoundActive = false;
    private boolean isSonyXb66Active = false;
    private int currentDbbLevel = 0;     // 0=OFF, 1=DBB 1 (Deep), 2=DBB 2 (Punch), 3=DBB 3 (Max WOOX)
    private int currentGameportMode = 0; // 0=OFF, 1=SPEED, 2=PUNCH, 3=BLAST
    private int currentDscPreset = 0;    // 0=OPTIMAL, 1=TECHNO, 2=ROCK, 3=POP, 4=JAZZ
    private int activeAcousticProfile = -1; // -1=OFF (Disabled by default), 0..4=Studio Acoustic Profile

    public interface VisualizerCallback {
        void onFftDataCapture(byte[] fft);
    }
    private VisualizerCallback visualizerCallback;

    private static AudioDspService instance;

    public static AudioDspService getInstance() {
        return instance;
    }

    private volatile float currentAudioDb = -20.0f;
    private volatile float currentLeftDb = -20.0f;
    private volatile float currentRightDb = -20.0f;

    public float getCurrentAudioDb() { return currentAudioDb; }
    public float getCurrentLeftDb() { return currentLeftDb; }
    public float getCurrentRightDb() { return currentRightDb; }

    public void updateStereoLevels(float leftDb, float rightDb) {
        this.currentLeftDb = leftDb;
        this.currentRightDb = rightDb;
        this.currentAudioDb = Math.max(leftDb, rightDb);
        if (tileLevelListener != null) {
            int level;
            if (currentAudioDb < -14.0f) level = 0;
            else if (currentAudioDb < -7.0f) level = 1;
            else if (currentAudioDb < -2.0f) level = 2;
            else if (currentAudioDb < 0.5f) level = 3;
            else level = 4;
            tileLevelListener.onAudioLevelChanged(currentAudioDb, level);
        }
    }

    public interface AudioLevelListener {
        void onAudioLevelChanged(float db, int quantizedLevel);
    }
    private AudioLevelListener tileLevelListener;

    public void setTileLevelListener(AudioLevelListener listener) {
        this.tileLevelListener = listener;
    }

    public class LocalBinder extends Binder {
        public AudioDspService getService() {
            return AudioDspService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        initAudioEffects();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public void setVisualizerCallback(VisualizerCallback callback) {
        this.visualizerCallback = callback;
    }

    private void initAudioEffects() {
        try {
            int audioSession = 0;

            // Connect Live Dynamic EQ directly to AudioDspService for zero-latency background execution
            aiAudioAnalyzer.setDirectDspListener((subDb, punchDb, clarityDb, midPresenceDb, airDb, hwBoost, mbcAlcDb, preampDb) -> {
                if (isMasterEnabled) {
                    setDynamicEqGains(subDb, punchDb, clarityDb, midPresenceDb, airDb, hwBoost, mbcAlcDb, preampDb);
                }
            });

            // 1. FULL 4-STAGE DYNAMICS PROCESSING ENGINE:
            // Pre-EQ (5 bands) -> MBC (5-band TDA7468D Bass ALC / Sony DBFB) -> Post-EQ (Sub-Synth Resonator) -> Safety Limiter
            DynamicsProcessing.Config.Builder builder = new DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2,       // 2 Stereo Channels
                true, 5, // Pre-EQ: 5 Hardware Bands (65Hz, 200Hz, 600Hz, 4kHz, 20kHz)
                true, 5, // MBC: 5-Band Multi-Band Compressor (Bass ALC on Bands 0-1, Uncompressed Mids/Highs on Bands 2-4!)
                true, 5, // Post-EQ: 5-Band Sub-Harmonic Resonator & Final Contour
                true     // Final Brickwall Safety Limiter
            );

            // 0.0 dB Input Gain: Zero global volume loss! Vocals and instruments keep 100% original loudness.
            builder.setInputGainAllChannelsTo(0.0f);

            // Stage 1: Pre-EQ (5 Acoustic Bands matching Sony M62427FP & Philips TDA7468D)
            DynamicsProcessing.Eq preEq = new DynamicsProcessing.Eq(true, true, 5);
            preEq.setBand(0, new DynamicsProcessing.EqBand(true, 65.0f, currentSubBassDb));
            preEq.setBand(1, new DynamicsProcessing.EqBand(true, 200.0f, currentPunchDb));
            preEq.setBand(2, new DynamicsProcessing.EqBand(true, 600.0f, currentClarityDb));
            preEq.setBand(3, new DynamicsProcessing.EqBand(true, 4000.0f, currentMidPresenceDb));
            preEq.setBand(4, new DynamicsProcessing.EqBand(true, 20000.0f, currentAirDb));
            builder.setPreEqAllChannelsTo(preEq);

            // Stage 2: MBC (TDA7468D Bass ALC & Sony M62427FP DBFB Dynamic Bass Feedback)
            // Compresses ONLY extreme sub-bass/woofer peaks so vocals/mids (Bands 2-4) NEVER duck or pump!
            DynamicsProcessing.Mbc mbc = new DynamicsProcessing.Mbc(true, true, 5);
            // Band 0 (0 - 65 Hz Sub-Bass ALC): 12ms attack, 120ms release, 4.0:1 ratio, -1.5dB threshold
            mbc.setBand(0, new DynamicsProcessing.MbcBand(
                true, 65.0f, 12.0f, 120.0f, 4.0f, -1.5f, 6.0f, -90.0f, 1.0f, 0.0f, currentMbcSubPostGainDb
            ));
            // Band 1 (65 - 200 Hz Woofer Punch ALC): 15ms attack, 100ms release, 3.2:1 ratio, -1.2dB threshold
            mbc.setBand(1, new DynamicsProcessing.MbcBand(
                true, 200.0f, 15.0f, 100.0f, 3.2f, -1.2f, 5.0f, -90.0f, 1.0f, 0.0f, currentMbcPunchPostGainDb
            ));
            // Band 2 (200 - 600 Hz Low-Mids): ratio 1.0:1 (100% uncompressed warm body)
            mbc.setBand(2, new DynamicsProcessing.MbcBand(
                true, 600.0f, 10.0f, 80.0f, 1.0f, -0.5f, 2.0f, -90.0f, 1.0f, 0.0f, 0.0f
            ));
            // Band 3 (600 - 4000 Hz Vocals & Instruments): ratio 1.0:1 (100% uncompressed vocals!)
            mbc.setBand(3, new DynamicsProcessing.MbcBand(
                true, 4000.0f, 10.0f, 80.0f, 1.0f, -0.5f, 2.0f, -90.0f, 1.0f, 0.0f, 0.0f
            ));
            // Band 4 (4000 - 20000 Hz Treble & Super Tweeter): ratio 1.2:1 (gentle high-frequency protection)
            mbc.setBand(4, new DynamicsProcessing.MbcBand(
                true, 20000.0f, 8.0f, 60.0f, 1.2f, -0.8f, 3.0f, -90.0f, 1.0f, 0.0f, 0.0f
            ));
            builder.setMbcAllChannelsTo(mbc);

            // Stage 3: Post-EQ (Global Sub-Harmonic Resonator for YouTube Music / Spotify / Session 0)
            DynamicsProcessing.Eq postEq = new DynamicsProcessing.Eq(true, true, 5);
            float subResonanceDb = subSynthLevel * 4.5f;   // Up to +4.5 dB extra sub-octave weight
            float punchCouplingDb = subSynthLevel * 2.2f;  // Upper-bass harmonic coupling
            postEq.setBand(0, new DynamicsProcessing.EqBand(true, 60.0f, subResonanceDb));
            postEq.setBand(1, new DynamicsProcessing.EqBand(true, 160.0f, punchCouplingDb));
            postEq.setBand(2, new DynamicsProcessing.EqBand(true, 600.0f, 0.0f));
            postEq.setBand(3, new DynamicsProcessing.EqBand(true, 4000.0f, 0.0f));
            postEq.setBand(4, new DynamicsProcessing.EqBand(true, 20000.0f, 0.0f));
            builder.setPostEqAllChannelsTo(postEq);

            // Stage 4: Final Ceiling Safety Limiter (-0.2 dB ceiling so normal music never pumps)
            DynamicsProcessing.Limiter limiter = new DynamicsProcessing.Limiter(
                true, isLimiterEnabled, 0,
                3.0f,   // attackTime (ms)
                70.0f,  // releaseTime (ms)
                8.0f,   // ratio (true ceiling protection at -0.2 dB)
                -0.2f,  // threshold (dB)
                0.0f    // postGain (dB)
            );
            builder.setLimiterAllChannelsTo(limiter);

            try {
                dynamicsProcessing = new DynamicsProcessing(1000, audioSession, builder.build());
                dynamicsProcessing.setEnabled(isMasterEnabled);
                AppLogger.i(TAG, "DynamicsProcessing 4-Etapas activo: PreEQ + MBC (TDA7468D Bass ALC) + PostEQ (Sub-Synth) + Limiter.");
            } catch (Exception dpe) {
                AppLogger.e(TAG, "Error inicializando DynamicsProcessing: " + dpe.getMessage(), dpe);
            }

            // 2. HARDWARE BASSBOOST (Qualcomm Aqstic Sub-Harmonic Exciter)
            try {
                bassBoost = new BassBoost(1000, audioSession);
                if (bassBoost.getStrengthSupported()) {
                    bassBoost.setStrength((short) currentBassBoost);
                }
                bassBoost.setEnabled(isMasterEnabled);
                AppLogger.i(TAG, "BassBoost por hardware activo (fuerza: " + currentBassBoost + "/1000).");
            } catch (Exception bbe) {
                AppLogger.w(TAG, "BassBoost por hardware no disponible en session 0: " + bbe.getMessage());
                bassBoost = null;
            }

            // 3. VISUALIZER
            try {
                visualizer = new Visualizer(audioSession);
                visualizer.setCaptureSize(Visualizer.getCaptureSizeRange()[1]);
                visualizer.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                    @Override
                    public void onWaveFormDataCapture(Visualizer v, byte[] waveform, int samplingRate) {}

                    @Override
                    public void onFftDataCapture(Visualizer v, byte[] fft, int samplingRate) {
                        if (visualizerCallback != null && isMasterEnabled) {
                            visualizerCallback.onFftDataCapture(fft);
                        }
                        if (isMasterEnabled && fft != null) {
                            aiAudioAnalyzer.processFft(fft);

                            float bassEnergy = 0.0f;
                            int bassBins = Math.min(fft.length / 2, 7);
                            for (int i = 1; i < bassBins; i++) {
                                float r = fft[2 * i];
                                float im = fft[2 * i + 1];
                                bassEnergy += (float) Math.sqrt(r * r + im * im);
                            }

                            float trebleEnergy = 0.0f;
                            int maxBin = Math.min(fft.length / 2, 42);
                            for (int i = 8; i < maxBin; i += 2) {
                                float r = fft[2 * i];
                                float im = fft[2 * i + 1];
                                trebleEnergy += (float) Math.sqrt(r * r + im * im);
                            }

                            float normBass = Math.min(1.35f, bassEnergy / 185.0f);
                            float normTreble = Math.min(1.35f, trebleEnergy / 210.0f);

                            currentLeftDb = (normBass < 0.02f) ? -20.0f : Math.max(-20.0f, Math.min(3.0f, -20.0f + normBass * 20.0f));
                            currentRightDb = (normTreble < 0.02f) ? -20.0f : Math.max(-20.0f, Math.min(3.0f, -20.0f + normTreble * 20.0f));
                            currentAudioDb = Math.max(currentLeftDb, currentRightDb);

                            QsVuTelemetryBridge.sendFromService(AudioDspService.this);

                            if (tileLevelListener != null) {
                                int level;
                                if (currentAudioDb < -14.0f) level = 0;
                                else if (currentAudioDb < -7.0f) level = 1;
                                else if (currentAudioDb < -2.0f) level = 2;
                                else if (currentAudioDb < 0.5f) level = 3;
                                else level = 4;

                                tileLevelListener.onAudioLevelChanged(currentAudioDb, level);
                            }
                        }
                    }
                }, Visualizer.getMaxCaptureRate(), false, true);
                visualizer.setEnabled(isMasterEnabled);
                AppLogger.i(TAG, "Visualizador FFT en tiempo real y detector IA inicializados.");
            } catch (Exception ve) {
                AppLogger.w(TAG, "Visualizer: " + ve.getMessage());
            }

        } catch (Exception e) {
            AppLogger.e(TAG, "Error general configurando Audio Effects: " + e.getMessage(), e);
        }
    }

    public void setMasterEnabled(boolean enabled) {
        this.isMasterEnabled = enabled;
        try {
            if (dynamicsProcessing != null) dynamicsProcessing.setEnabled(enabled);
            if (bassBoost != null) bassBoost.setEnabled(enabled);
            if (visualizer != null) visualizer.setEnabled(enabled);
        } catch (Exception e) {
            AppLogger.e(TAG, "Error setting master enabled: " + e.getMessage(), e);
        }
        QsVuTelemetryBridge.sendFromService(this);
    }

    public boolean isMasterEnabled() {
        return isMasterEnabled;
    }

    public void setMasterBassPercent(int percent) {
        this.masterPercent = percent;
        float factor = percent / 100.0f;

        // Powerful, warm full-bodied curve with zero low-mid hole
        this.currentSubBassDb = factor * 13.5f;
        this.currentPunchDb = factor * 9.5f;
        this.currentClarityDb = factor * 0.5f; // Warm body, never hollow
        this.currentMidPresenceDb = 1.5f;
        this.currentAirDb = 1.5f + (factor * 2.5f);
        this.currentMbcSubPostGainDb = factor * 2.0f;
        this.currentMbcPunchPostGainDb = factor * 1.2f;
        this.currentBassBoost = Math.min(1000, (int) (factor * 750));

        applyPreEqGains();
        applyMbcBassAlc();
        setBassBoost(this.currentBassBoost);
    }

    public int getMasterPercent() {
        return masterPercent;
    }

    public int getActiveAcousticProfile() {
        return activeAcousticProfile;
    }

    public void setActiveAcousticProfile(int index) {
        this.activeAcousticProfile = index;
        if (index >= 0) {
            this.isMaxSoundActive = false;
            this.isSonyXb66Active = false;
        }
    }

    public void deactivateAcousticProfile() {
        this.activeAcousticProfile = -1;
        recalculateHardwareMatrix();
        AppLogger.i(TAG, "Perfiles Acústicos de Estudio desactivados. Restaurando matriz de hardware base.");
    }

    public void setCustomCalibration(float sub, float punch, float clarity, int boost) {
        setCustomCalibration(sub, punch, clarity, 2.5f, boost);
    }

    public void setCustomCalibration(float sub, float punch, float clarity, float air, int boost) {
        float mid = 1.5f;
        float mbcAlc = Math.max(0.0f, (sub / 14.0f) * 2.0f);
        int hwBoost = Math.min(1000, Math.max(boost * 4, (int) (sub * 45.0f)));
        setFullStudioCalibration(sub, punch, clarity, mid, air, hwBoost, mbcAlc, 0.0f);
    }

    /**
     * Full 8-Parameter Studio Acoustic & Parametric Calibration:
     * Applies all 5 frequency bands (65Hz, 200Hz, 600Hz, 4kHz, 20kHz),
     * Hardware BassBoost Exciter (0..1000), MBC Bass ALC Sustain (0..4.0 dB),
     * and Pre-Amplifier Input Gain (-6.0..+3.0 dB).
     */
    public void setFullStudioCalibration(
            float subDb,
            float punchDb,
            float lowMidWarmthDb,
            float midPresenceDb,
            float airTrebleDb,
            int hwBassBoost,
            float mbcAlcGainDb,
            float preampGainDb) {
        this.currentSubBassDb = Math.max(-6.0f, Math.min(16.0f, subDb));
        this.currentPunchDb = Math.max(-6.0f, Math.min(16.0f, punchDb));
        this.currentClarityDb = Math.max(-6.0f, Math.min(6.0f, lowMidWarmthDb));
        this.currentMidPresenceDb = Math.max(-6.0f, Math.min(10.0f, midPresenceDb));
        this.currentAirDb = Math.max(-6.0f, Math.min(12.0f, airTrebleDb));
        this.currentMbcSubPostGainDb = Math.max(0.0f, Math.min(4.0f, mbcAlcGainDb));
        this.currentMbcPunchPostGainDb = Math.max(0.0f, Math.min(3.0f, mbcAlcGainDb * 0.7f));
        this.currentPreampGainDb = Math.max(-6.0f, Math.min(3.0f, preampGainDb));
        this.currentBassBoost = Math.max(0, Math.min(1000, hwBassBoost));

        applyPreampGain();
        applyPreEqGains();
        applyMbcBassAlc();
        setBassBoost(this.currentBassBoost);
    }

    public void setDynamicEqGains(float sub, float punch, float clarity, float air, int boost) {
        float mbcAlc = Math.max(0.5f, (sub / 14.0f) * 2.0f);
        int hwBoost = Math.min(1000, Math.max(boost * 4, (int) (sub * 45.0f)));
        setDynamicEqGains(sub, punch, clarity, 1.8f, air, hwBoost, mbcAlc, 0.0f);
    }

    public void setDynamicEqGains(
            float sub,
            float punch,
            float clarity,
            float midPresence,
            float air,
            int hwBoost,
            float mbcAlcGainDb,
            float preampGainDb) {
        setFullStudioCalibration(sub, punch, clarity, midPresence, air, hwBoost, mbcAlcGainDb, preampGainDb);
    }

    public void smoothTransitionTo(float targetSub, float targetPunch, float targetClarity, int targetBoost) {
        int hwBoost = Math.min(1000, Math.max(targetBoost * 4, (int) (targetSub * 45.0f)));
        float targetAlc = Math.max(0.5f, (targetSub / 14.0f) * 2.0f);
        smoothTransitionTo(targetSub, targetPunch, targetClarity, currentMidPresenceDb, currentAirDb, hwBoost, targetAlc, currentPreampGainDb);
    }

    public void smoothTransitionTo(
            float targetSub,
            float targetPunch,
            float targetClarity,
            float targetMidPresence,
            float targetAir,
            int targetHwBoost,
            float targetMbcAlc,
            float targetPreamp) {
        this.currentSubBassDb += (targetSub - this.currentSubBassDb) * 0.4f;
        this.currentPunchDb += (targetPunch - this.currentPunchDb) * 0.4f;
        this.currentClarityDb += (Math.max(-6.0f, Math.min(6.0f, targetClarity)) - this.currentClarityDb) * 0.4f;
        this.currentMidPresenceDb += (Math.max(-6.0f, Math.min(10.0f, targetMidPresence)) - this.currentMidPresenceDb) * 0.4f;
        this.currentAirDb += (Math.max(-6.0f, Math.min(12.0f, targetAir)) - this.currentAirDb) * 0.4f;
        this.currentMbcSubPostGainDb += (Math.max(0.0f, Math.min(4.0f, targetMbcAlc)) - this.currentMbcSubPostGainDb) * 0.4f;
        this.currentMbcPunchPostGainDb = this.currentMbcSubPostGainDb * 0.7f;
        this.currentPreampGainDb += (Math.max(-6.0f, Math.min(3.0f, targetPreamp)) - this.currentPreampGainDb) * 0.4f;
        this.currentBassBoost = Math.max(0, Math.min(1000, (int) (this.currentBassBoost + (targetHwBoost - this.currentBassBoost) * 0.4f)));

        applyPreampGain();
        applyPreEqGains();
        applyMbcBassAlc();
        setBassBoost(this.currentBassBoost);
    }

    /**
     * Connects the Sub-Harmonic Synthesizer UI (Sweep, Wide, Sub Level, Auto)
     * to the global Post-EQ resonator so YouTube Music, Spotify, and all apps
     * receive deep sub-octave reinforcement in real time!
     */
    public void updateGlobalSubSynth(float sweepHz, float wideQ, float subLevel0to1, boolean autoMode) {
        this.subSynthSweepHz = sweepHz;
        this.subSynthWideQ = wideQ;
        this.subSynthLevel = Math.max(0.0f, Math.min(1.0f, subLevel0to1));
        this.subSynthAutoMode = autoMode;
        applyPostEqSubSynth();
    }

    private void applyPostEqSubSynth() {
        try {
            if (dynamicsProcessing != null) {
                DynamicsProcessing.Eq postEq = dynamicsProcessing.getPostEqByChannelIndex(0);
                if (postEq != null) {
                    // Cutoff frequency tracks the synthesized sub-harmonic region
                    float subCutoff = subSynthAutoMode ? 65.0f : Math.max(45.0f, Math.min(95.0f, subSynthSweepHz * 0.85f));
                    float punchCutoff = Math.max(subCutoff + 55.0f, 175.0f);

                    // Wide Q controls how much upper-bass harmonic coupling joins the deep sub-octave
                    float qFactor = subSynthAutoMode ? 1.0f : Math.max(0.4f, Math.min(1.6f, 2.0f / subSynthWideQ));
                    float subBoostDb = subSynthLevel * 5.5f; // Up to +5.5 dB pure sub-harmonic weight
                    float punchCouplingDb = subSynthLevel * 2.8f * qFactor;

                    postEq.setBand(0, new DynamicsProcessing.EqBand(true, subCutoff, subBoostDb));
                    postEq.setBand(1, new DynamicsProcessing.EqBand(true, punchCutoff, punchCouplingDb));
                    postEq.setBand(2, new DynamicsProcessing.EqBand(true, 600.0f, 0.0f));
                    postEq.setBand(3, new DynamicsProcessing.EqBand(true, 4000.0f, 0.0f));
                    postEq.setBand(4, new DynamicsProcessing.EqBand(true, 20000.0f, 0.0f));
                    dynamicsProcessing.setPostEqAllChannelsTo(postEq);
                }
            }
        } catch (Exception e) {
            AppLogger.e(TAG, "Error applying Post-EQ SubSynth: " + e.getMessage(), e);
        }
    }

    public void setSubBassGain(float db) {
        this.currentSubBassDb = Math.max(-6.0f, Math.min(16.0f, db));
        applyPreEqGains();
    }

    public void setPunchGain(float db) {
        this.currentPunchDb = Math.max(-6.0f, Math.min(16.0f, db));
        applyPreEqGains();
    }

    public void setClarityGain(float db) {
        this.currentClarityDb = Math.max(-6.0f, Math.min(6.0f, db));
        applyPreEqGains();
    }

    public void setMidPresenceGain(float db) {
        this.currentMidPresenceDb = Math.max(-6.0f, Math.min(10.0f, db));
        applyPreEqGains();
    }

    public void setAirGain(float db) {
        this.currentAirDb = Math.max(-6.0f, Math.min(12.0f, db));
        applyPreEqGains();
        QsVuTelemetryBridge.sendFromService(this);
    }

    public void setMbcBassAlcGain(float db) {
        this.currentMbcSubPostGainDb = Math.max(0.0f, Math.min(4.0f, db));
        this.currentMbcPunchPostGainDb = Math.max(0.0f, Math.min(3.0f, db * 0.7f));
        applyMbcBassAlc();
    }

    public void setPreampGain(float db) {
        this.currentPreampGainDb = Math.max(-6.0f, Math.min(3.0f, db));
        applyPreampGain();
    }

    public void setBassBoost(int strength) {
        this.currentBassBoost = Math.max(0, Math.min(1000, strength));
        if (lastAppliedBassBoost >= 0
                && Math.abs(this.currentBassBoost - lastAppliedBassBoost) < BOOST_DEADBAND_UNITS
                && this.currentBassBoost != 0 && this.currentBassBoost != 1000) {
            return;
        }
        try {
            if (bassBoost != null && bassBoost.getStrengthSupported()) {
                bassBoost.setStrength((short) this.currentBassBoost);
                lastAppliedBassBoost = this.currentBassBoost;
            }
        } catch (Exception ignored) {}
    }

    public void setLimiterEnabled(boolean enabled) {
        this.isLimiterEnabled = enabled;
        try {
            if (dynamicsProcessing != null) {
                DynamicsProcessing.Limiter limiter = dynamicsProcessing.getLimiterByChannelIndex(0);
                if (limiter != null) {
                    limiter.setEnabled(enabled);
                    dynamicsProcessing.setLimiterAllChannelsTo(limiter);
                }
            }
        } catch (Exception ignored) {}
    }

    public AiAudioAnalyzer getAiAnalyzer() {
        return aiAudioAnalyzer;
    }

    /**
     * PHILIPS FW-M576 MAX SOUND (STMicroelectronics TDA7468D Service Manual Spec):
     * - Engages TDA7468D maximum dynamic bass (+14 dB step ceiling) + Bass ALC active
     * - Mutually exclusive with Sony LBT-XB66; overrides DSC while active
     */
    public void setMaxSound(boolean active) {
        if (active && isSonyXb66Active) {
            this.isSonyXb66Active = false;
        }
        this.isMaxSoundActive = active;
        recalculateHardwareMatrix();
        QsVuTelemetryBridge.sendFromService(this);

        if (active) {
            new Thread(() -> {
                QuadDacManager.setForceHighImpedance(true);
                QuadDacManager.setEssFilter(0);
            }).start();
            AppLogger.i(TAG, "⚡ Philips FW-M576 MAX SOUND Activado (TDA7468D +14dB Sub/Bass ALC + 2.0Vrms DAC).");
        } else {
            AppLogger.i(TAG, "Philips MAX SOUND desactivado. Restaurando matriz DSC + DBB + Gameport.");
        }
    }

    public boolean isMaxSoundActive() {
        return isMaxSoundActive;
    }

    /**
     * SONY LBT-XB66 (Mitsubishi M62427FP IC201 + SS-XB66V 17cm SAW Super Woofer Spec):
     * - 5-Band GEQ (63Hz, 250Hz, 1kHz, 4kHz, 16kHz) in GROOVE V-Curve
     * - DBFB HIGH (Dynamic Bass Feedback) + SUPER WOOFER HIGH (17cm SAW Chamber)
     */
    public void setSonyXb66Sound(boolean active) {
        if (active && isMaxSoundActive) {
            this.isMaxSoundActive = false;
        }
        this.isSonyXb66Active = active;
        recalculateHardwareMatrix();
        QsVuTelemetryBridge.sendFromService(this);

        if (active) {
            new Thread(() -> {
                QuadDacManager.setForceHighImpedance(true);
                QuadDacManager.setEssFilter(0);
            }).start();
            AppLogger.i(TAG, "📻 SONY LBT-XB66 Activado (M62427FP GROOVE + DBFB HIGH + 17cm SAW Super Woofer).");
        } else {
            AppLogger.i(TAG, "SONY LBT-XB66 desactivado. Restaurando matriz acústica.");
        }
    }

    public boolean isSonyXb66Active() {
        return isSonyXb66Active;
    }

    /**
     * PHILIPS FW-M576 DBB (Dynamic Bass Boost 3-Stage wOOx):
     * Works ADDITIVELY with DSC (Digital Sound Control) and Gameport, just like the real hardware!
     */
    public void setDbbLevel(int level) {
        this.currentDbbLevel = level;
        if (level > 0) {
            this.isMaxSoundActive = false;
            this.isSonyXb66Active = false;
        }
        recalculateHardwareMatrix();
        AppLogger.i(TAG, "Philips DBB cambiado a nivel " + level + " (Combinado con DSC=" + currentDscPreset + ", Gameport=" + currentGameportMode + ")");
    }

    public int getDbbLevel() {
        return currentDbbLevel;
    }

    /**
     * PHILIPS FW-M576 GAMEPORT SOUND MODES (SPEED, PUNCH, BLAST):
     * Works ADDITIVELY with DSC and DBB!
     */
    public void setGameportMode(int mode) {
        this.currentGameportMode = mode;
        if (mode > 0) {
            this.isMaxSoundActive = false;
            this.isSonyXb66Active = false;
        }
        recalculateHardwareMatrix();
        AppLogger.i(TAG, "Philips Gameport cambiado a modo " + mode + " (Combinado con DSC=" + currentDscPreset + ", DBB=" + currentDbbLevel + ")");
    }

    public int getGameportMode() {
        return currentGameportMode;
    }

    /**
     * PHILIPS FW-M576 DSC (Digital Sound Control: OPTIMAL, TECHNO, ROCK, POP, JAZZ):
     * Sets the 5-band base curve in TDA7468D 2dB steps, and combines additively with DBB & Gameport!
     */
    public void setDscPreset(int preset) {
        this.currentDscPreset = preset;
        this.isMaxSoundActive = false;
        this.isSonyXb66Active = false;
        recalculateHardwareMatrix();
        AppLogger.i(TAG, "Philips DSC aplicado: preset " + preset + " (DBB=" + currentDbbLevel + ", Gameport=" + currentGameportMode + ")");
    }

    public int getDscPreset() {
        return currentDscPreset;
    }

    /**
     * Authentic Hardware Combinatorial Matrix:
     * Replicates the exact signal flow of the Philips FW-M576 (TDA7468D) and Sony LBT-XB66 (M62427FP).
     */
    private void recalculateHardwareMatrix() {
        this.activeAcousticProfile = -1;
        this.currentPreampGainDb = 0.0f;
        try {
            applyPreampGain();

            if (isMaxSoundActive) {
                // PHILIPS FW-M576 MAX SOUND (TDA7468D Max Dynamic Boost + Bass ALC):
                this.currentSubBassDb = 12.5f;        // +12.5 dB PreEq + 2.0 dB MBC = +14.5 dB Sub-Bass
                this.currentPunchDb = 9.5f;           // +9.5 dB PreEq + 1.5 dB MBC = +11.0 dB Punch
                this.currentClarityDb = 0.5f;         // Full warm lower-mid body (zero hollowness)
                this.currentMidPresenceDb = 2.5f;     // Clear vocal & snare presence
                this.currentAirDb = 6.0f;             // TDA7468D +6dB high-frequency brilliance
                this.currentMbcSubPostGainDb = 2.0f;  // Bass ALC active boost
                this.currentMbcPunchPostGainDb = 1.5f;
                this.currentBassBoost = 750;          // Hardware sub-bass harmonic reinforcement
            } else if (isSonyXb66Active) {
                // SONY LBT-XB66 (M62427FP GROOVE + DBFB HIGH + 17cm SAW Super Woofer):
                // 5-Band GEQ: 63Hz (+10dB + SAW +3.5dB), 250Hz (-0.5dB), 1kHz-4kHz (+2.5dB), 16kHz (+6dB)
                this.currentSubBassDb = 13.5f;        // +13.5 dB PreEq + 2.5 dB DBFB = +16.0 dB SAW Subwoofer
                this.currentPunchDb = 10.0f;          // +10.0 dB PreEq + 1.5 dB DBFB = +11.5 dB 20cm HOP Woofer
                this.currentClarityDb = -0.5f;        // Subtle 250Hz V-Groove contour
                this.currentMidPresenceDb = 2.5f;     // 4kHz Sony Groove presence
                this.currentAirDb = 6.0f;             // 16kHz 5cm Super Tweeter sparkle
                this.currentMbcSubPostGainDb = 2.5f;  // DBFB HIGH dynamic feedback gain
                this.currentMbcPunchPostGainDb = 1.5f;
                this.currentBassBoost = 850;          // Deep 45Hz SAW resonant chamber pressure
            } else {
                //STAGE 1: Philips DSC Base 5-Band Curve (TDA7468D 2dB steps)
                float sub = 4.0f, punch = 3.5f, lowMid = 0.0f, mid = 1.5f, treble = 2.0f;
                switch (currentDscPreset) {
                    case 0: // OPTIMAL (Balanced Loudness)
                        sub = 5.0f; punch = 4.0f; lowMid = 0.0f; mid = 1.5f; treble = 2.5f;
                        break;
                    case 1: // TECHNO (Electronic Sub & Highs)
                        sub = 8.0f; punch = 6.5f; lowMid = -0.5f; mid = 2.0f; treble = 5.5f;
                        break;
                    case 2: // ROCK (Heavy Kick, Guitar & Cymbals)
                        sub = 6.5f; punch = 7.0f; lowMid = -0.5f; mid = 3.0f; treble = 4.5f;
                        break;
                    case 3: // POP (Warm Bass & Forward Vocals)
                        sub = 4.5f; punch = 5.0f; lowMid = 0.5f; mid = 3.5f; treble = 3.0f;
                        break;
                    case 4: // JAZZ (Warm Acoustic Body & Natural Detail)
                        sub = 4.0f; punch = 3.5f; lowMid = 1.5f; mid = 2.0f; treble = 2.5f;
                        break;
                }

                // STAGE 2: Additive Philips DBB (Dynamic Bass Boost wOOx 3-Level Matrix)
                float mbcSub = 0.8f, mbcPunch = 0.5f;
                int hwBoost = 350;
                switch (currentDbbLevel) {
                    case 1: // DBB 1 (Deep Sub-WOOX Contour)
                        sub += 4.5f;
                        punch += 2.5f;
                        mbcSub = 1.4f;
                        mbcPunch = 0.8f;
                        hwBoost = 550;
                        break;
                    case 2: // DBB 2 (Tight Upper-Bass Chest Punch)
                        sub += 3.0f;
                        punch += 5.5f;
                        mbcSub = 1.2f;
                        mbcPunch = 1.5f;
                        hwBoost = 600;
                        break;
                    case 3: // DBB 3 (Maximum 2-Stage WOOX Sub + Punch)
                        sub += 7.0f;
                        punch += 5.5f;
                        treble += 1.0f;
                        mbcSub = 2.0f;
                        mbcPunch = 1.5f;
                        hwBoost = 800;
                        break;
                    case 0: // DBB OFF
                    default:
                        break;
                }

                // STAGE 3: Additive Philips Gameport Console Modes (SPEED, PUNCH, BLAST)
                switch (currentGameportMode) {
                    case 1: // SPEED (Fast Transients, Racing & Rhythm)
                        sub += 1.5f;
                        punch += 3.0f;
                        mid += 3.0f;
                        treble += 3.5f;
                        break;
                    case 2: // PUNCH (Action, Combat & Mid-Bass Impact)
                        sub += 3.5f;
                        punch += 5.0f;
                        mid += 1.5f;
                        hwBoost = Math.max(hwBoost, 650);
                        break;
                    case 3: // BLAST (Cinema Sub-Octave Explosion Shockwave)
                        sub += 6.5f;
                        punch += 3.5f;
                        treble += 1.5f;
                        mbcSub += 0.8f;
                        hwBoost = Math.max(hwBoost, 850);
                        break;
                    case 0: // GAMEPORT OFF
                    default:
                        break;
                }

                this.currentSubBassDb = Math.min(15.0f, sub);
                this.currentPunchDb = Math.min(12.5f, punch);
                this.currentClarityDb = lowMid;
                this.currentMidPresenceDb = Math.min(6.0f, mid);
                this.currentAirDb = Math.min(8.0f, treble);
                this.currentMbcSubPostGainDb = Math.min(3.0f, mbcSub);
                this.currentMbcPunchPostGainDb = Math.min(2.5f, mbcPunch);
                this.currentBassBoost = Math.min(1000, hwBoost);
            }

            applyPreEqGains();
            applyMbcBassAlc();
            applyPostEqSubSynth();
            setBassBoost(this.currentBassBoost);
        } catch (Exception e) {
            AppLogger.e(TAG, "Error en recalculateHardwareMatrix: " + e.getMessage(), e);
        }
    }

    public float getCurrentSubBassDb() { return currentSubBassDb; }
    public float getCurrentPunchDb() { return currentPunchDb; }
    public float getCurrentClarityDb() { return currentClarityDb; }
    public float getCurrentMidPresenceDb() { return currentMidPresenceDb; }
    public float getCurrentAirDb() { return currentAirDb; }
    public int getCurrentBassBoost() { return currentBassBoost; }
    public float getCurrentMbcSubPostGainDb() { return currentMbcSubPostGainDb; }
    public float getCurrentPreampGainDb() { return currentPreampGainDb; }

    private void applyPreampGain() {
        try {
            if (dynamicsProcessing != null) {
                if (Float.isNaN(lastAppliedPreampDb) || Math.abs(currentPreampGainDb - lastAppliedPreampDb) >= EQ_DEADBAND_DB) {
                    dynamicsProcessing.setInputGainAllChannelsTo(currentPreampGainDb);
                    lastAppliedPreampDb = currentPreampGainDb;
                }
            }
        } catch (Exception e) {
            AppLogger.e(TAG, "Error applying Preamp Input Gain: " + e.getMessage(), e);
        }
    }

    private void applyPreEqGains() {
        try {
            if (dynamicsProcessing != null) {
                boolean changed = Float.isNaN(lastAppliedSubDb)
                    || Math.abs(currentSubBassDb - lastAppliedSubDb) >= EQ_DEADBAND_DB
                    || Math.abs(currentPunchDb - lastAppliedPunchDb) >= EQ_DEADBAND_DB
                    || Math.abs(currentClarityDb - lastAppliedClarityDb) >= EQ_DEADBAND_DB
                    || Math.abs(currentMidPresenceDb - lastAppliedMidDb) >= EQ_DEADBAND_DB
                    || Math.abs(currentAirDb - lastAppliedAirDb) >= EQ_DEADBAND_DB;
                if (!changed) return;

                DynamicsProcessing.Eq preEq = dynamicsProcessing.getPreEqByChannelIndex(0);
                if (preEq != null) {
                    preEq.getBand(0).setGain(currentSubBassDb);
                    preEq.getBand(1).setGain(currentPunchDb);
                    preEq.getBand(2).setGain(currentClarityDb);
                    preEq.getBand(3).setGain(currentMidPresenceDb);
                    preEq.getBand(4).setGain(currentAirDb);
                    dynamicsProcessing.setPreEqAllChannelsTo(preEq);
                    lastAppliedSubDb = currentSubBassDb;
                    lastAppliedPunchDb = currentPunchDb;
                    lastAppliedClarityDb = currentClarityDb;
                    lastAppliedMidDb = currentMidPresenceDb;
                    lastAppliedAirDb = currentAirDb;
                }
            }
        } catch (Exception e) {
            AppLogger.e(TAG, "Error applying Pre-EQ: " + e.getMessage(), e);
        }
    }

    private void applyMbcBassAlc() {
        try {
            if (dynamicsProcessing != null) {
                boolean changed = Float.isNaN(lastAppliedMbcSubDb)
                    || Math.abs(currentMbcSubPostGainDb - lastAppliedMbcSubDb) >= EQ_DEADBAND_DB
                    || Math.abs(currentMbcPunchPostGainDb - lastAppliedMbcPunchDb) >= EQ_DEADBAND_DB;
                if (!changed) return;

                DynamicsProcessing.Mbc mbc = dynamicsProcessing.getMbcByChannelIndex(0);
                if (mbc != null) {
                    mbc.getBand(0).setPostGain(currentMbcSubPostGainDb);
                    mbc.getBand(1).setPostGain(currentMbcPunchPostGainDb);
                    dynamicsProcessing.setMbcAllChannelsTo(mbc);
                    lastAppliedMbcSubDb = currentMbcSubPostGainDb;
                    lastAppliedMbcPunchDb = currentMbcPunchPostGainDb;
                }
            }
        } catch (Exception e) {
            AppLogger.e(TAG, "Error applying MBC Bass ALC: " + e.getMessage(), e);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "V60 Bass Lab DSP Service",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Bass DSP Engine active in background");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
            .setContentTitle("V60 Bass Lab")
            .setContentText("Quad DAC & Bass DSP Engine active")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (instance == this) {
            instance = null;
        }
        try {
            if (dynamicsProcessing != null) {
                dynamicsProcessing.setEnabled(false);
                dynamicsProcessing.release();
            }
            if (bassBoost != null) {
                bassBoost.setEnabled(false);
                bassBoost.release();
            }
            if (visualizer != null) {
                visualizer.setEnabled(false);
                visualizer.release();
            }
        } catch (Exception ignored) {}
    }
}
