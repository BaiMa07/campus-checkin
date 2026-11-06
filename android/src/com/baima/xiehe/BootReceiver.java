package com.baima.xiehe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机 / 应用更新后重新排闹钟（否则重启一次就再也不签了） */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        Logs.init(ctx);
        if (Prefs.get(ctx).enabled()) {
            AlarmScheduler.scheduleAll(ctx);
            Logs.add("开机/更新：已重新排闹钟");
        }
    }
}
