package io.github.jpablolazo.digitalvolume;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Turns the service on or off and picks what the volume keys do on HDMI, AV and Live TV. */
public class SettingsActivity extends Activity {
    private SharedPreferences prefs;
    private TextView enabledValue, externalValue, status;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(VolumeService.PREFS, MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF000000);
        root.setPadding(px(64), px(48), px(64), px(32));

        TextView title = text(getString(R.string.app_name), 34, 0xFFFFFFFF);
        root.addView(title);
        status = text("", 15, 0x99FFFFFF);
        status.setPadding(0, px(6), 0, px(28));
        root.addView(status);

        View r1 = row(getString(R.string.set_enabled));
        enabledValue = (TextView) ((LinearLayout) r1).getChildAt(1);
        r1.setOnClickListener(v -> {
            prefs.edit().putBoolean(VolumeService.K_ENABLED, !prefs.getBoolean(VolumeService.K_ENABLED, true)).apply();
            refresh();
        });
        root.addView(r1);

        View r2 = row(getString(R.string.set_external));
        externalValue = (TextView) ((LinearLayout) r2).getChildAt(1);
        r2.setOnClickListener(v -> {
            boolean tv = VolumeService.EXTERNAL_TV.equals(prefs.getString(VolumeService.K_EXTERNAL, VolumeService.EXTERNAL_TV));
            prefs.edit().putString(VolumeService.K_EXTERNAL, tv ? VolumeService.EXTERNAL_FIXED : VolumeService.EXTERNAL_TV).apply();
            refresh();
        });
        root.addView(r2);

        TextView help = text(getString(R.string.set_help), 15, 0x88FFFFFF);
        help.setPadding(px(4), px(28), 0, 0);
        help.setLineSpacing(0, 1.25f);
        root.addView(help);

        setContentView(root);
        r1.requestFocus();
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean on = prefs.getBoolean(VolumeService.K_ENABLED, true);
        boolean tv = VolumeService.EXTERNAL_TV.equals(prefs.getString(VolumeService.K_EXTERNAL, VolumeService.EXTERNAL_TV));
        enabledValue.setText(on ? R.string.on : R.string.off);
        externalValue.setText(tv ? R.string.external_tv : R.string.external_fixed);
        String services = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean bound = services != null && services.contains(getPackageName());
        status.setText(!bound ? getString(R.string.status_service_off)
                : getString(R.string.status_level, prefs.getInt(VolumeService.K_LEVEL, VolumeService.MAX), VolumeService.MAX));
    }

    private View row(String name) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(24), px(18), px(24), px(18));
        row.setFocusable(true);
        row.setClickable(true);
        StateListDrawable bg = new StateListDrawable();
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(0x33FFFFFF);
        focused.setCornerRadius(px(18));
        focused.setStroke(px(1), 0x55FFFFFF);
        GradientDrawable idle = new GradientDrawable();
        idle.setColor(0xFF111113);
        idle.setCornerRadius(px(18));
        bg.addState(new int[] { android.R.attr.state_focused }, focused);
        bg.addState(new int[] {}, idle);
        row.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = px(12);
        row.setLayoutParams(lp);

        TextView n = text(name, 20, 0xFFFFFFFF);
        row.addView(n, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = text("", 18, 0xCCFFFFFF);
        v.setGravity(Gravity.END);
        row.addView(v);
        return row;
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private int px(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
