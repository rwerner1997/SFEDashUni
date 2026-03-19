package com.sfe.dashboard;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-vehicle profile stored as JSON in app private storage.
 * Path: getFilesDir()/vehicles/{VIN}.json
 *
 * Holds Mode 01 supported PIDs (from bitmask query) and Mode 22 discovered PIDs
 * (from the interleaved discovery scan).  All Mode 22 PIDs that match a PidRegistry
 * entry are promoted to the active poll list; unknown PIDs are tracked for AI analysis.
 */
public class VehicleProfile {

    private static final String TAG = "VehicleProfile";

    // ── Identity ─────────────────────────────────────────────────
    public String vin;           // 17-char VIN, or "UNKNOWN_<ts>" fallback
    public String make;          // WMI prefix e.g. "JF1", "1G1"
    public long   lastConnectedMs;
    public boolean scanCompleted; // true if discovery scan has been fully run

    // ── Supported PIDs ───────────────────────────────────────────
    public final List<SupportedPid>  mode01Pids  = new ArrayList<>();
    public final List<DiscoveredPid> mode22Pids  = new ArrayList<>();

    /** A standard Mode 01 PID confirmed supported by this vehicle's ECU. */
    public static class SupportedPid {
        public int    pid;           // e.g. 0x0C for RPM
        public String registryKey;  // PidRegistry key, e.g. "mode01_0C"
        public int    pollTier;     // 1=fast ~20Hz, 2=medium ~7Hz, 3=slow ~1Hz
    }

    /**
     * A Mode 22 PID found via discovery scan.
     * registryKey is null if not matched in PidRegistry — these are candidates
     * for AI analysis via PidAnalyzer.
     */
    public static class DiscoveredPid {
        public String  ecu;          // "ECM" or "TCU"
        public String  pid;          // 6-char e.g. "221138"
        public String  registryKey;  // null = unknown
        public float   confidence;   // 0.0–1.0 (fraction of dynamic samples)
        public int     sampleCount;  // how many times polled across scan passes
        public boolean isStatic;     // true if value never changed → not a live sensor
        public int     lastRawValue;
        // AI analysis results (written by PidAnalyzer, read from SharedPreferences on load)
        public String  aiLabel;
        public String  aiUnit;
        public String  aiFormula;
        public float   aiConfidence;
        public String  aiReasoning;
    }

    // ── Constructors ─────────────────────────────────────────────

    public VehicleProfile(String vin) {
        this.vin  = vin;
        this.make = vin.length() >= 3 ? vin.substring(0, 3) : vin;
        this.lastConnectedMs = System.currentTimeMillis();
    }

    // ── Queries ──────────────────────────────────────────────────

    /** True if VIN begins with a Subaru WMI prefix. */
    public boolean isSubaru() {
        return vin.startsWith("JF1") || vin.startsWith("JF2");
    }

    /** Return all Mode 22 PIDs that have a registry match (confirmed, usable for polling). */
    public List<DiscoveredPid> confirmedMode22Pids() {
        List<DiscoveredPid> out = new ArrayList<>();
        for (DiscoveredPid p : mode22Pids) {
            if (p.registryKey != null && !p.isStatic && p.confidence >= 0.3f) out.add(p);
        }
        return out;
    }

    /** Return all unknown Mode 22 PIDs (no registry match, not static). */
    public List<String> unknownPidIds(String ecu) {
        List<String> out = new ArrayList<>();
        for (DiscoveredPid p : mode22Pids) {
            if (ecu.equals(p.ecu) && p.registryKey == null && !p.isStatic) out.add(p.pid);
        }
        return out;
    }

    /** Find or create a DiscoveredPid entry by pid+ecu. */
    public DiscoveredPid getOrCreate(String ecu, String pid) {
        for (DiscoveredPid p : mode22Pids) {
            if (p.ecu.equals(ecu) && p.pid.equals(pid)) return p;
        }
        DiscoveredPid p = new DiscoveredPid();
        p.ecu  = ecu;
        p.pid  = pid;
        p.confidence   = 0f;
        p.sampleCount  = 0;
        p.isStatic     = true;   // assume static until a change is observed
        p.lastRawValue = -1;
        mode22Pids.add(p);
        return p;
    }

    // ── Persistence ──────────────────────────────────────────────

    private static File vehicleDir(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "vehicles");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    private static File vehicleFile(Context ctx, String vin) {
        return new File(vehicleDir(ctx), sanitize(vin) + ".json");
    }

    private static String sanitize(String vin) {
        return vin.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    public static boolean exists(Context ctx, String vin) {
        return vehicleFile(ctx, vin).exists();
    }

    /** Load an existing profile, or create a new empty one. */
    public static VehicleProfile loadOrCreate(Context ctx, String vin) {
        if (exists(ctx, vin)) {
            VehicleProfile p = load(ctx, vin);
            if (p != null) {
                p.lastConnectedMs = System.currentTimeMillis();
                p.save(ctx);
                return p;
            }
        }
        VehicleProfile p = new VehicleProfile(vin);
        p.save(ctx);
        return p;
    }

    /** Load from JSON file. Returns null on parse failure. */
    public static VehicleProfile load(Context ctx, String vin) {
        File f = vehicleFile(ctx, vin);
        if (!f.exists()) return null;
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            JSONObject o = new JSONObject(sb.toString());
            VehicleProfile p = new VehicleProfile(o.getString("vin"));
            p.lastConnectedMs = o.optLong("lastConnectedMs", 0L);
            p.scanCompleted   = o.optBoolean("scanCompleted", false);

            JSONArray m01 = o.optJSONArray("mode01Pids");
            if (m01 != null) {
                for (int i = 0; i < m01.length(); i++) {
                    JSONObject jo = m01.getJSONObject(i);
                    SupportedPid sp = new SupportedPid();
                    sp.pid         = jo.getInt("pid");
                    sp.registryKey = jo.optString("registryKey", null);
                    sp.pollTier    = jo.optInt("pollTier", 3);
                    p.mode01Pids.add(sp);
                }
            }

            JSONArray m22 = o.optJSONArray("mode22Pids");
            if (m22 != null) {
                for (int i = 0; i < m22.length(); i++) {
                    JSONObject jo = m22.getJSONObject(i);
                    DiscoveredPid dp = new DiscoveredPid();
                    dp.ecu          = jo.getString("ecu");
                    dp.pid          = jo.getString("pid");
                    dp.registryKey  = jo.optString("registryKey", null);
                    if ("null".equals(dp.registryKey)) dp.registryKey = null;
                    dp.confidence   = (float) jo.optDouble("confidence", 0.0);
                    dp.sampleCount  = jo.optInt("sampleCount", 0);
                    dp.isStatic     = jo.optBoolean("isStatic", false);
                    dp.lastRawValue = jo.optInt("lastRawValue", -1);
                    dp.aiLabel      = jo.optString("aiLabel", null);
                    if ("null".equals(dp.aiLabel)) dp.aiLabel = null;
                    dp.aiUnit       = jo.optString("aiUnit", null);
                    if ("null".equals(dp.aiUnit)) dp.aiUnit = null;
                    dp.aiFormula    = jo.optString("aiFormula", null);
                    if ("null".equals(dp.aiFormula)) dp.aiFormula = null;
                    dp.aiConfidence = (float) jo.optDouble("aiConfidence", 0.0);
                    dp.aiReasoning  = jo.optString("aiReasoning", null);
                    if ("null".equals(dp.aiReasoning)) dp.aiReasoning = null;
                    p.mode22Pids.add(dp);
                }
            }

            Log.i(TAG, "Loaded profile for " + vin + " (" + p.mode22Pids.size() + " Mode22 PIDs)");
            return p;
        } catch (Exception e) {
            Log.e(TAG, "Failed to load profile for " + vin + ": " + e.getMessage());
            return null;
        }
    }

    /** Save profile to JSON. Silently swallows I/O errors. */
    public void save(Context ctx) {
        try {
            JSONObject o = new JSONObject();
            o.put("vin",             vin);
            o.put("lastConnectedMs", lastConnectedMs);
            o.put("scanCompleted",   scanCompleted);

            JSONArray m01 = new JSONArray();
            for (SupportedPid sp : mode01Pids) {
                JSONObject jo = new JSONObject();
                jo.put("pid",         sp.pid);
                jo.put("registryKey", sp.registryKey);
                jo.put("pollTier",    sp.pollTier);
                m01.put(jo);
            }
            o.put("mode01Pids", m01);

            JSONArray m22 = new JSONArray();
            for (DiscoveredPid dp : mode22Pids) {
                JSONObject jo = new JSONObject();
                jo.put("ecu",          dp.ecu);
                jo.put("pid",          dp.pid);
                jo.put("registryKey",  dp.registryKey);
                jo.put("confidence",   dp.confidence);
                jo.put("sampleCount",  dp.sampleCount);
                jo.put("isStatic",     dp.isStatic);
                jo.put("lastRawValue", dp.lastRawValue);
                if (dp.aiLabel != null)     jo.put("aiLabel",      dp.aiLabel);
                if (dp.aiUnit != null)      jo.put("aiUnit",       dp.aiUnit);
                if (dp.aiFormula != null)   jo.put("aiFormula",    dp.aiFormula);
                if (dp.aiConfidence > 0f)   jo.put("aiConfidence", dp.aiConfidence);
                if (dp.aiReasoning != null) jo.put("aiReasoning",  dp.aiReasoning);
                m22.put(jo);
            }
            o.put("mode22Pids", m22);

            File f = vehicleFile(ctx, vin);
            try (FileWriter fw = new FileWriter(f)) {
                fw.write(o.toString());
            }
            Log.d(TAG, "Saved profile for " + vin);
        } catch (Exception e) {
            Log.e(TAG, "Failed to save profile for " + vin + ": " + e.getMessage());
        }
    }

    /** Merge AI analysis results into this profile's DiscoveredPid entries and save. */
    public void applyAiResults(Context ctx, List<PidAnalyzer.PidResult> results) {
        for (PidAnalyzer.PidResult r : results) {
            // Find by pid string (matches both ECM and TCU — pid includes "22" prefix)
            for (DiscoveredPid dp : mode22Pids) {
                if (dp.pid.equalsIgnoreCase(r.pid)) {
                    dp.aiLabel      = r.label;
                    dp.aiUnit       = r.unit;
                    dp.aiFormula    = r.formula;
                    dp.aiConfidence = r.confidence;
                    dp.aiReasoning  = r.reasoning;
                    break;
                }
            }
        }
        save(ctx);
    }
}
