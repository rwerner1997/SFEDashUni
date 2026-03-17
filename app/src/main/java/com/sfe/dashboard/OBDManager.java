package com.sfe.dashboard;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
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
    private static final int CMD_TIMEOUT_SLOW = 500;  // for Mode 22 PIDs — generous headroom for ECU response
    private static final int CMD_TIMEOUT_ROUGH = 600; // for 2230xx roughness PIDs — sometimes need extra time
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

    private final OBDLogger logger = new OBDLogger();

    // ── PID scanner ───────────────────────────────────────────────
    private final AtomicBoolean scanRequested = new AtomicBoolean(false);
    private ParcelFileDescriptor scanPfd;   // held open for MediaStore scan log (API 29+)

    public void requestPIDScan()      { scanRequested.set(true); }
    public void cancelPIDScan()       { data.scanRunning = false; }
    public boolean isPIDScanRunning() { return data.scanRunning; }

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
                logger.open(ctx);
                try { pollLoop(); } finally { logger.close(); }
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
            // Check if a PID scan was requested — runs in this thread using the live connection
            if (scanRequested.getAndSet(false)) {
                runPIDScan();
                continue;   // resume normal polling after scan finishes
            }

            long t0 = System.currentTimeMillis();

            // ════════════════════════════════════════════════════
            // TIER 1 — FAST: every loop (~20Hz target) — RPM, Speed, MAP.
            // All Mode 01, same header — no header switch cost.
            // MAP here drives boostPsi() (MAP-baro) at full refresh rate.
            // ════════════════════════════════════════════════════
            setHeader("7DF", "7E8");
            parseRPM(sendCmd("010C"));
            parseSpeed(sendCmd("010D"));
            parseMAP(sendCmd("010B"));

            // ════════════════════════════════════════════════════
            // TIER 2 — MEDIUM: every 3rd loop (~7Hz)
            // MAP, MAF, timing, throttle, pedal, fuel trims (Mode 01).
            // Knock, wastegate, IAT, fine knock (Mode 22).
            // ════════════════════════════════════════════════════
            if (loopCount % 3 == 0) {
                // Mode 01 — engine vitals (MAP moved to Tier 1 for smooth boost display)
                setHeader("7DF", "7E8");
                parseTiming(sendCmd("010E"));
                parseMAF(sendCmd("0110"));
                parsePedal(sendCmd("0145"));
                parseLoad(sendCmd("0104"));
                parseSTFT(sendCmd("0106"));
                parseLTFT(sendCmd("0107"));

                // Mode 22 ECU — knock health, turbo, supplemental channels
                setHeader("7E0", "7E8");
                // 2210A6 direct boost sensor dropped — unverified formula gives wrong readings.
                // Boost is calculated from MAP-baro in DashData.boostPsi().
                // 223018 (knock feedback) returns 7F2231 on every poll — not supported on this ECU.
                // Removing the poll keeps knockCorr=NaN permanently, preventing spurious knock alerts.
                // 221022 (throttle body °) dropped — field never displayed; pedal (0145) used instead.
                // 22101F (IAT) dropped — returns 7F2231 (requestOutOfRange) on every poll, always NaN.
                parseWastegate(sendM22("2210A8", CMD_TIMEOUT_SLOW));
                parseFineKnock(sendM22("2210B0", CMD_TIMEOUT_SLOW));
                parseVvtAngleL(sendM22("2210B9", CMD_TIMEOUT_SLOW));
            }

            // ════════════════════════════════════════════════════
            // TIER 3a — ECU slow: every 10th loop (~1Hz)
            // Temps, accessories, roughness (page-gated)
            // ════════════════════════════════════════════════════
            if (loopCount % 10 == 0) {
                int ap = data.activePage;
                // Switch back to 7DF for Mode 01 PIDs
                setHeader("7DF", "7E8");
                parseCoolant(sendCmd("0105"));
                parseOilTemp(sendCmd("015C"));
                parseCatTemp(sendCmd("013C"));
                parseBaro(sendCmd("0133"));
                parseFuelLevel(sendCmd("012F"));   // fuel tank level (Mode 01)
                parseBattery(sendCmd("ATRV"));

                // Force ATSH + ATCRA re-send: cheap ELM clones may silently reset ATCRA
                // when ATSH changes (e.g. 7DF→7E0), leaving the filter stale and causing
                // the ECU response to be missed or mixed with bus noise from other ECUs.
                setHeaderForce("7E0", "7E8");
                parseTargetMAP(sendM22("223050", CMD_TIMEOUT_SLOW));
                // 22309A (batt temp) dropped — field never displayed or alerted on.
                // CVT temp: TCU 2210D2, formula byte-50 °C.
                // Confirmed across 3 reference points (pid_scan_20260315, _0316_083419, _084742).
                // 2210C9 swings wildly with braking/acceleration — not a temp sensor.
                setHeaderForce("7E1", "7E9");
                parseCVTTemp(sendM22("2210D2", CMD_TIMEOUT_SLOW));
                setHeaderForce("7E0", "7E8");
            }

            // ════════════════════════════════════════════════════
            // TIER 3d — DAM + fuel system: every 10th loop, offset 7
            // ════════════════════════════════════════════════════
            if (loopCount % 10 == 7) {
                setHeader("7E0", "7E8");
                parseDAM(sendM22("2210B1", CMD_TIMEOUT_SLOW));
                parseInjDutyCycle(sendM22("2210C1", CMD_TIMEOUT_SLOW));
                parseInjPulse(sendM22("2210B4", CMD_TIMEOUT_SLOW));  // 2210C0 returns static 80000007; 2210B4 is live
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

    // ── PID discovery scan ────────────────────────────────────────

    /**
     * Scan focused Mode 22 PID ranges on ECM (7E0) and TCU (7E1), log every response
     * that is NOT NR_31 (requestOutOfRange) to Documents/SFEDash/pid_scan_TIMESTAMP.csv.
     *
     * Skipping NR_31 keeps the output small — only responsive/conditional/unexpected PIDs
     * are logged.  A SUMMARY row at the end shows total counts.
     *
     * Trigger: both buttons held simultaneously (MainActivity sets scanRequested).
     * Cancel:  cancelPIDScan() clears data.scanRunning; the inner loop checks it each PID.
     *
     * Scan ranges (chosen to cover all known Subaru FA20DIT PIDs ± neighbours):
     *   ECM  0x1000–0x10FF, 0x1100–0x11FF, 0x3000–0x30FF
     *   TCU  0x1000–0x10FF, 0x1100–0x11FF, 0x3000–0x30FF
     */
    private void runPIDScan() throws IOException {
        final int SCAN_TIMEOUT = 300;   // ms — faster than CMD_TIMEOUT_SLOW but generous

        // Each row: { ecuLabel, txHeader, rxHeader, startPID, endPID }
        String[][] phases = {
            {"ECM", "7E0", "7E8", "1000", "10FF"},
            {"ECM", "7E0", "7E8", "1100", "11FF"},
            {"ECM", "7E0", "7E8", "3000", "30FF"},
            {"TCU", "7E1", "7E9", "1000", "10FF"},
            {"TCU", "7E1", "7E9", "1100", "11FF"},
            {"TCU", "7E1", "7E9", "3000", "30FF"},
        };

        int totalPids = 0;
        for (String[] ph : phases)
            totalPids += Integer.parseInt(ph[4], 16) - Integer.parseInt(ph[3], 16) + 1;

        data.scanRunning  = true;
        data.scanPhase    = "OPENING LOG";
        data.scanProgress = 0f;

        BufferedWriter w = openScanLog();
        if (w == null) { data.scanRunning = false; return; }

        try { w.write("ecu,pid,raw,status,data_hex\n"); } catch (IOException ignored) {}

        int scanned = 0, hits = 0, nr22 = 0, other = 0;
        String lastTx = "", lastRx = "";

        outer:
        for (String[] ph : phases) {
            String ecuLabel = ph[0], tx = ph[1], rx = ph[2];
            int start = Integer.parseInt(ph[3], 16);
            int end   = Integer.parseInt(ph[4], 16);

            if (!tx.equals(lastTx) || !rx.equals(lastRx)) {
                setHeaderForce(tx, rx);
                lastTx = tx; lastRx = rx;
            }

            for (int pid = start; pid <= end; pid++) {
                if (!running.get() || !data.scanRunning) break outer;

                String pidHex = String.format("%04X", pid);
                data.scanPhase    = ecuLabel + " " + pidHex;
                data.scanProgress = scanned / (float) totalPids;

                String r;
                try {
                    r = sendCmdTimeout("22" + pidHex, SCAN_TIMEOUT);
                } catch (IOException e) {
                    closeScanLog(w, scanned, hits, nr22, other);
                    data.scanRunning = false;
                    throw e;    // propagate to trigger reconnect
                }

                String status = classifyScanResponse(r);
                scanned++;

                if ("NR_31".equals(status)) continue;   // most common — skip silently

                String dataHex = "OK".equals(status) ? extractScanData(r, pidHex) : "";
                try {
                    w.write(ecuLabel + ",22" + pidHex + ","
                            + (r == null ? "" : r.replace(",", ";")) + ","
                            + status + "," + dataHex + "\n");
                } catch (IOException ignored) {}

                if      ("OK"   .equals(status)) hits++;
                else if ("NR_22".equals(status)) nr22++;
                else                             other++;
            }
        }

        closeScanLog(w, scanned, hits, nr22, other);

        // Keep overlay visible for 5 s so the user sees the result
        data.scanPhase    = "DONE  " + hits + " OK  " + nr22 + " CONDITIONAL";
        data.scanProgress = 1f;
        sleep(5000);
        data.scanRunning = false;
    }

    /** Classify an ELM327 / UDS response for scan logging. */
    private String classifyScanResponse(String r) {
        if (r == null || r.isEmpty()) return "NO_DATA";
        String u = strip(r).toUpperCase();
        if (u.startsWith("62"))        return "OK";
        if (u.contains("7F2231"))      return "NR_31";   // requestOutOfRange — not supported
        if (u.contains("7F2212"))      return "NR_12";   // subFunctionNotSupported
        if (u.contains("7F2222"))      return "NR_22";   // conditionsNotCorrect — PID exists!
        if (u.contains("7F2224"))      return "NR_24";   // requestSequenceError
        if (u.contains("7F2235"))      return "NR_35";   // requestSequenceError
        if (u.contains("7F22"))        return "NR_XX";   // other UDS negative response
        if (u.contains("NODATA") || u.contains("NO DATA")) return "NO_DATA";
        return "ERROR";
    }

    /** Strip the 62+PID prefix from a positive response to get just the data bytes. */
    private String extractScanData(String r, String pidHex) {
        String u = strip(r).toUpperCase();
        String prefix = "62" + pidHex.toUpperCase();
        int idx = u.indexOf(prefix);
        if (idx < 0) return "";
        int start = idx + prefix.length();
        return (start < u.length()) ? u.substring(start) : "";
    }

    /** Open a new pid_scan CSV file in Documents/SFEDash (same strategy as OBDLogger). */
    private BufferedWriter openScanLog() {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, "pid_scan_" + ts + ".csv");
                cv.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/SFEDash");
                Uri uri = ctx.getContentResolver()
                             .insert(MediaStore.Files.getContentUri("external"), cv);
                if (uri == null) { Log.e(TAG, "Scan log: MediaStore insert null"); return null; }
                scanPfd = ctx.getContentResolver().openFileDescriptor(uri, "w");
                if (scanPfd == null) { Log.e(TAG, "Scan log: pfd null"); return null; }
                return new BufferedWriter(new OutputStreamWriter(
                        new FileOutputStream(scanPfd.getFileDescriptor())), 65536);
            } else {
                //noinspection deprecation
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOCUMENTS), "SFEDash");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                return new BufferedWriter(
                        new FileWriter(new File(dir, "pid_scan_" + ts + ".csv")), 65536);
            }
        } catch (IOException e) {
            Log.e(TAG, "Cannot open scan log: " + e.getMessage());
            return null;
        }
    }

    private void closeScanLog(BufferedWriter w, int scanned, int hits, int nr22, int other) {
        try {
            w.write("SUMMARY,scanned=" + scanned + ",hits_OK=" + hits
                    + ",conditional_NR22=" + nr22 + ",other=" + other + ",,\n");
            w.flush();
            w.close();
        } catch (IOException ignored) {}
        if (scanPfd != null) {
            try { scanPfd.close(); } catch (IOException ignored) {}
            scanPfd = null;
        }
        Log.i(TAG, "PID scan done: " + hits + " OK, " + nr22
                + " conditional, " + other + " other / " + scanned + " scanned");
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

    /**
     * Send a Mode 22 command, log the raw response and first two data bytes, then return
     * the response for the caller's parser.  Drop-in replacement for sendCmdTimeout on
     * all "22xxxx" PIDs.
     */
    private String sendM22(String pid, int timeoutMs) throws IOException {
        String r = sendCmdTimeout(pid, timeoutMs);
        int b0 = isError(r) ? -1 : m22byte(r, 0);
        int b1 = (b0 >= 0)  ? m22byte(r, 1) : -1;   // -1 for single-byte responses
        logger.log(pid, r, b0, b1);
        return r;
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
        float raw = (a * 256f + b) / 4f;
        if (raw > 8000f) return;  // reject garbled ELM frames (FA20DIT redline ~7000 RPM)
        float prev = data.rpm;
        // EMA α=0.25: ~0.2s time constant at 20Hz — smooths noise without perceptible lag
        data.rpm = Float.isNaN(prev) ? raw : prev * 0.75f + raw * 0.25f;
    }

    private void parseSpeed(String r) {
        r = strip(r); if (isError(r) || r.length() < 6) return;
        int a = byteAt(r, 2); if (a < 0) return;
        float raw = a;
        float prev = data.speedKph;
        // EMA α=0.4: ~0.12s time constant at 20Hz — smooths 1-kph quantization steps
        data.speedKph = Float.isNaN(prev) ? raw : prev * 0.6f + raw * 0.4f;
    }

    private void parsePedal(String r) {
        // PID 0145 = Relative Accelerator Pedal Position
        // Car returns ~4% at rest (physical idle offset); subtract and clamp to 0.
        r = strip(r); if (isError(r) || r.length() < 6) return;
        int a = byteAt(r, 2); if (a < 0) return;
        data.pedalPct = Math.max(0f, a / 255f * 100f - 4f);
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
        // 2210A6 — Boost pressure psi (gauge). 0.1 psi/count, sensor reads 0 at atmospheric.
        // Log confirms byte=0x00 consistently at idle/no-boost; byte=0x9E(158)→15.8 psi at WOT.
        // No atmospheric offset needed — sensor reports gauge pressure directly.
        if (isError(r)) return;
        int a = m22byte(r, 0); if (a < 0) return;
        data.boostPsiDirect = a / 10f;
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
        float v = a / 2f - 64f;
        if (v < -20f || v > 55f) return;  // reject physically implausible values (ELM glitch)
        data.timingDeg = v;
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
        float raw = a;  // kPa absolute
        float prev = data.mapKpa;
        // EMA α=0.3: ~0.17s time constant at 20Hz — smooth boost without hiding spool/blowoff
        data.mapKpa = Float.isNaN(prev) ? raw : prev * 0.7f + raw * 0.3f;
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
        // IMPORTANT: clear to NaN on error — do NOT retain stale negative values, as a stuck
        // knockCorr < -2.5 would cause the knock alert to fire on every render frame forever.
        if (isError(r)) { data.knockCorr = Float.NaN; return; }
        int a = m22byte(r, 0); if (a < 0) { data.knockCorr = Float.NaN; return; }
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

    private void parseFineKnock(String r) {
        // 2210B0 — fine knock learning degrees (spec §7)
        // Clear to NaN on error for the same reason as parseKnockCorr above.
        if (isError(r)) { data.fineKnockDeg = Float.NaN; return; }
        int a = m22byte(r, 0); if (a < 0) { data.fineKnockDeg = Float.NaN; return; }
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

    // ── Mode 22 TCU — CVT fluid temp ─────────────────────────────────────────────────

    private void parseCVTTemp(String r) {
        // 2210D2 on TCU (7E1/7E9) — CVT fluid temperature °C, formula: byte - 50
        // Verified: 0x63→49°C(120°F), 0x7D→75°C(167°F), 0x81→79°C(174°F) across 3 logs.
        if (isError(r)) return;
        int a = m22byte(r, 0); if (a < 0) return;
        float v = a - 50f;
        if (v > -30f && v < 200f) data.cvtTempC = v;
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
        // 2210B4 — injector pulse width ms. PID scan confirmed live (2.7–5.0 ms at idle).
        // Formula: byte * 0.1 ms (unverified — 2210C0 was static 80000007 and dropped).
        if (isError(r)) return;
        int a = m22byte(r, 0); if (a < 0) return;
        data.injPulseMs = a * 0.1f;
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
        // 2210B1 — dynamic advance multiplier ratio.
        // FA20DIT encodes DAM as 0–16 counts; 16 = 1.0 (full advance), 0 = fully pulled.
        // Observed values: 0x00, 0x01, 0x03, 0x0C (0, 1, 3, 12 → 0.0, 0.06, 0.19, 0.75).
        // Prior formula was /255 which kept values microscopic — corrected to /16.
        if (isError(r)) return;
        int a = m22byte(r, 0); if (a < 0) return;
        data.damRatio = Math.min(1f, a / 16f);
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
