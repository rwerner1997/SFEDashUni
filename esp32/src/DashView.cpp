#include "DashView.h"
#include <math.h>

static const char* PAGE_NAMES[PAGE_COUNT] = {
    "ENGINE", "TEMPS", "BOOST", "FUEL",
    "VITALS", "ROUGHNESS", "G-FORCE", "TIMING", "SESSION"
};

// ─────────────────────────────────────────────────────────────────────────────

DashView::DashView(DashData& data)
    : _data(data), _tft(), _sprite(&_tft) {}

// ── Boot screen ───────────────────────────────────────────────────────────────

void DashView::begin() {
    _tft.init();
    _tft.setRotation(1);
    _tft.fillScreen(COL_BG);
    _tft.setTextDatum(MC_DATUM);

    // ── Boot screen ──────────────────────────────────────────────────────────
    // "SFE" pixel logo — drawn with thick filled rectangles in accent colour
    uint16_t acc = THEMES[0].accent;

    // S
    _tft.fillRect(80, 60, 36, 6, acc);
    _tft.fillRect(80, 60, 6, 22, acc);
    _tft.fillRect(80, 82, 36, 6, acc);
    _tft.fillRect(110, 82, 6, 22, acc);
    _tft.fillRect(80, 104, 36, 6, acc);
    // F
    _tft.fillRect(130, 60, 36, 6, acc);
    _tft.fillRect(130, 60, 6, 50, acc);
    _tft.fillRect(130, 82, 30, 6, acc);
    // E
    _tft.fillRect(180, 60, 36, 6, acc);
    _tft.fillRect(180, 60, 6, 50, acc);
    _tft.fillRect(180, 82, 30, 6, acc);
    _tft.fillRect(180, 104, 36, 6, acc);

    _tft.setTextColor(COL_TEXT, COL_BG);
    _tft.setTextFont(2);
    _tft.setTextDatum(ML_DATUM);
    _tft.drawString("DASHBOARD  v2.0", 230, 80);

    _tft.setTextColor(COL_DIM, COL_BG);
    _tft.setTextFont(1);
    _tft.drawString("ESP32 WROOM-32U  /  ILI9488 480x320  /  HX1838 IR", 230, 100);
    _tft.drawString("FA20DIT  WRX 2015+  /  Veepeak OBDII BT", 230, 114);

    // Boot checklist
    struct Check { const char* label; uint32_t delayMs; };
    Check checks[] = {
        { "TFT INIT",    0   },
        { "IR RECEIVER", 50  },
        { "PSRAM CHECK", 100 },
        { "BT STACK",    200 },
    };
    int cy = 160;
    for (auto& ch : checks) {
        delay(ch.delayMs);
        _tft.setTextColor(COL_GOOD, COL_BG);
        _tft.setTextFont(1);
        _tft.setTextDatum(ML_DATUM);
        _tft.drawString(String("\x07 ") + ch.label + " ..OK", 150, cy);
        cy += 16;
    }

    // Progress bar
    _tft.drawRect(150, cy + 4, 180, 10, COL_DIM);
    for (int i = 0; i <= 180; i += 20) {
        _tft.fillRect(151, cy + 5, i, 8, acc);
        delay(30);
    }

    _tft.setTextColor(COL_DIM, COL_BG);
    _tft.drawString("CONNECTING TO OBDII...", SCREEN_W/2, cy + 24);

    delay(300);
    _tft.fillScreen(COL_BG);

    // ── Sprite init ───────────────────────────────────────────────────────────
    _sprite.setColorDepth(16);
    bool ok = _sprite.createSprite(MAIN_W, MAIN_H);
    if (!ok) {
        Serial.println("[DashView] Sprite alloc failed — direct draw mode");
    }
    _sprite.setTextDatum(MC_DATUM);

    _fullRedraw = true;
}

// ── Public controls ───────────────────────────────────────────────────────────

void DashView::nextPage() {
    _page = (_page + 1) % PAGE_COUNT;
    _data.activePage = _page;
    _fullRedraw = true;
}

void DashView::prevPage() {
    _page = (_page + PAGE_COUNT - 1) % PAGE_COUNT;
    _data.activePage = _page;
    _fullRedraw = true;
}

void DashView::nextTheme() {
    _themeIdx = (_themeIdx + 1) % THEME_COUNT;
    _fullRedraw = true;
}

void DashView::prevTheme() {
    _themeIdx = (_themeIdx + THEME_COUNT - 1) % THEME_COUNT;
    _fullRedraw = true;
}

void DashView::toggleDriveMode() {
    _driveMode = !_driveMode;
    _fullRedraw = true;
}

void DashView::dismissAlert() {
    _alertActive = false;
    _fullRedraw = true;
}

void DashView::muteAlerts() {
    _alertMuted = true;
    _alertMuteUntilMs = millis() + 5UL * 60UL * 1000UL;  // 5 minutes
    _alertActive = false;
    _fullRedraw = true;
}

// ── Main draw ─────────────────────────────────────────────────────────────────

void DashView::draw() {
    // Track connection start for alert cooldown
    if (_data.connected && _connectedSinceMs == 0) _connectedSinceMs = millis();
    if (!_data.connected) _connectedSinceMs = 0;

    // Un-mute if timer expired
    if (_alertMuted && millis() > _alertMuteUntilMs) _alertMuted = false;

    // Check alerts every 30 s (after 90 s cooldown from connect)
    if (_data.connected && !_alertMuted &&
        _connectedSinceMs > 0 && millis() - _connectedSinceMs > 90000 &&
        millis() - _alertLastCheckMs > 30000) {
        checkAlerts();
        _alertLastCheckMs = millis();
    }

    if (_lastPage != _page || _fullRedraw) {
        _tft.fillScreen(COL_BG);
        _fullRedraw = false;
        _lastPage   = _page;
    }

    if (_driveMode) {
        drawDriveMode();
    } else {
        drawPage(_page);
        drawSideStrips();
        drawStatusBar();
    }

    if (_alertActive) {
        drawAlertOverlay();
    }
}

// ── Alert logic ───────────────────────────────────────────────────────────────

void DashView::checkAlerts() {
    if (!isnan(_data.knockCorr)  && _data.knockCorr < -2.5f)     { triggerAlert("SIGNIFICANT KNOCK");  return; }
    if (!isnan(_data.coolantC)   && _data.coolantF() > 225.0f)   { triggerAlert("COOLANT OVERHEAT");   return; }
    if (!isnan(_data.cvtTempC)   && _data.cvtTempF() > 230.0f)   { triggerAlert("CVT OVERHEAT");       return; }
    if (!isnan(_data.oilTempC)   && _data.oilTempF() > 260.0f)   { triggerAlert("OIL OVERHEAT");       return; }
    float b = _data.boostPsi();
    if (!isnan(b) && b > 20.0f)                                   { triggerAlert("BOOST SPIKE");        return; }
    if (!isnan(_data.battV)      && _data.battV < 11.5f
                                 && _data.battV > 5.0f)           { triggerAlert("LOW VOLTAGE");        return; }
    if (!isnan(_data.catTempC)   && _data.catTempF() > 1700.0f)  { triggerAlert("CAT OVERHEAT");       return; }
}

void DashView::triggerAlert(const char* msg) {
    if (_alertActive && strcmp(_alertMsg, msg) == 0) return;  // already showing this alert
    strncpy(_alertMsg, msg, sizeof(_alertMsg) - 1);
    _alertActive = true;
}

void DashView::drawAlertOverlay() {
    // Semi-transparent dark background over main area
    int ax = MAIN_X + 20, ay = 60, aw = MAIN_W - 40, ah = 110;
    _tft.fillRoundRect(ax, ay, aw, ah, 8, 0x1082);
    _tft.drawRoundRect(ax, ay, aw, ah, 8, COL_DANGER);

    // Pulsing warning triangle (alternate colour every 500 ms)
    uint16_t flashCol = (millis() / 500) & 1 ? COL_DANGER : COL_WARN;
    // Triangle: ▲
    int tx = MAIN_CX, ty = ay + 20;
    _tft.fillTriangle(tx, ty, tx - 18, ty + 30, tx + 18, ty + 30, flashCol);
    _tft.fillTriangle(tx, ty + 8, tx - 10, ty + 26, tx + 10, ty + 26, 0x1082);  // inner void

    _tft.setTextColor(COL_TEXT, 0x1082);
    _tft.setTextFont(4);
    _tft.setTextDatum(MC_DATUM);
    _tft.drawString(_alertMsg, MAIN_CX, ay + 68);

    _tft.setTextColor(COL_DIM, 0x1082);
    _tft.setTextFont(1);
    _tft.drawString("IR *=DISMISS  #=MUTE 5 MIN", MAIN_CX, ay + 92);
}

// ── Drive mode overlay ────────────────────────────────────────────────────────

void DashView::drawDriveMode() {
    _tft.fillScreen(COL_BG);

    // Large speed (centre)
    char spd[8]; fmtVal(spd, sizeof(spd), _data.speedMphEst(), "%.0f");
    _tft.setTextColor(COL_TEXT, COL_BG);
    _tft.setTextFont(7);
    _tft.setTextDatum(MC_DATUM);
    _tft.drawString(spd, SCREEN_W/2, SCREEN_H/2 - 20);
    _tft.setTextFont(2);
    _tft.setTextColor(COL_DIM, COL_BG);
    _tft.drawString("MPH", SCREEN_W/2, SCREEN_H/2 + 40);

    // RPM bar (top)
    float rpmPct = isnan(_data.rpm) ? 0.0f : constrain(_data.rpmEst() / 8000.0f, 0.0f, 1.0f);
    int rw = (int)(rpmPct * (SCREEN_W - 40));
    _tft.fillRect(20, 10, SCREEN_W - 40, 12, COL_DIM);
    if (rw > 0) _tft.fillRect(20, 10, rw, 12, rpmColour(_data.rpm));

    // 4 small tiles at bottom: Boost / Load / Oil / CVT
    struct Tile { const char* lbl; float val; const char* fmt; const char* unit; };
    Tile tiles[] = {
        { "BOOST", _data.boostPsiEst(), "%.1f", " psi" },
        { "LOAD",  _data.loadPct,       "%.0f", "%"    },
        { "OIL",   _data.oilTempF(),    "%.0f", "\xB0F"},
        { "CVT",   _data.cvtTempF(),    "%.0f", "\xB0F"},
    };
    int tw = (SCREEN_W - 20) / 4, tx = 10;
    for (auto& t : tiles) {
        _tft.fillRoundRect(tx, SCREEN_H - 52, tw - 4, 44, 4, 0x2104);
        _tft.setTextFont(1); _tft.setTextColor(COL_DIM, 0x2104);
        _tft.setTextDatum(TC_DATUM);
        _tft.drawString(t.lbl, tx + (tw-4)/2, SCREEN_H - 50);
        char buf[12]; fmtVal(buf, sizeof(buf), t.val, t.fmt);
        char full[20]; snprintf(full, sizeof(full), "%s%s", buf, isnan(t.val) ? "" : t.unit);
        _tft.setTextFont(2); _tft.setTextColor(COL_TEXT, 0x2104);
        _tft.setTextDatum(MC_DATUM);
        _tft.drawString(full, tx + (tw-4)/2, SCREEN_H - 28);
        tx += tw;
    }
}

// ── Page dispatch ─────────────────────────────────────────────────────────────

void DashView::drawPage(int page) {
    if (_sprite.created()) _sprite.fillSprite(COL_BG);

    switch (page) {
        case 0: drawEnginePage();       break;
        case 1: drawTempsPage();        break;
        case 2: drawBoostPage();        break;
        case 3: drawFuelPage();         break;
        case 4: drawEngineVitalsPage(); break;
        case 5: drawRoughnessPage();    break;
        case 6: drawGforcePage();       break;
        case 7: drawTimingPage();       break;
        default: drawSessionPage();     break;
    }

    if (_sprite.created()) {
        if (theme().scanlines) drawScanlines();
        _sprite.pushSprite(MAIN_X, 0);
    }
}

// ── Scanline overlay ──────────────────────────────────────────────────────────

void DashView::drawScanlines() {
    if (!_sprite.created()) return;
    for (int y = 0; y < MAIN_H; y += 4) {
        _sprite.drawFastHLine(0, y, MAIN_W, 0x0841);  // very dark translucent line
    }
}

// ── Page 0: ENGINE ────────────────────────────────────────────────────────────

void DashView::drawEnginePage() {
    drawArcGauge(MAIN_W/2, 120, 95,
                 0, 8000, _data.rpmEst(),
                 "RPM", "",
                 rpmColour(_data.rpm),
                 6000, 7000);

    // 4 tiles: MAF, STFT, LTFT, Load
    int tw = 85, th = 56, ty = 202, tx0 = 5;
    drawTile(tx0,           ty, tw, th, "MAF",  _data.mafGs,   "%.1f", " g/s");
    drawTile(tx0 + 90,      ty, tw, th, "STFT", _data.stftPct, "%+.1f", "%");
    drawTile(tx0 + 180,     ty, tw, th, "LTFT", _data.ltftPct, "%+.1f", "%");
    drawTile(tx0 + 270,     ty, tw, th, "LOAD", _data.loadPct, "%.0f",  "%");
}

// ── Page 1: TEMPS ─────────────────────────────────────────────────────────────

void DashView::drawTempsPage() {
    drawArcGauge(MAIN_W/2, 120, 95,
                 60, 260, _data.coolantF(),
                 "COOLANT", "\xB0""F",
                 theme().accent, 220, 240);

    int tw = 85, th = 56, ty = 202, tx0 = 5;
    drawTile(tx0,       ty, tw, th, "OIL",    _data.oilTempF(),  "%.0f", "\xB0F");
    drawTile(tx0 + 90,  ty, tw, th, "CVT",    _data.cvtTempF(),  "%.0f", "\xB0F");
    drawTile(tx0 + 180, ty, tw, th, "CAT",    _data.catTempF(),  "%.0f", "\xB0F");
    drawTile(tx0 + 270, ty, tw, th, "BATTMP", _data.battTempF(), "%.0f", "\xB0F");
}

// ── Page 2: BOOST ─────────────────────────────────────────────────────────────

void DashView::drawBoostPage() {
    float boost = _data.boostPsiEst();

    // Turbo wheel behind the arc gauge
    drawTurboWheel(MAIN_W/2, 120, 88, boost);

    drawArcGauge(MAIN_W/2, 130, 105,
                 -5, 22, boost,
                 "BOOST", "PSI",
                 theme().accent, 18, 21);

    int tw = 110, th = 48, ty = 238, tx0 = 5;
    drawTile(tx0,       ty, tw, th, "WASTEGATE", _data.wastegatePct, "%.0f", "%");
    drawTile(tx0 + 120, ty, tw, th, "STFT",      _data.stftPct,      "%+.1f", "%");
    drawTile(tx0 + 240, ty, tw, th, "F.KNOCK",   _data.fineKnockDeg, "%+.1f", "\xB0");
}

// ── Page 3: FUEL ─────────────────────────────────────────────────────────────

void DashView::drawFuelPage() {
    drawArcGauge(MAIN_W/2, 120, 95,
                 -25, 25, _data.stftPct,
                 "STFT", "%",
                 theme().accent, 10, 20);

    int tw = 85, th = 56, ty = 202, tx0 = 5;
    drawTile(tx0,       ty, tw, th, "LTFT",   _data.ltftPct,  "%+.1f", "%");
    drawTile(tx0 + 90,  ty, tw, th, "LOAD",   _data.loadPct,  "%.0f",  "%");
    drawTile(tx0 + 180, ty, tw, th, "MAF",    _data.mafGs,    "%.1f",  " g/s");
    drawTile(tx0 + 270, ty, tw, th, "TIMING", _data.timingDeg,"%.1f",  "\xB0");
}

// ── Page 4: ENGINE VITALS ─────────────────────────────────────────────────────

void DashView::drawEngineVitalsPage() {
    if (!_sprite.created()) return;

    // Update crank angle from RPM
    unsigned long now = millis();
    if (!isnan(_data.rpm) && _data.rpm > 100.0f && _lastEngUpdate > 0) {
        float dt = (float)(now - _lastEngUpdate);   // ms
        float degsPerMs = _data.rpmEst() / 60000.0f * 360.0f;
        _crankAngleDeg += degsPerMs * dt;
        if (_crankAngleDeg >= 360.0f) _crankAngleDeg -= 360.0f;
    }
    _lastEngUpdate = now;

    // Draw boxer block
    drawEngineBlock(_crankAngleDeg, _data.vvtAngleL,
                    _data.rough1, _data.rough2, _data.rough3, _data.rough4);

    // 4 tiles below diagram
    int tw = 85, th = 48, ty = 230, tx0 = 5;
    drawTile(tx0,       ty, tw, th, "LOAD",   _data.loadPct,   "%.0f",  "%");
    drawTile(tx0 + 90,  ty, tw, th, "THROTTL",_data.throttlePct,"%.0f", "%");
    drawTile(tx0 + 180, ty, tw, th, "OIL",    _data.oilTempF(), "%.0f", "\xB0F");
    drawTile(tx0 + 270, ty, tw, th, "VVT-L",  _data.vvtAngleL,  "%.1f", "\xB0");
}

// ── Page 5: ROUGHNESS ────────────────────────────────────────────────────────

void DashView::drawRoughnessPage() {
    static constexpr int BAR_MAX_H = 220;
    static constexpr int BAR_W     = 60;
    static constexpr int BAR_Y     = 250;

    int xs[4] = { 26, 112, 198, 284 };
    const float vals[4] = { _data.rough1, _data.rough2, _data.rough3, _data.rough4 };
    for (int i = 0; i < 4; i++) {
        drawCylBar(xs[i], BAR_Y, BAR_MAX_H, BAR_W, vals[i], i + 1);
    }

    if (_sprite.created()) {
        _sprite.setTextColor(COL_DIM, COL_BG);
        _sprite.setTextFont(2);
        _sprite.setTextDatum(TC_DATUM);
        _sprite.drawString("ROUGHNESS", MAIN_W/2, 4);
    }
}

// ── Page 6: G-FORCE ──────────────────────────────────────────────────────────

void DashView::drawGforcePage() {
    unsigned long now = millis();
    if (now - _lastGSample >= 100) {
        float g = 0.0f;
        if (!isnan(_data.loadPct) && !isnan(_data.rpm)) {
            g = (_data.loadPct / 100.0f) * (_data.rpm > 2000 ? 0.4f : 0.0f);
        }
        _gHistory[_gHead] = g;
        _gHead = (_gHead + 1) % G_HIST;
        _lastGSample = now;
    }

    TFT_eSprite* c = _sprite.created() ? &_sprite : nullptr;
    if (!c) return;

    c->drawFastHLine(0, MAIN_H/2, MAIN_W, COL_DIM);
    c->drawFastVLine(MAIN_W - 20, 0, MAIN_H, COL_DIM);

    int plotW = MAIN_W - 20;
    float xScale = (float)plotW / G_HIST;
    for (int i = 0; i < G_HIST; i++) {
        int idx = (_gHead + i) % G_HIST;
        float g = _gHistory[idx];
        int x = (int)(i * xScale);
        int y = MAIN_H/2 - (int)(g * (MAIN_H/2));
        y = constrain(y, 0, MAIN_H - 1);
        c->fillCircle(x, y, 2, theme().accent);
    }

    c->setTextColor(COL_DIM, COL_BG);
    c->setTextFont(2);
    c->drawString("G-FORCE (LONGITUDINAL)", MAIN_W/2, 4);
    c->drawString("+1G", MAIN_W - 10, 8);
    c->drawString(" 0G", MAIN_W - 10, MAIN_H/2 - 6);
    c->drawString("-1G", MAIN_W - 10, MAIN_H - 16);
}

// ── Page 7: TIMING ───────────────────────────────────────────────────────────

void DashView::drawTimingPage() {
    drawArcGauge(MAIN_W/2, 120, 95,
                 -20, 50, _data.timingDeg,
                 "TIMING", "\xB0",
                 theme().accent);

    int tw = 85, th = 56, ty = 202, tx0 = 5;
    drawTile(tx0,       ty, tw, th, "F.KNOCK", _data.fineKnockDeg, "%+.1f", "\xB0");
    drawTile(tx0 + 90,  ty, tw, th, "DAM",     _data.damRatio,      "%.2f", "");
    drawTile(tx0 + 180, ty, tw, th, "STFT",    _data.stftPct,       "%+.1f", "%");
    drawTile(tx0 + 270, ty, tw, th, "LOAD",    _data.loadPct,        "%.0f", "%");
}

// ── Page 8 (default): SESSION ─────────────────────────────────────────────────

void DashView::drawSessionPage() {
    TFT_eSprite* c = _sprite.created() ? &_sprite : nullptr;
    if (!c) return;

    c->setTextFont(2);
    c->setTextDatum(TL_DATUM);

    struct Row { const char* label; float value; const char* fmt; const char* unit; };
    Row rows[] = {
        { "PEAK BOOST",   _data.peakBoostPsi,              "%.1f",  " psi" },
        { "PEAK RPM",     _data.peakRpm,                   "%.0f",  " rpm" },
        { "PEAK TIMING",  _data.peakTimingDeg,             "%.1f",  "\xB0" },
        { "PEAK LOAD",    _data.peakLoadPct,                "%.0f",  "%"   },
        { "PEAK SPEED",   _data.peakSpeedMph,               "%.0f",  " mph" },
        { "PEAK CVT",     _data.peakCvtTempF,               "%.0f",  "\xB0F"},
        { "PEAK MAF",     _data.peakMafGs,                   "%.1f",  " g/s" },
        { "PEAK HP",      _data.peakEstHp,                   "%.0f",  " hp"  },
        { "PEAK CAT",     _data.peakCatTempF,                "%.0f",  "\xB0F"},
        { "KNOCK EVENTS", (float)_data.knockEventCount,     "%.0f",  ""     },
    };

    int y = 8;
    char buf[32];
    for (auto& row : rows) {
        c->setTextColor(COL_DIM, COL_BG);
        c->setTextDatum(TL_DATUM);
        c->drawString(row.label, 8, y);

        fmtVal(buf, sizeof(buf), row.value, row.fmt);
        char full[40];
        if (!isnan(row.value)) snprintf(full, sizeof(full), "%s%s", buf, row.unit);
        else strncpy(full, "---", sizeof(full));

        c->setTextColor(COL_TEXT, COL_BG);
        c->setTextDatum(TR_DATUM);
        c->drawString(full, MAIN_W - 8, y);
        y += 28;
    }

    c->setTextColor(COL_DIM, COL_BG);
    c->setTextDatum(BC_DATUM);
    c->drawString("IR 9 = RESET", MAIN_W/2, MAIN_H - 4);
}

// ── Side strips ───────────────────────────────────────────────────────────────

void DashView::drawSideStrips() {
    // Left strip: vertical RPM bar
    static constexpr int BAR_PAD = 4;
    static constexpr int BAR_TOP = 8;
    int barH = MAIN_H - BAR_TOP - BAR_PAD;
    int barX = BAR_PAD;
    int barW = STRIP_W - BAR_PAD * 2;

    _tft.fillRect(0, 0, STRIP_W, MAIN_H, COL_BG);

    float rpm = _data.rpmEst();
    float rpmPct = isnan(rpm) ? 0.0f : constrain(rpm / 8000.0f, 0.0f, 1.0f);
    int fillH = (int)(rpmPct * barH);

    _tft.fillRect(barX, BAR_TOP, barW, barH, COL_DIM);
    if (fillH > 0) {
        _tft.fillRect(barX, BAR_TOP + barH - fillH, barW, fillH, rpmColour(rpm));
    }
    char buf[8]; fmtVal(buf, sizeof(buf), rpm, "%.0f");
    _tft.setTextColor(COL_TEXT, COL_BG);
    _tft.setTextFont(1);
    _tft.setTextDatum(BC_DATUM);
    _tft.drawString(buf, STRIP_W/2, MAIN_H - 2);

    // Right strip: speed in MPH
    _tft.fillRect(SCREEN_W - STRIP_W, 0, STRIP_W, MAIN_H, COL_BG);
    char spd[8]; fmtVal(spd, sizeof(spd), _data.speedMphEst(), "%.0f");
    _tft.setTextColor(COL_TEXT, COL_BG);
    _tft.setTextFont(4);
    _tft.setTextDatum(MC_DATUM);
    _tft.drawString(spd, SCREEN_W - STRIP_W/2, MAIN_H/2 - 14);
    _tft.setTextFont(1);
    _tft.setTextColor(COL_DIM, COL_BG);
    _tft.drawString("MPH", SCREEN_W - STRIP_W/2, MAIN_H/2 + 14);
}

// ── Status bar ────────────────────────────────────────────────────────────────

void DashView::drawStatusBar() {
    int y = MAIN_H;
    _tft.fillRect(0, y, SCREEN_W, STATUS_H, 0x1082);

    _tft.setTextFont(1);
    _tft.setTextDatum(ML_DATUM);
    _tft.setTextColor(_data.connected ? COL_GOOD : COL_WARN, 0x1082);
    _tft.drawString(_data.btStatus, 6, y + STATUS_H/2);

    // Protocol + Hz + page name
    char hz[32];
    snprintf(hz, sizeof(hz), "%d Hz  %s  [%s]",
             _data.pollHz,
             _page < PAGE_COUNT ? PAGE_NAMES[_page] : "?",
             theme().name);
    _tft.setTextColor(COL_DIM, 0x1082);
    _tft.setTextDatum(MR_DATUM);
    _tft.drawString(hz, SCREEN_W - 4, y + STATUS_H/2);

    // Page dots (8 dots)
    int dotY = y + STATUS_H - 6;
    int dotSpacing = 14;
    int dotsW = (PAGE_COUNT - 1) * dotSpacing;
    int dotStartX = SCREEN_W/2 - dotsW/2;
    for (int i = 0; i < PAGE_COUNT; i++) {
        int dotX = dotStartX + i * dotSpacing;
        if (i == _page) {
            _tft.fillCircle(dotX, dotY, 4, theme().accent);
        } else {
            _tft.fillRect(dotX - 2, dotY - 2, 5, 5, COL_DIM);
        }
    }
}

// ── Turbo wheel animation ─────────────────────────────────────────────────────

void DashView::drawTurboWheel(int cx, int cy, int r, float boostPsi) {
    TFT_eSprite* c = _sprite.created() ? &_sprite : nullptr;
    if (!c) return;

    // Rotation speed: 0.2°/ms at idle boost, 1.2°/ms at 20 psi
    static float turboAngle = 0.0f;
    static unsigned long lastTurboMs = 0;
    unsigned long now = millis();
    if (lastTurboMs > 0) {
        float dt = (float)(now - lastTurboMs);
        float boost = isnan(boostPsi) ? 0.0f : boostPsi;
        float degPerMs = 0.2f + constrain(boost / 20.0f, 0.0f, 1.0f) * 1.0f;
        turboAngle += degPerMs * dt;
        if (turboAngle >= 360.0f) turboAngle -= 360.0f;
    }
    lastTurboMs = now;

    // Diffuser ring (outer)
    c->drawCircle(cx, cy, r + 2, COL_DIM);

    // 8 blades
    float baseRad = turboAngle * M_PI / 180.0f;
    for (int i = 0; i < 8; i++) {
        float a1 = baseRad + i * (2.0f * M_PI / 8.0f);
        float a2 = a1 + 0.35f;
        int ix = cx + (int)(cosf(a1) * (r - 14));
        int iy = cy + (int)(sinf(a1) * (r - 14));
        int ox = cx + (int)(cosf(a2) * (r - 2));
        int oy = cy + (int)(sinf(a2) * (r - 2));
        c->drawLine(cx, cy, ix, iy, theme().dimAccent);
        c->drawLine(ix, iy, ox, oy, theme().dimAccent);
    }

    // Hub
    c->fillCircle(cx, cy, 8, COL_DIM);
    c->fillCircle(cx, cy, 4, theme().dimAccent);
}

// ── FA20DIT boxer engine block diagram ────────────────────────────────────────
// Layout: left bank = cylinders 1,3 (left of centreline, pistons move right←)
//         right bank = cylinders 2,4 (right of centreline, pistons move →right)
// FA20 firing order: 1-3-2-4; crank offset between pairs = 180°

void DashView::drawEngineBlock(float crankDeg, float vvtDeg,
                                float r1, float r2, float r3, float r4) {
    TFT_eSprite* c = _sprite.created() ? &_sprite : nullptr;
    if (!c) return;

    // Centreline (crankshaft axis) horizontal through cy
    const int cy   = 110;   // vertical centre of block diagram
    const int boreH = 48;   // bore height (perpendicular to piston travel)
    const int stroke = 50;  // full stroke travel in pixels
    const int bankGap = 60; // gap between inner faces of banks

    // Cylinder block outlines
    // Left bank (cyl 1 top, cyl 3 bottom in column)
    // Right bank (cyl 2 top, cyl 4 bottom in column)
    // Layout: [LBank ] [crankcase] [RBank]
    //         x=10..90  x=90..280  x=280..360

    const int lWall = 15;   // left edge of left bank
    const int lInner = 90;  // inner face of left bank (pistons move right toward this)
    const int rInner = 280; // inner face of right bank
    const int rWall = 355;  // right edge of right bank
    const int cylH = boreH;
    const int cyl1Y = cy - cylH - 8;   // top cylinder centre (left bank)
    const int cyl2Y = cy - cylH - 8;   // top cylinder centre (right bank)
    const int cyl3Y = cy + 8;          // bottom cylinder centre (left bank)
    const int cyl4Y = cy + 8;          // bottom cylinder centre (right bank)

    // Roughness → cylinder fill colour
    auto cylCol = [this](float r) -> uint16_t {
        if (isnan(r) || r < 0) return COL_DIM;
        if (r < 20) return COL_GOOD;
        if (r < 50) return COL_WARN;
        return COL_DANGER;
    };

    // Draw cylinder bores (left bank, horizontal)
    c->fillRect(lWall, cyl1Y,  lInner - lWall, cylH, 0x18C3);  // bore body
    c->fillRect(lWall, cyl3Y,  lInner - lWall, cylH, 0x18C3);
    c->drawRect(lWall, cyl1Y,  lInner - lWall, cylH, COL_DIM);
    c->drawRect(lWall, cyl3Y,  lInner - lWall, cylH, COL_DIM);

    // Draw cylinder bores (right bank, horizontal)
    c->fillRect(rInner, cyl2Y, rWall - rInner, cylH, 0x18C3);
    c->fillRect(rInner, cyl4Y, rWall - rInner, cylH, 0x18C3);
    c->drawRect(rInner, cyl2Y, rWall - rInner, cylH, COL_DIM);
    c->drawRect(rInner, cyl4Y, rWall - rInner, cylH, COL_DIM);

    // Crankcase outline
    c->drawRect(lInner, cyl1Y - 4, rInner - lInner, (cyl3Y + cylH) - (cyl1Y - 4) + 4, COL_DIM);

    // Crankshaft dot at centre
    c->fillCircle(MAIN_W/2, cy + cylH/2, 8, COL_DIM);
    c->fillCircle(MAIN_W/2, cy + cylH/2, 4, theme().accent);

    // Piston positions from crank angle
    // Cyl 1,2 share crank pin at 0°; cyl 3,4 share at 180°
    auto pistonX = [&](float baseOffset, bool leftBank) -> int {
        float rad = (crankDeg + baseOffset) * M_PI / 180.0f;
        float normalised = (cosf(rad) + 1.0f) / 2.0f;  // 0=BDC, 1=TDC
        int travel = (int)(normalised * stroke);
        return leftBank ? (lInner - 2 - travel) : (rInner + 2 + travel);
    };

    // Piston widths
    const int pistW = 22, pistH = cylH - 8;

    // Cyl 1 (left bank, top, phase 0°)
    int p1x = pistonX(0, true);
    uint16_t pc1 = cylCol(r1);
    c->fillRect(p1x - pistW, cyl1Y + 4, pistW, pistH, pc1);
    c->drawRect(p1x - pistW, cyl1Y + 4, pistW, pistH, COL_TEXT);
    // Connecting rod: piston crown → crank centre
    c->drawLine(p1x - pistW/2, cyl1Y + 4 + pistH/2, MAIN_W/2, cy + cylH/2, 0x4208);

    // TDC combustion flash: piston near top (p1x close to lInner)
    if (p1x > lInner - stroke/4) {
        c->fillCircle(lWall + 14, cyl1Y + cylH/2, 6, COL_WARN);
    }

    // Cyl 2 (right bank, top, phase 0°)
    int p2x = pistonX(0, false);
    uint16_t pc2 = cylCol(r2);
    c->fillRect(p2x, cyl2Y + 4, pistW, pistH, pc2);
    c->drawRect(p2x, cyl2Y + 4, pistW, pistH, COL_TEXT);
    c->drawLine(p2x + pistW/2, cyl2Y + 4 + pistH/2, MAIN_W/2, cy + cylH/2, 0x4208);
    if (p2x < rInner + stroke/4) {
        c->fillCircle(rWall - 14, cyl2Y + cylH/2, 6, COL_WARN);
    }

    // Cyl 3 (left bank, bottom, phase 180°)
    int p3x = pistonX(180, true);
    uint16_t pc3 = cylCol(r3);
    c->fillRect(p3x - pistW, cyl3Y + 4, pistW, pistH, pc3);
    c->drawRect(p3x - pistW, cyl3Y + 4, pistW, pistH, COL_TEXT);
    c->drawLine(p3x - pistW/2, cyl3Y + 4 + pistH/2, MAIN_W/2, cy + cylH/2, 0x4208);
    if (p3x > lInner - stroke/4) {
        c->fillCircle(lWall + 14, cyl3Y + cylH/2, 6, COL_WARN);
    }

    // Cyl 4 (right bank, bottom, phase 180°)
    int p4x = pistonX(180, false);
    uint16_t pc4 = cylCol(r4);
    c->fillRect(p4x, cyl4Y + 4, pistW, pistH, pc4);
    c->drawRect(p4x, cyl4Y + 4, pistW, pistH, COL_TEXT);
    c->drawLine(p4x + pistW/2, cyl4Y + 4 + pistH/2, MAIN_W/2, cy + cylH/2, 0x4208);
    if (p4x < rInner + stroke/4) {
        c->fillCircle(rWall - 14, cyl4Y + cylH/2, 6, COL_WARN);
    }

    // VVT intake cam angle indicator (left bank, top intake port)
    if (!isnan(vvtDeg)) {
        float vvtRad = vvtDeg * M_PI / 180.0f;
        int camX = lWall + 10;
        int camY = cyl1Y - 10;
        c->fillCircle(camX, camY, 5, theme().dimAccent);
        c->drawLine(camX, camY,
                    camX + (int)(cosf(vvtRad) * 10),
                    camY + (int)(sinf(vvtRad) * 10),
                    theme().accent);
    }

    // Cylinder labels
    c->setTextFont(1); c->setTextColor(COL_DIM, COL_BG);
    c->setTextDatum(BC_DATUM);
    c->drawString("1", lWall + (lInner - lWall)/2, cyl1Y - 2);
    c->drawString("3", lWall + (lInner - lWall)/2, cyl3Y - 2);
    c->drawString("2", rInner + (rWall - rInner)/2, cyl2Y - 2);
    c->drawString("4", rInner + (rWall - rInner)/2, cyl4Y - 2);
}

// ── Colour blend helper (RGB565) ─────────────────────────────────────────────
// t=0.0 → returns a, t=1.0 → returns b, t=0.5 → midpoint.

static uint16_t blendColor(uint16_t a, uint16_t b, float t) {
    t = constrain(t, 0.0f, 1.0f);
    uint8_t ar = (a >> 11) & 0x1F, ag = (a >> 5) & 0x3F, ab = a & 0x1F;
    uint8_t br = (b >> 11) & 0x1F, bg = (b >> 5) & 0x3F, bb = b & 0x1F;
    uint8_t r  = (uint8_t)(ar + (int)((br - ar) * t));
    uint8_t g  = (uint8_t)(ag + (int)((bg - ag) * t));
    uint8_t bl = (uint8_t)(ab + (int)((bb - ab) * t));
    return (uint16_t)((r << 11) | (g << 5) | bl);
}

// ── Arc gauge ─────────────────────────────────────────────────────────────────

void DashView::drawArcGauge(int cx, int cy, int r,
                             float vmin, float vmax, float v,
                             const char* label, const char* unit,
                             uint16_t arcCol,
                             float warnAt, float dangerAt) {
    TFT_eSprite* c = _sprite.created() ? &_sprite : nullptr;

    static constexpr float START_DEG = 150.0f;
    static constexpr float SWEEP_DEG = 240.0f;

    // Track arc (background)
    if (c) c->drawArc(cx, cy, r, r - 12, (int)START_DEG, (int)(START_DEG + SWEEP_DEG), COL_DIM, COL_BG);
    else   _tft.drawArc(cx, cy, r, r - 12, (int)START_DEG, (int)(START_DEG + SWEEP_DEG), COL_DIM, COL_BG);

    // Tick marks: 21 ticks (every 12° of the 240° sweep), major every 4th
    if (c) {
        for (int i = 0; i <= 20; i++) {
            float tickRad = (START_DEG + (i / 20.0f) * SWEEP_DEG) * (float)M_PI / 180.0f;
            bool major = (i % 4 == 0);
            int len = major ? 7 : 4;
            int x0 = cx + (int)(cosf(tickRad) * (r - 13));
            int y0 = cy + (int)(sinf(tickRad) * (r - 13));
            int x1 = cx + (int)(cosf(tickRad) * (r - 13 + len));
            int y1 = cy + (int)(sinf(tickRad) * (r - 13 + len));
            c->drawLine(x0, y0, x1, y1, major ? COL_DIM : 0x2104);
        }
    }

    // Value arc + glow rings + end dot
    if (!isnan(v) && vmax > vmin) {
        float pct = constrain((v - vmin) / (vmax - vmin), 0.0f, 1.0f);
        float endDeg = START_DEG + pct * SWEEP_DEG;

        uint16_t col = arcCol;
        if (!isnan(dangerAt) && v >= dangerAt) col = COL_DANGER;
        else if (!isnan(warnAt) && v >= warnAt) col = COL_WARN;

        if (c) {
            // Outer glow rings (simulate alpha by blending toward background)
            uint16_t g1 = blendColor(col, COL_BG, 0.75f);  // faint outer ring
            uint16_t g2 = blendColor(col, COL_BG, 0.55f);  // medium ring
            c->drawArc(cx, cy, r + 5, r + 3, (int)START_DEG, (int)endDeg, g1, COL_BG);
            c->drawArc(cx, cy, r + 2, r,     (int)START_DEG, (int)endDeg, g2, COL_BG);
            // Main value arc
            c->drawArc(cx, cy, r, r - 12, (int)START_DEG, (int)endDeg, col, COL_BG);
            // End-of-arc dot
            if (pct > 0.01f) {
                float endRad = endDeg * (float)M_PI / 180.0f;
                int ex = cx + (int)(cosf(endRad) * (r - 6));
                int ey = cy + (int)(sinf(endRad) * (r - 6));
                c->fillCircle(ex, ey, 5, blendColor(col, COL_BG, 0.4f));
                c->fillCircle(ex, ey, 3, col);
                c->fillCircle(ex, ey, 1, COL_TEXT);
            }
        } else {
            _tft.drawArc(cx, cy, r, r - 12, (int)START_DEG, (int)endDeg, col, COL_BG);
        }
    }

    // Value text
    char buf[16]; fmtVal(buf, sizeof(buf), v, isnan(v) ? nullptr : "%.1f");
    char valStr[24]; snprintf(valStr, sizeof(valStr), "%s%s", buf, unit);

    if (c) {
        c->setTextColor(COL_TEXT, COL_BG); c->setTextFont(6); c->setTextDatum(MC_DATUM);
        c->drawString(valStr, cx, cy - 10);
        c->setTextFont(2); c->setTextColor(COL_DIM, COL_BG);
        c->drawString(label, cx, cy + 22);
    } else {
        _tft.setTextColor(COL_TEXT, COL_BG); _tft.setTextFont(6); _tft.setTextDatum(MC_DATUM);
        _tft.drawString(valStr, cx, cy - 10);
        _tft.setTextFont(2); _tft.setTextColor(COL_DIM, COL_BG);
        _tft.drawString(label, cx, cy + 22);
    }
}

// ── Bar widget ────────────────────────────────────────────────────────────────

void DashView::drawBar(int x, int y, int w, int h, float pct, uint16_t col, const char* label) {
    TFT_eSprite* c = _sprite.created() ? &_sprite : nullptr;
    int fill = isnan(pct) ? 0 : (int)(constrain(pct, 0, 100) / 100.0f * w);

    if (c) {
        c->fillRect(x, y, w, h, COL_DIM);
        if (fill > 0) c->fillRect(x, y, fill, h, col);
        c->setTextColor(COL_DIM, COL_BG); c->setTextFont(1); c->setTextDatum(TL_DATUM);
        c->drawString(label, x, y - 12);
    } else {
        _tft.fillRect(x, y, w, h, COL_DIM);
        if (fill > 0) _tft.fillRect(x, y, fill, h, col);
    }
}

// ── Tile widget ───────────────────────────────────────────────────────────────

void DashView::drawTile(int x, int y, int w, int h, const char* label, float value,
                        const char* fmt, const char* unit) {
    TFT_eSprite* c = _sprite.created() ? &_sprite : nullptr;

    char buf[16]; fmtVal(buf, sizeof(buf), value, fmt);
    char valStr[24]; snprintf(valStr, sizeof(valStr), "%s%s", buf, isnan(value) ? "" : unit);

    if (c) {
        c->fillRoundRect(x, y, w, h, 4, 0x2104);
        c->setTextColor(COL_DIM, 0x2104); c->setTextFont(1); c->setTextDatum(TC_DATUM);
        c->drawString(label, x + w/2, y + 4);
        c->setTextColor(COL_TEXT, 0x2104); c->setTextFont(4); c->setTextDatum(MC_DATUM);
        c->drawString(valStr, x + w/2, y + h/2 + 6);
    } else {
        int tx = x + MAIN_X;
        _tft.fillRoundRect(tx, y, w, h, 4, 0x2104);
        _tft.setTextColor(COL_DIM, 0x2104); _tft.setTextFont(1); _tft.setTextDatum(TC_DATUM);
        _tft.drawString(label, tx + w/2, y + 4);
        _tft.setTextColor(COL_TEXT, 0x2104); _tft.setTextFont(4); _tft.setTextDatum(MC_DATUM);
        _tft.drawString(valStr, tx + w/2, y + h/2 + 6);
    }
}

// ── Cylinder bar (roughness) ──────────────────────────────────────────────────

void DashView::drawCylBar(int x, int yBottom, int maxH, int w, float raw, int cylNum) {
    TFT_eSprite* c = _sprite.created() ? &_sprite : nullptr;

    int fillH = isnan(raw) ? 0 : (int)(constrain(raw / 100.0f, 0.0f, 1.0f) * maxH);
    uint16_t col = (raw > 50) ? COL_WARN : (raw > 20 ? theme().accent : COL_GOOD);

    if (c) {
        c->fillRect(x, yBottom - maxH, w, maxH, COL_DIM);
        if (fillH > 0) c->fillRect(x, yBottom - fillH, w, fillH, col);
        char buf[4]; snprintf(buf, sizeof(buf), "%d", cylNum);
        c->setTextColor(COL_DIM, COL_BG); c->setTextFont(2); c->setTextDatum(BC_DATUM);
        c->drawString(buf, x + w/2, yBottom + 14);
        char val[8]; fmtVal(val, sizeof(val), raw, "%.0f");
        c->setTextColor(COL_TEXT, COL_BG); c->setTextFont(1);
        c->drawString(val, x + w/2, yBottom - maxH - 4);
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

void DashView::fmtVal(char* buf, int bufLen, float v, const char* fmt) {
    if (isnan(v) || fmt == nullptr) {
        strncpy(buf, "---", bufLen);
        return;
    }
    snprintf(buf, bufLen, fmt, v);
}

uint16_t DashView::rpmColour(float rpm) {
    if (isnan(rpm) || rpm < 5000) return COL_RPM_BAR;
    if (rpm < 6500)               return COL_WARN;
    return COL_DANGER;
}
