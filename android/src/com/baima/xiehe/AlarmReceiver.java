package com.baima.xiehe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 闹钟触发：先排明天的闹钟，再拉起前台服务；被系统拒绝就用 goAsync 单发兜底 */
public class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(final Context ctx, Intent intent) {
        Logs.init(ctx);
        final Prefs p = Prefs.get(ctx);
        if (!p.enabled()) {
            Logs.add("闹钟触发，但后台守护已关闭");
            return;
        }

        AlarmScheduler.scheduleAll(ctx);

        Intent svc = new Intent(ctx, SignService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(svc);
            } else {
                ctx.startService(svc);
            }
        } catch (Throwable t) {
            Logs.add("后台启动服务被拒（" + t.getClass().getSimpleName() + "），改用单次直签");
            final PendingResult pr = goAsync();
            new Thread(new Runnable() {
                public void run() {
                    try {
                        SignService.oneShot(ctx, false);
                    } catch (Throwable e) {
                        Logs.add("单次直签异常：" + e);
                    } finally {
                        pr.finish();
                    }
                }
            }).start();
        }
    }
}
