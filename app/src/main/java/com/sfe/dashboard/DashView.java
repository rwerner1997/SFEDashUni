package com.sfe.dashboard;

import android.content.Context;
import android.graphics.*;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/**
 * DashView — SurfaceView that renders the entire dashboard at 30 fps.
 *
 * All drawing uses Android Canvas with the same C++ firmware render style:
 * flat fills, 1-px text shadow, concentric-shape glow (no shadowBlur).
 * Coordinates are in logical 320×480 space, scaled to fill the view.
 *
 * Page map (11 pages):
 *  0 ENGINE  1 TEMPS  2 BOOST/TURBO  3 CVT  4 ROUGHNESS
 *  5 FUEL  6 GFORCE  7 TIMING  8 IGNITION  9 CAM/VVT  10 SESSION
 */
public class DashView extends SurfaceView implements SurfaceHolder.Callback {

    // ── Logical canvas size (matches firmware) ───────────────────
    static final int LW = 320, LH = 480;

    // ── State ────────────────────────────────────────────────────
    private volatile int   pageIdx   = 0;
    private volatile int   themeIdx  = 0;
    private volatile boolean driveOn  = false;
    private volatile boolean autoScr  = false;
    private volatile boolean bootOn   = true;
    private volatile boolean bootDone = false;
    private volatile boolean alertOn  = false;
    private volatile String  alertMsg = "";
    private volatile String  alertSub = "";
    private volatile String  alertVal = "";
    private volatile String  alertSev = "red";

    // ── Engine animation ─────────────────────────────────────────
    private float  engAngle = 0f;
    private long   engLastMs = 0;
    private float  engRpmSmooth = 820f;
    private static final float[] CYL_PH = {0f, (float)(Math.PI*1.5), (float)Math.PI, (float)(Math.PI*0.5)};
    private static final float[] FA20_PHASE = {0f, (float)Math.PI, (float)(Math.PI*0.5), (float)(Math.PI*1.5)};

    // ── G-force ──────────────────────────────────────────────────
    private float gLong = 0.01f, gLat = 0f, gSmooth = 0f;
    private final float[] gTrailX = new float[28];
    private final float[] gTrailY = new float[28];
    private int   gTrailLen = 0;

    // ── Throttle display ─────────────────────────────────────────
    private float thrV = 1.2f;

    // ── Auto-scroll ──────────────────────────────────────────────
    private long  autoLastMs  = 0;
    private static final long AUTO_INTERVAL = 4000;

    // ── Boot ─────────────────────────────────────────────────────
    private long   bootStartMs = 0;
    private int    bootLinesShown = 0;
    private final String[] BOOT_CHECKS = {
        "MCU INIT ............... PASS",
        "DISPLAY INIT ........... ILI9488 (SIM)",
        "BLUETOOTH INIT ......... SPP",
        "CONNECTING ............. OBDII",
        "ECU HANDSHAKE .......... FA20DIT",
        "TCU HANDSHAKE .......... CVT TR690",
        "CALIBRATION DATA ....... LOADED",
        "WARMUP STATUS .......... MONITORING",
        "RENDER ENGINE .......... V4.0"
    };

    // ── History buffers ──────────────────────────────────────────
    private final float[][] hist = new float[8][80];

    // ── Render thread ────────────────────────────────────────────
    private RenderThread renderThread;
    private final Object  renderLock = new Object();

    // ── Paint objects (pre-allocated) ────────────────────────────
    private final Paint fillP  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokeP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textP  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF  arcRect = new RectF();

    // ── Pre-allocated reusable objects (avoid per-frame alloc) ───
    private final android.graphics.Path sparkPath    = new android.graphics.Path();
    private final android.graphics.Path alertTriPath = new android.graphics.Path();
    // Scanline overlay bitmap — built once in surfaceChanged, drawn as single blit
    private Bitmap scanlineBitmap;
    private final Paint bitmapPaint = new Paint();

    // ── Fonts ────────────────────────────────────────────────────
    private Typeface monoFace;
    private Typeface orbFace;

    // ── Scale (logical→screen) ───────────────────────────────────
    private float scaleF = 1f;
    private float offsetX = 0f, offsetY = 0f;

    // ── OBD reference ────────────────────────────────────────────
    private OBDManager obdManager;
    public void setOBDManager(OBDManager m) { this.obdManager = m; }

    // ── Themes ───────────────────────────────────────────────────
    static class Theme {
        String name;
        int bg,panel,accent,adim,border,label,unit,dim,
            green,yellow,orange,red,blue,cyan,purple,white;
        boolean scan;
        Theme(String n,int bg,int pan,int acc,int adim,int bord,int lbl,int unt,int dim,
              int gr,int ye,int or,int re,int bl,int cy,int pu,int wh,boolean sc){
            name=n;this.bg=bg;panel=pan;accent=acc;this.adim=adim;border=bord;
            label=lbl;unit=unt;this.dim=dim;green=gr;yellow=ye;orange=or;red=re;
            blue=bl;cyan=cy;purple=pu;white=wh;scan=sc;
        }
    }
    private static final Theme[] THEMES = {
        new Theme("HUD TEAL",0xFF04080E,0xFF0A1520,0xFF00C8DC,0xFF002832,0xFF1A3C50,
            0xFF6AAABB,0xFF4A7E8E,0xFF070C14,0xFF00E870,0xFFFFD800,0xFFFF7E00,
            0xFFFF2020,0xFF2070FF,0xFF00E0FF,0xFF8040FF,0xFFE4F0F4,true),
        new Theme("AMBER",0xFF070500,0xFF0E0900,0xFFD49000,0xFF382000,0xFF4A2E00,
            0xFFC8922A,0xFF907020,0xFF0A0700,0xFFBCDC00,0xFFFFD200,0xFFFF6800,
            0xFFFF2800,0xFFA07800,0xFFFFC000,0xFFC06800,0xFFFFF0D0,false),
        new Theme("RACING RED",0xFF060100,0xFF0E0303,0xFFE01010,0xFF380606,0xFF502020,
            0xFFC06060,0xFF904040,0xFF0A0202,0xFF20E040,0xFFFFE000,0xFFFF8C00,
            0xFFFF1010,0xFF3838FF,0xFFFF5050,0xFFFF0070,0xFFFFFFFF,false),
        new Theme("MATRIX",0xFF000400,0xFF000800,0xFF00CC3A,0xFF002C10,0xFF004A1A,
            0xFF00A040,0xFF007030,0xFF000500,0xFF00FF50,0xFFAAFF00,0xFF88DD00,
            0xFFFF2828,0xFF00A0FF,0xFF00FFB4,0xFF00FFAA,0xFF00FF50,true),
        new Theme("NEON PURPLE",0xFF050008,0xFF0A0014,0xFFA030FF,0xFF260040,0xFF3A1068,
            0xFF9050D0,0xFF6830A0,0xFF070010,0xFF00FFB0,0xFFFFD000,0xFFFF5C00,
            0xFFFF1840,0xFF1C5CFF,0xFF00C8FF,0xFFCC50FF,0xFFF0E8FF,true),
        new Theme("STEALTH",0xFF07090C,0xFF101520,0xFF86A8BC,0xFF182030,0xFF304858,
            0xFF6A8EA0,0xFF4A6878,0xFF0C1018,0xFF56A878,0xFFB89830,0xFFB86030,
            0xFFC03030,0xFF3868B0,0xFF56A0BC,0xFF7858A0,0xFFD4E2EC,false),
    };

    // ── Page / PID definitions ────────────────────────────────────
    static class PidDef {
        String lbl, unit; float mn, mx; int dec;
        int[] bandLo; int[] bandHi; String[] bandCol;
        // band arrays: parallel arrays for colour banding
        PidDef(String l,String u,float mn,float mx,int dec,
               float[] blo,float[] bhi,String[] bc){
            lbl=l;unit=u;this.mn=mn;this.mx=mx;this.dec=dec;
            this.bandLo=toIntArr(blo);this.bandHi=toIntArr(bhi);this.bandCol=bc;
        }
        static int[] toIntArr(float[] f){int[]a=new int[f.length];for(int i=0;i<f.length;i++)a[i]=(int)f[i];return a;}
        float[] bandLoF(){ float[]r=new float[bandLo.length];for(int i=0;i<bandLo.length;i++)r[i]=bandLo[i];return r;}
        float[] bandHiF(){ float[]r=new float[bandHi.length];for(int i=0;i<bandHi.length;i++)r[i]=bandHi[i];return r;}
    }

    static class PageDef {
        String cat, sub, type; int heroIdx;
        PidDef[] pids;
        PageDef(String cat,String sub,String type,int hi,PidDef...pids){
            this.cat=cat;this.sub=sub;this.type=type;heroIdx=hi;this.pids=pids;
        }
    }

    private static final PageDef[] PAGES = buildPages();
    private static PageDef[] buildPages() {
        // Helper lambdas would need API 24+ — use static methods instead
        return new PageDef[]{
            // ── Page 0: ENGINE ────────────────────────────────────────
            new PageDef("ENGINE","PERFORMANCE","arc",0,
                pid("ENGINE RPM","RPM",0,7000,0, fl(0,800,5000,6500),fl(800,5000,6500,9e9f),cs("blue","green","yellow","red")),
                pid("ENGINE LOAD","%",0,100,1,   fl(0,40,70,90),fl(40,70,90,9e9f),cs("green","yellow","orange","red")),
                pid("PEDAL POS","%",0,100,1,     fl(0,80,95),fl(80,95,9e9f),cs("green","yellow","red")),
                pid("IGN TIMING","°",-20,45,1,   fl(-20,0,10,25,35),fl(0,10,25,35,9e9f),cs("red","orange","green","yellow","cyan"))),
            // ── Page 1: TEMPERATURES ─────────────────────────────────
            new PageDef("TEMPERATURES","THERMAL MAP","arc",0,
                pid("COOLANT TEMP","°F",32,266,0, fl(32,140,210,239),fl(140,210,239,9e9f),cs("blue","green","yellow","red")),
                pid("OIL TEMP","°F",32,266,0,     fl(32,158,228,248),fl(158,228,248,9e9f),cs("blue","green","yellow","red")),
                pid("CVT TEMP","°F",32,266,0,     fl(32,122,200,230),fl(122,200,230,9e9f),cs("blue","green","yellow","red")),
                pid("CAT TEMP","°F",300,1800,0,   fl(300,800,1300,1600),fl(800,1300,1600,9e9f),cs("blue","green","yellow","red"))),
            // ── Page 2: BOOST / TURBO ────────────────────────────────
            new PageDef("BOOST","TURBO · BOOST · WASTEGATE","arc",0,
                pid("BOOST","PSI",-12,22,1,       fl(-12,-1,1,16,20),fl(-1,1,16,20,9e9f),cs("blue","cyan","green","yellow","red")),
                pid("TURBO RPM","RPM",0,250000,0, fl(0,50000,150000,200000),fl(50000,150000,200000,9e9f),cs("blue","green","yellow","red")),
                pid("WASTEGATE","%",0,100,1,       fl(0,20,60,85),fl(20,60,85,9e9f),cs("blue","cyan","green","red")),
                pid("TGT BOOST","PSI",-2,25,1,    fl(-2,0,14,20),fl(0,14,20,9e9f),cs("cyan","green","yellow","red"))),
            // ── Page 3: CVT ──────────────────────────────────────────
            new PageDef("CVT","TRANSMISSION · RATIO","arc",0,
                pid("GEAR RATIO","x:1",0f,6f,2,  fl(0,1.5f,3.5f,5f),fl(1.5f,3.5f,5f,9e9f),cs("green","cyan","yellow","red")),
                pid("CVT TEMP","°F",32,266,0,      fl(32,122,200,230),fl(122,200,230,9e9f),cs("blue","green","yellow","red")),
                pid("LOCK-UP DUTY","%",0,100,0,   fl(0,5,55),fl(5,55,9e9f),cs("blue","yellow","green")),
                pid("TRUE SLIP","%",0,25,2,        fl(0,3,8,15),fl(3,8,15,9e9f),cs("green","cyan","yellow","red"))),
            // ── Page 4: ROUGHNESS ────────────────────────────────────
            new PageDef("ROUGHNESS","CYL MONITORS","cylinder",-1,
                pid("CYL 1","",0,100,1, fl(0,10,30),fl(10,30,9e9f),cs("green","yellow","red")),
                pid("CYL 2","",0,100,1, fl(0,10,30),fl(10,30,9e9f),cs("green","yellow","red")),
                pid("CYL 3","",0,100,1, fl(0,10,30),fl(10,30,9e9f),cs("green","yellow","red")),
                pid("CYL 4","",0,100,1, fl(0,10,30),fl(10,30,9e9f),cs("green","yellow","red"))),
            // ── Page 5: G-FORCE ──────────────────────────────────────
            new PageDef("G-FORCE","DYNAMICS","gforce",0,
                pid("ACCEL (LONG)","G",-1.2f,1.2f,3, fl(-0.15f,0.15f,0.4f),fl(0.15f,0.4f,9e9f),cs("cyan","green","yellow")),
                pid("VEHICLE SPEED","MPH",0,100,0,   fl(0,75,90),fl(75,90,9e9f),cs("green","yellow","red")),
                pid("ENGINE RPM","RPM",0,7000,0,     fl(0,800,5000,6500),fl(800,5000,6500,9e9f),cs("blue","green","yellow","red")),
                pid("ENGINE LOAD","%",0,100,1,       fl(0,40,70,90),fl(40,70,90,9e9f),cs("green","yellow","orange","red"))),
            // ── Page 6: TIMING ───────────────────────────────────────
            new PageDef("TIMING","KNOCK · ADVANCE · DAM","arc",0,
                pid("IGN TIMING","°",-15,45,1,  fl(-15,0,8,20,35),fl(0,8,20,35,9e9f),cs("red","orange","yellow","green","cyan")),
                pid("KNOCK CORR","°",-6,0,2,    fl(-6,-3,-1.5f,-0.5f),fl(-3,-1.5f,-0.5f,9e9f),cs("red","orange","yellow","green")),
                pid("FINE KNOCK","°",-6,0,2,    fl(-6,-3,-1.5f,-0.5f),fl(-3,-1.5f,-0.5f,9e9f),cs("red","orange","yellow","green")),
                pid("DAM RATIO","",0f,1f,2,      fl(0f,0.7f,0.9f),fl(0.7f,0.9f,9e9f),cs("red","orange","green"))),
            // ── Page 7: SESSION (last) ──────────────────────────────
            new PageDef("SESSION","PEAK VALUES","session",-1,
                pid("BOOST","PSI",-12,22,2,  fl(0),fl(9e9f),cs("cyan")),
                pid("RPM","RPM",0,7000,0,    fl(0),fl(9e9f),cs("green")),
                pid("TIMING","°",-15,45,1,   fl(0),fl(9e9f),cs("yellow")),
                pid("LOAD","%",0,100,1,      fl(0),fl(9e9f),cs("orange")),
                pid("SPEED","MPH",0,100,0,   fl(0),fl(9e9f),cs("blue")),
                pid("KNOCK","°",-6,0,2,      fl(0),fl(9e9f),cs("red")),
                pid("MAF","G/S",0,200,1,     fl(0),fl(9e9f),cs("purple")),
                pid("HP","HP",0,250,0,       fl(0),fl(9e9f),cs("accent")),
                pid("CAT","°F",300,1800,0,   fl(0),fl(9e9f),cs("orange"))),
        };
    }
    private static PidDef pid(String l,String u,float mn,float mx,int dec,float[]blo,float[]bhi,String[]bc){
        return new PidDef(l,u,mn,mx,dec,blo,bhi,bc);
    }
    private static float[] fl(float...v){return v;}
    private static String[] cs(String...v){return v;}

    // ── Constructor ───────────────────────────────────────────────

    public DashView(Context ctx) {
        super(ctx);
        getHolder().addCallback(this);
        setFocusable(true);
        loadFonts(ctx);
        scheduleBootDone();
    }

    private void loadFonts(Context ctx) {
        try {
            orbFace  = Typeface.createFromAsset(ctx.getAssets(), "fonts/Orbitron-Bold.ttf");
        } catch (Exception e) { orbFace = Typeface.MONOSPACE; }
        try {
            monoFace = Typeface.createFromAsset(ctx.getAssets(), "fonts/ShareTechMono-Regular.ttf");
        } catch (Exception e) { monoFace = Typeface.MONOSPACE; }
    }

    private void scheduleBootDone() {
        bootStartMs = System.currentTimeMillis();
        new Thread(() -> {
            try {
                Thread.sleep(200);
                for (int i = 0; i < BOOT_CHECKS.length; i++) {
                    bootLinesShown = i + 1;
                    if (i == 3) {
                        // "CONNECTING" line — wait for real BT connection, max 10 s
                        long deadline = System.currentTimeMillis() + 10_000L;
                        while (!DashData.get().connected && System.currentTimeMillis() < deadline) {
                            Thread.sleep(100);
                        }
                        Thread.sleep(200); // brief pause so line is readable
                    } else {
                        Thread.sleep(i < 3 ? 220 : 280);
                    }
                }
                bootDone = true;
                Thread.sleep(1200);
                bootOn = false;
            } catch (Exception ignored) {}
        }).start();
    }

    // ── Public controls (called by MainActivity) ──────────────────

    public void nextPage()       { pageIdx = (pageIdx + 1) % PAGES.length; DashData.get().activePage = pageIdx; }
    public void prevPage()       { pageIdx = (pageIdx - 1 + PAGES.length) % PAGES.length; DashData.get().activePage = pageIdx; }
    public void nextTheme()      { themeIdx = (themeIdx + 1) % THEMES.length; }
    public void prevTheme()      { themeIdx = (themeIdx - 1 + THEMES.length) % THEMES.length; }
    public void toggleDriveMode(){ driveOn = !driveOn; }
    public void toggleAutoScroll(){ autoScr = !autoScr; }
    public void dismissAlert()   { alertOn = false; }
    public void triggerKnockAlert() {
        DashData d = DashData.get();
        alertMsg = "SIGNIFICANT KNOCK"; alertSev = d.knockCorr < -4f ? "red" : "orange";
        alertVal = String.format("%.2f°", d.knockCorr); alertSub = "ECU RETARDING TIMING >2.5°";
        alertOn = true;
    }

    private void triggerAlert(String msg, String sub, String val, String sev) {
        alertMsg = msg; alertSub = sub; alertVal = val; alertSev = sev; alertOn = true;
    }

    // ── SurfaceHolder.Callback ────────────────────────────────────

    @Override public void surfaceCreated(SurfaceHolder h) {
        renderThread = new RenderThread(h);
        renderThread.setRunning(true);
        renderThread.start();
        if (obdManager != null) obdManager.start();
    }

    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {
        scaleF  = (float) w / LW;
        offsetX = 0f;
        offsetY = (hh - LH * scaleF) / 2f;
        buildScanlineBitmap();
    }

    private void buildScanlineBitmap() {
        // Build a 320x480 bitmap with alternating transparent/semi-black rows.
        // Drawing this once per frame is a single GPU blit instead of 240 drawRect calls.
        scanlineBitmap = Bitmap.createBitmap(LW, LH, Bitmap.Config.ARGB_8888);
        Canvas bc = new Canvas(scanlineBitmap);
        Paint sp = new Paint();
        sp.setColor(0x11000000);
        sp.setStyle(Paint.Style.FILL);
        for (int y = 0; y < LH; y += 2) bc.drawRect(0, y, LW, y + 1, sp);
    }

    @Override public void surfaceDestroyed(SurfaceHolder h) {
        if (renderThread != null) { renderThread.setRunning(false); }
        if (obdManager != null)   { obdManager.stop(); }
    }

    // ── Render thread — 60fps sleep loop ────────────────────────

    private class RenderThread extends Thread {
        private final SurfaceHolder holder;
        private volatile boolean running = false;
        RenderThread(SurfaceHolder h) { holder = h; setDaemon(true); setName("Dash-Render"); }
        void setRunning(boolean r) { running = r; }

        @Override public void run() {
            final long TARGET_MS = 16; // 60fps
            while (running) {
                long frameStart = System.currentTimeMillis();
                Canvas c = null;
                try {
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= 26) {
                            c = holder.lockHardwareCanvas();
                        }
                    } catch (Exception ignored) { c = null; }
                    if (c == null) c = holder.lockCanvas();
                    if (c != null) {
                        c.save();
                        c.translate(offsetX, offsetY);
                        c.scale(scaleF, scaleF);
                        renderFrame(c);
                        c.restore();
                    }
                } catch (Exception e) {
                    android.util.Log.e("DashView", "Render error: " + e.getMessage());
                } finally {
                    try { if (c != null) holder.unlockCanvasAndPost(c); }
                    catch (Exception ignored) {}
                }
                long sleep = TARGET_MS - (System.currentTimeMillis() - frameStart);
                if (sleep > 0) try { Thread.sleep(sleep); } catch (Exception ignored) {}
            }
        }
    }

    // ── Frame entry ───────────────────────────────────────────────

    private void renderFrame(Canvas c) {
        Theme t = THEMES[themeIdx];
        c.drawColor(t.bg);
        updateEngAngle();
        updateGForce();

        if (bootOn) { drawBoot(c, t); return; }
        if (driveOn) { drawDriveMode(c, t); return; }

        PageDef pg = PAGES[pageIdx];
        if ("cylinder".equals(pg.type)) {
            drawCylinderPage(c, t);
        } else if ("session".equals(pg.type)) {
            drawSessionPage(c, t);
        } else {
            drawStatusBar(c, t);
            drawHero(c, t, pg);
            if (!"gforce".equals(pg.type)) drawSideStrips(c, t);
            drawSparkline(c, t, pg);
            hline(c, t, 215);
            drawPageHeader(c, t, pg);
            drawCards(c, t, pg);

        }

        // Page dot indicator — drawn last so it's always on top of page content
        if (!bootOn && !driveOn) drawPageDots(c, t);

        if (alertOn) drawAlert(c, t);

        // Auto-scroll
        if (autoScr && !driveOn && !bootOn && !alertOn) {
            long now = System.currentTimeMillis();
            if (now - autoLastMs > AUTO_INTERVAL) { nextPage(); autoLastMs = now; }
        }

        // Auto-trigger alerts from live data — priority-ordered, one at a time
        DashData d = DashData.get();
        if (!alertOn && d.connected) {
            if (d.knockCorr < -2.5f) {
                triggerKnockAlert();
            } else if (d.coolantF() > 225f) {
                triggerAlert("COOLANT OVERHEAT", "ENGINE TEMP CRITICAL — STOP SAFELY",
                    String.format("%.0f°F", d.coolantF()), "red");
            } else if (d.cvtTempF() > 230f) {
                triggerAlert("CVT OVERHEAT", "CVT FLUID TEMP CRITICAL — REDUCE LOAD",
                    String.format("%.0f°F", d.cvtTempF()), "red");
            } else if (d.oilTempF() > 260f) {
                triggerAlert("OIL OVERHEAT", "ENGINE OIL TEMP CRITICAL",
                    String.format("%.0f°F", d.oilTempF()), "red");
            } else if (d.boostPsi() > 20f) {
                triggerAlert("BOOST SPIKE", "BOOST PRESSURE EXCEEDED SAFE LIMIT",
                    String.format("+%.1f PSI", d.boostPsi()), "red");
            } else if (d.battV > 5f && d.battV < 11.5f) {
                triggerAlert("LOW VOLTAGE", "CHARGING SYSTEM FAILURE",
                    String.format("%.1fV", d.battV), "orange");
            } else if (d.catTempF() > 1600f && d.catTempF() < 2000f) {
                triggerAlert("CAT OVERHEAT", "CATALYST TEMP CRITICAL",
                    String.format("%.0f°F", d.catTempF()), "orange");
            }
        }

        pushHistory();
        d.updatePeaks();
        if (t.scan) scanlines(c);
    }

    // ── Engine angle update ────────────────────────────────────────

    private void updateEngAngle() {
        long now = System.currentTimeMillis();
        if (engLastMs == 0) { engLastMs = now; return; }
        float dt = (now - engLastMs) / 1000f;
        engLastMs = now;
        dt = Math.min(dt, 0.050f);
        float rpm = DashData.get().rpm;
        // Stop animation when engine is not running or data not yet received
        if (Float.isNaN(rpm) || rpm < 300f) {
            engRpmSmooth = 0f;
            return;
        }
        engRpmSmooth += (rpm - engRpmSmooth) * Math.min(1f, dt * 4f);
        engAngle += engRpmSmooth / 60f * (float)(Math.PI * 2) * dt * 0.25f;
    }

    // ── G-force update ────────────────────────────────────────────

    private void updateGForce() {
        DashData d = DashData.get();
        // Estimate longitudinal G from speed delta (placeholder — real app would use accelerometer)
        // For now just use a simple proxy from throttle/load
        float pedal = Float.isNaN(d.pedalPct) ? 0f : d.pedalPct;
        float targetG = (pedal / 100f * 0.4f) - 0.05f;
        gLong += (targetG - gLong) * 0.12f;
        gSmooth += (gLong - gSmooth) * 0.15f;
        // Shift trail
        if (gTrailLen < 28) {
            gTrailX[gTrailLen] = gLat;
            gTrailY[gTrailLen] = gLong;
            gTrailLen++;
        } else {
            System.arraycopy(gTrailX, 1, gTrailX, 0, 27);
            System.arraycopy(gTrailY, 1, gTrailY, 0, 27);
            gTrailX[27] = gLat; gTrailY[27] = gLong;
        }
        thrV = d.pedalPct;
    }

    // ── History buffer ────────────────────────────────────────────

    private void pushHistory() {
        DashData d = DashData.get();
        float[][] v = getPageVals(d);
        for (int p = 0; p < Math.min(v.length, hist.length); p++) {
            if (v[p] != null && v[p].length > 0) {
                System.arraycopy(hist[p], 1, hist[p], 0, 79);
                hist[p][79] = v[p][0];
            }
        }
    }

    // ── Live data for all pages ───────────────────────────────────

    private float[][] getPageVals(DashData d) {
        return new float[][]{
            {d.rpm, d.loadPct, d.pedalPct, d.timingDeg},                                  // 0 ENGINE
            {d.coolantF(), d.oilTempF(), d.cvtTempF(), d.catTempF()},                     // 1 TEMPS
            {d.boostPsi(), d.turboSpeedRpm, d.wastegatePct, d.targetBoostPsi},             // 2 BOOST/TURBO
            {d.gearRatioAct, d.cvtTempF(), d.lockupPct, d.cvtSlipPct()},                  // 3 CVT
            {d.rough1, d.rough2, d.rough3, d.rough4},                                     // 4 ROUGHNESS
            {gLong, d.speedMph(), d.rpm, d.loadPct},                                      // 5 G-FORCE
            {d.timingDeg, d.knockCorr, d.fineKnockDeg, d.damRatio},                       // 6 TIMING
            {d.peakBoostPsi, d.peakRpm, d.peakTimingDeg, d.peakLoadPct,                   // 7 SESSION
             d.peakSpeedMph, d.worstKnockCorr, d.peakMafGs, d.peakEstHp, d.peakCatTempF},
        };
    }

    private float[] pageVals(int pidx) {
        float[][] all = getPageVals(DashData.get());
        if (pidx < all.length) return all[pidx];
        return new float[]{0,0,0,0};
    }

    // ── Draw helpers ──────────────────────────────────────────────

    private void sf(float sz, boolean bold, boolean orb) {
        textP.setTypeface(orb ? orbFace : monoFace);
        textP.setFakeBoldText(bold);
        textP.setTextSize(sz);
    }

    private void hline(Canvas c, Theme t, float y) {
        strokeP.setColor(t.border); strokeP.setStyle(Paint.Style.STROKE); strokeP.setStrokeWidth(1f);
        c.drawLine(0, y, LW, y, strokeP);
    }

    private int bandColor(PidDef pid, float v, Theme t) {
        if (Float.isNaN(v)) return ac(t.label, 0.35f);
        for (int i = 0; i < pid.bandLo.length; i++) {
            if (v >= pid.bandLo[i] && v < pid.bandHi[i]) return themeColor(t, pid.bandCol[i]);
        }
        return themeColor(t, pid.bandCol[pid.bandCol.length-1]);
    }

    private int themeColor(Theme t, String name) {
        switch(name){
            case "green":  return t.green;  case "yellow": return t.yellow;
            case "orange": return t.orange; case "red":    return t.red;
            case "blue":   return t.blue;   case "cyan":   return t.cyan;
            case "purple": return t.purple; case "white":  return t.white;
            case "accent": return t.accent; default:       return t.accent;
        }
    }

    private float nrm(PidDef pid, float v) {
        if (Float.isNaN(v)) return 0f;
        return Math.max(0f, Math.min(1f, (v - pid.mn) / (pid.mx - pid.mn)));
    }

    private String fmtV(float v, int dec) {
        if (Float.isNaN(v)) return "---";
        if (dec == 0) return String.valueOf(Math.round(v));
        return String.format("%." + dec + "f", v);
    }

    /** alpha blended color */
    private int ac(int color, float a) {
        int al = (int)(a * 255); al = Math.max(0, Math.min(255, al));
        return (color & 0x00FFFFFF) | (al << 24);
    }

    /** Draw filled rect with alpha */
    private void fillRect(Canvas c, float x, float y, float w, float h, int color, float a) {
        fillP.setStyle(Paint.Style.FILL);
        fillP.setColor(ac(color, a));
        c.drawRect(x, y, x+w, y+h, fillP);
    }

    /** Stroke rect with alpha */
    private void strokeRect(Canvas c, float x, float y, float w, float h, int color, float a, float lw) {
        strokeP.setStyle(Paint.Style.STROKE);
        strokeP.setColor(ac(color, a));
        strokeP.setStrokeWidth(lw);
        c.drawRect(x, y, x+w, y+h, strokeP);
    }

    /** C++ glow dot — 3 concentric filled circles */
    private void glowDot(Canvas c, float x, float y, float r, int col) {
        fillP.setStyle(Paint.Style.FILL);
        fillP.setColor(ac(col, 0.08f)); c.drawCircle(x, y, r+7, fillP);
        fillP.setColor(ac(col, 0.18f)); c.drawCircle(x, y, r+4, fillP);
        fillP.setColor(ac(col, 0.42f)); c.drawCircle(x, y, r+2, fillP);
        fillP.setColor(col);               c.drawCircle(x, y, r,   fillP);
    }

    /** C++ glow arc — 3 concentric strokes */
    private void glowArc(Canvas c, RectF rect, float startDeg, float sweepDeg, int col, float lw) {
        strokeP.setStyle(Paint.Style.STROKE); strokeP.setStrokeCap(Paint.Cap.ROUND);
        strokeP.setColor(ac(col, 0.10f)); strokeP.setStrokeWidth(lw+10); c.drawArc(rect, startDeg, sweepDeg, false, strokeP);
        strokeP.setColor(ac(col, 0.22f)); strokeP.setStrokeWidth(lw+5);  c.drawArc(rect, startDeg, sweepDeg, false, strokeP);
        strokeP.setColor(ac(col, 0.50f)); strokeP.setStrokeWidth(lw+2);  c.drawArc(rect, startDeg, sweepDeg, false, strokeP);
        strokeP.setColor(col);               strokeP.setStrokeWidth(lw);     c.drawArc(rect, startDeg, sweepDeg, false, strokeP);
        strokeP.setStrokeCap(Paint.Cap.BUTT);
    }

    /** C++ glow rect */
    private void glowRect(Canvas c, float x, float y, float w, float h, int col, float alpha) {
        strokeP.setStyle(Paint.Style.STROKE);
        strokeP.setColor(ac(col, alpha*0.12f)); strokeP.setStrokeWidth(6); c.drawRect(x-3,y-3,x+w+3,y+h+3, strokeP);
        strokeP.setColor(ac(col, alpha*0.28f)); strokeP.setStrokeWidth(3); c.drawRect(x-1,y-1,x+w+1,y+h+1, strokeP);
        strokeP.setColor(ac(col, alpha));       strokeP.setStrokeWidth(1); c.drawRect(x,  y,  x+w,  y+h,   strokeP);
    }

    /** Corner brackets */
    private void cbrk(Canvas c, float x, float y, float w, float h, int col, float sz) {
        strokeP.setStyle(Paint.Style.STROKE); strokeP.setColor(col); strokeP.setStrokeWidth(1f);
        float[][] corners = {{x,y,sz,sz},{x+w,y,-sz,sz},{x,y+h,sz,-sz},{x+w,y+h,-sz,-sz}};
        for (float[] cr : corners) {
            c.drawLine(cr[0]+cr[2], cr[1], cr[0], cr[1], strokeP);
            c.drawLine(cr[0], cr[1], cr[0], cr[1]+cr[3], strokeP);
        }
    }

    /** 1-px text shadow — draw in bg first, then bright */
    private void textShadow(Canvas c, String text, float x, float y, int bgColor, int fgColor) {
        textP.setColor(bgColor); textP.setAlpha(180);
        c.drawText(text, x+1, y+1, textP);
        textP.setColor(fgColor); textP.setAlpha(255);
        c.drawText(text, x, y, textP);
    }

    private void scanlines(Canvas c) {
        try {
            if (scanlineBitmap != null) c.drawBitmap(scanlineBitmap, 0, 0, bitmapPaint);
        } catch (Exception ignored) {} // hardware canvas may reject software bitmap
    }

    // ── ARC GAUGE ─────────────────────────────────────────────────

    private static final float AG_CX=160, AG_CY=118, AG_R=82, AG_TW=11;
    // Android arc: 0° = 3 o'clock. Gauge goes from 225° (7 o'clock) to 315° sweeping 270°
    private static final float AG_START = 135f, AG_SWEEP = 270f;

    private void drawArcGauge(Canvas c, Theme t, PidDef pid, float v) {
        boolean noData = Float.isNaN(v);
        float nv = noData ? 0f : nrm(pid, v);
        int col = noData ? ac(t.label, 0.4f) : bandColor(pid, v, t);
        float r = AG_R;
        arcRect.set(AG_CX-r, AG_CY-r, AG_CX+r, AG_CY+r);

        // Track
        strokeP.setStyle(Paint.Style.STROKE); strokeP.setColor(t.border);
        strokeP.setStrokeWidth(AG_TW); strokeP.setStrokeCap(Paint.Cap.BUTT);
        c.drawArc(arcRect, AG_START, AG_SWEEP, false, strokeP);

        // Band tints (skip when no data so gauge looks clearly inactive)
        if (!noData) {
            for (int i = 0; i < pid.bandLo.length; i++) {
                float bl = Math.max(pid.mn, pid.bandLo[i]);
                float bh = Math.min(pid.mx, pid.bandHi[i]);
                if (bl >= bh) continue;
                float a1 = AG_START + ((bl-pid.mn)/(pid.mx-pid.mn))*AG_SWEEP;
                float sw = ((bh-bl)/(pid.mx-pid.mn))*AG_SWEEP;
                strokeP.setColor(ac(themeColor(t, pid.bandCol[i]), 0.16f));
                strokeP.setStrokeWidth(AG_TW);
                c.drawArc(arcRect, a1, sw, false, strokeP);
            }
            // Value arc
            if (nv > 0.005f) glowArc(c, arcRect, AG_START, nv*AG_SWEEP, col, AG_TW);
        }

        // Ticks
        for (int i = 0; i <= 20; i++) {
            double a = Math.toRadians(AG_START + (i/20.0)*AG_SWEEP);
            boolean maj = (i%4==0);
            float r1 = r - AG_TW/2f - 1, r2 = r1 - (maj?7:3.5f);
            strokeP.setColor(maj ? ac(t.label, noData?0.3f:0.7f) : ac(t.border,0.6f));
            strokeP.setStrokeWidth(maj ? 1.2f : 0.6f);
            c.drawLine((float)(AG_CX+r1*Math.cos(a)), (float)(AG_CY+r1*Math.sin(a)),
                       (float)(AG_CX+r2*Math.cos(a)), (float)(AG_CY+r2*Math.sin(a)), strokeP);
        }

        if (!noData) {
            // End dot
            double fillRad = Math.toRadians(AG_START + nv*AG_SWEEP);
            glowDot(c, (float)(AG_CX+r*Math.cos(fillRad)), (float)(AG_CY+r*Math.sin(fillRad)), 5f, col);
        }

        // Min/Max labels
        double saRad = Math.toRadians(AG_START);
        double eaRad = Math.toRadians(AG_START + AG_SWEEP);
        sf(8, false, false);
        textP.setColor(ac(t.label, noData?0.3f:0.9f)); textP.setTextAlign(Paint.Align.RIGHT);
        c.drawText(fmtV(pid.mn,0), (float)(AG_CX+(r+15)*Math.cos(saRad)), (float)(AG_CY+(r+15)*Math.sin(saRad)), textP);
        textP.setTextAlign(Paint.Align.LEFT);
        c.drawText(fmtV(pid.mx,0), (float)(AG_CX+(r+15)*Math.cos(eaRad)), (float)(AG_CY+(r+15)*Math.sin(eaRad)), textP);

        // Centre value
        String vs = noData ? "---" : fmtV(v, pid.dec);
        float fs = vs.length()<=3?50: vs.length()<=5?40: vs.length()<=7?32:26;
        sf(fs, true, true);
        textP.setTextAlign(Paint.Align.CENTER);
        textShadow(c, vs, AG_CX, AG_CY+14, t.bg, noData ? ac(t.label, 0.5f) : t.white);

        // Unit (hide when no data)
        if (!noData) {
            sf(11, false, false);
            textP.setColor(t.unit); textP.setAlpha(255); textP.setTextAlign(Paint.Align.CENTER);
            c.drawText(pid.unit, AG_CX, AG_CY+31, textP);
        }
    }

    // ── G-METER ───────────────────────────────────────────────────

    private static final float GM_CX=160,GM_CY=118,GM_R=72,GM_MG=1.0f;

    private void drawGMeter(Canvas c, Theme t) {
        int col = bandColor(PAGES[5].pids[0], gLong, t);
        fillP.setStyle(Paint.Style.FILL); fillP.setColor(t.panel); fillP.setAlpha(255);
        c.drawCircle(GM_CX, GM_CY, GM_R, fillP);
        for (float g : new float[]{0.25f, 0.5f, 0.75f, 1.0f}) {
            float r = (g/GM_MG)*GM_R;
            strokeP.setStyle(Paint.Style.STROKE); strokeP.setStrokeWidth(g==1f?1.5f:0.7f);
            strokeP.setColor(ac(t.border, g==1f?0.85f:0.35f));
            c.drawCircle(GM_CX, GM_CY, r, strokeP);
        }
        strokeP.setStyle(Paint.Style.STROKE); strokeP.setColor(ac(t.border,0.35f)); strokeP.setStrokeWidth(1f);
        c.drawLine(GM_CX-GM_R,GM_CY,GM_CX+GM_R,GM_CY,strokeP);
        c.drawLine(GM_CX,GM_CY-GM_R,GM_CX,GM_CY+GM_R,strokeP);
        sf(8,false,false); textP.setColor(t.white); textP.setAlpha(255);
        textP.setTextAlign(Paint.Align.CENTER); c.drawText("ACCEL",GM_CX,GM_CY-GM_R-6,textP);
        c.drawText("BRAKE",GM_CX,GM_CY+GM_R+12,textP);
        // Trail
        for (int i = 0; i < gTrailLen; i++) {
            float frac = (float)i/gTrailLen, sz = Math.max(1f,frac*3f);
            fillP.setColor(ac(col, frac*0.45f)); fillP.setStyle(Paint.Style.FILL);
            float px = GM_CX+(gTrailX[i]/GM_MG)*GM_R, py = GM_CY-(gTrailY[i]/GM_MG)*GM_R;
            c.drawRect(px-sz, py-sz, px+sz, py+sz, fillP);
        }
        float dX = GM_CX+(gLat/GM_MG)*GM_R, dY = GM_CY-(gLong/GM_MG)*GM_R;
        glowDot(c, dX, dY, 7f, col);
        fillP.setColor(t.white); fillP.setAlpha(255);
        c.drawCircle(dX, dY, 3f, fillP);
        sf(38,true,true); textP.setTextAlign(Paint.Align.CENTER);
        textShadow(c, String.format("%.2f",Math.abs(gLong)), GM_CX, GM_CY+14, t.bg, t.white);
        sf(9,false,false); textP.setColor(t.unit); textP.setAlpha(255);
        c.drawText("G", GM_CX, GM_CY+30, textP);
    }

    // ── ENGINE MINI ───────────────────────────────────────────────

    private void drawEngMini(Canvas c, Theme t, float ox, float oy, float w, float h) {
        int nC=4; float cw=(w-2)/nC, topY=oy+2, stroke=(h-4)*0.55f, pisH=cw*0.45f;
        for (int i=0; i<nC; i++) {
            float cx=ox+1+i*cw+cw/2f, pos=(1-cos(engAngle+CYL_PH[i]))/2f, pisY=topY+pos*stroke;
            strokeP.setColor(t.adim); strokeP.setStyle(Paint.Style.STROKE); strokeP.setStrokeWidth(1f);
            c.drawRect(cx-cw/2+1, topY, cx+cw/2-1, topY+stroke+pisH, strokeP);
            fillRect(c, cx-cw/2+2, pisY, cw-4, pisH, t.accent, 1f);
            if (pos < 0.15f) { fillRect(c, cx-cw/2+2, topY, cw-4, 3f, t.yellow, (1-pos/0.15f)*0.55f); }
        }
    }

    // ── PAGE DOT INDICATOR ────────────────────────────────────────

    /** One dot per page, centred at y=456.  Active page = glowing accent dot;
     *  others = small dim squares.  Called by all non-drive page draw methods. */
    private void drawPageDots(Canvas c, Theme t) {
        fillRect(c, 0, 460, LW, 20, t.dim, 1f);
        int ndots = PAGES.length;
        float dotTot = ndots * 9f + (ndots - 1) * 4f;
        float sx = (LW - dotTot) / 2f;
        for (int i = 0; i < ndots; i++) {
            float dx = sx + i * 13f + 4.5f;
            if (i == pageIdx) glowDot(c, dx, 470, 3f, t.accent);
            else {
                fillP.setColor(ac(t.border, 0.65f));
                fillP.setStyle(Paint.Style.FILL);
                c.drawRect(dx - 2, 468, dx + 2, 472, fillP);
            }
        }
    }

    // ── STATUS BAR ────────────────────────────────────────────────

    private void drawStatusBar(Canvas c, Theme t) {
        fillRect(c, 0,0, LW,26, t.dim, 1f);
        DashData d = DashData.get();
        int dotCol = d.connected ? t.green : t.orange;
        glowDot(c, 10, 13, 4f, dotCol);
        sf(7,true,false); textP.setColor(t.white); textP.setAlpha(255);
        textP.setTextAlign(Paint.Align.LEFT);
        c.drawText(d.connected ? "OBD" : "NO OBD", 20, 17, textP);
        // Sparkline box
        fillRect(c, 48,4,50,18, t.bg, 1f);
        strokeRect(c, 48,4,50,18, t.border, 0.3f, 1f);
        // Mini engine
        drawEngMini(c, t, 102, 3, 36, 20);
        // Divider
        fillRect(c, 142, 4, 1, 18, t.border, 0.4f);
        // Knock
        float kc = d.knockCorr;
        sf(7, kc<-2.5f, false);
        textP.setColor(kc<-2.5f ? t.red : ac(t.label,180));
        textP.setTextAlign(Paint.Align.LEFT);
        c.drawText(String.format("KNK %.1f", kc), 147, 17, textP);
        // Auto badge
        fillRect(c, 194,4,1,18, t.border, 0.3f);
        if (autoScr) { sf(6,false,false); textP.setColor(t.accent); textP.setTextAlign(Paint.Align.LEFT); c.drawText("AUTO",198,17,textP); }
        // Page counter
        sf(7,true,false); textP.setColor(t.white); textP.setTextAlign(Paint.Align.LEFT);
        c.drawText((pageIdx+1)+"/"+PAGES.length, 228, 17, textP);
        // G force
        float mag = Math.abs(gSmooth);
        int gcol = mag<0.1f?t.cyan:mag<0.35f?t.green:mag<0.55f?t.yellow:t.red;
        sf(11,true,true); textP.setColor(gcol); textP.setTextAlign(Paint.Align.RIGHT);
        c.drawText((gSmooth>=0?"+":"")+String.format("%.2f",gSmooth)+"g", LW-4, 17, textP);
        hline(c, t, 26);
    }

    // ── SPARKLINE ─────────────────────────────────────────────────

    private void drawSparkline(Canvas c, Theme t, PageDef pg) {
        float[] buf = hist[pageIdx];
        float sx=10, sy=202, sw=300, sh=12;
        fillRect(c, sx,sy,sw,sh, t.dim, 1f);
        PidDef pid = "gforce".equals(pg.type) ? pg.pids[0] : pg.pids[pg.heroIdx];
        int col = bandColor(pid, buf[79], t);
        int n = (int) sw;
        // Area fill — single filled path instead of 300 individual rects
        fillP.setStyle(Paint.Style.FILL);
        fillP.setColor(ac(col, 0.16f));
        sparkPath.rewind();
        sparkPath.moveTo(sx, sy + sh);
        for (int i = 0; i < n; i++) {
            float v = buf[(int)(i * 80 / sw)];
            float bh = nrm(pid, v) * sh;
            sparkPath.lineTo(sx + i, sy + sh - bh);
        }
        sparkPath.lineTo(sx + n, sy + sh);
        sparkPath.close();
        c.drawPath(sparkPath, fillP);
        // Line — reuse same path, just stroke it
        strokeP.setColor(col); strokeP.setStyle(Paint.Style.STROKE); strokeP.setStrokeWidth(1f);
        sparkPath.rewind();
        for (int i = 0; i < n; i++) {
            float v = buf[(int)(i * 80 / sw)];
            float y = sy + sh - nrm(pid, v) * sh - 1;
            if (i == 0) sparkPath.moveTo(sx + i, y); else sparkPath.lineTo(sx + i, y);
        }
        c.drawPath(sparkPath, strokeP);
    }

    // ── PAGE HEADER ───────────────────────────────────────────────

    private void drawPageHeader(Canvas c, Theme t, PageDef pg) {
        fillRect(c, 0,216,LW,22, t.dim, 1f);
        fillRect(c, 0,216,3,22, t.accent, 1f);
        sf(11,true,true); textP.setColor(t.white); textP.setAlpha(255); textP.setTextAlign(Paint.Align.LEFT);
        c.drawText(pg.cat, 10, 231, textP);
        sf(8,false,false); textP.setColor(t.accent); textP.setTextAlign(Paint.Align.RIGHT);
        c.drawText(pg.sub, LW-6, 231, textP);
        hline(c,t,238);
    }

    // ── CARDS 2×2 ─────────────────────────────────────────────────

    private static final float[][] CP = {{5,239,153,116},{163,239,153,116},{5,359,153,95},{163,359,153,95}};

    private void drawCards(Canvas c, Theme t, PageDef pg) {
        float[] pv = pageVals(pageIdx);
        for (int i=0; i<4 && i<pg.pids.length; i++) {
            float cx=CP[i][0],cy=CP[i][1],cw=CP[i][2],ch=CP[i][3];
            PidDef pid = pg.pids[i];
            float v = i<pv.length ? pv[i] : Float.NaN;
            boolean noData = Float.isNaN(v);
            float nv = noData ? 0f : nrm(pid, v);
            int col = noData ? ac(t.label, 0.4f) : bandColor(pid, v, t);
            boolean isH = (i == pg.heroIdx);
            fillRect(c, cx,cy,cw,ch, t.panel, 1f);
            if (!noData) fillRect(c, cx,cy,(int)(nv*cw),ch, col, isH?0.13f:0.08f);
            fillRect(c, cx,cy,3,ch, col, 1f);
            if (isH) glowRect(c,cx,cy,cw,ch, col, noData?0.2f:0.5f);
            else strokeRect(c,cx,cy,cw,ch, t.border, 0.4f, 1f);
            cbrk(c, cx,cy,cw,ch, ac(isH?col:t.accent, 0.25f), 5);
            sf(9,true,false); textP.setColor(t.white); textP.setAlpha(255); textP.setTextAlign(Paint.Align.LEFT);
            c.drawText(pid.lbl, cx+8, cy+17, textP);
            String vs = noData ? "---" : fmtV(v, pid.dec);
            float fs = vs.length()<=3?30: vs.length()<=5?24: vs.length()<=7?20:15;
            sf(fs,true,true); textP.setTextAlign(Paint.Align.LEFT);
            textShadow(c, vs, cx+8, cy+60, t.bg, col);
            if (!noData) {
                float tw = textP.measureText(vs);
                sf(10,false,false); textP.setColor(ac(col,230)); textP.setAlpha(230);
                c.drawText(pid.unit, cx+8+tw+3, cy+56, textP);
            }
            // Bar
            float bx=cx+8,by=cy+74,bw=cw-16,bh=6;
            fillRect(c,bx,by,bw,bh, t.bg, 1f);
            if (!noData) fillRect(c,bx,by,(int)(nv*bw),bh, col, 1f);
            strokeRect(c,bx,by,bw,bh, t.border, 0.5f, 1f);
            sf(7,false,false); textP.setColor(ac(t.label,180));
            textP.setTextAlign(Paint.Align.LEFT); c.drawText(fmtV(pid.mn,0), bx, cy+90, textP);
            textP.setTextAlign(Paint.Align.RIGHT); c.drawText(fmtV(pid.mx,0), bx+bw, cy+90, textP);
        }
        fillRect(c, 0,355,LW,4, t.dim, 1f);
    }

    // ── BOTTOM BAR (kept for compatibility, no longer called in main flow) ────

    private void drawBottomBar(Canvas c, Theme t) {
        hline(c,t,435);
        // Throttle
        fillRect(c, 0,436,LW,15, t.dim, 1f);
        sf(7,false,false); textP.setColor(t.label); textP.setAlpha(255); textP.setTextAlign(Paint.Align.LEFT);
        c.drawText("THR", 5, 447, textP);
        int tc = thrV<50?t.green:thrV<85?t.yellow:t.red;
        fillRect(c, 28,441,252,5, t.bg, 1f);
        fillRect(c, 28,441,(int)(thrV/100f*252),5, tc, 1f);
        strokeRect(c, 28,441,252,5, t.border, 0.4f, 1f);
        // Page dots
        drawPageDots(c, t);
        // Mini stats strip
        fillRect(c, 0,461,LW,19, t.dim, 1f); hline(c,t,461);
        DashData d = DashData.get();
        float cool=d.coolantF(); boolean wdone=cool>=185f;
        float wpct=Math.min(1f,(cool-32f)/(185f-32f));
        int eta=(int)((1-wpct)*240); String etaS=wdone?"WARM":(eta>=60?eta/60+"m"+(eta%60)+"s":eta+"s");
        String[] slbls={"RPM","SPD","WRM"};
        float[] svals={d.rpm, d.speedMph(), wdone?1f:wpct};
        int[] scols={bandColor(PAGES[0].pids[0],d.rpm,t), bandColor(PAGES[5].pids[1],d.speedMph(),t), wdone?t.green:t.yellow};
        String[] sstrs={fmtV(d.rpm,0), fmtV(d.speedMph(),0), etaS};
        int tw2=LW/3;
        for (int i=0;i<3;i++) {
            int sx3=i*tw2;
            fillRect(c, sx3+(i>0?1:0),463, 2,14, scols[i], 1f);
            sf(7,true,false); textP.setColor(t.white); textP.setAlpha(255); textP.setTextAlign(Paint.Align.LEFT);
            c.drawText(slbls[i], sx3+6, 469, textP);
            sf(9,true,true); textP.setColor(scols[i]); textP.setTextAlign(Paint.Align.LEFT);
            c.drawText(sstrs[i], sx3+6, 478, textP);
            if (i<2) fillRect(c, sx3+tw2-1, 463, 1, 14, t.border, 0.3f);
        }
    }

    // ── HERO DISPATCH ─────────────────────────────────────────────

    private void drawHero(Canvas c, Theme t, PageDef pg) {
        if ("gforce".equals(pg.type)) { drawGMeter(c,t); return; }
        // Boost page (idx 2) gets a turbo compressor wheel behind the arc gauge
        if (pageIdx == 2) drawTurboWheel(c, t);
        PidDef pid = pg.pids[pg.heroIdx];
        float[] pv = pageVals(pageIdx);
        float v = pg.heroIdx < pv.length ? pv[pg.heroIdx] : 0;
        drawArcGauge(c, t, pid, v);
        sf(8,false,false); textP.setColor(ac(t.label,220)); textP.setTextAlign(Paint.Align.CENTER);
        c.drawText(pid.lbl, LW/2f, 196, textP);
    }

    // ── SIDE DATA STRIPS (flanking arc gauge) ─────────────────────

    /** Two narrow vertical strips on either side of the arc gauge showing always-on vitals.
     *  Left:  RPM (top) + Boost (bottom)
     *  Right: Coolant °F (top) + Oil °F (bottom)
     *  Each strip is 68 px wide — leaves a ~4 px gap before the arc track outer edge (~72 px). */
    private void drawSideStrips(Canvas c, Theme t) {
        DashData d = DashData.get();
        final float stripW = 68f;
        final float topY   = 27f;   // just below status bar
        final float botY   = 215f;  // just above hline
        final float midY   = topY + (botY - topY) / 2f;  // ~121 px

        // Hairline separators between strip and arc area
        fillRect(c, (int)stripW,       (int)topY, 1, (int)(botY - topY), t.border, 0.22f);
        fillRect(c, (int)(LW - stripW),(int)topY, 1, (int)(botY - topY), t.border, 0.22f);
        // Horizontal divider between the two items in each strip
        fillRect(c, 0,               (int)midY, (int)stripW,       1, t.border, 0.18f);
        fillRect(c, (int)(LW - stripW),(int)midY, (int)stripW,       1, t.border, 0.18f);

        // Left strip: RPM (top slot) + Boost (bottom slot)
        drawStripItem(c, t, 0,           topY, stripW, midY - topY, PAGES[0].pids[0], d.rpm,       "RPM",  "RPM");
        drawStripItem(c, t, 0,           midY, stripW, botY - midY, PAGES[2].pids[0], d.boostPsi(),"BOOST","PSI");
        // Right strip: Coolant (top slot) + Oil (bottom slot)
        drawStripItem(c, t, LW - stripW, topY, stripW, midY - topY, PAGES[1].pids[0], d.coolantF(),"COOL", "°F");
        drawStripItem(c, t, LW - stripW, midY, stripW, botY - midY, PAGES[1].pids[1], d.oilTempF(),"OIL",  "°F");
    }

    private void drawStripItem(Canvas c, Theme t, float x, float y, float w, float h,
                                PidDef pid, float v, String shortLbl, String unit) {
        boolean noData = Float.isNaN(v);
        float nv  = noData ? 0f : nrm(pid, v);
        int   col = noData ? ac(t.label, 0.35f) : bandColor(pid, v, t);
        float cx  = x + w / 2f;

        // Subtle tinted fill
        if (!noData) fillRect(c, (int)x, (int)y, (int)w, (int)h, col, 0.05f);
        // 2 px accent bar at top of slot
        fillRect(c, (int)x, (int)y, (int)w, 2, col, 0.55f);

        // Short label (6 pt mono)
        sf(6, true, false);
        textP.setColor(ac(t.label, 170)); textP.setAlpha(170); textP.setTextAlign(Paint.Align.CENTER);
        c.drawText(shortLbl, cx, y + 12, textP);

        // Large value
        String vs = noData ? "---" : fmtV(v, pid.dec);
        float fs = vs.length() <= 3 ? 18f : vs.length() <= 5 ? 15f : 12f;
        sf(fs, true, true);
        textP.setTextAlign(Paint.Align.CENTER);
        float valY = y + h * 0.58f;
        textShadow(c, vs, cx, valY, t.bg, col);

        // Unit (6 pt mono) — hide when no data
        if (!noData) {
            sf(6, false, false);
            textP.setColor(ac(col, 200)); textP.setAlpha(200); textP.setTextAlign(Paint.Align.CENTER);
            c.drawText(unit, cx, valY + 11, textP);
        }

        // Mini bar (3 px) at bottom of slot
        float bx = x + 5, bw = w - 10, by = y + h - 10;
        fillRect(c, (int)bx, (int)by, (int)bw, 3, t.bg, 1f);
        if (!noData) fillRect(c, (int)bx, (int)by, Math.max(2, (int)(nv * bw)), 3, col, 1f);
    }

    // ── TURBO COMPRESSOR WHEEL ────────────────────────────────────

    private void drawTurboWheel(Canvas c, Theme t) {
        DashData d = DashData.get();
        float boost = d.boostPsi();
        // Normalized: 0 at vacuum, 1 at 20 PSI; NaN (no data yet) treated as 0
        float nb = Float.isNaN(boost) ? 0f : Math.max(0f, Math.min(1f, (boost + 2f) / 22f));
        // Turbo spins at ~15-20× engine speed; scale visually by RPM + boost
        float turboA = engAngle * (5f + nb * 9f);
        float cx = AG_CX, cy = AG_CY;
        int numBlades = 8;
        float outerR = 72f, innerR = 14f;
        int col = nb < 0.05f ? t.blue : nb < 0.4f ? t.cyan : nb < 0.75f ? t.green : t.yellow;
        float bladeAlpha = 0.28f + nb * 0.42f; // min 0.28 at idle, max 0.70 at full boost

        // Outer diffuser ring
        strokeP.setStyle(Paint.Style.STROKE); strokeP.setStrokeWidth(2f);
        strokeP.setColor(ac(t.border, 0.18f + nb * 0.2f));
        c.drawCircle(cx, cy, outerR + 4, strokeP);
        // Inner hub
        fillP.setStyle(Paint.Style.FILL); fillP.setColor(ac(t.border, 0.25f));
        c.drawCircle(cx, cy, innerR, fillP);

        // Blades — curved radial trapezoids using pre-allocated path
        for (int b = 0; b < numBlades; b++) {
            float baseA = turboA + b * (float)(Math.PI * 2 / numBlades);
            float tipA  = baseA  - 0.55f; // blade sweep angle
            float bx1 = cx + cos(baseA) * innerR, by1 = cy + sin(baseA) * innerR;
            float bx2 = cx + cos(baseA + 0.35f) * innerR, by2 = cy + sin(baseA + 0.35f) * innerR;
            float tx1 = cx + cos(tipA) * outerR, ty1 = cy + sin(tipA) * outerR;
            float tx2 = cx + cos(tipA + 0.3f) * outerR, ty2 = cy + sin(tipA + 0.3f) * outerR;
            sparkPath.rewind();
            sparkPath.moveTo(bx1, by1); sparkPath.lineTo(tx1, ty1);
            sparkPath.lineTo(tx2, ty2); sparkPath.lineTo(bx2, by2); sparkPath.close();
            fillP.setColor(ac(col, bladeAlpha));
            c.drawPath(sparkPath, fillP);
            // Blade edge highlight
            strokeP.setColor(ac(col, bladeAlpha * 1.8f)); strokeP.setStrokeWidth(1f);
            c.drawLine(bx1, by1, tx1, ty1, strokeP);
        }

        // Glow halo when boosting
        if (nb > 0.15f) {
            fillP.setColor(ac(col, nb * 0.08f));
            c.drawCircle(cx, cy, outerR + 6, fillP);
        }

        // Boost value ring overlay (thin arc showing boost level)
        if (nb > 0.02f) {
            arcRect.set(cx - outerR - 3, cy - outerR - 3, cx + outerR + 3, cy + outerR + 3);
            strokeP.setColor(ac(col, 0.28f + nb * 0.3f)); strokeP.setStrokeWidth(3f);
            c.drawArc(arcRect, AG_START, nb * AG_SWEEP, false, strokeP);
        }
    }

    // ── CYLINDER PAGE ─────────────────────────────────────────────

    private void drawCylinderPage(Canvas c, Theme t) {
        fillRect(c, 0,27,LW,22, t.dim, 1f);
        fillRect(c, 0,27,3,22, t.accent, 1f);
        sf(10,true,true); textP.setColor(t.white); textP.setAlpha(255); textP.setTextAlign(Paint.Align.CENTER);
        c.drawText("ROUGHNESS", LW/2f, 42, textP);
        sf(7,false,false); textP.setColor(t.accent); c.drawText("FA20DIT CYLINDER MONITOR", LW/2f, 52, textP);
        hline(c,t,53);

        DashData d = DashData.get();
        float[] roughVals = {d.rough1, d.rough2, d.rough3, d.rough4};
        float CY=210, BORE=36, STROKE=40, PISTH=13, WALL=2, CR=18;
        float crankX=LW/2f, crankY=CY;
        DashData dd=DashData.get();

        // ── Engine block background panels ──────────────────────────
        float blockTop=CY-64, blockBot=CY+64;
        fillRect(c, 8, blockTop, 82, blockBot-blockTop, t.panel, 0.55f);  // left bank
        fillRect(c, LW-90, blockTop, 82, blockBot-blockTop, t.panel, 0.55f); // right bank
        strokeRect(c, 8, blockTop, 82, blockBot-blockTop, t.border, 0.4f, 1f);
        strokeRect(c, LW-90, blockTop, 82, blockBot-blockTop, t.border, 0.4f, 1f);
        // Cam covers — thin accent strips at outer edges
        fillRect(c, 8, blockTop, 82, 5, t.adim, 1f);
        fillRect(c, LW-90, blockTop, 82, 5, t.adim, 1f);
        sf(5,true,false); textP.setColor(ac(t.accent,0.5f)); textP.setTextAlign(Paint.Align.CENTER);
        c.drawText("FA20", 49, blockTop+12, textP);
        c.drawText("FA20", LW-49, blockTop+12, textP);

        // ── AVCS cam angle indicators (OCV duty → cam pos proxy) ──
        float ocvL = (!Float.isNaN(dd.ocvIntakeL)&&!Float.isNaN(dd.ocvExhL)) ? (dd.ocvIntakeL+dd.ocvExhL)/2f/100f : 0f;
        float ocvR = (!Float.isNaN(dd.ocvIntakeR)&&!Float.isNaN(dd.ocvExhR)) ? (dd.ocvIntakeR+dd.ocvExhR)/2f/100f : 0f;
        float camArcR=8f;
        for(int bank=0;bank<2;bank++){
            float bx = bank==0?26f:LW-26f;
            float ocv = bank==0?ocvL:ocvR;
            int camCol = ocv<0.15f?t.blue:ocv<0.5f?t.cyan:t.green;
            strokeP.setStyle(Paint.Style.STROKE); strokeP.setStrokeWidth(2f);
            strokeP.setColor(ac(t.border,0.4f));
            arcRect.set(bx-camArcR,blockTop+16-camArcR,bx+camArcR,blockTop+16+camArcR);
            c.drawArc(arcRect,0,360,false,strokeP);
            strokeP.setColor(camCol); strokeP.setStrokeWidth(2.5f);
            c.drawArc(arcRect,-90,ocv*270,false,strokeP);
        }

        // ── Oil temp heat-map glow on block ───────────────────────
        float oilN=Float.isNaN(dd.oilTempC)?0f:Math.max(0,Math.min(1f,(dd.oilTempF()-100f)/150f)); // 0 at 100°F, 1 at 250°F
        if(oilN>0.1f){
            int oilCol=oilN<0.5f?t.blue:oilN<0.8f?t.green:oilN<0.95f?t.yellow:t.red;
            fillRect(c, 8,blockTop,82,blockBot-blockTop, oilCol, oilN*0.06f);
            fillRect(c, LW-90,blockTop,82,blockBot-blockTop, oilCol, oilN*0.06f);
        }

        // ── Crank disc ─────────────────────────────────────────────
        fillP.setStyle(Paint.Style.FILL); fillP.setColor(t.panel); fillP.setAlpha(255);
        c.drawCircle(crankX, crankY, CR+10, fillP);
        strokeP.setColor(t.border); strokeP.setStyle(Paint.Style.STROKE); strokeP.setStrokeWidth(1.5f);
        c.drawCircle(crankX, crankY, CR+10, strokeP);
        // Timing ring marks
        for(int tm=0;tm<12;tm++){
            double ta=Math.toRadians(tm*30);
            float r1=CR+8, r2=CR+11;
            strokeP.setColor(ac(t.accent,0.35f)); strokeP.setStrokeWidth(1f);
            c.drawLine((float)(crankX+r1*Math.cos(ta)),(float)(crankY+r1*Math.sin(ta)),
                       (float)(crankX+r2*Math.cos(ta)),(float)(crankY+r2*Math.sin(ta)),strokeP);
        }
        fillP.setColor(ac(t.accent,0.18f)); fillP.setAlpha(46); c.drawCircle(crankX, crankY, 7, fillP); fillP.setAlpha(255);
        strokeP.setColor(t.accent); strokeP.setStrokeWidth(1f);
        c.drawCircle(crankX, crankY, 7, strokeP);
        fillP.setColor(t.accent); fillP.setAlpha(255); c.drawCircle(crankX, crankY, 3, fillP);

        // ── Crank webs ─────────────────────────────────────────────
        for (float off : new float[]{0f, (float)Math.PI}) {
            float a = engAngle + off;
            float px = crankX + cos(a)*CR, py = crankY + sin(a)*CR;
            strokeP.setColor(ac(t.label,0.4f)); strokeP.setStrokeWidth(4f);
            c.drawLine(crankX, crankY, px, py, strokeP);
            strokeP.setColor(ac(t.label,0.22f)); strokeP.setStrokeWidth(6f);
            c.drawLine(crankX,crankY,crankX-cos(a)*(CR*0.7f),crankY-sin(a)*(CR*0.7f),strokeP);
            fillP.setColor(ac(t.label,0.45f)); fillP.setStyle(Paint.Style.FILL);
            c.drawCircle(px, py, 4, fillP);
        }

        // ── Cylinders ──────────────────────────────────────────────
        float[][] rowY = {{CY-46},{CY+46},{CY-46},{CY+46}};
        boolean[] isLeft = {true,true,false,false};
        int[] cpIdx = {0,1,1,0};
        for (int ci=0; ci<4; ci++) {
            float rv = roughVals[ci];
            boolean rvNaN = Float.isNaN(rv);
            PidDef pid = PAGES[4].pids[ci];
            int col = rvNaN ? ac(t.label, 0.35f) : bandColor(pid, rv, t);
            float rY = rowY[ci][0];
            float pisPos = (1 - cos(engAngle + FA20_PHASE[ci])) / 2f;
            boolean isl = isLeft[ci];
            float boreLen = STROKE+PISTH+12;
            float boreStartX = isl ? 14 : LW-14-boreLen;
            float boreEndX   = isl ? 14+boreLen : LW-14;
            float boreTop = rY - BORE/2f;

            fillRect(c, boreStartX-WALL, boreTop-WALL, boreLen+WALL*2, BORE+WALL*2, t.panel, 1f);
            strokeRect(c, boreStartX-WALL, boreTop-WALL, boreLen+WALL*2, BORE+WALL*2, t.border, 0.55f, 1f);
            fillRect(c, boreStartX, boreTop, boreLen, BORE, t.bg, 1f);

            // Combustion flash at TDC (pisPos near 0 = TDC for boxer layout)
            if (pisPos < 0.22f) {
                float flashA = (1 - pisPos/0.22f);
                // Combustion chamber end: outer edge of bore
                float fx = isl ? boreStartX+1 : boreEndX-20;
                fillRect(c, fx, boreTop+1, 20, BORE-2, t.yellow, flashA*0.6f);
                if (flashA > 0.6f) fillRect(c, fx, boreTop+1, 12, BORE-2, t.white, (flashA-0.6f)*0.45f);
                // Exhaust heat glow on bore walls
                strokeP.setStyle(Paint.Style.STROKE); strokeP.setColor(ac(t.orange, flashA*0.3f));
                strokeP.setStrokeWidth(2f);
                c.drawRect(boreStartX-WALL, boreTop-WALL, boreEndX+WALL, boreTop+BORE+WALL, strokeP);
            }

            // Roughness glow overlay on bore
            if (!rvNaN && rv > 5f) fillRect(c, boreStartX, boreTop, boreLen, BORE, col, Math.min(0.25f, rv/200f));

            float phe = isl ? boreStartX+pisPos*STROKE : boreEndX-pisPos*STROKE-PISTH;
            // Piston rings highlight
            fillRect(c, phe, boreTop+1, PISTH, BORE-2, t.accent, 1f);
            fillRect(c, phe, boreTop+3, PISTH, 2, ac(t.white,0.35f), 1f);
            fillRect(c, phe, boreTop+BORE-6, PISTH, 2, ac(t.white,0.2f), 1f);

            // Con-rod (thicker, more visible)
            float cpA = engAngle + (cpIdx[ci]==0?0:(float)Math.PI);
            float cpX2 = crankX + cos(cpA)*CR, cpY2 = crankY + sin(cpA)*CR;
            strokeP.setColor(ac(t.label,0.5f)); strokeP.setStrokeWidth(3f); strokeP.setStyle(Paint.Style.STROKE);
            c.drawLine(isl?phe+PISTH:phe, rY, cpX2, cpY2, strokeP);
            strokeP.setColor(ac(t.accent,0.25f)); strokeP.setStrokeWidth(1.5f);
            c.drawLine(isl?phe+PISTH:phe, rY, cpX2, cpY2, strokeP);

            // Cyl number + roughness indicator dot
            float numX = isl ? boreStartX-9 : boreEndX+9;
            sf(9,true,true); textP.setColor(col); textP.setAlpha(255); textP.setTextAlign(Paint.Align.CENTER);
            c.drawText(String.valueOf(ci+1), numX, rY+4, textP);
            glowDot(c, numX, rY+10, 2.5f, col);
        }

        // ── Exhaust manifold lines (decorative) ───────────────────
        strokeP.setStyle(Paint.Style.STROKE); strokeP.setStrokeWidth(2.5f);
        strokeP.setColor(ac(t.orange,0.22f));
        // Left bank: short stub lines from bore head ends inward
        c.drawLine(14, CY-46, 14, CY+10, strokeP);
        c.drawLine(14, CY+46, 14, CY+10, strokeP);
        c.drawLine(8, CY+10, 14, CY+10, strokeP);
        // Right bank
        c.drawLine(LW-14, CY-46, LW-14, CY+10, strokeP);
        c.drawLine(LW-14, CY+46, LW-14, CY+10, strokeP);
        c.drawLine(LW-8, CY+10, LW-14, CY+10, strokeP);

        // ── Intake runners (decorative) ────────────────────────────
        strokeP.setColor(ac(t.cyan,0.18f)); strokeP.setStrokeWidth(1.5f);
        c.drawLine(14, CY-46, 8, CY-46, strokeP);
        c.drawLine(14, CY+46, 8, CY+46, strokeP);
        c.drawLine(LW-14, CY-46, LW-8, CY-46, strokeP);
        c.drawLine(LW-14, CY+46, LW-8, CY+46, strokeP);

        // Live RPM + boost — pinned to screen edges to avoid overlap
        sf(7,true,true); textP.setColor(t.white); textP.setAlpha(255); textP.setTextAlign(Paint.Align.LEFT);
        c.drawText(Float.isNaN(dd.rpm) ? "--- RPM" : Math.round(dd.rpm)+" RPM", 14, CY+70, textP);
        float ddBoost = dd.boostPsi();
        int bCol = Float.isNaN(ddBoost) ? ac(t.label,128) : ddBoost<0?t.cyan:ddBoost<10?t.green:t.yellow;
        sf(7,true,true); textP.setColor(bCol); textP.setTextAlign(Paint.Align.RIGHT);
        c.drawText(Float.isNaN(ddBoost) ? "--- PSI" : (ddBoost>=0?"+":"")+String.format("%.1f",ddBoost)+" PSI", LW-14, CY+70, textP);
        // Firing order on its own centred line
        sf(7,false,false); textP.setColor(ac(t.white,0.55f)); textP.setTextAlign(Paint.Align.CENTER);
        c.drawText("FIRING ORDER  1-3-2-4", LW/2f, CY+86, textP);

        hline(c,t,310);
        float cW=78,cH=82,cY2=312;
        for (int i=0; i<4; i++) {
            float cx2=i*(cW+2)+2;
            float rv=roughVals[i]; boolean rvNaN2=Float.isNaN(rv);
            PidDef pid=PAGES[4].pids[i];
            int col = rvNaN2 ? ac(t.label, 0.35f) : bandColor(pid,rv,t);
            float nv = rvNaN2 ? 0f : nrm(pid,rv);
            fillRect(c,cx2,cY2,cW,cH, t.panel, 1f);
            if (!rvNaN2) fillRect(c,cx2,cY2,(int)(nv*cW),cH, col, 0.08f);
            fillRect(c,cx2,cY2,cW,3, col, 1f);
            strokeRect(c,cx2,cY2,cW,cH, t.border, 0.35f, 1f);
            cbrk(c,cx2,cY2,cW,cH, ac(t.accent,0.25f), 4);
            sf(8,true,false); textP.setColor(t.white); textP.setAlpha(255); textP.setTextAlign(Paint.Align.CENTER);
            c.drawText(new String[]{"CYL 1","CYL 2","CYL 3","CYL 4"}[i], cx2+cW/2f, cY2+15, textP);
            sf(22,true,true); textP.setTextAlign(Paint.Align.CENTER);
            String rvStr = rvNaN2 ? "---" : String.format("%.1f",rv);
            textShadow(c, rvStr, cx2+cW/2f, cY2+44, t.bg, col);
            sf(7,false,false); textP.setColor(col); textP.setTextAlign(Paint.Align.CENTER);
            c.drawText(rvNaN2 ? "NO DATA" : rv<10?"OK":rv<30?"WATCH":"KNOCK", cx2+cW/2f, cY2+58, textP);
            // mini bar
            float bx=cx2+6, by=cY2+cH-14, bw=cW-12;
            fillRect(c,bx,by,bw,5, t.bg, 1f);
            if (!rvNaN2) fillRect(c,bx,by,(int)(nv*bw),5, col, 1f);
            strokeRect(c,bx,by,bw,5, t.border, 0.4f, 1f);
        }
        hline(c,t,396);
        // Status strip — RPM + Speed (ends at y=458 to leave room for dot strip at y=460)
        DashData ds=DashData.get();
        fillRect(c,0,396,LW,64, t.dim, 1f);
        int[] scols2={Float.isNaN(ds.rpm)?ac(t.label,0.35f):bandColor(PAGES[0].pids[0],ds.rpm,t), Float.isNaN(ds.speedMph())?ac(t.label,0.35f):bandColor(PAGES[5].pids[1],ds.speedMph(),t), ds.connected?t.green:t.orange};
        String[] slbls2={"RPM","MPH","OBD"}; String[] svals2={Float.isNaN(ds.rpm)?"---":String.valueOf(Math.round(ds.rpm)),Float.isNaN(ds.speedMph())?"---":String.valueOf(Math.round(ds.speedMph())),ds.btStatus};
        for(int i=0;i<3;i++){
            int sx3=i*(LW/3);
            fillRect(c,sx3+(i>0?1:0),398,2,58, scols2[i], 1f);
            sf(7,true,false); textP.setColor(t.white); textP.setAlpha(255); textP.setTextAlign(Paint.Align.LEFT);
            c.drawText(slbls2[i],sx3+6,408,textP);
            sf(9,true,true); textP.setColor(scols2[i]); c.drawText(svals2[i],sx3+6,450,textP);
            if(i<2) fillRect(c,sx3+(LW/3)-1,398,1,58, t.border, 0.3f);
        }
    }

    // ── SESSION PAGE ──────────────────────────────────────────────

    private void drawSessionPage(Canvas c, Theme t) {
        fillRect(c, 0,27,LW,22, t.dim, 1f);
        fillRect(c, 0,27,3,22, t.accent, 1f);
        sf(10,true,true); textP.setColor(t.white); textP.setAlpha(255); textP.setTextAlign(Paint.Align.CENTER);
        c.drawText("SESSION", LW/2f, 42, textP);
        sf(7,false,false); textP.setColor(t.accent); c.drawText("PEAK VALUES · THIS BOOT", LW/2f, 52, textP);
        hline(c,t,53);

        DashData d = DashData.get();
        float[] pkVals = {d.peakBoostPsi,d.peakRpm,d.peakTimingDeg,d.peakLoadPct,d.peakSpeedMph,d.worstKnockCorr,d.peakMafGs,d.peakEstHp,d.peakCatTempF};
        String[] pkLbls = {"PEAK BOOST","PEAK RPM","PEAK TIMING","PEAK LOAD","PEAK SPEED","WORST KNOCK","PEAK MAF","PEAK HP","PEAK CAT"};
        String[] pkUnits= {"PSI","RPM","°","%","MPH","°","G/S","HP","°F"};
        int[] pkColors  = {t.cyan,t.green,t.yellow,t.orange,t.blue,t.red,t.purple,t.accent,t.orange};
        int[] pkDecs    = {2,0,1,1,0,2,1,0,0};
        int cols=3,rows=3,startY=52,tW=LW/cols,tH=(LH-startY-54)/rows;
        for (int i=0;i<9;i++) {
            int col2=i%cols, row2=i/cols;
            float cx=col2*tW, cy=startY+row2*tH;
            int col=pkColors[i];
            fillRect(c, cx+2,cy+2,tW-4,tH-4, t.panel, 1f);
            fillRect(c, cx+2,cy+2,tW-4,3, col, 1f);
            strokeRect(c, cx+2,cy+2,tW-4,tH-4, t.border, 0.4f, 1f);
            cbrk(c, cx+2,cy+2,tW-4,tH-4, ac(col,0.28f), 4);
            sf(7,true,false); textP.setColor(ac(t.white,220)); textP.setTextAlign(Paint.Align.LEFT);
            c.drawText(pkLbls[i], cx+6, cy+20, textP);
            boolean hasData = !Float.isNaN(pkVals[i]);
            String dv = hasData ? fmtV(pkVals[i],pkDecs[i]) : "---";
            float vFs = dv.length()<=3?34: dv.length()<=5?26: 20;
            sf(vFs,true,true); textP.setTextAlign(Paint.Align.CENTER);
            textShadow(c, dv, cx+tW/2f, cy+tH*0.65f, t.bg, hasData?t.white:ac(t.label,100));
            sf(8,false,false); textP.setColor(ac(col,200)); textP.setTextAlign(Paint.Align.RIGHT);
            c.drawText(pkUnits[i], cx+tW-6, cy+tH-10, textP);
        }
        int sY=startY+rows*tH+2;
        hline(c,t,sY); fillRect(c, 0,sY,LW,22, t.dim, 1f);
        sf(7,false,false); textP.setColor(ac(t.label,200)); textP.setTextAlign(Paint.Align.LEFT);
        c.drawText("KNOCK EVENTS THIS SESSION:", 10, sY+14, textP);
        sf(11,true,true); textP.setColor(d.knockEventCount>0?t.red:t.green); textP.setTextAlign(Paint.Align.RIGHT);
        c.drawText(String.valueOf(d.knockEventCount), LW-10, sY+15, textP);
        hline(c,t,sY+22);
    }

    // ── DRIVE MODE ────────────────────────────────────────────────

    private void drawDriveMode(Canvas c, Theme t) {
        c.drawColor(t.bg);
        DashData d = DashData.get();
        float spd=d.speedMph(), rpm=d.rpm, boost=d.boostPsi(), tgtMap=d.targetMapPsi();
        float load=d.loadPct, oil=d.oilTempF(), knock=d.knockCorr;

        PidDef rpmPid=PAGES[0].pids[0], bstPid=PAGES[2].pids[0];
        PidDef lodPid=PAGES[0].pids[1], oilPid=PAGES[1].pids[1];
        PidDef knkPid=PAGES[6].pids[1];  // index 1 = KNOCK CORR on TIMING page (index 6)

        int bstCol=Float.isNaN(boost)?ac(t.label,0.35f):bandColor(bstPid,boost,t);
        int lodCol=Float.isNaN(load)?ac(t.label,0.35f):bandColor(lodPid,load,t);
        int oilCol=Float.isNaN(oil)?ac(t.label,0.35f):bandColor(oilPid,oil,t);
        boolean kActive=!Float.isNaN(knock)&&knock<-2.5f;
        int knkCol=Float.isNaN(knock)?ac(t.label,0.35f):kActive?t.red:knock<-0.1f?t.orange:t.green;

        float rpmN=Float.isNaN(rpm)?0f:nrm(rpmPid,rpm);
        float bstN=Float.isNaN(boost)?0f:nrm(bstPid,boost);
        float tgtN=Float.isNaN(tgtMap)?0f:nrm(bstPid,tgtMap);
        long ms=System.currentTimeMillis(); float pulse=0.5f+0.5f*(float)Math.abs(Math.sin(ms*0.006));
        int heroH=195;

        fillRect(c, 0,0,LW,3, t.accent, 1f);
        sf(8,true,false); textP.setColor(t.label); textP.setAlpha(255); textP.setTextAlign(Paint.Align.CENTER);
        c.drawText("VEHICLE SPEED", LW/2f, 22, textP);
        String ss = Float.isNaN(spd) ? "---" : String.valueOf(Math.round(spd));
        float sfs = ss.length()<=2?110: ss.length()==3?88:72;
        sf(sfs,true,true); textP.setTextAlign(Paint.Align.CENTER);
        textShadow(c, ss, LW/2f, 132, t.bg, Float.isNaN(spd)?ac(t.label,0.5f):t.white);
        sf(14,true,true); textP.setColor(t.accent); textP.setAlpha(255); c.drawText("MPH", LW/2f, 154, textP);

        // RPM bar
        int rpmCol=Float.isNaN(rpm)?ac(t.label,0.35f):bandColor(rpmPid,rpm,t);
        fillRect(c, 14,170,LW-28,10, t.dim, 1f);
        if (!Float.isNaN(rpm)) fillRect(c, 14,170,(int)(rpmN*(LW-28)),10, rpmCol, 1f);
        strokeRect(c, 14,170,LW-28,10, t.border, 0.5f, 1f);
        sf(7,true,false); textP.setColor(t.label); textP.setTextAlign(Paint.Align.LEFT); c.drawText("RPM",14,168,textP);
        textP.setColor(rpmCol); textP.setTextAlign(Paint.Align.RIGHT);
        c.drawText(Float.isNaN(rpm)?"---":String.valueOf(Math.round(rpm)),LW-14,168,textP);

        hline(c,t,heroH);
        fillRect(c, 0,heroH,LW,65, t.panel, 1f);
        fillRect(c, 0,heroH,LW,2, t.accent, 1f);
        sf(8,true,false); textP.setColor(t.label); textP.setTextAlign(Paint.Align.LEFT); c.drawText("BOOST",14,heroH+16,textP);
        String bstStr=Float.isNaN(boost)?"---":((boost>=0?"+":"")+String.format("%.1f",boost)+"PSI");
        sf(12,true,true); textP.setColor(bstCol); textP.setTextAlign(Paint.Align.LEFT);
        textShadow(c, bstStr, 58, heroH+16, t.bg, bstCol);
        sf(7,false,false); textP.setColor(ac(t.label,165)); textP.setTextAlign(Paint.Align.RIGHT);
        c.drawText(Float.isNaN(tgtMap)?"TGT --- PSI":"TGT "+String.format("%.1f",tgtMap)+" PSI", LW-14, heroH+16, textP);

        int bbX=14,bbY=heroH+24,bbW=LW-28,bbH=20;
        fillRect(c, bbX,bbY,bbW,bbH, t.bg, 1f);
        if (!Float.isNaN(boost)) fillRect(c, bbX,bbY,Math.max(2,(int)(bstN*bbW)),bbH, bstCol, 1f);
        // Target dashes
        if (!Float.isNaN(tgtMap)) {
            int tgtX=bbX+(int)(tgtN*bbW);
            fillP.setColor(ac(t.white,115)); fillP.setStyle(Paint.Style.FILL);
            for(int dy=bbY-3;dy<bbY+bbH+3;dy+=5) c.drawRect(tgtX,dy,tgtX+1,dy+3,fillP);
        }
        strokeRect(c, bbX,bbY,bbW,bbH, t.border, 0.45f, 1f);
        hline(c,t,heroH+65);

        // 4 tiles
        int tileY=heroH+65, tileH=LH-tileY, tW3=LW/4;
        float[] tVals={boost,load,oil,knock};
        int[]   tCols={bstCol,lodCol,oilCol,knkCol};
        String[]tLbls={"BOOST","LOAD","OIL","KNOCK"};
        String[]tUnits={"PSI","%","°F","°"};
        PidDef[]tPids={bstPid,lodPid,oilPid,knkPid};
        String[]tDisps={Float.isNaN(boost)?"---":(boost>=0?"+":"")+String.format("%.1f",boost),Float.isNaN(load)?"---":String.valueOf(Math.round(load)),Float.isNaN(oil)?"---":String.valueOf(Math.round(oil)),Float.isNaN(knock)?"---":String.format("%.2f",knock)};
        for (int i=0;i<4;i++) {
            int tx=i*tW3, ty=tileY;
            int col=tCols[i]; boolean isK=i==3;
            float nv2=Float.isNaN(tVals[i])?0f:nrm(tPids[i],tVals[i]);
            fillRect(c, tx,ty,tW3,tileH, t.panel, 1f);
            if(i>0) fillRect(c, tx,ty,1,tileH, t.border, 0.22f);
            fillRect(c, tx,ty,tW3,3, isK&&kActive?t.red:col, 1f);
            fillRect(c, tx,ty+3,tW3,22, col, isK&&kActive?pulse*0.12f:0.14f);
            sf(9,true,false); textP.setColor(t.white); textP.setAlpha(255); textP.setTextAlign(Paint.Align.CENTER);
            c.drawText(tLbls[i], tx+tW3/2f, ty+20, textP);
            String comb=tDisps[i]+" "+tUnits[i]; int dl=comb.length();
            float vFs=dl<=5?26: dl<=7?20: dl<=9?16:13;
            sf(vFs,true,true); textP.setTextAlign(Paint.Align.CENTER);
            int fgCol=isK&&kActive?(pulse>0.5f?t.red:t.white):t.white;
            textShadow(c, comb, tx+tW3/2f, ty+(int)(tileH*0.58f), t.bg, fgCol);
            fillRect(c, tx+4,LH-10,tW3-8,4, t.bg, 1f);
            fillRect(c, tx+4,LH-10,(int)(nv2*(tW3-8)),4, col, 1f);
        }
        sf(6,false,false); textP.setColor(ac(t.accent,50)); textP.setTextAlign(Paint.Align.CENTER);
        c.drawText("HOLD RIGHT BUTTON TO EXIT DRIVE MODE", LW/2f, LH-18, textP);
        if(t.scan) scanlines(c);
    }

    // ── ALERT ────────────────────────────────────────────────────

    private void drawAlert(Canvas c, Theme t) {
        long ms=System.currentTimeMillis();
        float p3=0.3f+0.7f*(float)Math.abs(Math.sin(ms*0.004));
        int col="red".equals(alertSev)?t.red:t.orange;
        fillRect(c, 0,0,LW,LH, 0xFF000000, 0.82f);
        glowRect(c, 3,3,LW-6,LH-6, col, p3*0.85f);
        fillRect(c, 0,0,LW,56, col, 0.22f);
        fillRect(c, 0,0,LW,3, col, 1f);
        sf(7,true,false); textP.setColor(col); textP.setAlpha(255); textP.setTextAlign(Paint.Align.CENTER);
        c.drawText("red".equals(alertSev)?"!! CRITICAL ALERT !!":"! WARNING !", LW/2f, 14, textP);
        // Triangle
        float ic_cx=LW/2f, ic_cy=168, ic_r=34;
        float[] tx={ic_cx, ic_cx+ic_r*0.87f, ic_cx-ic_r*0.87f};
        float[] ty={ic_cy-ic_r, ic_cy+ic_r*0.5f, ic_cy+ic_r*0.5f};
        alertTriPath.rewind(); android.graphics.Path tri=alertTriPath;
        tri.moveTo(tx[0],ty[0]); tri.lineTo(tx[1],ty[1]); tri.lineTo(tx[2],ty[2]); tri.close();
        fillP.setColor(ac(col,0.12f)); fillP.setStyle(Paint.Style.FILL); c.drawPath(tri,fillP);
        strokeP.setColor(col); strokeP.setStyle(Paint.Style.STROKE); strokeP.setStrokeWidth(2f); c.drawPath(tri,strokeP);
        sf(18,true,true); textP.setColor(col); textP.setTextAlign(Paint.Align.CENTER); c.drawText("!",ic_cx,ic_cy+9,textP);
        sf(13,true,true); textP.setTextAlign(Paint.Align.CENTER);
        textShadow(c, alertMsg, LW/2f, 234, t.bg, col);
        sf(8,false,false); textP.setColor(ac(t.label,200)); c.drawText(alertSub, LW/2f, 254, textP);
        sf(36,true,true); textP.setTextAlign(Paint.Align.CENTER);
        textShadow(c, alertVal, LW/2f, 307, t.bg, t.white);
        fillRect(c, 40,318,LW-80,2, col, 0.35f);
        float p4=0.4f+0.6f*(float)Math.abs(Math.sin(ms*0.003));
        sf(8,false,false); textP.setColor(ac(t.label,(int)(p4*255)));
        c.drawText("PRESS RIGHT BUTTON TO DISMISS", LW/2f, 354, textP);
        if(t.scan) scanlines(c);
    }

    // ── BOOT SCREEN ───────────────────────────────────────────────

    /** SFE pixel-art logo with speed-line effect.
     *  Draws "SFE" in large italic Orbitron, then overlays:
     *   • bg-colour horizontal cuts every 4 px  (speed-line / stripe effect)
     *   • blue  band at ~50% letter height       (brand colour)
     *   • purple band at ~58% letter height      (brand colour)
     *  textP.setAntiAlias(false) gives a crisp, pixel-art quality.
     *  No bitmap allocation — purely programmatic Canvas ops. */
    private void drawSFELogo(Canvas c, Theme t) {
        final float baseline = 63f;   // text baseline Y
        final float sz       = 68f;   // Orbitron size — fills ~200px at this logical scale

        // Approximate letter bounds (Orbitron cap height ≈ 0.73 em above baseline)
        final float top    = baseline - sz * 0.78f;   //  ~10 px from accent bar
        final float bot    = baseline + sz * 0.08f;   // slightly below baseline

        final float logoH  = bot - top;               // ~55 px
        // Brand stripes: blue at 50%, purple at 58% of letter height
        final float blueY  = top + logoH * 0.50f;
        final float purpY  = top + logoH * 0.58f;
        final float bandH  = Math.max(3.5f, logoH * 0.075f); // ~4 px stripe height

        // ── 1. Draw "SFE" with italic canvas skew ──────────────────
        textP.setAntiAlias(false);        // pixel-art crispness, minimal GPU overdraw
        c.save();
        // skew(-k,0): horizontal shear → italic slant (k=0.20 ≈ 11° lean)
        c.skew(-0.20f, 0f);
        sf(sz, true, true);
        textP.setColor(t.white); textP.setAlpha(255);
        textP.setTextAlign(Paint.Align.CENTER);
        // When canvas is skewed by -k, a point (x,y) renders at screen (x - k*y, y).
        // To keep text centred on LW/2, shift draw X right by k*baseline.
        c.drawText("SFE", LW / 2f + 0.20f * baseline, baseline, textP);
        c.restore();
        textP.setAntiAlias(true);         // restore for all other UI text

        // ── 2. Blue + purple brand stripes — logo width only ───────
        // Measure text width, then account for italic skew offset at each stripe Y.
        sf(sz, true, true);
        float tw = textP.measureText("SFE");
        final float pad = 6f;  // small horizontal padding on each side
        // With canvas skew(-0.20,0): text centre at screen-X = LW/2, but at heights
        // above baseline the italic lean shifts content rightward.
        // Stripe left/right at a given stripeY: centre shifts by 0.20*(baseline-stripeY).
        float blueCx = LW / 2f + 0.20f * (baseline - blueY);
        float purpCx = LW / 2f + 0.20f * (baseline - purpY);
        fillRect(c, blueCx - tw/2f - pad, blueY, tw + pad*2, bandH, 0xFF3878E8, 1f);
        fillRect(c, purpCx - tw/2f - pad, purpY, tw + pad*2, bandH, 0xFF7830C0, 1f);

        // ── 3. Speed-line cuts (bg-coloured stripes, 2px every 4px) ─
        // Cover the entire logo height; bg colour = invisible outside letters,
        // cuts through letter body and coloured bands alike.
        for (float y = top; y < bot + 4f; y += 4f) {
            fillRect(c, 0, y, LW, 2f, t.bg, 1f);
        }
    }

    private void drawBoot(Canvas c, Theme t) {
        c.drawColor(t.bg);
        long now = System.currentTimeMillis();

        // ── Top accent bar ────────────────────────────────────────
        fillRect(c, 0, 0, LW, 3, t.accent, 1f);

        // ── SFE pixel-art logo (y ≈ 3 → 68) ─────────────────────
        drawSFELogo(c, t);

        // ── Subtitle text ─────────────────────────────────────────
        sf(8,true,true);  textP.setColor(t.accent); textP.setAlpha(255); textP.setTextAlign(Paint.Align.CENTER);
        c.drawText("SIK FUK ENTERPRISES", LW/2f, 76, textP);
        sf(7,true,false); textP.setColor(ac(t.white,160)); c.drawText("AUTOMOTIVE DIVISION", LW/2f, 86, textP);
        sf(7,true,false); textP.setColor(ac(t.white,178)); c.drawText("ENGINE MONITOR  v4.0", LW/2f, 97, textP);
        sf(6,false,false); textP.setColor(ac(t.accent,160)); c.drawText("FA20DIT · SUBARU FORESTER XT · OBD2/UDS", LW/2f, 107, textP);

        // ── Divider ───────────────────────────────────────────────
        fillRect(c, 20, 112, LW-40, 1, t.accent, 0.4f);

        // ── Hardware spec table ───────────────────────────────────
        String[][] hw={{"MCU","ESP32 / ANDROID BT"},{"OBD","VEEPEAK OBDII · SPP"},
                       {"ECU","AT SH 7E0 · UDS MODE 22"},{"TCU","AT SH 7E1 · CVT TR690"}};
        for(int i=0;i<hw.length;i++){
            float y=121+i*15;
            sf(7,true,false); textP.setColor(t.accent); textP.setAlpha(255); textP.setTextAlign(Paint.Align.LEFT);
            c.drawText(hw[i][0], 14, y, textP);
            fillRect(c, 42, y-10, 1, 12, t.border, 0.45f);
            sf(7,false,false); textP.setColor(ac(t.white,210));
            c.drawText(hw[i][1], 48, y, textP);
        }

        // ── Boot check list ───────────────────────────────────────
        fillRect(c, 20, 183, LW-40, 1, t.border, 0.4f);
        int n = Math.min(bootLinesShown, BOOT_CHECKS.length);
        for(int i=0;i<n;i++){
            float y = 193 + i*15;
            boolean isPend = (i==n-1) && !bootDone;
            sf(7,true,false);  textP.setColor(isPend?t.yellow:t.green); textP.setTextAlign(Paint.Align.LEFT);
            c.drawText(isPend?"[...]":"[ OK]", 14, y, textP);
            sf(7,false,false); textP.setColor(isPend?ac(t.white,230):ac(t.white,130));
            c.drawText(BOOT_CHECKS[i], 60, y, textP);
        }

        // ── Progress bar ──────────────────────────────────────────
        float prog = bootDone ? 1f : (float)n / BOOT_CHECKS.length;
        int pbY=LH-58, pbX=14, pbW=LW-28, pbH=8;
        fillRect(c, pbX, pbY, pbW, pbH, t.bg, 1f);
        fillRect(c, pbX, pbY, Math.max(4,(int)(prog*pbW)), pbH, bootDone?t.green:t.accent, 1f);
        strokeRect(c, pbX, pbY, pbW, pbH, t.border, 0.55f, 1f);
        sf(8,true,false); textP.setColor(bootDone?t.green:t.white); textP.setAlpha(255); textP.setTextAlign(Paint.Align.RIGHT);
        c.drawText(Math.round(prog*100)+"%", LW-14, pbY-4, textP);

        // ── Status line ───────────────────────────────────────────
        if(bootDone){
            float bp = 0.5f+0.5f*(float)Math.abs(Math.sin(now*0.004));
            sf(10,true,false); textP.setColor(ac(t.green,(int)(bp*255))); textP.setTextAlign(Paint.Align.CENTER);
            c.drawText("> SYSTEM READY <", LW/2f, LH-22, textP);
            sf(7,false,false); textP.setColor(ac(t.accent,140)); c.drawText("ENTERING DASHBOARD...", LW/2f, LH-8, textP);
        } else {
            sf(7,false,false); textP.setColor(ac(t.label,90)); textP.setTextAlign(Paint.Align.CENTER);
            c.drawText("INITIALIZING SYSTEMS", LW/2f, LH-14, textP);
        }

        // ── Bottom accent bar ─────────────────────────────────────
        fillRect(c, 0, LH-3, LW, 3, t.accent, 1f);
        if(t.scan) scanlines(c);
    }

    // ── Public state query ────────────────────────────────────────
    public boolean isAlertOn() { return alertOn; }

    // ── Trig helpers ──────────────────────────────────────────────
    private static float cos(float a) { return (float)Math.cos(a); }
    private static float sin(float a) { return (float)Math.sin(a); }
}
