package com.v60.basslab;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HiResPlayerEngine
 * 
 * High-resolution local audio player that decodes audio files via hardware MediaCodec
 * and processes raw PCM samples through the SubharmonicSynthesizerEngine before outputting
 * to AudioTrack and the LG V60 Quad DAC.
 */
public class HiResPlayerEngine {

    private static final String TAG = "V60_HiResPlayer";

    public interface PlayerCallback {
        void onTrackLoaded(String title, long durationMs);
        void onProgress(long positionMs, long durationMs);
        void onPlaybackStateChanged(boolean isPlaying);
        void onError(String message);
    }

    public interface AudioLevelListener {
        void onLevels(float leftDb, float rightDb);
    }

    private final Context context;
    private final SubharmonicSynthesizerEngine synthEngine;
    private PlayerCallback callback;
    private AudioLevelListener audioLevelListener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private AudioTrack audioTrack;
    private MediaExtractor extractor;
    private MediaCodec codec;

    private Thread playbackThread;
    private final AtomicBoolean isPlaying = new AtomicBoolean(false);
    private final AtomicBoolean isPaused = new AtomicBoolean(false);
    private final AtomicBoolean isStopped = new AtomicBoolean(true);

    private long durationUs = 0;
    private volatile long currentPositionUs = 0;
    private volatile long seekTargetUs = -1;
    private String currentTitle = "Sin pista cargada";

    public HiResPlayerEngine(Context context, SubharmonicSynthesizerEngine synthEngine) {
        this.context = context.getApplicationContext();
        this.synthEngine = synthEngine;
    }

    public void setCallback(PlayerCallback callback) {
        this.callback = callback;
    }

    public void setAudioLevelListener(AudioLevelListener listener) {
        this.audioLevelListener = listener;
    }

    public SubharmonicSynthesizerEngine getSynthEngine() {
        return synthEngine;
    }

    public boolean isPlaying() {
        return isPlaying.get();
    }

    public String getCurrentTitle() {
        return currentTitle;
    }

    public long getDurationMs() {
        return durationUs / 1000;
    }

    public long getPositionMs() {
        return currentPositionUs / 1000;
    }

    public int getAudioSessionId() {
        return audioTrack != null ? audioTrack.getAudioSessionId() : 0;
    }

    public void loadAndPlay(Uri uri, String displayName) {
        stop();

        this.currentTitle = displayName != null ? displayName : "Pista de audio";
        this.isStopped.set(false);
        this.isPaused.set(false);

        playbackThread = new Thread(() -> runDecoderLoop(uri), "V60_DecoderThread");
        playbackThread.start();
    }

    public void togglePlayPause() {
        if (isStopped.get()) return;

        if (isPaused.get()) {
            isPaused.set(false);
            if (audioTrack != null && audioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                audioTrack.play();
            }
            isPlaying.set(true);
            notifyPlaybackState(true);
        } else {
            isPaused.set(true);
            if (audioTrack != null && audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                audioTrack.pause();
            }
            isPlaying.set(false);
            notifyPlaybackState(false);
        }
    }

    public void seekTo(long positionMs) {
        this.seekTargetUs = positionMs * 1000;
    }

    public void stop() {
        isStopped.set(true);
        isPlaying.set(false);
        isPaused.set(false);

        if (playbackThread != null) {
            playbackThread.interrupt();
            try {
                playbackThread.join(400);
            } catch (InterruptedException ignored) {}
            playbackThread = null;
        }

        cleanupResources();
        notifyPlaybackState(false);
    }

    private void runDecoderLoop(Uri uri) {
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(context, uri, null);

            int audioTrackIndex = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrackIndex = i;
                    format = f;
                    break;
                }
            }

            if (audioTrackIndex < 0 || format == null) {
                notifyError("No se encontró pista de audio compatible");
                return;
            }

            extractor.selectTrack(audioTrackIndex);
            durationUs = format.containsKey(MediaFormat.KEY_DURATION) ? format.getLong(MediaFormat.KEY_DURATION) : 0;

            int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            String mime = format.getString(MediaFormat.KEY_MIME);

            synthEngine.init(sampleRate);

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            int channelConfig = (channelCount == 1) ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
            int minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT);

            AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();

            AudioFormat audioFormat = new AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(channelConfig)
                .build();

            audioTrack = new AudioTrack(attrs, audioFormat, minBufferSize * 3, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
            audioTrack.play();

            isPlaying.set(true);
            notifyTrackLoaded(currentTitle, durationUs / 1000);
            notifyPlaybackState(true);

            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            boolean sawInputEOS = false;
            boolean sawOutputEOS = false;

            short[] pcmShortBuffer = new short[4096];
            long lastProgressReportMs = 0;

            while (!isStopped.get() && !sawOutputEOS) {
                // Check pause
                while (isPaused.get() && !isStopped.get()) {
                    Thread.sleep(30);
                }
                if (isStopped.get()) break;

                // Handle Seek
                if (seekTargetUs >= 0) {
                    extractor.seekTo(seekTargetUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                    codec.flush();
                    seekTargetUs = -1;
                }

                // 1. Feed Input to Decoder
                if (!sawInputEOS) {
                    int inputBufferIndex = codec.dequeueInputBuffer(4000);
                    if (inputBufferIndex >= 0) {
                        ByteBuffer inputBuffer = codec.getInputBuffer(inputBufferIndex);
                        if (inputBuffer != null) {
                            int sampleSize = extractor.readSampleData(inputBuffer, 0);
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                sawInputEOS = true;
                            } else {
                                long presentationTimeUs = extractor.getSampleTime();
                                codec.queueInputBuffer(inputBufferIndex, 0, sampleSize, presentationTimeUs, 0);
                                extractor.advance();
                            }
                        }
                    }
                }

                // 2. Pull Decoded PCM from Decoder
                int outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 4000);
                if (outputBufferIndex >= 0) {
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawOutputEOS = true;
                    }

                    ByteBuffer outputBuffer = codec.getOutputBuffer(outputBufferIndex);
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset);
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size);

                        currentPositionUs = bufferInfo.presentationTimeUs;

                        int sampleCount = bufferInfo.size / 2; // 16-bit
                        if (pcmShortBuffer.length < sampleCount) {
                            pcmShortBuffer = new short[sampleCount];
                        }
                        outputBuffer.asShortBuffer().get(pcmShortBuffer, 0, sampleCount);

                        // 3. RUN SUB-HARMONIC SYNTHESIZER DSP IN REAL TIME!
                        if (channelCount >= 2) {
                            synthEngine.processStereoPcm(pcmShortBuffer, 0, sampleCount);
                        }

                        // Compute stereo levels for analog VU meter and Quick Settings tile
                        if (audioLevelListener != null && sampleCount >= 2) {
                            float sumL = 0;
                            float sumR = 0;
                            int pairs = sampleCount / 2;
                            for (int i = 0; i < sampleCount; i += 2) {
                                float sL = pcmShortBuffer[i] / 32768.0f;
                                float sR = pcmShortBuffer[i + 1] / 32768.0f;
                                sumL += sL * sL;
                                sumR += sR * sR;
                            }
                            float rmsL = (float) Math.sqrt(sumL / pairs);
                            float rmsR = (float) Math.sqrt(sumR / pairs);
                            float dbL = (rmsL < 0.0001f) ? -20.0f : (float) (20.0 * Math.log10(rmsL * 2.2f));
                            float dbR = (rmsR < 0.0001f) ? -20.0f : (float) (20.0 * Math.log10(rmsR * 2.2f));
                            audioLevelListener.onLevels(dbL, dbR);
                        }

                        // 4. Write Processed Audio to AudioTrack
                        audioTrack.write(pcmShortBuffer, 0, sampleCount);
                    }

                    codec.releaseOutputBuffer(outputBufferIndex, false);

                    // Report progress periodically
                    long now = System.currentTimeMillis();
                    if (now - lastProgressReportMs > 250) {
                        lastProgressReportMs = now;
                        notifyProgress(currentPositionUs / 1000, durationUs / 1000);
                    }
                }
            }

            // Finished playback
            if (sawOutputEOS) {
                notifyProgress(durationUs / 1000, durationUs / 1000);
            }
        } catch (Exception e) {
            AppLogger.e(TAG, "Error en decodificador Hi-Res: " + e.getMessage(), e);
            notifyError("Error reproduciendo audio: " + e.getMessage());
        } finally {
            cleanupResources();
            isPlaying.set(false);
            isStopped.set(true);
            notifyPlaybackState(false);
        }
    }

    private void cleanupResources() {
        try {
            if (audioTrack != null) {
                audioTrack.stop();
                audioTrack.release();
                audioTrack = null;
            }
            if (codec != null) {
                codec.stop();
                codec.release();
                codec = null;
            }
            if (extractor != null) {
                extractor.release();
                extractor = null;
            }
        } catch (Exception ignored) {}
    }

    private void notifyTrackLoaded(String title, long durationMs) {
        if (callback != null) {
            mainHandler.post(() -> callback.onTrackLoaded(title, durationMs));
        }
    }

    private void notifyProgress(long positionMs, long durationMs) {
        if (callback != null) {
            mainHandler.post(() -> callback.onProgress(positionMs, durationMs));
        }
    }

    private void notifyPlaybackState(boolean playing) {
        if (callback != null) {
            mainHandler.post(() -> callback.onPlaybackStateChanged(playing));
        }
    }

    private void notifyError(String error) {
        if (callback != null) {
            mainHandler.post(() -> callback.onError(error));
        }
    }
}
