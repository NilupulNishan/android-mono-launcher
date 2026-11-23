package com.nilupul.mono;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The "mono" icon in your app list: one big on/off button.
 * Off means the phone behaves exactly as if mono were not installed.
 */
public class SwitchActivity extends Activity {

    private static final int REQ_HOME_ROLE = 1;

    private TextView status;
    private TextView button;
    private TextView message;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        int pad = Apps.dp(this, 32);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad);

        TextView title = Apps.text(this, "mono", 34, Apps.WHITE);
        TextView sub = Apps.text(this, "black & white text home screen", 14, Apps.GREY);
        sub.setPadding(0, Apps.dp(this, 10), 0, 0);

        status = Apps.text(this, "", 16, Apps.WHITE);
        status.setLineSpacing(0, 1.3f);
        status.setPadding(0, Apps.dp(this, 48), 0, 0);

        button = Apps.text(this, "", 22, Apps.WHITE);
        button.setGravity(Gravity.CENTER);
        button.setPadding(0, Apps.dp(this, 18), 0, Apps.dp(this, 18));
        GradientDrawable border = new GradientDrawable();
        border.setColor(Apps.BLACK);
        border.setStroke(Apps.dp(this, 1), Apps.WHITE);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), border, null));
        button.setClickable(true);
        button.setOnClickListener(v -> toggle());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Apps.dp(this, 24);

        message = Apps.text(this, "", 14, Apps.WHITE);
        message.setLineSpacing(0, 1.3f);
        message.setPadding(0, Apps.dp(this, 16), 0, 0);

        TextView settings = Apps.text(this, "or choose in home app settings →", 14, Apps.GREY);
        settings.setPadding(0, Apps.dp(this, 16), 0, Apps.dp(this, 16));
        Apps.pressable(settings);
        settings.setOnClickListener(v -> openHomeSettings());

        TextView note = Apps.text(this,
                "off = your phone works exactly as before.\n\n"
                + "mono asks for no permissions, has no internet access and runs nothing "
                + "in the background. it only lists your apps and opens them.\n\n"
                + "to remove it completely: settings → apps → mono → uninstall.",
                13, Apps.GREY);
        note.setLineSpacing(0, 1.35f);
        note.setPadding(0, Apps.dp(this, 32), 0, 0);

        col.addView(title);
        col.addView(sub);
        col.addView(status);
        col.addView(button, blp);
        col.addView(message);
        col.addView(settings);
        col.addView(note);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Apps.BLACK);
        scroll.addView(col);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // If turning on was cancelled, don't leave mono's home screen switched on.
        if (Apps.homeEnabled(this) && !Apps.roleHeld(this)) Apps.setHomeEnabled(this, false);
        render();
    }

    private void render() {
        boolean on = Apps.isOn(this);
        status.setText(on ? "status: on\nmono is your home screen."
                          : "status: off\nyour normal OPPO home screen is in use.");
        button.setText(on ? "turn off" : "turn on");
    }

    private void toggle() {
        message.setText("");
        if (Apps.isOn(this)) {
            Apps.turnOff(this);
            render();
            return;
        }
        Apps.setHomeEnabled(this, true);
        RoleManager rm = getSystemService(RoleManager.class);
        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) {
            try {
                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_HOME_ROLE);
                return;
            } catch (RuntimeException ignored) {
                // fall through to the settings screen
            }
        }
        openHomeSettings();
    }

    private void openHomeSettings() {
        Apps.setHomeEnabled(this, true); // so mono shows up in the list
        Intent i = new Intent(Settings.ACTION_HOME_SETTINGS);
        if (!Apps.tryStart(this, i, null)) {
            Apps.tryStart(this, new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS), "can't open settings");
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQ_HOME_ROLE) return;
        if (Apps.roleHeld(this)) {
            Apps.goHome(this); // show mono right away
        } else {
            message.setText("not switched. tap \"choose in home app settings\" below and pick mono.");
        }
    }
}
