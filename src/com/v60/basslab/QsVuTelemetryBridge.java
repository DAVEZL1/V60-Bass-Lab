package com.v60.basslab;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * QsVuTelemetryBridge
 *
 * Puente de telemetría acústica de latencia ultra-baja (~0.015 ms) por loopback UDP (127.0.0.1:49660)
 * entre AudioDspService / TileServices (com.v60.basslab) y el motor de renderizado de 60 FPS VSYNC
 * inyectado en com.android.systemui (SystemUiQsHook).
 *
 * Evita por completo el cuello de botella de Binder IPC en TileService.updateTile() (~10 FPS),
 * permitiendo que SystemUI renderice los dos vúmetros (VU Bass y VU Treble) a 60 FPS nativos
 * directamente sobre el Hardware Canvas de Jetpack Compose.
 */
public final class QsVuTelemetryBridge {

    public static final int UDP_PORT = 49660;

    public static final int FLAG_MASTER_ENABLED = 1 << 0;
    public static final int FLAG_MAX_SOUND      = 1 << 1;
    public static final int FLAG_SONY_XB66      = 1 << 2;
    public static final int FLAG_LIVE_3D_EQ     = 1 << 3;
    public static final int FLAG_AIR_BOOST      = 1 << 4;

    // Estado recibido en SystemUI (volatile para lectura lock-free a 60 FPS en UI Thread)
    private static volatile float rxBassDb = -20.0f;
    private static volatile float rxTrebleDb = -20.0f;
    private static volatile int rxFlags = FLAG_MASTER_ENABLED;
    private static volatile long rxLastUptimeMs = 0L;
    private static volatile boolean receiverStarted = false;

    // Estado del emisor asíncrono en com.v60.basslab
    private static HandlerThread senderThread;
    private static Handler senderHandler;
    private static DatagramSocket txSocket;
    private static DatagramPacket txPacket;
    private static final byte[] txBuffer = new byte[12];
    private static volatile float pendingBassDb = -20.0f;
    private static volatile float pendingTrebleDb = -20.0f;
    private static volatile int pendingFlags = FLAG_MASTER_ENABLED;
    private static volatile boolean sendScheduled = false;

    private QsVuTelemetryBridge() {}

    /**
     * Empaqueta y envía de forma asíncrona (cero bloqueo en UI/FFT thread, cero allocs por frame)
     * los niveles instantáneos de Graves y Agudos hacia SystemUI (127.0.0.1:49660).
     */
    public static void sendFromService(AudioDspService service) {
        if (service == null) return;
        int flags = 0;
        if (service.isMasterEnabled()) flags |= FLAG_MASTER_ENABLED;
        if (service.isMaxSoundActive()) flags |= FLAG_MAX_SOUND;
        if (service.isSonyXb66Active()) flags |= FLAG_SONY_XB66;
        if (service.getAiAnalyzer() != null && service.getAiAnalyzer().isLiveDynamicEqActive()) {
            flags |= FLAG_LIVE_3D_EQ;
        }
        if (service.getCurrentAirDb() >= 4.5f) flags |= FLAG_AIR_BOOST;

        sendAsync(service.getCurrentLeftDb(), service.getCurrentRightDb(), flags);
    }

    public static void sendAsync(float bassDb, float trebleDb, int flags) {
        pendingBassDb = bassDb;
        pendingTrebleDb = trebleDb;
        pendingFlags = flags;

        ensureSenderInitialized();
        if (senderHandler != null && !sendScheduled) {
            sendScheduled = true;
            senderHandler.post(SEND_TASK);
        }
    }

    private static synchronized void ensureSenderInitialized() {
        if (senderHandler != null) return;
        try {
            senderThread = new HandlerThread("V60-VuUdpTx");
            senderThread.setDaemon(true);
            senderThread.start();
            senderHandler = new Handler(senderThread.getLooper());
        } catch (Throwable ignored) {}
    }

    private static final Runnable SEND_TASK = new Runnable() {
        @Override
        public void run() {
            sendScheduled = false;
            try {
                if (txSocket == null || txSocket.isClosed()) {
                    txSocket = new DatagramSocket();
                    InetAddress loopback = InetAddress.getByName("127.0.0.1");
                    txPacket = new DatagramPacket(txBuffer, txBuffer.length, loopback, UDP_PORT);
                }
                int bBits = Float.floatToRawIntBits(pendingBassDb);
                int tBits = Float.floatToRawIntBits(pendingTrebleDb);
                int f = pendingFlags;

                txBuffer[0] = (byte) 0x56; // 'V'
                txBuffer[1] = (byte) 0x36; // '6'
                txBuffer[2] = (byte) (f & 0xFF);
                txBuffer[3] = 0;

                txBuffer[4] = (byte) (bBits >>> 24);
                txBuffer[5] = (byte) (bBits >>> 16);
                txBuffer[6] = (byte) (bBits >>> 8);
                txBuffer[7] = (byte) bBits;

                txBuffer[8]  = (byte) (tBits >>> 24);
                txBuffer[9]  = (byte) (tBits >>> 16);
                txBuffer[10] = (byte) (tBits >>> 8);
                txBuffer[11] = (byte) tBits;

                txSocket.send(txPacket);
            } catch (Throwable ignored) {}
        }
    };

    /**
     * Inicia el hilo receptor UDP en com.android.systemui (puerto 127.0.0.1:49660).
     */
    public static synchronized void startReceiverInSystemUi() {
        if (receiverStarted) return;
        receiverStarted = true;

        Thread rxThread = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[16];
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                while (true) {
                    DatagramSocket sock = null;
                    try {
                        sock = new DatagramSocket(UDP_PORT, InetAddress.getByName("127.0.0.1"));
                        while (!sock.isClosed()) {
                            pkt.setLength(buf.length);
                            sock.receive(pkt);
                            if (pkt.getLength() >= 12 && buf[0] == (byte) 0x56 && buf[1] == (byte) 0x36) {
                                int flags = buf[2] & 0xFF;
                                int bBits = ((buf[4] & 0xFF) << 24)
                                          | ((buf[5] & 0xFF) << 16)
                                          | ((buf[6] & 0xFF) << 8)
                                          |  (buf[7] & 0xFF);
                                int tBits = ((buf[8] & 0xFF) << 24)
                                          | ((buf[9] & 0xFF) << 16)
                                          | ((buf[10] & 0xFF) << 8)
                                          |  (buf[11] & 0xFF);

                                float bDb = Float.intBitsToFloat(bBits);
                                float tDb = Float.intBitsToFloat(tBits);
                                if (!Float.isNaN(bDb) && !Float.isInfinite(bDb)) {
                                    rxBassDb = Math.max(-20.0f, Math.min(3.0f, bDb));
                                }
                                if (!Float.isNaN(tDb) && !Float.isInfinite(tDb)) {
                                    rxTrebleDb = Math.max(-20.0f, Math.min(3.0f, tDb));
                                }
                                rxFlags = flags;
                                rxLastUptimeMs = SystemClock.uptimeMillis();
                            }
                        }
                    } catch (Throwable ignored) {
                        try {
                            Thread.sleep(1000L);
                        } catch (InterruptedException ie) {
                            break;
                        }
                    } finally {
                        if (sock != null) {
                            try { sock.close(); } catch (Throwable ignored) {}
                        }
                    }
                }
            }
        }, "V60-VuUdpRx");
        rxThread.setDaemon(true);
        rxThread.start();
    }

    public static boolean hasFreshTelemetry() {
        long age = SystemClock.uptimeMillis() - rxLastUptimeMs;
        return rxLastUptimeMs > 0L && age >= 0L && age <= 450L;
    }

    public static float getRxBassDb() {
        return rxBassDb;
    }

    public static float getRxTrebleDb() {
        return rxTrebleDb;
    }

    public static int getRxFlags() {
        return rxFlags;
    }

    public static boolean isBassTileActive() {
        int f = rxFlags;
        return (f & (FLAG_MAX_SOUND | FLAG_SONY_XB66 | FLAG_LIVE_3D_EQ | FLAG_MASTER_ENABLED)) != 0;
    }

    public static boolean isTrebleTileActive() {
        int f = rxFlags;
        return (f & (FLAG_LIVE_3D_EQ | FLAG_AIR_BOOST | FLAG_MASTER_ENABLED)) != 0;
    }
}
