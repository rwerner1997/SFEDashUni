package com.sfe.dashboard;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.*;
import android.widget.*;




/**
 * MainActivity — full-screen dashboard with two software buttons.
 *
 * LEFT BUTTON:
 *   Short tap  → previous page
 *   Long press → next theme
 *   Double tap → toggle auto-scroll
 *
 * RIGHT BUTTON:
 *   Short tap  → next page  (or dismiss alert if one is showing)
 *   Long press → toggle drive mode
 */
public class MainActivity extends Activity {

    private DashView  dashView;
    private OBDManager obdManager;

    // ── Double-tap detection ─────────────────────────────────────
    private long  leftLastTapMs = 0;
    private static final long DOUBLE_TAP_MS = 350;
    private static final long LONG_PRESS_MS = 600;

    // ── Simultaneous-button detection (for PID scan trigger) ─────
    private boolean leftDown = false;

    // ── Long-press runnables ──────────────────────────────────────
    private final Runnable leftLongRunnable  = () -> {
        dashView.nextTheme();
        leftLongFired = true;
    };
    private final Runnable rightLongRunnable = () -> {
        dashView.toggleDriveMode();
        rightLongFired = true;
    };
    private boolean leftLongFired  = false;
    private boolean rightLongFired = false;

    private static final int BT_ENABLE_REQUEST = 1;
    private static final int PERM_REQUEST      = 2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Full screen
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                             WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Request BT permissions
        requestBluetoothPermissions();

        // Build UI
        buildUI();
    }

    private void buildUI() {
        // Root layout — black background
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        // DashView fills the top portion
        dashView = new DashView(this);
        FrameLayout.LayoutParams dashParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        root.addView(dashView, dashParams);

        // Button strip — two buttons at the very bottom, overlaid on the canvas
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setBackgroundColor(0xE5000000);

        Button btnLeft  = makeButton("◀  PREV");
        Button btnRight = makeButton("NEXT  ▶");

        btnLeft.setOnTouchListener((v, ev) -> onLeftTouch(ev));
        btnRight.setOnTouchListener((v, ev) -> onRightTouch(ev));

        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btnRow.addView(btnLeft,  bp);
        btnRow.addView(btnRight, bp);

        FrameLayout.LayoutParams rowParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        root.addView(btnRow, rowParams);

        setContentView(root);

        // Start OBD after view is attached
        obdManager = new OBDManager(this, DashData.get());
        dashView.setOBDManager(obdManager);

        // Ensure BT is on
        ensureBluetoothOn();
    }

    private Button makeButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(0xFFCCCCCC);
        b.setBackgroundColor(0xFF111111);
        b.setTextSize(14f);
        b.setPadding(24, 18, 24, 18);
        b.setTypeface(android.graphics.Typeface.MONOSPACE);
        b.setAllCaps(false);
        return b;
    }

    // ── LEFT button touch ─────────────────────────────────────────

    private boolean onLeftTouch(MotionEvent ev) {
        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN:
                leftDown = true;
                leftLongFired = false;
                dashView.postDelayed(leftLongRunnable, LONG_PRESS_MS);
                return true;
            case MotionEvent.ACTION_UP:
                leftDown = false;
                dashView.removeCallbacks(leftLongRunnable);
                if (!leftLongFired) {
                    long now = System.currentTimeMillis();
                    if (now - leftLastTapMs < DOUBLE_TAP_MS) {
                        // Double tap → auto scroll
                        dashView.toggleAutoScroll();
                        leftLastTapMs = 0;
                    } else {
                        leftLastTapMs = now;
                        // Short tap — wait to see if double tap follows
                        dashView.postDelayed(() -> {
                            if (leftLastTapMs != 0 &&
                                System.currentTimeMillis() - leftLastTapMs >= DOUBLE_TAP_MS) {
                                dashView.prevPage();
                                leftLastTapMs = 0;
                            }
                        }, DOUBLE_TAP_MS);
                    }
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                leftDown = false;
                dashView.removeCallbacks(leftLongRunnable);
                return true;
        }
        return false;
    }

    // ── RIGHT button touch ────────────────────────────────────────

    private boolean onRightTouch(MotionEvent ev) {
        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN:
                // Both buttons held simultaneously → toggle PID scan
                if (leftDown) {
                    dashView.removeCallbacks(leftLongRunnable);
                    leftLongFired = true;  // suppress left-button action on release
                    if (obdManager != null) {
                        if (obdManager.isPIDScanRunning()) {
                            obdManager.cancelPIDScan();
                        } else if (DashData.get().connected) {
                            obdManager.requestPIDScan();
                        }
                    }
                    return true;
                }
                rightLongFired = false;
                dashView.postDelayed(rightLongRunnable, LONG_PRESS_MS);
                return true;
            case MotionEvent.ACTION_UP:
                dashView.removeCallbacks(rightLongRunnable);
                if (!rightLongFired) {
                    // Short tap → next page or dismiss alert
                    if (dashView.isAlertOn()) {
                        dashView.dismissAlert();
                    } else {
                        dashView.nextPage();
                    }
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                dashView.removeCallbacks(rightLongRunnable);
                return true;
        }
        return false;
    }

    // ── Bluetooth ────────────────────────────────────────────────

    private void ensureBluetoothOn() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            Toast.makeText(this, "No Bluetooth on this device", Toast.LENGTH_LONG).show();
            return;
        }
        if (!adapter.isEnabled()) {
            Intent en = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(en, BT_ENABLE_REQUEST);
        }
    }

    private void requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            String[] perms = {
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION,
            };
            boolean needsRequest = false;
            for (String p : perms) {
                if (checkSelfPermission( p) != PackageManager.PERMISSION_GRANTED) {
                    needsRequest = true; break;
                }
            }
            if (needsRequest) requestPermissions( perms, PERM_REQUEST);
        } else {
            // API < 31: location always needed; WRITE_EXTERNAL_STORAGE needed below API 29
            // (API 29+ uses MediaStore for Documents access — no permission required).
            java.util.List<String> permList = new java.util.ArrayList<>();
            permList.add(Manifest.permission.ACCESS_FINE_LOCATION);
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                permList.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
            String[] perms = permList.toArray(new String[0]);
            boolean needsRequest = false;
            for (String p : perms) {
                if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                    needsRequest = true;
                    break;
                }
            }
            if (needsRequest) requestPermissions(perms, PERM_REQUEST);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == BT_ENABLE_REQUEST && res == RESULT_OK) {
            if (obdManager != null) obdManager.start();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (obdManager != null) obdManager.stop();
    }
}
