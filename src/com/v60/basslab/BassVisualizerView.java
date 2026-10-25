package com.v60.basslab;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * BassVisualizerView
 * 
 * Visualizador de espectro de audio estilo RACK por bloques LED segmentados (Cyberpunk / Hi-Fi):
 * - 20 columnas de frecuencia independientes.
 * - Cada columna contiene 12 bloques LED horizontales discretos.
 * - Graduación vertical de color: Rojo Carmesí (#D0141E) -> Naranja Fuego (#FF5010) -> Ámbar Pico (#FFB800).
 * - Segmentos inactivos dibujados en sombra técnica oscura (#15080B).
 * - Decay balístico suave a 60 FPS con peak-hold.
 */
public class BassVisualizerView extends View {

    private static final int NUM_COLUMNS = 20;
    private static final int NUM_SEGMENTS = 12;

    private final float[] currentHeights = new float[NUM_COLUMNS];
    private final float[] targetHeights = new float[NUM_COLUMNS];
    private final float[] peakSegments = new float[NUM_COLUMNS];

    private final Paint segmentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgSegmentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private boolean isPlaying = true;

    // Paleta de colores de los 12 segmentos (de abajo hacia arriba)
    private static final int[] SEGMENT_COLORS = {
        0xFFCC1620, // 0  (Sub)
        0xFFD91E22, // 1
        0xFFE62A24, // 2
        0xFFF23B20, // 3
        0xFFFF5018, // 4  (Punch)
        0xFFFF6510, // 5
        0xFFFF7808, // 6
        0xFFFF8C00, // 7
        0xFFFFA000, // 8  (Mid)
        0xFFFFB000, // 9
        0xFFFFC200, // 10 (High)
        0xFFFF3333  // 11 (Overdrive Peak Red)
    };

    public BassVisualizerView(Context context) {
        super(context);
        init();
    }

    public BassVisualizerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public BassVisualizerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        segmentPaint.setStyle(Paint.Style.FILL);
        bgSegmentPaint.setStyle(Paint.Style.FILL);
        bgSegmentPaint.setColor(0xFF14080B); // Bloque apagado

        for (int i = 0; i < NUM_COLUMNS; i++) {
            currentHeights[i] = 0.15f;
            targetHeights[i] = 0.15f;
            peakSegments[i] = 2f;
        }
    }

    public void setAudioData(byte[] fftOrWaveform) {
        if (fftOrWaveform != null && fftOrWaveform.length > 0) {
            int step = Math.max(1, (fftOrWaveform.length / 3) / NUM_COLUMNS);
            for (int i = 0; i < NUM_COLUMNS; i++) {
                int index = i * step;
                if (index < fftOrWaveform.length) {
                    byte val = fftOrWaveform[index];
                    float norm = Math.abs((float) val) / 128.0f;
                    targetHeights[i] = Math.min(1.0f, Math.max(0.08f, norm * 1.35f));
                }
            }
        }
        postInvalidateOnAnimation();
    }

    public void setSubBassBoostFactor(float factor) {
        for (int i = 0; i < 6; i++) {
            targetHeights[i] = Math.min(1.0f, targetHeights[i] * (1.0f + factor * 0.08f));
        }
        postInvalidateOnAnimation();
    }

    public void setPlaying(boolean playing) {
        this.isPlaying = playing;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float colWidth = (float) w / NUM_COLUMNS;
        float barWidth = colWidth * 0.74f;
        float horizontalMargin = colWidth * 0.13f;

        float segmentGap = 2.5f;
        float totalVerticalGaps = (NUM_SEGMENTS - 1) * segmentGap;
        float segmentHeight = Math.max(2.0f, (h - totalVerticalGaps - 6.0f) / NUM_SEGMENTS);

        for (int col = 0; col < NUM_COLUMNS; col++) {
            if (isPlaying) {
                currentHeights[col] += (targetHeights[col] - currentHeights[col]) * 0.38f;
                targetHeights[col] *= 0.90f;
                if (targetHeights[col] < 0.08f) targetHeights[col] = 0.08f;
            } else {
                currentHeights[col] *= 0.85f;
            }

            int activeCount = Math.round(currentHeights[col] * NUM_SEGMENTS);
            activeCount = Math.max(1, Math.min(NUM_SEGMENTS, activeCount));

            // Peak hold logic
            if (activeCount >= peakSegments[col]) {
                peakSegments[col] = activeCount;
            } else {
                peakSegments[col] -= 0.18f; // Decaimiento suave del pico
                if (peakSegments[col] < activeCount) peakSegments[col] = activeCount;
            }

            float left = (col * colWidth) + horizontalMargin;
            float right = left + barWidth;

            // Dibujar cada uno de los 12 bloques (0 en la base, 11 en el tope)
            for (int seg = 0; seg < NUM_SEGMENTS; seg++) {
                float bottom = (h - 3.0f) - seg * (segmentHeight + segmentGap);
                float top = bottom - segmentHeight;

                rect.set(left, top, right, bottom);

                boolean isActive = seg < activeCount;
                boolean isPeak = Math.round(peakSegments[col]) == seg && seg > 0;

                if (isActive || isPeak) {
                    segmentPaint.setColor(SEGMENT_COLORS[seg]);
                    canvas.drawRoundRect(rect, 2.5f, 2.5f, segmentPaint);
                } else {
                    canvas.drawRoundRect(rect, 2.5f, 2.5f, bgSegmentPaint);
                }
            }
        }

        if (isPlaying) {
            postInvalidateDelayed(25); // ~40 fps
        }
    }
}
