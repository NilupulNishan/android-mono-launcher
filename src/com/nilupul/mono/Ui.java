package com.nilupul.mono;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * mono-style pop-ups: pure black, thin grey border, square corners, JetBrains Mono.
 * Replaces Android's default grey AlertDialogs everywhere in mono.
 */
final class Ui {

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private Ui() {}

    interface Pick {
        void on(int which);
    }

    /** A titled list of choices. Tapping one closes the pop-up and reports which. */
    static void list(Activity a, String title, String[] items, Pick pick) {
        Dialog d = dialog(a);
        LinearLayout col = column(a);
        col.addView(title(a, title));
        LinearLayout rows = new LinearLayout(a);
        rows.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < items.length; i++) {
            final int which = i;
            TextView t = Apps.text(a, items[i], 17, Apps.WHITE);
            t.setPadding(0, Apps.dp(a, 13), 0, Apps.dp(a, 13));
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            Apps.pressable(t);
            t.setOnClickListener(v -> {
                d.dismiss();
                pick.on(which);
            });
            rows.addView(t, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
        ScrollView scroll = new ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(rows);
        col.addView(scroll, new LinearLayout.LayoutParams(MATCH, WRAP));
        show(a, d, col);
    }

    /** A message with up to three text buttons: [neutral]   [cancel] [primary]. */
    static void confirm(Activity a, String title, String message,
                        String primary, Runnable onPrimary,
                        String neutral, Runnable onNeutral) {
        Dialog d = dialog(a);
        LinearLayout col = column(a);
        col.addView(title(a, title));
        TextView m = Apps.text(a, message, 14, Apps.WHITE);
        m.setLineSpacing(0, 1.35f);
        m.setTextIsSelectable(true); // lets the adb command be copied
        m.setPadding(0, Apps.dp(a, 4), 0, Apps.dp(a, 8));
        col.addView(m, new LinearLayout.LayoutParams(MATCH, WRAP));
        col.addView(buttons(a, d, primary, onPrimary, neutral, onNeutral));
        show(a, d, col);
    }

    interface Text {
        void on(String value);
    }

    /** A single-line text box with save / reset / cancel. */
    static void input(Activity a, String title, String value, Text onSave, Runnable onReset) {
        Dialog d = dialog(a);
        LinearLayout col = column(a);
        col.addView(title(a, title));
        EditText e = new EditText(a);
        e.setTypeface(Apps.font(a));
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        e.setTextColor(Apps.WHITE);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setText(value);
        e.setSelectAllOnFocus(true);
        GradientDrawable line = new GradientDrawable();
        line.setColor(Apps.BLACK);
        line.setStroke(Apps.dp(a, 1), Apps.DIM);
        e.setBackground(line);
        int p = Apps.dp(a, 10);
        e.setPadding(p, p, p, p);
        col.addView(e, new LinearLayout.LayoutParams(MATCH, WRAP));
        col.addView(buttons(a, d, "save", () -> onSave.on(e.getText().toString()), "reset", onReset));
        show(a, d, col);
        e.requestFocus();
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
    }

    // -------------------------------------------------------------- parts

    private static Dialog dialog(Activity a) {
        Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setCanceledOnTouchOutside(true);
        return d;
    }

    private static LinearLayout column(Activity a) {
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = Apps.dp(a, 22);
        col.setPadding(p, Apps.dp(a, 18), p, Apps.dp(a, 10));
        return col;
    }

    private static TextView title(Activity a, String s) {
        TextView t = Apps.text(a, s, 13, Apps.GREY);
        t.setPadding(0, 0, 0, Apps.dp(a, 8));
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private static LinearLayout buttons(Activity a, Dialog d, String primary, Runnable onPrimary,
                                        String neutral, Runnable onNeutral) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Apps.dp(a, 10), 0, 0);
        if (neutral != null) row.addView(button(a, d, neutral, Apps.GREY, onNeutral));
        row.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1f));
        if (onPrimary != null) row.addView(button(a, d, "cancel", Apps.GREY, null));
        row.addView(button(a, d, primary, Apps.WHITE, onPrimary));
        return row;
    }

    private static TextView button(Activity a, Dialog d, String label, int color, Runnable action) {
        TextView b = Apps.text(a, "[ " + label + " ]", 15, color);
        int p = Apps.dp(a, 10);
        b.setPadding(p, Apps.dp(a, 12), p, Apps.dp(a, 12));
        Apps.pressable(b);
        b.setOnClickListener(v -> {
            d.dismiss();
            if (action != null) action.run();
        });
        return b;
    }

    private static void show(Activity a, Dialog d, View content) {
        d.setContentView(content);
        Window w = d.getWindow();
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Apps.BLACK);
        bg.setStroke(Apps.dp(a, 1), Apps.DIM);
        w.setBackgroundDrawable(bg);
        w.setDimAmount(0.7f);
        d.show();
        int width = a.getResources().getDisplayMetrics().widthPixels - 2 * Apps.dp(a, 24);
        w.setLayout(width, WRAP);
    }
}
