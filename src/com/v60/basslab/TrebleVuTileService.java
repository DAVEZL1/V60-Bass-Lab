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
 * TrebleVuTileService (Tile 2 — VU TREBLE • SUPER-TWEETER & 3D STEM-TRACKER a 60 FPS VSYNC)
 *
 * Envía telemetría de agudos a 60 Hz por loopback UDP hacia SystemUiQsHook y mantiene
 * una instancia estable de BitmapDrawable (102x102) para que el Choreographer de SystemUI
 * anime el vúmetro vectorial a 60 FPS reales sin recomposiciones de TileService.
 */
public class TrebleVuTileService extends TileService {

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
        renderer = new QsVuGaugeRenderer(false, true);
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        isListening = true;
        if (renderer == null) {
            renderer = new QsVuGaugeRenderer(false, true);
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
            boolean nextLiveEq = !service.getAiAnalyzer().isLiveDynamicEqActive();
            service.getAiAnalyzer().setLiveDynamicEqActive(nextLiveEq);
            if (!nextLiveEq) {
                float nextAir = (service.getCurrentAirDb() < 4.5f) ? 6.0f : 2.5f;
                service.setAirGain(nextAir);
            }
            QsVuTelemetryBridge.sendFromService(service);
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

        boolean isLiveEq = (service != null && service.getAiAnalyzer().isLiveDynamicEqActive());
        boolean isAirBoost = (service != null && service.getCurrentAirDb() >= 4.5f);
        boolean isActive = isLiveEq || isAirBoost || (service != null && service.isMasterEnabled());

        int signature = (isActive ? 1 : 0) | (isLiveEq ? 2 : 0) | (isAirBoost ? 4 : 0);
        if (!forceTileUpdate && signature == lastStateSignature && cachedMarkerIcon != null) {
            return;
        }
        lastStateSignature = signature;

        if (cachedMarkerIcon == null) {
            float liveTrebleDb = renderer.resolveLiveDb(this, service);
            Bitmap frameBmp = renderer.stepAndRender(liveTrebleDb, isActive);
            cachedMarkerIcon = Icon.createWithBitmap(frameBmp);
        }

        tile.setIcon(cachedMarkerIcon);
        tile.setState(isActive ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(isLiveEq ? "VU Treble • 3D" : "VU Treble");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (isLiveEq) {
                tile.setSubtitle("🎛️ LIVE 3D EQ");
            } else {
                tile.setSubtitle("TREB 60FPS");
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
