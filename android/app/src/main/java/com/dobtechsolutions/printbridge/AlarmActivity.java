package com.dobtechsolutions.printbridge;

import android.app.Activity;
import android.app.KeyguardManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.dobtechsolutions.printbridge.core.AlertClient;

import java.util.List;

/**
 * Full-screen "New order" screen: turns the screen on (also over the lock screen), lists the
 * waiting orders with a big Accept button each, and closes once nothing is waiting.
 */
public final class AlarmActivity extends Activity implements OrderAlarm.Listener {
    private LinearLayout list;
    private TextView title;

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            if (km != null) km.requestDismissKeyguard(this, null);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        float dp = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#1C1917"));
        int pad = (int) (16 * dp);
        root.setPadding(pad, pad, pad, pad);

        title = new TextView(this);
        title.setTextColor(Color.WHITE);
        title.setTextSize(30);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        sp.topMargin = (int) (12 * dp);
        root.addView(scroll, sp);

        Button mute = new Button(this);
        mute.setText(R.string.alarm_mute);
        mute.setAllCaps(false);
        mute.setTextSize(18);
        mute.setMinHeight((int) (56 * dp));
        mute.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                OrderAlarm.mute(AlarmActivity.this);
                Toast.makeText(AlarmActivity.this, R.string.alarm_muted, Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(mute, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        OrderAlarm.addListener(this);
        onOrdersChanged(OrderAlarm.pending());
    }

    @Override
    protected void onPause() {
        OrderAlarm.removeListener(this);
        super.onPause();
    }

    @Override
    public void onOrdersChanged(List<AlertClient.Order> orders) {
        if (orders.isEmpty()) {
            finish();
            return;
        }
        title.setText(orders.size() == 1 ? getString(R.string.alarm_title_one)
                : getString(R.string.alarm_many, orders.size()));
        list.removeAllViews();
        for (AlertClient.Order o : orders) list.addView(card(o));
    }

    private View card(final AlertClient.Order o) {
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (16 * dp);
        c.setPadding(p, p, p, p);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(12 * dp);
        c.setBackground(bg);

        TextView number = new TextView(this);
        number.setText("#" + o.number);
        number.setTextSize(26);
        number.setTypeface(Typeface.DEFAULT_BOLD);
        number.setTextColor(Color.parseColor("#1C1917"));
        c.addView(number);

        TextView meta = new TextView(this);
        String type = OrderAlarm.typeLabel(this, o);
        meta.setText((type.isEmpty() ? "" : type + " · ") + OrderAlarm.money(o)
                + (o.customer.isEmpty() ? "" : " · " + o.customer));
        meta.setTextSize(20);
        meta.setTextColor(Color.parseColor("#C2410C"));
        meta.setTypeface(Typeface.DEFAULT_BOLD);
        c.addView(meta);

        if (!o.items.isEmpty()) {
            TextView items = new TextView(this);
            items.setText(o.items);
            items.setTextSize(18);
            items.setTextColor(Color.parseColor("#292524"));
            items.setPadding(0, (int) (6 * dp), 0, 0);
            c.addView(items);
        }
        if (!o.notes.isEmpty()) {
            TextView notes = new TextView(this);
            notes.setText(getString(R.string.alarm_notes, o.notes));
            notes.setTextSize(16);
            notes.setTextColor(Color.parseColor("#57534E"));
            c.addView(notes);
        }

        final Button accept = new Button(this);
        accept.setText(R.string.alarm_accept);
        accept.setAllCaps(false);
        accept.setTextSize(22);
        accept.setTypeface(Typeface.DEFAULT_BOLD);
        accept.setTextColor(Color.WHITE);
        accept.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#15803D")));
        accept.setMinHeight((int) (72 * dp));
        accept.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                accept.setEnabled(false);
                accept.setText(R.string.alarm_accepting);
                OrderAlarm.accept(AlarmActivity.this, o.id, new OrderAlarm.AcceptCallback() {
                    @Override
                    public void onDone(boolean ok) {
                        if (!ok) {
                            accept.setEnabled(true);
                            accept.setText(R.string.alarm_accept);
                            Toast.makeText(AlarmActivity.this, R.string.alarm_accept_failed, Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        });
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ap.topMargin = (int) (12 * dp);
        c.addView(accept, ap);

        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.bottomMargin = (int) (12 * dp);
        c.setLayoutParams(cp);
        return c;
    }
}
