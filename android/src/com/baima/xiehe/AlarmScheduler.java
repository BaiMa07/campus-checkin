package com.baima.xiehe;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** 每天到点唤醒：主闹钟 + 30 分钟 + 50 分钟两个冗余，防止一次没跑起来就漏签 */
public class AlarmScheduler {

    private static final int[] OFFSETS = {0, 30, 50};

    public static void scheduleAll(Context ctx) {
        Prefs p = Prefs.get(ctx);
        int h = 21, m = 20;
        try {
            String[] parts = p.triggerTime().split(":");
            h = Integer.parseInt(parts[0].trim());
            m = Integer.parseInt(parts[1].trim());
        } catch (Exception ignored) { }

        long now = System.currentTimeMillis();
        for (int i = 0; i < OFFSETS.length; i++) {
            Calendar c = Calendar.getInstance();
            c.set(Calendar.HOUR_OF_DAY, h);
            c.set(Calendar.MINUTE, m);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            c.add(Calendar.MINUTE, OFFSETS[i]);
            if (c.getTimeInMillis() <= now) c.add(Calendar.DAY_OF_MONTH, 1);
            set(ctx, i, c.getTimeInMillis());
        }
        Logs.add("已排闹钟：每天 " + p.triggerTime() + "（+30 分、+50 分各一次兜底）");
    }

    private static void set(Context ctx, int slot, long at) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(ctx, AlarmReceiver.class);
        i.putExtra("slot", slot);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 1000 + slot, i, flags);

        boolean exact = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            exact = am.canScheduleExactAlarms();
        }
        try {
            if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    public static void cancelAll(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        for (int i = 0; i < OFFSETS.length; i++) {
            Intent intent = new Intent(ctx, AlarmReceiver.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(ctx, 1000 + i, intent, flags);
            am.cancel(pi);
        }
        Logs.add("已取消所有闹钟");
    }

    public static String nextText(Context ctx) {
        Prefs p = Prefs.get(ctx);
        int h = 21, m = 20;
        try {
            String[] parts = p.triggerTime().split(":");
            h = Integer.parseInt(parts[0].trim());
            m = Integer.parseInt(parts[1].trim());
        } catch (Exception ignored) { }
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, h);
        c.set(Calendar.MINUTE, m);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_MONTH, 1);
        return new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(c.getTimeInMillis()));
    }
}
