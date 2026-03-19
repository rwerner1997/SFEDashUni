#pragma once
#include <Arduino.h>
#include <BluetoothSerial.h>
#include "DashData.h"

// ─────────────────────────────────────────────────────────────────────────────
// OBDManager — Bluetooth Classic SPP master → Veepeak OBDII (ELM327)
//
// Mirrors OBDManager.java: same three polling tiers, same Mode 22 PIDs,
// same header-caching strategy to minimise ATSH/ATCRA round-trips.
//
// Tier 1 (~20 Hz): RPM (010C), MAP (010B)
// Tier 2 (~7 Hz):  Speed, Timing, MAF, Pedal, Load, STFT, LTFT,
//                  Wastegate (2210A8), Fine Knock (2210B0), VVT-L (2210B9)
// Tier 3a (~1 Hz): Coolant, Oil, CAT, Baro, Fuel, Battery,
//                  CVT Temp (2210D2 @ TCU 7E1, byte-50),
//                  Shift selector (221093+221095 @ TCU 7E1), Roughness*
// Tier 3d (~1 Hz): DAM (2210B1, /16), Inj duty (2210C1), Inj pulse (2210B4)
// *Roughness polled only when page 4 (ENGINE VITALS) or page 5 (ROUGHNESS) active.
//
// Runs on a dedicated FreeRTOS task (core 1, 8KB stack).
// ─────────────────────────────────────────────────────────────────────────────

class OBDManager {
public:
    explicit OBDManager(DashData& data);

    // Call from setup() — spawns the FreeRTOS task and returns immediately.
    void start();
    void stop();

private:
    DashData&      _data;
    BluetoothSerial _bt;
    volatile bool  _running = false;

    // Header cache — reset on each reconnect so AT commands are re-sent after ATZ.
    char _currentHeader[8] = "";
    char _currentCRA[8]    = "";

    // ── Task entry ───────────────────────────────────────────────────────────
    static void taskEntry(void* pv);
    void connectionLoop();
    void initELM327();
    void pollLoop();

    // ── I/O ──────────────────────────────────────────────────────────────────
    String sendCmd(const char* cmd);
    String sendCmdTimeout(const char* cmd, int timeoutMs);
    void   setHeader(const char* hdr, const char* cra);
    void   setHeaderForce(const char* hdr, const char* cra);
    String readUntilPrompt(int timeoutMs);
    void   sendRaw(const char* s);

    // ── Response helpers ─────────────────────────────────────────────────────
    bool   isError(const String& r);
    String strip(const String& r);
    int    byteAt(const String& r, int pos);
    int    m22byte(const String& r, int dataByteIndex);
    int    m22word(const String& r);

    // ── Mode 01 parsers ───────────────────────────────────────────────────────
    void parseRPM(const String& r);
    void parseSpeed(const String& r);
    void parseLoad(const String& r);
    void parseCoolant(const String& r);
    void parseTiming(const String& r);
    void parseMAF(const String& r);
    void parseMAP(const String& r);
    void parseBaro(const String& r);
    void parseSTFT(const String& r);
    void parseLTFT(const String& r);
    void parseCatTemp(const String& r);
    void parsePedal(const String& r);
    void parseOilTemp(const String& r);
    void parseFuelLevel(const String& r);
    void parseBattery(const String& r);

    // ── Mode 22 parsers ───────────────────────────────────────────────────────
    void parseWastegate(const String& r);
    void parseFineKnock(const String& r);
    void parseVvtAngleL(const String& r);
    void parseCVTTemp(const String& r);
    void parseDAM(const String& r);
    void parseRoughness(const String& r, int cyl);
    void parseShiftSelector(const String& r93, const String& r95);
    void parseInjDutyCycle(const String& r);
    void parseInjPulse(const String& r);

    void sleepMs(uint32_t ms);

    static constexpr int CMD_TIMEOUT_MS    = 250;
    static constexpr int CMD_TIMEOUT_SLOW  = 500;
    static constexpr int CMD_TIMEOUT_ROUGH = 600;
    static constexpr int CONNECT_RETRY_MS  = 5000;
};
