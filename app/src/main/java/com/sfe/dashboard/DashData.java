package com.sfe.dashboard;

/**
 * Shared data store — written by OBDManager thread, read by DashView render thread.
 * All fields are volatile for safe cross-thread access without locking.
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
    public volatile float rpm        = 820f;     // 221027
    public volatile float speedKph   = 0f;       // 221028
    public volatile float throttlePct= 1.2f;     // 221022 — throttle body angle
    public volatile float pedalPct   = 1.2f;     // 221023 — accelerator pedal position
    public volatile float loadPct    = 12f;      // 0104 Mode 01
    public volatile float coolantC   = 57f;      // 221020
    public volatile float timingDeg  = 8.4f;     // 22102A
    public volatile float mafGs      = 1.4f;     // 221026
    public volatile float mapKpa     = 97.8f;    // 221024 — absolute MAP kPa
    public volatile float stftPct    = 0f;       // 0106 Mode 01
    public volatile float ltftPct    = 0f;       // 0107 Mode 01
    public volatile float baroKpa    = 97.8f;    // 0133 Mode 01 — updated once at connect
    public volatile float battV      = 13.8f;

    // ── MODE 22 ECU (AT SH 7E0) ──────────────────────────────────
    public volatile float oilTempC      = 43f;
    public volatile float catTempC      = 400f;   // °C — catalyst temp bank 1 (013C, Mode 01)
    public volatile float iatC          = 20f;    // 22101F — intake air temperature (°C)
    public volatile float knockCorr     = 0f;     // 223018 — feedback knock correction (deg, neg=retard)
    public volatile float fineKnockDeg  = 0f;     // 2210B0 — fine knock learning (deg)
    public volatile float rough1        = 0.2f;
    public volatile float rough2        = 0.1f;
    public volatile float rough3        = 0.3f;
    public volatile float rough4        = 0.1f;
    public volatile float boostPsiDirect = -99f;  // 2210A6 — direct boost pressure (psi); -99 = unavailable
    public volatile float wastegatePct  = 0f;     // 2210A8 — wastegate duty (%)
    public volatile float targetBoostPsi = 0f;    // 2210A7 — target boost pressure (psi)
    public volatile float turboSpeedRpm = 0f;     // 2210A9 — turbo speed estimate (rpm)
    public volatile float chargeAirTempC = 25f;   // 2210AA — charge air / intercooler temp (°C)
    public volatile float ocvIntakeL    = 20f;    // 2210BB — OCV intake left (% duty)
    public volatile float ocvIntakeR    = 20f;    // 22109B
    public volatile float ocvExhL       = 35f;    // 2210EF
    public volatile float ocvExhR       = 35f;    // 2210CF
    public volatile float targetMapKpa  = 97.8f;  // 223050 — target MAP (kPa)
    public volatile float battTempC     = 20f;    // 22309A
    public volatile float altDutyPct    = 70f;    // 221093
    public volatile float fuelPumpPct   = 50f;    // 2210B3

    // ── MODE 22 ECU — additional ScanGauge / spec PIDs ───────────
    public volatile float injPulseMs       = 0f;   // 2210C0 — injector pulse width (ms)
    public volatile float injDutyCyclePct  = 0f;   // 2210C1 — injector duty cycle (%)
    public volatile float afrLambda        = 1.0f; // 2210C3 — air/fuel ratio (lambda)
    public volatile float targetAfrLambda  = 1.0f; // 2210C4 — target AFR (lambda)
    public volatile float hpfpPsi          = 0f;   // 2210C7 — high pressure fuel pump (psi)
    public volatile float radFanPct        = 0f;   // 2210E3 — radiator fan control (%)
    public volatile float vvtAngleR        = 0f;   // 221099 — VVT advance angle right (°)
    public volatile float vvtAngleL        = 0f;   // 2210B9 — VVT advance angle left  (°)
    public volatile float throttleMotorPct = 0f;   // 22105F — throttle motor duty (%)
    public volatile float cpcValvePct      = 0f;   // 2210CB — CPC valve duty (%)
    public volatile float osvLPct          = 0f;   // 2210E5 — OSV duty left  (%)
    public volatile float osvRPct          = 0f;   // 2210C5 — OSV duty right (%)
    public volatile int   fuelTankPressKpa = 0;    // 22108F — fuel tank air pressure (raw word)
    public volatile float damRatio         = 1.0f; // 2210B1 — dynamic advance multiplier (ratio)
    public volatile float fuelLevelPct     = 50f;  // 012F   — fuel level (%)

    // ── MODE 22 TCU (AT SH 7E1) ──────────────────────────────────
    public volatile float cvtTempC      = 27f;    // 221021 — CVT fluid temperature (°C)
    public volatile float lockupPct     = 0f;     // 221045
    public volatile float transferPct   = 5f;     // 221065
    public volatile float turbineRpm    = 820f;   // 221067
    public volatile float primaryRpm    = 820f;   // 22300E — primary pulley speed (rpm)
    public volatile float secondaryRpm  = 820f;   // 2230D0 — secondary pulley speed (rpm)
    public volatile float gearRatioAct  = 2.50f;  // 2230DA — CVT ratio actual
    public volatile float gearRatioTgt  = 2.50f;  // 2230F8 — CVT ratio target
    public volatile float torqueConverterSlipRpm = -1f; // 221153 — torque converter slip (rpm); -1 = not yet received
    // From CarScanner log PIDs
    public volatile int   priPulleyRaw  = 0;   // 2210D2 from TCU — primary pulley (raw)
    public volatile int   cvtModeRaw    = 0;   // 221299 from ECM — CVT mode/range (raw)

    // ── Derived (computed in getter, not polled) ─────────────────
    public float speedMph()     { return speedKph * 0.621371f; }
    public float coolantF()     { return coolantC  * 9f/5f + 32f; }
    public float oilTempF()     { return oilTempC  * 9f/5f + 32f; }
    public float catTempF()  { return catTempC * 9f/5f + 32f; }
    public float cvtTempF()     { return cvtTempC  * 9f/5f + 32f; }
    public float battTempF()    { return battTempC * 9f/5f + 32f; }
    public float mapPsi()       { return mapKpa     / 6.89476f; }
    public float targetMapPsi() { return targetMapKpa / 6.89476f; }
    public float baroPsi()      { return baroKpa   / 6.89476f; }

    /** Boost psi. Prefers 2210A6 direct value when available; falls back to
     *  MAP-baro calculation with calibration offset. */
    public float boostPsi() {
        if (boostPsiDirect > -90f) return boostPsiDirect;
        return (mapKpa - baroKpa) / 6.89476f - 0.85f;
    }

    public float iatF()  { return iatC * 9f/5f + 32f; }
    public float chargeAirTempF() { return chargeAirTempC * 9f/5f + 32f; }

    /** Throttle motor duty centred at 0 (range -100 to +100 %) */
    public float throttleMotorCentred() { return throttleMotorPct; }

    /** CVT torque converter slip — direct from 221153 when available, else calculated.
     *  Sanity-capped: if direct PID gives >50% (unreasonable for TC slip), fall back. */
    public float cvtSlipPct() {
        if (torqueConverterSlipRpm >= 0f && turbineRpm > 100f) {
            float pct = torqueConverterSlipRpm / turbineRpm * 100f;
            if (pct <= 50f) return pct;   // plausible — use direct PID
        }
        if (turbineRpm < 100f) return 0f;
        return Math.abs(turbineRpm - secondaryRpm) / turbineRpm * 100f;
    }

    /** Estimated HP: (MAF * 14.7 * BSFC) very rough proxy */
    public float estHp() {
        return mafGs * 0.82f;  // rough: 1 g/s MAF ≈ 0.8 HP at part throttle
    }

    // ── Knock session tracking ───────────────────────────────────
    public volatile int knockEventCount = 0;
    public void recordKnockEvent() {
        if (knockCorr < -2.5f) knockEventCount++;
    }

    // ── Peak values ──────────────────────────────────────────────
    public volatile float peakBoostPsi   = -99f;
    public volatile float peakRpm        = 0f;
    public volatile float peakTimingDeg  = -99f;
    public volatile float peakLoadPct    = 0f;
    public volatile float peakSpeedMph   = 0f;
    public volatile float worstKnockCorr = 0f;
    public volatile float peakMafGs      = 0f;
    public volatile float peakEstHp      = 0f;
    public volatile float peakCatTempF   = 0f;

    public void updatePeaks() {
        float b = boostPsi();
        if (b > peakBoostPsi)         peakBoostPsi   = b;
        if (rpm > peakRpm)            peakRpm        = rpm;
        if (timingDeg > peakTimingDeg) peakTimingDeg = timingDeg;
        if (loadPct > peakLoadPct)    peakLoadPct    = loadPct;
        float s = speedMph();
        if (s > peakSpeedMph)         peakSpeedMph   = s;
        if (knockCorr < worstKnockCorr) worstKnockCorr = knockCorr;
        if (mafGs > peakMafGs)        peakMafGs      = mafGs;
        float hp = estHp();
        if (hp > peakEstHp)           peakEstHp      = hp;
        float ct = catTempF();
        if (ct > peakCatTempF)        peakCatTempF   = ct;
    }

    public void resetPeaks() {
        peakBoostPsi = -99f; peakRpm = 0f; peakTimingDeg = -99f;
        peakLoadPct = 0f; peakSpeedMph = 0f; worstKnockCorr = 0f;
        peakMafGs = 0f; peakEstHp = 0f; peakCatTempF = 0f;
        knockEventCount = 0;
    }
}
