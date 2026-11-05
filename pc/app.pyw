# -*- coding: utf-8 -*-
"""
协和查寝 · 电脑版（图形界面）
双击「协和查寝.lnk」或直接运行本文件即可。

功能与手机 App 对齐：
  多账号管理 / 单账号签到 / 一键签到（带间隔）/ 定时自动签到 /
  清缓存 / 清日志 / 导出日志 / 危险操作二次确认
"""

import json
import os
import sys
import threading
import time
from datetime import datetime, timedelta

import tkinter as tk
from tkinter import ttk, messagebox, filedialog, scrolledtext

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import core  # noqa: E402
import secure  # noqa: E402

APP_FILE = os.path.abspath(__file__)
HELPER = os.path.join(core.BASE_DIR, "login_helper.py")
SETTINGS_PATH = os.path.join(core.DATA, "settings.json")

DEFAULT_SETTINGS = {"gap": 3, "auto": False, "time": "21:30"}


def pyw_exe():
    """优先用 pythonw.exe 启动子进程，避免弹黑框"""
    exe = sys.executable
    base, ext = os.path.splitext(exe)
    if base.lower().endswith("python"):
        cand = base + "w" + ext
        if os.path.exists(cand):
            return cand
    return exe


def ps_lit(s):
    """包成 PowerShell 单引号字符串（内部单引号转义）"""
    return "'" + str(s).replace("'", "''") + "'"


def already_running():
    """同一个程序只允许开一个窗口；已存在就把旧窗口叫到前面"""
    try:
        import ctypes
        k = ctypes.windll.kernel32
        k.CreateMutexW(None, False, "Local\\XieHeQianDaoPCApp")
        if k.GetLastError() != 183:       # ERROR_ALREADY_EXISTS
            return False
        u = ctypes.windll.user32
        h = u.FindWindowW(None, "协和查寝 · 电脑版")
        if h:
            u.ShowWindow(h, 9)
            u.SetForegroundWindow(h)
        return True
    except Exception:
        return False


def human(n):
    if n < 1024:
        return "%d B" % n
    if n < 1024 * 1024:
        return "%.1f KB" % (n / 1024.0)
    return "%.1f MB" % (n / 1048576.0)


def dir_size(path):
    total = 0
    if not os.path.exists(path):
        return 0
    if os.path.isfile(path):
        return os.path.getsize(path)
    for root, _, files in os.walk(path):
        for f in files:
            try:
                total += os.path.getsize(os.path.join(root, f))
            except Exception:
                pass
    return total


class App(object):

    def __init__(self):
        core.ensure_dirs()
        self.store = core.Store()
        self.settings = self.load_settings()

        self.signing = False
        self.last_auto_day = None

        self.root = tk.Tk()
        self.root.title("协和查寝 · 电脑版")
        self.root.geometry("900x720")
        self.root.minsize(820, 640)
        try:
            self.root.iconbitmap(default="")
        except Exception:
            pass

        self.build_ui()
        self.refresh()
        self.say("程序已启动。账号存在 data\\accounts.db，日志在 data\\app.log")

        threading.Thread(target=self.scheduler_loop, daemon=True).start()
        self.root.protocol("WM_DELETE_WINDOW", self.on_close)
        threading.Thread(target=self.ensure_shortcut, daemon=True).start()

    def ensure_shortcut(self):
        """首次运行时在程序目录生成带图标的快捷方式（失败就算了，不影响使用）"""
        lnk = os.path.join(core.BASE_DIR, "协和查寝.lnk")
        if os.path.exists(lnk):
            return
        exe = pyw_exe()
        ps = ("$w=New-Object -ComObject WScript.Shell;$s=$w.CreateShortcut(%s);"
              "$s.TargetPath=%s;$s.Arguments=%s;$s.WorkingDirectory=%s;"
              "$s.IconLocation=%s;$s.Description=%s;$s.Save()") % (
            ps_lit(lnk), ps_lit(exe), ps_lit('"%s"' % APP_FILE),
            ps_lit(core.BASE_DIR), ps_lit(exe + ",0"), ps_lit("协和查寝 · 电脑版"))
        try:
            __import__("subprocess").run(
                ["powershell", "-NoProfile", "-Command", ps],
                capture_output=True, timeout=25)
        except Exception:
            pass

    # ------------------------------------------------------------ 界面

    def build_ui(self):
        pad = {"padx": 10}

        # 顶部状态
        top = tk.Frame(self.root, bg="#f7f8fa")
        top.pack(fill="x")
        self.lbl_status = tk.Label(top, text="正在读取…", anchor="w", justify="left",
                                   bg="#ffffff", fg="#1f2937", padx=12, pady=10,
                                   font=("Microsoft YaHei UI", 10))
        self.lbl_status.pack(fill="x", padx=10, pady=(10, 6))

        # 账号表
        box = tk.LabelFrame(self.root, text=" 账号列表 ", fg="#374151",
                            font=("Microsoft YaHei UI", 9))
        box.pack(fill="both", expand=False, padx=10, pady=4)

        cols = ("name", "no", "dorm", "state", "last", "result")
        heads = ("名称", "学号", "宿舍", "状态", "上次签到", "最近结果")
        widths = (110, 130, 120, 70, 130, 300)
        self.tree = ttk.Treeview(box, columns=cols, show="headings", height=6)
        for c, h, w in zip(cols, heads, widths):
            self.tree.heading(c, text=h)
            self.tree.column(c, width=w, anchor="w")
        self.tree.pack(fill="x", padx=6, pady=6)
        self.tree.bind("<Double-1>", lambda e: self.sign_selected())

        bar = tk.Frame(box)
        bar.pack(fill="x", padx=6, pady=(0, 8))
        tk.Button(bar, text="＋ 添加账号", command=self.add_account,
                  width=12).pack(side="left")
        tk.Button(bar, text="重新登录选中", command=self.relogin_selected,
                  width=12).pack(side="left", padx=6)
        tk.Button(bar, text="签到选中", command=self.sign_selected,
                  width=10).pack(side="left")
        tk.Button(bar, text="删除选中", command=self.delete_selected,
                  width=10).pack(side="left", padx=6)
        tk.Button(bar, text="一键签到（全部）", command=self.sign_all,
                  width=16, bg="#2563eb", fg="white").pack(side="right")

        # 设置
        st = tk.LabelFrame(self.root, text=" 设置 ", fg="#374151",
                           font=("Microsoft YaHei UI", 9))
        st.pack(fill="x", padx=10, pady=4)

        row1 = tk.Frame(st)
        row1.pack(fill="x", padx=6, pady=6)
        tk.Label(row1, text="账号之间的签到间隔：").pack(side="left")
        self.spin_gap = tk.Spinbox(row1, from_=0, to=120, width=5)
        self.spin_gap.delete(0, "end")
        self.spin_gap.insert(0, str(self.settings.get("gap", 3)))
        self.spin_gap.pack(side="left")
        tk.Label(row1, text="秒（防风控，建议 3–10）").pack(side="left")

        row2 = tk.Frame(st)
        row2.pack(fill="x", padx=6, pady=(0, 6))
        self.var_auto = tk.BooleanVar(value=bool(self.settings.get("auto")))
        tk.Checkbutton(row2, text="每天自动签到，时间：", variable=self.var_auto,
                       command=self.on_auto_toggle).pack(side="left")
        self.entry_time = tk.Entry(row2, width=8)
        self.entry_time.insert(0, self.settings.get("time", "21:30"))
        self.entry_time.pack(side="left")
        tk.Button(row2, text="保存时间", command=self.save_time,
                  width=9).pack(side="left", padx=6)
        self.var_startup = tk.BooleanVar(value=os.path.exists(self.startup_lnk()))
        tk.Checkbutton(row2, text="开机自动启动", variable=self.var_startup,
                       command=self.on_startup_toggle).pack(side="left", padx=12)
        tk.Label(row2, text="（窗口可以最小化，别关）", fg="#9ca3af").pack(side="left")

        # 日志
        lg = tk.LabelFrame(self.root, text=" 运行日志 ", fg="#374151",
                           font=("Microsoft YaHei UI", 9))
        lg.pack(fill="both", expand=True, padx=10, pady=4)

        self.txt_log = scrolledtext.ScrolledText(lg, height=12, bg="#111827",
                                                 fg="#b9f6ca", insertbackground="#b9f6ca",
                                                 font=("Consolas", 9), wrap="word")
        self.txt_log.pack(fill="both", expand=True, padx=6, pady=6)

        bottom = tk.Frame(self.root)
        bottom.pack(fill="x", padx=10, pady=(4, 10))
        tk.Button(bottom, text="清除缓存", command=self.clear_cache,
                  width=12).pack(side="left")
        tk.Button(bottom, text="清除日志", command=self.clear_log,
                  width=12).pack(side="left", padx=6)
        tk.Button(bottom, text="导出日志", command=self.export_log,
                  width=12).pack(side="left")
        tk.Button(bottom, text="刷新", command=self.refresh,
                  width=8).pack(side="right")

    def say(self, text):
        line = core.log(text)
        self.root.after(0, lambda: self._append(line))

    def _append(self, line):
        self.txt_log.insert("end", line + "\n")
        self.txt_log.see("end")

    # ------------------------------------------------------------ 数据

    def load_settings(self):
        try:
            with open(SETTINGS_PATH, encoding="utf-8") as f:
                data = json.load(f)
        except Exception:
            data = {}
        out = dict(DEFAULT_SETTINGS)
        out.update(data or {})
        return out

    def save_settings(self):
        try:
            with open(SETTINGS_PATH, "w", encoding="utf-8") as f:
                json.dump(self.settings, f, ensure_ascii=False, indent=2)
        except Exception as e:
            self.say("保存设置失败：%s" % e)

    def refresh(self):
        accs = self.store.list()
        self.tree.delete(*self.tree.get_children())
        ok_n = 0
        for a in accs:
            if a.get("last_ok") == core.STATE_OK:
                ok_n += 1
            self.tree.insert("", "end", iid=str(a["id"]), values=(
                a.get("name") or "账号%d" % a["id"],
                a.get("student_no") or "—",
                a.get("dorm") or "—",
                core.state_text(a),
                core.hhmm(a.get("last_time")),
                (a.get("last_result") or "")[:60],
            ))
        auto = self.settings.get("auto")
        self.lbl_status.config(text=(
            "账号：共 %d 个，今日已签 %d 个\n"
            "定时打卡：%s　%s\n"
            "数据目录：%s" % (
                len(accs), ok_n,
                "开" if auto else "关",
                ("每天 " + self.settings.get("time", "21:30")) if auto else "（不会自动签到，可随时手动签）",
                core.DATA)))

    def selected(self):
        sel = self.tree.selection()
        if not sel:
            return None
        return self.store.get(int(sel[0]))

    # ------------------------------------------------------------ 添加 / 登录

    def add_account(self):
        self.do_login(None)

    def relogin_selected(self):
        acc = self.selected()
        if not acc:
            messagebox.showinfo("提示", "先在列表里选中一个账号")
            return
        self.do_login(acc)

    def ask_credentials(self, existing):
        """问学号密码；返回 (user, pwd) 或 None 表示取消"""
        dlg = tk.Toplevel(self.root)
        dlg.title("登录信息" if existing else "添加账号")
        dlg.transient(self.root)
        dlg.resizable(False, False)
        dlg.grab_set()

        frm = ttk.Frame(dlg, padding=16)
        frm.pack(fill="both", expand=True)

        ttk.Label(frm, text="登录窗口打开后会自动填好这两项，你只需要拖动滑块并点「登录」。",
                  wraplength=430, justify="left").grid(
            row=0, column=0, columnspan=2, sticky="w", pady=(0, 12))

        ttk.Label(frm, text="学号").grid(row=1, column=0, sticky="e", padx=(0, 8))
        v_user = tk.StringVar(value=(existing or {}).get("student_no", ""))
        e_user = ttk.Entry(frm, textvariable=v_user, width=34)
        e_user.grid(row=1, column=1, sticky="w", pady=3)

        ttk.Label(frm, text="密码").grid(row=2, column=0, sticky="e", padx=(0, 8))
        v_pwd = tk.StringVar()
        e_pwd = ttk.Entry(frm, textvariable=v_pwd, width=34, show="*")
        e_pwd.grid(row=2, column=1, sticky="w", pady=3)

        ttk.Label(frm, text="密码只在本次登录时填进网页，不保存到本地。",
                  foreground="#888").grid(row=3, column=1, sticky="w", pady=(4, 10))

        result = {"ok": False}

        def on_ok():
            if not v_user.get().strip():
                messagebox.showwarning("提示", "学号不能为空", parent=dlg)
                return
            result["ok"] = True
            dlg.destroy()

        bar = ttk.Frame(frm)
        bar.grid(row=4, column=0, columnspan=2, sticky="e", pady=(6, 0))
        ttk.Button(bar, text="取消", command=dlg.destroy).pack(side="right")
        ttk.Button(bar, text="  打开登录窗口  ", command=on_ok).pack(side="right", padx=(0, 8))

        dlg.bind("<Return>", lambda e: on_ok())
        dlg.bind("<Escape>", lambda e: dlg.destroy())

        dlg.update_idletasks()
        x = self.root.winfo_x() + (self.root.winfo_width() - dlg.winfo_width()) // 2
        y = self.root.winfo_y() + 140
        dlg.geometry("+%d+%d" % (max(x, 0), max(y, 0)))
        (e_user if not v_user.get() else e_pwd).focus_set()
        self.root.wait_window(dlg)

        if not result["ok"]:
            return None
        return v_user.get().strip(), v_pwd.get()

    def do_login(self, existing):
        # 已经有登录窗口在开着，就别再开第二个
        if getattr(self, "_login_busy", False):
            messagebox.showinfo("提示", "登录窗口已经打开了，先把它用完或关掉再点。")
            return
        cred = self.ask_credentials(existing)
        if cred is None:
            return
        if existing and existing.get("profile"):
            profile = existing["profile"]
            if not os.path.isabs(profile):
                profile = os.path.join(core.PROFILE_DIR, profile)
        else:
            profile = os.path.join(core.PROFILE_DIR, "acc_%d" % int(time.time()))
        out = os.path.join(core.TMP_DIR, "login_%d.json" % int(time.time()))
        if os.path.exists(out):
            os.remove(out)

        self._login_busy = True
        if cred[1]:
            self.say("正在后台自动登录（会自己过滑块，窗口不会弹出来）…")
        else:
            self.say("正在打开登录窗口……切到「账号登录」并填好学号，拖滑块点登录即可")
        threading.Thread(target=self._login_worker, args=(existing, profile, out, cred),
                         daemon=True).start()

    def _run_helper(self, profile, out, cred, auto, timeout):
        """跑一次登录子进程，返回退出码"""
        cmd = [pyw_exe(), HELPER, profile, out]
        env = dict(os.environ)
        env["XIEHE_USER"] = cred[0]
        env["XIEHE_PWD"] = cred[1]
        if auto:
            env["XIEHE_AUTO"] = "1"
        p = __import__("subprocess").Popen(cmd, cwd=core.BASE_DIR, env=env)
        try:
            return p.wait(timeout=timeout)
        except Exception:
            try:
                p.kill()
            except Exception:
                pass
            return 9

    def _login_worker(self, existing, profile, out, cred):
        # 有密码就先试后台自动登录，成功的话用户完全无感
        rc = None
        if cred[1]:
            rc = self._run_helper(profile, out, cred, auto=True, timeout=150)
            if rc != 0:
                self.say("自动登录没成功，改成弹窗口，请手动拖一下滑块")
                try:
                    os.remove(out)
                except Exception:
                    pass
                rc = self._run_helper(profile, out, cred, auto=False, timeout=300)
        else:
            rc = self._run_helper(profile, out, cred, auto=False, timeout=300)

        self._login_busy = False

        if rc != 0 or not os.path.exists(out):
            msg = {2: "登录超时，请重试",
                   3: "登录窗口被关闭，已取消",
                   5: "用户名或密码不正确，请检查后重试",
                   1: "参数错误"}.get(rc, "登录失败（退出码 %s）" % rc)
            self.say(msg)
            return

        try:
            with open(out, encoding="utf-8") as f:
                ck = (json.load(f) or {}).get("cookies") or {}
        except Exception as e:
            self.say("读取登录结果失败：%s" % e)
            return

        info = core.get_info(core.make_session(ck))
        if info is None:
            self.say("Cookie 拿到了，但接口没通过，请再登录一次")
            return

        ssl = info.get("SSL_INFO") or {}
        no = ssl.get("XSBH") or ""
        dorm = ssl.get("SSLDM_DISPLAY") or ""
        xm = ssl.get("XM") or ""
        name = xm or no or "账号"

        # 密码用 DPAPI 加密后存库，以后 Cookie 过期能自己重新登录
        pwd_token = secure.encrypt(cred[1]) if cred[1] else ""

        if existing:
            existing.update({"cookies": ck, "profile": profile, "name": name or existing["name"],
                             "student_no": no or existing["student_no"],
                             "dorm": dorm or existing["dorm"],
                             "password": pwd_token or existing.get("password") or ""})
            self.store.update(existing)
            self.say("已更新「%s」的登录状态" % name)
        else:
            dup = self.store.find_by_no(no)
            if dup:
                dup.update({"cookies": ck, "profile": profile,
                            "name": name or dup["name"], "dorm": dorm or dup["dorm"],
                            "password": pwd_token or dup.get("password") or ""})
                self.store.update(dup)
                self.say("该账号已存在（%s），已更新登录状态，没有重复添加" % name)
            else:
                self.store.add(name, no, dorm, ck, profile, pwd_token)
                self.say("已添加账号「%s」%s%s" % (
                    name, ("（" + dorm + "）") if dorm else "",
                    "，已保存密码，过期后能自动重新登录" if pwd_token else ""))

        try:
            os.remove(out)
        except Exception:
            pass
        self.root.after(0, self.refresh)

    # ------------------------------------------------------------ 签到

    def sign_selected(self):
        acc = self.selected()
        if not acc:
            messagebox.showinfo("提示", "先在列表里选中一个账号")
            return
        if self.signing:
            messagebox.showinfo("提示", "正在签到中，请稍候")
            return
        self.signing = True
        threading.Thread(target=self._sign_worker, args=([acc], 0, False),
                         daemon=True).start()

    def sign_all(self):
        accs = self.store.list()
        if not accs:
            messagebox.showinfo("提示", "还没有账号，先点「添加账号」")
            return
        if self.signing:
            messagebox.showinfo("提示", "正在签到中，请稍候")
            return
        self.signing = True
        threading.Thread(target=self._sign_worker,
                         args=(accs, self.gap(), False), daemon=True).start()

    def gap(self):
        try:
            return max(0, min(120, int(self.spin_gap.get())))
        except Exception:
            return 3

    def _sign_worker(self, accs, gap, retry_loop):
        try:
            deadline = time.time() + 45 * 60
            round_no = 0
            while True:
                round_no += 1
                ok_n = 0
                expired_n = 0
                for i, a in enumerate(accs):
                    self.say("【%s】开始签到（%d/%d）" % (a.get("name") or a["id"],
                                                      i + 1, len(accs)))
                    r = core.sign_account(self.store, a)
                    self.say("【%s】%s" % (a.get("name") or a["id"], r["msg"]))
                    if r["ok"]:
                        ok_n += 1
                    if r["expired"]:
                        expired_n += 1
                    self.root.after(0, self.refresh)
                    if i < len(accs) - 1 and gap > 0:
                        time.sleep(gap)

                self.say("本轮完成：%d/%d 已签" % (ok_n, len(accs)))
                if ok_n == len(accs):
                    self.say("全部账号已签到")
                    break
                if expired_n == len(accs):
                    self.say("所有账号的登录都失效了，请在列表里点「重新登录选中」")
                    break
                if not retry_loop:
                    break
                if time.time() + 60 > deadline:
                    self.say("试了 45 分钟没签上，先停一下")
                    break
                self.say("60 秒后重试…")
                time.sleep(60)
        except Exception as e:
            self.say("签到异常：%s" % e)
        finally:
            self.signing = False
            self.root.after(0, self.refresh)

    # ------------------------------------------------------------ 删除

    def delete_selected(self):
        acc = self.selected()
        if not acc:
            messagebox.showinfo("提示", "先在列表里选中一个账号")
            return
        name = acc.get("name") or acc.get("student_no") or acc["id"]
        if not messagebox.askyesno("删除账号",
                                   "确定删除「%s」吗？\n删除后需要重新登录才能再签到。" % name):
            return
        self.store.delete(acc["id"])
        self.say("已删除账号「%s」" % name)
        self.refresh()

    # ------------------------------------------------------------ 定时

    def on_auto_toggle(self):
        self.settings["auto"] = bool(self.var_auto.get())
        self.save_settings()
        if self.settings["auto"]:
            t = self.settings.get("time", "21:30")
            self.say("定时自动打卡已开启：每天 %s（窗口别关，可最小化）" % t)
            self.last_auto_day = None
        else:
            self.say("定时自动打卡已关闭")
        self.refresh()

    def save_time(self):
        v = self.entry_time.get().strip()
        try:
            datetime.strptime(v, "%H:%M")
        except Exception:
            messagebox.showerror("时间格式不对", "请写成 21:30 这样（24 小时制）")
            return
        self.settings["time"] = v
        self.save_settings()
        self.say("自动签到时间已设为每天 %s" % v)
        self.refresh()

    def scheduler_loop(self):
        """后台线程：到点就自动签"""
        while True:
            time.sleep(10)
            try:
                if not self.settings.get("auto"):
                    continue
                if self.signing:
                    continue
                now = datetime.now()
                if self.last_auto_day == now.date():
                    continue
                hh, mm = [int(x) for x in self.settings.get("time", "21:30").split(":")]
                target = now.replace(hour=hh, minute=mm, second=0, microsecond=0)
                if now < target or (now - target) > timedelta(minutes=45):
                    continue
                self.last_auto_day = now.date()
                accs = self.store.list()
                if not accs:
                    continue
                self.signing = True
                self.say("到点了（%s），开始自动签到" % self.settings.get("time"))
                threading.Thread(target=self._sign_worker,
                                 args=(accs, self.gap(), True), daemon=True).start()
            except Exception as e:
                core.log("定时线程异常：%s" % e)

    def startup_lnk(self):
        d = os.path.join(os.environ.get("APPDATA", ""),
                         r"Microsoft\Windows\Start Menu\Programs\Startup")
        return os.path.join(d, "协和查寝.lnk")

    def on_startup_toggle(self):
        lnk = self.startup_lnk()
        try:
            if self.var_startup.get():
                ps = ("$w=New-Object -ComObject WScript.Shell;$s=$w.CreateShortcut(%s);"
                      "$s.TargetPath=%s;$s.Arguments=%s;$s.WorkingDirectory=%s;"
                      "$s.WindowStyle=7;$s.Save()") % (
                    ps_lit(lnk), ps_lit(pyw_exe()), ps_lit('"%s"' % APP_FILE),
                    ps_lit(core.BASE_DIR))
                r = __import__("subprocess").run(
                    ["powershell", "-NoProfile", "-Command", ps],
                    capture_output=True, text=True, timeout=30)
                if os.path.exists(lnk):
                    self.say("已设置开机自动启动（启动后窗口最小化在任务栏）")
                else:
                    self.say("设置开机启动失败：%s" % (r.stderr or "")[:120])
                    self.var_startup.set(False)
            else:
                if os.path.exists(lnk):
                    os.remove(lnk)
                self.say("已取消开机自动启动")
        except Exception as e:
            self.say("设置开机启动出错：%s" % e)
            self.var_startup.set(os.path.exists(lnk))

    # ------------------------------------------------------------ 缓存 / 日志

    def clear_cache(self):
        size = dir_size(core.TMP_DIR) + dir_size(os.path.join(core.BASE_DIR, "__pycache__"))
        if not messagebox.askyesno(
                "清除缓存",
                "将删除临时文件与 Python 缓存，共 %s。\n\n"
                "不会删除：账号列表、登录凭证、运行日志。" % human(size)):
            return
        removed = 0
        for d in (core.TMP_DIR, os.path.join(core.BASE_DIR, "__pycache__")):
            if not os.path.isdir(d):
                continue
            for name in os.listdir(d):
                p = os.path.join(d, name)
                try:
                    if os.path.isfile(p):
                        os.remove(p)
                        removed += 1
                    else:
                        __import__("shutil").rmtree(p, ignore_errors=True)
                        removed += 1
                except Exception:
                    pass
        os.makedirs(core.TMP_DIR, exist_ok=True)
        left = dir_size(core.TMP_DIR)
        self.say("已清除缓存：%d 项，%s%s" % (
            removed, human(size), "，已清空" if left == 0 else "，仍有 " + human(left)))

    def clear_log(self):
        size = dir_size(core.LOG_PATH)
        if not messagebox.askyesno(
                "清除日志",
                "将清空全部运行日志（%s）。\n\n建议先「导出日志」留档。" % human(size)):
            return
        try:
            open(core.LOG_PATH, "w", encoding="utf-8").close()
            self.txt_log.delete("1.0", "end")
            self.say("日志已清空")
        except Exception as e:
            self.say("清空失败：%s" % e)

    def export_log(self):
        name = "xiehe-log-%s.txt" % datetime.now().strftime("%Y%m%d-%H%M")
        path = filedialog.asksaveasfilename(
            title="导出日志", defaultextension=".txt", initialfile=name,
            filetypes=[("文本文件", "*.txt"), ("所有文件", "*.*")])
        if not path:
            return
        try:
            if os.path.exists(core.LOG_PATH):
                with open(core.LOG_PATH, encoding="utf-8") as src:
                    text = src.read()
            else:
                text = self.txt_log.get("1.0", "end")
            with open(path, "w", encoding="utf-8") as f:
                f.write(text)
            self.say("日志已导出：%s（%s）" % (path, human(len(text.encode("utf-8")))))
            messagebox.showinfo("导出成功", "已保存到：\n" + path)
        except Exception as e:
            messagebox.showerror("导出失败", str(e))

    # ------------------------------------------------------------

    def on_close(self):
        if self.settings.get("auto") and self.var_auto.get():
            if not messagebox.askyesno(
                    "退出确认",
                    "定时自动签到依赖这个窗口，关掉后今天就不会自动签了。\n确定退出吗？"):
                return
        self.root.destroy()

    def run(self):
        self.root.mainloop()


if __name__ == "__main__":
    if already_running():
        sys.exit(0)
    App().run()
