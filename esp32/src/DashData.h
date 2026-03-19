#pragma once
#include <Arduino.h>  // millis()
#include <math.h>     // NAN, isnan()

// ─────────────────────────────────────────────────────────────────────────────
// DashData — shared state written by OBDManager task, read by DashView task.
// All numeric fields are volatile float; NAN means "not yet received".
// Mirrors DashData.java; see project CLAUDE.md for PID reference.
// ─────────────────────────────────────────────────────────────────────────────

struct DashData {

    // ── Active page ─────────────────────────────────────────────────────────
    volatile int activePage = 0;   // 0-7 (see DashView.h)

    // ── Connection state ─────────────────────────────────────────────────────
    volatile bool connected    = false;
    volatile char btStatus[24] = "SEARCHING";
    volatile char obdProtocol[8] = "---";
    volatile unsigned long lastPollMs = 0;
    volatile int  pollHz = 0;

    // ── Tier 1 burst: Mode 01 + Mode 22 fast ────────────────────────────────
    volatile float rpm         = NAN;  // 010C  (Mode 01)
    volatile float speedKph    = NAN;  // 010D
    volatile float throttlePct = NAN;  // 221022 — throttle body angle
    volatile float pedalPct    = NAN;  // 0145
    volatile float loadPct     = NAN;  // 0104
    volatile float coolantC    = NAN;  // 0105
    volatile float timingDeg   = NAN;  // 010E
    volatile float mafGs       = NAN;  // 0110
    volatile float mapKpa      = NAN;  // 010B
    volatile float stftPct     = NAN;  // 0106
    volatile float ltftPct     = NAN;  // 0107
    volatile float baroKpa     = 101.3f; // 0133 — default std atmosphere; overwritten by PID 0133
    volatile float battV       = NAN;  // ATRV

    // ── Velocity extrapolation (written by OBDManager, read by DashView) ─────
    // Allows DashView to interpolate displayed values between OBD poll samples.
    volatile float rpmVelPerMs    = 0;
    volatile unsigned long rpmLastMs   = 0;
    volatile float speedVelPerMs  = 0;
    volatile unsigned long speedLastMs = 0;
    volatile float mapVelPerMs    = 0;
    volatile unsigned long mapLastMs   = 0;

    // ── Mode 22 ECU (7E0/7E8) ────────────────────────────────────────────────
    volatile float oilTempC       = NAN;  // 015C  (Mode 01)
    volatile float catTempC       = NAN;  // 013C
    volatile float fineKnockDeg   = NAN;  // 2210B0
    volatile float rough1         = NAN;  // 223062
    volatile float rough2         = NAN;  // 223048
    volatile float rough3         = NAN;  // 223068
    volatile float rough4         = NAN;  // 22304A
    volatile float boostPsiDirect = NAN;  // 2210A6 — reserved; unverified formula; not polled
    volatile float wastegatePct   = NAN;  // 2210A8
    volatile float vvtAngleL      = NAN;  // 2210B9 — VVT intake-left cam angle (°)
    volatile float damRatio       = NAN;  // 2210B1 — dynamic advance multiplier (0–1.0+)
    volatile float cvtTempC       = NAN;  // 2210D2 @ TCU 7E1 — confirmed dynamic (byte - 50 °C)
    volatile float fuelLevelPct   = NAN;  // 012F
    volatile char  shiftPos[4]    = "";   // PRNDL: "P"/"R"/"N"/"D" (221093+221095 @ TCU 7E1)

    // ── Mode 22 injection (7E0/7E8) ──────────────────────────────────────────
    volatile float injDutyPct     = NAN;  // 2210C1 — injection duty cycle %
    volatile float injPulseMs     = NAN;  // 2210B4 — injection pulse width ms

    // ── Derived getters ──────────────────────────────────────────────────────
    float speedMph()     const { return speedKph * 0.621371f; }
    float coolantF()     const { return coolantC * 9.0f/5.0f + 32.0f; }
    float oilTempF()     const { return oilTempC * 9.0f/5.0f + 32.0f; }
    float catTempF()     const { return catTempC * 9.0f/5.0f + 32.0f; }
    float cvtTempF()     const { return cvtTempC * 9.0f/5.0f + 32.0f; }
    float mapPsi()       const { return mapKpa   / 6.89476f; }
    float baroPsi()      const { return baroKpa  / 6.89476f; }

    /** Boost psi: MAP minus barometric pressure. */
    float boostPsi() const {
        if (isnan(mapKpa) || isnan(baroKpa)) return NAN;
        return (mapKpa - baroKpa) / 6.89476f;
    }

    float estHp() const { return mafGs * 0.82f; }

    // ── Velocity-extrapolated getters ────────────────────────────────────────
    // RPM: extrapolate up to 300 ms so tier-2/3 poll gaps don't freeze the needle.
    // Velocity decays exponentially (τ=150 ms) so the needle slows to a stop
    // naturally when no new data arrives rather than shooting off linearly.
    // Displacement = vel * τ * (1 − e^(−t/τ)) — integral of decaying velocity.
    float rpmEst() const {
        if (isnan(rpm) || rpmLastMs == 0) return rpm;
        float age = (float)(millis() - rpmLastMs);
        if (age > 300.0f) return rpm;
        constexpr float TAU = 150.0f;
        float proj = rpmVelPerMs * TAU * (1.0f - expf(-age / TAU));
        return fmaxf(0.0f, rpm + proj);
    }
    // Speed: plain linear extrapolation — snap-to-zero in parseSpeed already
    // handles the stop case; 150 ms cap covers normal poll gaps.
    float speedKphEst() const {
        if (isnan(speedKph) || speedLastMs == 0) return speedKph;
        float age = (float)(millis() - speedLastMs);
        if (age > 150.0f) return speedKph;
        return fmaxf(0.0f, speedKph + speedVelPerMs * age);
    }
    float speedMphEst() const { return speedKphEst() * 0.621371f; }
    float boostPsiEst() const {
        if (isnan(mapKpa) || isnan(baroKpa)) return NAN;
        unsigned long age = millis() - mapLastMs;
        float mapEst = (age > 80 || mapLastMs == 0) ? mapKpa : mapKpa + mapVelPerMs * (float)age;
        return (mapEst - baroKpa) / 6.89476f;
    }

    // ── Running trip averages (Welford's online algorithm) ───────────────────
    volatile float avgRpm         = NAN;
    volatile float avgSpeedMph    = NAN;
    volatile float avgBoostPsi    = NAN;
    volatile float avgLoadPct     = NAN;
    volatile float avgCoolantF    = NAN;
    volatile float avgThrottlePct = NAN;
    volatile float avgStftPct     = NAN;
    volatile float avgMafGs       = NAN;
    volatile float avgEstHp       = NAN;
    volatile int   avgSampleCount = 0;

    void updateAverages() {
        avgSampleCount++;
        int n = avgSampleCount;
        if (!isnan(rpm))      avgRpm          = _wel(avgRpm,         rpm,         n);
        if (!isnan(speedKph)) avgSpeedMph     = _wel(avgSpeedMph,    speedMph(),  n);
        float b = boostPsi();
        if (!isnan(b))        avgBoostPsi     = _wel(avgBoostPsi,    b,           n);
        if (!isnan(loadPct))  avgLoadPct      = _wel(avgLoadPct,     loadPct,     n);
        if (!isnan(coolantC)) avgCoolantF     = _wel(avgCoolantF,    coolantF(),  n);
        if (!isnan(pedalPct)) avgThrottlePct  = _wel(avgThrottlePct, pedalPct,    n);
        if (!isnan(stftPct))  avgStftPct      = _wel(avgStftPct,     stftPct,     n);
        if (!isnan(mafGs))    avgMafGs        = _wel(avgMafGs,       mafGs,       n);
        float hp = estHp();
        if (!isnan(hp))       avgEstHp        = _wel(avgEstHp,       hp,          n);
    }

    void resetAverages() {
        avgRpm = NAN; avgSpeedMph = NAN; avgBoostPsi = NAN;
        avgLoadPct = NAN; avgCoolantF = NAN; avgThrottlePct = NAN;
        avgStftPct = NAN; avgMafGs = NAN; avgEstHp = NAN;
        avgSampleCount = 0;
    }

    // ── Knock session tracking ────────────────────────────────────────────────
    volatile int knockEventCount = 0;
    void recordKnockEvent() {
        // knockCorr (223018) not supported on this ECU — always NaN; counter stays 0.
    }

    // ── Peak values ──────────────────────────────────────────────────────────
    volatile float peakBoostPsi  = NAN;
    volatile float peakRpm       = NAN;
    volatile float peakTimingDeg = NAN;
    volatile float peakLoadPct   = NAN;
    volatile float peakSpeedMph  = NAN;
    volatile float peakMafGs     = NAN;
    volatile float peakEstHp     = NAN;
    volatile float peakCatTempF  = NAN;
    volatile float peakCvtTempF  = NAN;

    void updatePeaks() {
        float b = boostPsi();
        if (!isnan(b)            && (isnan(peakBoostPsi)   || b          > peakBoostPsi))   peakBoostPsi   = b;
        if (!isnan(rpm)       && (isnan(peakRpm)       || rpm       > peakRpm))       peakRpm       = rpm;
        if (!isnan(timingDeg) && (isnan(peakTimingDeg) || timingDeg > peakTimingDeg)) peakTimingDeg = timingDeg;
        if (!isnan(loadPct)   && (isnan(peakLoadPct)   || loadPct   > peakLoadPct))   peakLoadPct   = loadPct;
        float s = speedMph();
        if (!isnan(s)         && (isnan(peakSpeedMph)  || s         > peakSpeedMph))  peakSpeedMph  = s;
        if (!isnan(mafGs)     && (isnan(peakMafGs)     || mafGs     > peakMafGs))     peakMafGs     = mafGs;
        float hp = estHp();
        if (!isnan(hp)           && (isnan(peakEstHp)       || hp         > peakEstHp))       peakEstHp      = hp;
        float ct = catTempF();
        if (!isnan(ct)           && (isnan(peakCatTempF)    || ct         > peakCatTempF))    peakCatTempF   = ct;
        float cv = cvtTempF();
        if (!isnan(cv)           && (isnan(peakCvtTempF)    || cv         > peakCvtTempF))    peakCvtTempF   = cv;
    }

    void resetPeaks() {
        peakBoostPsi = NAN; peakRpm = NAN; peakTimingDeg = NAN;
        peakLoadPct  = NAN; peakSpeedMph = NAN;
        peakMafGs    = NAN; peakEstHp = NAN; peakCatTempF = NAN;
        peakCvtTempF = NAN;
        knockEventCount = 0;
        resetAverages();
    }

private:
    static float _wel(float cur, float newV, int n) {
        return isnan(cur) ? newV : cur + (newV - cur) / (float)n;
    }
};

// Global singleton — written by OBDManager task, read by DashView task.
extern DashData g_data;
