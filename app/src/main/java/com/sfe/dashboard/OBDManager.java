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

    private static final int CMD_TIMEOUT_MS  = 1500;  // per-command timeout
    private static final int CONNECT_RETRY_S = 5;     // seconds between reconnect attempts

    private final Context    ctx;
    private final DashData   data;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private BluetoothSocket  socket;
    private InputStream      inStream;
    private OutputStream     outStream;
    private Thread           pollThread;

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
        sendCmd("ATST19");          // timeout 25ms per try (0x19 = 25)
        sendCmd("ATAT1");           // adaptive timing mode 1
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
        // Set ECU header once at start
        String currentHeader = "";

        while (running.get() && data.connected) {
            long t0 = System.currentTimeMillis();

            // ── MODE 01 — standard PIDs (no header needed) ────────
            if (!currentHeader.isEmpty()) {
                sendCmd("ATSH7E8"); // Reset to default response header
                // Actually with ATH0 and ATSP0 the adapter handles routing
                // We just need to clear the custom header
                sendCmd("ATSH7DF"); // broadcast address — responses from all ECUs
                currentHeader = "";
            }

            parseRPM(sendCmd("010C"));
            parseSpeed(sendCmd("010D"));
            parseThrottle(sendCmd("0111"));
            parseLoad(sendCmd("0104"));
            parseCoolant(sendCmd("0105"));
            parseTiming(sendCmd("010E"));
            parseMAF(sendCmd("0110"));
            parseMAP(sendCmd("010B"));
            parseSTFT(sendCmd("0106"));
            parseLTFT(sendCmd("0107"));
            parseBattery(sendCmd("ATRV"));   // ELM battery voltage

            // ── MODE 22 ECU — AT SH 7E0 ──────────────────────────
            if (!"7E0".equals(currentHeader)) {
                sendCmd("ATSH7E0");
                currentHeader = "7E0";
            }
            parseOilTemp(sendCmd("2210AF"));
            parseKnockCorr(sendCmd("223018"));
            parseRoughness(sendCmd("223062"), 1);
            parseRoughness(sendCmd("223048"), 2);
            parseRoughness(sendCmd("223068"), 3);
            parseRoughness(sendCmd("22304A"), 4);
            parseOcvIntakeL(sendCmd("2210BB"));
            parseOcvIntakeR(sendCmd("22109B"));
            parseOcvExhL(sendCmd("2210EF"));
            parseOcvExhR(sendCmd("2210CF"));
            parseTargetMAP(sendCmd("223050"));
            parseBattTemp(sendCmd("22309A"));
            parseAltDuty(sendCmd("221093"));
            parseFuelPump(sendCmd("2210B3"));

            // ── MODE 22 TCU — AT SH 7E1 ──────────────────────────
            if (!"7E1".equals(currentHeader)) {
                sendCmd("ATSH7E1");
                currentHeader = "7E1";
            }
            parseCVTTemp(sendCmd("221017"));
            parseLockup(sendCmd("221045"));
            parseTransfer(sendCmd("221065"));
            parseTurbineRpm(sendCmd("221067"));
            parsePrimaryRpm(sendCmd("22300E"));
            parseSecondaryRpm(sendCmd("2230D0"));
            parseGearRatioAct(sendCmd("2230DA"));
            parseGearRatioTgt(sendCmd("2230F8"));

            // ── Update peaks ──────────────────────────────────────
            data.updatePeaks();
            data.recordKnockEvent();

            // ── Hz tracking ───────────────────────────────────────
            hzCount++;
            long now = System.currentTimeMillis();
            if (now - lastHz >= 1000) {
                data.pollHz = hzCount;
                hzCount = 0;
                lastHz = now;
            }
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

    /** Write raw bytes without reading response */
    private void sendRaw(String s) throws IOException {
        if (outStream != null) { outStream.write(s.getBytes()); outStream.flush(); }
    }

    /** Read bytes until '>' prompt or timeout, return trimmed response */
    private String readUntilPrompt(int timeoutMs) throws IOException {
        StringBuilder sb = new StringBuilder();
        long deadline = System.currentTimeMillis() + timeoutMs;
        byte[] buf = new byte[1];
        while (System.currentTimeMillis() < deadline) {
            if (inStream.available() > 0) {
                int n = inStream.read(buf, 0, 1);
                if (n > 0) {
                    char c = (char) buf[0];
                    if (c == '>') break;
                    sb.append(c);
                }
            } else {
                sleep(5);
            }
        }
        return sb.toString().trim().replace("\r", "").replace("\n", "").toUpperCase();
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
        return r.isEmpty() || r.contains("NODATA") || r.contains("ERROR")
               || r.contains("UNABLE") || r.contains("?") || r.contains("STOPPED");
    }

    // ── Mode 01 parsers ───────────────────────────────────────────

    private void parseRPM(String r) {
        r = strip(r); if (isError(r) || r.length() < 8) return;
        // "410CXXXXYYYY" — service 41, pid 0C, bytes A B
        int a = byteAt(r, 2), b = byteAt(r, 3);
        if (a < 0 || b < 0) return;
        data.rpm = (a * 256f + b) / 4f;
    }

    private void parseSpeed(String r) {
        r = strip(r); if (isError(r) || r.length() < 6) return;
        int a = byteAt(r, 2); if (a < 0) return;
        data.speedKph = a;
    }

    private void parseThrottle(String r) {
        r = strip(r); if (isError(r) || r.length() < 6) return;
        int a = byteAt(r, 2); if (a < 0) return;
        data.throttlePct = a / 255f * 100f;
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
        // First reading updates baro if engine just started (low RPM / idle MAP ≈ baro)
        if (data.rpm < 900 && a > 90 && a < 105) data.baroKpa = a;
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

    /** Get first data byte from a Mode 22 response "621XYYAA..." → AA */
    private int m22byte(String r, int dataByteIndex) {
        // "62" + 2-byte PID = 6 chars header, then data bytes
        return byteAt(strip(r), 3 + dataByteIndex);
    }

    private int m22word(String r) {
        int a = m22byte(r, 0), b = m22byte(r, 1);
        if (a < 0 || b < 0) return -1;
        return a * 256 + b;
    }

    private void parseOilTemp(String r) {
        // Formula: (A * 0.5) - 40 °C  (Subaru standard)
        if (isError(r)) return;
        int a = m22byte(r, 0); if (a < 0) return;
        data.oilTempC = a * 0.5f - 40f;
    }

    private void parseKnockCorr(String r) {
        // Signed byte: (A - 128) * 0.5 degrees
        if (isError(r)) return;
        int a = m22byte(r, 0); if (a < 0) return;
        data.knockCorr = (a - 128) * 0.5f;
    }

    private void parseRoughness(String r, int cyl) {
        // Raw byte, 0 = smooth, higher = rougher
        if (isError(r)) return;
        int a = m22byte(r, 0); if (a < 0) return;
        float v = a * 0.5f;  // scale to 0-127 range
        switch (cyl) {
            case 1: data.rough1 = v; break;
            case 2: data.rough2 = v; break;
            case 3: data.rough3 = v; break;
            case 4: data.rough4 = v; break;
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

    private void pars
