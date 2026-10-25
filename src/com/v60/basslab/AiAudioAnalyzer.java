package com.v60.basslab;

import java.util.Locale;

/**
 * AiAudioAnalyzer
 *
 * Motor Psicoacústico de Inteligencia Artificial de Alta Eficiencia:
 * 1. Smart DJ (Auto-Tuning por Canción): Clasifica y fija la huella espectral del tema completo.
 * 2. Live Dynamic EQ Inmersivo 3D (Stem-Tracker Continuo):
 *    - Extracción espectral rápida en 6 sub-bandas sin llamadas costosas a StrictMath.hypot.
 *    - Detección de flujo transitorio (Beat/Kick Flux) + compensación psicoacústica Fletcher-Munson.
 *    - Balística asimétrica (Ataque rápido 0.38 en golpes de bajo / Relajación musical suave 0.16).
 *    - Ejecución directa en AudioDspService en segundo plano (funciona con pantalla apagada o en otras apps).
 */
public class AiAudioAnalyzer {

    private static final String TAG = "V60_AiAnalyzer";

    public interface DirectDspListener {
        void onApplyLiveDynamicEq(
            float subDb, float punchDb, float clarityDb, float midPresenceDb, float airDb,
            int hwBoost, float mbcAlcDb, float preampDb
        );
    }

    public interface AiGenreListener {
        void onGenreStatusChanged(
            String statusTitle, String description, boolean isLocked,
            float recSubDb, float recPunchDb, float recClarityDb, float recMidPresenceDb, float recAirDb,
            int recHwBoost, float recMbcAlcDb, float recPreampDb,
            int recMasterPercent, int recEssFilter, int recProfileIndex,
            int recDbbLevel, int recDscPreset,
            boolean enablePhilipsMax, boolean enableSonyXb66
        );
        void onLiveDynamicStemChanged(
            String stemTitle, String stemAction,
            float subDb, float punchDb, float clarityDb, float midPresenceDb, float airDb,
            int hwBoost, float mbcAlcDb, float preampDb,
            int dynMasterPercent, int dynEssFilter
        );
    }

    private DirectDspListener directDspListener;
    private AiGenreListener listener;
    private boolean isAiActive = false;
    private boolean isLiveDynamicEqActive = false;

    // Estados de canción (Smart DJ)
    private boolean isLocked = false;
    private long analysisStartTime = 0;
    private long silenceStartTime = 0;

    // Acumuladores para análisis global de canción
    private float accSub = 0.0f;
    private float accMid = 0.0f;
    private float accWarmth = 0.0f;
    private float accVocal = 0.0f;
    private float accHigh = 0.0f;
    private int accumulatedFrames = 0;
    private int beatCounter = 0;
    private float prevEnergy = 0.0f;
    private float prevBassInstant = 0.0f;

    // Estado continuo de Live Dynamic EQ (8 parámetros con balística asimétrica)
    private float dynSub = 8.0f;
    private float dynPunch = 6.5f;
    private float dynClarity = -0.5f;
    private float dynMidPresence = 2.0f;
    private float dynAir = 3.0f;
    private float dynHwBoost = 550f;
    private float dynMbcAlc = 1.5f;
    private float dynPreamp = 0.0f;
    private float dynMasterPercent = 70f;
    private int dynEssFilter = 0;
    private int currentModulationIntensity = 65;
    private String currentTelemetryLine = "SUB +8.0dB • KICK +6.5dB • CUERPO -0.5dB • VOZ +2.0dB • AIRE +3.0dB • ALC +1.5dB";

    private long lastDynamicUpdateMs = 0;

    public void setDirectDspListener(DirectDspListener directListener) {
        this.directDspListener = directListener;
    }

    public void setAiGenreListener(AiGenreListener listener) {
        this.listener = listener;
    }

    public int getCurrentModulationIntensity() {
        return currentModulationIntensity;
    }

    public String getCurrentTelemetryLine() {
        return currentTelemetryLine;
    }

    public void setAiActive(boolean active) {
        this.isAiActive = active;
        resetAnalysis();
        if (!active && listener != null) {
            listener.onGenreStatusChanged("Manual Mode", "Manual settings active", false, 0, 0, 0, 0, 0, 0, 0, 0, 60, 0, -1, 0, 0, false, false);
        }
    }

    public boolean isAiActive() {
        return isAiActive;
    }

    public void setLiveDynamicEqActive(boolean active) {
        this.isLiveDynamicEqActive = active;
        if (active) {
            updateTelemetryString();
            if (directDspListener != null) {
                directDspListener.onApplyLiveDynamicEq(
                    dynSub, dynPunch, dynClarity, dynMidPresence, dynAir,
                    Math.round(dynHwBoost), dynMbcAlc, dynPreamp
                );
            }
            if (listener != null) {
                listener.onLiveDynamicStemChanged(
                    "⚡ 3D Psychoacoustic Engine Active",
                    "Tracking transients, vocal stage, sub-bass and Fletcher-Munson contour",
                    dynSub, dynPunch, dynClarity, dynMidPresence, dynAir,
                    Math.round(dynHwBoost), dynMbcAlc, dynPreamp,
                    Math.round(dynMasterPercent), dynEssFilter
                );
            }
        }
    }

    public boolean isLiveDynamicEqActive() {
        return isLiveDynamicEqActive;
    }

    public void unlockAndReanalyze() {
        resetAnalysis();
        AppLogger.i(TAG, "Reanálisis manual solicitado por el usuario.");
    }

    private void resetAnalysis() {
        isLocked = false;
        analysisStartTime = 0;
        silenceStartTime = 0;
        accSub = 0.0f;
        accMid = 0.0f;
        accWarmth = 0.0f;
        accVocal = 0.0f;
        accHigh = 0.0f;
        accumulatedFrames = 0;
        beatCounter = 0;
        prevEnergy = 0.0f;
    }

    public void processFft(byte[] fft) {
        if ((!isAiActive && !isLiveDynamicEqActive) || fft == null || fft.length < 32) return;

        int maxBin = Math.min(fft.length / 2, 60);
        float subEnergy = 0.0f;     // 20 - 65 Hz (bins 1..3)
        float midEnergy = 0.0f;     // 65 - 180 Hz (bins 4..7)
        float warmthEnergy = 0.0f;  // 180 - 750 Hz (bins 8..15)
        float vocalEnergy = 0.0f;   // 750 - 3800 Hz (bins 16..35)
        float highEnergy = 0.0f;    // > 3800 Hz (bins 36..59)

        // Bucle optimizado sin StrictMath.hypot (evita sobrecarga JNI en el hilo de captura)
        for (int k = 1; k < maxBin; k++) {
            int r = fft[2 * k];
            int i = fft[2 * k + 1];
            float mag = (float) Math.sqrt(r * r + i * i);

            if (k <= 3) subEnergy += mag;
            else if (k <= 7) midEnergy += mag;
            else if (k <= 15) warmthEnergy += mag;
            else if (k <= 35) vocalEnergy += mag;
            else highEnergy += mag;
        }

        float totalInstantEnergy = subEnergy + midEnergy + warmthEnergy + vocalEnergy + highEnergy + 0.001f;
        long now = System.currentTimeMillis();

        // 1. Detección de silencio o cambio de pista
        if (totalInstantEnergy < 2.2f) {
            if (silenceStartTime == 0) {
                silenceStartTime = now;
            } else if (now - silenceStartTime > 1600 && isLocked) {
                resetAnalysis();
                if (listener != null) {
                    listener.onGenreStatusChanged("Waiting for track...", "Detecting start of new track", false, 0, 0, 0, 0, 0, 0, 0, 0, 60, 0, -1, 0, 0, false, false);
                }
            }
            return;
        } else {
            silenceStartTime = 0;
        }

        // Flujo transitorio de graves (detecta ataques de bombo y drops al instante)
        float currentBassInstant = subEnergy + midEnergy;
        float bassFlux = Math.max(0.0f, currentBassInstant - prevBassInstant);
        prevBassInstant = currentBassInstant * 0.75f + prevBassInstant * 0.25f;

        // 2. MODO LIVE DYNAMIC EQ INMERSIVO (Evaluación continua de alta velocidad cada 160 ms)
        if (isLiveDynamicEqActive) {
            processLiveDynamicEq(subEnergy, midEnergy, warmthEnergy, vocalEnergy, highEnergy, bassFlux, totalInstantEnergy, now);
        }

        // 3. MODO BLOQUEO DE CANCIÓN (Smart DJ)
        if (isLocked || !isAiActive) {
            return;
        }

        if (analysisStartTime == 0) {
            analysisStartTime = now;
        }

        accSub += subEnergy;
        accMid += midEnergy;
        accWarmth += warmthEnergy;
        accVocal += vocalEnergy;
        accHigh += highEnergy;
        accumulatedFrames++;

        if (currentBassInstant > prevEnergy * 1.35f && currentBassInstant > 12.0f) {
            beatCounter++;
        }
        prevEnergy = currentBassInstant;

        long elapsed = now - analysisStartTime;
        if (elapsed < 4500) {
            int progressSeconds = (int) (elapsed / 1000);
            if (listener != null && accumulatedFrames % 15 == 0) {
                listener.onGenreStatusChanged("Analyzing (" + progressSeconds + "s)...", "Studying rhythm and harmonic spectrum", false, 0, 0, 0, 0, 0, 0, 0, 0, 60, 0, -1, 0, 0, false, false);
            }
        } else if (accumulatedFrames > 35) {
            classifyAndLockSong();
        }
    }

    /**
     * Motor Psicoacústico Inmersivo en Tiempo Real (160 ms, Balística Asimétrica + Curvas Proporcionales)
     */
    private void processLiveDynamicEq(
            float sub, float mid, float warmth, float vocal, float high,
            float bassFlux, float total, long now) {
        if (now - lastDynamicUpdateMs < 160) return; // 6.25 actualizaciones/seg sin saturar UI
        lastDynamicUpdateMs = now;

        float subRatio = sub / total;
        float midRatio = mid / total;
        float warmthRatio = warmth / total;
        float vocalRatio = vocal / total;
        float highRatio = high / total;
        float transientDrive = Math.min(1.0f, bassFlux / 45.0f);

        float targetSub;
        float targetPunch;
        float targetClarity;
        float targetMidPresence;
        float targetAir;
        float targetHwBoost;
        float targetMbcAlc;
        float targetPreamp;
        float targetMasterPercent;
        int targetEssFilter;
        String stemTitle;
        String stemAction;

        if (subRatio > 0.31f || (sub > 13.0f && subRatio > 0.25f)) {
            // 1. DROP / SUB-808 INMERSIVO 3D (Modulación proporcional a la presión sub-grave)
            float intensity = Math.min(1.0f, (subRatio - 0.22f) * 3.8f + transientDrive * 0.35f);
            stemTitle = "💥 3D Immersive Drop / Sub-808";
            stemAction = "Sub-octave 65Hz expansion, clean 600Hz cut & holographic air";
            targetSub = 10.5f + intensity * 3.2f;       // +10.5 a +13.7 dB
            targetPunch = 8.0f + intensity * 2.2f;      // +8.0 a +10.2 dB
            targetClarity = -1.2f - intensity * 0.8f;   // -1.2 a -2.0 dB (evita enmascarar voces)
            targetMidPresence = 2.0f + intensity * 0.8f;// +2.0 a +2.8 dB
            targetAir = 3.2f + intensity * 1.4f;        // +3.2 a +4.6 dB (platillos flotantes 3D)
            targetHwBoost = 680f + intensity * 220f;    // 68% a 90%
            targetMbcAlc = 2.0f + intensity * 0.8f;     // +2.0 a +2.8 dB Sustain
            targetPreamp = 0.0f;
            targetMasterPercent = 80f + intensity * 14f;
            targetEssFilter = 0;
        } else if (midRatio > 0.26f && (transientDrive > 0.22f || subRatio > 0.18f)) {
            // 2. GROOVE RÍTMICO & PEGADA DE BOMBO (90-200 Hz Kick-Bin Slam)
            float punchIntensity = Math.min(1.0f, (midRatio - 0.20f) * 4.0f + transientDrive * 0.45f);
            stemTitle = "🥁 Rhythmic Groove & Kick Slam";
            stemAction = "Physical impact at 90–200Hz, fast snare attack & tight sub";
            targetSub = 7.8f + punchIntensity * 1.8f;
            targetPunch = 9.2f + punchIntensity * 2.4f; // Hasta +11.6 dB de pegada en el pecho
            targetClarity = -1.4f;
            targetMidPresence = 2.6f + punchIntensity * 0.9f;
            targetAir = 3.5f + punchIntensity * 1.0f;
            targetHwBoost = 600f + punchIntensity * 160f;
            targetMbcAlc = 1.7f + punchIntensity * 0.5f;
            targetPreamp = 0.0f;
            targetMasterPercent = 76f + punchIntensity * 10f;
            targetEssFilter = 2;
        } else if (vocalRatio > 0.30f && subRatio < 0.24f) {
            // 3. ESCENA VOCAL HOLOGRÁFICA (Bolsillo acústico para la voz principal)
            float vocalFocus = Math.min(1.0f, (vocalRatio - 0.26f) * 4.2f);
            stemTitle = "🎤 Holographic Vocal Stage";
            stemAction = "Vocal pocket: 1–4kHz boost, 350Hz veil cut & velvety bass";
            targetSub = 5.2f + (1.0f - vocalFocus) * 1.2f;
            targetPunch = 5.5f;
            targetClarity = -1.6f - vocalFocus * 0.9f;  // Limpia el velo en 250-600Hz
            targetMidPresence = 3.2f + vocalFocus * 1.2f; // +3.2 a +4.4 dB (voz al frente)
            targetAir = 3.2f + vocalFocus * 1.0f;       // Aire sedoso en respiración vocal
            targetHwBoost = 360f;
            targetMbcAlc = 1.1f;
            targetPreamp = 0.2f;
            targetMasterPercent = 60f;
            targetEssFilter = 2;
        } else if (highRatio > 0.26f) {
            // 4. SOLOS & AIRE ESPACIAL SUPER-TWEETER
            float airFocus = Math.min(1.0f, (highRatio - 0.22f) * 4.0f);
            stemTitle = "✨ Solos & Hi-Fi Spatial Air";
            stemAction = "3D soundstage opening at 8–20kHz & instrumental clarity";
            targetSub = 6.2f;
            targetPunch = 6.8f;
            targetClarity = -0.8f;
            targetMidPresence = 2.8f + airFocus * 0.8f;
            targetAir = 4.2f + airFocus * 1.6f;         // Hasta +5.8 dB de aire cristalino
            targetHwBoost = 480f;
            targetMbcAlc = 1.4f;
            targetPreamp = 0.0f;
            targetMasterPercent = 66f;
            targetEssFilter = 2;
        } else if (warmthRatio > 0.25f && subRatio < 0.22f) {
            // 5. CALIDEZ VALVULAR & CUERPO INSTRUMENTAL (250-600 Hz positivo)
            float warmFocus = Math.min(1.0f, (warmthRatio - 0.20f) * 3.5f);
            stemTitle = "🎸 Analog Warmth & Strings";
            stemAction = "Injecting 250–600Hz tube body (+2.0dB) & organic texture";
            targetSub = 6.0f;
            targetPunch = 7.2f;
            targetClarity = 1.4f + warmFocus * 1.0f;    // +1.4 a +2.4 dB de riqueza armónica
            targetMidPresence = 2.0f;
            targetAir = 2.2f;
            targetHwBoost = 450f;
            targetMbcAlc = 1.3f;
            targetPreamp = 0.5f;
            targetMasterPercent = 68f;
            targetEssFilter = 1;
        } else if (total < 18.0f) {
            // 6. PASAJE ATMOSFÉRICO / CONTORNO FLETCHER-MUNSON A BAJO VOLUMEN
            stemTitle = "🌊 Atmospheric Passage (Loudness)";
            stemAction = "Fletcher-Munson compensation: boosting sub & air in quiet passage";
            targetSub = 9.2f;
            targetPunch = 7.0f;
            targetClarity = 0.5f;
            targetMidPresence = 2.2f;
            targetAir = 4.5f;
            targetHwBoost = 580f;
            targetMbcAlc = 1.8f;
            targetPreamp = 0.8f;
            targetMasterPercent = 72f;
            targetEssFilter = 0;
        } else {
            // 7. DINÁMICA DE ESTUDIO BALANCEADA
            stemTitle = "🎵 Master 8-Channel Dynamics";
            stemAction = "Continuous psychoacoustic balance across 5 bands + Exciter + ALC";
            targetSub = 8.2f + transientDrive * 1.5f;
            targetPunch = 6.8f + transientDrive * 1.2f;
            targetClarity = -0.5f;
            targetMidPresence = 2.2f;
            targetAir = 3.2f;
            targetHwBoost = 560f + transientDrive * 90f;
            targetMbcAlc = 1.6f;
            targetPreamp = 0.0f;
            targetMasterPercent = 72f;
            targetEssFilter = 0;
        }

        // Balística Asimétrica: Ataque rápido (0.38) en subidas de energía, Relajación suave (0.16) en caídas
        dynSub = smoothAsymmetric(dynSub, targetSub, 0.38f, 0.16f);
        dynPunch = smoothAsymmetric(dynPunch, targetPunch, 0.38f, 0.16f);
        dynClarity = smoothAsymmetric(dynClarity, targetClarity, 0.30f, 0.16f);
        dynMidPresence = smoothAsymmetric(dynMidPresence, targetMidPresence, 0.32f, 0.16f);
        dynAir = smoothAsymmetric(dynAir, targetAir, 0.32f, 0.16f);
        dynHwBoost = smoothAsymmetric(dynHwBoost, targetHwBoost, 0.38f, 0.16f);
        dynMbcAlc = smoothAsymmetric(dynMbcAlc, targetMbcAlc, 0.35f, 0.16f);
        dynPreamp = smoothAsymmetric(dynPreamp, targetPreamp, 0.25f, 0.15f);
        dynMasterPercent = smoothAsymmetric(dynMasterPercent, targetMasterPercent, 0.35f, 0.16f);
        dynEssFilter = targetEssFilter;

        // Calcular intensidad de modulación psicoacústica (0..100%)
        float energyBoostRatio = (dynSub + dynPunch + Math.abs(dynClarity) + dynMidPresence + dynAir) / 36.0f;
        currentModulationIntensity = Math.max(15, Math.min(100, Math.round(energyBoostRatio * 100.0f)));
        updateTelemetryString();

        // 1. Aplicar directamente en el motor DSP de segundo plano (sin depender de la UI)
        if (directDspListener != null) {
            directDspListener.onApplyLiveDynamicEq(
                dynSub, dynPunch, dynClarity, dynMidPresence, dynAir,
                Math.round(dynHwBoost), dynMbcAlc, dynPreamp
            );
        }

        // 2. Notificar a la UI si está abierta
        if (listener != null) {
            listener.onLiveDynamicStemChanged(
                stemTitle, stemAction,
                dynSub, dynPunch, dynClarity, dynMidPresence, dynAir,
                Math.round(dynHwBoost), dynMbcAlc, dynPreamp,
                Math.round(dynMasterPercent), dynEssFilter
            );
        }
    }

    private float smoothAsymmetric(float current, float target, float attackAlpha, float releaseAlpha) {
        float alpha = (Math.abs(target) > Math.abs(current)) ? attackAlpha : releaseAlpha;
        return current + (target - current) * alpha;
    }

    private void updateTelemetryString() {
        currentTelemetryLine = String.format(
            Locale.US,
            "SUB %+.1fdB • KICK %+.1fdB • CUERPO %+.1fdB • VOZ %+.1fdB • AIRE %+.1fdB • ALC +%.1fdB",
            dynSub, dynPunch, dynClarity, dynMidPresence, dynAir, dynMbcAlc
        );
    }

    /**
     * Clasificación fija de canción completa (Smart DJ)
     */
    private void classifyAndLockSong() {
        if (accumulatedFrames <= 0) return;

        float avgSub = accSub / accumulatedFrames;
        float avgMid = accMid / accumulatedFrames;
        float avgWarmth = accWarmth / accumulatedFrames;
        float avgVocal = accVocal / accumulatedFrames;
        float avgHigh = accHigh / accumulatedFrames;

        float totalAvg = avgSub + avgMid + avgWarmth + avgVocal + avgHigh + 0.001f;

        float subRatio = avgSub / totalAvg;
        float midRatio = avgMid / totalAvg;
        float warmthRatio = avgWarmth / totalAvg;
        float vocalRatio = avgVocal / totalAvg;
        float highRatio = avgHigh / totalAvg;

        String genreName;
        String desc;
        float recSub;
        float recPunch;
        float recClarity;
        float recMidPresence;
        float recAir;
        int recHwBoost;
        float recMbcAlc;
        float recPreamp;
        int recMasterPercent;
        int recEssFilter;
        int recProfileIndex = -1;
        int recDbbLevel;
        int recDscPreset;
        boolean enablePhilipsMax;
        boolean enableSonyXb66;

        if (subRatio > 0.34f && avgSub > 7.0f) {
            genreName = "Urban / Trap / Hip-Hop";
            desc = "Deep 808 sub-bass (+12dB), ALC +2.3dB & clear vocal presence";
            recSub = 12.0f;
            recPunch = 6.5f;
            recClarity = -1.5f;
            recMidPresence = 2.5f;
            recAir = 4.0f;
            recHwBoost = 800;
            recMbcAlc = 2.3f;
            recPreamp = 0.0f;
            recMasterPercent = 85;
            recEssFilter = 0;
            recDbbLevel = 3;
            recDscPreset = 1;
            enablePhilipsMax = false;
            enableSonyXb66 = false;
        } else if (midRatio > 0.28f && beatCounter >= 8) {
            genreName = "Electronic / Club / EDM";
            desc = "Kick punch 90-200Hz (+10.5dB), fast transients & sparkle";
            recSub = 8.5f;
            recPunch = 10.5f;
            recClarity = -1.5f;
            recMidPresence = 3.0f;
            recAir = 4.5f;
            recHwBoost = 680;
            recMbcAlc = 1.9f;
            recPreamp = 0.0f;
            recMasterPercent = 80;
            recEssFilter = 2;
            recDbbLevel = 2;
            recDscPreset = 1;
            enablePhilipsMax = false;
            enableSonyXb66 = false;
        } else if (warmthRatio > 0.26f && highRatio > 0.20f) {
            genreName = "Rock / Metal / Alternative";
            desc = "Punchy kick, 1-4kHz guitar attack (+3.5dB) & cymbals";
            recSub = 6.5f;
            recPunch = 8.0f;
            recClarity = -1.0f;
            recMidPresence = 3.5f;
            recAir = 4.0f;
            recHwBoost = 550;
            recMbcAlc = 1.5f;
            recPreamp = 0.0f;
            recMasterPercent = 70;
            recEssFilter = 1;
            recDbbLevel = 0;
            recDscPreset = 2;
            enablePhilipsMax = false;
            enableSonyXb66 = false;
        } else if (vocalRatio > 0.29f && subRatio < 0.24f) {
            genreName = "Vocal / Acoustic / Jazz";
            desc = "Organic warmth 250-600Hz (+1.5dB), intimate vocals & wide soundstage";
            recSub = 5.0f;
            recPunch = 5.5f;
            recClarity = 1.5f;
            recMidPresence = 2.5f;
            recAir = 2.5f;
            recHwBoost = 350;
            recMbcAlc = 1.0f;
            recPreamp = 0.5f;
            recMasterPercent = 58;
            recEssFilter = 1;
            recDbbLevel = 0;
            recDscPreset = 4;
            enablePhilipsMax = false;
            enableSonyXb66 = false;
        } else if (highRatio > 0.26f) {
            genreName = "Modern Pop / Hi-Fi";
            desc = "Silky 16kHz air, vocal presence (+3.0dB) & defined bass";
            recSub = 7.5f;
            recPunch = 6.5f;
            recClarity = -0.5f;
            recMidPresence = 3.0f;
            recAir = 3.5f;
            recHwBoost = 520;
            recMbcAlc = 1.4f;
            recPreamp = 0.0f;
            recMasterPercent = 70;
            recEssFilter = 0;
            recDbbLevel = 1;
            recDscPreset = 3;
            enablePhilipsMax = false;
            enableSonyXb66 = false;
        } else if (subRatio > 0.24f && midRatio > 0.24f) {
            genreName = "Retro Pop / 80s - 90s";
            desc = "Classic Loudness contour with deep bass & crystal treble";
            recSub = 9.5f;
            recPunch = 8.0f;
            recClarity = -1.0f;
            recMidPresence = 2.0f;
            recAir = 5.0f;
            recHwBoost = 600;
            recMbcAlc = 1.7f;
            recPreamp = 0.0f;
            recMasterPercent = 75;
            recEssFilter = 0;
            recDbbLevel = 2;
            recDscPreset = 0;
            enablePhilipsMax = true;
            enableSonyXb66 = false;
        } else {
            genreName = "Balanced / Studio Reference";
            desc = "Balanced studio curve with accurate tonal fidelity";
            recSub = 4.5f;
            recPunch = 4.0f;
            recClarity = 0.0f;
            recMidPresence = 1.5f;
            recAir = 2.0f;
            recHwBoost = 300;
            recMbcAlc = 0.8f;
            recPreamp = 0.0f;
            recMasterPercent = 55;
            recEssFilter = 9;
            recDbbLevel = 0;
            recDscPreset = 0;
            enablePhilipsMax = false;
            enableSonyXb66 = false;
        }

        isLocked = true;
        AppLogger.i(TAG, "Canción clasificada y FIJADA: [" + genreName + "] -> Sub=" + recSub + "dB, Punch=" + recPunch + "dB, LowMid=" + recClarity + "dB, Mid=" + recMidPresence + "dB, Air=" + recAir + "dB, ALC=" + recMbcAlc + "dB");

        if (listener != null) {
            listener.onGenreStatusChanged(
                "🔒 Locked: " + genreName, desc, true,
                recSub, recPunch, recClarity, recMidPresence, recAir,
                recHwBoost, recMbcAlc, recPreamp,
                recMasterPercent, recEssFilter, recProfileIndex,
                recDbbLevel, recDscPreset,
                enablePhilipsMax, enableSonyXb66
            );
        }
    }
}
