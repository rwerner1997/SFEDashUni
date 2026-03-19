package com.sfe.dashboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI-assisted Mode 22 PID identification.
 *
 * Reads the most recent sfe_*.csv session log produced by OBDLogger, extracts
 * time-series data for unknown PIDs alongside known reference channels, and asks
 * the Claude API (claude-haiku-4-5) to characterise each unknown PID.
 *
 * Runs on a daemon background Thread — never touches the Bluetooth socket or
 * any OBDManager internals.  Results are written to DashData volatile fields
 * (for the DashView overlay) and persisted to SharedPreferences.
 *
 * Trigger: long-press RIGHT button on SESSION page (MainActivity.triggerPidAnalysis).
 * Cancel:  call cancel() — interrupts the background thread.
 */
public class PidAnalyzer {

    private static final String TAG         = "PidAnalyzer";
    private static final String PREFS_NAME  = "sfe_pid_analysis";
    private static final String KEY_API_KEY = "sfe_claude_api_key";
    private static final String API_URL     = "https://api.anthropic.com/v1/messages";
    private static final String MODEL_ID    = "claude-haiku-4-5-20251001";
    private static final int    MAX_TOKENS  = 1500;
    private static final int    MAX_SAMPLES = 60;      // samples per PID sent to Claude
    private static final int    BATCH_SIZE  = 5;       // unknown PIDs per API call
    private static final int    HTTP_TIMEOUT_MS = 30_000;
    private static final int    MIN_SAMPLES = 5;       // skip PIDs with fewer samples

    // Known reference channels always extracted from the log for Claude's context
    private static final String[] REFERENCE_PIDS = {
        "2210A8",   // wastegate duty %
        "2210B0",   // fine knock learning °
        "2210B1",   // DAM
        "2210D2",   // CVT temp °C
        "221093",   // shift selector A
    };
    private static final String[] REFERENCE_LABELS = {
        "wastegate duty %, formula=byte/2.55",
        "fine knock learning °, formula=byte/4-32",
        "DAM ratio, formula=byte/16",
        "CVT fluid temp °C, formula=byte-50",
        "shift selector A (raw byte; D=0x00,P=0x04,R=0x06)",
    };

    // ── State ─────────────────────────────────────────────────────
    private final Context     ctx;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread            analysisThread;

    /** Callback invoked on analysis completion with the result list. */
    public interface ResultCallback {
        void onResults(List<PidResult> results);
    }

    public PidAnalyzer(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    // ── API key storage ───────────────────────────────────────────

    public void setApiKey(String key) {
        getPrefs().edit().putString(KEY_API_KEY, key.trim()).apply();
    }

    public String getApiKey() {
        return getPrefs().getString(KEY_API_KEY, "");
    }

    public boolean hasApiKey() {
        String k = getApiKey();
        return k != null && k.startsWith("sk-ant-");
    }

    // ── Control ───────────────────────────────────────────────────

    public boolean isRunning() { return running.get(); }

    /**
     * Start AI analysis on a background daemon thread.
     *
     * @param unknownPids  PIDs to identify (6-char strings e.g. "221138")
     * @param ecu          "ECM" or "TCU" — primary ECU for the unknowns
     * @param callback     optional callback when done (may be null)
     */
    public void startAnalysis(List<String> unknownPids, String ecu, ResultCallback callback) {
        if (running.getAndSet(true)) return;   // already running — ignore

        DashData d = DashData.get();
        d.analysisRunning  = true;
        d.analysisPhase    = "STARTING";
        d.analysisProgress = 0f;
        d.analysisError    = null;

        final List<String> pids = new ArrayList<>(unknownPids);

        analysisThread = new Thread(() -> {
            List<PidResult> results = new ArrayList<>();
            try {
                results = runAnalysis(pids, ecu);
                if (callback != null) callback.onResults(results);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                DashData.get().analysisPhase = "CANCELLED";
            } catch (Exception e) {
                Log.e(TAG, "Analysis failed", e);
                String msg = e.getMessage();
                if (msg != null && msg.length() > 60) msg = msg.substring(0, 60);
                DashData.get().analysisError = msg;
                DashData.get().analysisPhase = "ERROR";
            } finally {
                running.set(false);
                DashData d2 = DashData.get();
                d2.analysisRunning = false;
                if (!results.isEmpty()) {
                    //noinspection unchecked
                    d2.lastAnalysisResults = results;
                }
            }
        }, "PidAnalyzer");
        analysisThread.setDaemon(true);
        analysisThread.start();
    }

    public void cancel() {
        if (analysisThread != null) analysisThread.interrupt();
        running.set(false);
        DashData.get().analysisRunning = false;
    }

    // ── Core pipeline ─────────────────────────────────────────────

    private List<PidResult> runAnalysis(List<String> unknownPids, String ecu)
            throws Exception {
        DashData d = DashData.get();

        // 1. Find most recent session log
        d.analysisPhase    = "FINDING LOG FILE";
        d.analysisProgress = 0.05f;
        File logFile = findMostRecentLog();
        if (logFile == null) throw new IOException("No session log found. Drive first.");

        // 2. Read the log and extract time-series
        d.analysisPhase    = "READING LOG";
        d.analysisProgress = 0.15f;
        List<String> pidsToExtract = new ArrayList<>();
        pidsToExtract.addAll(Arrays.asList(REFERENCE_PIDS));
        pidsToExtract.addAll(unknownPids);
        Map<String, List<float[]>> timeSeries = readLogFile(logFile, pidsToExtract);
        d.analysisProgress = 0.30f;

        // 3. Filter unknown PIDs: skip those with < MIN_SAMPLES
        List<String> eligible = new ArrayList<>();
        for (String pid : unknownPids) {
            List<float[]> samples = timeSeries.get(pid.toUpperCase());
            if (samples != null && samples.size() >= MIN_SAMPLES) eligible.add(pid);
        }
        if (eligible.isEmpty()) throw new IOException(
                "No unknown PIDs have enough log samples (need " + MIN_SAMPLES + "+).");

        // 4. Downsample reference channels
        Map<String, float[]> refSampled = new LinkedHashMap<>();
        for (int i = 0; i < REFERENCE_PIDS.length; i++) {
            List<float[]> s = timeSeries.get(REFERENCE_PIDS[i].toUpperCase());
            if (s != null && !s.isEmpty()) {
                refSampled.put(REFERENCE_LABELS[i], downsample(s, MAX_SAMPLES / 2));
            }
        }

        // 5. Process in batches of BATCH_SIZE
        List<PidResult> allResults = new ArrayList<>();
        int totalBatches = (eligible.size() + BATCH_SIZE - 1) / BATCH_SIZE;
        for (int batch = 0; batch < totalBatches; batch++) {
            if (Thread.interrupted()) throw new InterruptedException();

            int from = batch * BATCH_SIZE;
            int to   = Math.min(from + BATCH_SIZE, eligible.size());
            List<String> batchPids = eligible.subList(from, to);

            d.analysisPhase    = "ANALYSING BATCH " + (batch + 1) + "/" + totalBatches;
            d.analysisProgress = 0.30f + 0.55f * (batch / (float) totalBatches);

            // Downsample each unknown PID for this batch
            Map<String, float[]> batchSampled = new LinkedHashMap<>();
            for (String pid : batchPids) {
                List<float[]> s = timeSeries.get(pid.toUpperCase());
                if (s != null) batchSampled.put(pid, downsample(s, MAX_SAMPLES));
            }

            // Build prompt and call API
            d.analysisPhase = "CALLING CLAUDE AI (" + (batch + 1) + "/" + totalBatches + ")";
            String prompt = buildPrompt(refSampled, batchSampled, ecu);
            String apiKey = getApiKey();
            String responseJson = callClaudeApi(apiKey, prompt);

            // Parse and save
            List<PidResult> batchResults = parseClaudeResponse(responseJson, batchPids);
            saveResults(batchResults);
            allResults.addAll(batchResults);
        }

        d.analysisProgress = 0.98f;
        d.analysisPhase    = "DONE  " + allResults.size() + " PIDs IDENTIFIED";
        Thread.sleep(5000);   // keep overlay visible
        return allResults;
    }

    // ── Log discovery ─────────────────────────────────────────────

    /**
     * Find the most recent sfe_*.csv in Documents/SFEDash.
     * Uses MediaStore on API 29+; falls back to direct File access on older APIs.
     */
    private File findMostRecentLog() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Uri uri = MediaStore.Files.getContentUri("external");
            String[] proj = {MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DATE_MODIFIED};
            String sel  = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? AND "
                        + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?";
            String[] args = {"Documents/SFEDash%", "sfe_%.csv"};
            try (Cursor cur = ctx.getContentResolver().query(
                    uri, proj, sel, args,
                    MediaStore.MediaColumns.DATE_MODIFIED + " DESC")) {
                if (cur != null && cur.moveToFirst()) {
                    String path = cur.getString(
                            cur.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA));
                    if (path != null) {
                        File f = new File(path);
                        if (f.exists() && f.length() > 100) return f;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "MediaStore query failed: " + e.getMessage());
            }
            return null;
        } else {
            //noinspection deprecation
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOCUMENTS), "SFEDash");
            File[] files = dir.listFiles(
                    f -> f.getName().startsWith("sfe_") && f.getName().endsWith(".csv"));
            if (files == null || files.length == 0) return null;
            Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            return files[0].length() > 100 ? files[0] : null;
        }
    }

    // ── Log parsing ───────────────────────────────────────────────

    /**
     * Parse sfe_*.csv into per-PID time series.
     * CSV columns: elapsed_s, pid, raw, bytes
     * Returns: Map from uppercase PID string → list of [elapsed_s, first_data_byte]
     */
    private Map<String, List<float[]>> readLogFile(File f, List<String> pidsToExtract)
            throws IOException {
        // Build a fast lookup set (all uppercase)
        java.util.Set<String> wantSet = new java.util.HashSet<>();
        for (String p : pidsToExtract) wantSet.add(p.toUpperCase());

        Map<String, List<float[]>> result = new LinkedHashMap<>();

        try (BufferedReader br = new BufferedReader(new FileReader(f), 65536)) {
            String line;
            boolean header = true;
            while ((line = br.readLine()) != null) {
                if (header) { header = false; continue; }  // skip CSV header
                // Format: elapsed_s,pid,"raw",bytes
                // Split on comma, but beware the raw field may be quoted with internal commas.
                String[] parts = splitCsvLine(line);
                if (parts.length < 4) continue;
                float elapsed;
                try { elapsed = Float.parseFloat(parts[0]); } catch (NumberFormatException e) { continue; }
                String pid = parts[1].trim().toUpperCase();
                if (!wantSet.contains(pid)) continue;

                String bytesCol = parts[3].trim(); // e.g. "0xC2(194)" or "0xC2(194)+0x00(0)"
                if ("ERR".equals(bytesCol)) continue;

                // Extract first byte decimal value from "0xC2(194)..."
                int b0 = extractFirstByte(bytesCol);
                if (b0 < 0) continue;

                if (!result.containsKey(pid)) result.put(pid, new ArrayList<>());
                result.get(pid).add(new float[]{elapsed, b0});
            }
        }
        Log.i(TAG, "Log parsed: " + result.size() + " PIDs extracted from " + f.getName());
        return result;
    }

    /** Split a CSV line respecting double-quoted fields. */
    private String[] splitCsvLine(String line) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') { inQuote = !inQuote; }
            else if (ch == ',' && !inQuote) { parts.add(cur.toString()); cur = new StringBuilder(); }
            else { cur.append(ch); }
        }
        parts.add(cur.toString());
        return parts.toArray(new String[0]);
    }

    /** Extract decimal byte value from bytes column like "0xC2(194)" → 194 */
    private int extractFirstByte(String bytesCol) {
        int open  = bytesCol.indexOf('(');
        int close = bytesCol.indexOf(')');
        if (open < 0 || close <= open) return -1;
        try {
            return Integer.parseInt(bytesCol.substring(open + 1, close));
        } catch (NumberFormatException e) { return -1; }
    }

    // ── Downsampling ──────────────────────────────────────────────

    /**
     * Evenly downsample a time series to at most maxSamples points.
     * Returns a flat float[] of alternating [t0, v0, t1, v1, ...] pairs.
     */
    private float[] downsample(List<float[]> series, int maxSamples) {
        if (series.isEmpty()) return new float[0];
        // Sort by elapsed time just in case
        series.sort(Comparator.comparingDouble(a -> a[0]));
        int n = series.size();
        if (n <= maxSamples) {
            float[] out = new float[n * 2];
            for (int i = 0; i < n; i++) { out[i*2] = series.get(i)[0]; out[i*2+1] = series.get(i)[1]; }
            return out;
        }
        float[] out = new float[maxSamples * 2];
        for (int i = 0; i < maxSamples; i++) {
            int idx = (int) Math.round(i * (n - 1.0) / (maxSamples - 1));
            out[i*2]   = series.get(idx)[0];
            out[i*2+1] = series.get(idx)[1];
        }
        return out;
    }

    // ── Prompt construction ───────────────────────────────────────

    private String buildPrompt(Map<String, float[]> refSampled,
                               Map<String, float[]> unknownSampled,
                               String ecu) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("You are an automotive OBD-II data analyst specialising in ")
          .append("Subaru FA20DIT turbocharged engine ECU (7E0/7E8) and CVT TCU (7E1/7E9) signals.\n\n")
          .append("Vehicle: 2015+ Subaru WRX FA20DIT (turbocharged flat-4, CVT)\n")
          .append("ECU being analysed: ").append(ecu).append("\n\n");

        // Reference channels
        if (!refSampled.isEmpty()) {
            sb.append("KNOWN REFERENCE PIDs (use these for correlation context):\n");
            for (Map.Entry<String, float[]> e : refSampled.entrySet()) {
                sb.append("- ").append(e.getKey()).append(":\n  ");
                float[] tv = e.getValue();
                int max = Math.min(tv.length / 2, 20);
                for (int i = 0; i < max; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(String.format(Locale.US, "t=%.0fs:%.0f", tv[i*2], tv[i*2+1]));
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        // Unknown PIDs — detailed tables
        sb.append("UNKNOWN PIDs TO CHARACTERISE:\n\n");
        for (Map.Entry<String, float[]> e : unknownSampled.entrySet()) {
            String pid = e.getKey();
            float[] tv  = e.getValue();
            int n = tv.length / 2;
            sb.append("PID ").append(pid).append(" (").append(n).append(" samples):\n");
            sb.append("  t(s) | raw_byte | raw_hex\n");
            for (int i = 0; i < n; i++) {
                int t   = Math.round(tv[i*2]);
                int val = Math.round(tv[i*2+1]);
                sb.append(String.format(Locale.US, "  %4d | %8d | 0x%02X\n", t, val, val));
            }
            // Summary stats
            float min = Float.MAX_VALUE, max2 = Float.MIN_VALUE, sum = 0;
            for (int i = 0; i < n; i++) { float v = tv[i*2+1]; if(v<min)min=v; if(v>max2)max2=v; sum+=v; }
            sb.append(String.format(Locale.US, "  Stats: min=%.0f max=%.0f mean=%.1f range=%.0f\n\n",
                    min, max2, sum/n, max2-min));
        }

        sb.append("For each unknown PID determine:\n")
          .append("1. What physical sensor, actuator, or counter this represents\n")
          .append("2. The most likely scaling formula to convert raw byte to engineering units\n")
          .append("   Common Subaru formulas: byte-40(temp°C), byte/4-32(timing°), byte/2(duty%),\n")
          .append("   byte/16(ratio), byte-50(CVT temp°C), word/4(RPM), raw_byte(roughness)\n")
          .append("3. If the range never changes, label it STATIC and set confidence=0.95\n")
          .append("4. Correlate with known reference channels to improve accuracy\n\n")
          .append("IMPORTANT: Respond ONLY with a JSON array and nothing else:\n")
          .append("[{\"pid\":\"221138\",\"label\":\"...\",\"unit\":\"...\",\"formula\":\"...\",")
          .append("\"confidence\":0.7,\"reasoning\":\"...\"}]\n")
          .append("Keep reasoning under 180 characters. confidence: 0.0=guess 0.5=plausible 0.8=confident 0.95=certain");

        return sb.toString();
    }

    // ── API call ──────────────────────────────────────────────────

    private String callClaudeApi(String apiKey, String prompt) throws IOException {
        URL url = new URL(API_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type",     "application/json; charset=utf-8");
        conn.setRequestProperty("x-api-key",        apiKey);
        conn.setRequestProperty("anthropic-version","2023-06-01");
        conn.setConnectTimeout(HTTP_TIMEOUT_MS);
        conn.setReadTimeout(HTTP_TIMEOUT_MS);
        conn.setDoOutput(true);

        // Build request JSON
        JSONObject body    = new JSONObject();
        JSONArray  messages = new JSONArray();
        JSONObject msg     = new JSONObject();
        try {
            body.put("model",      MODEL_ID);
            body.put("max_tokens", MAX_TOKENS);
            msg.put("role",    "user");
            msg.put("content", prompt);
            messages.put(msg);
            body.put("messages", messages);
        } catch (Exception e) {
            throw new IOException("JSON build failed: " + e.getMessage());
        }

        byte[] bodyBytes = body.toString().getBytes(StandardCharsets.UTF_8);
        conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));

        try (OutputStream os = conn.getOutputStream()) {
            os.write(bodyBytes);
        }

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300)
                ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder resp = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) resp.append(line).append('\n');
        }

        if (code == 401) throw new IOException("Invalid API key (HTTP 401)");
        if (code == 429) throw new IOException("Rate limit (HTTP 429) — try again later");
        if (code != 200) throw new IOException("Claude API HTTP " + code + ": " + resp.toString().trim());

        Log.d(TAG, "Claude API response: " + resp.toString().substring(0, Math.min(200, resp.length())));
        return resp.toString();
    }

    // ── Response parsing ──────────────────────────────────────────

    private List<PidResult> parseClaudeResponse(String json, List<String> expectedPids)
            throws Exception {
        // Extract content text from Claude's response envelope
        JSONObject root    = new JSONObject(json);
        JSONArray  content = root.optJSONArray("content");
        String text = "";
        if (content != null && content.length() > 0) {
            text = content.getJSONObject(0).optString("text", "");
        }

        Log.d(TAG, "Claude text: " + text.substring(0, Math.min(300, text.length())));

        // Extract JSON array — find first '[' and last ']'
        int start = text.indexOf('[');
        int end   = text.lastIndexOf(']');
        if (start < 0 || end <= start) {
            Log.w(TAG, "No JSON array in Claude response: " + text.substring(0, Math.min(200, text.length())));
            return buildUnknownResults(expectedPids);
        }

        JSONArray arr = new JSONArray(text.substring(start, end + 1));
        List<PidResult> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            PidResult r = new PidResult();
            r.pid        = o.optString("pid", "");
            r.label      = o.optString("label", "UNKNOWN");
            r.unit       = o.optString("unit", "");
            r.formula    = o.optString("formula", "raw byte");
            r.confidence = (float) o.optDouble("confidence", 0.0);
            r.reasoning  = o.optString("reasoning", "");
            if (r.reasoning.length() > 200) r.reasoning = r.reasoning.substring(0, 197) + "…";
            out.add(r);
        }

        // Back-fill any expected PIDs not returned by Claude
        java.util.Set<String> returned = new java.util.HashSet<>();
        for (PidResult r : out) returned.add(r.pid.toUpperCase());
        for (String pid : expectedPids) {
            if (!returned.contains(pid.toUpperCase())) {
                PidResult r = new PidResult();
                r.pid = pid; r.label = "UNKNOWN"; r.confidence = 0f;
                out.add(r);
            }
        }
        return out;
    }

    private List<PidResult> buildUnknownResults(List<String> pids) {
        List<PidResult> out = new ArrayList<>();
        for (String pid : pids) {
            PidResult r = new PidResult();
            r.pid = pid; r.label = "UNKNOWN"; r.confidence = 0f; r.reasoning = "Parse error";
            out.add(r);
        }
        return out;
    }

    // ── Persistence ───────────────────────────────────────────────

    private void saveResults(List<PidResult> results) {
        SharedPreferences.Editor ed = getPrefs().edit();
        long now = System.currentTimeMillis();
        for (PidResult r : results) {
            try {
                JSONObject o = new JSONObject();
                o.put("label",      r.label);
                o.put("unit",       r.unit);
                o.put("formula",    r.formula);
                o.put("confidence", r.confidence);
                o.put("reasoning",  r.reasoning);
                o.put("timestamp",  now);
                o.put("model",      MODEL_ID);
                ed.putString("sfe_pid_" + r.pid.toUpperCase(), o.toString());
            } catch (Exception ignored) {}
        }
        ed.apply();
    }

    /** Load a previously stored AI result for a given PID. Returns null if none. */
    public PidResult loadResult(String pid) {
        String json = getPrefs().getString("sfe_pid_" + pid.toUpperCase(), null);
        if (json == null) return null;
        try {
            JSONObject o = new JSONObject(json);
            PidResult r  = new PidResult();
            r.pid         = pid;
            r.label       = o.optString("label", "UNKNOWN");
            r.unit        = o.optString("unit", "");
            r.formula     = o.optString("formula", "raw byte");
            r.confidence  = (float) o.optDouble("confidence", 0.0);
            r.reasoning   = o.optString("reasoning", "");
            r.timestampMs = o.optLong("timestamp", 0L);
            r.modelId     = o.optString("model", MODEL_ID);
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    // ── Result data class ─────────────────────────────────────────

    public static class PidResult {
        public String pid;
        public String label;
        public String unit;
        public String formula;
        public float  confidence;   // 0.0–1.0
        public String reasoning;
        public long   timestampMs;
        public String modelId;
    }

    // ── Helpers ───────────────────────────────────────────────────

    private SharedPreferences getPrefs() {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
