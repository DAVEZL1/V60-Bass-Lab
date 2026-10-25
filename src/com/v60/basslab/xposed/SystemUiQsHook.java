package com.v60.basslab.xposed;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.media.AudioManager;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;

import com.v60.basslab.QsVuGaugeRenderer;
import com.v60.basslab.QsVuTelemetryBridge;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * SystemUiQsHook (Módulo LSPosed / Vector de 60 FPS VSYNC para Android 16 SystemUI)
 *
 * 1. Detecta en O(1) los Bitmaps marcadores de V60 Bass Lab por sus dimensiones exclusivas:
 *    - 98x98 px   = VU BASS (MaxSoundTileService)
 *    - 102x102 px = VU TREBLE (TrebleVuTileService)
 * 2. Recibe telemetría acústica de latencia ultra-baja por loopback UDP (127.0.0.1:49660)
 *    mediante QsVuTelemetryBridge y consulta AudioManager directamente en SystemUI.
 * 3. Reemplaza el dibujado de Bitmap de baja frecuencia por renderizado vectorial puro a 60 FPS
 *    directamente sobre el Hardware RecordingCanvas de Jetpack Compose en SystemUI.
 * 4. Ejecuta un bucle Choreographer VSYNC (60/120 Hz) mientras el panel Quick Settings está visible,
 *    invocando BitmapDrawable.invalidateSelf() -> DrawablePainter.invalidateDrawable() en cada VSYNC
 *    con 0% de uso de CPU cuando el panel está cerrado.
 */
public class SystemUiQsHook implements IXposedHookLoadPackage {

    private static final Paint DIAL_BG_PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint DIAL_RIM_PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);

    private static final QsVuGaugeRenderer BASS_RENDERER = new QsVuGaugeRenderer(true, false);
    private static final QsVuGaugeRenderer TREBLE_RENDERER = new QsVuGaugeRenderer(false, false);

    private static volatile WeakReference<BitmapDrawable> activeBassDrawableRef;
    private static volatile WeakReference<BitmapDrawable> activeTrebleDrawableRef;
    private static volatile long lastDrawUptimeMs = 0L;
    private static boolean choreographerRunning = false;
    private static AudioManager cachedAudioManager = null;
    private static int bassFrameCount = 0;
    private static long bassFpsWindowStartMs = 0L;

    static {
        DIAL_BG_PAINT.setStyle(Paint.Style.FILL);
        DIAL_BG_PAINT.setColor(0xF212090E);

        DIAL_RIM_PAINT.setStyle(Paint.Style.STROKE);
        DIAL_RIM_PAINT.setStrokeWidth(2.2f);
    }

    /**
     * Devuelve 1 si es VU BASS (98x98), 2 si es VU TREBLE (102x102), o 0 para cualquier otro Bitmap.
     */
    private static int getV60VuType(Bitmap bmp) {
        if (bmp == null) return 0;
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        if (w == QsVuGaugeRenderer.SIZE_BASS_PX && h == QsVuGaugeRenderer.SIZE_BASS_PX) return 1;
        if (w == QsVuGaugeRenderer.SIZE_TREBLE_PX && h == QsVuGaugeRenderer.SIZE_TREBLE_PX) return 2;
        return 0;
    }

    private static AudioManager getSystemUiAudioManager() {
        if (cachedAudioManager != null) return cachedAudioManager;
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Method curApp = atClass.getDeclaredMethod("currentApplication");
            Application app = (Application) curApp.invoke(null);
            if (app != null) {
                cachedAudioManager = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            }
        } catch (Throwable ignored) {}
        return cachedAudioManager;
    }

    private static void drawStudioDialBackdrop(Canvas canvas, float cx, float cy, float size, int vuType) {
        float radius = size * 0.49f;
        canvas.drawCircle(cx, cy, radius, DIAL_BG_PAINT);
        DIAL_RIM_PAINT.setColor(vuType == 1 ? 0xCCFF7315 : 0xCC00E5FF);
        canvas.drawCircle(cx, cy, radius, DIAL_RIM_PAINT);
    }

    private static void renderDirect60FpsGauge(Canvas canvas, float left, float top, float width, float height, int vuType) {
        float cx = left + width * 0.5f;
        float cy = top + height * 0.5f;
        float size = Math.min(width, height);

        int sc = canvas.save();
        try {
            canvas.scale(1.56f, 1.56f, cx, cy);
            drawStudioDialBackdrop(canvas, cx, cy, size, vuType);

            AudioManager am = getSystemUiAudioManager();
            if (vuType == 1) {
                float liveBassDb = BASS_RENDERER.resolveLiveDbInSystemUi(am);
                boolean active = QsVuTelemetryBridge.isBassTileActive();
                BASS_RENDERER.stepAndDrawDirect(canvas, left, top, width, height, liveBassDb, active);

                long now = SystemClock.uptimeMillis();
                if (bassFpsWindowStartMs == 0L || now - bassFpsWindowStartMs > 3000L) {
                    bassFpsWindowStartMs = now;
                    bassFrameCount = 1;
                } else {
                    bassFrameCount++;
                    long elapsed = now - bassFpsWindowStartMs;
                    if (elapsed >= 1000L) {
                        float fps = (bassFrameCount * 1000.0f) / elapsed;
                        android.util.Log.i("V60BassLab", String.format(java.util.Locale.US,
                                "QS VSYNC Render Rate: %.1f FPS (%d frames in %d ms, db=%.1f)",
                                fps, bassFrameCount, elapsed, liveBassDb));
                        bassFpsWindowStartMs = now;
                        bassFrameCount = 0;
                    }
                }
            } else {
                float liveTrebleDb = TREBLE_RENDERER.resolveLiveDbInSystemUi(am);
                boolean active = QsVuTelemetryBridge.isTrebleTileActive();
                TREBLE_RENDERER.stepAndDrawDirect(canvas, left, top, width, height, liveTrebleDb, active);
            }
        } finally {
            canvas.restoreToCount(sc);
        }
    }

    /**
     * Bucle Choreographer VSYNC a 60/120 FPS en el hilo principal de SystemUI.
     * Invalida los DrawablePainters de VU Bass y VU Treble en cada fotograma mientras Quick Settings está abierto.
     */
    private static final Choreographer.FrameCallback VSYNC_CALLBACK = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            long nowMs = SystemClock.uptimeMillis();
            if (nowMs - lastDrawUptimeMs > 650L) {
                // El panel Quick Settings se cerró; pausar bucle VSYNC con 0% de uso de CPU
                choreographerRunning = false;
                return;
            }

            try {
                WeakReference<BitmapDrawable> bassRef = activeBassDrawableRef;
                if (bassRef != null) {
                    BitmapDrawable bassBd = bassRef.get();
                    if (bassBd != null) {
                        bassBd.invalidateSelf();
                    }
                }
                WeakReference<BitmapDrawable> trebleRef = activeTrebleDrawableRef;
                if (trebleRef != null) {
                    BitmapDrawable trebleBd = trebleRef.get();
                    if (trebleBd != null) {
                        trebleBd.invalidateSelf();
                    }
                }
            } catch (Throwable ignored) {}

            if (choreographerRunning) {
                try {
                    Choreographer.getInstance().postFrameCallback(this);
                } catch (Throwable t) {
                    choreographerRunning = false;
                }
            }
        }
    };

    private static void ensureChoreographerLoopRunning() {
        lastDrawUptimeMs = SystemClock.uptimeMillis();
        if (!choreographerRunning && Looper.myLooper() == Looper.getMainLooper()) {
            choreographerRunning = true;
            BASS_RENDERER.resetTiming();
            TREBLE_RENDERER.resetTiming();
            try {
                Choreographer.getInstance().postFrameCallback(VSYNC_CALLBACK);
            } catch (Throwable t) {
                choreographerRunning = false;
            }
        }
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam == null || !"com.android.systemui".equals(lpparam.packageName)) {
            return;
        }

        try {
            QsVuTelemetryBridge.startReceiverInSystemUi();
            XposedBridge.log("[V60BassLab] Motor Vectorial 60 FPS VSYNC + UDP Bridge activo en SystemUI");

            // 1. Bloquear tintado monocromático en BitmapDrawable
            XposedBridge.hookAllMethods(BitmapDrawable.class, "setColorFilter", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.thisObject instanceof BitmapDrawable) {
                            Bitmap bmp = ((BitmapDrawable) param.thisObject).getBitmap();
                            if (getV60VuType(bmp) != 0 && param.args != null && param.args.length > 0 && param.args[0] != null) {
                                param.args[0] = null;
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            });

            XposedBridge.hookAllMethods(BitmapDrawable.class, "setTintList", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.thisObject instanceof BitmapDrawable) {
                            Bitmap bmp = ((BitmapDrawable) param.thisObject).getBitmap();
                            if (getV60VuType(bmp) != 0 && param.args != null && param.args.length > 0 && param.args[0] != null) {
                                param.args[0] = null;
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            });

            // 2. Interceptar BitmapDrawable.draw(Canvas) y renderizar directamente el calibre vectorial a 60 FPS VSYNC
            XposedBridge.hookAllMethods(BitmapDrawable.class, "draw", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!(param.thisObject instanceof BitmapDrawable)) return;
                        if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof Canvas)) return;

                        BitmapDrawable bd = (BitmapDrawable) param.thisObject;
                        int vuType = getV60VuType(bd.getBitmap());
                        if (vuType == 0) return;

                        if (vuType == 1) {
                            activeBassDrawableRef = new WeakReference<>(bd);
                        } else {
                            activeTrebleDrawableRef = new WeakReference<>(bd);
                        }
                        ensureChoreographerLoopRunning();

                        Rect b = bd.getBounds();
                        if (b != null && b.width() > 0 && b.height() > 0) {
                            Canvas canvas = (Canvas) param.args[0];
                            renderDirect60FpsGauge(canvas, b.left, b.top, b.width(), b.height(), vuType);
                            // Omitir el dibujado del Bitmap estático para máxima nitidez vectorial y 60 FPS puros
                            param.setResult(null);
                        }
                    } catch (Throwable ignored) {}
                }
            });

            // 3. Fallback para Canvas.drawBitmap directo si algún contenedor no pasa por BitmapDrawable.draw
            XC_MethodHook drawBitmapHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length < 2 || !(param.args[0] instanceof Bitmap)) return;
                        Bitmap bmp = (Bitmap) param.args[0];
                        int vuType = getV60VuType(bmp);
                        if (vuType == 0) return;

                        if (param.thisObject instanceof Canvas) {
                            Canvas canvas = (Canvas) param.thisObject;
                            if (param.args.length >= 3 && param.args[2] instanceof Rect) {
                                Rect dst = (Rect) param.args[2];
                                if (dst.width() > 0 && dst.height() > 0) {
                                    ensureChoreographerLoopRunning();
                                    renderDirect60FpsGauge(canvas, dst.left, dst.top, dst.width(), dst.height(), vuType);
                                    param.setResult(null);
                                }
                            } else if (param.args.length >= 3 && param.args[2] instanceof RectF) {
                                RectF dst = (RectF) param.args[2];
                                if (dst.width() > 0f && dst.height() > 0f) {
                                    ensureChoreographerLoopRunning();
                                    renderDirect60FpsGauge(canvas, dst.left, dst.top, dst.width(), dst.height(), vuType);
                                    param.setResult(null);
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            };

            XposedBridge.hookAllMethods(Canvas.class, "drawBitmap", drawBitmapHook);
            try {
                Class<?> baseRecCanvas = Class.forName("android.graphics.BaseRecordingCanvas", false, lpparam.classLoader);
                XposedBridge.hookAllMethods(baseRecCanvas, "drawBitmap", drawBitmapHook);
            } catch (Throwable ignored) {}

        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }
}
