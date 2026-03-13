# SFEDash — Claude Code Context

## Project Summary
Android OBD-II dashboard app for a **2015+ Subaru WRX (FA20DIT engine, CVT)**.
Connects via Bluetooth Classic SPP to a **Veepeak OBDII adapter** (device name: "OBDII", PIN: 1234).
Uses ELM327 AT commands + Subaru Mode 22 (UDS service 0x22) PIDs for manufacturer-specific data.

## Key Files (read these before touching anything)
| File | Role |
|------|------|
| `OBDManager.java` | BT connect, ELM327 poll loop, all PID sends & parsers (~1000 lines) |
| `DashView.java` | SurfaceView rendering, all 11 pages, page definitions (~1560 lines) |
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
| 2 | ~3–5 Hz | 7E0/7E8 | Knock corr, wastegate, IAT, target boost, fine knock |
| 3a | ~1 Hz | 7E0/7E8 | CVT temp, target MAP, batt temp; roughness (page 4 only); OCV (page 9 only); turbo detail (page 2 only); fuel stats (page 5 only) |
| 3b | ~1 Hz | 7E1/7E9 | TCU: lockup, transfer, turbine RPM, primary/secondary RPM, gear ratios, TC slip (CVT page 3 only) |
| 3c | ~1 Hz | 7E0/7E8 | Injector, AFR, HPFP, CPC, OSV, fuel tank press, DAM |

**Header switching is expensive** — minimize `setHeader()` calls in the hot path.

## Confirmed Mode 22 PIDs (FA20DIT WRX, ECU 7E0)
Source: ScanGauge official XGauge page for Subaru Impreza WRX + Outback CVT.

| PID | Parameter | Formula | Notes |
|-----|-----------|---------|-------|
| 223018 | Feedback Knock Correction (°) | `byte / 4 - 32` | ScanGauge KRC confirmed |
| 2210AF | **Engine Oil Temp** (°F) | `byte * 9/5 - 40` | ScanGauge EOT — **NOT knock corr** |
| 2210B0 | Fine Knock Learning (°) | `byte / 4 - 32` | No public ScanGauge code; unverified |
| 2210B1 | DAM — Dynamic Advance Multiplier | `byte / 255` (TODO verify) | No public ScanGauge code; unverified |
| 223062 | Roughness Cyl 1 | raw byte | ScanGauge RM1 confirmed |
| 223048 | Roughness Cyl 2 | raw byte | ScanGauge RM2 confirmed |
| 223068 | Roughness Cyl 3 | raw byte | ScanGauge RM3 confirmed |
| 22304A | Roughness Cyl 4 | raw byte | ScanGauge RM4 confirmed |
| 2210A6 | Boost pressure (psi) | TODO verify | Direct boost sensor |
| 221021 | CVT fluid temp (°C) | `byte - 40` | On ECM (7E0), confirmed via terminal |

## Confirmed Mode 22 PIDs (TCU 7E1)
Source: ScanGauge Outback CVT XGauge page.

| PID | Parameter | Formula | Notes |
|-----|-----------|---------|-------|
| 22300E | Primary pulley speed (RPM) | raw word | ScanGauge confirmed (was spec 221151) |
| 2230D0 | Secondary pulley speed (RPM) | raw word | ScanGauge confirmed (was spec 221152) |
| 2230DA | CVT ratio actual | `word / 1000` (TODO verify) | ScanGauge confirmed PID; formula unverified |
| 2230F8 | CVT ratio target | `word * 100 / 255` | |
| 221045 | Lockup duty (%) | `byte / 2` | ScanGauge confirmed |
| 221065 | Transfer duty (%) | `byte / 2` | ScanGauge confirmed |
| 221067 | Turbine RPM | `byte * 32` | ScanGauge confirmed |
| 221153 | Torque converter slip (RPM) | raw word (TODO verify) | Spec §9; formula unverified |

## Known Wrong PIDs (do not use)
- `2210AF` for knock correction — it's engine oil temperature.
- `221151/221152/221150` for CVT shaft speeds/ratio — these are spec PIDs that don't respond on this TCU; use `22300E/2230D0/2230DA`.
- `221017` for CVT temp from TCU — returns 7F2231 (error) on this vehicle; use `221021` on ECM.

## Pages (DashView.java — `PAGES[]` array, index 0–10)
```
0  ENGINE     arc      RPM (hero), load, throttle, timing
1  TEMPS      arc      coolant, oil, CVT temp, cat
2  BOOST      arc      boost psi (hero), turbo speed, wastegate, target boost
3  CVT        arc      gear ratio act (hero), CVT temp, lockup%, slip%
4  ROUGHNESS  cylinder rough1–4 + FA20DIT boxer diagram
5  FUEL       arc      AFR lambda, inj duty, STFT, LTFT
6  G-FORCE    gforce   longitudinal G dot plot
7  TIMING     arc      ignition advance, knock corr, fine knock, DAM
8  IGNITION   arc      battery V, baro, battery temp
9  CAM/VVT    arc      OCV intake L/R, OCV exh L/R
10 SESSION    session  peak values + knock event count
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
