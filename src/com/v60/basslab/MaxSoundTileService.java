package com.v60.basslab;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * MaxSoundTileService (Tile 1 — VU BASS • MAX SOUND a 60 FPS VSYNC)
 *
 * Envía telemetría de graves a 60 Hz por loopback UDP hacia SystemUiQsHook y mantiene
 * una instancia estable de BitmapDrawable (98x98) para que el Choreographer de SystemUI
 * anime el vúmetro vectorial a 60 FPS reales sin recomposiciones de TileService.
 */
public class MaxSoundTileService extends TileService {

    private static final long TELEMETRY_INTERVAL_MS = 16L; // 60 Hz

    private final Handler tickerHandler = new Handler(Looper.getMainLooper());
    private boolean isListening = false;
    private QsVuGaugeRenderer renderer;
    private Icon cachedMarkerIcon;
    private int lastStateSignature = -1;

    private final Runnable tickerRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isListening) return;
            pumpTelemetryAndSyncState(false);
            tickerHandler.postDelayed(this, TELEMETRY_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        renderer = new QsVuGaugeRenderer(true, true);
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        isListening = true;
        if (renderer == null) {
            renderer = new QsVuGaugeRenderer(true, true);
        }
        renderer.resetTiming();
        lastStateSignature = -1;

        if (AudioDspService.getInstance() == null) {
            try {
                Intent intent = new Intent(this, AudioDspService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent);
                } else {
                    startService(intent);
                }
            } catch (Exception ignored) {}
        }

        pumpTelemetryAndSyncState(true);
        tickerHandler.removeCallbacks(tickerRunnable);
        tickerHandler.postDelayed(tickerRunnable, TELEMETRY_INTERVAL_MS);
    }

    @Override
    public void onStopListening() {
        super.onStopListening();
        isListening = false;
        tickerHandler.removeCallbacks(tickerRunnable);
    }

    @Override
    public void onClick() {
        super.onClick();
        AudioDspService service = AudioDspService.getInstance();
        if (service != null) {
            if (service.isSonyXb66Active()) {
                service.setSonyXb66Sound(false);
            } else {
                boolean nextState = !service.isMaxSoundActive();
                service.setMaxSound(nextState);
            }
            pumpTelemetryAndSyncState(true);
        } else {
            Intent intent = new Intent(this, AudioDspService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            pumpTelemetryAndSyncState(true);
        }
    }

    private void pumpTelemetryAndSyncState(boolean forceTileUpdate) {
        AudioDspService service = AudioDspService.getInstance();
        if (service != null) {
            QsVuTelemetryBridge.sendFromService(service);
        }

        Tile tile = getQsTile();
        if (tile == null || renderer == null) return;

        boolean isMax = (service != null && service.isMaxSoundActive());
        boolean isSony = (service != null && service.isSonyXb66Active());
        boolean isLiveEq = (service != null && service.getAiAnalyzer().isLiveDynamicEqActive());
        boolean isActive = isMax || isSony || isLiveEq || (service != null && service.isMasterEnabled());

        int signature = (isActive ? 1 : 0) | (isMax ? 2 : 0) | (isSony ? 4 : 0) | (isLiveEq ? 8 : 0);
        if (!forceTileUpdate && signature == lastStateSignature && cachedMarkerIcon != null) {
            // No invocar tile.updateTile() en cada frame: esto preserva el DrawablePainter en SystemUI
            // permitiendo que SystemUiQsHook invalide el Canvas a 60 FPS VSYNC reales.
            return;
        }
        lastStateSignature = signature;

        if (cachedMarkerIcon == null) {
            float liveBassDb = renderer.resolveLiveDb(this, service);
            Bitmap frameBmp = renderer.stepAndRender(liveBassDb, isActive);
            cachedMarkerIcon = Icon.createWithBitmap(frameBmp);
        }

        tile.setIcon(cachedMarkerIcon);
        tile.setState(isActive ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(isSony ? "VU Bass • XB66" : (isMax ? "VU Bass • MAX" : "VU Bass"));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (isSony) {
                tile.setSubtitle("📻 SONY XB66");
            } else if (isMax) {
                tile.setSubtitle("⚡ MAX 150%");
            } else {
                tile.setSubtitle("BASS 60FPS");
            }
        }

        tile.updateTile();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isListening = false;
        tickerHandler.removeCallbacks(tickerRunnable);
        if (renderer != null) {
            renderer.release();
            renderer = null;
        }
    }
}
