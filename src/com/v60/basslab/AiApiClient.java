package com.v60.basslab;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AiApiClient {

    private static final String TAG = "V60_AiApiClient";
    private static final String PREFS_NAME = "v60_ai_prefs";
    private static final String KEY_PROVIDER = "ai_provider";
    private static final String KEY_API_KEY = "ai_api_key";
    private static final String KEY_CUSTOM_URL = "ai_custom_url";

    public static final String PROVIDER_GEMINI = "Google Gemini";
    public static final String PROVIDER_OPENAI = "OpenAI (ChatGPT)";
    public static final String PROVIDER_DEEPSEEK = "DeepSeek";
    public static final String PROVIDER_CLAUDE = "Anthropic (Claude)";
    public static final String PROVIDER_CUSTOM = "Servidor Local (Ollama / Custom)";

    public static final String[] PROVIDERS = {
        PROVIDER_GEMINI,
        PROVIDER_OPENAI,
        PROVIDER_DEEPSEEK,
        PROVIDER_CLAUDE,
        PROVIDER_CUSTOM
    };

    // Modern Gemini models (with automatic fallback)
    private static final String[] GEMINI_MODELS = {
        "gemini-2.5-flash-lite",
        "gemini-flash-latest",
        "gemini-3.8-flash"
    };

    public static class AiEqResult {
        public float subBassDb = 8.5f;      // Banda 1: 32-65 Hz Sub-Grave Infrasónico (-6.0 a +16.0 dB)
        public float punchDb = 6.5f;        // Banda 2: 90-200 Hz Pegada de Bombo / Woofer (-6.0 a +16.0 dB)
        public float warmthDb = -0.5f;      // Banda 3: 250-600 Hz Cuerpo Valvular / Corte Turbidez (-6.0 a +6.0 dB)
        public float midPresenceDb = 2.0f;  // Banda 4: 1-4 kHz Presencia Vocal & Definición (-6.0 a +10.0 dB)
        public float airDb = 3.5f;          // Banda 5: 8-20 kHz Brillo & Aire Super-Tweeter (-6.0 a +12.0 dB)
        public int bassBoost = 600;         // Excitador Armónico Hardware Qualcomm Aqstic (0 a 1000)
        public float mbcBassAlcDb = 1.6f;   // Compresor Dinámico de Graves ALC / DBFB Sustain (0.0 a +4.0 dB)
        public float preampGainDb = 0.0f;   // Ganancia de Pre-Amplificación / Headroom (-6.0 a +3.0 dB)
        public int masterBassPercent = 70;
        public int essFilterIndex = 0;
        public int acousticProfileIndex = -1; // -1 = Curva Paramétrica IA Personalizada, 0..4 = Perfil Acústico
        public boolean enablePhilipsMax = false;
        public boolean enableSonyXb66 = false;
        public int dbbLevel = 0;
        public int dscPreset = 0;
        public boolean forceHighImp = true;
        public String title = "Perfil Personalizado";
        public String description = "Calibrado por IA";
    }

    public interface AiApiCallback {
        void onSuccess(AiEqResult result);
        void onError(String errorMessage);
    }

    private final SharedPreferences prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public AiApiClient(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void saveSettings(String provider, String apiKey, String customUrl) {
        prefs.edit()
            .putString(KEY_PROVIDER, provider)
            .putString(KEY_API_KEY, apiKey)
            .putString(KEY_CUSTOM_URL, customUrl)
            .apply();
        AppLogger.i(TAG, "Configuración IA guardada: Proveedor=" + provider + ", URL=" + customUrl);
    }

    public String getSavedProvider() {
        return prefs.getString(KEY_PROVIDER, PROVIDER_GEMINI);
    }

    public String getSavedApiKey() {
        return prefs.getString(KEY_API_KEY, "");
    }

    public String getSavedCustomUrl() {
        return prefs.getString(KEY_CUSTOM_URL, "http://localhost:11434/v1/chat/completions");
    }

    public void generateEqFromPrompt(String userPrompt, AiApiCallback callback) {
        String provider = getSavedProvider();
        String apiKey = getSavedApiKey().trim();
        String customUrl = getSavedCustomUrl().trim();

        if (apiKey.isEmpty() && !provider.equals(PROVIDER_CUSTOM)) {
            String err = "Please enter your API Key in the Smart AI settings to connect.";
            AppLogger.w(TAG, err);
            callback.onError(err);
            return;
        }

        String maskedKey = apiKey.length() > 8 ? apiKey.substring(0, 6) + "..." + apiKey.substring(apiKey.length() - 3) : "***";
        AppLogger.i(TAG, "Starting AI calibration with [" + provider + "], Key=" + maskedKey + ", Prompt: '" + userPrompt + "'");

        executor.execute(() -> {
            long startTime = System.currentTimeMillis();
            try {
                String systemInstruction = "You are a professional acoustic engineer and mastering technician calibrating the 8-parameter parametric console for an LG V60 ThinQ (ESS Sabre ES9219 Quad DAC chip, Philips FW-M576 TDA7468D and Sony LBT-XB66 M62427FP SAW acoustic suites). " +
                        "Available parameters in Manual Parametric Calibration: " +
                        "1) subBassDb (32-65Hz Infrasonic Sub-Bass, range -6.0 to +16.0 dB), " +
                        "2) punchDb (90-200Hz Kick/Woofer Punch, range -6.0 to +16.0 dB), " +
                        "3) clarityDb (250-600Hz Tube Body / Mud Cut, range -6.0 to +6.0 dB: negative cleans mud, positive gives tube warmth/body), " +
                        "4) midPresenceDb (1-4kHz Vocal & Instrumental Presence, range -6.0 to +10.0 dB), " +
                        "5) airDb (8-20kHz Super-Tweeter Sparkle & Air, range -6.0 to +12.0 dB), " +
                        "6) bassBoost (Hardware Harmonic Exciter, range 0 to 1000), " +
                        "7) mbcBassAlcDb (Dynamic Bass Compressor ALC / DBFB Sustain, range 0.0 to 4.0 dB), " +
                        "8) preampGainDb (Pre-Amplifier Gain / Headroom, range -6.0 to +3.0 dB). " +
                        "Also: essFilterIndex (0=Sharp Roll-Off, 1=Slow Roll-Off, 2=Short Delay, 8=Hybrid Fast, 9=Linear Phase), acousticProfileIndex (-1 to use your custom parametric curve, or 0=90s Stereo, 1=12'' Car Subwoofer, 2=Funktion-One Club Punch, 3=70s Tube Vinyl, 4=Audiophile Direct 0dB). " +
                        "Adjust all parameters according to the user request: '" + userPrompt + "'. " +
                        "You MUST respond ONLY with a valid JSON object matching this exact structure with no markdown formatting or backticks: " +
                        "{\"subBassDb\": 11.5, \"punchDb\": 7.5, \"clarityDb\": -1.5, \"midPresenceDb\": 2.5, \"airDb\": 4.0, \"bassBoost\": 650, \"mbcBassAlcDb\": 1.8, \"preampGainDb\": 0.0, \"masterBassPercent\": 75, \"essFilterIndex\": 0, \"acousticProfileIndex\": -1, \"enablePhilipsMax\": false, \"enableSonyXb66\": false, \"dbbLevel\": 0, \"dscPreset\": 0, \"forceHighImp\": true, \"title\": \"Short Name\", \"description\": \"Brief explanation in English\"}";

                String jsonResponse = null;
                Exception lastException = null;

                if (provider.equals(PROVIDER_GEMINI)) {
                    // Try models in order of speed and availability
                    for (String model : GEMINI_MODELS) {
                        try {
                            AppLogger.i(TAG, "Intentando conexión con Gemini modelo: " + model);
                            jsonResponse = callGeminiWithModel(apiKey, model, systemInstruction);
                            if (jsonResponse != null) break;
                        } catch (Exception ex) {
                            AppLogger.w(TAG, "Fallo con modelo " + model + ": " + ex.getMessage());
                            lastException = ex;
                        }
                    }
                    if (jsonResponse == null && lastException != null) {
                        throw lastException;
                    }
                } else if (provider.equals(PROVIDER_OPENAI)) {
                    jsonResponse = callOpenAiCompatible("https://api.openai.com/v1/chat/completions", apiKey, "gpt-4o-mini", systemInstruction);
                } else if (provider.equals(PROVIDER_DEEPSEEK)) {
                    jsonResponse = callOpenAiCompatible("https://api.deepseek.com/chat/completions", apiKey, "deepseek-chat", systemInstruction);
                } else if (provider.equals(PROVIDER_CLAUDE)) {
                    jsonResponse = callClaude(apiKey, systemInstruction);
                } else {
                    jsonResponse = callOpenAiCompatible(customUrl, apiKey, "llama3", systemInstruction);
                }

                long elapsed = System.currentTimeMillis() - startTime;
                AppLogger.i(TAG, "Respuesta recibida con éxito en " + elapsed + " ms. Parseando JSON...");

                AiEqResult result = parseAiResponse(jsonResponse);
                AppLogger.i(TAG, "Perfil generado: " + result.title + " (Sub=" + result.subBassDb + "dB, Punch=" + result.punchDb + "dB, LowMid=" + result.warmthDb + "dB, Mid=" + result.midPresenceDb + "dB, Air=" + result.airDb + "dB, ALC=" + result.mbcBassAlcDb + "dB)");

                mainHandler.post(() -> callback.onSuccess(result));

            } catch (Exception e) {
                long elapsed = System.currentTimeMillis() - startTime;
                AppLogger.e(TAG, "Error de llamada a la API (" + elapsed + "ms): " + e.getMessage(), e);
                mainHandler.post(() -> callback.onError("Error IA: " + e.getMessage()));
            }
        });
    }

    private String callGeminiWithModel(String apiKey, String modelName, String prompt) throws Exception {
        String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/" + modelName + ":generateContent?key=" + apiKey;
        URL url = new URL(endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; utf-8");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);

        JSONObject part = new JSONObject();
        part.put("text", prompt);
        JSONArray partsArray = new JSONArray();
        partsArray.put(part);

        JSONObject content = new JSONObject();
        content.put("parts", partsArray);
        JSONArray contentsArray = new JSONArray();
        contentsArray.put(content);

        JSONObject requestBody = new JSONObject();
        requestBody.put("contents", contentsArray);

        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = requestBody.toString().getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        String responseText = readStream(is);

        if (code >= 200 && code < 300) {
            JSONObject root = new JSONObject(responseText);
            JSONArray candidates = root.getJSONArray("candidates");
            if (candidates.length() > 0) {
                JSONObject firstCandidate = candidates.getJSONObject(0);
                JSONObject c = firstCandidate.getJSONObject("content");
                JSONArray parts = c.getJSONArray("parts");
                return parts.getJSONObject(0).getString("text");
            }
        }
        throw new Exception("HTTP " + code + " en " + modelName + ": " + responseText);
    }

    private String callOpenAiCompatible(String endpoint, String apiKey, String model, String prompt) throws Exception {
        AppLogger.i(TAG, "Conectando a endpoint OpenAI compatible: " + endpoint + " (modelo: " + model + ")");
        URL url = new URL(endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; utf-8");
        if (!apiKey.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        }
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);

        JSONObject message = new JSONObject();
        message.put("role", "user");
        message.put("content", prompt);

        JSONArray messages = new JSONArray();
        messages.put(message);

        JSONObject requestBody = new JSONObject();
        requestBody.put("model", model);
        requestBody.put("messages", messages);
        requestBody.put("temperature", 0.5);

        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = requestBody.toString().getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        String responseText = readStream(is);

        if (code >= 200 && code < 300) {
            JSONObject root = new JSONObject(responseText);
            JSONArray choices = root.getJSONArray("choices");
            if (choices.length() > 0) {
                return choices.getJSONObject(0).getJSONObject("message").getString("content");
            }
        }
        throw new Exception("HTTP " + code + ": " + responseText);
    }

    private String callClaude(String apiKey, String prompt) throws Exception {
        AppLogger.i(TAG, "Conectando a Anthropic Claude API...");
        URL url = new URL("https://api.anthropic.com/v1/messages");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; utf-8");
        conn.setRequestProperty("x-api-key", apiKey);
        conn.setRequestProperty("anthropic-version", "2023-06-01");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);

        JSONObject message = new JSONObject();
        message.put("role", "user");
        message.put("content", prompt);
        JSONArray messages = new JSONArray();
        messages.put(message);

        JSONObject requestBody = new JSONObject();
        requestBody.put("model", "claude-3-5-sonnet-20241022");
        requestBody.put("max_tokens", 500);
        requestBody.put("messages", messages);

        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = requestBody.toString().getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        String responseText = readStream(is);

        if (code >= 200 && code < 300) {
            JSONObject root = new JSONObject(responseText);
            JSONArray content = root.getJSONArray("content");
            if (content.length() > 0) {
                return content.getJSONObject(0).getString("text");
            }
        }
        throw new Exception("HTTP " + code + ": " + responseText);
    }

    private AiEqResult parseAiResponse(String rawText) throws Exception {
        int start = rawText.indexOf('{');
        int end = rawText.lastIndexOf('}');
        if (start != -1 && end != -1 && end > start) {
            rawText = rawText.substring(start, end + 1);
        }

        JSONObject json = new JSONObject(rawText);
        AiEqResult res = new AiEqResult();
        res.subBassDb = (float) json.optDouble("subBassDb", 8.5);
        res.punchDb = (float) json.optDouble("punchDb", 6.5);
        res.warmthDb = (float) json.optDouble("clarityDb", json.optDouble("warmthDb", -0.5));
        res.midPresenceDb = (float) json.optDouble("midPresenceDb", 2.0);
        res.airDb = (float) json.optDouble("airDb", 3.5);
        res.bassBoost = json.optInt("bassBoost", 600);
        res.mbcBassAlcDb = (float) json.optDouble("mbcBassAlcDb", 1.6);
        res.preampGainDb = (float) json.optDouble("preampGainDb", 0.0);
        res.masterBassPercent = json.optInt("masterBassPercent", 70);
        res.essFilterIndex = json.optInt("essFilterIndex", 0);
        res.acousticProfileIndex = json.optInt("acousticProfileIndex", -1);
        res.enablePhilipsMax = json.optBoolean("enablePhilipsMax", false);
        res.enableSonyXb66 = json.optBoolean("enableSonyXb66", false);
        res.dbbLevel = json.optInt("dbbLevel", 0);
        res.dscPreset = json.optInt("dscPreset", 0);
        res.forceHighImp = json.optBoolean("forceHighImp", true);
        res.title = json.optString("title", "Perfil IA");
        res.description = json.optString("description", "Ajustado por IA");
        return res;
    }

    private String readStream(InputStream is) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        return sb.toString();
    }
}
