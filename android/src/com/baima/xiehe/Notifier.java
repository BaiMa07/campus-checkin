package com.baima.xiehe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 通知渠道与通知构造 */
public class Notifier {

    public static final String CH_RUN = "run";
    public static final String CH_RESULT = "result";
    public static final int ID_RUN = 1001;
    public static final int ID_RESULT = 1002;
    public static final int ID_LOGIN = 1003;

    public static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel a = new NotificationChannel(CH_RUN, "签到进度", NotificationManager.IMPORTANCE_LOW);
            a.setShowBadge(false);
            NotificationChannel b = new NotificationChannel(CH_RESULT, "签到结果", NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(a);
            nm.createNotificationChannel(b);
        }
    }

    private static Notification build(Context c, String ch, String text, boolean ongoing) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(c, ch);
        } else {
            b = new Notification.Builder(c);
        }
        Intent i = new Intent(c, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(c, 1, i, flags);
        b.setSmallIcon(R.drawable.ic_stat_sign)
                .setContentTitle("协和查寝")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi);
        return b.build();
    }

    public static Notification run(Context c, String text) {
        return build(c, CH_RUN, text, true);
    }

    /** 登录失效：点通知直接进自动重登（密码已保存时用） */
    public static void needLogin(Context c, long accountId, String title) {
        ensureChannels(c);
        Intent i = new Intent(c, LoginActivity.class);
        i.putExtra(LoginActivity.EXTRA_ACCOUNT_ID, accountId);
        i.putExtra(LoginActivity.EXTRA_AUTO, true);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(c, 2000 + (int) accountId, i, flags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(c, CH_RESULT);
        } else {
            b = new Notification.Builder(c);
        }
        b.setSmallIcon(R.drawable.ic_stat_sign)
                .setContentTitle("协和查寝：登录已失效")
                .setContentText(title + " · 点这里自动重新登录")
                .setStyle(new Notification.BigTextStyle().bigText(
                        title + " 的登录状态过期了，点这条通知会自动登录并继续签到。"))
                .setAutoCancel(true)
                .setContentIntent(pi);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(ID_LOGIN, b.build());
    }

    /** 过程提示（自动重登中之类），直接复用结果通道 */
    public static void progress(Context c, String text) {
        result(c, text);
    }

    public static void result(Context c, String text) {
        ensureChannels(c);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(ID_RESULT, build(c, CH_RESULT, text, false));
    }
}
