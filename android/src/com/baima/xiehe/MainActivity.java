package com.baima.xiehe;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_EXPORT = 2001;

    private TextView tvStatus;
    private TextView tvLog;
    private TextView tvScheduleSub;
    private static long lastCheck;

    private static final int REQ_BATCH = 4001;
    private java.util.List<Account> batchList = new java.util.ArrayList<Account>();
    private int batchIdx;
    private boolean batchRunning;
    private int batchOk;
    private int batchFail;
    private Switch swSchedule;
    private boolean binding;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        Logs.init(this);
        Notifier.ensureChannels(this);
        Prefs.get(this).migrateIfNeeded();
        Net.init(this);
        AccountStore.get(this).dedupe();
        Storage.autoTrim(this);

        tvStatus = (TextView) findViewById(R.id.tvStatus);
        tvLog = (TextView) findViewById(R.id.tvLog);
        tvScheduleSub = (TextView) findViewById(R.id.tvScheduleSub);
        swSchedule = (Switch) findViewById(R.id.swSchedule);

        findViewById(R.id.btnLogin).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                LoginPrompt.show(MainActivity.this, 0);
            }
        });
        findViewById(R.id.btnAccounts).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, AccountsActivity.class));
            }
        });
        findViewById(R.id.btnLoc).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showLocDialog(); }
        });
        findViewById(R.id.btnCalendar).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, CalendarActivity.class));
            }
        });
        findViewById(R.id.btnRefresh).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { refresh(); }
        });
        findViewById(R.id.btnRelogin).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startBatchRelogin(); }
        });
        findViewById(R.id.btnSign).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startSign(); }
        });
        findViewById(R.id.btnNet).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showNetDialog(); }
        });
        swSchedule.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (binding) return;
                setSchedule(checked);
            }
        });
        findViewById(R.id.btnNoti).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { askNotification(); }
        });
        findViewById(R.id.btnTime).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showTimeDialog(); }
        });
        findViewById(R.id.btnAlarm).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { askExactAlarm(); }
        });
        findViewById(R.id.btnBattery).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { askBattery(); }
        });

        findViewById(R.id.btnOverlay).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { askOverlay(); }
        });

        findViewById(R.id.btnClearCache).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { confirmClearCache(); }
        });
        findViewById(R.id.btnClearLog).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { confirmClearLog(); }
        });
        findViewById(R.id.btnExportLog).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { exportLog(); }
        });

        Logs.setListener(new Logs.Listener() {
            public void onLog(final String line) {
                ui.post(new Runnable() {
                    public void run() {
                        tvLog.append(line + "\n");
                    }
                });
            }
        });

        tvLog.setText(Logs.dump());
        renderToggle();
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderToggle();
        // 先用本地数据把界面刷出来，再决定要不要真的去问服务器
        tvStatus.setText(buildStatus());
        long now = System.currentTimeMillis();
        if (now - lastCheck > 3 * 60 * 1000L) {
            lastCheck = now;
            refresh(true);
        }
    }

    /* ---------------- 状态 ---------------- */

    private void renderToggle() {
        Prefs p = Prefs.get(this);
        boolean on = p.enabled();
        binding = true;
        swSchedule.setChecked(on);
        binding = false;
        tvScheduleSub.setText(on
                ? getString(R.string.schedule_on, p.triggerTime())
                : getString(R.string.schedule_off_hint));
    }

    /** 打开/关闭定时自动打卡 */
    private void setSchedule(boolean on) {
        Prefs p = Prefs.get(this);
        p.setEnabled(on);
        if (on) {
            AlarmScheduler.scheduleAll(this);
            Logs.add("定时自动打卡已开启：每天 " + p.triggerTime());
            toast("已开启，每天 " + p.triggerTime() + " 自动签到");
        } else {
            AlarmScheduler.cancelAll(this);
            // 立刻停掉可能正在跑的服务，不然要等到下一轮才退出
            try { stopService(new Intent(this, SignService.class)); } catch (Throwable ignored) { }
            Logs.add("定时自动打卡已关闭，闹钟已全部取消");
            toast("已关闭自动签到（仍可手动签）");
        }
        renderToggle();
        refresh();
    }

    /* ---------------- 一键重新登录 ---------------- */

    /** 把所有存过密码的账号排队，依次自动登录（每个都是自动填表 + 自动过滑块） */
    private void startBatchRelogin() {
        if (batchRunning) {
            toast("正在批量登录中…");
            return;
        }
        java.util.List<Account> all = AccountStore.get(this).list();
        batchList.clear();
        int noPwd = 0;
        int frozen = 0;
        for (Account a : all) {
            String pwd = Secure.decrypt(a.pwdEnc);
            if (Guard.frozen(a)) {
                frozen++;
            } else if (pwd.length() > 0 && a.studentNo != null && a.studentNo.length() > 0) {
                batchList.add(a);
            } else if (all.size() > 0) {
                noPwd++;
            }
        }
        if (batchList.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("一键重新登录")
                    .setMessage(frozen > 0
                            ? "有 " + frozen + " 个账号正在风控冻结中，这几天先别动它，等解冻后再登录。"
                            : "还没有存过密码的账号。先点「登录 / 添加账号」，输入一次学号和密码，"
                            + "之后就能用这个按钮一次性全部重新登录。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        String msg = "将依次自动登录 " + batchList.size() + " 个账号，每个大约 10 秒，"
                + "账号之间会随机停 8~15 秒（登太密会被学校判定为会话异常）。"
                + "全程不用操作（自动填表、自动过滑块）。"
                + (noPwd > 0 ? "\n\n另有 " + noPwd + " 个账号没存密码，会被跳过。" : "")
                + (frozen > 0 ? "\n\n" + frozen + " 个账号正在冻结中，这次跳过。" : "")
                + "\n\n登录成功后不会立刻签到，只刷新状态；要签到请点「立即签到」。";
        new AlertDialog.Builder(this)
                .setTitle("一键重新登录")
                .setMessage(msg)
                .setPositiveButton("开始", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        batchOk = 0;
                        batchFail = 0;
                        batchIdx = 0;
                        batchRunning = true;
                        batchNext();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void batchNext() {
        if (!batchRunning) return;
        if (batchIdx >= batchList.size()) {
            batchRunning = false;
            String tip = "全部处理完：成功 " + batchOk + " 个"
                    + (batchFail > 0 ? "，失败 " + batchFail + " 个" : "");
            toast(tip);
            Logs.add("一键重新登录结束：" + tip);
            refresh();
            return;
        }
        Account a = batchList.get(batchIdx);
        String pwd = Secure.decrypt(a.pwdEnc);
        if (pwd.length() == 0 || Guard.frozen(a)) {
            batchIdx++;
            batchNext();
            return;
        }
        // 刚登录过的号再登一次，等于故意制造"会话异常"，直接跳过
        if (Guard.inCooldown(a)) {
            Logs.add("【" + a.title() + "】刚登录过，跳过本次重登");
            batchIdx++;
            batchNext();
            return;
        }
        toast("正在登录第 " + (batchIdx + 1) + "/" + batchList.size() + " 个：" + a.title());
        Intent i = new Intent(this, LoginActivity.class);
        i.putExtra(LoginActivity.EXTRA_ACCOUNT_ID, a.id);
        i.putExtra(LoginActivity.EXTRA_USER, a.studentNo);
        i.putExtra(LoginActivity.EXTRA_PWD, pwd);
        i.putExtra(LoginActivity.EXTRA_AUTO, true);
        i.putExtra(LoginActivity.EXTRA_BATCH, true);
        startActivityForResult(i, REQ_BATCH);
    }

    /** 真正的刷新：逐个账号问一次服务器，把状态和失败原因更新掉 */
    private void refresh() {
        refresh(false);
    }

    private void refresh(final boolean silent) {
        tvStatus.setText("正在检查各账号的登录状态…");
        new Thread(new Runnable() {
            public void run() {
                AccountStore st = AccountStore.get(MainActivity.this);
                List<Account> list = st.list();
                int valid = 0;
                for (Account a : list) {
                    if (a.cookie == null || a.cookie.length() == 0) {
                        a.lastResult = "还没登录，点「登录 / 添加账号」";
                        a.lastOk = Account.STATE_FAIL;
                        st.update(a);
                        continue;
                    }
                    // 冻结期不发请求，免得把冻结时间越撞越长
                    if (Guard.frozen(a)) {
                        a.lastResult = "账号被冻结，" + Guard.untilText(a.blockedUntil) + " 后恢复";
                        a.lastOk = Account.STATE_FAIL;
                        st.update(a);
                        continue;
                    }
                    KqApi.Resp r = KqApi.getKqInfo(a.cookie, a.ua);
                    if (r.state == KqApi.State.OK && r.model != null) {
                        valid++;
                        if (r.model.hasSigned) {
                            a.lastResult = "今天已签到";
                            a.lastOk = Account.STATE_OK;
                        } else if (r.model.inWindow) {
                            a.lastResult = "已登录，可以签到";
                            a.lastOk = Account.STATE_NONE;
                        } else {
                            a.lastResult = "已登录，未到签到时段（" + r.model.start + "-" + r.model.end + "）";
                            a.lastOk = Account.STATE_NONE;
                        }
                    } else if (r.state == KqApi.State.BLOCKED) {
                        a.blockedUntil = r.blockedUntil;
                        a.lastResult = "账号被冻结，" + Guard.untilText(a.blockedUntil) + " 后恢复";
                        a.lastOk = Account.STATE_FAIL;
                        Logs.add("【" + a.title() + "】" + a.lastResult + "｜" + r.msg);
                    } else if (r.state == KqApi.State.EXPIRED) {
                        a.lastResult = "登录已失效，需要重新登录";
                        a.lastOk = Account.STATE_FAIL;
                    } else {
                        // 网络问题不算"签到失败"，保留原来的状态，只提示这次没查通
                        a.lastResult = "这次没查通（" + r.msg + "），状态保持不变";
                    }
                    st.update(a);
                }
                final String text = buildStatus();
                final String tip = list.isEmpty() ? "还没有账号" : ("已检查 " + list.size()
                        + " 个账号，登录有效 " + valid + " 个");
                ui.post(new Runnable() {
                    public void run() {
                        tvStatus.setText(text);
                        if (!silent) toast(tip);
                    }
                });
            }
        }).start();
    }

    private String buildStatus() {
        Prefs p = Prefs.get(this);
        StringBuilder sb = new StringBuilder();

        List<Account> list = AccountStore.get(this).list();
        if (list.isEmpty()) {
            sb.append("还没有账号\n请点「登录 / 添加账号」，输入学号和密码，剩下的自动完成");
        } else {
            int okN = 0;
            for (Account a : list) {
                if (a.lastOk == Account.STATE_OK) okN++;
            }
            sb.append("账号：共 ").append(list.size()).append(" 个，今日已签 ").append(okN).append(" 个\n");
            int i = 0;
            for (Account a : list) {
                if (i++ >= 5) {
                    sb.append("…\n");
                    break;
                }
                sb.append("· ").append(a.title()).append("　");
                // 这里只读本地状态，不再请求服务器：刷新按钮和进前台各查一遍已经够了，
                // 状态栏再查一遍会把请求量翻倍，白白多出"异常"痕迹
                if (Guard.frozen(a)) {
                    sb.append("冻结中，").append(Guard.untilText(a.blockedUntil)).append(" 恢复");
                } else if (a.cookie == null || a.cookie.length() == 0) {
                    sb.append("还没登录");
                } else if (a.lastResult != null && a.lastResult.length() > 0) {
                    sb.append(a.lastResult);
                } else {
                    sb.append(a.lastOk == Account.STATE_OK ? "今天已签" : "今天未签");
                }
                sb.append('\n');
            }
        }

        sb.append("\n定时打卡：").append(p.enabled() ? "开" : "关");
        sb.append("\n触发时间：每天 ").append(p.triggerTime());
        sb.append("\n账号间隔：").append(p.gapSeconds()).append(" 秒");
        sb.append("\n下次唤醒：").append(p.enabled() ? AlarmScheduler.nextText(this) : "未开启");
        if (p.lastResult().length() > 0) {
            sb.append("\n上次结果：").append(p.lastResult());
        }
        return sb.toString();
    }

    /* ---------------- 签到 / 守护 ---------------- */

    private void startSign() {
        final List<Account> list = AccountStore.get(this).list();
        if (list.isEmpty()) {
            Logs.add("还没有账号，先点「登录 / 添加账号」");
            toast("还没有账号");
            return;
        }
        Intent i = new Intent(this, SignService.class);
        i.putExtra("force", true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        ui.postDelayed(new Runnable() {
            public void run() { refresh(); }
        }, 5000);
    }

    /** 主界面这里设的是"默认位置"，单个账号可以在账号管理里单独设 */
    private void showLocDialog() {
        LocDialog.show(this, null);
    }

    /** 网络出口：检测当前 IP；填多个代理可以按账号自动换出口 */
    private void showNetDialog() {
        int pad = (int) (getResources().getDisplayMetrics().density * 20);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        final TextView tvResult = new TextView(this);
        tvResult.setTextSize(13);
        tvResult.setPadding(0, pad / 3, 0, pad / 3);
        tvResult.setText("当前出口：" + Net.activeText() + "\n点下面「检测」看真实 IP");
        box.addView(tvResult);

        Button btnCheck = new Button(this);
        btnCheck.setText("检测当前出口 IP");
        box.addView(btnCheck);

        final EditText etList = new EditText(this);
        etList.setHint("代理列表，一行一个，例如\n127.0.0.1:7890\n10.0.0.2:8080");
        etList.setMinLines(3);
        etList.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        etList.setText(Prefs.get(this).proxyList());
        box.addView(etList);

        TextView tip = new TextView(this);
        tip.setTextSize(12);
        tip.setText("留空=直连。填了多个的话，每签一个账号就自动换下一个出口（多账号各走各的 IP）。"
                + "代理只影响本应用，签到和查状态都会走它。");
        tip.setPadding(0, pad / 3, 0, 0);
        box.addView(tip);

        btnCheck.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                tvResult.setText("检测中…");
                // 先把框里的列表应用上去，这样检测的就是将要用的出口
                Net.setList(MainActivity.this, etList.getText().toString());
                new Thread(new Runnable() {
                    public void run() {
                        final String r = Net.check();
                        ui.post(new Runnable() {
                            public void run() { tvResult.setText(r); }
                        });
                    }
                }).start();
            }
        });

        new AlertDialog.Builder(this)
                .setTitle("网络 / 出口 IP")
                .setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        Net.setList(MainActivity.this, etList.getText().toString());
                        toast("已保存，当前出口：" + Net.activeText());
                    }
                })
                .setNeutralButton("恢复直连", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        Net.setList(MainActivity.this, "");
                        toast("已恢复直连");
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showTimeDialog() {
        final Prefs p = Prefs.get(this);
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setText(p.triggerTime());
        et.setHint("21:20");
        new AlertDialog.Builder(this)
                .setTitle("每天几点开始检查")
                .setMessage("学校窗口是 21:30-22:30。建议 21:20 开始，程序会一直试到签上或超时。")
                .setView(et)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String v = et.getText().toString().trim();
                        if (!v.matches("^([01]?[0-9]|2[0-3]):[0-5][0-9]$")) {
                            Logs.add("时间格式不对：" + v + "（要写成 21:20 这样）");
                            return;
                        }
                        p.setTriggerTime(v);
                        Logs.add("触发时间已改为每天 " + v);
                        if (p.enabled()) AlarmScheduler.scheduleAll(MainActivity.this);
                        renderToggle();
                        refresh();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /* ---------------- 授权 ---------------- */

    private void askNotification() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 100);
        } else {
            Logs.add("当前系统不需要申请通知权限");
        }
    }

    private void askExactAlarm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am != null && !am.canScheduleExactAlarms()) {
                try {
                    startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:" + getPackageName())));
                    return;
                } catch (Throwable ignored) { }
            }
        }
        Logs.add("精确闹钟已可用（或系统不需要）");
    }

    private void askBattery() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Logs.add("已经在电池优化白名单里了");
                return;
            }
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable ignored) { }
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        Logs.add("通知权限：" + (res.length > 0 && res[0] == 0 ? "已允许" : "被拒绝"));
    }

    /** 悬浮窗权限：后台静默重登要用它把登录网页挂起来 */
    private void askOverlay() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            toast("这个系统版本不需要单独授权");
            return;
        }
        if (Settings.canDrawOverlays(this)) {
            toast("已允许，定时任务遇到登录失效会自动重登");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("允许后台自动登录")
                .setMessage("定时打卡时如果登录过期了，程序会在后台把登录网页挂到一个几乎透明的悬浮窗上，"
                        + "自动填表、自动过滑块，登完继续签到。\n\n"
                        + "不加这个权限也能用，只是失效时会改成发一条通知，需要你点一下。\n\n"
                        + "点「去授权」后找到本应用，打开「显示在其他应用上层 / 悬浮窗」开关。")
                .setPositiveButton("去授权", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        try {
                            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:" + getPackageName()));
                            startActivity(i);
                        } catch (Throwable t) {
                            toast("打不开设置页，请手动去系统设置里授权");
                        }
                    }
                })
                .setNegativeButton("以后再说", null)
                .show();
    }

    /* ---------------- 缓存与日志 ---------------- */

    private void confirmClearCache() {
        final long cache = Storage.cacheBytes(this);
        final long web = Storage.webBytes(this);
        final long log = Storage.logBytes();
        new AlertDialog.Builder(this)
                .setTitle("清除缓存")
                .setMessage("当前占用：\n"
                        + "· 网页数据 " + human(web) + "（登录网页留下的，占大头）\n"
                        + "· 缓存文件 " + human(cache) + "\n"
                        + "· 运行日志 " + human(log) + "\n\n"
                        + "清除会删掉网页数据和缓存，共约 " + human(cache + web)
                        + "，网页里的登录状态也会一起清掉（不影响已保存的账号，"
                        + "需要时用「一键重新登录」重登）。\n\n"
                        + "不会删除：账号、密码、签到记录。")
                .setPositiveButton("清除", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { doClearCache(); }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doClearCache() {
        long freed = Storage.clear(this);
        toast("已清理，释放 " + human(freed));
        tvStatus.setText(buildStatus());
    }

    private void confirmClearLog() {
        long size = Logs.sizeBytes();
        new AlertDialog.Builder(this)
                .setTitle("清除日志")
                .setMessage("将清空全部运行日志（" + human(size) + "，内存中 "
                        + Logs.count() + " 条）。\n\n建议先「导出日志」留档，出问题时方便排查。")
                .setPositiveButton("清空", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        boolean ok = Logs.clear();
                        tvLog.setText("");
                        Logs.add(ok ? "日志已清空" : "日志文件删除失败，已清空内存日志");
                        toast("日志已清空");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void exportLog() {
        String name = "xiehe-log-"
                + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA).format(new Date()) + ".txt";
        try {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TITLE, name);
            startActivityForResult(i, REQ_EXPORT);
        } catch (Throwable t) {
            Logs.add("这台机器不支持选择保存位置：" + t);
            toast("无法打开保存对话框");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_BATCH) {
            if (!batchRunning) return;
            if (res == RESULT_OK) batchOk++;
            else batchFail++;
            batchIdx++;
            // 账号之间随机停 8~15 秒：连着登太密最容易被判"会话异常"
            int wait = 8000 + (int) (Math.random() * 7000);
            ui.postDelayed(new Runnable() {
                public void run() { batchNext(); }
            }, wait);
            return;
        }
        if (req != REQ_EXPORT) return;
        if (res != RESULT_OK || data == null || data.getData() == null) {
            toast("已取消导出");
            return;
        }
        Uri uri = data.getData();
        OutputStream os = null;
        try {
            String text = Logs.exportText();
            os = getContentResolver().openOutputStream(uri);
            if (os == null) throw new IllegalStateException("无法写入");
            os.write(text.getBytes("UTF-8"));
            os.flush();
            Logs.add("日志已导出，共 " + text.length() + " 字符");
            toast("日志已导出");
        } catch (Throwable t) {
            Logs.add("导出失败：" + t);
            toast("导出失败：" + t.getMessage());
        } finally {
            if (os != null) try { os.close(); } catch (Exception ignored) { }
        }
    }

    private static String human(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.US, "%.1f KB", b / 1024.0);
        return String.format(Locale.US, "%.1f MB", b / 1048576.0);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
