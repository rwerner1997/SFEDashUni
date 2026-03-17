#pragma once
#include <TFT_eSPI.h>
#include "DashData.h"

// ─────────────────────────────────────────────────────────────────────────────
// DashView — TFT_eSPI renderer for ILI9488 480×320 (landscape, rotation 1).
//
// Layout (pixels):
//   Left strip   x: 0..54       RPM bar (vertical)
//   Right strip  x: 426..479    Speed (MPH)
//   Main area    x: 55..425     370×290 — page content
//   Status bar   y: 290..319    BT status / Hz / page name / page dots
//
// Pages (0–7):
//   0  ENGINE         RPM arc (hero), MAF, STFT, LTFT, Load
//   1  TEMPS          Coolant arc, Oil, CVT, CAT, Batt Temp
//   2  BOOST          Boost arc + turbo wheel, Wastegate, STFT, Fine Knock
//   3  FUEL           STFT arc (hero), LTFT, Load, MAF, Timing
//   4  ENGINE VITALS  FA20DIT boxer diagram (animated), Load, Throttle, Oil, VVT-L
//   5  ROUGHNESS      4-cylinder roughness bars
//   6  G-FORCE        Longitudinal G dot plot
//   7  TIMING         Timing arc, Fine Knock, DAM, STFT, Load
//   8  SESSION        Peak values: Boost, RPM, Timing, Load, Speed, CVT, MAF, HP, Knock
//
// IR controls (handled by main.cpp, forwarded via public methods):
//   LEFT/RIGHT         ← prev/next page
//   UP/DOWN            ← prev/next theme
//   OK                 ← toggle drive mode
//   1-8                ← jump to page 0-7
//   9                  ← reset peaks
//   *                  ← dismiss alert
//   #                  ← mute alerts 5 min
// ─────────────────────────────────────────────────────────────────────────────

static constexpr int PAGE_COUNT = 9;

// ── Screen geometry ───────────────────────────────────────────────────────────
static constexpr int STRIP_W    = 55;
static constexpr int SCREEN_W   = 480;
static constexpr int SCREEN_H   = 320;
static constexpr int STATUS_H   = 30;
static constexpr int MAIN_X     = STRIP_W;
static constexpr int MAIN_W     = SCREEN_W - STRIP_W * 2;   // 370
static constexpr int MAIN_H     = SCREEN_H - STATUS_H;       // 290
static constexpr int MAIN_CX    = MAIN_X + MAIN_W / 2;       // 240
static constexpr int MAIN_CY    = MAIN_H / 2;                // 145

// ── Static (non-themed) colours ───────────────────────────────────────────────
static constexpr uint16_t COL_BG      = TFT_BLACK;
static constexpr uint16_t COL_TEXT    = TFT_WHITE;
static constexpr uint16_t COL_DIM     = 0x4208;   // dark grey
static constexpr uint16_t COL_WARN    = TFT_ORANGE;
static constexpr uint16_t COL_DANGER  = TFT_RED;
static constexpr uint16_t COL_GOOD    = TFT_GREEN;
static constexpr uint16_t COL_RPM_BAR = 0x07E0;   // bright green → orange at high RPM

// ── Theme (colour palette, tuned for ILI9488 60-Hz LCD characteristics) ──────
struct Theme {
    const char* name;
    uint16_t accent;      // primary accent colour (arc fill, active dot, highlights)
    uint16_t dimAccent;   // muted version for backgrounds / side-strip fill
    bool     scanlines;   // draw 1-px scan-line overlay over main area
};

static const Theme THEMES[] = {
    // 0  HUD TEAL  — muted teal; raw 0x07FF blooms on ILI9488 backlight
    { "HUD TEAL",    0x0410, 0x0208, true  },
    // 1  AMBER      — warm gold
    { "AMBER",       0xFD40, 0x7A00, false },
    // 2  RACING RED — coral-red; raw 0xF800 is harsh under direct sunlight
    { "RACING RED",  0xF8A0, 0x7800, false },
    // 3  MATRIX     — deep green; 0x07E0 is too bright on ILI9488
    { "MATRIX",      0x0400, 0x0200, true  },
    // 4  NEON PURPL — magenta-violet
    { "NEON PURPLE", 0xC81F, 0x6008, true  },
    // 5  STEALTH    — neutral grey
    { "STEALTH",     0x8410, 0x4208, false },
};
static constexpr int THEME_COUNT = 6;

// ─────────────────────────────────────────────────────────────────────────────

class DashView {
public:
    explicit DashView(DashData& data);

    void begin();
    void draw();               // call from render task at ~30 Hz

    // ── IR-driven controls (called from main loop) ────────────────────────────
    void nextPage();
    void prevPage();
    void nextTheme();
    void prevTheme();
    void toggleDriveMode();
    void dismissAlert();
    void muteAlerts();

private:
    DashData& _data;
    TFT_eSPI  _tft;
    TFT_eSprite _sprite;       // off-screen sprite for main area (PSRAM-friendly)

    int _page     = 0;
    int _lastPage = -1;
    bool _fullRedraw = true;

    // ── Theme ─────────────────────────────────────────────────────────────────
    int _themeIdx = 0;
    const Theme& theme() const { return THEMES[_themeIdx]; }

    // ── Alert state ───────────────────────────────────────────────────────────
    bool _alertActive = false;
    char _alertMsg[48] = {};
    bool _alertMuted   = false;
    unsigned long _alertMuteUntilMs = 0;
    unsigned long _alertLastCheckMs = 0;
    unsigned long _connectedSinceMs = 0;   // for cooldown: don't alert for 90s after connect

    void checkAlerts();
    void triggerAlert(const char* msg);
    void drawAlertOverlay();

    // ── Drive mode ────────────────────────────────────────────────────────────
    bool _driveMode = false;
    void drawDriveMode();

    // ── G-force history ───────────────────────────────────────────────────────
    static constexpr int G_HIST = 120;
    float _gHistory[G_HIST] = {};
    int   _gHead = 0;
    unsigned long _lastGSample = 0;

    // ── Engine animation state ────────────────────────────────────────────────
    float _crankAngleDeg = 0.0f;
    unsigned long _lastEngUpdate = 0;

    // ── Page dispatchers ─────────────────────────────────────────────────────
    void drawPage(int page);
    void drawEnginePage();
    void drawTempsPage();
    void drawBoostPage();
    void drawFuelPage();
    void drawEngineVitalsPage();
    void drawRoughnessPage();
    void drawGforcePage();
    void drawTimingPage();
    void drawSessionPage();

    // ── Common widgets ───────────────────────────────────────────────────────
    void drawSideStrips();
    void drawStatusBar();
    void drawScanlines();

    // Arc gauge: centred at (cx, cy), radius r, sweep 240°, value in [vmin,vmax].
    void drawArcGauge(int cx, int cy, int r,
                      float vmin, float vmax, float v,
                      const char* label, const char* unit,
                      uint16_t arcCol,
                      float warnAt = NAN, float dangerAt = NAN);

    // Turbo wheel animation drawn into sprite centred at (cx, cy), radius r.
    void drawTurboWheel(int cx, int cy, int r, float boostPsi);

    // FA20DIT boxer engine diagram drawn into sprite.
    void drawEngineBlock(float crankDeg, float vvtDeg,
                         float r1, float r2, float r3, float r4);

    // Horizontal bar.
    void drawBar(int x, int y, int w, int h, float pct, uint16_t col, const char* label);

    // Value tile: small labelled numeric box.
    void drawTile(int x, int y, int w, int h, const char* label, float value,
                  const char* fmt, const char* unit);

    // Roughness bar (vertical).
    void drawCylBar(int x, int yBottom, int maxH, int w, float raw, int cylNum);

    // Format a float to a fixed-width string; returns "---" for NaN.
    void fmtVal(char* buf, int bufLen, float v, const char* fmt);

    uint16_t rpmColour(float rpm);
};
