package com.sfe.dashboard;

/**
 * Shared data store — written by OBDManager thread, read by DashView render thread.
 * All fields are volatile for safe cross-thread access without locking.
 */
public class DashData {

    private static final DashData INSTANCE = new DashData();
    public static DashData get() { return INSTANCE; }

    // ── OBD connection state ─────────────────────────────────────
    public volatile boolean connected   = false;
    public volatile String  btStatus    = "SEARCHING";
    public volatile String  obdProtocol = "---";
    public volatile long    lastPollMs  = 0;
    public volatile int     pollHz      = 0;       // rolling average polls/sec

    // ── MODE 01 — standard PIDs ──────────────────────────────────
    public volatile float rpm        = 820f;
    public volatile float speedKph   = 0f;
    public volatile float throttlePct= 1.2f;
    public volatile float loadPct    = 12f;
    public volatile float coolantC   = 57f;
    public volatile float timingDeg  = 8.4f;
    public volatile float mafGs      = 1.4f;
    public volatile float mapKpa     = 97.8f;   // absolute MAP
    public volatile float stftPct    = 0f;
    public volatile float ltftPct    = 0f;
    public volatile float baroKpa    = 97.8f;   // updated once at connect
    public volatile float battV      = 13.8f;

    // ── MODE 22 ECU (AT SH 7E0) ──────────────────────────────────
    public volatile float oilTempC      = 43f;
    public volatile float catTempC       = 400f;  // °C — catalyst temp bank 1
    public volatile float knockCorr     = 0f;    // degrees, negative = retard
    public volatile float rough1        = 0.2f;
    public volatile float rough2        = 0.1f;
    public volatile float rough3        = 0.3f;
    public volatile float rough4        = 0.1f;
    public volatile float wastegatePct  = 0f;    // %
    public volatile float ocvIntakeL    = 20f;   // % duty
    public volatile float ocvIntakeR    = 20f;
    public volatile float ocvExhL       = 35f;
    public volatile float ocvExhR       = 35f;
    public volatile float targetMapKpa  = 97.8f;
    public volatile float battTempC     = 20f;
    public volatile float altDutyPct    = 70f;
    public volatile float fuelPumpPct   = 50f;

    // ── MODE 22 ECU — additional ScanGauge PIDs ───────────────────
    public volatile float injPulseMs       = 0f;   // 2210A3 — fuel injection #1 pulse width
    public volatile float radFanPct        = 0f;   // 2210E3 — radiator fan control (%)
    public volatile float vvtAngleR        = 0f;   // 221099 — VVT advance angle right (°)
    public volatile float vvtAngleL        = 0f;   // 2210B9 — VVT advance angle left  (°)
    public volatile float throttleMotorPct = 0f;   // 22105F — throttle motor duty (%)
    public volatile float cpcValvePct      = 0f;   // 2210CB — CPC valve duty (%)
    public volatile float osvLPct          = 0f;   // 2210E5 — OSV duty left  (%)
    public volatile float osvRPct          = 0f;   // 2210C5 — OSV duty right (%)
    public volatile int   fuelTankPressKpa = 0;    // 22108F — fuel tank air pressure (raw word)
    public volatile int   egrSteps         = 0;    // 2210B1 — number of EGR steps
    public volatile float fuelLevelPct     = 50f;  // 012F   — fuel level (%)

    // ── MODE 22 TCU (AT SH 7E1) ──────────────────────────────────
    public volatile float cvtTempC      = 27f;
    public volatile float lockupPct     = 0f;
    public volatile float transferPct   = 5f;
    public volatile float turbineRpm    = 820f;
    public volatile float primaryRpm    = 820f;
    public volatile float secondaryRpm  = 820f;
    public volatile float gearRatioAct  = 2.50f;
    public volatile float gearRatioTgt  = 2.50f;
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

    /** Boost = MAP - baro, in PSI. Negative = vacuum.
     *  -0.85 PSI calibration offset corrects systematic +0.8-1 PSI over-read. */
    public float boostPsi() {
        return (mapKpa - baroKpa) / 6.89476f - 0.85f;
    }

    /** Throttle motor duty centred at 0 (range -100 to +100 %) */
    public float throttleMotorCentred() { return throttleMotorPct; }

    /** CVT belt slip % — turbine vs secondary pulley */
    public float cvtSlipPct() {
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
