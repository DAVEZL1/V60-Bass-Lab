package com.v60.basslab;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AudioCaptureManager
 * 
 * Captura audio de reproducción interna de otras aplicaciones mediante AudioPlaybackCapture (API 29+ / Android 10+):
 * - Solicita autorización mediante MediaProjectionManager.
 * - Filtra por USAGE_MEDIA y USAGE_GAME para capturar música y juegos.
 * - Hilo en segundo plano para lectura ininterrumpida de PCM de 16 bits.
 * - Banco de filtros digitales IIR Biquad para separación de frecuencias en tiempo real:
 *     - BASS: Pasa-banda centrado en 80 Hz (rango 30 Hz - 250 Hz).
 *     - TREBLE: Pasa-banda centrado en 5000 Hz (rango 2.5 kHz - 12 kHz).
 * - Seguidor de envolvente acústica con ataque rápido (~10ms) y caída gradual (~80ms).
 * - Liberación limpia de recursos (MediaProjection, AudioRecord, Thread) para evitar fugas.
 */
public class AudioCaptureManager {

    private static final String TAG = "V60_AudioCapture";
    private static final int SAMPLE_RATE = 44100;

    public interface OnAudioLevelsListener {
        void onLevels(float bass, float treble);
        void onStateChanged(boolean isCapturing, String statusMessage);
        void onError(String errorMessage);
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private AudioRecord audioRecord;

    private Thread captureThread;
    private final AtomicBoolean isCapturing = new AtomicBoolean(false);
    private OnAudioLevelsListener listener;

    // Coeficientes Biquad Pasa-Banda BASS (80 Hz, Q = 0.8)
    private float bassB0, bassB2, bassA1, bassA2;
    private float bassX1, bassX2, bassY1, bassY2;

    // Coeficientes Biquad Pasa-Banda TREBLE (5000 Hz, Q = 0.8)
    private float trebB0, trebB2, trebA1, trebA2;
    private float trebX1, trebX2, trebY1, trebY2;

    // Suavizado dinámico de envolvente
    private float smoothedBass = 0.0f;
    private float smoothedTreble = 0.0f;
    private final float attackCoeff = 0.25f;  // Ataque rápido (~10 ms)
    private final float releaseCoeff = 0.04f; // Caída suave (~80 ms)

    public AudioCaptureManager(Context context) {
        this.context = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            this.projectionManager = (MediaProjectionManager) this.context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        }
        initFilters();
    }

    public void setListener(OnAudioLevelsListener listener) {
        this.listener = listener;
    }

    public boolean isCapturing() {
        return isCapturing.get();
    }

    /**
     * Devuelve el Intent del sistema para solicitar permisos de captura al usuario.
     */
    public Intent createScreenCaptureIntent() {
        if (projectionManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return projectionManager.createScreenCaptureIntent();
        }
        return null;
    }

    /**
     * Inicia la captura a partir del resultado de la autorización de MediaProjection.
     */
    public boolean startCapture(int resultCode, Intent resultData) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            notifyError("AudioPlaybackCapture requiere Android 10 (API 29) o superior.");
            return false;
        }

        if (projectionManager == null || resultData == null) {
            notifyError("Datos de captura inválidos o denegados.");
            return false;
        }

        stopCapture();

        try {
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData);
            if (mediaProjection == null) {
                notifyError("Permiso de captura denegado por el usuario.");
                return false;
            }

            // Callback obligatorio en Android 14+ (API 34)
            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    stopCapture();
                    notifyStateChanged(false, "Captura de audio detenida por el sistema");
                }
            }, mainHandler);

            // 1. Configurar AudioPlaybackCapture
            AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build();

            // 2. Configurar AudioRecord PCM de 16 bits
            int channelConfig = AudioFormat.CHANNEL_IN_STEREO;
            int encoding = AudioFormat.ENCODING_PCM_16BIT;
            int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, encoding);
            int bufferSize = Math.max(minBufferSize * 2, 4096);

            AudioFormat audioFormat = new AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(channelConfig)
                .build();

            audioRecord = new AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(config)
                .build();

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                notifyError("No se pudo inicializar AudioRecord para captura.");
                stopCapture();
                return false;
            }

            audioRecord.startRecording();
            if (audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                notifyError("El motor de audio no pudo iniciar la grabación.");
                stopCapture();
                return false;
            }

            isCapturing.set(true);
            notifyStateChanged(true, "Capturando audio de aplicaciones activas...");

            // Iniciar hilo de lectura PCM
            captureThread = new Thread(this::audioReaderLoop, "V60_AudioCaptureThread");
            captureThread.start();
            return true;

        } catch (SecurityException se) {
            AppLogger.e(TAG, "SecurityException en captura: " + se.getMessage(), se);
            notifyError("Permiso de captura denegado o restringido por seguridad: " + se.getMessage());
            stopCapture();
            return false;
        } catch (Exception e) {
            AppLogger.e(TAG, "Error iniciando AudioPlaybackCapture: " + e.getMessage(), e);
            notifyError("Error al iniciar captura de audio: " + e.getMessage());
            stopCapture();
            return false;
        }
    }

    /**
     * Bucle de lectura y procesamiento de muestras PCM en segundo plano.
     */
    private void audioReaderLoop() {
        short[] buffer = new short[1024];
        long lastNotifyTime = 0;

        while (isCapturing.get()) {
            if (audioRecord == null) break;

            int read = audioRecord.read(buffer, 0, buffer.length);
            if (read > 0) {
                float sumBassSq = 0.0f;
                float sumTrebSq = 0.0f;

                for (int i = 0; i < read; i++) {
                    float sample = buffer[i] / 32768.0f;

                    // 1. Filtrado pasa-banda BASS (80 Hz)
                    float yBass = bassB0 * sample + bassB2 * bassX2 - bassA1 * bassY1 - bassA2 * bassY2;
                    bassX2 = bassX1;
                    bassX1 = sample;
                    bassY2 = bassY1;
                    bassY1 = yBass;
                    sumBassSq += yBass * yBass;

                    // 2. Filtrado pasa-banda TREBLE (5000 Hz)
                    float yTreb = trebB0 * sample + trebB2 * trebX2 - trebA1 * trebY1 - trebA2 * trebY2;
                    trebX2 = trebX1;
                    trebX1 = sample;
                    trebY2 = trebY1;
                    trebY1 = yTreb;
                    sumTrebSq += yTreb * yTreb;
                }

                // Cálculo RMS
                float rmsBass = (float) Math.sqrt(sumBassSq / read);
                float rmsTreble = (float) Math.sqrt(sumTrebSq / read);

                // Normalización de escala para el rango visual
                float instantBass = Math.min(1.0f, rmsBass * 4.2f);
                float instantTreble = Math.min(1.0f, rmsTreble * 5.8f);

                // Seguidor de envolvente con ataque y decaimiento natural
                smoothedBass += (instantBass > smoothedBass ? attackCoeff : releaseCoeff) * (instantBass - smoothedBass);
                smoothedTreble += (instantTreble > smoothedTreble ? attackCoeff : releaseCoeff) * (instantTreble - smoothedTreble);

                // Limitar notificaciones a la UI a ~50 FPS (cada 20 ms)
                long now = System.currentTimeMillis();
                if (now - lastNotifyTime >= 20) {
                    lastNotifyTime = now;
                    final float finalBass = smoothedBass;
                    final float finalTreble = smoothedTreble;
                    mainHandler.post(() -> {
                        if (listener != null && isCapturing.get()) {
                            listener.onLevels(finalBass, finalTreble);
                        }
                    });
                }
            } else if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE) {
                break;
            }
        }
    }

    /**
     * Inicializa los coeficientes de los filtros digitales IIR pasa-banda (Cookbook Biquad).
     */
    private void initFilters() {
        // BASS: 80 Hz, Q = 0.8
        float w0Bass = (float) (2.0 * Math.PI * 80.0 / SAMPLE_RATE);
        float alphaBass = (float) (Math.sin(w0Bass) / (2.0 * 0.8));
        float b0B = (float) Math.sin(w0Bass) / 2.0f;
        float b2B = -b0B;
        float a0B = 1.0f + alphaBass;
        float a1B = -2.0f * (float) Math.cos(w0Bass);
        float a2B = 1.0f - alphaBass;

        bassB0 = b0B / a0B;
        bassB2 = b2B / a0B;
        bassA1 = a1B / a0B;
        bassA2 = a2B / a0B;

        // TREBLE: 5000 Hz, Q = 0.8
        float w0Treb = (float) (2.0 * Math.PI * 5000.0 / SAMPLE_RATE);
        float alphaTreb = (float) (Math.sin(w0Treb) / (2.0 * 0.8));
        float b0T = (float) Math.sin(w0Treb) / 2.0f;
        float b2T = -b0T;
        float a0T = 1.0f + alphaTreb;
        float a1T = -2.0f * (float) Math.cos(w0Treb);
        float a2T = 1.0f - alphaTreb;

        trebB0 = b0T / a0T;
        trebB2 = b2T / a0T;
        trebA1 = a1T / a0T;
        trebA2 = a2T / a0T;
    }

    /**
     * Detiene la captura y libera completamente todos los recursos.
     */
    public void stopCapture() {
        isCapturing.set(false);

        if (captureThread != null) {
            captureThread.interrupt();
            try {
                captureThread.join(300);
            } catch (InterruptedException ignored) {}
            captureThread = null;
        }

        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
                audioRecord.release();
            } catch (Exception ignored) {}
            audioRecord = null;
        }

        if (mediaProjection != null) {
            try {
                mediaProjection.stop();
            } catch (Exception ignored) {}
            mediaProjection = null;
        }

        smoothedBass = 0.0f;
        smoothedTreble = 0.0f;
        notifyStateChanged(false, "Captura en pausa / detenido");
    }

    private void notifyStateChanged(boolean capturing, String message) {
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onStateChanged(capturing, message);
            }
        });
    }

    private void notifyError(String error) {
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onError(error);
            }
        });
    }
}
