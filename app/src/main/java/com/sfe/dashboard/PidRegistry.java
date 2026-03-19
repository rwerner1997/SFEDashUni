package com.sfe.dashboard;

import java.util.HashMap;
import java.util.Map;

/**
 * Static known-PID database for OBD-II Mode 01 (SAE J1979) and Mode 22
 * manufacturer-specific PIDs.
 *
 * Mode 01: universal, available on all OBDII vehicles (1996+).
 * Mode 22: keyed by VIN WMI prefix (first 3 chars of VIN).
 *          Currently populated for Subaru (JF1/JF2) with FA20DIT confirmed PIDs.
 *
 * Formula types:
 *   a256b_div4      → (A*256+B)/4               (RPM)
 *   a_minus_40      → A - 40                     (coolant, IAT)
 *   a_pct           → A * 100 / 255              (load %, throttle %)
 *   a_div2          → A / 2                      (wastegate %)
 *   a_div2_minus64  → A/2 - 64                   (timing °)
 *   a256b_div100    → (A*256+B) / 100            (MAF g/s)
 *   byte_minus_50   → A - 50                     (Subaru CVT temp °C)
 *   byte_div_16     → A / 16                     (Subaru DAM ratio)
 *   byte_div_4_minus32 → A/4 - 32               (Subaru knock learning °)
 *   byte_9_5_minus40   → A * 9/5 - 40           (Subaru oil temp °F)
 *   raw_byte        → A                          (roughness, raw byte)
 *   raw_word        → A*256+B                    (shaft speeds raw word)
 *   word_div_32     → (A*256+B) / 32            (Subaru turbine RPM)
 *   a_div2_pct      → A / 2                      (Subaru duty cycle %)
 *   a_div_2_55      → A / 2.55                   (battery voltage proxy)
 *   voltage         → A * 0.1                    (battery V)
 */
public class PidRegistry {

    public static class PidEntry {
        public final String key;          // unique ID: "mode01_0C", "subaru_ecm_10A8"
        public final String label;        // "Engine RPM"
        public final String unit;         // "RPM", "°F", "%"
        public final String category;     // "engine", "temps", "fuel", "transmission", "timing"
        public final float  min, max;     // typical display range
        public final int    decimals;     // decimal places for display
        public final String formulaType;  // see javadoc above
        public final int    mode;         // 0x01 or 0x22
        public final String pidHex;       // "010C", "2210A8"
        public final String ecu;          // null=broadcast, "ECM", "TCU"
        public final String txHeader;     // "7DF", "7E0", "7E1"
        public final String rxHeader;     // "7E8", "7E9"
        public final boolean isTwoByte;   // true if word response expected

        PidEntry(String key, String label, String unit, String category,
                 float min, float max, int decimals, String formulaType,
                 int mode, String pidHex, String ecu,
                 String txHeader, String rxHeader, boolean isTwoByte) {
            this.key         = key;
            this.label       = label;
            this.unit        = unit;
            this.category    = category;
            this.min         = min;
            this.max         = max;
            this.decimals    = decimals;
            this.formulaType = formulaType;
            this.mode        = mode;
            this.pidHex      = pidHex;
            this.ecu         = ecu;
            this.txHeader    = txHeader;
            this.rxHeader    = rxHeader;
            this.isTwoByte   = isTwoByte;
        }
    }

    // ── Mode 01 standard PIDs (universal) ───────────────────────

    /** Map from Mode 01 PID int (e.g. 0x0C) → PidEntry */
    public static final Map<Integer, PidEntry> MODE01 = buildMode01();

    // ── Mode 22 PIDs by WMI prefix ───────────────────────────────

    /** Map from WMI prefix → (PID command string → PidEntry).
     *  PID command string is the full 6-char request, e.g. "2210A8". */
    public static final Map<String, Map<String, PidEntry>> MODE22_BY_WMI = buildMode22();

    // ── Lookup helpers ────────────────────────────────────────────

    /**
     * Look up a Mode 22 PID entry for a given vehicle.
     *
     * @param vin    full VIN (first 3 chars used as WMI key)
     * @param pidCmd full 6-char command e.g. "221093"
     * @return PidEntry or null if not found
     */
    public static PidEntry lookupMode22(String vin, String pidCmd) {
        String wmi = vin.length() >= 3 ? vin.substring(0, 3) : vin;
        Map<String, PidEntry> wmMap = MODE22_BY_WMI.get(wmi);
        if (wmMap == null) return null;
        return wmMap.get(pidCmd.toUpperCase());
    }

    /**
     * Evaluate a PidEntry formula against raw byte/word values.
     *
     * @param entry    the PidEntry with formulaType
     * @param rawByte  first data byte (A), or -1 if not available
     * @param rawWord  two-byte big-endian word (A*256+B), or -1 if not available
     * @return computed engineering-unit value, or Float.NaN on error
     */
    public static float evaluate(PidEntry entry, int rawByte, int rawWord) {
        if (rawByte < 0) return Float.NaN;
        float A = rawByte;
        float W = rawWord >= 0 ? rawWord : A;
        switch (entry.formulaType) {
            case "a256b_div4":          return W / 4f;
            case "a_minus_40":          return A - 40f;
            case "a_pct":               return A * 100f / 255f;
            case "a_div2":              return A / 2f;
            case "a_div2_minus64":      return A / 2f - 64f;
            case "a256b_div100":        return W / 100f;
            case "byte_minus_50":       return A - 50f;
            case "byte_div_16":         return A / 16f;
            case "byte_div_4_minus32":  return A / 4f - 32f;
            case "byte_9_5_minus40":    return A * 9f / 5f - 40f;
            case "raw_byte":            return A;
            case "raw_word":            return W;
            case "word_div_32":         return W / 32f;
            case "a_div2_pct":          return A / 2f;
            case "voltage":             return A * 0.1f;
            default:                    return Float.NaN;
        }
    }

    // ── Private builders ──────────────────────────────────────────

    private static PidEntry m01(int pid, String key, String label, String unit,
                                String cat, float min, float max, int dec, String formula) {
        String pidHex = String.format("01%02X", pid);
        return new PidEntry(key, label, unit, cat, min, max, dec, formula,
                0x01, pidHex, null, "7DF", "7E8", false);
    }

    private static PidEntry m01w(int pid, String key, String label, String unit,
                                 String cat, float min, float max, int dec, String formula) {
        String pidHex = String.format("01%02X", pid);
        return new PidEntry(key, label, unit, cat, min, max, dec, formula,
                0x01, pidHex, null, "7DF", "7E8", true);
    }

    private static PidEntry m22(String wmiKey, String pidSuffix, String label, String unit,
                                String cat, float min, float max, int dec, String formula,
                                String ecu, String tx, String rx, boolean twoB) {
        String pidHex = "22" + pidSuffix;
        return new PidEntry(wmiKey + "_" + ecu.toLowerCase() + "_" + pidSuffix.toLowerCase(),
                label, unit, cat, min, max, dec, formula,
                0x22, pidHex, ecu, tx, rx, twoB);
    }

    private static Map<Integer, PidEntry> buildMode01() {
        Map<Integer, PidEntry> m = new HashMap<>();
        // Engine
        m.put(0x04, m01(0x04, "mode01_04", "Engine Load",       "%",   "engine",      0, 100, 1, "a_pct"));
        m.put(0x05, m01(0x05, "mode01_05", "Coolant Temp",       "°F",  "temps",      -40, 250, 0, "a_minus_40"));
        m.put(0x06, m01(0x06, "mode01_06", "Short Fuel Trim B1", "%",   "fuel",       -100, 100, 1, "a_pct"));
        m.put(0x07, m01(0x07, "mode01_07", "Long Fuel Trim B1",  "%",   "fuel",       -100, 100, 1, "a_pct"));
        m.put(0x0B, m01(0x0B, "mode01_0B", "Intake MAP",         "kPa", "engine",      0, 300, 0, "raw_byte"));
        m.put(0x0C, m01w(0x0C,"mode01_0C", "Engine RPM",         "RPM", "engine",      0, 8000, 0, "a256b_div4"));
        m.put(0x0D, m01(0x0D, "mode01_0D", "Vehicle Speed",      "km/h","engine",      0, 280, 0, "raw_byte"));
        m.put(0x0E, m01(0x0E, "mode01_0E", "Ignition Timing",    "°",   "timing",    -64,  64, 1, "a_div2_minus64"));
        m.put(0x0F, m01(0x0F, "mode01_0F", "Intake Air Temp",    "°F",  "temps",      -40, 250, 0, "a_minus_40"));
        m.put(0x10, m01w(0x10,"mode01_10", "MAF",                "g/s", "engine",      0, 655, 2, "a256b_div100"));
        m.put(0x11, m01(0x11, "mode01_11", "Throttle Position",  "%",   "engine",      0, 100, 1, "a_pct"));
        m.put(0x1F, m01w(0x1F,"mode01_1F", "Engine Runtime",     "s",   "engine",      0, 65535, 0, "raw_word"));
        m.put(0x2C, m01(0x2C, "mode01_2C", "EGR Commanded",      "%",   "fuel",        0, 100, 1, "a_pct"));
        m.put(0x2F, m01(0x2F, "mode01_2F", "Fuel Level",         "%",   "fuel",        0, 100, 1, "a_pct"));
        m.put(0x33, m01(0x33, "mode01_33", "Baro Pressure",      "kPa", "engine",      0, 255, 0, "raw_byte"));
        m.put(0x42, m01w(0x42,"mode01_42", "Battery Voltage",    "V",   "engine",      0, 65.5f, 2, "a256b_div100"));
        m.put(0x45, m01(0x45, "mode01_45", "Accel Pedal Pos",    "%",   "engine",      0, 100, 1, "a_pct"));
        m.put(0x46, m01(0x46, "mode01_46", "Ambient Air Temp",   "°F",  "temps",      -40, 215, 0, "a_minus_40"));
        m.put(0x5A, m01(0x5A, "mode01_5A", "Accel Pedal D",      "%",   "engine",      0, 100, 1, "a_pct"));
        m.put(0x5B, m01(0x5B, "mode01_5B", "Hybrid Battery %",   "%",   "engine",      0, 100, 1, "a_pct"));
        m.put(0x5C, m01(0x5C, "mode01_5C", "Engine Oil Temp",    "°F",  "temps",      -40, 250, 0, "a_minus_40"));
        m.put(0x67, m01w(0x67,"mode01_67", "Engine Coolant",     "°F",  "temps",      -40, 250, 0, "a_minus_40"));
        // Catalyst temps
        m.put(0x3C, m01w(0x3C,"mode01_3C", "Cat Temp B1S1",      "°F",  "temps",      -40, 1200, 0, "a256b_div100"));
        return m;
    }

    private static Map<String, Map<String, PidEntry>> buildMode22() {
        Map<String, Map<String, PidEntry>> byWmi = new HashMap<>();

        // ── Subaru (JF1 = USA WRX, JF2 = USA Outback/Forester) ─────
        Map<String, PidEntry> subaru = new HashMap<>();

        // ECM (7E0/7E8) — FA20DIT confirmed PIDs
        add(subaru, m22("subaru","1027","Engine RPM",             "RPM",  "engine",      0,8000,0,"a256b_div4",    "ECM","7E0","7E8",true));
        add(subaru, m22("subaru","1028","Vehicle Speed",          "km/h", "engine",      0,280, 0,"raw_byte",      "ECM","7E0","7E8",false));
        add(subaru, m22("subaru","1024","Intake MAP",             "kPa",  "engine",      0,300, 0,"raw_byte",      "ECM","7E0","7E8",false));
        add(subaru, m22("subaru","102A","Ignition Timing",        "°",    "timing",    -64,64,  1,"a_div2_minus64","ECM","7E0","7E8",false));
        add(subaru, m22("subaru","1026","MAF",                    "g/s",  "engine",      0,400, 2,"a256b_div100",  "ECM","7E0","7E8",true));
        add(subaru, m22("subaru","1020","Coolant Temp",           "°C",   "temps",      -40,130,0,"a_minus_40",    "ECM","7E0","7E8",false));
        add(subaru, m22("subaru","1023","Throttle Position",      "%",    "engine",      0,100, 1,"a_pct",         "ECM","7E0","7E8",false));
        add(subaru, m22("subaru","1022","Throttle Body Angle",    "%",    "engine",      0,100, 1,"a_pct",         "ECM","7E0","7E8",false));
        add(subaru, m22("subaru","10A8","Wastegate Duty",         "%",    "engine",      0,100, 1,"a_div2_pct",    "ECM","7E0","7E8",false));
        add(subaru, m22("subaru","10B0","Fine Knock Learning",    "°",    "timing",    -32,32,  2,"byte_div_4_minus32","ECM","7E0","7E8",false));
        add(subaru, m22("subaru","10B1","DAM",                   "",     "timing",      0,1,   2,"byte_div_16",   "ECM","7E0","7E8",false));
        add(subaru, m22("subaru","10AF","Engine Oil Temp",        "°F",   "temps",      -40,300,0,"byte_9_5_minus40","ECM","7E0","7E8",false));
        add(subaru, m22("subaru","10B9","VVT Angle Left",         "°",    "engine",     -30,50, 1,"a_div2",        "ECM","7E0","7E8",false));
        add(subaru, m22("subaru","109B","VVT Angle Right",        "°",    "engine",     -30,50, 1,"a_div2",        "ECM","7E0","7E8",false));
        add(subaru, m22("subaru","10C1","Inj Duty Cycle",         "%",    "fuel",        0,100, 1,"a_pct",         "ECM","7E0","7E8",false));
        add(subaru, m22("subaru","10B4","Inj Pulse Width",        "ms",   "fuel",        0,20,  2,"a_div2",        "ECM","7E0","7E8",false));
        // Roughness (ECM, page-gated)
        add(subaru, m22("subaru","3062","Roughness Cyl 1",        "raw",  "engine",      0,255, 0,"raw_byte",      "ECM","7E0","7E8",false));
        add(subaru, m22("subaru","3048","Roughness Cyl 2",        "raw",  "engine",      0,255, 0,"raw_byte",      "ECM","7E0","7E8",false));
        add(subaru, m22("subaru","3068","Roughness Cyl 3",        "raw",  "engine",      0,255, 0,"raw_byte",      "ECM","7E0","7E8",false));
        add(subaru, m22("subaru","304A","Roughness Cyl 4",        "raw",  "engine",      0,255, 0,"raw_byte",      "ECM","7E0","7E8",false));

        // TCU (7E1/7E9) — confirmed CVT temp + shift selector
        add(subaru, m22("subaru","10D2","CVT Fluid Temp",         "°C",   "transmission", 0,130, 0,"byte_minus_50", "TCU","7E1","7E9",false));
        add(subaru, m22("subaru","1093","Shift Selector A",       "raw",  "transmission", 0,255, 0,"raw_byte",      "TCU","7E1","7E9",false));
        add(subaru, m22("subaru","1095","Shift Selector B",       "raw",  "transmission", 0,255, 0,"raw_byte",      "TCU","7E1","7E9",false));
        add(subaru, m22("subaru","1067","Turbine RPM",            "RPM",  "transmission", 0,8000, 0,"word_div_32",  "TCU","7E1","7E9",false));
        add(subaru, m22("subaru","1065","Transfer Duty",          "%",    "transmission", 0,100, 1,"a_div2",        "TCU","7E1","7E9",false));
        add(subaru, m22("subaru","1045","Lockup Duty",            "%",    "transmission", 0,100, 1,"a_div2",        "TCU","7E1","7E9",false));

        byWmi.put("JF1", subaru);
        byWmi.put("JF2", subaru);
        // Additional Subaru WMIs (Canadian, overseas)
        byWmi.put("JF3", subaru);

        return byWmi;
    }

    private static void add(Map<String, PidEntry> map, PidEntry e) {
        map.put(e.pidHex.toUpperCase(), e);
    }
}
