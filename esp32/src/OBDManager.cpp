#include "OBDManager.h"

// ── PIN pairing support ───────────────────────────────────────────────────────
// The Veepeak uses PIN "1234". On first connection the ESP32 must pair.
// We register a GAP callback so the ESP32 responds to SSP/legacy PIN requests
// automatically rather than needing user interaction.
#include <esp_bt_main.h>
#include <esp_gap_bt_api.h>

static void btGapCallback(esp_bt_gap_cb_event_t event, esp_bt_gap_cb_param_t* param) {
    if (event == ESP_BT_GAP_AUTH_CMPL_EVT) {
        if (param->auth_cmpl.stat == ESP_BT_STATUS_SUCCESS) {
            Serial.printf("[BT] Paired with %s\n", param->auth_cmpl.device_name);
        } else {
            Serial.printf("[BT] Pairing failed, status=%d\n", param->auth_cmpl.stat);
        }
    } else if (event == ESP_BT_GAP_PIN_REQ_EVT) {
        // Legacy PIN pairing — respond with "1234"
        esp_bt_pin_code_t pin = {'1','2','3','4'};
        esp_bt_gap_pin_reply(param->pin_req.bda, true, 4, pin);
    }
}

// ─────────────────────────────────────────────────────────────────────────────

OBDManager::OBDManager(DashData& data) : _data(data) {}

void OBDManager::start() {
    if (_running) return;
    _running = true;
    xTaskCreatePinnedToCore(taskEntry, "OBD-Poll", 8192, this, 1, nullptr, 1);
}

void OBDManager::stop() {
    _running = false;
    _bt.disconnect();
}

void OBDManager::taskEntry(void* pv) {
    static_cast<OBDManager*>(pv)->connectionLoop();
    vTaskDelete(nullptr);
}

// ── Connection loop ───────────────────────────────────────────────────────────

void OBDManager::connectionLoop() {
    // Register GAP callback for PIN pairing before starting BT.
    esp_bt_gap_register_callback(btGapCallback);

    while (_running) {
        snprintf((char*)_data.btStatus, sizeof(_data.btStatus), "SEARCHING");
        _data.connected = false;

        // Start BT stack in master mode.
        if (_bt.isReady()) _bt.end();
        _bt.begin("SFEDash", /*isMaster=*/true);

        Serial.println("[OBD] Connecting to " BT_DEVICE_NAME "...");
        snprintf((char*)_data.btStatus, sizeof(_data.btStatus), "CONNECTING");

        bool ok = _bt.connect(BT_DEVICE_NAME);
        if (!ok) {
            Serial.println("[OBD] BT connect failed");
            snprintf((char*)_data.btStatus, sizeof(_data.btStatus), "NOT FOUND");
            _bt.end();
            sleepMs(CONNECT_RETRY_MS);
            continue;
        }

        Serial.println("[OBD] BT connected, initialising ELM327...");
        snprintf((char*)_data.btStatus, sizeof(_data.btStatus), "INIT ELM327");

        // Flush any stale bytes
        while (_bt.available()) _bt.read();

        bool initOk = true;
        String proto;
        try {
            initELM327();
            proto = sendCmd("ATDPN");
            proto.trim();
        } catch (...) {
            initOk = false;
        }

        if (!initOk || proto.length() == 0) {
            Serial.println("[OBD] ELM327 init failed");
            snprintf((char*)_data.btStatus, sizeof(_data.btStatus), "INIT FAIL");
            _bt.disconnect();
            sleepMs(CONNECT_RETRY_MS);
            continue;
        }

        proto.toCharArray((char*)_data.obdProtocol, sizeof(_data.obdProtocol));
        snprintf((char*)_data.btStatus, sizeof(_data.btStatus), "CONNECTED");
        _data.connected = true;
        Serial.printf("[OBD] Connected, protocol: %s\n", _data.obdProtocol);

        pollLoop();

        _data.connected = false;
        snprintf((char*)_data.btStatus, sizeof(_data.btStatus), "RECONNECTING");
        _bt.disconnect();
        sleepMs(CONNECT_RETRY_MS);
    }
}

// ── ELM327 initialisation ──────────────────────────────────────────────────

void OBDManager::initELM327() {
    sleepMs(500);
    sendRaw("ATZ\r");
    sleepMs(1500);
    // Drain ATZ response
    readUntilPrompt(3000);

    sendCmd("ATE0");    // echo off
    sendCmd("ATL0");    // linefeeds off
    sendCmd("ATH0");    // headers off
    sendCmd("ATS0");    // spaces off
    sendCmd("ATSP0");   // auto protocol
    sendCmd("ATST0A");  // 40ms ELM timeout
    sendCmd("ATAT2");   // adaptive timing mode 2
    sendCmd("ATAL");    // allow long messages
    sendCmd("ATCRA7E8"); // default CAN filter: ECU

    String r = sendCmd("0100");
    if (r.indexOf("UNABLE") >= 0 || r.indexOf("ERROR") >= 0 || r.length() == 0) {
        Serial.println("[OBD] 0100 ping failed: " + r);
        // Don't throw — some ELM clones need a second attempt; pollLoop will catch errors.
    }
}

// ── Main poll loop ─────────────────────────────────────────────────────────
//
// Tier 1 (~20 Hz): RPM (010C), MAP (010B)
// Tier 2 (~7 Hz):  Speed, Timing, MAF, Pedal, Load, STFT, LTFT,
//                  Wastegate, Fine Knock, VVT-L
// Tier 3a (~1 Hz): Slow sensors + CVT temp (TCU 7E1, PID 2210D2)
// Tier 3d (~1 Hz): DAM (2210B1)
//
// Header switching is expensive (2× AT round-trips). Tiers are ordered so
// Mode 01 (7DF/7E8) and Mode 22 ECU (7E0/7E8) blocks are grouped together.

void OBDManager::pollLoop() {
    int  loopCount = 0;
    int  hzCount   = 0;
    unsigned long lastHz = millis();

    // Reset header cache — ATZ wipes ELM327 state, force re-send.
    _currentHeader[0] = '\0';
    _currentCRA[0]    = '\0';

    while (_running && _bt.connected()) {
        unsigned long t0 = millis();

        // ── TIER 1: every loop (~20 Hz) — RPM + MAP ────────────────────────
        setHeader("7DF", "7E8");
        parseRPM(sendCmd("010C"));
        parseMAP(sendCmd("010B"));

        // ── TIER 2: every 3rd loop (~7 Hz) ────────────────────────────────
        if (loopCount % 3 == 0) {
            setHeader("7DF", "7E8");
            parseSpeed(sendCmd("010D"));
            parseTiming(sendCmd("010E"));
            parseMAF(sendCmd("0110"));
            parsePedal(sendCmd("0145"));
            parseLoad(sendCmd("0104"));
            parseSTFT(sendCmd("0106"));
            parseLTFT(sendCmd("0107"));

            setHeader("7E0", "7E8");
            parseWastegate(sendCmdTimeout("2210A8", CMD_TIMEOUT_SLOW));
            parseFineKnock(sendCmdTimeout("2210B0", CMD_TIMEOUT_SLOW));
            parseVvtAngleL(sendCmdTimeout("2210B9", CMD_TIMEOUT_SLOW));
        }

        // ── TIER 3a: every 10th loop (~1 Hz) — slow/temps ─────────────────
        if (loopCount % 10 == 0) {
            int ap = _data.activePage;

            setHeader("7DF", "7E8");
            parseCoolant(sendCmd("0105"));
            parseOilTemp(sendCmd("015C"));
            parseCatTemp(sendCmd("013C"));
            parseBaro(sendCmd("0133"));
            parseFuelLevel(sendCmd("012F"));
            parseBattery(sendCmd("ATRV"));

            // CVT temp + shift selector — TCU (7E1/7E9).
            // Use setHeaderForce — cheap ELM clones silently drop ATCRA on ATSH change.
            setHeaderForce("7E1", "7E9");
            parseCVTTemp(sendCmdTimeout("2210D2", CMD_TIMEOUT_SLOW));
            // Shift selector: 221093 (primary) + 221095 (secondary).
            // Header already set to 7E1/7E9; no extra ATSH needed.
            String r93 = sendCmdTimeout("221093", CMD_TIMEOUT_SLOW);
            String r95 = sendCmdTimeout("221095", CMD_TIMEOUT_SLOW);
            parseShiftSelector(r93, r95);

            // Roughness polled when on ENGINE VITALS (page 4) or ROUGHNESS (page 5).
            // Switch back to ECU header for roughness (7E0/7E8).
            if (ap == 4 || ap == 5) {
                setHeaderForce("7E0", "7E8");
                parseRoughness(sendCmdTimeout("223062", CMD_TIMEOUT_ROUGH), 1);
                parseRoughness(sendCmdTimeout("223048", CMD_TIMEOUT_ROUGH), 2);
                parseRoughness(sendCmdTimeout("223068", CMD_TIMEOUT_ROUGH), 3);
                parseRoughness(sendCmdTimeout("22304A", CMD_TIMEOUT_ROUGH), 4);
            }
        }

        // ── TIER 3d: DAM + injection — offset 7 in the 10-loop window ────────
        if (loopCount % 10 == 7) {
            setHeader("7E0", "7E8");
            parseDAM(sendCmdTimeout("2210B1", CMD_TIMEOUT_SLOW));
            parseInjDutyCycle(sendCmdTimeout("2210C1", CMD_TIMEOUT_SLOW));
            parseInjPulse(sendCmdTimeout("2210B4", CMD_TIMEOUT_SLOW));
        }

        _data.updatePeaks();
        _data.updateAverages();
        _data.recordKnockEvent();

        loopCount++;
        unsigned long now = millis();
        if (now - lastHz >= 1000) {
            _data.pollHz = hzCount;
            hzCount = 0;
            lastHz  = now;
        }
        hzCount++;
        _data.lastPollMs = millis() - t0;
    }
}

// ── I/O ─────────────────────────────────────────────────────────────────────

void OBDManager::sendRaw(const char* s) {
    _bt.print(s);
}

String OBDManager::sendCmd(const char* cmd) {
    _bt.printf("%s\r", cmd);
    return readUntilPrompt(CMD_TIMEOUT_MS);
}

String OBDManager::sendCmdTimeout(const char* cmd, int timeoutMs) {
    _bt.printf("%s\r", cmd);
    return readUntilPrompt(timeoutMs);
}

void OBDManager::setHeader(const char* hdr, const char* cra) {
    if (strcmp(hdr, _currentHeader) != 0) {
        char buf[16]; snprintf(buf, sizeof(buf), "ATSH%s", hdr);
        sendCmd(buf);
        strncpy(_currentHeader, hdr, sizeof(_currentHeader) - 1);
    }
    if (strcmp(cra, _currentCRA) != 0) {
        char buf[16]; snprintf(buf, sizeof(buf), "ATCRA%s", cra);
        sendCmd(buf);
        strncpy(_currentCRA, cra, sizeof(_currentCRA) - 1);
    }
}

void OBDManager::setHeaderForce(const char* hdr, const char* cra) {
    char buf[16];
    snprintf(buf, sizeof(buf), "ATSH%s", hdr);
    sendCmd(buf);
    strncpy(_currentHeader, hdr, sizeof(_currentHeader) - 1);
    snprintf(buf, sizeof(buf), "ATCRA%s", cra);
    sendCmd(buf);
    strncpy(_currentCRA, cra, sizeof(_currentCRA) - 1);
}

/** Read bytes until '>' prompt or timeout.  Returns uppercase, stripped of CR/LF. */
String OBDManager::readUntilPrompt(int timeoutMs) {
    String result;
    result.reserve(64);
    unsigned long deadline = millis() + timeoutMs;
    while (millis() < deadline) {
        while (_bt.available()) {
            char ch = (char)_bt.read();
            if (ch == '>') {
                result.toUpperCase();
                result.trim();
                return result;
            }
            if (ch != '\r' && ch != '\n') result += ch;
        }
        vTaskDelay(pdMS_TO_TICKS(2));
    }
    result.toUpperCase();
    result.trim();
    return result;
}

// ── Response helpers ──────────────────────────────────────────────────────────

String OBDManager::strip(const String& r) {
    String s = r;
    s.replace(" ", "");
    return s;
}

bool OBDManager::isError(const String& r) {
    if (r.length() == 0) return true;
    return r.indexOf("NODATA") >= 0 || r.indexOf("NO DATA") >= 0
        || r.indexOf("ERROR")  >= 0 || r.indexOf("UNABLE") >= 0
        || r.indexOf("?")      >= 0 || r.indexOf("STOPPED") >= 0
        || r.indexOf("BUSBUSY") >= 0
        || r.indexOf("7F22") >= 0 || r.indexOf("7F21") >= 0;
}

int OBDManager::byteAt(const String& r, int pos) {
    int idx = pos * 2;
    if (idx + 2 > (int)r.length()) return -1;
    String hex = r.substring(idx, idx + 2);
    char* end;
    long v = strtol(hex.c_str(), &end, 16);
    return (end == hex.c_str()) ? -1 : (int)v;
}

int OBDManager::m22byte(const String& r, int dataByteIndex) {
    String s = strip(r);
    int idx = s.indexOf("62");
    int base = (idx >= 0 && idx % 2 == 0) ? idx / 2 : 0;
    return byteAt(s, base + 3 + dataByteIndex);
}

int OBDManager::m22word(const String& r) {
    int a = m22byte(r, 0), b = m22byte(r, 1);
    if (a < 0 || b < 0) return -1;
    return a * 256 + b;
}

// ── Mode 01 parsers ───────────────────────────────────────────────────────────

void OBDManager::parseRPM(const String& r) {
    String s = strip(r);
    if (isError(s) || s.length() < 8) return;
    int a = byteAt(s, 2), b = byteAt(s, 3);
    if (a < 0 || b < 0) return;
    float raw = (a * 256.0f + b) / 4.0f;
    if (raw > 8000.0f) return;  // reject garbled frames (FA20DIT redline ~7000 RPM)
    float prev = _data.rpm;
    unsigned long now = millis();
    // EMA α=0.25: ~0.2s time constant at 20 Hz — smooths noise without perceptible lag
    float next = isnan(prev) ? raw : prev * 0.75f + raw * 0.25f;
    if (!isnan(prev) && _data.rpmLastMs > 0 && now > _data.rpmLastMs) {
        _data.rpmVelPerMs = (next - prev) / (float)(now - _data.rpmLastMs);
    }
    _data.rpm = next;
    _data.rpmLastMs = now;
}

void OBDManager::parseSpeed(const String& r) {
    String s = strip(r);
    if (isError(s) || s.length() < 6) return;
    int a = byteAt(s, 2); if (a < 0) return;
    float raw = (float)a;
    float prev = _data.speedKph;
    unsigned long now = millis();
    // Snap to zero immediately — OBD speed is 1 kph quantized so there is no noise
    // at 0 kph; EMA would otherwise take 2–3 s to converge to 0 after a full stop.
    // For non-zero values use EMA α=0.4 to smooth 1 kph quantization steps.
    float next;
    if (raw == 0.0f) {
        next = 0.0f;
    } else {
        next = isnan(prev) ? raw : prev * 0.6f + raw * 0.4f;
    }
    if (!isnan(prev) && _data.speedLastMs > 0 && now > _data.speedLastMs) {
        _data.speedVelPerMs = (next - prev) / (float)(now - _data.speedLastMs);
    }
    _data.speedKph = next;
    _data.speedLastMs = now;
}

void OBDManager::parsePedal(const String& r) {
    String s = strip(r);
    if (isError(s) || s.length() < 6) return;
    int a = byteAt(s, 2); if (a < 0) return;
    _data.pedalPct = a / 255.0f * 100.0f;
}

void OBDManager::parseLoad(const String& r) {
    String s = strip(r);
    if (isError(s) || s.length() < 6) return;
    int a = byteAt(s, 2); if (a < 0) return;
    _data.loadPct = a / 255.0f * 100.0f;
}

void OBDManager::parseCoolant(const String& r) {
    String s = strip(r);
    if (isError(s) || s.length() < 6) return;
    int a = byteAt(s, 2); if (a < 0) return;
    _data.coolantC = a - 40.0f;
}

void OBDManager::parseTiming(const String& r) {
    String s = strip(r);
    if (isError(s) || s.length() < 6) return;
    int a = byteAt(s, 2); if (a < 0) return;
    _data.timingDeg = a / 2.0f - 64.0f;
}

void OBDManager::parseMAF(const String& r) {
    String s = strip(r);
    if (isError(s) || s.length() < 8) return;
    int a = byteAt(s, 2), b = byteAt(s, 3);
    if (a < 0 || b < 0) return;
    _data.mafGs = (a * 256.0f + b) / 100.0f;
}

void OBDManager::parseMAP(const String& r) {
    String s = strip(r);
    if (isError(s) || s.length() < 6) return;
    int a = byteAt(s, 2); if (a < 0) return;
    float raw = (float)a;  // kPa absolute
    float prev = _data.mapKpa;
    unsigned long now = millis();
    // EMA α=0.3: ~0.17s time constant at 20 Hz — smooth boost without hiding spool/blowoff
    float next = isnan(prev) ? raw : prev * 0.7f + raw * 0.3f;
    if (!isnan(prev) && _data.mapLastMs > 0 && now > _data.mapLastMs) {
        _data.mapVelPerMs = (next - prev) / (float)(now - _data.mapLastMs);
    }
    _data.mapKpa = next;
    _data.mapLastMs = now;
}

void OBDManager::parseBaro(const String& r) {
    String s = strip(r);
    if (isError(s) || s.length() < 6) return;
    int a = byteAt(s, 2); if (a < 0) return;
    if (a > 85 && a < 108) _data.baroKpa = (float)a;
}

void OBDManager::parseSTFT(const String& r) {
    String s = strip(r);
    if (isError(s) || s.length() < 6) return;
    int a = byteAt(s, 2); if (a < 0) return;
    _data.stftPct = (a - 128.0f) / 1.28f;
}

void OBDManager::parseLTFT(const String& r) {
    String s = strip(r);
    if (isError(s) || s.length() < 6) return;
    int a = byteAt(s, 2); if (a < 0) return;
    _data.ltftPct = (a - 128.0f) / 1.28f;
}

void OBDManager::parseCatTemp(const String& r) {
    String s = strip(r);
    if (isError(s) || s.length() < 8) return;
    int a = byteAt(s, 2), b = byteAt(s, 3);
    if (a < 0 || b < 0) return;
    float v = (a * 256.0f + b) / 10.0f - 40.0f;
    if (v > -41.0f && v < 2000.0f) _data.catTempC = v;
}

void OBDManager::parseOilTemp(const String& r) {
    String s = strip(r);
    if (isError(s) || s.length() < 6) return;
    int a = byteAt(s, 2); if (a < 0) return;
    float v = a - 40.0f;
    if (v > -41.0f && v < 200.0f) _data.oilTempC = v;
}

void OBDManager::parseFuelLevel(const String& r) {
    String s = strip(r);
    if (isError(s) || s.length() < 6) return;
    int a = byteAt(s, 2); if (a < 0) return;
    float raw = a / 2.55f;
    // EMA α=0.05: smooths ADC noise at ~7 Hz poll rate (time constant ~3s)
    _data.fuelLevelPct = isnan(_data.fuelLevelPct) ? raw : _data.fuelLevelPct * 0.95f + raw * 0.05f;
}

void OBDManager::parseBattery(const String& r) {
    // ATRV returns e.g. "12.6V"
    String s = r;
    s.replace("V", "");
    s.replace("v", "");
    s.trim();
    float v = s.toFloat();
    if (v > 6.0f && v < 20.0f) _data.battV = v;
}

// ── Mode 22 parsers ───────────────────────────────────────────────────────────

void OBDManager::parseWastegate(const String& r) {
    if (isError(r)) return;
    int a = m22byte(r, 0); if (a < 0) return;
    _data.wastegatePct = a / 2.55f;
}

void OBDManager::parseFineKnock(const String& r) {
    if (isError(r)) return;
    int a = m22byte(r, 0); if (a < 0) return;
    _data.fineKnockDeg = a / 4.0f - 32.0f;
}

void OBDManager::parseVvtAngleL(const String& r) {
    // 2210B9 — VVT intake-left cam angle; signed byte / 2 → degrees
    if (isError(r)) return;
    int a = m22byte(r, 0); if (a < 0) return;
    _data.vvtAngleL = (float)(int8_t)a / 2.0f;
}

void OBDManager::parseCVTTemp(const String& r) {
    // 2210D2 on TCU (7E1/7E9) — confirmed dynamic across three drive sessions.
    // Formula: byte - 50 → °C.  Range check: 0x63=49°C, 0x7D=75°C, 0x81=79°C all verified.
    if (isError(r)) return;
    int a = m22byte(r, 0); if (a < 0) return;
    float v = a - 50.0f;
    if (v > -51.0f && v < 200.0f) _data.cvtTempC = v;
}

void OBDManager::parseDAM(const String& r) {
    // 2210B1 — dynamic advance multiplier.
    // FA20DIT encodes as 0–16+ counts; 16 = 1.0 (full advance). Values can exceed 1.0.
    // Observed: 0x00–0x1E (30) across sessions; highway cruise often 0x1E = 1.875.
    if (isError(r)) return;
    int a = m22byte(r, 0); if (a < 0) return;
    _data.damRatio = a / 16.0f;
}

void OBDManager::parseRoughness(const String& r, int cyl) {
    if (isError(r)) return;
    int a = m22byte(r, 0); if (a < 0) return;
    switch (cyl) {
        case 1: _data.rough1 = (float)a; break;
        case 2: _data.rough2 = (float)a; break;
        case 3: _data.rough3 = (float)a; break;
        case 4: _data.rough4 = (float)a; break;
    }
}

void OBDManager::parseInjDutyCycle(const String& r) {
    // 2210C1 — injection duty cycle; raw byte / 2 → %
    if (isError(r)) return;
    int a = m22byte(r, 0); if (a < 0) return;
    _data.injDutyPct = a / 2.0f;
}

void OBDManager::parseInjPulse(const String& r) {
    // 2210B4 — injection pulse width; raw word / 1000 → ms (unverified scale)
    if (isError(r)) return;
    int w = m22word(r); if (w < 0) return;
    _data.injPulseMs = w / 1000.0f;
}

void OBDManager::parseShiftSelector(const String& r93, const String& r95) {
    // Gear encoding confirmed from sfe_20260319_083507.csv (22-min highway drive):
    //   D: 093=0x04, 095=0x20  |  P: 093=0x00, 095=0x00
    //   N: 093=0x00, 095=0x20  |  R: 093=0x06, 095=0x21
    // ELM clone guard: reject frames lacking the expected PID echo (621093/621095).
    // Cheap clones sometimes return a stale 2210D2 response in the 221093 buffer;
    // e.g. "6210D286" instead of "62109304" — that byte has bit1 set → wrongly decoded R.
    String s93 = strip(r93);
    String s95 = strip(r95);
    bool ok93 = !isError(r93) && s93.indexOf("621093") >= 0;
    bool ok95 = !isError(r95) && s95.indexOf("621095") >= 0;
    if (!ok93 || !ok95) return;  // keep last known shiftPos
    int a93 = m22byte(r93, 0);
    int a95 = m22byte(r95, 0);
    if (a93 < 0 || a95 < 0) return;
    if      ((a93 & 0x02) != 0) snprintf((char*)_data.shiftPos, sizeof(_data.shiftPos), "R");
    else if ((a93 & 0x04) != 0) snprintf((char*)_data.shiftPos, sizeof(_data.shiftPos), "D");
    else if ((a95 & 0x20) != 0) snprintf((char*)_data.shiftPos, sizeof(_data.shiftPos), "N");
    else                         snprintf((char*)_data.shiftPos, sizeof(_data.shiftPos), "P");
}

// ── Utility ─────────────────────────────────────────────────────────────────

void OBDManager::sleepMs(uint32_t ms) {
    vTaskDelay(pdMS_TO_TICKS(ms));
}
