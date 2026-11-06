package com.baima.xiehe;

import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 前台服务：窗口内轮询全部账号，都签上就停 */
public class SignService extends Service {

    public static volatile boolean running = false;
    private static final long WINDOW_MS = 45 * 60 * 1000L;

    private Thread worker;
    private PowerManager.WakeLock wakeLock;
    private boolean force;
    private volatile boolean stopped;

    @Override
    public void onCreate() {
        super.onCreate();
        Logs.init(this);
        Notifier.ensureChannels(this);
        Net.init(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) force = intent.getBooleanExtra("force", false);
        if (running) {
            Logs.add("已有签到任务在跑，忽略本次启动");
            return START_NOT_STICKY;
        }
        running = true;
        startForeground(Notifier.ID_RUN, Notifier.run(this, "正在启动…"));
        Logs.add(force ? "手动签到开始" : "后台签到开始");
        worker = new Thread(new Runnable() {
            public void run() { loop(); }
        }, "sign-loop");
        worker.start();
        return START_NOT_STICKY;
    }

    /** 写日志 + 更新通知 */
    private void update(String text) {
        Logs.add(text);
        notifyOnly(text);
    }

    /** 只更新通知，不重复写日志（SignCore 已经记过结果了） */
    private void notifyOnly(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(Notifier.ID_RUN, Notifier.run(this, text));
        } catch (Throwable ignored) { }
    }

    /** 可被打断的等待：返回 false 表示期间被要求停止（关开关 / 服务销毁） */
    private boolean sleep(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (!stopped && System.currentTimeMillis() < end) {
            try {
                Thread.sleep(Math.min(400, end - System.currentTimeMillis()));
            } catch (InterruptedException e) {
                return false;
            }
        }
        return !stopped;
    }

    @Override
    public void onDestroy() {
        stopped = true;
        if (worker != null) worker.interrupt();
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) { }
        running = false;
        Logs.add("签到服务已停止");
        super.onDestroy();
    }

    private static String now() {
        return new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date());
    }

    private void loop() {
        Prefs p = Prefs.get(this);
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xiehe:sign");
                wakeLock.acquire(WINDOW_MS);
            }
        } catch (Throwable ignored) { }

        try {
            long deadline = System.currentTimeMillis() + WINDOW_MS;
            // 定时任务错峰：每天同一秒提交太整齐，前 5~120 秒随机等一下
            if (!force) {
                int jitter = 5 + new java.util.Random().nextInt(86);
                Logs.add("错峰启动：随机等待 " + jitter + " 秒再签");
                if (!sleep(jitter * 1000L)) return;
            }
            for (int round = 0; round < 46 && System.currentTimeMillis() < deadline; round++) {
                if (stopped) break;
                if (!force && !p.enabled() && round > 0) {
                    update("后台守护已关闭，退出");
                    break;
                }

                List<Account> list = AccountStore.get(this).list();
                if (list.isEmpty()) {
                    update("还没有账号，请先在 App 里添加");
                    Notifier.result(this, "还没有账号，请打开 App 添加");
                    break;
                }

                int okN = 0;
                int expiredN = 0;
                int softN = 0;
                for (int i = 0; i < list.size(); i++) {
                    Account a = list.get(i);
                    SignCore.Result r = SignCore.sign(this, a);
                    notifyOnly("【" + a.title() + "】" + r.msg);
                    if (r.ok) okN++;
                    if (r.expired) expiredN++;
                    if (r.soft) softN++;
                    // 账号之间留间隔（最后一个不等）
                    if (i < list.size() - 1) {
                        int gap = p.gapSeconds();
                        if (gap > 0 && !sleep(gap * 1000L)) break;
                    }
                }

                if (okN == list.size()) {
                    update("全部账号已签到（" + okN + "/" + list.size() + "）");
                    Notifier.result(this, "全部账号签到完成 " + okN + "/" + list.size());
                    p.setLastResult("全部已签 " + now());
                    break;
                }
                if (softN == list.size()) {
                    // 冻结中的号要等解冻再接着试，不能直接收工
                    long soonest = Long.MAX_VALUE;
                    for (Account x : list) {
                        if (Guard.frozen(x) && x.blockedUntil < soonest) soonest = x.blockedUntil;
                    }
                    if (soonest != Long.MAX_VALUE) {
                        long waitMs = Math.max(5000,
                                Math.min(120000, soonest - System.currentTimeMillis() + 3000));
                        update("账号冻结中，" + (waitMs / 1000) + " 秒后自动再试");
                        if (!sleep(waitMs)) break;
                        continue;
                    }
                    // 全部是"还没到签到时段/今天请假"，没必要继续等
                    update("暂时不用签：" + list.get(0).lastResult);
                    break;
                }
                if (expiredN == list.size()) {
                    // 刚登录过的号在冷却，等冷却过了再试，别立刻又去登
                    boolean cooling = false;
                    for (Account x : list) {
                        if (Guard.inCooldown(x)) { cooling = true; break; }
                    }
                    if (cooling && deadline - System.currentTimeMillis() > 90000) {
                        update("登录冷却中，60 秒后再试");
                        if (!sleep(60000)) break;
                        continue;
                    }
                    update("所有账号的登录都失效了，需要重新登录");
                    Notifier.result(this, "会话全部失效，请打开 App 重新登录");
                    p.setLastResult("会话失效 " + now());
                    break;
                }

                update("本轮完成 " + okN + "/" + list.size() + "，60 秒后重试");
                if (!sleep(60000)) break;
            }
        } catch (Throwable t) {
            Logs.add("异常：" + t);
        } finally {
            try {
                if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            } catch (Throwable ignored) { }
            running = false;
            try { stopForeground(true); } catch (Throwable ignored) { }
            stopSelf();
        }
    }

    /** 闹钟后台上拉前台服务被系统拒绝时的兜底：在广播里直接签（约 8 秒预算） */
    public static void oneShot(Context ctx, boolean force) {
        Prefs p = Prefs.get(ctx);
        if (!force && !p.enabled()) return;
        List<Account> list = AccountStore.get(ctx).list();
        if (list.isEmpty()) {
            Logs.add("兜底单次：还没有账号");
            return;
        }
        int ok = 0;
        for (Account a : list) {
            SignCore.Result r = SignCore.sign(ctx, a);
            Logs.add("兜底单次【" + a.title() + "】" + r.msg);
            if (r.ok) ok++;
        }
        if (ok > 0) Notifier.result(ctx, "签到完成 " + ok + "/" + list.size());
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
