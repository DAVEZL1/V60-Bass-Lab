package com.v60.basslab;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

public class AppLogger {

    private static final String TAG = "V60_Logger";
    private static final int MAX_BUFFER_LINES = 200;

    private static final LinkedList<String> logBuffer = new LinkedList<>();
    private static Context appContext;
    private static final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    public interface LogUpdateListener {
        void onLogAdded(String logLine);
    }
    private static LogUpdateListener listener;
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static void init(Context context) {
        appContext = context.getApplicationContext();
        i("V60_Logger", "=== V60 Bass Lab iniciado - Logger activo ===");
    }

    public static void setListener(LogUpdateListener l) {
        listener = l;
    }

    public static synchronized void d(String tag, String message) {
        Log.d(tag, message);
        appendLog("DEBUG", tag, message);
    }

    public static synchronized void i(String tag, String message) {
        Log.i(tag, message);
        appendLog("INFO", tag, message);
    }

    public static synchronized void w(String tag, String message) {
        Log.w(tag, message);
        appendLog("WARN", tag, message);
    }

    public static synchronized void e(String tag, String message, Throwable t) {
        Log.e(tag, message, t);
        String trace = "";
        if (t != null) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            trace = "\n" + sw.toString();
        }
        appendLog("ERROR", tag, message + trace);
    }

    private static synchronized void appendLog(String level, String tag, String message) {
        String time = timeFormat.format(new Date());
        String entry = String.format("[%s] [%s] [%s] %s", time, level, tag, message);

        if (logBuffer.size() >= MAX_BUFFER_LINES) {
            logBuffer.removeFirst();
        }
        logBuffer.addLast(entry);

        if (listener != null) {
            mainHandler.post(() -> {
                if (listener != null) listener.onLogAdded(entry);
            });
        }

        // Write to internal file asynchronously
        writeToFile(entry + "\n");
    }

    public static synchronized String getAllLogs() {
        StringBuilder sb = new StringBuilder();
        for (String line : logBuffer) {
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    public static synchronized void clear() {
        logBuffer.clear();
        appendLog("INFO", "V60_Logger", "--- Logs reiniciados ---");
    }

    public static File exportLogsToDownload() {
        try {
            File dest = new File("/storage/emulated/0/Download/V60BassLab_debug.log");
            try (FileOutputStream fos = new FileOutputStream(dest, false)) {
                fos.write(getAllLogs().getBytes(StandardCharsets.UTF_8));
            }
            return dest;
        } catch (Exception e) {
            Log.e(TAG, "Error exportando logs a Download: ", e);
            return null;
        }
    }

    private static void writeToFile(String line) {
        if (appContext == null) return;
        try {
            File logFile = new File(appContext.getFilesDir(), "debug.log");
            try (FileOutputStream fos = new FileOutputStream(logFile, true)) {
                fos.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }
}
