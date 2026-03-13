package com.sfe.dashboard;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OBDManager — Bluetooth Classic SPP connection to Veepeak OBDII adapter.
 * Runs ELM327 protocol on a background thread, polling all FA20DIT PIDs.
 *
 * Bluetooth Classic SPP UUID (standard)
 * Target device: "OBDII", PIN: 1234 (must be paired in Android Settings first)
 */
public class OBDManager {

    private static final String TAG = "OBDManager";
    private static final UUID  SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final String TARGET_DEVICE_NAME = "OBDII";

    private static final int CMD_TIMEOUT_MS   = 250;  // per-command timeout — BT RTT ~50-100ms, 2.5× headroom
    private static final int CMD_TIMEOUT_SLOW = 350;  // for Mode 22 PIDs — allow one retry cycle
    private static final int CONNECT_RETRY_S  = 5;    // seconds between reconnect attempts

    private final Context    ctx;
    private final DashData   data;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private BluetoothSocket  socket;
    private InputStream      inStream;
    private OutputStream     outStream;
    private Thread           pollThread;

    // CAN header/filter state — reset to "" at start of each pollLoop so AT commands
    // are always re-sent after a reconnect (ATZ wipes ELM327 state).
    private String currentHeader = "";
    private String currentCRA    = "";

    // Poll-rate tracking
    private int  pollCount  = 0;
    private long pollWindow = System.currentTimeMillis();

    public OBDManager(Context ctx, DashData data) {
        this.ctx  = ctx;
        this.data = data;
    }

    // ── Lifecycle ────────────────────────────────────────────────

    public void start() {
        if (running.getAndSet(true)) return;
        pollThread = new Thread(this::connectionLoop, "OBD-Poll");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    public void stop() {
        running.set(false);
        closeSocket();
        if (pollThread != null) pollThread.interrupt();
    }

    // ── Main connection loop ─────────────────────────────────────

    private void connectionLoop() {
        while (running.get()) {
            data.btStatus = "SEARCHING";
            data.connected = false;
            try {
                BluetoothDevice device = findDevice();
                if (device == null) {
                    data.btStatus = "NOT PAIRED";
                    sleep(CONNECT_RETRY_S * 1000L);
                    continue;
                }
                data.btStatus = "CONNECTING";
                connectToDevice(device);
                data.btStatus = "INIT ELM327";
                initELM327();
                data.btStatus = "CONNECTED";
                data.connected = true;
                data.obdProtocol = sendCmd("ATDPN").trim();
                pollLoop();
            } catch (Exception e) {
                Log.w(TAG, "Connection error: " + e.getMessage());
                data.connected = false;
                data.btStatus  = "RECONNECTING";
                closeSocket();
                sleep(CONNECT_RETRY_S * 1000L);
            }
        }
    }

    // ── Device discovery ─────────────────────────────────────────

    private BluetoothDevice findDevice() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) return null;

        // 1. Check already-bonded/paired devices first (instant)
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        for (BluetoothDevice d : bonded) {
            if (TARGET_DEVICE_NAME.equals(d.getName())) {
                Log.i(TAG, "Found in bonded devices: " + d.getAddress());
                return d;
            }
        }

        // 2. Not paired — run a discovery scan (up to 12 seconds)
        // This is how Car Scanner finds it without system pairing.
        Log.i(TAG, "Not in bonded list — starting discovery scan...");
        data.btStatus = "SCANNING";
        final BluetoothDevice[] found = {null};
        final CountDownLatch latch = new CountDownLatch(1);

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                    BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (d != null) {
                        String name = d.getName();
                        Log.d(TAG, "Discovered: " + name + " [" + d.getAddress() + "]");
                        if (TARGET_DEVICE_NAME.equals(name)) {
                            found[0] = d;
                            latch.countDown();
                        }
                    }
                } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                    latch.countDown();
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        ctx.registerReceiver(receiver, filter);

        try {
            adapter.startDiscovery();
            latch.await(12, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            adapter.cancelDiscovery();
            try { ctx.unregisterReceiver(receiver); } catch (Exception ignored) {}
        }

        if (found[0] != null) {
            Log.i(TAG, "Found via scan: " + found[0].getAddress());
        } else {
            Log.w(TAG, "Device not found in scan");
        }
        return found[0];
    }

    // ── Socket connection ─────────────────────────────────────────

    private void connectToDevice(BluetoothDevice device) throws IOException {
        BluetoothAdapter.getDefaultAdapter().cancelDiscovery(); // must cancel before connect

        // Try insecure first — works without system pairing (same as Car Scanner)
        try {
            socket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
            socket.connect();
        } catch (IOException e1) {
            Log.d(TAG, "Insecure connect failed, trying secure: " + e1.getMessage());
            try { socket.close(); } catch (Exception ignored) {}
            try {
                // Secure (paired) socket
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                socket.connect();
            } catch (IOException e2) {
                Log.d(TAG, "Secure connect failed, trying reflection: " + e2.getMessage());
                try { socket.close(); } catch (Exception ignored) {}
                // Last resort — hidden API direct channel 1
                try {
                    socket = (BluetoothSocket) device.getClass()
                            .getMethod("createRfcommSocket", int.class)
                            .invoke(device, 1);
                    socket.connect();
                } catch (Exception e3) {
                    throw new IOException("All connect methods failed", e3);
                }
            }
        }
        inStream  = socket.getInputStream();
        outStream = socket.getOutputStream();
    }

    private void closeSocket() {
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        socket    = null;
        inStream  = null;
        outStream = null;
    }

    // ── ELM327 Initialisation ─────────────────────────────────────

    private void initELM327() throws IOException {
        sleep(500);                 // let adapter settle after connection
        sendRaw("ATZ\r");          // reset
        sleep(1500);                // ATZ takes ~1s
        readUntilPrompt(3000);      // drain response
        sendCmd("ATE0");            // echo off
        sendCmd("ATL0");            // linefeeds off
        sendCmd("ATH0");            // headers off  ← we'll get "62 XX XX [data]" format
        sendCmd("ATS0");            // spaces off   ← response bytes with no spaces
        sendCmd("ATSP0");           // auto-detect protocol
        sendCmd("ATST0A");          // timeout 10 * 4ms = 40ms per try (ELM side); ATAT adjusts down further
        sendCmd("ATAT2");           // adaptive timing mode 2 — most aggressive, minimises dead-wait
        sendCmd("ATAL");            // allow long messages (multi-frame Mode 22 responses)
        sendCmd("ATCRA7E8");        // default receive-address filter: ECU (7E8)
        // Verify comms with a simple Mode 01 ping
        String resp = sendCmd("0100");
        if (resp.contains("UNABLE") || resp.contains("ERROR") || resp.isEmpty()) {
            throw new IOException("ELM327 init failed: " + resp);
        }
        // Capture baro once (current MAP at idle before throttle is baro)
        // We'll update it from the 010B response during the first poll
    }

    // ── Main poll loop ────────────────────────────────────────────

    private void pollLoop() throws IOException {
        long lastHz = System.currentTimeMillis();
        int hzCount = 0;
        int loopCount = 0;
        currentHeader = "";   // force re-send after reconnect (ATZ wipes ELM state)
        currentCRA    = "";

        while (running.get() && data.connected) {
            long t0 = System.currentTimeMillis();

            // ════════════════════════════════════════════════════
            // TIER 1 — FAST: every loop (~10-15Hz) — MODE 01
            // Standard OBD-II PIDs; reliable across all vehicles.
            // ════════════════════════════════════════════════════
            setHeader("7DF", "7E8");
            parseRPM(sendCmd("010C"));
            parseSpeed(sendCmd("010D"));
            parseMAP(sendCmd("010B"));
            parsePedal(sendCmd("0145"));   // Relative Accelerator Pedal Position
            parseCatTemp(sendCmd("013C"));

            // ════════════════════════════════════════════════════
            // TIER 2 — MEDIUM: every 3rd loop (~3-5Hz)
            // Load, fuel trim, oil/cat temps (Mode 01, no Mode 22 equivalent).
            // Knock, wastegate, IAT, target boost, fine knock (Mode 22).
            // ════════════════════════════════════════════════════
            if (loopCount % 3 == 0) {
                // Mode 01 — engine vitals
                setHeader("7DF", "7E8");
                parseLoad(sendCmd("0104"));
                parseCoolant(sendCmd("0105"));
                parseOilTemp(sendCmd("015C"));
                parseTiming(sendCmd("010E"));
                parseMAF(sendCmd("0110"));
                parseSTFT(sendCmd("0106"));
                parseLTFT(sendCmd("0107"));

                // Mode 22 ECU — knock health, turbo, supplemental channels
                setHeader("7E0", "7E8");
                parseThrottleAngle(sendCmdTimeout("221022", CMD_TIMEOUT_SLOW));  // throttle body °
                parseBoostDirect(sendCmdTimeout("2210A6", CMD_TIMEOUT_SLOW));    // direct boost psi
                parseKnockCorr(sendCmdTimeout("223018", CMD_TIMEOUT_SLOW));      // was 2210AF (spec §7) — reverted; 223018 confirmed working
                parseWastegate(sendCmdTimeout("2210A8", CMD_TIMEOUT_SLOW));
                parseIAT(sendCmdTimeout("22101F", CMD_TIMEOUT_SLOW));
                parseTargetBoost(sendCmdTimeout("2210A7", CMD_TIMEOUT_SLOW));
                parseFineKnock(sendCmdTimeout("2210B0", CMD_TIMEOUT_SLOW));
            }

            // ════════════════════════════════════════════════════
            // TIER 3 — SLOW: every 10th loop (~1Hz)
            // Temps, AVCS, TCU, accessories
            // ════════════════════════════════════════════════════
            // ════════════════════════════════════════════════════
            // TIER 3a — ECU slow: every 10th loop (~1Hz)
            // ════════════════════════════════════════════════════
            if (loopCount % 10 == 0) {
                int ap = data.activePage;
                // Switch back to 7DF for Mode 01 PIDs
                setHeader("7DF", "7E8");
                parseBaro(sendCmd("0133"));
                parseFuelLevel(sendCmd("012F"));   // fuel tank level (Mode 01)
                parseBattery(sendCmd("ATRV"));

                setHeader("7E0", "7E8");
                parseCVTTemp(sendCmdTimeout("221021", CMD_TIMEOUT_SLOW));        // ECM, not TCU — confirmed via terminal
                parseTargetMAP(sendCmdTimeout("223050", CMD_TIMEOUT_SLOW));
                parseBattTemp(sendCmdTimeout("22309A", CMD_TIMEOUT_SLOW));
                // Roughness only needed on ROUGHNESS page (4)
                // PIDs confirmed by ScanGauge RM1-RM4 for FA20DIT WRX (firmware 4.22+)
                if (ap == 4) {
                    parseRoughness(sendCmdTimeout("223062", CMD_TIMEOUT_SLOW), 1);
                    parseRoughness(sendCmdTimeout("223048", CMD_TIMEOUT_SLOW), 2);
                    parseRoughness(sendCmdTimeout("223068", CMD_TIMEOUT_SLOW), 3);
                    parseRoughness(sendCmdTimeout("22304A", CMD_TIMEOUT_SLOW), 4);
                }
                // OCV only needed on CAM/VVT page (9)
                if (ap == 9) {
                    parseOcvIntakeL(sendCmdTimeout("2210BB", CMD_TIMEOUT_SLOW));
                    parseOcvIntakeR(sendCmdTimeout("22109B", CMD_TIMEOUT_SLOW));
                    parseOcvExhL(sendCmdTimeout("2210EF", CMD_TIMEOUT_SLOW));
                    parseOcvExhR(sendCmdTimeout("2210CF", CMD_TIMEOUT_SLOW));
                }
                // Turbo detail only needed on BOOST/TURBO page (2)
                if (ap == 2) {
                    parseTurboSpeed(sendCmdTimeout("2210A9", CMD_TIMEOUT_SLOW));
                    parseChargeAirTemp(sendCmdTimeout("2210AA", CMD_TIMEOUT_SLOW));
                }
                // Fuel/alt stats used on FUEL page (5)
                if (ap == 5) {
                    parseAltDuty(sendCmdTimeout("221093", CMD_TIMEOUT_SLOW));
                    parseFuelPump(sendCmdTimeout("2210B3", CMD_TIMEOUT_SLOW));
                }
            }

            // ════════════════════════════════════════════════════
            // TIER 3b — TCU slow: every 10th loop, offset by 5
            // Always poll CVT temp (shown on TEMPS page); gate detail behind CVT page (3)
            // ════════════════════════════════════════════════════
            if (loopCount % 10 == 5 && data.activePage == 3) {
                setHeaderForce("7E1", "7E9");  // force re-send every time: ELM clones may silently drop ATCRA7E9
                {
                    parseLockup(sendCmdTimeout("221045", CMD_TIMEOUT_SLOW));
                    parseTransfer(sendCmdTimeout("221065", CMD_TIMEOUT_SLOW));
                    parseTurbineRpm(sendCmdTimeout("221067", CMD_TIMEOUT_SLOW));
                    parsePrimaryRpm(sendCmdTimeout("22300E", CMD_TIMEOUT_SLOW));     // was 221151 (spec §9) — reverted
                    parseSecondaryRpm(sendCmdTimeout("2230D0", CMD_TIMEOUT_SLOW));   // was 221152 (spec §9) — reverted
                    parseGearRatioAct(sendCmdTimeout("2230DA", CMD_TIMEOUT_SLOW));   // was 221150 (spec §9) — reverted
                    parseGearRatioTgt(sendCmdTimeout("2230F8", CMD_TIMEOUT_SLOW));
                    parseTorqueConvSlip(sendCmdTimeout("221153", CMD_TIMEOUT_SLOW)); // spec §9 — direct slip rpm
                    parsePriPulley(sendCmdTimeout("2210D2", CMD_TIMEOUT_SLOW));
                    setHeader("7E0", "7E8");
                    parseCvtMode(sendCmdTimeout("221299", CMD_TIMEOUT_SLOW));
                }
            }

            // ════════════════════════════════════════════════════
            // TIER 3c — ECU VVT + actuators: every 10th loop, offset 2
            // Only needed on CAM/VVT page (9)
            // ════════════════════════════════════════════════════
            if (loopCount % 10 == 2 && data.activePage == 9) {
                setHeader("7E0", "7E8");
                parseVvtAngleR(sendCmdTimeout("221099", CMD_TIMEOUT_SLOW));
                parseVvtAngleL(sendCmdTimeout("2210B9", CMD_TIMEOUT_SLOW));
                parseRadFan(sendCmdTimeout("2210E3", CMD_TIMEOUT_SLOW));
                parseThrottleMotor(sendCmdTimeout("22105F", CMD_TIMEOUT_SLOW));
            }

            // ════════════════════════════════════════════════════
            // TIER 3d — ECU accessories: every 10th loop, offset 7
            // ScanGauge PIDs: CPC, OSV valves, fuel injection PW,
            // fuel tank pressure, EGR steps
            // ════════════════════════════════════════════════════
            if (loopCount % 10 == 7) {
                setHeader("7E0", "7E8");
                parseInjPulse(sendCmdTimeout("2210C0", CMD_TIMEOUT_SLOW));       // was 2210A3
                parseInjDutyCycle(sendCmdTimeout("2210C1", CMD_TIMEOUT_SLOW));   // spec §8 — new
                parseAFR(sendCmdTimeout("2210C3", CMD_TIMEOUT_SLOW));            // spec §8 — new
                parseTargetAFR(sendCmdTimeout("2210C4", CMD_TIMEOUT_SLOW));      // spec §8 — new
                parseHPFP(sendCmdTimeout("2210C7", CMD_TIMEOUT_SLOW));           // spec §8 — new
                parseCpcValve(sendCmdTimeout("2210CB", CMD_TIMEOUT_SLOW));
                parseOsvL(sendCmdTimeout("2210E5", CMD_TIMEOUT_SLOW));
                parseOsvR(sendCmdTimeout("2210C5", CMD_TIMEOUT_SLOW));
                parseFuelTankPress(sendCmdTimeout("22108F", CMD_TIMEOUT_SLOW));
                parseDAM(sendCmdTimeout("2210B1", CMD_TIMEOUT_SLOW));            // was egrSteps
            }

            data.updatePeaks();
            data.recordKnockEvent();

            loopCount++;
            long now = System.currentTimeMillis();
            if (now - lastHz >= 1000) {
                data.pollHz = hzCount;
                hzCount = 0;
                lastHz = now;
            }
            hzCount++;
            data.lastPollMs = System.currentTimeMillis() - t0;
        }
    }

    // ── Command send/receive ──────────────────────────────────────

    /** Send a command, return trimmed response (without '>' prompt) */
    private String sendCmd(String cmd) throws IOException {
        if (outStream == null) throw new IOException("Not connected");
        outStream.write((cmd + "\r").getBytes());
        outStream.flush();
        return readUntilPrompt(CMD_TIMEOUT_MS);
    }

    /** Send a command with a custom timeout — use for slow/optional PIDs */
    private String sendCmdTimeout(String cmd, int timeoutMs) throws IOException {
        if (outStream == null) throw new IOException("Not connected");
        outStream.write((cmd + "\r").getBytes());
        outStream.flush();
        return readUntilPrompt(timeoutMs);
    }

    /** Set CAN transmit header (ATSH) and receive-address filter (ATCRA) together.
     *  Only sends each AT command when the value has actually changed, so the fast
     *  7DF↔7E0 transitions (both use CRA 7E8) cost only one ATSH round-trip, not two. */
    private void setHeader(String hdr, String cra) throws IOException {
        if (!hdr.equals(currentHeader)) {
            sendCmd("ATSH" + hdr);
            currentHeader = hdr;
        }
        if (!cra.equals(currentCRA)) {
            sendCmd("ATCRA" + cra);
            currentCRA = cra;
        }
    }

    /** Like setHeader but always re-sends both AT commands regardless of cache.
     *  Use when switching to a secondary ECU (e.g. TCU) where ATCRA must be
     *  applied fresh — cheap ELM327 clones may silently ignore ATCRA7E9 if it
     *  arrives while the bus is still settling after the previous ECU command. */
    private void setHeaderForce(String hdr, String cra) throws IOException {
        sendCmd("ATSH" + hdr);
        currentHeader = hdr;
        sendCmd("ATCRA" + cra);
        currentCRA = cra;
    }

    /** Write raw bytes without reading response */
    private void sendRaw(String s) throws IOException {
        if (outStream != null) { outStream.write(s.getBytes()); outStream.flush(); }
    }

    /** Read bytes until '>' prompt or timeout.
     *  Reads up to 64 bytes at a time (vs single-byte) to drain BT buffer quickly.
     *  Returns trimmed, CR/LF-stripped, uppercase response. */
    private String readUntilPrompt(int timeoutMs) throws IOException {
        StringBuilder sb = new StringBuilder(64);
        long deadline = System.currentTimeMillis() + timeoutMs;
        byte[] buf = new byte[64];
        while (System.currentTimeMillis() < deadline) {
            int avail = inStream.available();
            if (avail > 0) {
                int n = inStream.read(buf, 0, Math.min(avail, buf.length));
                for (int i = 0; i < n; i++) {
                    char ch = (char)(buf[i] & 0xFF);
                    if (ch == '>') {
                        return sb.toString().toUpperCase().trim();
                    }
                    if (ch != '\r' && ch != '\n') sb.append(ch);
                }
            } else {
                sleep(2);  // 2ms vs 5ms — shorter busy-wait reduces per-command latency
            }
        }
        return sb.toString().toUpperCase().trim();
    }

    // ── PID Parsers ───────────────────────────────────────────────
    // All mode 01 responses (ATH0, ATS0): "41[PID][data hex]"
    // All mode 22 responses (ATH0, ATS0): "62[PIDhigh][PIDlow][data hex]"
    // Bytes extracted as 2-char hex pairs.

    /** Extract byte at position pos (0-based) from hex response string.
     *  E.g. "410C0FA0" byte[2]=0x0F, byte[3]=0xA0  */
    private int byteAt(String r, int pos) {
        try {
            int idx = pos * 2;
            if (idx + 2 > r.length()) return -1;
            return Integer.parseInt(r.substring(idx, idx + 2), 16);
        } catch (Exception e) { return -1; }
    }

    /** Strip spaces from response */
    private String strip(String r) { return r.replace(" ", ""); }

    private boolean isError(String r) {
        if (r == null || r.isEmpty()) return true;
        String u = r.toUpperCase();
        return u.contains("NODATA") || u.contains("NO DATA") || u.contains("ERROR")
               || u.contains("UNABLE") || u.contains("?") || u.contains("STOPPED")
               || u.contains("BUSBUSY") || u.contains("BUS BUSY")
               || u.contains("7F22") || u.contains("7F21"); // UDS negative response to mode 22/21
    }

    // ── Mode 01 parsers ───────────────────────────────────────────

    private void parseRPM(String r) {
        r = strip(r); if (isError(r) || r.length() < 8) return;
        int a = byteAt(r, 2), b = byteAt(r, 3);
        if (a < 0 || b < 0) return;
        data.rpm = (a * 256f + b) / 4f;
    }

    private void parseSpeed(String r) {
        r = strip(r); if (isError(r) || r.length() < 6) return;
        int a = byteAt(r, 2); if (a < 0) return;
        data.speedKph = a;
    }

    private void parsePedal(String r) {
        // PID 0145 = Relative Accelerator Pedal Position
        r = strip(r); if (isError(r) || r.length() < 6) return;
        int a = byteAt(r, 2); if (a < 0) return;
        data.pedalPct = a / 255f * 100f;
    }

    // ── Mode 22 ECU supplemental parsers ─────────────────────────
    // These are unverified on the FA20DIT — used as supplements in
    // Tier 2 alongside Mode 01; will not break the app if they fail.

    private void parseThrottleAngle(String r) {
        // 221022 — Throttle body angle %. TODO: verify formula on car.
        if (isError(r)) return;
        int a = m22byte(r, 0); if (a < 0) return;
        data.throttlePct = a / 255f * 100f;
    }

    private void parsePedal22(String r) {
        // 221023 — Accelerator pedal position %. TODO: verify formula on car.
        if (isError(r)) return;
        int a = m22byte(r, 0); if (a < 0) return;
        data.pedalPct = a / 255f * 100f;
    }

    private void parseMAP22(String r) {
        // 221024 — Manifold absolute pressure kPa. TODO: verify formula on car.
        if (isError(r)) return;
        int a = m22byte(r, 0); if (a < 0) return;
        data.mapKpa = a;
    }

    private void parseMAF22(String r) {
        // 221026 — MAF g/s. TODO: verify formula on car.
        if (isError(r)) return;
        int v = m22word(r); if (v < 0) return;
        data.mafGs = v / 100f;  // same encoding as Mode 01 (0.01 g/s per count)
    }

    private void parseTiming22(String r) {
        // 22102A — Ignition timing degrees. TODO: verify formula on car.
        if (isError(r)) return;
        int a = m22byte(r, 0); if (a < 0) return;
        data.timingDeg = a / 2f - 64f;  // same encoding as Mode 01
    }

    private void parseCoolant22(String r) {
        // 221020 — Coolant temperature °C. TODO: verify formula on car.
        if (isError(r)) return;
        int a = m22byte(r, 0); if (a < 0) return;
        float v = a - 40f;
        if (v > -41f && v < 200f) data.coolantC = v;
    }

    private void parseBoostDirect(String r) {
        // 2210A6 — Boost pressure psi (spec §6). TODO: verify formula on car.
        // Assumes 0.1 psi/count absolute; subtract atmospheric for gauge psi.
        if (isError(r)) return;
        int a = m22byte(r, 0); if (a < 0) return;
        data.boostPsiDirect = a / 10f - 14.7f;
    }

    private void parseLoad(String r) {
        r = strip(r); if (isError(r) || r.length() < 6) return;
        int a = byteAt(r, 2); if (a < 0) return;
        data.loadPct = a / 255f * 100f;
    }

    private void parseCoolant(String r) {
        r = strip(r); if (isError(r) || r.length() < 6) return;
        int a = byteAt(r, 2); if (a < 0) return;
        data.coolantC = a - 40f;
    }

    private void parseTiming(String r) {
        r = strip(r); if (isError(r) || r.length() < 6) return;
        int a = byteAt(r, 2); if (a < 0) return;
        data.timingDeg = a / 2f - 64f;
    }

    private void parseMAF(String r) {
        r = strip(r); if (isError(r) || r.length() < 8) return;
        int a = byteAt(r, 2), b = byteAt(r, 3);
        if (a < 0 || b < 0) return;
        data.mafGs = (a * 256f + b) / 100f;
    }

    private void parseMAP(String r) {
        r = strip(r); if (isError(r) || r.length() < 6) return;
        int a = byteAt(r, 2); if (a < 0) return;
        data.mapKpa = a;  // kPa absolute
    }

    private void parseBaro(String r) {
        // PID 0133 — dedicated barometric pressure sensor (no engine state dependency)
        r = strip(r); if (isError(r) || r.length() < 6) return;
        int a = byteAt(r, 2); if (a < 0) return;
        if (a > 85 && a < 108) data.baroKpa = a; // sanity check: 85-108 kPa is realistic
    }

    private void parseSTFT(String r) {
        r = strip(r); if (isError(r) || r.length() < 6) return;
        int a = byteAt(r, 2); if (a < 0) return;
        data.stftPct = (a - 128f) / 1.28f;
    }

    private void parseLTFT(String r) {
        r = strip(r); if (isError(r) || r.length() < 6) return;
        int a = byteAt(r, 2); if (a < 0) return;
        data.ltftPct = (a - 128f) / 1.28f;
    }

    private void parseCatTemp(String r) {
        // Mode 01 PID 013C — Catalyst Temperature Bank 1 Sensor 1
        // Two bytes: temp(°C) = (A*256 + B) / 10 - 40
        r = strip(r); if (isError(r) || r.length() < 8) return;
        int a = byteAt(r, 2), b = byteAt(r, 3);
        if (a < 0 || b < 0) return;
        float v = (a * 256f + b) / 10f - 40f;
        if (v > -41f && v < 2000f) data.catTempC = v;
    }

    /** ATRV returns "12.6V" style string */
    private void parseBattery(String r) {
        try {
            String v = r.replace("V","").replace("v","").trim();
            data.battV = Float.parseFloat(v);
        } catch (Exception ignored) {}
    }

    // ── Mode 22 ECU parsers ───────────────────────────────────────
    // Response format (ATH0, ATS0): "62[PID3bytes][databytes]"
    // Skip first 4 bytes (service 62 + 2-byte PID = 3 hex chars each = 8 chars total)
    // First data byte is at position 4 in the stripped hex string

    /** Get data byte from a Mode 22 response — searches for the 0x62 service byte
     *  to handle cases where partial headers are present (ELM327 clone quirks). */
    private int m22byte(String r, int dataByteIndex) {
        String s = strip(r);
        // Find "62" service response prefix — may be offset if header not fully stripped
        int idx = s.indexOf("62");
        // Use found position if plausible; fall back to byte-3 (clean ATH0 response)
        int base = (idx >= 0 && idx % 2 == 0) ? idx / 2 : 0;
        return byteAt(s, base + 3 + dataByteIndex);
    }

    private int m22word(String r) {
        int a = m22byte(r, 0), b = m22byte(r, 1);
        if (a < 0 || b < 0) return -1;
        return a * 256 + b;
    }

    private void parseOilTemp(String r) {
        // Mode 01 PID 0x5C — standard engine oil temperature, A-40 = °C
        r = strip(r); if (isError(r) || r.length() < 6) return;
        int a = byteAt(r, 2); if (a < 0) return;
        float v = a - 40f;
        if (v > -41f && v < 200f) data.oilTempC = v;
    }

    private void parseKnockCorr(String r) {
        // 223018 — feedback knock correction degrees (ScanGauge confirmed for FA20DIT WRX)
        // Note: 2210AF = Engine Oil Temperature (ScanGauge EOT), NOT knock correction
        // ScanGauge MTH 00010004FFE0: Range: 0→-32°, 128→0°, 255→31.75° (retard is negative)
        if (isError(r)) return;
        int a = m22byte(r, 0); if (a < 0) return;
        data.knockCorr = a / 4f - 32f;
    }

    private void parseWastegate(String r) {
        // 2210A8 — wastegate duty cycle % (spec §6; was 2210C9)
        if (isError(r)) return;
        int a = m22byte(r, 0); if (a < 0) return;
        data.wastegatePct = a / 2.55f;
    }

    private void parseIAT(String r) {
        // 22101F — intake air temperature °C (spec §5 Medium)
        if (isError(r)) return;
        int a = m22byte(r, 0); if (a < 0) return;
        float v = a - 40f;
        if (v > -41f && v < 200f) data.iatC = v;
    }

    private void parseTargetBoost(String r) {
        // 2210A7 — target boost pressure psi (spec §6). TODO: verify formula on car.
        if (isError(r)) return;
        int a = m22byte(r, 0); if (a < 0) return;
        data.targetBoostPsi = a / 10f - 14.7f;
    }

    private void parseFineKnock(String r) {
        // 2210B0 — fine knock learning degrees (spec §7)
        if (isError(r)) return;
        int a = m22byte(r, 0); if (a < 0) return;
        data.fineKnockDeg = a / 4f - 32f;
    }

    private void parseRoughness(String r, int cyl) {
        // ScanGauge MTH 000100010000 = raw * 1 / 1 + 0 — raw value directly
        if (isError(r)) return;
        int a = m22byte(r, 0); if (a < 0) return;
        switch (cyl) {
            case 1: data.rough1 = a; break;
            case 2: data.rough2 = a; break;
            case 3: data.rough3 = a; break;
            case 4: data.rough4 = a; break;
        }
    }

    private void parseOcvIntakeL(String r) {
        if (isError(r)) return;
        int a = m22byte(r, 0); if (a < 0) return;
        data.ocvIntakeL = a / 255f * 100f;
    }
    private void parseOcvIntakeR(String r) {
        if (isError(r)) return;
        int a = m22byte(r, 0); if (a < 0) return;
        data.ocvIntakeR = a / 255f * 100f;
    }
    private void parseOcvExhL(String r) {
        if (isError(r)) return;
        int a = m22byte(r, 0); if (a < 0) return;
        data.ocvExhL = a / 255f * 100f;
    }
    private void parseOcvExhR(String r) {
        if (isError(r)) return;
        int a = m22byte(r, 0); if (a < 0) return;
        data.ocvExhR = a / 255f * 100f;
    }

    private void parseTargetMAP(String r) {
        if (isError(r)) return;
        int a = m22byte(r, 0); if (a < 0) return;
        // Units: 1 kPa per count (may need tuning based on Car Scanner values)
        data.targetMapKpa = a;
    }

    private void parseBattTemp(String r) {
        if (isError(r)) return;
        int a = m22byte(r, 0); if (a < 0) return;
        data.battTempC = a - 40f;
    }

    private void parseAltDuty(String r) {
        // MTH 000100010000 = raw directly (0-255, treat as 0-100%)
        if (isError(r)) return;
        int a = m22byte(r, 0); if (a < 0) return;
        data.altDutyPct = a / 2.55f;
    }

    private void parseFuelPump(String r) {
        if (isError(r)) return;
        int a = m22byte(r, 0); if (a < 0) return;
        data.fuelPumpPct = a / 255f * 100f;
    }

    private void parseTurboSpeed(String r) {
        // 2210A9 — turbo speed estimate rpm (spec §6). TODO: verify formula on car.
        if (isError(r)) return;
        int v = m22word(r); if (v < 0) return;
        data.turboSpeedRpm = v * 10f;  // 10 RPM per count (common encoding)
    }

    private void parseChargeAirTemp(String r) {
        // 2210AA — charge air / intercooler outlet temperature °C (spec §6)
        if (isError(r)) return;
        int a = m22byte(r, 0); if (a < 0) return;
        float v = a - 40f;
        if (v > -41f && v < 200f) data.chargeAirTempC = v;
    }

    // ── Mode 22 TCU parsers ────────────────────────────────────────

    private void parseCVTTemp(String r) {
        // 221021 — CVT fluid temperature °C — lives on ECM (7E0/7E8), not TCU
        // Terminal confirmed: 221017 returns 7F2231 on both ECM+TCU; 221021 works on ECM only
        if (isError(r)) return;
        int a = m22byte(r, 0); if (a < 0) return;
        float v = a - 40f;
        if (v > -41f && v < 250f) data.cvtTempC = v;
    }

    private void parseLockup(String r) {
        // MTH 000100020000 = raw / 2 (%)
        if (isError(r)) return;
        int a = m22byte(r, 0); if (a < 0) return;
        data.lockupPct = a / 2f;
    }

    private void parseTransfer(String r) {
        // MTH 000100020000 = raw / 2 (%)
        if (isError(r)) return;
        int a = m22byte(r, 0); if (a < 0) return;
        data.transferPct = a / 2f;
    }

    private void parseTurbineRpm(String r) {
        // MTH 002000010000 = raw * 32 (RPM)
        if (isError(r)) return;
        int a = m22byte(r, 0); if (a < 0) return;
        data.turbineRpm = a * 32f;
    }

    private void parsePrimaryRpm(String r) {
        // MTH 000100010000 = raw word directly (RPM)
        if (isError(r)) return;
        int v = m22word(r); if (v < 0) return;
        data.primaryRpm = v;
    }

    private void parseSecondaryRpm(String r) {
        // MTH 000100010000 = raw word directly (RPM)
        if (isError(r)) return;
        int v = m22word(r); if (v < 0) return;
        data.secondaryRpm = v;
    }

    private void parseGearRatioAct(String r) {
        // 2230DA — CVT ratio actual (ScanGauge confirmed for Subaru CVT; was spec §9 221150).
        // Word assumed to encode ratio * 1000 (e.g. 2500 = 2.500). TODO: verify on car.
        if (isError(r)) return;
        int v = m22word(r); if (v < 0) return;
        data.gearRatioAct = v / 1000f;
    }

    private void parseGearRatioTgt(String r) {
        // 2230F8 — CVT ratio target (no spec equivalent; keep for compatibility)
        if (isError(r)) return;
        int v = m22word(r); if (v < 0) return;
        data.gearRatioTgt = v * 100f / 255f;
    }

    private void parseTorqueConvSlip(String r) {
        // 221153 — torque converter slip rpm (spec §9). TODO: verify formula on car.
        if (isError(r)) return;
        int v = m22word(r); if (v < 0) return;
        data.torqueConverterSlipRpm = v;
    }

    private void parsePriPulley(String r) {
        // 2210D2 from TCU (7E1/7E9) — confirmed in CarScanner log, value 0x75=117
        // Likely primary pulley pressure; raw * 6.9 ≈ kPa, or raw as-is (psi-adjacent)
        if (isError(r)) return;
        int a = m22byte(r, 0); if (a < 0) return;
        data.priPulleyRaw = a;
    }

    private void parseCvtMode(String r) {
        // 221299 from ECM (7E0/7E8) — confirmed in CarScanner log, value 0x04
        // Likely CVT selector position / range indicator (raw byte 0-255)
        if (isError(r)) return;
        int a = m22byte(r, 0); if (a < 0) return;
        data.cvtModeRaw = a;
    }

    // ── Mode 22 ECU — ScanGauge extended parsers ──────────────────

    private void parseVvtAngleR(String r) {
        // 221099 — VVT Advance Angle Right (degrees)
        // ScanGauge MTH 000100020000: raw / 2 = degrees
        if (isError(r)) return;
        int a = m22byte(r, 0); if (a < 0) return;
        data.vvtAngleR = a / 2f;
    }

    private void parseVvtAngleL(String r) {
        // 2210B9 — VVT Advance Angle Left (degrees)
        // ScanGauge MTH 000100020000: raw / 2 = degrees
        if (isError(r)) return;
        int a = m22byte(r, 0); if (a < 0) return;
        data.vvtAngleL = a / 2f;
    }

    private void parseRadFan(String r) {
        // 2210E3 — Radiator Fan Control (%)
        // ScanGauge MTH 000100010000: raw directly (0-255); rescale to 0-100%
        if (isError(r)) return;
        int a = m22byte(r, 0); if (a < 0) return;
        data.radFanPct = a / 2.55f;
    }

    private void parseThrottleMotor(String r) {
        // 22105F — Throttle Motor Duty (%)
        // ScanGauge MTH 0062007DFF9C: (raw * 98 / 125) - 100  → −100 to +100%
        if (isError(r)) return;
        int a = m22byte(r, 0); if (a < 0) return;
        data.throttleMotorPct = (a * 98f / 125f) - 100f;
    }

    private void parseInjPulse(String r) {
        // 2210C0 — injector pulse width ms (spec §8; was 2210A3). TODO: verify formula on car.
        // Assumes 2-byte word in 0.001ms units: word * 0.001 = ms
        if (isError(r)) return;
        int v = m22word(r); if (v < 0) return;
        data.injPulseMs = v * 0.001f;
    }

    private void parseInjDutyCycle(String r) {
        // 2210C1 — injector duty cycle % (spec §8). TODO: verify formula on car.
        if (isError(r)) return;
        int a = m22byte(r, 0); if (a < 0) return;
        data.injDutyCyclePct = a / 2.55f;
    }

    private void parseAFR(String r) {
        // 2210C3 — air/fuel ratio as lambda (spec §8). TODO: verify formula on car.
        // Assumes word / 1000 = lambda (e.g. 1000 = 1.000 lambda = stoich)
        if (isError(r)) return;
        int v = m22word(r); if (v < 0) return;
        data.afrLambda = v / 1000f;
    }

    private void parseTargetAFR(String r) {
        // 2210C4 — target AFR lambda (spec §8). TODO: verify formula on car.
        if (isError(r)) return;
        int v = m22word(r); if (v < 0) return;
        data.targetAfrLambda = v / 1000f;
    }

    private void parseHPFP(String r) {
        // 2210C7 — high pressure fuel pump pressure psi (spec §8). TODO: verify formula on car.
        if (isError(r)) return;
        int a = m22byte(r, 0); if (a < 0) return;
        data.hpfpPsi = a;  // raw byte; may need scaling
    }

    private void parseCpcValve(String r) {
        // 2210CB — CPC Valve Duty Ratio (%)
        // ScanGauge MTH 006400FF0000: raw * 100 / 255
        if (isError(r)) return;
        int a = m22byte(r, 0); if (a < 0) return;
        data.cpcValvePct = a * 100f / 255f;
    }

    private void parseOsvL(String r) {
        // 2210E5 — OSV Duty Left (%)
        // ScanGauge MTH 006400FF0000: raw * 100 / 255
        if (isError(r)) return;
        int a = m22byte(r, 0); if (a < 0) return;
        data.osvLPct = a * 100f / 255f;
    }

    private void parseOsvR(String r) {
        // 2210C5 — OSV Duty Right (%)
        // ScanGauge MTH 006400FF0000: raw * 100 / 255
        if (isError(r)) return;
        int a = m22byte(r, 0); if (a < 0) return;
        data.osvRPct = a * 100f / 255f;
    }

    private void parseFuelTankPress(String r) {
        // 22108F — Fuel Tank Air Pressure (raw 2-byte word)
        // ScanGauge MTH 000100010000 (word, 1:1 scale); unit labelled MPa/kPa — store raw
        if (isError(r)) return;
        int v = m22word(r); if (v < 0) return;
        data.fuelTankPressKpa = v;
    }

    private void parseDAM(String r) {
        // 2210B1 — dynamic advance multiplier ratio (spec §7; was EGR steps)
        // 1.0 = nominal, <1.0 = ECU has pulled timing due to knock history
        // TODO: verify formula on car
        if (isError(r)) return;
        int a = m22byte(r, 0); if (a < 0) return;
        data.damRatio = a / 255f;
    }

    // ── Mode 01 — additional parsers ─────────────────────────────

    private void parseFuelLevel(String r) {
        // PID 012F — Fuel Level Input (0-255 raw → 0-100%)
        r = strip(r); if (isError(r) || r.length() < 6) return;
        int a = byteAt(r, 2); if (a < 0) return;
        data.fuelLevelPct = a / 2.55f;
    }

    // ── Utility ───────────────────────────────────────────────────

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public boolean isConnected() { return data.connected; }
}
