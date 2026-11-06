package com.baima.xiehe;

import android.content.Context;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 单个账号的签到动作（同步，要在后台线程里调）。
 * 三个调用方共用：账号列表的「签到」、一键签到、后台守护服务。
 */
public class SignCore {

    public static class Result {
        public boolean ok;        // 是否达成"今天已签到"
        public boolean expired;   // 会话失效，需要重新登录
        public boolean retry;     // 是否值得过一会儿再试
        public boolean soft;      // 只是时机未到，不算失败
        public String msg = "";   // 给用户看的一句话
    }

    /** 签到指定账号，并把结果写回数据库 */
    public static Result sign(Context ctx, Account a) {
        return sign(ctx, a, true);
    }

    /**
     * @param allowRelogin 会话失效时是否允许先自动重登再重试（重登成功后只重试一次，避免死循环）
     */
    private static Result sign(Context ctx, Account a, boolean allowRelogin) {
        if (a == null) {
            Result r = new Result();
            r.msg = "账号不存在";
            return r;
        }
        // 同一个账号同一时刻只允许一条链路在动它：后台服务、手动签到、重登排队来，
        // 并发的两个会话最容易触发学校的"会话异常"风控
        synchronized (Guard.lock(a.id)) {
            return doSign(ctx, a, allowRelogin);
        }
    }

    private static Result doSign(Context ctx, Account a, boolean allowRelogin) {
        Result r = new Result();
        // 冻结期一个请求都不发，等服务端解冻
        if (Guard.frozen(a)) {
            r.soft = true;
            r.msg = "账号被冻结，" + Guard.untilText(a.blockedUntil) + " 后自动恢复，本轮跳过";
            return r;
        }
        if (a.cookie == null || a.cookie.length() == 0) {
            return reloginThenRetry(ctx, a, r, allowRelogin, "还没有登录凭证");
        }

        // 配了多个代理就每个账号换一个出口，免得几个号都从同一个 IP 出去
        if (Net.count() > 1) {
            Net.next();
            Logs.add("【" + a.title() + "】本次出口：" + Net.activeText());
        }

        String ua = (a.ua != null && a.ua.length() > 0) ? a.ua : KqApi.DEFAULT_UA;

        KqApi.Resp info = KqApi.getKqInfo(a.cookie, ua);
        if (info.state == KqApi.State.BLOCKED) {
            return frozen(ctx, a, r, info);
        }
        if (info.state == KqApi.State.EXPIRED) {
            return reloginThenRetry(ctx, a, r, allowRelogin, "登录已失效");
        }
        if (info.state != KqApi.State.OK || info.model == null) {
            r.msg = "接口异常：" + info.msg;
            r.retry = true;
            save(ctx, a, r);
            return r;
        }

        KqModel m = info.model;
        // 顺手补全学号 / 宿舍，列表里好看一点
        boolean dirty = false;
        if (m.studentNo.length() > 0 && !m.studentNo.equals(a.studentNo)) {
            a.studentNo = m.studentNo;
            dirty = true;
        }
        if (m.dorm.length() > 0 && !m.dorm.equals(a.dorm)) {
            a.dorm = m.dorm;
            dirty = true;
        }
        if ((a.name == null || a.name.length() == 0) && a.studentNo.length() > 0) {
            a.name = a.studentNo;
            dirty = true;
        }
        if (dirty) AccountStore.get(ctx).update(a);

        if (m.hasSigned) {
            r.ok = true;
            r.msg = "今天已签到";
            save(ctx, a, r);
            return r;
        }
        if (!m.inWindow) {
            r.msg = "不在签到时段（" + m.start + "-" + m.end + "）";
            r.retry = true;
            r.soft = true;
            save(ctx, a, r);
            return r;
        }
        if (m.leave) {
            r.msg = "今天请假，不用签到";
            r.soft = true;
            save(ctx, a, r);
            return r;
        }

        KqModel.Point pt;
        Prefs pf = Prefs.get(ctx);
        if (a.locOn && a.locLat != 0 && a.locLng != 0) {
            // 这个账号单独指定的位置优先
            pt = new KqModel.Point();
            pt.name = a.locName.length() > 0 ? a.locName : "自定义位置";
            pt.lat = a.locLat;
            pt.lng = a.locLng;
            pt.radius = 30;
        } else if (pf.locOn() && pf.locLat() != 0 && pf.locLng() != 0) {
            // 主界面设的默认位置
            pt = new KqModel.Point();
            pt.name = pf.locName().length() > 0 ? pf.locName() : "自定义位置";
            pt.lat = pf.locLat();
            pt.lng = pf.locLng();
            pt.radius = 30;
        } else if (a.lastLocLat != 0 && a.lastLocLng != 0) {
            // 自动：沿用上一次签到成功的位置
            pt = new KqModel.Point();
            pt.name = a.lastLocName.length() > 0 ? a.lastLocName : "上次位置";
            pt.lat = a.lastLocLat;
            pt.lng = a.lastLocLng;
            pt.radius = 30;
        } else {
            pt = m.pickPoint();
        }
        if (pt == null) {
            r.msg = "没匹配到打卡点（宿舍：" + m.dorm + "）";
            save(ctx, a, r);
            return r;
        }

        double[] c = KqModel.jitter(pt.lat, pt.lng);
        KqApi.Resp sub = KqApi.addKqInfo(a.cookie, ua, pt.name, c[1], c[0]);

        if (sub.state == KqApi.State.BLOCKED) {
            return frozen(ctx, a, r, sub);
        }
        if (sub.state == KqApi.State.EXPIRED) {
            return reloginThenRetry(ctx, a, r, allowRelogin, "提交时登录已失效");
        }
        if (sub.state != KqApi.State.OK) {
            r.msg = "提交失败：" + sub.msg;
            r.retry = true;
            save(ctx, a, r);
            return r;
        }

        // 提交成功后再查一次，确认服务端真的记上了
        try { Thread.sleep(2000); } catch (InterruptedException ignored) { }
        KqApi.Resp chk = KqApi.getKqInfo(a.cookie, ua);
        // 记住这次用的位置，下次自动沿用
        a.lastLocName = pt.name;
        a.lastLocLat = pt.lat;
        a.lastLocLng = pt.lng;

        if (chk.state == KqApi.State.OK && chk.model != null && chk.model.hasSigned) {
            r.ok = true;
            r.msg = "签到成功（" + pt.name + "）";
        } else {
            // 提交成功但状态没刷新，算达成，避免重复提交
            r.ok = true;
            r.msg = "已提交，状态待刷新（" + pt.name + "）";
        }
        save(ctx, a, r);
        return r;
    }

    /**
     * 会话失效的统一处理：存过密码就先在后台静默重登，成功了立刻重试本次签到；
     * 重登失败或没存密码，就记下原因、通知用户，并结束这个账号的本次签到。
     */
    private static Result reloginThenRetry(Context ctx, Account a, Result r,
                                           boolean allowRelogin, String why) {
        String pwd = Secure.decrypt(a.pwdEnc);
        if (!allowRelogin || pwd.length() == 0) {
            r.expired = true;
            r.msg = allowRelogin ? "登录已失效，且没有保存密码，需要手动登录一次"
                    : "登录已失效，自动重登后仍然无效";
            save(ctx, a, r);
            notifyLogin(ctx, a);
            return r;
        }

        // 刚登录过就再等等：短时间连着重登是"会话异常"的主要来源
        if (Guard.inCooldown(a)) {
            long left = Guard.cooldownLeft(a) / 1000;
            r.expired = true;
            r.retry = true;
            r.msg = "刚登录过（" + (left > 0 ? left + " 秒后" : "稍后") + "再试），先不重复登录";
            save(ctx, a, r);
            Logs.add("【" + a.title() + "】" + why + "，但距上次登录不足 3 分钟，跳过重登");
            return r;
        }

        Logs.add("【" + a.title() + "】" + why + "，开始后台自动重登");
        Notifier.progress(ctx, "【" + a.title() + "】登录失效，正在自动重登…");
        SilentLogin.Result lr = SilentLogin.relogin(ctx, a, 60);
        if (lr.ok) {
            a.cookie = lr.cookie;
            a.lastLoginAt = System.currentTimeMillis();
            a.lastOk = Account.STATE_NONE;
            a.lastResult = "已自动重登，正在签到";
            a.lastTime = System.currentTimeMillis();
            AccountStore.get(ctx).update(a);
            Logs.add("【" + a.title() + "】自动重登成功，继续本次签到");
            return doSign(ctx, a, false);
        }

        if (Guard.isFreezeText(lr.reason)) {
            return frozen(ctx, a, r, lr.reason);
        }

        r.expired = true;
        r.msg = "登录失效，自动重登失败：" + lr.reason;
        a.lastOk = Account.STATE_FAIL;
        a.lastResult = r.msg;
        AccountStore.get(ctx).update(a);
        notifyLogin(ctx, a);
        Logs.add("【" + a.title() + "】自动重登失败：" + lr.reason);
        return r;
    }

    /**
     * 被风控冻结：记下解冻时间，之后到点前一个请求都不发。
     * 不算失败、也不重试，避免越撞越久。
     */
    private static Result frozen(Context ctx, Account a, Result r, KqApi.Resp resp) {
        return frozen(ctx, a, r, resp.blockedUntil, resp.msg);
    }

    /** 从一段文字（比如登录页提示）里认出冻结 */
    private static Result frozen(Context ctx, Account a, Result r, String text) {
        return frozen(ctx, a, r, Guard.freezeUntil(text), text);
    }

    private static Result frozen(Context ctx, Account a, Result r, long until,
                                 String serverMsg) {
        if (until <= System.currentTimeMillis()) until = Guard.freezeUntil(serverMsg);
        a.blockedUntil = until;
        r.soft = true;
        r.msg = "账号被风控冻结，" + Guard.untilText(until) + " 后自动恢复（期间不发请求）";
        a.lastTime = System.currentTimeMillis();
        a.lastResult = r.msg;
        AccountStore.get(ctx).update(a);
        Logs.add("【" + a.title() + "】" + r.msg + "｜原始提示：" + serverMsg);
        try {
            Notifier.result(ctx, "【" + a.title() + "】" + r.msg
                    + "\n期间请勿手动登录，越登冻得越久");
        } catch (Throwable ignored) { }
        return r;
    }

    /** 会话过期时：存过密码就发"点一下自动重登"的通知，没存就提示手动登一次 */
    private static void notifyLogin(Context ctx, Account a) {
        try {
            String pwd = Secure.decrypt(a.pwdEnc);
            if (pwd.length() > 0) {
                Notifier.needLogin(ctx, a.id, a.title());
            } else {
                Notifier.result(ctx, "【" + a.title() + "】登录已失效，打开应用重新登录一次"
                        + "（登录时输入的密码会自动保存，以后就不用再手动登了）");
            }
        } catch (Throwable ignored) { }
    }

    private static void save(Context ctx, Account a, Result r) {
        a.lastTime = System.currentTimeMillis();
        a.lastOk = r.ok ? Account.STATE_OK : (r.soft ? Account.STATE_NONE : Account.STATE_FAIL);
        a.lastResult = r.msg;
        AccountStore.get(ctx).update(a);
        if (r.ok) {
            String day = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
                    .format(new Date(a.lastTime > 0 ? a.lastTime : System.currentTimeMillis()));
            AccountStore.get(ctx).record(a.id, day, true, r.msg);
        }
        Logs.add("【" + a.title() + "】" + r.msg);
    }

    public static String hhmm(long ms) {
        if (ms <= 0) return "—";
        return new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(ms));
    }
}
