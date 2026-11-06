# -*- coding: utf-8 -*-
"""
协和查寝 · 电脑版 —— 核心逻辑（账号库 / 接口 / 签到）
不依赖任何界面代码，app.pyw 和 login_helper.py 共用。
"""

import json
import math
import os
import random
import re
import sqlite3
import sys
import time
from datetime import datetime

import requests

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(BASE_DIR, "data")
DB_PATH = os.path.join(DATA, "accounts.db")
LOG_PATH = os.path.join(DATA, "app.log")
PROFILE_DIR = os.path.join(DATA, "profiles")
TMP_DIR = os.path.join(DATA, "tmp")

API = "https://xg.sdxiehe.edu.cn/xsfw/sys/swmzncqapp/kqController/"
SERVICE = "https://xg.sdxiehe.edu.cn/xsfw/sys/swmzncqapp/*default/index.do"
SIGN_PAGE = SERVICE + "#/xscq/kqqdx"
UA = ("Mozilla/5.0 (Linux; Android 14; PGT-AN20) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")

STATE_NONE, STATE_FAIL, STATE_OK = -1, 0, 1

# 账号 id -> 冻结到期时间戳
_blocked = {}


def ensure_dirs():
    for d in (DATA, PROFILE_DIR, TMP_DIR):
        os.makedirs(d, exist_ok=True)


def log(msg):
    """写一行日志到 data/app.log，同时返回带时间的字符串给界面用"""
    ensure_dirs()
    line = datetime.now().strftime("%m-%d %H:%M:%S") + "  " + str(msg)
    try:
        with open(LOG_PATH, "a", encoding="utf-8") as f:
            f.write(line + "\n")
    except Exception:
        pass
    return line


# ---------------------------------------------------------------- 账号库

class Store(object):

    def __init__(self, path=DB_PATH):
        ensure_dirs()
        self.path = path
        self._init()

    def _conn(self):
        c = sqlite3.connect(self.path)
        c.row_factory = sqlite3.Row
        return c

    def _init(self):
        with self._conn() as c:
            c.execute("""CREATE TABLE IF NOT EXISTS accounts(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT, student_no TEXT, dorm TEXT,
                cookies TEXT, profile TEXT,
                last_time INTEGER DEFAULT 0, last_result TEXT,
                last_ok INTEGER DEFAULT -1, created_at INTEGER DEFAULT 0)""")
            # 老库补字段：保存密码（DPAPI 密文），用于自动重新登录
            cols = {r["name"] for r in c.execute("PRAGMA table_info(accounts)")}
            if "password" not in cols:
                c.execute("ALTER TABLE accounts ADD COLUMN password TEXT")

    def list(self):
        with self._conn() as c:
            return [dict(r) for r in c.execute("SELECT * FROM accounts ORDER BY id")]

    def get(self, aid):
        with self._conn() as c:
            r = c.execute("SELECT * FROM accounts WHERE id=?", (aid,)).fetchone()
            return dict(r) if r else None

    def add(self, name, student_no, dorm, cookies, profile, password=""):
        with self._conn() as c:
            cur = c.execute(
                "INSERT INTO accounts(name,student_no,dorm,cookies,profile,created_at,password)"
                " VALUES(?,?,?,?,?,?,?)",
                (name, student_no, dorm, json.dumps(cookies), profile,
                 int(time.time()), password))
            return cur.lastrowid

    def update(self, acc):
        with self._conn() as c:
            c.execute("""UPDATE accounts SET name=?,student_no=?,dorm=?,cookies=?,
                         profile=?,last_time=?,last_result=?,last_ok=?,password=? WHERE id=?""",
                      (acc["name"], acc["student_no"], acc["dorm"],
                       json.dumps(acc["cookies"]) if not isinstance(acc["cookies"], str)
                       else acc["cookies"],
                       acc["profile"], acc["last_time"], acc["last_result"],
                       acc["last_ok"], acc.get("password") or "", acc["id"]))

    def delete(self, aid):
        with self._conn() as c:
            c.execute("DELETE FROM accounts WHERE id=?", (aid,))

    def find_by_no(self, no):
        if not no:
            return None
        for a in self.list():
            if a["student_no"] == no:
                return a
        return None

    def cookies(self, acc):
        ck = acc.get("cookies")
        if isinstance(ck, dict):
            return ck
        try:
            return json.loads(ck or "{}")
        except Exception:
            return {}


# ---------------------------------------------------------------- 接口

def make_session(cookies):
    s = requests.Session()
    s.cookies.update(cookies)
    s.headers.update({
        "User-Agent": UA,
        "Referer": SIGN_PAGE,
        "X-Requested-With": "XMLHttpRequest",
        "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
    })
    return s


class Frozen(Exception):
    """学校风控把号冻了（消息中心那条"会话异常"）。解冻前一个请求都别发。"""
    pass


# 进程内的登录冷却：同一个号刚登过就别连着登，短时间多次登录会被判会话异常
_last_login = {}
LOGIN_COOLDOWN = 180


def frozen_text(s):
    s = s or ""
    return ("冻结" in s) or ("会话" in s and "异常" in s)


def get_info(session):
    """返回 data 字典；会话失效或异常返回 None；被风控抛 Frozen"""
    try:
        r = session.post(API + "getKqInfo.do", data={"data": "{}"}, timeout=20)
        text = r.text or ""
        if frozen_text(text):
            raise Frozen(text[:300])
        if "authserver" in (r.url or ""):
            return None
        if r.status_code != 200:
            return None
        j = r.json()
        if str(j.get("code")) != "0":
            return None
        return j.get("data") or {}
    except Frozen:
        raise
    except Exception:
        return None


def add_sign(session, lat, lng, addr):
    payload = json.dumps({"KQWZXX": addr, "JDZB": str(lng), "WDZB": str(lat)},
                         ensure_ascii=False)
    try:
        r = session.post(API + "addKqInfo.do",
                         data={"data": payload.encode("utf-8")}, timeout=25)
        try:
            return r.json()
        except Exception:
            return {"code": "-1", "msg": (r.text or "")[:200]}
    except Exception as e:
        return {"code": "-1", "msg": str(e)}


def pick_point(info):
    pts = info.get("DD_LIST") or []
    if not pts:
        return None
    name = (info.get("SSL_INFO") or {}).get("SSLDM_DISPLAY") or ""
    for p in pts:
        if name and name in (p.get("QDDD") or ""):
            return p
    key = "".join(ch for ch in name if ch.isdigit())
    if key:
        for p in pts:
            q = p.get("QDDD") or ""
            if key in q and "辅助" not in q:
                return p
    return None


def jitter(lat, lng, meters=3.0):
    dlat = random.uniform(-meters, meters) / 111320.0
    dlng = random.uniform(-meters, meters) / (111320.0 * math.cos(math.radians(lat)))
    return round(lat + dlat, 6), round(lng + dlng, 6)


# ---------------------------------------------------------------- 签到

def auto_relogin(acc, timeout=200):
    """
    用保存的密码自动重新登录，成功返回新的 cookie 字典，失败返回 None。
    浏览器窗口会被移到屏幕外，用户看不到。
    """
    import subprocess

    try:
        import secure
    except Exception:
        return None

    pwd = secure.decrypt(acc.get("password") or "")
    no = (acc.get("student_no") or "").strip()
    if not pwd or not no:
        return None

    profile = acc.get("profile") or os.path.join(
        PROFILE_DIR, "acc_%d" % int(time.time()))
    if not os.path.isabs(profile):
        profile = os.path.join(PROFILE_DIR, profile)
    os.makedirs(profile, exist_ok=True)

    ensure_dirs()
    out = os.path.join(TMP_DIR, "auto_%d_%d.json" % (acc.get("id") or 0, int(time.time())))
    helper = os.path.join(BASE_DIR, "login_helper.py")

    exe = sys.executable or "python"
    pyw = os.path.join(os.path.dirname(exe), "pythonw.exe")
    if os.path.exists(pyw):
        exe = pyw

    env = dict(os.environ)
    env["XIEHE_USER"] = no
    env["XIEHE_PWD"] = pwd
    env["XIEHE_AUTO"] = "1"

    log("【%s】用保存的密码自动登录中…" % (acc.get("name") or no))
    try:
        subprocess.run([exe, helper, profile, out], env=env, timeout=timeout,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except Exception as e:
        log("自动登录出错：%s" % e)
        return None

    if not os.path.exists(out):
        log("自动登录没有成功（滑块或密码问题）")
        return None
    try:
        with open(out, encoding="utf-8") as f:
            ck = (json.load(f) or {}).get("cookies") or {}
    except Exception:
        return None
    finally:
        try:
            os.remove(out)
        except Exception:
            pass

    return ck or None


def _frozen(store, acc, res, text):
    """记下冻结，按解冻时间冷却；期间不再发请求"""
    until = time.time() + 600
    m = re.search(r"(\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2})", text or "")
    if m:
        try:
            until = datetime.strptime(m.group(1).replace("T", " "),
                                      "%Y-%m-%d %H:%M:%S").timestamp()
        except Exception:
            pass
    _blocked[acc["id"]] = until
    res["msg"] = "账号被学校风控冻结，%s 后自动恢复（期间不发请求）" % \
                 datetime.fromtimestamp(until).strftime("%H:%M:%S")
    res["soft"] = True
    res["expired"] = False
    _save(store, acc, res)
    log("【%s】%s" % (acc.get("name") or acc.get("student_no"), res["msg"]))
    return res


def _blocked_left(acc):
    left = _blocked.get(acc.get("id"), 0) - time.time()
    return left if left > 0 else 0


def sign_account(store, acc, force=False):
    """
    签一个账号，返回 dict：
        ok      今天是否已签（含原本就签过）
        msg     给界面显示的文字
        expired 会话失效
        soft    只是没到时间，不算失败
    """
    res = {"ok": False, "msg": "", "expired": False, "soft": False}

    if _blocked_left(acc) > 0:
        res["msg"] = "账号被冻结，%s 后自动恢复，本轮跳过" % \
                     datetime.fromtimestamp(_blocked[acc["id"]]).strftime("%H:%M:%S")
        res["soft"] = True
        return res

    ck = store.cookies(acc)
    if not ck:
        res["msg"] = "没有登录凭证，请点「重新登录」"
        res["expired"] = True
        _save(store, acc, res)
        return res

    s = make_session(ck)
    try:
        info = get_info(s)
    except Frozen as e:
        return _frozen(store, acc, res, str(e))
    if info is None:
        # 会话过期但存过密码，就自己重新登录一次（刚登过就先等等，别连环登）
        last = _last_login.get(acc.get("id"), 0)
        if time.time() - last < LOGIN_COOLDOWN:
            res["msg"] = "刚登录过，先不重复登录（%d 秒后再试）" % \
                         int(LOGIN_COOLDOWN - (time.time() - last))
            res["expired"] = True
            res["soft"] = True
            _save(store, acc, res)
            return res
        new_ck = auto_relogin(acc)
        if new_ck:
            acc["cookies"] = new_ck
            _last_login[acc.get("id")] = time.time()
            try:
                store.update(acc)
            except Exception:
                pass
            s = make_session(new_ck)
            try:
                info = get_info(s)
            except Frozen as e:
                return _frozen(store, acc, res, str(e))
        if info is None:
            res["msg"] = "登录已失效，需要重新登录"
            res["expired"] = True
            _save(store, acc, res)
            return res

    cf = info.get("CONFIG_INFO") or {}
    qd = info.get("QD_INFO") or {}
    ssl = info.get("SSL_INFO") or {}

    # 顺手补全学号/宿舍
    dirty = False
    no = ssl.get("XSBH") or ""
    dorm = ssl.get("SSLDM_DISPLAY") or ""
    xm = ssl.get("XM") or ""
    if no and no != acc.get("student_no"):
        acc["student_no"] = no
        dirty = True
    if dorm and dorm != acc.get("dorm"):
        acc["dorm"] = dorm
        dirty = True
    if xm and (not acc.get("name") or acc.get("name") == acc.get("student_no")):
        acc["name"] = xm
        dirty = True
    if dirty:
        store.update(acc)

    if qd.get("hasQd"):
        res["ok"] = True
        res["msg"] = "今天已签到"
        _save(store, acc, res)
        return res

    if not cf.get("IN_CQ_TIME") and not force:
        res["msg"] = "不在签到时段（%s-%s）" % (cf.get("CQKSSJ"), cf.get("CQJSSJ"))
        res["soft"] = True
        _save(store, acc, res)
        return res

    pt = pick_point(info)
    if not pt:
        res["msg"] = "没匹配到打卡点（宿舍：%s）" % dorm
        _save(store, acc, res)
        return res

    lat, lng = float(pt["WDZB"]), float(pt["JDZB"])
    lat, lng = jitter(lat, lng, 3.0)
    addr = pt.get("QDDD") or ""
    resp = add_sign(s, lat, lng, addr)

    if frozen_text(str(resp.get("msg") or "")):
        return _frozen(store, acc, res, str(resp.get("msg")))

    if str(resp.get("code")) == "0":
        time.sleep(2)
        try:
            now = get_info(s)
        except Frozen as e:
            return _frozen(store, acc, res, str(e))
        if now and (now.get("QD_INFO") or {}).get("hasQd"):
            res["ok"] = True
            res["msg"] = "签到成功（%s）" % addr
        else:
            res["ok"] = True
            res["msg"] = "已提交，状态待刷新（%s）" % addr
    else:
        res["msg"] = "提交失败：%s" % (resp.get("msg") or resp.get("code"))
    _save(store, acc, res)
    return res


def _save(store, acc, res):
    acc["last_time"] = int(time.time())
    acc["last_ok"] = STATE_OK if res.get("ok") else (STATE_NONE if res.get("soft") else STATE_FAIL)
    acc["last_result"] = res.get("msg") or ""
    try:
        store.update(acc)
    except Exception:
        pass
    log("【%s】%s" % (acc.get("name") or acc.get("student_no") or acc.get("id"),
                    res.get("msg")))


def state_text(acc):
    v = acc.get("last_ok", -1)
    if v == STATE_OK:
        return "已签到"
    if v == STATE_FAIL:
        return "失败"
    return "未签"


def hhmm(ts):
    if not ts:
        return "—"
    return datetime.fromtimestamp(ts).strftime("%m-%d %H:%M")
