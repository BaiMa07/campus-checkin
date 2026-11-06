package com.baima.xiehe;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 风控闸门。
 *
 * 学校的统一认证看到「同一账号短时间反复登录」或者「同时存在多个会话」，
 * 会把这个号冻结几分钟（消息中心里那条"当前账号会话异常，将被冻结"）。
 * 所以这里统一做三件事：
 *   1) 账号级串行锁：后台服务、手动签到、批量重登不会同时动同一个号
 *   2) 登录冷却：同一个号刚登录过就不再重复登录
 *   3) 冻结识别：认出"冻结"字样，记下解冻时间，到点之前一个请求都不发
 */
public class Guard {

    /** 同一个号两次登录之间至少隔这么久 */
    public static final long LOGIN_COOLDOWN = 3 * 60 * 1000L;
    /** 服务端没给解冻时间时，默认按 10 分钟算 */
    public static final long FREEZE_DEFAULT = 10 * 60 * 1000L;

    private static final ConcurrentHashMap<Long, Object> LOCKS =
            new ConcurrentHashMap<Long, Object>();

    /** 每个账号一把锁（按 id 分配，进程内有效） */
    public static Object lock(long id) {
        Long k = Long.valueOf(id);
        Object o = LOCKS.get(k);
        if (o == null) {
            Object n = new Object();
            Object prev = LOCKS.putIfAbsent(k, n);
            o = prev == null ? n : prev;
        }
        return o;
    }

    /* ---------------- 登录冷却 ---------------- */

    public static boolean inCooldown(Account a) {
        return a != null && a.lastLoginAt > 0
                && System.currentTimeMillis() - a.lastLoginAt < LOGIN_COOLDOWN;
    }

    public static long cooldownLeft(Account a) {
        if (a == null || a.lastLoginAt <= 0) return 0;
        long left = a.lastLoginAt + LOGIN_COOLDOWN - System.currentTimeMillis();
        return left > 0 ? left : 0;
    }

    /* ---------------- 冻结 ---------------- */

    public static boolean frozen(Account a) {
        return a != null && a.blockedUntil > System.currentTimeMillis();
    }

    /** 这是不是一个"被风控"的提示（"冻结"字样，或者"会话…异常"） */
    public static boolean isFreezeText(String text) {
        if (text == null || text.length() == 0) return false;
        return text.contains("冻结")
                || (text.contains("会话") && text.contains("异常"));
    }

    /**
     * 从返回文字里抠出解冻时间（"预期解冻时间：2026-11-06 23:08:58"），
     * 抠不出来按默认 10 分钟。返回绝对时间戳。
     */
    public static long freezeUntil(String text) {
        long now = System.currentTimeMillis();
        long fallback = now + FREEZE_DEFAULT;
        if (text == null || text.length() == 0) return fallback;
        int i = text.indexOf("解冻");
        if (i < 0) i = text.indexOf("冻结");
        if (i < 0) return fallback;
        String tail = text.substring(i);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d{4})-(\\d{2})-(\\d{2})[ T](\\d{2}):(\\d{2}):(\\d{2})")
                .matcher(tail);
        String iso = "";
        while (m.find()) iso = m.group(1) + "-" + m.group(2) + "-" + m.group(3) + " "
                + m.group(4) + ":" + m.group(5) + ":" + m.group(6);
        if (iso.length() == 0) return fallback;
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).parse(iso);
            if (d == null) return fallback;
            long t = d.getTime();
            // 解冻时间已经过去（或者服务器时钟差得多）就按默认值，别原地打转
            return t > now ? t : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    public static String untilText(long ts) {
        if (ts <= 0) return "—";
        return new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date(ts));
    }
}
