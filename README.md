# SFE OBD Dashboard — Android APK

## Build Instructions (5 minutes)

### Requirements
- Android Studio Hedgehog (2023.1) or newer — free at https://developer.android.com/studio
- Android SDK installed (Android Studio installs it automatically on first launch)
- Your phone must have the Veepeak OBDII adapter already PAIRED in Android Settings → Bluetooth
  - Device name: OBDII
  - PIN: 1234

### Steps
1. Open Android Studio → "Open an Existing Project" → select this folder (SFEDash/)
2. Let Gradle sync (bottom bar — takes ~1 min first time, downloads dependencies)
3. Plug in your phone via USB with Developer Mode + USB Debugging enabled
   OR just build the APK file:
   Build → Build Bundle(s) / APK(s) → Build APK(s)
4. APK appears at:  app/build/outputs/apk/debug/app-debug.apk
5. Sideload to phone:  adb install app/build/outputs/apk/debug/app-debug.apk

### Phone Setup (one-time)
1. Settings → Bluetooth → Pair new device → "OBDII" → PIN 1234
2. Settings → Developer Options → Enable USB Debugging
3. Settings → Install Unknown Apps (if sideloading without Android Studio)

### Controls
| Button      | Tap              | Long Press (0.6s) | Double Tap  |
|-------------|------------------|-------------------|-------------|
| LEFT ◀      | Previous page    | Cycle theme       | Auto-scroll |
| RIGHT ▶     | Next page        | Drive mode        | —           |
| RIGHT ▶     | Dismiss alert    | Exit drive mode   | —           |

### Pages (11 total)
1. ENGINE — RPM arc + load/throttle/timing cards
2. TEMPERATURES — coolant / oil / CVT / cat
3. BOOST — boost PSI arc + MAP / MAF / target MAP
4. CVT — CVT temp / lock-up duty / belt slip / AWD transfer
5. ROUGHNESS — FA20DIT boxer diagram + cyl 1-4 roughness
6. FUEL SYSTEM — STFT / LTFT / fuel pump / alternator
7. G-FORCE — longitudinal G dot plot
8. TIMING — ignition advance / knock correction
9. IGNITION — battery / baro / battery temp
10. AVCS — intake & exhaust OCV duty (4 channels)
11. SESSION — peak values this session + knock event count

### Bluetooth Flow
- App auto-searches paired devices for "OBDII" on launch
- Status shown in top-left of status bar:  SEARCHING → CONNECTING → INIT ELM327 → CONNECTED
- If connection drops, auto-reconnects every 5 seconds
- **You do NOT need to do anything** — just make sure the adapter is plugged in and paired

### Mode 22 PIDs
All Subaru FA20DIT-specific PIDs require the ECU/TCU to be awake (ignition ON or engine running).
The adapter sends AT SH 7E0 (ECU) and AT SH 7E1 (TCU) headers automatically.

### Notes on PID Scaling
The Mode 22 PID formulas in OBDManager.java are best-guess standard Subaru scalings.
Some may need adjustment after you verify against Car Scanner values on your specific car.
Key ones to verify:
- 22 10 AF (oil temp): formula is  (A * 0.5) - 40
- 22 30 18 (knock corr): formula is (A - 128) * 0.5
- 22 30 DA (actual gear ratio): formula is (word) / 1000

## File Structure
```
SFEDash/
├── app/src/main/java/com/sfe/dashboard/
│   ├── MainActivity.java    — UI, buttons, permissions
│   ├── DashView.java        — All rendering (SurfaceView)
│   ├── OBDManager.java      — BT connect + ELM327 polling
│   └── DashData.java        — Shared volatile data store
├── app/src/main/
│   ├── AndroidManifest.xml
│   └── assets/fonts/        — Drop Orbitron-Bold.ttf + ShareTechMono-Regular.ttf here
│                              (optional: app falls back to system monospace if missing)
└── README.md
```

## Optional: Better Fonts
Download free from Google Fonts:
- https://fonts.google.com/specimen/Orbitron  → Orbitron-Bold.ttf
- https://fonts.google.com/specimen/Share+Tech+Mono → ShareTechMono-Regular.ttf

Place both files in:  app/src/main/assets/fonts/

Without them the app uses the system monospace font — still readable, just less styled.
