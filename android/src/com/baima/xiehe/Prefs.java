package com.baima.xiehe;

import android.content.Context;
import android.content.SharedPreferences;

/** 轻量配置存储（开关 / 时间 / 间隔 / 全局结果）。账号本身在 SQLite 里。 */
public class Prefs {

    private static final String FILE = "xiehe_prefs";
    private static Prefs inst;
    private final Context app;
    private final SharedPreferences sp;

    private Prefs(Context c) {
        app = c.getApplicationContext();
        sp = app.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static synchronized Prefs get(Context c) {
        if (inst == null) inst = new Prefs(c);
        return inst;
    }

    public String ua() { return sp.getString("ua", ""); }
    public void setUa(String v) { sp.edit().putString("ua", v).apply(); }

    public boolean enabled() { return sp.getBoolean("enabled", false); }
    public void setEnabled(boolean v) { sp.edit().putBoolean("enabled", v).apply(); }

    /** 每天几点开始检查，默认 21:20（学校窗口 21:30-22:30） */
    public String triggerTime() { return sp.getString("trigger", "21:20"); }
    public void setTriggerTime(String v) { sp.edit().putString("trigger", v).apply(); }

    /** 多账号之间的签到间隔（秒），防风控 */
    public int gapSeconds() { return sp.getInt("gap", 3); }
    public void setGapSeconds(int v) { sp.edit().putInt("gap", Math.max(0, Math.min(120, v))).apply(); }

    /** 多个出口代理，一行一个 host:port；第一个是直连用不到，留空表示直连 */
    public String proxyList() { return sp.getString("proxy_list", ""); }
    public void setProxyList(String v) { sp.edit().putString("proxy_list", v == null ? "" : v).apply(); }

    /** 出口代理：host 为空表示直连 */
    public String proxyHost() { return sp.getString("proxy_host", ""); }
    public int proxyPort() { return sp.getInt("proxy_port", 0); }
    public void setProxy(String h, int p) {
        sp.edit().putString("proxy_host", h == null ? "" : h)
                .putInt("proxy_port", p).apply();
    }

    /** 自定义签到位置：on=false 表示按宿舍自动匹配打卡点 */
    public boolean locOn() { return sp.getBoolean("loc_on", false); }
    public String locName() { return sp.getString("loc_name", ""); }
    public double locLat() { return num(sp.getString("loc_lat", "0")); }
    public double locLng() { return num(sp.getString("loc_lng", "0")); }

    public void setLoc(boolean on, String name, double lat, double lng) {
        sp.edit().putBoolean("loc_on", on)
                .putString("loc_name", name == null ? "" : name)
                .putString("loc_lat", String.valueOf(lat))
                .putString("loc_lng", String.valueOf(lng)).apply();
    }

    private static double num(String s) {
        try { return Double.parseDouble(s); } catch (Exception e) { return 0; }
    }

    public String lastResult() { return sp.getString("last", ""); }
    public void setLastResult(String v) { sp.edit().putString("last", v).apply(); }

    /* ---------- 兼容旧版：把原来的单一 Cookie 迁移成第一个账号 ---------- */

    public String legacyCookie() { return sp.getString("cookie", ""); }

    public boolean migrated() { return sp.getBoolean("migrated", false); }

    /** 首次运行新版时调用：老版本的 Cookie 不丢 */
    public void migrateIfNeeded() {
        if (migrated()) return;
        sp.edit().putBoolean("migrated", true).apply();
        String ck = legacyCookie();
        if (ck == null || ck.length() == 0) return;
        AccountStore st = AccountStore.get(app);
        if (st.count() > 0) return;
        final Account a = new Account();
        a.cookie = ck;
        a.ua = ua();
        a.name = "原账号";
        st.add(a);
        Logs.add("已把旧版登录状态迁移成一个账号");
    }

    /** 迁移完就不再需要这个字段了 */
    public void dropLegacyCookie() { sp.edit().remove("cookie").apply(); }
}
