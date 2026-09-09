package com.example.androidmcp;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small native view vocabulary shared by the three screens. No animation or background work. */
final class UiKit {
    static final int BG = 0xff0b1217, CARD = 0xff152128, RAISED = 0xff1d2c34;
    static final int TEXT = 0xffedf5f5, MUTED = 0xffa2b4bd, ACCENT = 0xff64dfc4;
    static final int BORDER = 0xff2b3c44, SOFT = 0xff173c36, WARNING = 0xffffd18a;
    static final int DANGER = 0xffffb4ab, DANGER_BG = 0xff462d32;
    private final Context context;
    UiKit(Context context) { this.context = context; }
    int dp(float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    LinearLayout column() { LinearLayout view = new LinearLayout(context); view.setOrientation(LinearLayout.VERTICAL); return view; }
    LinearLayout row() { LinearLayout view = new LinearLayout(context); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    GradientDrawable shape(int color, int stroke, float radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color); background.setCornerRadius(dp(radius));
        if (stroke != 0) background.setStroke(dp(1), stroke);
        return background;
    }
    RippleDrawable ripple(int color, int stroke, float radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x3064dfc4), shape(color, stroke, radius), shape(0xffffffff, 0, radius));
    }
    TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setFontFeatureSettings("kern");
        view.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        view.setIncludeFontPadding(false); view.setLineSpacing(dp(2), 1.05f);
        return view;
    }
    TextView badge(String value) {
        TextView view = text(value, 12, ACCENT, true);
        view.setPadding(dp(10), dp(7), dp(10), dp(7)); view.setBackground(shape(SOFT, 0, 10));
        return view;
    }
    void add(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top); parent.addView(child, params);
    }
    void gap(LinearLayout parent, int height) { View gap = new View(context); parent.addView(gap, new LinearLayout.LayoutParams(1, dp(height))); }
    LinearLayout card(LinearLayout parent) {
        LinearLayout card = column(); card.setPadding(dp(20), dp(20), dp(20), dp(20));
        card.setBackground(shape(CARD, BORDER, 24));
        add(parent, card, 14); return card;
    }
    Button button(String label, boolean primary, Runnable action) {
        Button button = new Button(context);
        button.setText(label); button.setTextSize(15); button.setAllCaps(false);
        button.setSingleLine(false); button.setEllipsize(null);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setMinHeight(dp(54)); button.setMinimumHeight(dp(54));
        button.setPadding(dp(16), dp(12), dp(16), dp(12));
        button.setStateListAnimator(null); button.setElevation(0);
        paintButton(button, primary ? ACCENT : RAISED, primary ? BG : TEXT);
        button.setOnClickListener(v -> action.run()); return button;
    }
    void paintButton(Button button, int background, int foreground) {
        button.setTextColor(foreground); button.setBackground(ripple(background, 0, 16));
    }
    View icon(String name, int color, int size) { return new Symbol(context, name, color, dp(size)); }
    void heading(LinearLayout card, String icon, String title, String description) {
        LinearLayout row = row();
        View symbol = icon(icon, ACCENT, 24);
        row.addView(symbol, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView label = text(title, 17, TEXT, true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1); params.leftMargin = dp(12);
        row.addView(label, params); card.addView(row);
        if (description != null && !description.isEmpty()) add(card, text(description, 14, MUTED, false), 12);
    }

    private static final class Symbol extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path outline = new Path();
        private final String name;
        private final int size;
        Symbol(Context context, String name, int color, int size) {
            super(context); this.name = name; this.size = size;
            paint.setColor(color); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.65f);
            paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        @Override protected void onMeasure(int width, int height) { setMeasuredDimension(resolveSize(size, width), resolveSize(size, height)); }
        private void line(Canvas c, float a, float b, float x, float y) { c.drawLine(a, b, x, y, paint); }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c); c.save(); c.scale(getWidth() / 24f, getHeight() / 24f);
            outline.reset();
            switch (name) {
                case "home":
                    c.drawRoundRect(3, 3, 10, 10, 2, 2, paint); c.drawRoundRect(14, 3, 21, 10, 2, 2, paint);
                    c.drawRoundRect(3, 14, 10, 21, 2, 2, paint); c.drawRoundRect(14, 14, 21, 21, 2, 2, paint); break;
                case "shield":
                    Path shield = outline; shield.moveTo(12, 2); shield.lineTo(21, 6); shield.lineTo(20, 14);
                    shield.quadTo(18, 19, 12, 22); shield.quadTo(6, 19, 4, 14); shield.lineTo(3, 6); shield.close(); c.drawPath(shield, paint);
                    line(c, 8, 12, 11, 15); line(c, 11, 15, 16, 9); break;
                case "settings":
                    c.drawCircle(12, 12, 6, paint); c.drawCircle(12, 12, 2, paint);
                    for (int i = 0; i < 8; i++) { double a = i * Math.PI / 4; line(c, 12 + (float)Math.cos(a)*6, 12 + (float)Math.sin(a)*6, 12 + (float)Math.cos(a)*9, 12 + (float)Math.sin(a)*9); } break;
                case "wifi":
                    c.drawArc(1, 4, 23, 24, 225, 90, false, paint); c.drawArc(5, 9, 19, 23, 225, 90, false, paint);
                    c.drawArc(9, 14, 15, 22, 225, 90, false, paint); c.drawCircle(12, 20, .6f, paint); break;
                case "remote":
                    c.drawCircle(12, 12, 9, paint); c.drawOval(8, 3, 16, 21, paint); line(c, 3, 12, 21, 12); break;
                case "folder":
                    Path folder = outline; folder.moveTo(3, 7); folder.lineTo(3, 19); folder.lineTo(21, 19); folder.lineTo(21, 7); folder.lineTo(12, 7); folder.lineTo(10, 4); folder.lineTo(3, 4); folder.close(); c.drawPath(folder, paint); break;
                case "key":
                    c.drawCircle(8, 9, 5, paint); line(c, 12, 12, 21, 21); line(c, 17, 17, 19, 15); line(c, 19, 19, 21, 17); break;
                case "bell":
                    c.drawArc(6, 3, 18, 16, 180, 180, false, paint); line(c, 6, 9, 5, 18); line(c, 18, 9, 19, 18); line(c, 5, 18, 19, 18); c.drawArc(10, 18, 14, 23, 0, 180, false, paint); break;
                case "terminal":
                    c.drawRoundRect(2, 4, 22, 20, 3, 3, paint); line(c, 6, 9, 9, 12); line(c, 9, 12, 6, 15); line(c, 13, 15, 18, 15); break;
                case "update":
                    c.drawArc(4, 4, 20, 20, 30, 300, false, paint); line(c, 19, 3, 20, 9); line(c, 20, 9, 14, 8); break;
                default:
                    c.drawRoundRect(6, 2, 18, 22, 3, 3, paint); line(c, 10, 5, 14, 5); line(c, 10, 19, 14, 19); break;
            }
            c.restore();
        }
    }
}
