package com.v60.basslab;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.media.AudioManager;
import android.os.SystemClock;

/**
 * QsVuGaugeRenderer
 *
 * Motor compartido de renderizado vectorial y física electro-mecánica D'Arsonval a 60 FPS
 * para los 2 Quick Settings Tiles:
 *  - Tile 1: VU BASS (MaxSoundTileService, marcador 98x98 px)
 *  - Tile 2: VU TREBLE (TrebleVuTileService, marcador 102x102 px)
 *
 * Modos de ejecución:
 *  1. Direct Hardware Canvas (60 FPS VSYNC en SystemUI vía SystemUiQsHook):
 *     Renderiza vectores puros directamente sobre el RecordingCanvas de Jetpack Compose en SystemUI
 *     a 60 FPS reales con interpolación sub-segmento en los 14 LEDs y decaimiento de condensador
 *     en los 3 LEDs inferiores (SIGNAL, OPTIMAL 0dB, PEAK +3dB).
 *  2. Software Bitmap Fallback (para entornos sin LSPosed):
 *     Renderiza sobre Bitmap de 98x98 / 102x102 px.
 */
public class QsVuGaugeRenderer {

    // Dimensiones distintivas O(1) inmunes a premultiplied-alpha y Hardware Bitmaps:
    //  - 98x98 px  = Vúmetro de Graves (VU BASS)
    //  - 102x102 px = Vúmetro de Agudos (VU TREBLE)
    public static final int SIZE_BASS_PX   = 98;
    public static final int SIZE_TREBLE_PX = 102;

    // Geometría idéntica a TachometerView.java (270° de barrido: 135° a 405°)
    public static final float ANGLE_START = 135.0f;
    public static final float ANGLE_END = 405.0f;
    public static final float ANGLE_RANGE = 270.0f;
    public static final float ZERO_VU_NORM = 0.76f;

    // Físicas asimétricas D'Arsonval calibradas para 60 FPS VSYNC
    private static final float ATTACK_STIFFNESS = 480.0f;
    private static final float ATTACK_DAMPING = 27.5f;
    private static final float RELEASE_STIFFNESS = 120.0f;
    private static final float RELEASE_DAMPING = 19.2f;
    private static final float STOP_PIN_RESTITUTION = -0.22f;

    private static final int NUM_LED_SEGMENTS = 14;

    private final boolean isBass;
    private final int bitmapSize;
    private final Bitmap[] bitmaps;
    private final Canvas[] canvases;
    private int bufferIndex = 0;

    private final Paint ledSegmentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint innerRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bottomLedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needleTipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hubPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hubCorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF ledArcRect = new RectF(8.5f, 6.5f, 87.5f, 85.5f); // cx=48, cy=46, r=39.5
    private final RectF innerArcRect = new RectF(17.0f, 15.0f, 79.0f, 77.0f);
    private final Path needlePath = new Path();
    private final Path needleTipPath = new Path();

    // Estado físico del galvanómetro a 60 FPS
    private float currentAngle = ANGLE_START;
    private float targetAngle = ANGLE_START;
    private float velocity = 0.0f;
    private float peakAngle = ANGLE_START;
    private long peakHoldTimeMs = 0L;
    private long lastFrameNano = 0L;
    private float coilRipplePhase = 0.0f;
    private float synthPhase = 0.0f;

    // Luminancia analógica continua (0..1) con decaimiento de condensador para los 3 LEDs inferiores a 60 FPS
    private float led1Glow = 0.0f;
    private float led2Glow = 0.0f;
    private float led3Glow = 0.0f;

    public QsVuGaugeRenderer(boolean isBass) {
        this(isBass, true);
    }

    public QsVuGaugeRenderer(boolean isBass, boolean allocateBitmaps) {
        this.isBass = isBass;
        this.bitmapSize = isBass ? SIZE_BASS_PX : SIZE_TREBLE_PX;
        if (allocateBitmaps) {
            bitmaps = new Bitmap[2];
            canvases = new Canvas[2];
            for (int i = 0; i < 2; i++) {
                bitmaps[i] = Bitmap.createBitmap(bitmapSize, bitmapSize, Bitmap.Config.ARGB_8888);
                canvases[i] = new Canvas(bitmaps[i]);
            }
        } else {
            bitmaps = null;
            canvases = null;
        }

        ledSegmentPaint.setStyle(Paint.Style.STROKE);
        ledSegmentPaint.setStrokeWidth(7.6f);
        ledSegmentPaint.setStrokeCap(Paint.Cap.BUTT);

        innerRingPaint.setStyle(Paint.Style.STROKE);
        innerRingPaint.setStrokeWidth(1.6f);

        tickPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setStrokeCap(Paint.Cap.ROUND);

        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setFakeBoldText(true);
        labelPaint.setTextSize(11.5f);

        bottomLedPaint.setStyle(Paint.Style.FILL);

        needlePaint.setStyle(Paint.Style.FILL);
        needleTipPaint.setStyle(Paint.Style.FILL);
        hubPaint.setStyle(Paint.Style.FILL);
        hubCorePaint.setStyle(Paint.Style.STROKE);
        hubCorePaint.setStrokeWidth(1.8f);
    }

    public void resetTiming() {
        lastFrameNano = System.nanoTime();
    }

    public float getCurrentVuDb() {
        float norm = Math.max(0.0f, Math.min(1.0f, (currentAngle - ANGLE_START) / ANGLE_RANGE));
        if (norm <= ZERO_VU_NORM) {
            return -20.0f + (norm / ZERO_VU_NORM) * 20.0f;
        } else {
            return ((norm - ZERO_VU_NORM) / (1.0f - ZERO_VU_NORM)) * 3.0f;
        }
    }

    public boolean isOverdrive() {
        float norm = (currentAngle - ANGLE_START) / ANGLE_RANGE;
        return norm >= ZERO_VU_NORM;
    }

    /**
     * Resuelve el dB en vivo desde el proceso de la app (TileService / AudioDspService).
     */
    public float resolveLiveDb(Context context, AudioDspService service) {
        float rawDb = -20.0f;
        if (service != null && service.isMasterEnabled()) {
            rawDb = isBass ? service.getCurrentLeftDb() : service.getCurrentRightDb();
        }

        AudioManager am = (context != null) ? (AudioManager) context.getSystemService(Context.AUDIO_SERVICE) : null;
        boolean isMonster = (service != null && (service.isMaxSoundActive() || service.isSonyXb66Active()));
        return applyEnvelopeFallbackIfNeeded(rawDb, am, isMonster, 0.0166f);
    }

    /**
     * Resuelve el dB en vivo a 60 FPS directamente dentro de com.android.systemui (SystemUiQsHook),
     * combinando la telemetría UDP de baja latencia con el estado de AudioManager del sistema.
     */
    public float resolveLiveDbInSystemUi(AudioManager am) {
        float rawDb = -20.0f;
        int flags = QsVuTelemetryBridge.getRxFlags();
        boolean isMonster = (flags & (QsVuTelemetryBridge.FLAG_MAX_SOUND | QsVuTelemetryBridge.FLAG_SONY_XB66)) != 0;

        if (QsVuTelemetryBridge.hasFreshTelemetry() && (flags & QsVuTelemetryBridge.FLAG_MASTER_ENABLED) != 0) {
            rawDb = isBass ? QsVuTelemetryBridge.getRxBassDb() : QsVuTelemetryBridge.getRxTrebleDb();
            // Micro-modulación armónica a 60Hz entre paquetes FFT de 20Hz para movimiento continuo de bobina
            if (rawDb > -18.0f) {
                float fastMod = (float) Math.sin(SystemClock.uptimeMillis() * (isBass ? 0.032 : 0.048)) * 0.85f;
                rawDb += fastMod;
            }
        }

        return applyEnvelopeFallbackIfNeeded(rawDb, am, isMonster, 0.0166f);
    }

    private float applyEnvelopeFallbackIfNeeded(float rawDb, AudioManager am, boolean isMonster, float dtSec) {
        boolean isMusicActive = (am != null && am.isMusicActive());

        // Si el stream está en hardware offload y Visualizer reporta silencio pero hay música activa:
        if (rawDb <= -18.5f && isMusicActive && am != null) {
            int vol = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            int maxVol = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            float volNorm = (float) vol / (float) maxVol;

            float boostOffset = isMonster ? 4.2f : 1.5f;
            synthPhase += dtSec * (isBass ? 5.8f : 8.2f);

            if (isBass) {
                float kickPulse = (float) Math.pow(Math.max(0.0f, Math.sin(synthPhase * 1.45f)), 4.0);
                float subWave = (float) Math.abs(Math.sin(synthPhase * 0.72f)) * 0.55f;
                float env = Math.min(1.15f, (kickPulse * 0.75f + subWave * 0.45f) * (0.45f + 0.65f * volNorm));
                rawDb = -18.0f + env * 18.5f + boostOffset * 0.4f;
            } else {
                float hiHat = (float) Math.pow(Math.max(0.0f, Math.cos(synthPhase * 2.3f)), 3.0);
                float snare = (float) Math.pow(Math.max(0.0f, Math.sin(synthPhase * 1.45f + 1.57f)), 6.0);
                float shimmer = (float) Math.abs(Math.sin(synthPhase * 3.1f)) * 0.35f;
                float env = Math.min(1.12f, (hiHat * 0.50f + snare * 0.55f + shimmer) * (0.42f + 0.62f * volNorm));
                rawDb = -18.0f + env * 17.8f + boostOffset * 0.3f;
            }
        }

        return Math.max(-20.0f, Math.min(3.0f, rawDb));
    }

    /**
     * Renderiza un fotograma en Bitmap (usado como marcador inicial 98x98 / 102x102 para TileService).
     */
    public Bitmap stepAndRender(float liveDb, boolean isActiveState) {
        updateTargetAndStepPhysics(liveDb, isActiveState);
        if (bitmaps == null || canvases == null) return null;

        bufferIndex = (bufferIndex + 1) & 1;
        Bitmap bmp = bitmaps[bufferIndex];
        Canvas canvas = canvases[bufferIndex];
        bmp.eraseColor(Color.TRANSPARENT);

        canvas.save();
        float scaleFactor = bitmapSize / 96.0f;
        canvas.scale(scaleFactor, scaleFactor);
        drawGauge96(canvas, isActiveState);
        canvas.restore();
        return bmp;
    }

    /**
     * Renderiza directamente sobre el Hardware Canvas de SystemUI a 60 FPS VSYNC con resolución nativa.
     */
    public void stepAndDrawDirect(Canvas canvas, float left, float top, float width, float height, float liveDb, boolean isActiveState) {
        updateTargetAndStepPhysics(liveDb, isActiveState);

        canvas.save();
        canvas.translate(left, top);
        canvas.scale(width / 96.0f, height / 96.0f);
        drawGauge96(canvas, isActiveState);
        canvas.restore();
    }

    private void updateTargetAndStepPhysics(float liveDb, boolean isActiveState) {
        float normInput;
        if (liveDb <= -20.0f) {
            normInput = 0.0f;
        } else if (liveDb >= 3.0f) {
            normInput = 1.0f;
        } else if (liveDb <= 0.0f) {
            normInput = ((liveDb + 20.0f) / 20.0f) * ZERO_VU_NORM;
        } else {
            normInput = ZERO_VU_NORM + (liveDb / 3.0f) * (1.0f - ZERO_VU_NORM);
        }
        float shaped = (float) Math.pow(Math.max(0.0f, Math.min(1.0f, normInput * 1.18f)), 0.84);
        targetAngle = ANGLE_START + shaped * ANGLE_RANGE;

        advancePhysics(isActiveState);
    }

    private void advancePhysics(boolean isActiveState) {
        long now = System.nanoTime();
        if (lastFrameNano == 0L) {
            lastFrameNano = now;
            return;
        }
        float dt = (now - lastFrameNano) / 1_000_000_000.0f;
        if (dt <= 0.0005f) {
            dt = 0.0166f;
        } else if (dt > 0.065f) {
            dt = 0.033f;
        }
        lastFrameNano = now;

        // 2 sub-pasos simplécticos a 60 FPS (120Hz de integración interna)
        int steps = 2;
        float subDt = dt / steps;
        coilRipplePhase += dt * (isBass ? 42.0f : 58.0f);

        float inertiaFactor = isBass ? 1.0f : 1.08f;
        float minStop = ANGLE_START - 2.0f;
        float maxStop = ANGLE_END + 3.0f;

        for (int s = 0; s < steps; s++) {
            float disp = currentAngle - targetAngle;
            boolean attacking = targetAngle > currentAngle;
            float k = (attacking ? ATTACK_STIFFNESS : RELEASE_STIFFNESS) * inertiaFactor;
            float c = (attacking ? ATTACK_DAMPING : RELEASE_DAMPING) * (isBass ? 1.0f : 1.04f);

            float norm = (targetAngle - ANGLE_START) / ANGLE_RANGE;
            float ripple = (norm > 0.05f) ? (float) Math.sin(coilRipplePhase) * norm * 24.0f : 0.0f;

            float accel = -k * disp - c * velocity + ripple;
            velocity += accel * subDt;
            currentAngle += velocity * subDt;

            if (currentAngle < minStop) {
                currentAngle = minStop;
                velocity *= STOP_PIN_RESTITUTION;
            } else if (currentAngle > maxStop) {
                currentAngle = maxStop;
                velocity *= STOP_PIN_RESTITUTION;
            }
        }

        // Peak-Hold (550ms hold + caída suave a 55°/s a 60 FPS)
        long nowMs = SystemClock.uptimeMillis();
        if (currentAngle >= peakAngle) {
            peakAngle = Math.min(ANGLE_END, currentAngle);
            peakHoldTimeMs = nowMs;
        } else if (nowMs - peakHoldTimeMs > 550L) {
            peakAngle = Math.max(ANGLE_START, peakAngle - 55.0f * dt);
        }

        // Decaimiento analógico de condensador para los 3 LEDs inferiores a 60 FPS
        float normLevel = Math.max(0.0f, Math.min(1.0f, (currentAngle - ANGLE_START) / ANGLE_RANGE));
        float targetLed1 = (normLevel >= 0.14f) ? 1.0f : (isActiveState ? 0.65f : 0.0f);
        float targetLed2 = (normLevel >= 0.46f) ? Math.min(1.0f, (normLevel - 0.42f) * 3.2f) : 0.0f;
        float targetLed3 = (normLevel >= ZERO_VU_NORM - 0.04f) ? Math.min(1.0f, (normLevel - 0.70f) * 4.5f) : 0.0f;

        led1Glow = stepCapacitorGlow(led1Glow, targetLed1, dt);
        led2Glow = stepCapacitorGlow(led2Glow, targetLed2, dt);
        led3Glow = stepCapacitorGlow(led3Glow, targetLed3, dt);
    }

    private static float stepCapacitorGlow(float current, float target, float dt) {
        float rate = (target > current) ? 26.0f : 6.8f; // Encendido instantáneo, persistencia suave de fósforo
        float next = current + (target - current) * Math.min(1.0f, rate * dt);
        return Math.max(0.0f, Math.min(1.0f, next));
    }

    private void drawGauge96(Canvas canvas, boolean isActiveState) {
        final float cx = 48.0f;
        final float cy = 46.0f;
        final float normLevel = Math.max(0.0f, Math.min(1.0f, (currentAngle - ANGLE_START) / ANGLE_RANGE));
        final float peakNorm = Math.max(0.0f, Math.min(1.0f, (peakAngle - ANGLE_START) / ANGLE_RANGE));

        // 1. ANILLO EXTERIOR DE 14 SEGMENTOS LED CON INTERPOLACIÓN SUB-SEGMENTO A 60 FPS
        float stepDeg = ANGLE_RANGE / NUM_LED_SEGMENTS; // 19.28° por paso
        float segSweep = stepDeg - 4.2f;                // 15.08° bloque LED + 4.2° separación
        float exactSegments = normLevel * NUM_LED_SEGMENTS;
        int fullLitSegments = (int) Math.floor(exactSegments);
        float leadingFrac = exactSegments - fullLitSegments;
        int peakSegment = Math.min(NUM_LED_SEGMENTS - 1, (int) (peakNorm * NUM_LED_SEGMENTS));
        boolean showPeakSeg = (peakNorm > 0.08f && peakSegment > fullLitSegments);

        for (int i = 0; i < NUM_LED_SEGMENTS; i++) {
            float segStart = ANGLE_START + i * stepDeg + 2.1f;

            int rgbColor;
            if (isBass) {
                if (i >= 11) rgbColor = 0x00FF2B22;      // Zona Roja (+0..+3 dB)
                else if (i >= 8) rgbColor = 0x00FFB800;  // Ámbar Philips (-5..0 dB)
                else rgbColor = 0x0030D158;              // Verde Esmeralda (-20..-5 dB)
            } else {
                if (i >= 11) rgbColor = 0x00FF375F;      // Rojo-Magenta Brillo Pico (+0..+3 dB)
                else if (i >= 8) rgbColor = 0x0032ADE6;  // Azul Eléctrico (-5..0 dB)
                else rgbColor = 0x0000E5FF;              // Cian Super-Tweeter (-20..-5 dB)
            }

            int alpha;
            if (showPeakSeg && i == peakSegment) {
                alpha = 255;
            } else if (i < fullLitSegments) {
                alpha = 248;
            } else if (i == fullLitSegments && leadingFrac > 0.05f) {
                // Transición suave continua del LED en el frente de onda de la aguja a 60 FPS
                alpha = 72 + Math.round(leadingFrac * 176.0f);
            } else {
                alpha = 72;
            }

            ledSegmentPaint.setColor((alpha << 24) | rgbColor);
            canvas.drawArc(ledArcRect, segStart, segSweep, false, ledSegmentPaint);
        }

        // 2. ESCALA INTERNA CON MARCAS GRADUADAS (0%, 25%, 50%, 75%, 100%)
        int baseScaleRgb = isBass ? 0x00FFB800 : 0x0000E5FF;
        innerRingPaint.setColor((90 << 24) | baseScaleRgb);
        canvas.drawArc(innerArcRect, ANGLE_START, ANGLE_RANGE, false, innerRingPaint);

        for (int t = 0; t <= 8; t++) {
            float frac = t / 8.0f;
            float deg = ANGLE_START + frac * ANGLE_RANGE;
            double rad = Math.toRadians(deg);
            float cos = (float) Math.cos(rad);
            float sin = (float) Math.sin(rad);
            boolean major = (t % 2 == 0);
            float rIn = major ? 25.5f : 27.8f;
            float rOut = 31.0f;
            tickPaint.setStrokeWidth(major ? 2.2f : 1.4f);
            int tickRgb = (frac >= ZERO_VU_NORM) ? 0x00FF2B22 : (isBass ? 0x00FFF3E0 : 0x00E0F7FA);
            int tickAlpha = major ? 175 : 110;
            tickPaint.setColor((tickAlpha << 24) | tickRgb);
            canvas.drawLine(cx + rIn * cos, cy + rIn * sin, cx + rOut * cos, cy + rOut * sin, tickPaint);
        }

        // 3. RÓTULO DE CANAL ("BASS" o "TREB")
        int labelRgb = isBass ? 0x00FFD180 : 0x0080DEEA;
        labelPaint.setColor((235 << 24) | labelRgb);
        canvas.drawText(isBass ? "BASS" : "TREB", cx, cy + 21.5f, labelPaint);

        // 4. TRES INDICADORES LED INFERIORES ANIMADOS A 60 FPS (SIGNAL, OPTIMAL 0dB, PEAK +3dB)
        float ledY = 81.5f;
        float ledSpacing = 15.5f;
        float ledR = 4.2f;

        // LED 1 (Izquierdo: SIGNAL)
        int led1Rgb = isBass ? 0x00FF7315 : 0x0000E5FF;
        if (led1Glow > 0.08f) {
            int haloAlpha = Math.round(led1Glow * 125.0f);
            bottomLedPaint.setColor((haloAlpha << 24) | led1Rgb);
            canvas.drawCircle(cx - ledSpacing, ledY, ledR * (1.25f + 0.55f * led1Glow), bottomLedPaint);
        }
        int core1Alpha = 68 + Math.round(led1Glow * 187.0f);
        bottomLedPaint.setColor((core1Alpha << 24) | led1Rgb);
        canvas.drawCircle(cx - ledSpacing, ledY, 3.4f + 0.8f * led1Glow, bottomLedPaint);

        // LED 2 (Central: OPTIMAL 0dB)
        int led2Rgb = isBass ? 0x00FFB800 : 0x0032ADE6;
        if (led2Glow > 0.08f) {
            int haloAlpha = Math.round(led2Glow * 130.0f);
            bottomLedPaint.setColor((haloAlpha << 24) | led2Rgb);
            canvas.drawCircle(cx, ledY, ledR * (1.25f + 0.55f * led2Glow), bottomLedPaint);
        }
        int core2Alpha = 68 + Math.round(led2Glow * 187.0f);
        bottomLedPaint.setColor((core2Alpha << 24) | led2Rgb);
        canvas.drawCircle(cx, ledY, 3.4f + 0.8f * led2Glow, bottomLedPaint);

        // LED 3 (Derecho: PEAK OVERLOAD +3dB)
        int led3Rgb = 0x00FF2B22;
        if (led3Glow > 0.08f) {
            int haloAlpha = Math.round(led3Glow * 155.0f);
            bottomLedPaint.setColor((haloAlpha << 24) | led3Rgb);
            canvas.drawCircle(cx + ledSpacing, ledY, ledR * (1.30f + 0.70f * led3Glow), bottomLedPaint);
        }
        int core3Alpha = 68 + Math.round(led3Glow * 187.0f);
        bottomLedPaint.setColor((core3Alpha << 24) | led3Rgb);
        canvas.drawCircle(cx + ledSpacing, ledY, 3.4f + 1.1f * led3Glow, bottomLedPaint);

        // 5. AGUJA GALVANOMÉTRICA CON CONTRAPESO Y PUNTA DE TRITIO (alpha = 255)
        float needleLen = 33.5f;
        float tailLen = 8.0f;
        float baseW = 2.6f;
        float tailW = 3.4f;
        float tipW = 0.9f;

        needlePath.reset();
        needlePath.moveTo(-tailLen, -tailW * 0.6f);
        needlePath.lineTo(0f, -baseW);
        needlePath.lineTo(needleLen, -tipW);
        needlePath.lineTo(needleLen, tipW);
        needlePath.lineTo(0f, baseW);
        needlePath.lineTo(-tailLen, tailW * 0.6f);
        needlePath.close();

        float tipStart = needleLen * 0.65f;
        needleTipPath.reset();
        needleTipPath.moveTo(tipStart, -baseW * 0.5f);
        needleTipPath.lineTo(needleLen, -tipW);
        needleTipPath.lineTo(needleLen, tipW);
        needleTipPath.lineTo(tipStart, baseW * 0.5f);
        needleTipPath.close();

        needlePaint.setColor(isBass ? 0xFFFF481F : 0xFF00E5FF);
        needleTipPaint.setColor(isBass ? 0xFFFFF3E0 : 0xFFFFFFFF);

        canvas.save();
        canvas.translate(cx, cy);
        canvas.rotate(currentAngle);
        canvas.drawPath(needlePath, needlePaint);
        canvas.drawPath(needleTipPath, needleTipPaint);
        canvas.restore();

        // 6. DOMO CENTRAL DE PIVOTE MECANIZADO
        hubPaint.setColor(isBass ? 0xFFFF7315 : 0xFF00B8D4);
        canvas.drawCircle(cx, cy, 5.8f, hubPaint);
        hubCorePaint.setColor(0xFFFFF3E0);
        canvas.drawCircle(cx, cy, 2.4f, hubCorePaint);
    }

    public void release() {
        if (bitmaps != null) {
            for (int i = 0; i < bitmaps.length; i++) {
                if (bitmaps[i] != null && !bitmaps[i].isRecycled()) {
                    bitmaps[i].recycle();
                }
            }
        }
    }
}
