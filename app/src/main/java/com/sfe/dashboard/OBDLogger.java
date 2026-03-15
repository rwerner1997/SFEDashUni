package com.sfe.dashboard;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Writes per-session OBD diagnostic logs to Documents/SFEDash/ on the phone.
 *
 * Android 10+ (API 29+): uses MediaStore — no permission required.
 *   Visible in Files app under: Internal Storage > Documents > SFEDash
 *
 * Android 9 and below: uses legacy file path with WRITE_EXTERNAL_STORAGE.
 *   Visible at: /sdcard/Documents/SFEDash/
 *
 * CSV columns:
 *   elapsed_s — seconds since this connection session started
 *   pid       — 6-char Mode 22 PID (e.g. "221021")
 *   raw       — exact trimmed response string from ELM327
 *   bytes     — first (and second if present) data byte in hex+decimal,
 *               e.g. "0xC2(194)" or "0xC2(194)+0x00(0)"; "ERR" for errors
 *
 * One file per connection session.  Flushes every 200 rows; max 100 000 rows.
 */
class OBDLogger {

    private static final String TAG       = "OBDLogger";
    private static final int    MAX_LINES = 100_000;

    private BufferedWriter       writer;
    private ParcelFileDescriptor pfd;      // held open for MediaStore path (API 29+)
    private long                 startMs;
    private int                  lineCount;

    // ── Lifecycle ────────────────────────────────────────────────

    /** Open a new log file.  Call once per OBD connection, before the poll loop. */
    void open(Context ctx) {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        startMs   = System.currentTimeMillis();
        lineCount = 0;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                openViaMediaStore(ctx, ts);
            } else {
                openViaLegacyFile(ts);
            }
            if (writer != null) writer.write("elapsed_s,pid,raw,bytes\n");
        } catch (IOException e) {
            Log.e(TAG, "Cannot open log: " + e.getMessage());
            writer = null;
        }
    }

    /** Flush and close the current log file. */
    void close() {
        if (writer == null) return;
        try { writer.flush(); writer.close(); } catch (IOException ignored) {}
        writer = null;
        if (pfd != null) {
            try { pfd.close(); } catch (IOException ignored) {}
            pfd = null;
        }
        Log.i(TAG, "OBD log closed (" + lineCount + " lines)");
    }

    // ── Open helpers ─────────────────────────────────────────────

    /** API 29+: insert into MediaStore so the file appears in Documents/SFEDash. */
    private void openViaMediaStore(Context ctx, String ts) throws IOException {
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, "sfe_" + ts + ".csv");
        cv.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/SFEDash");
        Uri uri = ctx.getContentResolver()
                     .insert(MediaStore.Files.getContentUri("external"), cv);
        if (uri == null) throw new IOException("MediaStore insert returned null");
        pfd = ctx.getContentResolver().openFileDescriptor(uri, "w");
        if (pfd == null) throw new IOException("openFileDescriptor returned null");
        writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(pfd.getFileDescriptor())), 8192);
        Log.i(TAG, "OBD log → Documents/SFEDash/sfe_" + ts + ".csv");
    }

    /** API 28 and below: write directly to /sdcard/Documents/SFEDash/. */
    @SuppressWarnings("deprecation")
    private void openViaLegacyFile(String ts) throws IOException {
        File dir = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                "SFEDash");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File f = new File(dir, "sfe_" + ts + ".csv");
        writer = new BufferedWriter(new FileWriter(f), 8192);
        Log.i(TAG, "OBD log → " + f.getAbsolutePath());
    }

    // ── Logging ──────────────────────────────────────────────────

    /**
     * Record one Mode 22 exchange.
     *
     * @param pid  6-char PID string, e.g. "221021"
     * @param raw  trimmed ELM327 response string
     * @param b0   first data byte value, or -1 if error/parse failure
     * @param b1   second data byte value, or -1 if absent (single-byte response)
     */
    void log(String pid, String raw, int b0, int b1) {
        if (writer == null || lineCount >= MAX_LINES) return;
        try {
            float  t       = (System.currentTimeMillis() - startMs) / 1000f;
            String safeRaw = (raw == null) ? "" : raw.replace("\"", "'");
            String bytes;
            if (b0 < 0) {
                bytes = "ERR";
            } else if (b1 < 0) {
                bytes = String.format(Locale.US, "0x%02X(%d)", b0, b0);
            } else {
                bytes = String.format(Locale.US, "0x%02X(%d)+0x%02X(%d)", b0, b0, b1, b1);
            }
            writer.write(String.format(Locale.US, "%.3f,%s,\"%s\",%s\n",
                    t, pid, safeRaw, bytes));
            lineCount++;
            if (lineCount % 200 == 0) writer.flush();
        } catch (IOException e) {
            Log.w(TAG, "log write: " + e.getMessage());
        }
    }
}
