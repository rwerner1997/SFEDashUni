#include <Arduino.h>
#include <IRrecv.h>
#include <IRutils.h>
#include "DashData.h"
#include "OBDManager.h"
#include "DashView.h"

// ─────────────────────────────────────────────────────────────────────────────
// SFEDash ESP32 — 2015+ Subaru WRX FA20DIT / CVT
//
// Core 0: render task (DashView, ~30 Hz)
// Core 1: OBD poll task (OBDManager, ~20 Hz burst)
//
// IR remote: HX1838 NEC-protocol receiver on GPIO 34 (input-only pin)
//
// Default NEC codes below are for the common HX1838 D-pad kit remote.
// If buttons don't respond, add -DSERIAL_IR_DEBUG to build_flags and open
// Serial Monitor at 115200 — each keypress will print its hex code.
// Update the matching #define with the value printed.
// ─────────────────────────────────────────────────────────────────────────────

// ── IR button codes (NEC protocol, 32-bit) ────────────────────────────────────
// Common HX1838 D-pad remote defaults. Update if your remote differs.
#define IR_UP     0xFF18E7UL
#define IR_DOWN   0xFF4AB5UL
#define IR_LEFT   0xFF10EFUL
#define IR_RIGHT  0xFF5AA5UL
#define IR_OK     0xFF38C7UL
#define IR_1      0xFF30CFUL
#define IR_2      0xFF18E7UL   // NOTE: some remotes share codes; re-calibrate if needed
#define IR_3      0xFF7A85UL
#define IR_4      0xFF10EFUL
#define IR_5      0xFF38C7UL
#define IR_6      0xFF5AA5UL
#define IR_7      0xFF42BDUL
#define IR_8      0xFF4AB5UL
#define IR_9      0xFF52ADUL
#define IR_STAR   0xFF6897UL
#define IR_HASH   0xFFB04FUL

// ─────────────────────────────────────────────────────────────────────────────

DashData    g_data;
OBDManager  g_obd(g_data);
DashView    g_view(g_data);

IRrecv      g_ir(IR_RECV_PIN);
decode_results g_irResult;

// ── IR handler ────────────────────────────────────────────────────────────────

static void handleIR(uint64_t code) {
#ifdef SERIAL_IR_DEBUG
    Serial.printf("[IR] received: 0x%08llX\n", code);
#endif

    if (code == REPEAT) return;  // ignore held-key repeat (except paging, handled below)

    if      (code == IR_LEFT)  g_view.prevPage();
    else if (code == IR_RIGHT) g_view.nextPage();
    else if (code == IR_UP)    g_view.prevTheme();
    else if (code == IR_DOWN)  g_view.nextTheme();
    else if (code == IR_OK)    g_view.toggleDriveMode();
    else if (code == IR_1)     { g_data.activePage = 0; }
    else if (code == IR_2)     { g_data.activePage = 1; }
    else if (code == IR_3)     { g_data.activePage = 2; }
    else if (code == IR_4)     { g_data.activePage = 3; }
    else if (code == IR_5)     { g_data.activePage = 4; }
    else if (code == IR_6)     { g_data.activePage = 5; }
    else if (code == IR_7)     { g_data.activePage = 6; }
    else if (code == IR_8)     { g_data.activePage = 7; }
    else if (code == IR_9)     g_data.resetPeaks();
    else if (code == IR_STAR)  g_view.dismissAlert();
    else if (code == IR_HASH)  g_view.muteAlerts();
}

// ── Render task (core 0) ──────────────────────────────────────────────────────

static void renderTask(void*) {
    g_view.begin();

    for (;;) {
        // Poll IR receiver (non-blocking)
        if (g_ir.decode(&g_irResult)) {
            if (g_irResult.decode_type == NEC || g_irResult.decode_type == NEC_LIKE) {
                handleIR(g_irResult.value);
            }
            g_ir.resume();
        }

        g_view.draw();
        vTaskDelay(pdMS_TO_TICKS(33));   // ~30 fps
    }
}

// ── Arduino setup / loop ──────────────────────────────────────────────────────

void setup() {
    Serial.begin(115200);
    Serial.println("[SFEDash] Starting...");
    Serial.printf("[SFEDash] IR receiver on GPIO %d\n", IR_RECV_PIN);

    // Backlight on
    pinMode(TFT_BL, OUTPUT);
    digitalWrite(TFT_BL, TFT_BACKLIGHT_ON);

    // Start IR receiver (50 ms timeout between bursts, no LED blink)
    g_ir.enableIRIn();

    // Render task on core 0 (priority 1, 8KB stack — increased for engine diagram)
    xTaskCreatePinnedToCore(renderTask, "Render", 8192, nullptr, 1, nullptr, 0);

    // OBD task launches internally on core 1
    g_obd.start();
}

void loop() {
    // All work is done in tasks; loop() just yields.
    delay(100);
}
