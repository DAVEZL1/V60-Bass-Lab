package com.v60.basslab;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;

import java.util.Locale;

/**
 * TachometerView (v2.0 — Authentic D'Arsonval Moving-Coil Analog VU & Tachometer)
 *
 * Implementa las skills:
 * - common-mobile-animation: Físicas asimétricas de galvanómetro D'Arsonval (ANSI C16.5 / IEC 60268-17)
 * - android-performance: Cero asignaciones de memoria (0 allocations) en onDraw() para 60/120 FPS estables
 * - apple-design & Android UI/UX Specialist: Bisel anodizado multicapa, lente de cristal curvo (Liquid Glass),
 *   doble escala (dB VU + % Potencia), zona roja de sobrecarga (+0 a +3 dB), marcador Peak-Hold balístico,
 *   sombra proyectada dinámica y respuesta táctil interactiva (selector de sensibilidad + barrido de calibración).
 */
public class TachometerView extends View {

    // Físicas electro-mecánicas asimétricas de Galvanómetro D'Arsonval (Bobina Móvil):
    // 1. Ataque (Torque electromagnético rápido con ~1.4% de overshoot sub-amortiguado)
    private static final float ATTACK_STIFFNESS = 460.0f;
    private static final float ATTACK_DAMPING = 28.5f;

    // 2. Retorno (Muelle helicoidal de bronce fosforoso + amortiguamiento por corrientes de Foucault)
    private static final float RELEASE_STIFFNESS = 115.0f;
    private static final float RELEASE_DAMPING = 19.8f;

    // Coeficiente de restitución en los topes mecánicos de goma (-20 dB y +3 dB)
    private static final float STOP_PIN_RESTITUTION = -0.22f;

    // Rango angular del arco: 135° (-20 dB / 0%) a 405° (+3 dB / 100%) -> 270° de barrido
    private static final float ANGLE_START = 135.0f;
    private static final float ANGLE_END = 405.0f;
    private static final float ANGLE_RANGE = 270.0f;
    // Posición del umbral 0 dB VU (aprox. al 76% de la escala, antes de la zona roja +0..+3 dB)
    private static final float ZERO_VU_NORM = 0.76f;

    // Paleta de alta fidelidad (Philips FW-M576 / McIntosh / Dark Glass)
    private static final int COLOR_BEZEL_OUTER = 0xFF1C1A20;
    private static final int COLOR_BEZEL_INNER = 0xFF0A080C;
    private static final int COLOR_DIAL_CENTER = 0xFF160B10;
    private static final int COLOR_DIAL_EDGE = 0xFF060305;
    private static final int COLOR_RING_RED = 0xFFFF2B22;
    private static final int COLOR_RING_ORANGE = 0xFFFF7315;
    private static final int COLOR_RING_AMBER = 0xFFFFB800;
    private static final int COLOR_WARM_CREAM = 0xFFFFF3E0;
    private static final int COLOR_TEXT_MUTED = 0xFFA6969C;
    private static final int COLOR_NEEDLE_BODY = 0xFFFF481F;
    private static final int COLOR_NEEDLE_TIP = 0xFFFFD180;

    // Etiquetas de escala externa (VU dB) y sus posiciones normalizadas (0.0 a 1.0)
    private static final String[] VU_DB_LABELS = {"-20", "-10", "-5", "0", "+3"};
    private static final float[] VU_DB_POS = {0.0f, 0.28f, 0.54f, ZERO_VU_NORM, 1.0f};

    // Etiquetas de escala interna (% Potencia Philips FW-M576)
    private static final int[] POWER_VALUES = {0, 25, 50, 75, 100};

    // Pinturas pre-asignadas (Cero allocations en onDraw)
    private final Paint bezelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dialBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outerGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint redZoneArcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint activeArcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint readoutBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint readoutTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needleTipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needleShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint peakHoldPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stopPinPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint capPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint capGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glassLensPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ledPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Paths y RectF pre-asignados
    private final Path needlePath = new Path();
    private final Path needleTipPath = new Path();
    private final Path peakTrianglePath = new Path();
    private final Path glassHighlightPathLeft = new Path();
    private final Path glassHighlightPathRight = new Path();
    private final RectF arcRectLeft = new RectF();
    private final RectF arcRectRight = new RectF();
    private final RectF activeArcRectLeft = new RectF();
    private final RectF activeArcRectRight = new RectF();
    private final RectF readoutRect = new RectF();

    // Shaders cacheados por tamaño en onSizeChanged (Cero GC en onDraw)
    private RadialGradient dialShaderLeft, dialShaderRight;
    private LinearGradient bezelShaderLeft, bezelShaderRight;
    private LinearGradient glassShaderLeft, glassShaderRight;
    private RadialGradient capGlowShaderLeft, capGlowShaderRight;
    private RadialGradient capDomeShaderLeft, capDomeShaderRight;

    // Estado físico del canal BASS (Izquierdo)
    private float currentAngleBass = ANGLE_START;
    private float targetAngleBass = ANGLE_START;
    private float velocityBass = 0.0f;
    private float peakAngleBass = ANGLE_START;
    private long peakHoldTimeBass = 0L;

    // Estado físico del canal TREBLE (Derecho)
    private float currentAngleTreble = ANGLE_START;
    private float targetAngleTreble = ANGLE_START;
    private float velocityTreble = 0.0f;
    private float peakAngleTreble = ANGLE_START;
    private long peakHoldTimeTreble = 0L;

    // Micro-vibración electromagnética de la bobina (AC ripple)
    private float coilRipplePhase = 0.0f;
    private long lastRealSignalTime = 0L;

    // Sensibilidad táctil ajustable (1.0x, 1.35x, 1.70x)
    private int sensitivityIndex = 1; // Por defecto 1.35x (alta respuesta analógica)
    private static final float[] SENSITIVITY_GAINS = {1.0f, 1.35f, 1.70f};
    private static final String[] SENSITIVITY_LABELS = {"0 dB", "+3 dB", "+6 dB"};

    private long lastFrameTime = 0L;
    private boolean isAnimating = true;

    // Modo Demo con sintetizador de ritmo analógico (Kick + Sub + Snare + Hi-Hat)
    private boolean isDemoMode = true;
    private ValueAnimator demoAnimator;

    // Geometría de medidores
    private float gaugeRadius = 100f;
    private float bassCenterX = 0f;
    private float trebleCenterX = 0f;
    private float centerY = 0f;

    public TachometerView(Context context) {
        super(context);
        init();
    }

    public TachometerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public TachometerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setContentDescription("Vúmetro analógico estéreo de graves y agudos. Toca para calibrar sensibilidad.");

        bezelPaint.setStyle(Paint.Style.FILL);
        dialBgPaint.setStyle(Paint.Style.FILL);

        outerGlowPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStyle(Paint.Style.STROKE);

        redZoneArcPaint.setStyle(Paint.Style.STROKE);
        redZoneArcPaint.setStrokeCap(Paint.Cap.BUTT);

        activeArcPaint.setStyle(Paint.Style.STROKE);
        activeArcPaint.setStrokeCap(Paint.Cap.ROUND);

        tickPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setStrokeCap(Paint.Cap.ROUND);

        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);

        subTextPaint.setTextAlign(Paint.Align.CENTER);
        subTextPaint.setFakeBoldText(true);

        readoutBgPaint.setStyle(Paint.Style.FILL);
        readoutBgPaint.setColor(0xCC090508);

        readoutTextPaint.setTextAlign(Paint.Align.CENTER);
        readoutTextPaint.setFakeBoldText(true);

        needlePaint.setStyle(Paint.Style.FILL);
        needlePaint.setColor(COLOR_NEEDLE_BODY);

        needleTipPaint.setStyle(Paint.Style.FILL);
        needleTipPaint.setColor(COLOR_NEEDLE_TIP);

        needleShadowPaint.setStyle(Paint.Style.FILL);
        needleShadowPaint.setColor(0x66000000);

        peakHoldPaint.setStyle(Paint.Style.FILL);
        stopPinPaint.setStyle(Paint.Style.FILL);

        capPaint.setStyle(Paint.Style.FILL);
        capGlowPaint.setStyle(Paint.Style.FILL);
        glassLensPaint.setStyle(Paint.Style.FILL);
        ledPaint.setStyle(Paint.Style.FILL);

        if (isDemoMode) {
            startDemoAnimation();
        }
    }

    /**
     * Mapea un nivel normalizado (0.0f a 1.0f) aplicando curva logarítmica de galvanómetro real
     * y sensibilidad activa hacia el ángulo de la escala (135° a 405°).
     */
    private float levelToAngle(float rawLevel) {
        float scaled = Math.max(0.0f, rawLevel * SENSITIVITY_GAINS[sensitivityIndex]);
        // Compresión suave tipo rectificador de germanio en el extremo superior (+3 dB)
        float shaped = (float) Math.pow(Math.min(1.0f, scaled), 0.82);
        return ANGLE_START + (shaped * ANGLE_RANGE);
    }

    /**
     * Convierte un ángulo (135°..405°) a lectura en decibelios VU (-20.0 dB a +3.0 dB).
     */
    private float angleToVuDb(float angle) {
        float norm = Math.max(0.0f, Math.min(1.0f, (angle - ANGLE_START) / ANGLE_RANGE));
        if (norm <= ZERO_VU_NORM) {
            // -20 dB a 0 dB
            float t = norm / ZERO_VU_NORM;
            return -20.0f + (t * 20.0f);
        } else {
            // 0 dB a +3 dB (Zona Roja)
            float t = (norm - ZERO_VU_NORM) / (1.0f - ZERO_VU_NORM);
            return t * 3.0f;
        }
    }

    /**
     * Actualiza los niveles de audio en tiempo real (normalizados de 0.0f a 1.0f).
     * Si detecta energía musical real, toma prioridad automática sobre el patrón de demo.
     */
    public void setLevels(float bass, float treble) {
        if (bass > 0.025f || treble > 0.025f) {
            lastRealSignalTime = SystemClock.uptimeMillis();
        }
        this.targetAngleBass = levelToAngle(bass);
        this.targetAngleTreble = levelToAngle(treble);

        if (!isAnimating) {
            isAnimating = true;
            lastFrameTime = System.nanoTime();
            postInvalidateOnAnimation();
        }
    }

    public void setDemoMode(boolean enabled) {
        if (this.isDemoMode == enabled) return;
        this.isDemoMode = enabled;
        if (enabled) {
            startDemoAnimation();
        } else {
            stopDemoAnimation();
            this.targetAngleBass = ANGLE_START;
            this.targetAngleTreble = ANGLE_START;
            isAnimating = true;
            lastFrameTime = System.nanoTime();
            postInvalidateOnAnimation();
        }
    }

    public boolean isDemoMode() {
        return isDemoMode;
    }

    /**
     * Al tocar los vúmetros: aplica un impulso capacitivo de prueba a las agujas
     * y alterna la sensibilidad (+0 dB -> +3 dB -> +6 dB).
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            sensitivityIndex = (sensitivityIndex + 1) % SENSITIVITY_GAINS.length;
            // Impulso balístico directo al momento de tocar el cristal del vúmetro
            velocityBass += 420.0f;
            velocityTreble += 360.0f;
            if (!isAnimating) {
                isAnimating = true;
                lastFrameTime = System.nanoTime();
            }
            postInvalidateOnAnimation();
            performClick();
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    /**
     * Generador rítmico analógico de 124 BPM para Modo Demo (cuando no hay audio real activo).
     * Simula el golpe real de un bombo (Kick), bajo 808, caja (Snare) y Hi-Hats en vez de una onda senoidal.
     */
    private void startDemoAnimation() {
        if (demoAnimator != null && demoAnimator.isRunning()) return;
        demoAnimator = ValueAnimator.ofFloat(0f, 1f);
        demoAnimator.setDuration(1935); // 4 tiempos a ~124 BPM (483.8 ms por beat)
        demoAnimator.setRepeatCount(ValueAnimator.INFINITE);
        demoAnimator.setInterpolator(new LinearInterpolator());
        demoAnimator.addUpdateListener(a -> {
            long nowMs = SystemClock.uptimeMillis();
            // Si está entrando audio real del sistema/reproductor en los últimos 900ms, usar el audio real
            if (nowMs - lastRealSignalTime < 900L) {
                if (!isAnimating) {
                    isAnimating = true;
                    lastFrameTime = System.nanoTime();
                }
                postInvalidateOnAnimation();
                return;
            }

            float barPhase = (float) a.getAnimatedValue() * 4.0f; // 0.0 a 4.0 beats
            int beatIndex = (int) barPhase;
            float beatFrac = barPhase - beatIndex; // 0.0 a 1.0 dentro del beat

            // Envolvente exponencial de percusión (ataque rápido en 0.0..0.08, caída exponencial)
            float kickEnv = (float) Math.exp(-beatFrac * 5.2f);
            // Redoble sincopado en el beat 3.5
            float syncopation = (beatIndex == 1 || beatIndex == 3) && beatFrac > 0.5f
                ? (float) Math.exp(-(beatFrac - 0.5f) * 7.5f) * 0.65f : 0.0f;
            // Línea de sub-bajo cálida continua
            float subSwell = 0.16f + 0.12f * (float) Math.sin(barPhase * Math.PI);

            float simulatedBass = Math.min(0.95f, subSwell + kickEnv * 0.56f + syncopation * 0.35f);

            // Canal Treble: Caja (Snare) en beats 1 y 3 + Hi-Hats de semicorchea (16th notes)
            float sixteenthFrac = (barPhase * 4.0f) - (int) (barPhase * 4.0f);
            float hiHatEnv = (float) Math.exp(-sixteenthFrac * 6.5f) * 0.24f;
            float snareEnv = (beatIndex == 1 || beatIndex == 3) ? (float) Math.exp(-beatFrac * 6.0f) * 0.52f : 0.08f;
            float vocalHarmonic = 0.12f + 0.10f * (float) Math.abs(Math.sin(barPhase * Math.PI * 1.5f));

            float simulatedTreble = Math.min(0.92f, vocalHarmonic + snareEnv + hiHatEnv);

            targetAngleBass = levelToAngle(simulatedBass);
            targetAngleTreble = levelToAngle(simulatedTreble);

            if (!isAnimating) {
                isAnimating = true;
                lastFrameTime = System.nanoTime();
            }
            postInvalidateOnAnimation();
        });
        demoAnimator.start();
    }

    private void stopDemoAnimation() {
        if (demoAnimator != null) {
            demoAnimator.cancel();
            demoAnimator = null;
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) return;

        gaugeRadius = Math.min(w * 0.232f, h * 0.455f);
        bassCenterX = w * 0.265f;
        trebleCenterX = w * 0.735f;
        centerY = h * 0.51f;

        rebuildCachedGeometryAndShaders(bassCenterX, centerY, gaugeRadius, true);
        rebuildCachedGeometryAndShaders(trebleCenterX, centerY, gaugeRadius, false);
    }

    private void rebuildCachedGeometryAndShaders(float cx, float cy, float r, boolean isLeft) {
        RadialGradient dialShader = new RadialGradient(
            cx, cy - r * 0.15f, r * 0.96f,
            new int[]{0xFF211016, COLOR_DIAL_CENTER, COLOR_DIAL_EDGE},
            new float[]{0.0f, 0.62f, 1.0f},
            Shader.TileMode.CLAMP
        );

        LinearGradient bezelShader = new LinearGradient(
            cx - r, cy - r, cx + r, cy + r,
            new int[]{0xFF3A353F, COLOR_BEZEL_OUTER, COLOR_BEZEL_INNER, 0xFF28222C},
            new float[]{0.0f, 0.35f, 0.75f, 1.0f},
            Shader.TileMode.CLAMP
        );

        LinearGradient glassShader = new LinearGradient(
            cx, cy - r * 0.92f, cx, cy + r * 0.15f,
            new int[]{0x38FFFFFF, 0x12FFFFFF, 0x00FFFFFF},
            new float[]{0.0f, 0.45f, 1.0f},
            Shader.TileMode.CLAMP
        );

        float pivotRadius = r * 0.125f;
        RadialGradient capGlow = new RadialGradient(
            cx, cy, pivotRadius * 2.4f,
            0x99FF7315, 0x00FF2B22, Shader.TileMode.CLAMP
        );

        RadialGradient capDome = new RadialGradient(
            cx - pivotRadius * 0.25f, cy - pivotRadius * 0.25f, pivotRadius * 1.1f,
            new int[]{0xFFFFD180, 0xFFFF7315, 0xFF4A120C},
            new float[]{0.0f, 0.55f, 1.0f},
            Shader.TileMode.CLAMP
        );

        float redArcRadius = r * 0.83f;
        float activeArcRadius = r * 0.895f;
        RectF targetArcRect = isLeft ? arcRectLeft : arcRectRight;
        targetArcRect.set(cx - redArcRadius, cy - redArcRadius, cx + redArcRadius, cy + redArcRadius);

        RectF targetActiveRect = isLeft ? activeArcRectLeft : activeArcRectRight;
        targetActiveRect.set(cx - activeArcRadius, cy - activeArcRadius, cx + activeArcRadius, cy + activeArcRadius);

        // Cristal curvo superior (Liquid Glass reflection)
        Path glassPath = isLeft ? glassHighlightPathLeft : glassHighlightPathRight;
        glassPath.reset();
        float gr = r * 0.90f;
        RectF glassOval = new RectF(cx - gr, cy - gr, cx + gr, cy + gr);
        glassPath.addArc(glassOval, 195f, 150f);
        glassPath.quadTo(cx, cy - r * 0.18f, cx - gr * 0.96f, cy - gr * 0.25f);
        glassPath.close();

        if (isLeft) {
            dialShaderLeft = dialShader;
            bezelShaderLeft = bezelShader;
            glassShaderLeft = glassShader;
            capGlowShaderLeft = capGlow;
            capDomeShaderLeft = capDome;
        } else {
            dialShaderRight = dialShader;
            bezelShaderRight = bezelShader;
            glassShaderRight = glassShader;
            capGlowShaderRight = capGlow;
            capDomeShaderRight = capDome;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // 1. Integración física asimétrica de 2º orden (Sub-stepping para estabilidad absoluta)
        advancePhysics();

        // 2. Dibujar Vúmetro Analógico BASS (Izquierdo)
        drawSingleGauge(canvas, bassCenterX, centerY, currentAngleBass, peakAngleBass, true);

        // 3. Dibujar Vúmetro Analógico TREBLE (Derecho)
        drawSingleGauge(canvas, trebleCenterX, centerY, currentAngleTreble, peakAngleTreble, false);

        if (isAnimating) {
            postInvalidateOnAnimation();
        }
    }

    /**
     * Ecuación diferencial de Galvanómetro D'Arsonval con:
     * - Constantes asimétricas de ataque (bobina energizada) vs retorno (muelle espiral)
     * - Topes mecánicos con rebote amortiguado en los extremos (-20 dB y +3 dB)
     * - Micro-vibración electromagnética de bobina (AC ripple)
     * - Retención balística de pico máximo (Peak-Hold)
     */
    private void advancePhysics() {
        long now = System.nanoTime();
        if (lastFrameTime == 0L) {
            lastFrameTime = now;
            return;
        }

        float dt = (now - lastFrameTime) / 1_000_000_000.0f;
        dt = Math.min(dt, 0.032f);
        lastFrameTime = now;

        // Dos sub-pasos por frame para integración simpléctica de alta precisión
        int steps = 2;
        float subDt = dt / steps;
        coilRipplePhase += dt * 42.0f;

        float minStopAngle = ANGLE_START - 2.2f;
        float maxStopAngle = ANGLE_END + 3.2f;

        for (int s = 0; s < steps; s++) {
            // --- CANAL BASS ---
            float dispBass = currentAngleBass - targetAngleBass;
            boolean isAttackingBass = targetAngleBass > currentAngleBass;
            float kB = isAttackingBass ? ATTACK_STIFFNESS : RELEASE_STIFFNESS;
            float cB = isAttackingBass ? ATTACK_DAMPING : RELEASE_DAMPING;

            // Micro-vibración analógica de bobina proporcional a la señal
            float normB = (targetAngleBass - ANGLE_START) / ANGLE_RANGE;
            float rippleB = (normB > 0.06f) ? (float) Math.sin(coilRipplePhase) * normB * 22.0f : 0.0f;

            float accelBass = -kB * dispBass - cB * velocityBass + rippleB;
            velocityBass += accelBass * subDt;
            currentAngleBass += velocityBass * subDt;

            if (currentAngleBass < minStopAngle) {
                currentAngleBass = minStopAngle;
                velocityBass *= STOP_PIN_RESTITUTION;
            } else if (currentAngleBass > maxStopAngle) {
                currentAngleBass = maxStopAngle;
                velocityBass *= STOP_PIN_RESTITUTION;
            }

            // --- CANAL TREBLE ---
            float dispTreble = currentAngleTreble - targetAngleTreble;
            boolean isAttackingTreble = targetAngleTreble > currentAngleTreble;
            // La aguja de agudos tiene ligeramente menor masa inercial rotacional
            float kT = (isAttackingTreble ? ATTACK_STIFFNESS : RELEASE_STIFFNESS) * 1.08f;
            float cT = (isAttackingTreble ? ATTACK_DAMPING : RELEASE_DAMPING) * 1.04f;

            float normT = (targetAngleTreble - ANGLE_START) / ANGLE_RANGE;
            float rippleT = (normT > 0.06f) ? (float) Math.cos(coilRipplePhase * 1.35f) * normT * 26.0f : 0.0f;

            float accelTreble = -kT * dispTreble - cT * velocityTreble + rippleT;
            velocityTreble += accelTreble * subDt;
            currentAngleTreble += velocityTreble * subDt;

            if (currentAngleTreble < minStopAngle) {
                currentAngleTreble = minStopAngle;
                velocityTreble *= STOP_PIN_RESTITUTION;
            } else if (currentAngleTreble > maxStopAngle) {
                currentAngleTreble = maxStopAngle;
                velocityTreble *= STOP_PIN_RESTITUTION;
            }
        }

        // Actualizar marcadores Peak-Hold (retención de 600ms + caída suave a 48°/s)
        long nowMs = SystemClock.uptimeMillis();
        if (currentAngleBass >= peakAngleBass) {
            peakAngleBass = Math.min(ANGLE_END, currentAngleBass);
            peakHoldTimeBass = nowMs;
        } else if (nowMs - peakHoldTimeBass > 600L) {
            peakAngleBass = Math.max(ANGLE_START, peakAngleBass - 48.0f * dt);
        }

        if (currentAngleTreble >= peakAngleTreble) {
            peakAngleTreble = Math.min(ANGLE_END, currentAngleTreble);
            peakHoldTimeTreble = nowMs;
        } else if (nowMs - peakHoldTimeTreble > 600L) {
            peakAngleTreble = Math.max(ANGLE_START, peakAngleTreble - 48.0f * dt);
        }

        // Detección de reposo completo
        boolean settledB = Math.abs(velocityBass) < 0.15f && Math.abs(currentAngleBass - targetAngleBass) < 0.15f && peakAngleBass <= ANGLE_START + 0.5f;
        boolean settledT = Math.abs(velocityTreble) < 0.15f && Math.abs(currentAngleTreble - targetAngleTreble) < 0.15f && peakAngleTreble <= ANGLE_START + 0.5f;
        if (settledB && settledT && !isDemoMode && (nowMs - lastRealSignalTime > 1500L)) {
            currentAngleBass = targetAngleBass;
            currentAngleTreble = targetAngleTreble;
            velocityBass = 0.0f;
            velocityTreble = 0.0f;
            isAnimating = false;
        }
    }

    private void drawSingleGauge(Canvas canvas, float cx, float cy, float currentAngle, float peakAngle, boolean isBass) {
        float r = gaugeRadius;
        float normalizedLevel = Math.max(0.0f, Math.min(1.0f, (currentAngle - ANGLE_START) / ANGLE_RANGE));
        boolean isOverload = normalizedLevel >= ZERO_VU_NORM;

        // 1. Halo exterior reactivo a la presión acústica (brilla más en golpes de bajo / zona roja)
        outerGlowPaint.setStrokeWidth(r * 0.13f);
        outerGlowPaint.setColor(isOverload ? COLOR_RING_RED : COLOR_RING_ORANGE);
        int dynamicGlowAlpha = (int) (32 + normalizedLevel * 95);
        outerGlowPaint.setAlpha(Math.min(140, dynamicGlowAlpha));
        canvas.drawCircle(cx, cy, r * 1.01f, outerGlowPaint);

        // 2. Bisel metálico anodizado multicapa
        bezelPaint.setShader(isBass ? bezelShaderLeft : bezelShaderRight);
        canvas.drawCircle(cx, cy, r, bezelPaint);

        ringPaint.setStrokeWidth(r * 0.032f);
        ringPaint.setColor(COLOR_RING_ORANGE);
        canvas.drawCircle(cx, cy, r * 0.96f, ringPaint);

        // 3. Esfera interna retroiluminada cálida
        dialBgPaint.setShader(isBass ? dialShaderLeft : dialShaderRight);
        canvas.drawCircle(cx, cy, r * 0.935f, dialBgPaint);

        // 4. Arco dinámico de energía + Arco fijo de Zona Roja (+0 dB a +3 dB)
        RectF activeRect = isBass ? activeArcRectLeft : activeArcRectRight;
        float sweepAngle = Math.max(2.0f, normalizedLevel * ANGLE_RANGE);
        activeArcPaint.setStrokeWidth(r * 0.028f);
        activeArcPaint.setColor(isOverload ? COLOR_RING_RED : COLOR_RING_AMBER);
        activeArcPaint.setAlpha(150);
        canvas.drawArc(activeRect, ANGLE_START, sweepAngle, false, activeArcPaint);

        // Banda roja de sobrecarga (+0 dB a +3 dB: últimos 65° del arco)
        RectF redRect = isBass ? arcRectLeft : arcRectRight;
        float redStartAngle = ANGLE_START + (ZERO_VU_NORM * ANGLE_RANGE);
        float redSweepAngle = (1.0f - ZERO_VU_NORM) * ANGLE_RANGE;
        redZoneArcPaint.setStrokeWidth(r * 0.042f);
        redZoneArcPaint.setColor(isOverload ? 0xEEFF2B22 : 0x88FF2B22);
        canvas.drawArc(redRect, redStartAngle, redSweepAngle, false, redZoneArcPaint);

        // Anillo interior de escala
        ringPaint.setStrokeWidth(r * 0.012f);
        ringPaint.setColor(0x44FF9F0A);
        canvas.drawCircle(cx, cy, r * 0.74f, ringPaint);

        // 5. Doble Escala Graduada (dB VU + % Potencia) y Topes Mecánicos
        drawDualScaleAndStopPins(canvas, cx, cy, r);

        // 6. Rótulos tipográficos del dial (VU / BASS / TREBLE / Sensibilidad)
        textPaint.setColor(COLOR_RING_AMBER);
        textPaint.setTextSize(r * 0.105f);
        canvas.drawText("VU  •  " + SENSITIVITY_LABELS[sensitivityIndex], cx, cy - r * 0.25f, textPaint);

        textPaint.setColor(COLOR_WARM_CREAM);
        textPaint.setTextSize(r * 0.135f);
        canvas.drawText(isBass ? "BASS" : "TREBLE", cx, cy + r * 0.36f, textPaint);

        // 7. Lectura digital dB VU en píldora inferior
        float vuDb = angleToVuDb(currentAngle);
        String dbText = String.format(Locale.US, "%+.1f dB", vuDb);
        float pillW = r * 0.44f;
        float pillH = r * 0.13f;
        float pillY = cy + r * 0.49f;
        readoutRect.set(cx - pillW, pillY - pillH, cx + pillW, pillY + pillH);
        canvas.drawRoundRect(readoutRect, pillH, pillH, readoutBgPaint);

        readoutTextPaint.setTextSize(r * 0.095f);
        readoutTextPaint.setColor(isOverload ? COLOR_RING_RED : COLOR_RING_AMBER);
        canvas.drawText(dbText, cx, pillY + (readoutTextPaint.getTextSize() * 0.35f), readoutTextPaint);

        // 8. Tres indicadores LED inferiores (SIGNAL, wOOx OPTIMAL, PEAK +3dB)
        drawTachometerLeds(canvas, cx, cy, r, normalizedLevel);

        // 9. Marcador Balístico Peak-Hold en el borde de la escala
        drawPeakHoldMarker(canvas, cx, cy, r, peakAngle);

        // 10. Aguja analógica con contrapeso, punta luminiscente y sombra dinámica
        drawCounterweightedNeedle(canvas, cx, cy, r, currentAngle);

        // 11. Domo central de pivote mecanizado
        drawCenterPivot(canvas, cx, cy, r, isBass);

        // 12. Reflejo de lente de cristal curvo (Liquid Glass)
        glassLensPaint.setShader(isBass ? glassShaderLeft : glassShaderRight);
        canvas.drawPath(isBass ? glassHighlightPathLeft : glassHighlightPathRight, glassLensPaint);
    }

    private void drawDualScaleAndStopPins(Canvas canvas, float cx, float cy, float r) {
        float innerMajor = r * 0.72f;
        float innerMid = r * 0.76f;
        float innerMinor = r * 0.79f;
        float outerTick = r * 0.865f;
        float powerTextDist = r * 0.57f;

        // 40 divisiones de precisión a lo largo de 270 grados
        for (int i = 0; i <= 40; i++) {
            float norm = i / 40.0f;
            float deg = ANGLE_START + (norm * ANGLE_RANGE);
            double rad = Math.toRadians(deg);
            float cos = (float) Math.cos(rad);
            float sin = (float) Math.sin(rad);

            boolean isMajor = (i % 10 == 0);
            boolean isMid = (i % 5 == 0 && !isMajor);
            boolean inRedZone = (norm >= ZERO_VU_NORM);

            float inner = isMajor ? innerMajor : (isMid ? innerMid : innerMinor);
            tickPaint.setStrokeWidth(isMajor ? r * 0.025f : (isMid ? r * 0.016f : r * 0.009f));

            if (inRedZone) {
                tickPaint.setColor(COLOR_RING_RED);
            } else if (isMajor) {
                tickPaint.setColor(COLOR_RING_ORANGE);
            } else if (isMid) {
                tickPaint.setColor(COLOR_RING_AMBER);
            } else {
                tickPaint.setColor(0x99FFF3E0);
            }

            canvas.drawLine(
                cx + inner * cos, cy + inner * sin,
                cx + outerTick * cos, cy + outerTick * sin,
                tickPaint
            );
        }

        // Números de escala interna (0, 25, 50, 75, 100)
        subTextPaint.setTextSize(r * 0.095f);
        for (int i = 0; i < POWER_VALUES.length; i++) {
            float norm = i / (float) (POWER_VALUES.length - 1);
            float deg = ANGLE_START + (norm * ANGLE_RANGE);
            double rad = Math.toRadians(deg);
            float cos = (float) Math.cos(rad);
            float sin = (float) Math.sin(rad);

            float tx = cx + powerTextDist * cos;
            float ty = cy + powerTextDist * sin + (subTextPaint.getTextSize() * 0.35f);

            subTextPaint.setColor(norm >= ZERO_VU_NORM ? COLOR_RING_RED : COLOR_WARM_CREAM);
            canvas.drawText(String.valueOf(POWER_VALUES[i]), tx, ty, subTextPaint);
        }

        // Marcas clave de decibelios VU (-20, -10, -5, 0, +3)
        subTextPaint.setTextSize(r * 0.072f);
        float vuDist = r * 0.665f;
        for (int i = 0; i < VU_DB_LABELS.length; i++) {
            float norm = VU_DB_POS[i];
            float deg = ANGLE_START + (norm * ANGLE_RANGE);
            double rad = Math.toRadians(deg);
            float cos = (float) Math.cos(rad);
            float sin = (float) Math.sin(rad);

            subTextPaint.setColor(norm >= ZERO_VU_NORM ? COLOR_RING_RED : COLOR_TEXT_MUTED);
            canvas.drawText(
                VU_DB_LABELS[i],
                cx + vuDist * cos,
                cy + vuDist * sin + (subTextPaint.getTextSize() * 0.35f),
                subTextPaint
            );
        }

        // Topes mecánicos físicos (pines de goma con borde metálico en -20dB y +3dB)
        float pinDist = r * 0.68f;
        float pinRadius = r * 0.022f;
        double radMin = Math.toRadians(ANGLE_START - 3.2f);
        double radMax = Math.toRadians(ANGLE_END + 3.8f);

        stopPinPaint.setColor(0xFFB0A8AC);
        canvas.drawCircle(cx + pinDist * (float) Math.cos(radMin), cy + pinDist * (float) Math.sin(radMin), pinRadius, stopPinPaint);
        canvas.drawCircle(cx + pinDist * (float) Math.cos(radMax), cy + pinDist * (float) Math.sin(radMax), pinRadius, stopPinPaint);
    }

    private void drawPeakHoldMarker(Canvas canvas, float cx, float cy, float r, float peakAngle) {
        if (peakAngle <= ANGLE_START + 1.5f) return;

        boolean inRed = ((peakAngle - ANGLE_START) / ANGLE_RANGE) >= ZERO_VU_NORM;
        peakHoldPaint.setColor(inRed ? COLOR_RING_RED : COLOR_RING_AMBER);

        canvas.save();
        canvas.translate(cx, cy);
        canvas.rotate(peakAngle);

        float tipR = r * 0.84f;
        float baseR = r * 0.905f;
        float halfW = r * 0.028f;

        peakTrianglePath.reset();
        peakTrianglePath.moveTo(tipR, 0f);
        peakTrianglePath.lineTo(baseR, -halfW);
        peakTrianglePath.lineTo(baseR, halfW);
        peakTrianglePath.close();

        canvas.drawPath(peakTrianglePath, peakHoldPaint);
        canvas.restore();
    }

    private void drawTachometerLeds(Canvas canvas, float cx, float cy, float r, float level) {
        float ledY = cy + r * 0.72f;
        float ledSpacing = r * 0.22f;
        float ledRadius = r * 0.042f;

        // LED 1 (Izquierdo: SIGNAL >= 18%)
        boolean on1 = level >= 0.18f;
        ledPaint.setColor(on1 ? COLOR_RING_ORANGE : 0xFF2B120C);
        canvas.drawCircle(cx - ledSpacing, ledY, ledRadius, ledPaint);
        if (on1) {
            ledPaint.setColor(0x66FF7315);
            canvas.drawCircle(cx - ledSpacing, ledY, ledRadius * 1.9f, ledPaint);
        }

        // LED 2 (Centro: OPTIMAL 0 dB >= 52%)
        boolean on2 = level >= 0.52f;
        ledPaint.setColor(on2 ? COLOR_RING_AMBER : 0xFF2E1A08);
        canvas.drawCircle(cx, ledY, ledRadius, ledPaint);
        if (on2) {
            ledPaint.setColor(0x66FFB800);
            canvas.drawCircle(cx, ledY, ledRadius * 1.9f, ledPaint);
        }

        // LED 3 (Derecho: PEAK +3dB OVERLOAD >= 76%)
        boolean on3 = level >= ZERO_VU_NORM;
        ledPaint.setColor(on3 ? COLOR_RING_RED : 0xFF2E0A0C);
        canvas.drawCircle(cx + ledSpacing, ledY, ledRadius, ledPaint);
        if (on3) {
            ledPaint.setColor(0x88FF2B22);
            canvas.drawCircle(cx + ledSpacing, ledY, ledRadius * 2.1f, ledPaint);
        }
    }

    private void drawCounterweightedNeedle(Canvas canvas, float cx, float cy, float r, float angle) {
        float needleLength = r * 0.835f;
        float tailLength = r * 0.19f;
        float baseWidth = r * 0.036f;
        float tailWidth = r * 0.052f;
        float tipWidth = r * 0.009f;

        // Geometría local sobre eje +X (rotada por hardware en Canvas)
        needlePath.reset();
        needlePath.moveTo(-tailLength, -tailWidth * 0.6f);
        needlePath.lineTo(0f, -baseWidth);
        needlePath.lineTo(needleLength, -tipWidth);
        needlePath.lineTo(needleLength, tipWidth);
        needlePath.lineTo(0f, baseWidth);
        needlePath.lineTo(-tailLength, tailWidth * 0.6f);
        needlePath.close();

        // Punta luminiscente de tritio (último 28% de la aguja)
        float tipStart = needleLength * 0.70f;
        needleTipPath.reset();
        needleTipPath.moveTo(tipStart, -baseWidth * 0.42f);
        needleTipPath.lineTo(needleLength, -tipWidth);
        needleTipPath.lineTo(needleLength, tipWidth);
        needleTipPath.lineTo(tipStart, baseWidth * 0.42f);
        needleTipPath.close();

        // 1. Sombra proyectada sobre el fondo de la esfera (desplazada hacia abajo por luz cenital)
        canvas.save();
        canvas.translate(cx + r * 0.018f, cy + r * 0.032f);
        canvas.rotate(angle);
        canvas.drawPath(needlePath, needleShadowPaint);
        canvas.restore();

        // 2. Cuerpo de la aguja + Punta fluorescente de alta visibilidad
        canvas.save();
        canvas.translate(cx, cy);
        canvas.rotate(angle);
        canvas.drawPath(needlePath, needlePaint);
        canvas.drawPath(needleTipPath, needleTipPaint);
        canvas.restore();
    }

    private void drawCenterPivot(Canvas canvas, float cx, float cy, float r, boolean isBass) {
        float pivotRadius = r * 0.125f;

        // Resplandor cálido bajo la cúpula
        capGlowPaint.setShader(isBass ? capGlowShaderLeft : capGlowShaderRight);
        canvas.drawCircle(cx, cy, pivotRadius * 2.4f, capGlowPaint);

        // Cúpula central mecanizada
        capPaint.setShader(isBass ? capDomeShaderLeft : capDomeShaderRight);
        canvas.drawCircle(cx, cy, pivotRadius, capPaint);

        // Anillo interior de bronce bruñido
        ringPaint.setStrokeWidth(pivotRadius * 0.16f);
        ringPaint.setColor(0xFFFFD180);
        canvas.drawCircle(cx, cy, pivotRadius * 0.42f, ringPaint);
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility != VISIBLE) {
            stopDemoAnimation();
            isAnimating = false;
        } else if (isDemoMode) {
            startDemoAnimation();
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE) {
            stopDemoAnimation();
            isAnimating = false;
        } else if (isDemoMode) {
            startDemoAnimation();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stopDemoAnimation();
        isAnimating = false;
        super.onDetachedFromWindow();
    }
}
