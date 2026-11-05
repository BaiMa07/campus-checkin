# -*- coding: utf-8 -*-
"""
滑块验证码自动通过。

学校的登录页用 longbow sliderCaptcha，要点有三条：

1. 页面实例上的 ``inst.x`` 是**前端随机生成的**，不是正确答案，照着它拖必然失败。
   正确答案是背景图里那个被挖掉的拼图形状，得从图里算。
2. 缺口和拼图块在原图上大小一致，所以可以用拼图块的**形状边缘**去背景图的
   **梯度图**上滑动匹配，得分最高的位置就是缺口。
3. 轨迹必须带垂直方向的自然抖动。前端判定里 ``spliced = (垂直位移标准差 != 0)``，
   一路走直线会被判成机器。

拖动本身用 Playwright 的真实鼠标事件（isTrusted=true），不用 JS 改样式。
"""

import base64
import io
import random
import time

import numpy as np
from PIL import Image


# ---------------------------------------------------------------- 图像识别

def _load(src):
    """把 data:image/png;base64,xxx 解码成 ndarray"""
    if not src or "," not in src:
        return None
    raw = base64.b64decode(src.split(",", 1)[1])
    return np.asarray(Image.open(io.BytesIO(raw)).convert("RGBA")).astype(np.float32)


def _piece_mask(small):
    """拼图块的形状掩码：非透明且非纯白"""
    alpha = small[:, :, 3]
    rgb = small[:, :, :3]
    white = (rgb[:, :, 0] > 245) & (rgb[:, :, 1] > 245) & (rgb[:, :, 2] > 245)
    return (alpha > 40) & ~white


def find_gap(big_src, small_src, div_width):
    """
    返回 (拖动距离 canvas 像素, 缺口在原图的左边缘 x)。
    识别失败返回 None。
    """
    big = _load(big_src)
    small = _load(small_src)
    if big is None or small is None:
        return None

    mask = _piece_mask(small)
    ys, xs = np.where(mask)
    if len(xs) == 0:
        return None
    min_x, max_x = int(xs.min()), int(xs.max())
    min_y, max_y = int(ys.min()), int(ys.max())
    piece_w = max_x - min_x + 1

    sub = mask[min_y:max_y + 1, min_x:max_x + 1]
    h, w = sub.shape
    # 形状边缘点（上下左右任一邻居为空即为边缘）
    edge_pts = []
    for y in range(1, h - 1):
        row = sub[y]
        up, dn = sub[y - 1], sub[y + 1]
        for x in range(1, w - 1):
            if row[x] and not (up[x] and dn[x] and row[x - 1] and row[x + 1]):
                edge_pts.append((x, y))
    if not edge_pts:
        return None

    # 背景图梯度
    rgb = big[:, :, :3]
    lum = rgb[:, :, 0] * 0.299 + rgb[:, :, 1] * 0.587 + rgb[:, :, 2] * 0.114
    gx = np.zeros_like(lum)
    gy = np.zeros_like(lum)
    gx[:, 1:-1] = lum[:, 2:] - lum[:, :-2]
    gy[1:-1, :] = lum[2:, :] - lum[:-2, :]
    grad = np.sqrt(gx * gx + gy * gy)

    H, W = lum.shape
    ey = np.array([p[1] for p in edge_pts]) + min_y
    ex = np.array([p[0] for p in edge_pts])

    best_score, best_x = -1.0, 0
    for sx in range(W - piece_w):
        score = grad[ey, sx + ex].sum()
        if score > best_score:
            best_score, best_x = score, sx

    scale = div_width / float(W)
    return (best_x - min_x) * scale, best_x


# ---------------------------------------------------------------- 拖动

def _drag(page, distance):
    """拟人轨迹：缓慢起步、中段加速、尾部减速，带垂直抖动和一次停顿"""
    box = page.evaluate("""() => {
        const r = document.querySelector('#sliderDiv .slider').getBoundingClientRect();
        return [r.x + r.width / 2, r.y + r.height / 2];
    }""")
    x0, y0 = box

    page.mouse.move(x0 + random.uniform(-2, 2), y0 + random.uniform(-1, 1))
    page.wait_for_timeout(random.randint(160, 380))
    page.mouse.down()
    page.wait_for_timeout(random.randint(80, 180))

    steps = random.randint(26, 40)
    duration = random.randint(750, 1250)
    pause_at = random.randint(int(steps * 0.35), int(steps * 0.8))
    base_y = random.uniform(-1.2, 1.2)

    cur = 0.0
    for i in range(1, steps + 1):
        t = i / steps
        want = distance * (1 - (1 - t) ** 3)      # easeOutCubic
        if want - cur < 0.7 and i < steps:
            continue
        cur = want
        page.mouse.move(x0 + cur, y0 + base_y + random.uniform(-2.0, 2.0))
        if i == pause_at:
            page.wait_for_timeout(random.randint(120, 260))
        else:
            page.wait_for_timeout(int(duration / steps * random.uniform(0.6, 1.5)) + 5)

    # 收尾微调，模拟手指找位置
    for _ in range(random.randint(1, 3)):
        cur += random.uniform(-0.9, 1.1)
        page.mouse.move(x0 + cur, y0 + base_y + random.uniform(-1, 1))
        page.wait_for_timeout(random.randint(35, 80))

    page.mouse.move(x0 + distance, y0 + base_y)
    page.wait_for_timeout(random.randint(60, 130))
    page.mouse.up()


# ---------------------------------------------------------------- 对外接口

def solve(page, attempts=4, log=None):
    """
    处理弹出的滑块。成功返回 True。
    没弹出滑块也返回 True（说明不需要验证）。
    """
    say = log or (lambda m: None)
    try:
        return _solve(page, attempts, say)
    except Exception as e:
        msg = str(e)
        low = msg.lower()
        if "destroyed" in low or "navigat" in low or "has been closed" in low:
            say("页面已跳转，视为滑块通过")
            return True
        say("滑块处理出错：%s" % msg)
        return False


def _solve(page, attempts, say):
    for i in range(attempts):
        if page.locator("#sliderDiv").count() == 0:
            return True

        # 等图片就绪
        for _ in range(20):
            ok = page.evaluate("""() => {
                const b = document.querySelector('#slider-img1');
                const s = document.querySelector('#slider-img2');
                return !!(b && s && b.src && b.src.length > 200 && b.naturalWidth > 0 && s.naturalWidth > 0);
            }""")
            if ok:
                break
            page.wait_for_timeout(300)
        else:
            say("滑块图片一直没加载出来")
            return False

        info = page.evaluate("""() => {
            const b = document.querySelector('#slider-img1');
            const s = document.querySelector('#slider-img2');
            const d = document.querySelector('#sliderDiv');
            return {big: b.src, small: s.src, divW: d ? d.offsetWidth : 280};
        }""")

        try:
            found = find_gap(info["big"], info["small"], info["divW"])
        except Exception as e:
            say("缺口识别出错：%s" % e)
            found = None

        if not found:
            say("这轮识别不出缺口，换一张")
            _refresh(page)
            continue

        distance, gap_x = found
        if not (4 < distance < 233):
            say("缺口位置异常（%d），换一张" % gap_x)
            _refresh(page)
            continue

        say("识别到缺口 x=%d，拖动 %.1f 像素" % (gap_x, distance))
        _drag(page, distance)
        page.wait_for_timeout(1300)

        try:
            state = page.evaluate("""() => {
                const c = document.querySelector('.sliderContainer');
                const bj = document.querySelector('#bjGray');
                return {cls: c ? c.className : '', hidden: bj ? (bj.style.display === 'none') : false};
            }""")
        except Exception:
            # 页面已经跳走了 —— 说明验证通过并自动提交了表单
            say("滑块验证通过（页面已跳转）")
            return True

        if "success" in state["cls"] or state["hidden"]:
            say("滑块验证通过")
            return True

        say("第 %d 次没通过，换图重试" % (i + 1))
        _refresh(page)
        page.wait_for_timeout(1200)

    return False


def _refresh(page):
    """点刷新图标换一张图"""
    try:
        page.evaluate("""() => {
            const r = document.querySelector('#sliderDiv .refreshIcon');
            if (r) r.click();
        }""")
    except Exception:
        pass
