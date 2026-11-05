# -*- coding: utf-8 -*-
"""
登录子进程：打开一个独立的 Chromium 窗口，让用户手动登录（学校强制滑块），
登录成功后把 Cookie 写进 out_json，然后自己关掉窗口。

用法：  python login_helper.py <profile_dir> <out_json>
退出码：0 成功 / 2 超时 / 3 窗口被关闭 / 1 参数错误
"""

import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import core  # noqa: E402

WAIT_SECONDS = 240


def _vis(page, sel):
    """元素是否存在且可见"""
    try:
        loc = page.locator(sel).first
        return loc.count() > 0 and loc.is_visible()
    except Exception:
        return False


def reveal_password_form(page):
    """
    登录页默认停在「微信登录」标签页，账号密码表单是隐藏的（页面右半边一片空白）。
    这里自动点一下「账号登录」，把学号/密码框显示出来。
    """
    for _ in range(15):
        if _vis(page, "#username"):
            return True
        for sel in ("a.loginFont_a:has-text('账号登录')",
                    "span:has-text('账号登录')",
                    "#swipeCaptchaDiv ~ * >> text=账号登录",
                    "text=账号登录"):
            try:
                loc = page.locator(sel).first
                if loc.count() == 0:
                    continue
                if not loc.is_visible():
                    continue
                loc.click(timeout=3000)
                page.wait_for_timeout(900)
                if _vis(page, "#username"):
                    core.log("已自动切换到「账号登录」")
                    return True
            except Exception:
                continue
        page.wait_for_timeout(700)
    core.log("没找到「账号登录」标签，页面可能没加载完")
    return False


def fill_credentials(page, user, pwd):
    """把学号/密码填进表单（用户只需拖滑块）"""
    if not user:
        return False
    try:
        loc = page.locator("#username").first
        loc.fill("")
        loc.type(user, delay=60)
        if pwd:
            pl = page.locator("#password").first
            pl.fill("")
            pl.type(pwd, delay=50)
        core.log("已自动填入学号%s" % ("和密码" if pwd else "（密码需手动输入）"))
        return True
    except Exception as e:
        core.log("自动填写失败：%s" % e)
        return False


def tick_remember(page):
    """勾上「7 天免登录」，让 Cookie 活得更久"""
    for sel in ("#rememberMe", "input[name='rememberMe']", "#rememberMeCheck"):
        try:
            loc = page.locator(sel).first
            if not (loc.count() and loc.is_visible()):
                continue
            if not loc.is_checked():
                loc.check(timeout=2500)
            core.log("已勾选「7天免登录」")
            return True
        except Exception:
            continue
    return False


def waf_blocked(page):
    try:
        t = page.inner_text("body")[:400]
    except Exception:
        return False
    return "安全设备" in t or "已被安全设备" in t


def cookies_of(ctx):
    out = {}
    try:
        for c in ctx.cookies():
            if "sdxiehe" in (c.get("domain") or ""):
                out[c["name"]] = c["value"]
    except Exception:
        pass
    return out


def click_login(page):
    """点登录按钮，触发滑块或直接提交"""
    try:
        page.evaluate("""() => {
            const el = document.querySelector('#login_submit')
                    || document.querySelector('.login-btn');
            if (el) { el.click(); return true; }
            return false;
        }""")
        return True
    except Exception as e:
        core.log("点登录按钮失败：%s" % e)
        return False


def main():
    if len(sys.argv) < 3:
        return 1
    profile = os.path.abspath(sys.argv[1])
    out = os.path.abspath(sys.argv[2])
    os.makedirs(profile, exist_ok=True)
    os.makedirs(os.path.dirname(out), exist_ok=True)

    # 学号密码走环境变量传，避免出现在命令行里被其他进程看到
    user = (os.environ.get("XIEHE_USER") or "").strip()
    pwd = os.environ.get("XIEHE_PWD") or ""
    auto = (os.environ.get("XIEHE_AUTO") or "") == "1"

    core.log("打开登录窗口（配置目录：%s）" % os.path.basename(profile))

    from playwright.sync_api import sync_playwright

    # 自动模式下把窗口挪到屏幕外，用户看不见
    pos = "-2400,40" if auto else "120,40"

    with sync_playwright() as p:
        ctx = p.chromium.launch_persistent_context(
            profile,
            headless=False,
            viewport=None,                      # 跟着窗口大小走，别把页面压窄
            args=["--window-size=1180,920", "--window-position=" + pos,
                  "--disable-blink-features=AutomationControlled"],
        )
        page = ctx.pages[0] if ctx.pages else ctx.new_page()
        try:
            page.goto(core.SERVICE, wait_until="domcontentloaded", timeout=60000)
        except Exception as e:
            core.log("打开登录页失败：%s" % e)
        page.wait_for_timeout(1500)

        if waf_blocked(page):
            core.log("被学校安全设备拦截了，请稍后重试")
            try:
                ctx.close()
            except Exception:
                pass
            return 2

        reveal_password_form(page)
        filled = fill_credentials(page, user, pwd)
        tick_remember(page)

        # 账号密码都有了就自动点登录，否则留给人工
        if filled and pwd:
            click_login(page)
            page.wait_for_timeout(2500)
            if page.locator("#sliderDiv").count():
                core.log("出现滑块验证，开始自动处理")
                import slider
                if slider.solve(page, log=core.log):
                    core.log("滑块通过，等待页面跳转")
                else:
                    core.log("滑块没通过，转人工：请在窗口里手动拖一次")
            else:
                core.log("无需滑块，等待页面跳转")

        # 自动模式没有人工介入，跳转应该很快，别干等 4 分钟
        deadline = time.time() + (70 if auto else WAIT_SECONDS)
        ok = False
        closed = False
        last_probe = 0.0
        while time.time() < deadline:
            try:
                url = page.url
            except Exception:
                closed = True
                break

            if "ids.sdxiehe" not in url and "xg.sdxiehe" in url:
                time.sleep(2)      # 等 ticket 换完 Cookie
                ok = True
                break

            # 密码错了就别干等了，直接返回
            if "error" in url.lower() or "reauth" in url.lower():
                core.log("登录失败：用户名或密码不正确")
                break
            try:
                body = page.inner_text("body")[:3000]
                for kw in ("密码有误", "密码错误", "密码不正确", "用户名或者密码",
                           "用户名或密码", "账号或密码", "用户名不存在",
                           "账户被锁定", "账号被锁定", "验证码错误"):
                    if kw in body:
                        core.log("登录失败：%s" % kw)
                        closed = "badpwd"
                        break
                if closed == "badpwd":
                    break
            except Exception:
                pass

            # 兜底：URL 没变但会话已经可用（有些跳转会停留在原地址）
            now = time.time()
            if now - last_probe > 6:
                last_probe = now
                ck = cookies_of(ctx)
                if ck:
                    try:
                        if core.get_info(core.make_session(ck)) is not None:
                            ok = True
                            break
                    except Exception:
                        pass

            time.sleep(1)

        if not ok:
            try:
                ctx.close()
            except Exception:
                pass
            if closed == "badpwd":
                return 5
            if closed:
                core.log("登录窗口被关闭，放弃")
                return 3
            core.log("等待登录超时")
            return 2

        cookies = cookies_of(ctx)
        try:
            ctx.close()
        except Exception:
            pass

    if not cookies:
        core.log("没抓到 Cookie，请确认登录是否真的成功了")
        return 2

    with open(out, "w", encoding="utf-8") as f:
        json.dump({"cookies": cookies, "profile": profile}, f, ensure_ascii=False)
    core.log("登录成功，已保存 %d 条 Cookie" % len(cookies))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except SystemExit:
        raise
    except Exception:
        import traceback
        detail = traceback.format_exc()
        try:
            core.log("登录窗口异常：%s" % detail.strip().splitlines()[-1])
        except Exception:
            pass
        try:
            with open(os.path.join(core.DATA, "login_error.txt"), "w",
                      encoding="utf-8") as f:
                f.write(detail)
        except Exception:
            pass
        sys.exit(4)
