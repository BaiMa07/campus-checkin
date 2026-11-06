package com.baima.xiehe;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** 账号表：SQLite 本地库（requirements 里说的"数据库新增/移除记录"就是这张表） */
public class AccountStore extends SQLiteOpenHelper {

    private static final String DB_NAME = "xiehe.db";
    private static final int DB_VER = 5;
    private static final String T = "accounts";

    private static AccountStore inst;

    private AccountStore(Context c) {
        super(c.getApplicationContext(), DB_NAME, null, DB_VER);
    }

    public static synchronized AccountStore get(Context c) {
        if (inst == null) inst = new AccountStore(c);
        return inst;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        createHistory(db);
        db.execSQL("CREATE TABLE " + T + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "name TEXT,"
                + "student_no TEXT,"
                + "dorm TEXT,"
                + "cookie TEXT,"
                + "ua TEXT,"
                + "pwd_enc TEXT,"
                + "loc_on INTEGER DEFAULT 0,"
                + "loc_name TEXT,"
                + "loc_lat TEXT,"
                + "loc_lng TEXT,"
                + "last_loc_name TEXT,"
                + "last_loc_lat TEXT,"
                + "last_loc_lng TEXT,"
                + "last_time INTEGER DEFAULT 0,"
                + "last_result TEXT,"
                + "last_ok INTEGER DEFAULT -1,"
                + "created_at INTEGER DEFAULT 0)");
    }

    /** 签到历史：日历用，一天一条 */
    private static void createHistory(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS history("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "account_id INTEGER,"
                + "day TEXT,"
                + "ok INTEGER DEFAULT 1,"
                + "ts INTEGER DEFAULT 0,"
                + "msg TEXT,"
                + "UNIQUE(account_id, day))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int o, int n) {
        // v2：加密码密文字段（老版本升级上来时补列）
        if (o < 2) {
            try {
                db.execSQL("ALTER TABLE " + T + " ADD COLUMN pwd_enc TEXT");
            } catch (Throwable ignored) { }
        }
        if (o < 3) {
            try { createHistory(db); } catch (Throwable ignored) { }
        }
        if (o < 5) {
            try { db.execSQL("ALTER TABLE " + T + " ADD COLUMN last_loc_name TEXT"); } catch (Throwable ignored) { }
            try { db.execSQL("ALTER TABLE " + T + " ADD COLUMN last_loc_lat TEXT"); } catch (Throwable ignored) { }
            try { db.execSQL("ALTER TABLE " + T + " ADD COLUMN last_loc_lng TEXT"); } catch (Throwable ignored) { }
        }
        if (o < 4) {
            try { db.execSQL("ALTER TABLE " + T + " ADD COLUMN loc_on INTEGER DEFAULT 0"); } catch (Throwable ignored) { }
            try { db.execSQL("ALTER TABLE " + T + " ADD COLUMN loc_name TEXT"); } catch (Throwable ignored) { }
            try { db.execSQL("ALTER TABLE " + T + " ADD COLUMN loc_lat TEXT"); } catch (Throwable ignored) { }
            try { db.execSQL("ALTER TABLE " + T + " ADD COLUMN loc_lng TEXT"); } catch (Throwable ignored) { }
        }
    }

    public synchronized List<Account> list() {
        List<Account> out = new ArrayList<Account>();
        Cursor c = null;
        try {
            c = getReadableDatabase().query(T, null, null, null, null, null, "id ASC");
            while (c.moveToNext()) out.add(read(c));
        } catch (Throwable t) {
            Logs.add("读取账号失败：" + t);
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) { }
        }
        return out;
    }

    public synchronized Account get(long id) {
        Cursor c = null;
        try {
            c = getReadableDatabase().query(T, null, "id=?", new String[]{String.valueOf(id)},
                    null, null, null);
            if (c.moveToFirst()) return read(c);
        } catch (Throwable t) {
            Logs.add("读取账号失败：" + t);
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) { }
        }
        return null;
    }

    public synchronized long count() {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + T, null);
            if (c.moveToFirst()) return c.getLong(0);
        } catch (Throwable ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) { }
        }
        return 0;
    }

    public synchronized long add(Account a) {
        ContentValues v = new ContentValues();
        v.put("name", a.name);
        v.put("student_no", a.studentNo);
        v.put("dorm", a.dorm);
        v.put("cookie", a.cookie);
        v.put("ua", a.ua);
        v.put("pwd_enc", a.pwdEnc);
        v.put("loc_on", a.locOn ? 1 : 0);
        v.put("loc_name", a.locName);
        v.put("loc_lat", String.valueOf(a.locLat));
        v.put("loc_lng", String.valueOf(a.locLng));
        v.put("last_loc_name", a.lastLocName);
        v.put("last_loc_lat", String.valueOf(a.lastLocLat));
        v.put("last_loc_lng", String.valueOf(a.lastLocLng));
        v.put("last_time", a.lastTime);
        v.put("last_result", a.lastResult);
        v.put("last_ok", a.lastOk);
        v.put("created_at", System.currentTimeMillis());
        long id = -1;
        try {
            id = getWritableDatabase().insert(T, null, v);
            a.id = id;
        } catch (Throwable t) {
            Logs.add("新增账号失败：" + t);
        }
        return id;
    }

    public synchronized boolean update(Account a) {
        ContentValues v = new ContentValues();
        v.put("name", a.name);
        v.put("student_no", a.studentNo);
        v.put("dorm", a.dorm);
        v.put("cookie", a.cookie);
        v.put("ua", a.ua);
        v.put("pwd_enc", a.pwdEnc);
        v.put("loc_on", a.locOn ? 1 : 0);
        v.put("loc_name", a.locName);
        v.put("loc_lat", String.valueOf(a.locLat));
        v.put("loc_lng", String.valueOf(a.locLng));
        v.put("last_loc_name", a.lastLocName);
        v.put("last_loc_lat", String.valueOf(a.lastLocLat));
        v.put("last_loc_lng", String.valueOf(a.lastLocLng));
        v.put("last_time", a.lastTime);
        v.put("last_result", a.lastResult);
        v.put("last_ok", a.lastOk);
        try {
            return getWritableDatabase().update(T, v, "id=?", new String[]{String.valueOf(a.id)}) > 0;
        } catch (Throwable t) {
            Logs.add("更新账号失败：" + t);
            return false;
        }
    }

    /** 只更新密码密文 */
    public synchronized boolean setPwd(long id, String enc) {
        ContentValues v = new ContentValues();
        v.put("pwd_enc", enc == null ? "" : enc);
        try {
            return getWritableDatabase().update(T, v, "id=?", new String[]{String.valueOf(id)}) > 0;
        } catch (Throwable t) {
            Logs.add("保存密码失败：" + t);
            return false;
        }
    }

    /** 按学号找账号（没有就按展示名找），用于登录后补密码 */
    public synchronized Account findAny(String no, String name) {
        Account a = findByStudentNo(no);
        if (a != null) return a;
        if (name == null || name.length() == 0) return null;
        for (Account x : list()) {
            if (name.equals(x.name)) return x;
        }
        return null;
    }

    public synchronized boolean delete(long id) {
        try {
            return getWritableDatabase().delete(T, "id=?", new String[]{String.valueOf(id)}) > 0;
        } catch (Throwable t) {
            Logs.add("删除账号失败：" + t);
            return false;
        }
    }

    /** 按学号找已存在的账号（防止重复添加同一个号） */
    public synchronized Account findByStudentNo(String no) {
        if (no == null || no.length() == 0) return null;
        for (Account a : list()) {
            if (no.equals(a.studentNo)) return a;
        }
        return null;
    }

    /** 按 Cookie 找已存在的账号（学号没拿到时的兜底） */
    public synchronized Account findByCookie(String cookie) {
        if (cookie == null || cookie.length() == 0) return null;
        for (Account a : list()) {
            if (cookie.equals(a.cookie)) return a;
        }
        return null;
    }

    /**
     * 清掉重复账号：学号相同的只留最新一条；学号都空时按 Cookie 判断。
     * 返回删掉的条数。
     */
    public synchronized int dedupe() {
        List<Account> all = list();
        java.util.Map<String, Long> keep = new java.util.HashMap<String, Long>();
        java.util.List<Long> drop = new java.util.ArrayList<Long>();
        for (Account a : all) {
            String key;
            if (a.studentNo != null && a.studentNo.length() > 0) key = "no:" + a.studentNo;
            else if (a.cookie != null && a.cookie.length() > 0) key = "ck:" + a.cookie;
            else key = "id:" + a.id;
            Long prev = keep.get(key);
            if (prev == null) {
                keep.put(key, a.id);
            } else {
                // 保留 id 更大的（后加的），删掉旧的那条
                if (a.id > prev) {
                    drop.add(prev);
                    keep.put(key, a.id);
                } else {
                    drop.add(a.id);
                }
            }
        }
        int n = 0;
        for (Long id : drop) {
            if (delete(id)) n++;
        }
        if (n > 0) Logs.add("已清理重复账号 " + n + " 条");
        return n;
    }

    /** 记一条签到历史（同一天同账号只留一条） */
    public synchronized void record(long accountId, String day, boolean ok, String msg) {
        try {
            ContentValues v = new ContentValues();
            v.put("account_id", accountId);
            v.put("day", day);
            v.put("ok", ok ? 1 : 0);
            v.put("ts", System.currentTimeMillis());
            v.put("msg", msg == null ? "" : msg);
            getWritableDatabase().insertWithOnConflict("history", null, v,
                    SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Throwable t) {
            Logs.add("写签到历史失败：" + t);
        }
    }

    /** 最早有记录的那天（用来判断"从哪天开始算漏签"） */
    public synchronized String firstDay(long accountId) {
        Cursor c = null;
        try {
            c = accountId > 0
                    ? getReadableDatabase().rawQuery(
                        "SELECT MIN(day) FROM history WHERE account_id=?",
                        new String[]{String.valueOf(accountId)})
                    : getReadableDatabase().rawQuery("SELECT MIN(day) FROM history", null);
            if (c.moveToFirst()) {
                String s = c.getString(0);
                return s == null ? "" : s;
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) { }
        }
        return "";
    }

    /** 某月每天的签到情况：key=yyyy-MM-dd，value=1 签了 / 0 没签 */
    public synchronized java.util.Map<String, Integer> monthHistory(long accountId, String month) {
        java.util.Map<String, Integer> out = new java.util.HashMap<String, Integer>();
        Cursor c = null;
        try {
            String sql = accountId > 0
                    ? "SELECT day, MAX(ok) ok FROM history WHERE day LIKE ? AND account_id=? GROUP BY day"
                    : "SELECT day, MAX(ok) ok FROM history WHERE day LIKE ? GROUP BY day";
            String[] args = accountId > 0
                    ? new String[]{month + "%", String.valueOf(accountId)}
                    : new String[]{month + "%"};
            c = getReadableDatabase().rawQuery(sql, args);
            while (c.moveToNext()) out.put(c.getString(0), c.getInt(1));
        } catch (Throwable t) {
            Logs.add("读签到历史失败：" + t);
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) { }
        }
        return out;
    }

    /** 某一天的明细：每个账号一条 */
    public synchronized java.util.List<String> dayDetail(long accountId, String day) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        Cursor c = null;
        try {
            String sql = accountId > 0
                    ? "SELECT account_id, msg, ts FROM history WHERE day=? AND account_id=? ORDER BY id"
                    : "SELECT account_id, msg, ts FROM history WHERE day=? ORDER BY id";
            String[] args = accountId > 0
                    ? new String[]{day, String.valueOf(accountId)}
                    : new String[]{day};
            c = getReadableDatabase().rawQuery(sql, args);
            while (c.moveToNext()) {
                long aid = c.getLong(0);
                String msg = c.getString(1);
                long ts = c.getLong(2);
                String name = "账号" + aid;
                Account a = get(aid);
                if (a != null) name = a.title();
                String t = "";
                try {
                    t = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
                            .format(new java.util.Date(ts));
                } catch (Throwable ignored) { }
                out.add(name + "  " + t + "\n" + (msg == null ? "" : msg));
            }
        } catch (Throwable t) {
            Logs.add("读当天明细失败：" + t);
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) { }
        }
        return out;
    }

    private static Account read(Cursor c) {
        Account a = new Account();
        a.id = c.getLong(c.getColumnIndex("id"));
        a.name = str(c, "name");
        a.studentNo = str(c, "student_no");
        a.dorm = str(c, "dorm");
        a.cookie = str(c, "cookie");
        a.ua = str(c, "ua");
        a.pwdEnc = str(c, "pwd_enc");
        a.locOn = c.getInt(c.getColumnIndex("loc_on")) == 1;
        a.locName = str(c, "loc_name");
        a.locLat = dbl(c, "loc_lat");
        a.locLng = dbl(c, "loc_lng");
        a.lastLocName = str(c, "last_loc_name");
        a.lastLocLat = dbl(c, "last_loc_lat");
        a.lastLocLng = dbl(c, "last_loc_lng");
        a.lastTime = c.getLong(c.getColumnIndex("last_time"));
        a.lastResult = str(c, "last_result");
        a.lastOk = c.getInt(c.getColumnIndex("last_ok"));
        a.createdAt = c.getLong(c.getColumnIndex("created_at"));
        return a;
    }

    private static double dbl(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        if (i < 0) return 0;
        try { return Double.parseDouble(c.getString(i)); } catch (Exception e) { return 0; }
    }

    private static String str(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        if (i < 0) return "";
        String s = c.getString(i);
        return s == null ? "" : s;
    }
}
