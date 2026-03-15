package com.sfe.dashboard;

/**
 * Shared data store — written by OBDManager thread, read by DashView render thread.
 * All fields are volatile for safe cross-thread access without locking.
 *
 * Unpolled fields are initialised to Float.NaN — DashView shows "---" until real
 * data arrives.  Parse methods leave fields unchanged on error so the last good
 * value is retained; NaN persists only until the first successful poll.
 */
public class DashData {

    private static final DashData INSTANCE = new DashData();
    public static DashData get() { return INSTANCE; }

    // ── Active page (set by DashView on page change) ─────────────
    public volatile int activePage = 0;

    // ── OBD connection state ─────────────────────────────────────
    public volatile boolean connected   = false;
    public volatile String  btStatus    = "SEARCHING";
    public volatile String  obdProtocol = "---";
    public volatile long    lastPollMs  = 0;
    public volatile int     pollHz      = 0;       // rolling average polls/sec

    // ── MODE 22 BURST Tier 1 (AT SH 7E0) + MODE 01 slow PIDs ────
    public volatile float rpm        = Float.NaN; // 221027
    public volatile float speedKph   = Float.NaN; // 221028
    public volatile float throttlePct= Float.NaN; // 221022 — throttle body angle
    public volatile float pedalPct   = Float.NaN; // 221023 — accelerator pedal position
    public volatile float loadPct    = Float.NaN; // 0104 Mode 01
    public volatile float coolantC   = Float.NaN; // 221020
    public volatile float timingDeg  = Float.NaN; // 22102A
    public volatile float mafGs      = Float.NaN; // 221026
    public volatile float mapKpa     = Float.NaN; // 221024 — absolute MAP kPa
    public volatile float stftPct    = Float.NaN; // 0106 Mode 01
    public volatile float ltftPct    = Float.NaN; // 0107 Mode 01
    public volatile float baroKpa    = Float.NaN; // 0133 Mode 01 — updated once at connect
    public volatile float battV      = Float.NaN;

    // ── MODE 22 ECU (AT SH 7E0) ──────────────────────────────────
    public volatile float oilTempC      = Float.NaN;
    public volatile float catTempC      = Float.NaN; // °C — catalyst temp bank 1 (013C, Mode 01)
    public volatile float iatC          = Float.NaN; // 22101F — intake air temperature (°C)
    public volatile float knockCorr     = Float.NaN; // 223018 — feedback knock correction (deg, neg=retard)
    public volatile float fineKnockDeg  = Float.NaN; // 2210B0 — fine knock learning (deg)
    public volatile float rough1        = Float.NaN;
    public volatile float rough2        = Float.NaN;
    public volatile float rough3        = Float.NaN;
    public volatile float rough4        = Float.NaN;
    public volatile float boostPsiDirect = Float.NaN; // 2210A6 — direct boost pressure (psi)
    public volatile float wastegatePct  = Float.NaN; // 2210A8 — wastegate duty (%)
    public volatile float ocvIntakeL    = Float.NaN; // 2210BB — OCV intake left (% duty)
    public volatile float ocvIntakeR    = Float.NaN; // 22109B
    public volatile float ocvExhL       = Float.NaN; // 2210EF
    public volatile float ocvExhR       = Float.NaN; // 2210CF
    public volatile float targetMapKpa  = Float.NaN; // 223050 — target MAP (kPa)
    public volatile float battTempC     = Float.NaN; // 22309A
    public volatile float altDutyPct    = Float.NaN; // 221093
    public volatile float fuelPumpPct   = Float.NaN; // 2210B3

    // ── MODE 22 ECU — additional ScanGauge / spec PIDs ───────────
    public volatile float injPulseMs       = Float.NaN; // 2210C0 — injector pulse width (ms)
    public volatile float injDutyCyclePct  = Float.NaN; // 2210C1 — injector duty cycle (%)
    public volatile float afrLambda        = Float.NaN; // 2210C3 — air/fuel ratio (lambda)
    public volatile float targetAfrLambda  = Float.NaN; // 2210C4 — target AFR (lambda)
    public volatile float hpfpPsi          = Float.NaN; // 2210C7 — high pressure fuel pump (psi)
    public volatile float radFanPct        = Float.NaN; // 2210E3 — radiator fan control (%)
    public volatile float vvtAngleR        = Float.NaN; // 221099 — VVT advance angle right (°)
    public volatile float vvtAngleL        = Float.NaN; // 2210B9 — VVT advance angle left  (°)
    public volatile float throttleMotorPct = Float.NaN; // 22105F — throttle motor duty (%)
    public volatile float cpcValvePct      = Float.NaN; // 2210CB — CPC valve duty (%)
    public volatile float osvLPct          = Float.NaN; // 2210E5 — OSV duty left  (%)
    public volatile float osvRPct          = Float.NaN; // 2210C5 — OSV duty right (%)
    public volatile int   fuelTankPressKpa = 0;         // 22108F — fuel tank air pressure (raw word)
    public volatile float damRatio         = Float.NaN; // 2210B1 — dynamic advance multiplier (ratio)
    public volatile float fuelLevelPct     = Float.NaN; // 012F   — fuel level (%)

    // ── CVT fluid temp (on ECM 7E0, not TCU) ─────────────────────
    public volatile float cvtTempC      = Float.NaN; // 221021 — CVT fluid temperature (°C)

    // ── PID Scan state (written by OBDManager poll thread) ───────
    public volatile boolean scanRunning  = false;   // true while scan is active or showing result
    public volatile String  scanPhase    = "";       // e.g. "ECM 10A3" or "DONE — 23 OK"
    public volatile float   scanProgress = 0f;      // 0.0 → 1.0

    // ── Derived (computed in getter, not polled) ─────────────────
    public float speedMph()     { return speedKph * 0.621371f; }
    public float coolantF()     { return coolantC  * 9f/5f + 32f; }
    public float oilTempF()     { return oilTempC  * 9f/5f + 32f; }
    public float catTempF()     { return catTempC  * 9f/5f + 32f; }
    public float cvtTempF()     { return cvtTempC  * 9f/5f + 32f; }
    public float battTempF()    { return battTempC * 9f/5f + 32f; }
    public float mapPsi()       { return mapKpa     / 6.89476f; }
    public float targetMapPsi() { return targetMapKpa / 6.89476f; }
    public float baroPsi()      { return baroKpa   / 6.89476f; }

    /** Boost psi. Prefers 2210A6 direct value when available; falls back to
     *  MAP-baro calculation with calibration offset. Returns NaN if no data. */
    public float boostPsi() {
        if (!Float.isNaN(boostPsiDirect)) return boostPsiDirect;
        if (Float.isNaN(mapKpa) || Float.isNaN(baroKpa)) return Float.NaN;
        return (mapKpa - baroKpa) / 6.89476f - 0.85f;
    }

    public float iatF()  { return iatC * 9f/5f + 32f; }

    /** Throttle motor duty centred at 0 (range -100 to +100 %) */
    public float throttleMotorCentred() { return throttleMotorPct; }

    /** Estimated HP: (MAF * 14.7 * BSFC) very rough proxy */
    public float estHp() {
        return mafGs * 0.82f;  // rough: 1 g/s MAF ≈ 0.8 HP at part throttle
    }

    // ── Knock session tracking ───────────────────────────────────
    public volatile int knockEventCount = 0;
    public void recordKnockEvent() {
        if (!Float.isNaN(knockCorr) && knockCorr < -2.5f) knockEventCount++;
    }

    // ── Peak values ──────────────────────────────────────────────
    public volatile float peakBoostPsi   = Float.NaN;
    public volatile float peakRpm        = Float.NaN;
    public volatile float peakTimingDeg  = Float.NaN;
    public volatile float peakLoadPct    = Float.NaN;
    public volatile float peakSpeedMph   = Float.NaN;
    public volatile float worstKnockCorr = Float.NaN;
    public volatile float peakMafGs      = Float.NaN;
    public volatile float peakEstHp      = Float.NaN;
    public volatile float peakCatTempF   = Float.NaN;

    public void updatePeaks() {
        float b = boostPsi();
        if (!Float.isNaN(b) && (Float.isNaN(peakBoostPsi) || b > peakBoostPsi))                   peakBoostPsi   = b;
        if (!Float.isNaN(rpm) && (Float.isNaN(peakRpm) || rpm > peakRpm))                         peakRpm        = rpm;
        if (!Float.isNaN(timingDeg) && (Float.isNaN(peakTimingDeg) || timingDeg > peakTimingDeg)) peakTimingDeg  = timingDeg;
        if (!Float.isNaN(loadPct) && (Float.isNaN(peakLoadPct) || loadPct > peakLoadPct))         peakLoadPct    = loadPct;
        float s = speedMph();
        if (!Float.isNaN(s) && (Float.isNaN(peakSpeedMph) || s > peakSpeedMph))                   peakSpeedMph   = s;
        if (!Float.isNaN(knockCorr) && (Float.isNaN(worstKnockCorr) || knockCorr < worstKnockCorr)) worstKnockCorr = knockCorr;
        if (!Float.isNaN(mafGs) && (Float.isNaN(peakMafGs) || mafGs > peakMafGs))                 peakMafGs      = mafGs;
        float hp = estHp();
        if (!Float.isNaN(hp) && (Float.isNaN(peakEstHp) || hp > peakEstHp))                       peakEstHp      = hp;
        float ct = catTempF();
        if (!Float.isNaN(ct) && (Float.isNaN(peakCatTempF) || ct > peakCatTempF))                 peakCatTempF   = ct;
    }

    public void resetPeaks() {
        peakBoostPsi = Float.NaN; peakRpm = Float.NaN; peakTimingDeg = Float.NaN;
        peakLoadPct  = Float.NaN; peakSpeedMph = Float.NaN; worstKnockCorr = Float.NaN;
        peakMafGs    = Float.NaN; peakEstHp = Float.NaN; peakCatTempF = Float.NaN;
        knockEventCount = 0;
    }
}
