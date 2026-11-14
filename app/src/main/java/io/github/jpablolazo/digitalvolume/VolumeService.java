package io.github.jpablolazo.digitalvolume;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.audiofx.DynamicsProcessing;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * Maps the remote's volume keys to a digital gain on Android's output mix, so the optical output follows them
 * while the TV speakers stay at 0. See README.md for how and why.
 */
public class VolumeService extends AccessibilityService
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    static final String TAG = "DigitalVolume";
    static final String PREFS = "volume";
    static final String K_ENABLED = "enabled";
    static final String K_EXTERNAL = "external_mode";
    static final String K_LEVEL = "level";
    static final String K_TV_INDEX = "tv_index";
    static final String EXTERNAL_TV = "tv";
    static final String EXTERNAL_FIXED = "fixed";
    static final int MAX = 30;

    private static final String VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION";
    private static final String MUTE_CHANGED = "android.media.STREAM_MUTE_CHANGED_ACTION";
    private static final String EXTRA_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    private static final String EXTRA_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE";
    private static final String EXTRA_PREV = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE";
    private static final String EXTRA_MUTED = "android.media.EXTRA_STREAM_VOLUME_MUTED";

    // Live TV, HDMI and AV run in the MediaTek TV app; their audio bypasses Android's mix.
    private static final String LIVE_TV = "com.mediatek.wwtv.tvcenter";
    // Windows that open over the picture without replacing it.
    private static final String[] OVERLAYS = {
            "com.android.systemui", "com.android.tv.settings", "com.google.android.inputmethod" };

    private static final float STEP_DB = 1.5f;
    private static final float MUTE_DB = -96f;
    private static final long OSD_MS = 4000;
    private static final long PRESS_GAP_MS = 120, HOLD_MS = 450, REPEAT_MS = 120, STOP_MS = 160;
    private static final long ECHO_AFTER_UP_MS = 400, ECHO_AFTER_MUTE_MS = 150, ECHO_AFTER_SELF_MS = 300;

    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread workerThread;
    private Handler worker;
    private AudioManager am;
    private SharedPreferences prefs;
    private DynamicsProcessing dp;

    private volatile boolean enabled = true;
    private volatile boolean externalTv = true;
    private volatile String front = "";
    private volatile boolean muted;
    private volatile boolean syncPending;
    private volatile long selfAt = -1;
    private int level = MAX;
    private float currentDb;
    private int rampToken;

    private long lastUpEvt = -1, lastDownEvt = -1, downPressStart = -1, lastMuteEvt = -1;
    private boolean downHolding;

    private WindowManager wm;
    private View osd;
    private ProgressBar bar;
    private TextView label, value;

    private final Runnable hideOsd = () -> { if (osd != null) osd.setAlpha(0f); };

    private final Runnable downTick = new Runnable() {
        @Override public void run() {
            if (!downHolding) return;
            if (SystemClock.uptimeMillis() - lastDownEvt > STOP_MS || level <= 0) {
                downHolding = false;
                return;
            }
            step(-1);
            main.postDelayed(this, REPEAT_MS);
        }
    };

    // Lifecycle

    @Override protected void onServiceConnected() {
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        workerThread = new HandlerThread("volume-sync");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        prefs.registerOnSharedPreferenceChangeListener(this);
        enabled = prefs.getBoolean(K_ENABLED, true);
        externalTv = EXTERNAL_TV.equals(prefs.getString(K_EXTERNAL, EXTERNAL_TV));
        level = clamp(prefs.getInt(K_LEVEL, MAX));

        IntentFilter filter = new IntentFilter(VOLUME_CHANGED);
        filter.addAction(MUTE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, RECEIVER_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
        try {
            buildOsd();
        } catch (RuntimeException e) {
            Log.w(TAG, "volume bar unavailable", e);
        }

        if (enabled) {
            currentDb = targetDb();
            setGain(currentDb);
            sync();
        }

        // No window event arrives for an app that was already in front (a cold boot lands on Live TV).
        main.postDelayed(() -> {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (front.isEmpty() && root != null && root.getPackageName() != null) {
                setFront(root.getPackageName().toString());
            }
        }, 1500);
    }

    @Override public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(this);
        try {
            unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
        }
        if (osd != null) {
            wm.removeView(osd);
            osd = null;
        }
        releaseEffect();
        if (workerThread != null) workerThread.quitSafely();
        super.onDestroy();
    }

    @Override public void onInterrupt() { }

    @Override public void onSharedPreferenceChanged(SharedPreferences p, String key) {
        if (K_ENABLED.equals(key)) {
            enabled = p.getBoolean(K_ENABLED, true);
            if (enabled) {
                currentDb = targetDb();
                setGain(currentDb);
                if (bypassing()) restoreTvVolume(); else sync();
            } else {
                stopDownHold();
                releaseEffect();
                hideOsd.run();
            }
        } else if (K_EXTERNAL.equals(key)) {
            boolean was = externalTv;
            externalTv = EXTERNAL_TV.equals(p.getString(K_EXTERNAL, EXTERNAL_TV));
            if (enabled && onExternalInput() && was != externalTv) {
                if (externalTv) restoreTvVolume(); else saveTvVolumeThenSync();
            }
        }
    }

    // App in front

    private boolean onExternalInput() {
        return LIVE_TV.equals(front);
    }

    private boolean bypassing() {
        return onExternalInput() && externalTv;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.getPackageName() == null) return;
        String pkg = e.getPackageName().toString();
        String cls = e.getClassName() == null ? "" : e.getClassName().toString();

        if (pkg.equals("com.android.systemui") && cls.contains("VolumeDialog")) {
            if (enabled && !bypassing()) showOsd();
            return;
        }
        if (pkg.equals(getPackageName()) && !cls.endsWith("SettingsActivity")) return;
        for (String overlay : OVERLAYS) {
            if (pkg.startsWith(overlay)) return;
        }
        setFront(pkg);
    }

    private void setFront(String pkg) {
        boolean wasExternal = onExternalInput();
        front = pkg;
        boolean isExternal = onExternalInput();
        if (!enabled || !externalTv || wasExternal == isExternal) return;
        if (isExternal) restoreTvVolume(); else saveTvVolumeThenSync();
    }

    private void restoreTvVolume() {
        final int index = prefs.getInt(K_TV_INDEX, 0);
        worker.post(() -> {
            selfAt = SystemClock.uptimeMillis();
            am.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0);
        });
    }

    private void saveTvVolumeThenSync() {
        worker.post(() -> prefs.edit().putInt(K_TV_INDEX, am.getStreamVolume(AudioManager.STREAM_MUSIC)).apply());
        sync();
    }

    // Volume keys, as seen through AudioService broadcasts

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (!enabled || bypassing()) return;
            String action = i.getAction();
            int type = i.getIntExtra(EXTRA_TYPE, -1);
            if (type == AudioManager.STREAM_MUSIC || type == AudioManager.STREAM_VOICE_CALL) showOsd();

            if (onExternalInput()) {
                if (MUTE_CHANGED.equals(action)) lastMuteEvt = SystemClock.uptimeMillis();
                sync();
                return;
            }
            if (MUTE_CHANGED.equals(action)) {
                lastMuteEvt = SystemClock.uptimeMillis();
                // The Mute key mutes every stream; our own reset only touches STREAM_MUSIC.
                if (type == AudioManager.STREAM_SYSTEM) {
                    boolean m = i.getBooleanExtra(EXTRA_MUTED, muted);
                    if (m != muted) {
                        muted = m;
                        changed();
                    }
                }
                return;
            }
            if (type != AudioManager.STREAM_MUSIC) return;
            int now = i.getIntExtra(EXTRA_VALUE, -1);
            int prev = i.getIntExtra(EXTRA_PREV, -1);
            if (now >= 0) onVolumeEvent(now, prev);
        }
    };

    private void onVolumeEvent(int now, int prev) {
        long t = SystemClock.uptimeMillis();
        if (now > prev) {
            lastUpEvt = t;
            stopDownHold();
            step(1);
        } else if (now == 0 && prev == 0) {
            boolean newPress = lastDownEvt < 0 || t - lastDownEvt > PRESS_GAP_MS;
            if (newPress && isEcho(t)) return;
            lastDownEvt = t;
            if (newPress) {
                stopDownHold();
                downPressStart = t;
                step(-1);
            } else if (!downHolding && t - downPressStart >= HOLD_MS) {
                downHolding = true;
                main.post(downTick);
            }
        } else {
            sync();
        }
    }

    private boolean isEcho(long t) {
        return (lastUpEvt >= 0 && t - lastUpEvt < ECHO_AFTER_UP_MS)
                || (lastMuteEvt >= 0 && t - lastMuteEvt < ECHO_AFTER_MUTE_MS)
                || (selfAt >= 0 && t - selfAt < ECHO_AFTER_SELF_MS);
    }

    private void stopDownHold() {
        downHolding = false;
        main.removeCallbacks(downTick);
    }

    private void step(int dir) {
        level = clamp(level + dir);
        muted = false;
        changed();
    }

    private void changed() {
        prefs.edit().putInt(K_LEVEL, level).apply();
        showOsd();
        sync();
        rampTo(targetDb());
    }

    /**
     * Puts STREAM_MUSIC back to 0 and, unless muted, unmutes every stream. The Mute key only checks
     * STREAM_MUSIC, so leaving it auto-muted at 0 would swallow the next press. Runs off the main thread:
     * AudioService is slow on this firmware.
     */
    private void sync() {
        if (syncPending || bypassing()) return;
        syncPending = true;
        worker.post(() -> {
            try {
                if (bypassing()) return;
                if (am.getStreamVolume(AudioManager.STREAM_MUSIC) > 0) {
                    selfAt = SystemClock.uptimeMillis();
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
                }
                if (!muted) {
                    for (int stream = 0; stream <= 11; stream++) {
                        if (am.isStreamMute(stream)) {
                            selfAt = SystemClock.uptimeMillis();
                            am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0);
                        }
                    }
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "sync failed", e);
            } finally {
                syncPending = false;
            }
        });
    }

    // Gain

    private float targetDb() {
        return (muted || level == 0) ? MUTE_DB : -(MAX - level) * STEP_DB;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(MAX, v));
    }

    private void rampTo(float target) {
        final int token = ++rampToken;
        final float from = currentDb;
        final int steps = 4;
        for (int i = 1; i <= steps; i++) {
            final float db = from + (target - from) * i / steps;
            main.postDelayed(() -> {
                if (token != rampToken || !enabled) return;
                setGain(db);
                currentDb = db;
            }, 12L * (i - 1));
        }
    }

    private void setGain(float db) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (dp == null || !dp.hasControl()) createEffect();
                dp.setInputGainAllChannelsTo(db);
                return;
            } catch (RuntimeException e) {
                Log.w(TAG, "effect lost, recreating", e);
                releaseEffect();
            }
        }
    }

    private void createEffect() {
        releaseEffect();
        DynamicsProcessing.Config cfg = new DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                false, 0, false, 0, false, 0, false).build();
        dp = new DynamicsProcessing(1000, 0, cfg);
        dp.setEnabled(true);
    }

    private void releaseEffect() {
        if (dp == null) return;
        try {
            dp.setEnabled(false);
            dp.release();
        } catch (RuntimeException ignored) {
        }
        dp = null;
    }

    // Volume bar

    private int px(float dp) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                getResources().getDisplayMetrics()));
    }

    private void showOsd() {
        if (osd == null) return;
        if (onExternalInput()) {
            label.setText(R.string.osd_external);
            bar.setVisibility(View.INVISIBLE);
            value.setText(R.string.osd_fixed);
        } else {
            label.setText(R.string.osd_label);
            bar.setVisibility(View.VISIBLE);
            bar.setProgress(muted ? 0 : level);
            value.setText(muted || level == 0 ? getString(R.string.osd_mute) : String.valueOf(level));
        }
        osd.setAlpha(1f);
        main.removeCallbacks(hideOsd);
        main.postDelayed(hideOsd, OSD_MS);
    }

    /** Opaque and placed over SystemUI's volume bar, which no app can turn off. */
    private void buildOsd() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(24), 0, px(24), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1C1C1E);
        bg.setCornerRadius(px(28));
        bg.setStroke(px(1), 0x33FFFFFF);
        row.setBackground(bg);

        label = new TextView(this);
        label.setTextColor(0xCCFFFFFF);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        row.addView(label);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(MAX);
        bar.setProgressTintList(ColorStateList.valueOf(Color.WHITE));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(0x55FFFFFF));
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(0, px(6), 1f);
        barParams.setMargins(px(16), 0, px(16), 0);
        row.addView(bar, barParams);

        value = new TextView(this);
        value.setTextColor(Color.WHITE);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        value.setMinWidth(px(64));
        value.setGravity(Gravity.END);
        row.addView(value);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                px(360), px(52),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.y = px(14);
        osd = row;
        osd.setAlpha(0f);
        wm.addView(osd, lp);
    }
}
