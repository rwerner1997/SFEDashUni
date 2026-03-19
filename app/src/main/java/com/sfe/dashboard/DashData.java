package com.sfe.dashboard;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

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
    public volatile float baroKpa    = 101.3f;    // 0133 Mode 01 — default std atmosphere; overwritten on first successful poll
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

    // ── CVT shift selector position (TCU 7E1, 221093 + 221095) ──────────────
    // Confirmed encoding from gear-cycle log sfe_20260318_205734.csv (R→P→N→D pass):
    //   R: 221093=0x06, 221095=0x21
    //   P: 221093=0x04, 221095=0x20  (confirmed against 3 parked PID scans)
    //   N: 221093=0x00, 221095=0x20
    //   D: 221093=0x00, 221095=0x00  (confirmed mid-drive PID scans)
    //   S: encoding unknown (not captured in log)
    // Decoded in OBDManager.parseShiftSelector(); null = no valid reading yet.
    public volatile String shiftPos     = null;

    // ── Vehicle identity (written by OBDManager after VIN request) ──
    public volatile String  vin         = null;   // 17-char VIN or "UNKNOWN_<ts>" fallback
    public volatile String  vehicleMake = null;   // WMI prefix e.g. "JF1"
    public volatile boolean isSubaruWRX = false;  // true when VIN matches JF1/JF2 prefix

    // ── Discovery scan state (written by OBDManager) ─────────────
    public volatile boolean discoveryRunning  = false;
    public volatile String  discoveryPhase    = "";      // e.g. "ECM 10A3 (pass 2/3)"
    public volatile float   discoveryProgress = 0f;
    public volatile int     discoveryFound    = 0;       // # of dynamic (non-static) PIDs found

    // ── Generic PID values (written by OBDManager, read by DashView) ──
    // Key = registry key e.g. "mode01_0C", "subaru_ecm_10A8"
    public final ConcurrentHashMap<String, Float> genericValues = new ConcurrentHashMap<>();

    // ── PID Analysis state (written by PidAnalyzer, read by DashView) ─
    public volatile boolean analysisRunning  = false;
    public volatile String  analysisPhase    = "";
    public volatile float   analysisProgress = 0f;
    public volatile String  analysisError    = null;
    @SuppressWarnings("rawtypes")
    public volatile List    lastAnalysisResults = null; // List<PidAnalyzer.PidResult>

    // ── PID Scan state (written by OBDManager poll thread) ───────
    public volatile boolean scanRunning  = false;   // true while scan is active or showing result
    public volatile String  scanPhase    = "";       // e.g. "ECM 10A3" or "DONE — 23 OK"
    public volatile float   scanProgress = 0f;      // 0.0 → 1.0

    // ── Velocity extrapolation state (written by OBDManager poll thread) ────────
    // Render thread uses these to interpolate displayed values between OBD samples.
    public volatile float rpmVelPerMs   = 0f;  // RPM per millisecond
    public volatile long  rpmLastMs     = 0;
    public volatile float speedVelPerMs = 0f;  // kph per millisecond
    public volatile long  speedLastMs   = 0;
    public volatile float mapVelPerMs   = 0f;  // kPa per millisecond
    public volatile long  mapLastMs     = 0;

    /** RPM extrapolated from last OBD sample using measured rate of change.
     *  Age capped at 80 ms (~1.5× a 20 Hz OBD frame) to prevent runaway. */
    public float rpmEst() {
        if (Float.isNaN(rpm)) return Float.NaN;
        long age = Math.min(System.currentTimeMillis() - rpmLastMs, 80L);
        return Math.max(0f, rpm + rpmVelPerMs * age);
    }

    /** Speed (kph) extrapolated from last OBD sample. */
    public float speedKphEst() {
        if (Float.isNaN(speedKph)) return Float.NaN;
        long age = Math.min(System.currentTimeMillis() - speedLastMs, 80L);
        return Math.max(0f, speedKph + speedVelPerMs * age);
    }

    /** Boost (psi) extrapolated from MAP rate of change. */
    public float boostPsiEst() {
        if (Float.isNaN(mapKpa) || Float.isNaN(baroKpa)) return Float.NaN;
        long age = Math.min(System.currentTimeMillis() - mapLastMs, 80L);
        float mapEst = mapKpa + mapVelPerMs * age;
        return (mapEst - baroKpa) / 6.89476f;
    }

    // ── Derived (computed in getter, not polled) ─────────────────
    public float speedMph()     { return speedKph * 0.621371f; }
    public float speedMphEst()  { return speedKphEst() * 0.621371f; }
    public float coolantF()     { return coolantC  * 9f/5f + 32f; }
    public float oilTempF()     { return oilTempC  * 9f/5f + 32f; }
    public float catTempF()     { return catTempC  * 9f/5f + 32f; }
    public float cvtTempF()     { return cvtTempC  * 9f/5f + 32f; }
    public float battTempF()    { return battTempC * 9f/5f + 32f; }
    public float mapPsi()       { return mapKpa     / 6.89476f; }
    public float targetMapPsi() { return targetMapKpa / 6.89476f; }
    public float baroPsi()      { return baroKpa   / 6.89476f; }

    /** Boost psi from MAP − baro. 2210A6 direct sensor is unverified and dropped.
     *  Returns NaN until both MAP and baro are populated. */
    public float boostPsi() {
        if (Float.isNaN(mapKpa) || Float.isNaN(baroKpa)) return Float.NaN;
        return (mapKpa - baroKpa) / 6.89476f;
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
    public volatile float peakCvtTempF   = Float.NaN;
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
        float cvt = cvtTempF();
        if (!Float.isNaN(cvt) && (Float.isNaN(peakCvtTempF) || cvt > peakCvtTempF))              peakCvtTempF   = cvt;
        if (!Float.isNaN(mafGs) && (Float.isNaN(peakMafGs) || mafGs > peakMafGs))                 peakMafGs      = mafGs;
        float hp = estHp();
        if (!Float.isNaN(hp) && (Float.isNaN(peakEstHp) || hp > peakEstHp))                       peakEstHp      = hp;
        float ct = catTempF();
        if (!Float.isNaN(ct) && (Float.isNaN(peakCatTempF) || ct > peakCatTempF))                 peakCatTempF   = ct;
    }

    public void resetPeaks() {
        peakBoostPsi = Float.NaN; peakRpm = Float.NaN; peakTimingDeg = Float.NaN;
        peakLoadPct  = Float.NaN; peakSpeedMph = Float.NaN; peakCvtTempF   = Float.NaN;
        peakMafGs    = Float.NaN; peakEstHp = Float.NaN; peakCatTempF = Float.NaN;
        knockEventCount = 0;
    }

    // ── Trip averages (Welford online mean, reset on demand) ─────
    public volatile float avgRpm        = Float.NaN;
    public volatile float avgSpeedMph   = Float.NaN;
    public volatile float avgBoostPsi   = Float.NaN;
    public volatile float avgLoadPct    = Float.NaN;
    public volatile float avgCoolantF   = Float.NaN;
    public volatile float avgThrottlePct= Float.NaN;
    public volatile float avgStftPct    = Float.NaN;
    public volatile float avgMafGs      = Float.NaN;
    public volatile float avgEstHp      = Float.NaN;
    public volatile int   avgSampleCount= 0;

    /** Update all running trip averages with the current live values.
     *  Uses Welford's online algorithm; skips NaN fields so the average
     *  is not diluted before a PID is first received. */
    public void updateAverages() {
        avgSampleCount++;
        int n = avgSampleCount;
        if (!Float.isNaN(rpm))         avgRpm          = wel(avgRpm,          rpm,          n);
        if (!Float.isNaN(speedKph))    avgSpeedMph     = wel(avgSpeedMph,     speedMph(),   n);
        float b = boostPsi();
        if (!Float.isNaN(b))           avgBoostPsi     = wel(avgBoostPsi,     b,            n);
        if (!Float.isNaN(loadPct))     avgLoadPct      = wel(avgLoadPct,      loadPct,      n);
        if (!Float.isNaN(coolantC))    avgCoolantF     = wel(avgCoolantF,     coolantF(),   n);
        if (!Float.isNaN(pedalPct))    avgThrottlePct  = wel(avgThrottlePct, pedalPct,     n);
        if (!Float.isNaN(stftPct))     avgStftPct      = wel(avgStftPct,      stftPct,      n);
        if (!Float.isNaN(mafGs))       avgMafGs        = wel(avgMafGs,        mafGs,        n);
        float hp = estHp();
        if (!Float.isNaN(hp))          avgEstHp        = wel(avgEstHp,        hp,           n);
    }

    private static float wel(float cur, float newV, int n) {
        return Float.isNaN(cur) ? newV : cur + (newV - cur) / n;
    }

    public void resetAverages() {
        avgRpm = Float.NaN; avgSpeedMph = Float.NaN; avgBoostPsi = Float.NaN;
        avgLoadPct = Float.NaN; avgCoolantF = Float.NaN; avgThrottlePct = Float.NaN;
        avgStftPct = Float.NaN; avgMafGs = Float.NaN; avgEstHp = Float.NaN;
        avgSampleCount = 0;
    }
}
