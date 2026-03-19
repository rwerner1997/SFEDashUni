# SFEDash — Claude Code Context

## ⚠ ESP32 Fork — FROZEN
**Do not modify anything under `esp32/` until the Android (Java) version is stable.**
The C++ port in `esp32/` is a snapshot taken from the Android codebase at fork time.
All PID fixes, formula corrections, and logic changes must be made in the Android source
first, then ported to the ESP32 fork in a dedicated pass.

## Project Summary
Android OBD-II dashboard app for a **2015+ Subaru WRX (FA20DIT engine, CVT)**.
Connects via Bluetooth Classic SPP to a **Veepeak OBDII adapter** (device name: "OBDII", PIN: 1234).
Uses ELM327 AT commands + Subaru Mode 22 (UDS service 0x22) PIDs for manufacturer-specific data.

## Key Files (read these before touching anything)
| File | Role |
|------|------|
| `OBDManager.java` | BT connect, ELM327 poll loop, all PID sends & parsers (~1000 lines) |
| `DashView.java` | SurfaceView rendering, all 7 pages, page definitions (~1560 lines) |
| `DashData.java` | Shared volatile data store — all fields, derived getters (~170 lines) |
| `MainActivity.java` | Permissions, button handling, lifecycle (~240 lines) |

## Architecture
- **Single background thread** in OBDManager polls OBD in a loop.
- **DashData** is the shared state: all fields `volatile`, written by OBDManager, read by DashView.
- **DashView** is a SurfaceView with a dedicated render thread; reads DashData directly.
- No ViewModel, no LiveData, no Jetpack — intentionally minimal.

## OBD Polling Tiers (OBDManager.java)
| Tier | Freq | Header | PIDs |
|------|------|--------|------|
| 1 (burst) | ~10–20 Hz | 7E0/7E8 | RPM, speed, MAP, throttle, MAF, timing, coolant, boost |
| 2 | ~3–5 Hz | 7E0/7E8 | Wastegate, IAT, fine knock (knock corr removed — 223018 not supported) |
| 3a | ~1 Hz | 7E0/7E8 | Target MAP, batt temp; roughness (page 3 only). CVT temp = NaN (correct PID unknown) |
| 3d | ~1 Hz | 7E0/7E8 | DAM |

**Header switching is expensive** — minimize `setHeader()` calls in the hot path.

## Confirmed Mode 22 PIDs (FA20DIT WRX, ECU 7E0)
Source: ScanGauge official XGauge page for Subaru Impreza WRX + Outback CVT.

| PID | Parameter | Formula | Notes |
|-----|-----------|---------|-------|
| 223018 | Feedback Knock Correction (°) | `byte / 4 - 32` | **Returns 7F2231 (requestOutOfRange) on every poll on this car — PID not supported.** `knockCorr` always NaN; knock alert will never fire from this source. |
| 2210AF | **Engine Oil Temp** (°F) | `byte * 9/5 - 40` | ScanGauge EOT — **NOT knock corr** |
| 2210B0 | Fine Knock Learning (°) | `byte / 4 - 32` | Works on this ECU; unverified formula. Log shows values ~0x28(-22°) at idle/light load and 0x9B-0x9D(+6.75–7.25°) occasionally. Possibly correct. |
| 2210B1 | DAM — Dynamic Advance Multiplier | `byte / 16` | FA20DIT encodes DAM as 0–30+ counts; 16 = 1.0 (full advance). Values **can exceed 1.0** — highway drive observed up to 0x1E (30) = 1.875 (ECU adding extra advance above base map). Observed range: 0x00–0x1E across sessions. Prior formula was /255 — corrected. |
| 223062 | Roughness Cyl 1 | raw byte | ScanGauge RM1 confirmed |
| 223048 | Roughness Cyl 2 | raw byte | ScanGauge RM2 confirmed |
| 223068 | Roughness Cyl 3 | raw byte | ScanGauge RM3 confirmed |
| 22304A | Roughness Cyl 4 | raw byte | ScanGauge RM4 confirmed |
| 2210A6 | Boost pressure (psi) | TODO verify | Direct boost sensor |
| 221021 | CVT fluid temp (°C) | `byte - 40` | **⚠ RETURNS STATIC DATA.** Log confirmed response is always `62102137EFE4CD` (4 data bytes, byte[0]=0x37 forever) — value never changes regardless of engine state. This PID does NOT return dynamic CVT fluid temp on this ECU. |
| 22101F | IAT | `byte - 40` | **Returns 7F2231 on every poll on this car — PID not supported.** `iatC` always NaN. |

## Confirmed Mode 22 PIDs (TCU 7E1)
Source: ScanGauge Outback CVT XGauge page + PID scan.

| PID | Parameter | Formula | Notes |
|-----|-----------|---------|-------|
| 22300E | Primary pulley speed (RPM) | raw word | ScanGauge confirmed. **Did NOT appear in PID scan** (returned NR_31) — contradicts ScanGauge. May need specific conditions. |
| 2230D0 | Secondary pulley speed (RPM) | raw word | ScanGauge confirmed. Also absent from PID scan. |
| 2230DA | CVT ratio actual | `word / 1000` (TODO verify) | ScanGauge confirmed PID; also absent from PID scan. |
| 221045 | Lockup duty (%) | `byte / 2` | ScanGauge confirmed. Absent from PID scan (NR_31). |
| 221065 | Transfer duty (%) | `byte / 2` | ScanGauge confirmed. |
| 221067 | Turbine RPM | `byte * 32` | ScanGauge confirmed. |

## PID Scan Findings (TCU responding PIDs — March 2026)

Sources: pid_scan_20260315_160213.csv (mid-drive), pid_scan_20260316_083419.csv (CarScanner ref: 110–120°F), pid_scan_20260316_084742.csv (CarScanner ref: 167–170°F).

| PID | scan_20260315 | scan_083419 | scan_084742 | Notes |
|-----|--------------|-------------|-------------|-------|
| 22104F | 73 | 73 | 73 | **STATIC** — 0x73 every session. Not a live sensor. |
| 22104E | — | 5A | 5A | **STATIC** — 0x5A both new scans. Not a live sensor. |
| 221091 | C0 | C0 | C0 | **STATIC** — unknown, too hot for CVT fluid. |
| 221094 | — | 94 | 94 | **STATIC** — 0x94 both new scans. Not a live sensor. |
| 2210C9 | 42 | 3C | 49 | **NOT CVT TEMP** — swings wildly with braking/accel. Unknown sensor (pressure? clutch duty?). |
| 2210D2 | 81 | 63 | 7D | **CONFIRMED CVT FLUID TEMP** — formula `byte - 50` °C: 0x63→49°C(120°F) ✓, 0x7D→75°C(167°F) ✓, 0x81→79°C(174°F) ✓ across all three reference points. |
| 221138 | 0C86 | 10CE | 0B0B | word; changes with drive state — possible shaft speed (RPM?) |
| 221139 | 060F | 06C5 | 0603 | word; possible shaft speed |
| 22113A | 05DC | 076A | 06C6 | word; possible shaft speed |
| 221152 | 0EC7 | 0EC9 | 0EC9 | word = ~3785; nearly constant — note 22300E/2230D0 did NOT respond |
| **221093** | 00 | 04 | 04 | **SHIFT SELECTOR (primary)** — used with 221095 for PRNDL decode. See encoding table below. |
| **221095** | 00 | 20 | 20 | **SHIFT SELECTOR (secondary)** — distinguishes N from D (bit 5). See encoding table below. |

### Confirmed PRNDL Encoding (corrected — sfe_20260319_083507.csv, 22-min highway drive)
| Gear | 221093 | 221095 | Key bits |
|------|--------|--------|----------|
| R    | 0x06   | 0x21   | 093 bit1+bit2 set; 095 bit0+bit5 set |
| D    | 0x04   | 0x20   | 093 bit2 set (not bit1); 095 bit5 set — **entire 22-min highway drive** |
| N    | 0x00   | 0x20   | 093 = 0; 095 bit5 set — brief transition at destination |
| P    | 0x00   | 0x00   | both 0 — **confirmed parked at destination** |
| S    | ?      | ?      | not captured — encoding unknown |

**⚠ CORRECTION (2026-03-19):** The March 18 gear-cycle log (sfe_20260318_205734.csv) had P and D labels **swapped**. The March 19 highway drive (sfe_20260319_083507.csv, CVT 57→84°C over 22 min) conclusively shows 093=0x04/095=0x20 throughout the entire drive = Drive; 093=0x00/095=0x00 when parked at destination = Park.

Decode logic (OBDManager.parseShiftSelector): if `093 bit1` set → R; elif `093 bit2` set → D; elif `095 bit5` set → N; else → P. Stored as `DashData.shiftPos` (String "P"/"R"/"N"/"D", null = no reading).

## Known Wrong PIDs (do not use)
- `2210AF` for knock correction — it's engine oil temperature.
- `221151/221150` for CVT shaft speeds — responded in PID scan but returned single-byte 0x52 (82), not a plausible shaft speed word at idle; may not be shaft speeds.
- `221017` for CVT temp from TCU — returns 7F2231 (error) on this vehicle.
- `221021` for CVT temp from ECM — returns a STATIC 4-byte response (`37EFE4CD`) that never changes during driving. Not a live sensor.
- `22104F` for CVT temp from TCU — also STATIC. Returns `0x73` for entire sessions including WOT pulls. Not a live sensor despite appearing plausible at scan time.
- `22104E` for CVT temp from TCU — STATIC. Returns `0x5A` every sample across full drive log. Not a live sensor.
- `221094` for CVT temp from TCU — STATIC. Returns `0x94` every sample across full drive log. Not a live sensor.
- `2210C9` for CVT temp from TCU — **NOT a temp sensor**. Live signal but swings wildly with braking/acceleration — likely pressure, clutch duty, or similar. Do not use for CVT temp.
- `223018` for knock correction — returns 7F2231 (requestOutOfRange) on every poll. **Poll has been REMOVED from OBDManager.** knockCorr stays NaN permanently.
- `22101F` for IAT — returns 7F2231 on every poll. Not supported.
- `221154` for shift selector — returns 7F2231 (requestOutOfRange) on **100% of polls** across full 10-minute drive log `sfe_20260318_131140.csv` (120/120 ERR). **Poll has been REMOVED.** Not supported on this TCU.

## Pages (DashView.java — `PAGES[]` array, index 0–6)
```
0  ENGINE     arc      RPM (hero), load, pedal, timing
1  TEMPS      arc      coolant, oil, CVT temp, cat
2  BOOST      arc      boost psi (hero), wastegate  ← 2-tile layout
3  ROUGHNESS  cylinder rough1–4 + FA20DIT boxer diagram
4  G-FORCE    gforce   longitudinal G dot plot
5  TIMING     arc      ignition advance, knock corr, fine knock, DAM
6  SESSION    session  peak values + knock event count
```

## DashView Rendering
- `buildPages()` defines all pages via `PageDef` objects with `PidDef` arrays.
- `draw(Canvas)` dispatches to `drawArcPage`, `drawCylinderPage`, `drawGforcePage`, `drawSessionPage`.
- Side strips (left/right) always show RPM and speed overlays regardless of page.
- Theme cycling (tap-hold LEFT): `DARK`, `AMBER`, `GREEN`, `CYAN`.
- Drive mode (tap-hold RIGHT): hides status bar, shows minimal overlay.

## ELM327 / Bluetooth Notes
- `setHeader(tx, rx)` sends `AT SH <tx>` + `AT CRA <rx>` — only if changed (cached).
- `setHeaderForce(tx, rx)` always resends (used for TCU because ELM clones drop ATCRA7E9).
- `m22byte(r, 0)` — parse first byte of Mode 22 response.
- `m22word(r)` — parse 2-byte big-endian word from Mode 22 response.
- `isError(r)` — true if response is null, "NO DATA", "ERROR", "?", or empty.
- Response format: `"62 XX XX [data bytes] >"` — `62` = Mode 22 positive response.

## Build
```
./gradlew assembleDebug
# APK → app/build/outputs/apk/debug/app-debug.apk
```
No special SDK setup needed beyond standard Android SDK (minSdk 21, targetSdk 33).
