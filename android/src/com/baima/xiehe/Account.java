package com.baima.xiehe;

/** 一个签到账号：Cookie 独立，互相不干扰 */
public class Account {

    public static final int STATE_NONE = -1;   // 还没签过
    public static final int STATE_FAIL = 0;
    public static final int STATE_OK = 1;

    public long id;
    public String name = "";        // 展示名（学号 / 姓名）
    public String studentNo = "";   // 学号（从接口拿）
    public String dorm = "";        // 宿舍（从接口拿）
    public String cookie = "";
    public String ua = "";
    public String pwdEnc = "";      // 密码密文（Android Keystore 加密），用来自动重登
    public boolean locOn;           // 这个账号单独指定的签到位置
    public String locName = "";
    public double locLat;
    public double locLng;
    public String lastLocName = "";  // 上一次签到成功用的位置（自动模式优先用它）
    public double lastLocLat;
    public double lastLocLng;
    public long lastTime;           // 上次签到尝试时间
    public String lastResult = "";  // 上次结果文字
    public int lastOk = STATE_NONE;
    public long blockedUntil;       // 被风控冻结到什么时候（毫秒时间戳，0=没冻）
    public long lastLoginAt;        // 上次登录成功的时间，用来做登录冷却
    public long createdAt;

    public String stateText() {
        if (Guard.frozen(this)) return "冻结中";
        if (lastOk == STATE_OK) return "已签到";
        if (lastOk == STATE_FAIL) return "失败";
        return "未签";
    }

    public String title() {
        if (name != null && name.length() > 0) return name;
        if (studentNo != null && studentNo.length() > 0) return studentNo;
        return "账号 " + id;
    }
}
