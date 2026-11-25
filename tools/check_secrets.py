# -*- coding: utf-8 -*-
"""
提交前扫描：拦住学号、手机号、经纬度、Cookie、明文口令这类不该进仓库的东西。

由 .git/hooks/pre-commit 调用，检查的是**暂存区内容**（不是工作区），
命中就把提交挡下来，并打印文件、行号和命中内容。

跳过：二进制文件、APK、keystore、构建产物、日志。
误报豁免：在那一行末尾写  # nosecret  即可放行。
"""

from __future__ import print_function

import os
import re
import subprocess
import sys

SKIP_EXT = (
    ".apk", ".idsig", ".keystore", ".jar", ".zip", ".png", ".jpg", ".jpeg",
    ".gif", ".ico", ".woff", ".woff2", ".ttf", ".so", ".dex", ".class",
)
SKIP_DIR = ("android/build/", "android/tools/", "build/", "dist/", ".workbuddy/")

# 已脱敏的占位坐标，允许出现
ALLOW_VALUES = {"36.740000", "117.278000"}

RULES = [
    ("学号（12~15 位，20 开头）", re.compile(r"(?<!\d)20\d{10,13}(?!\d)")),
    ("手机号", re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)")),
    ("楼栋号（数字+号公寓/号楼）", re.compile(r"\d{1,3}\s*号(?:公寓|宿舍楼|宿舍|号楼)")),
    ("经纬度（4 位以上小数）", re.compile(r"(?<!\d)(?:3\d|4\d|1[0-2]\d)\.\d{4,}(?!\d)")),
    ("Cookie / 会话串", re.compile(r"(?:route|JSESSIONID|SESSION|PHPSESSID|token)\s*=\s*[A-Za-z0-9%\-_\.]{8,}",
                                    re.I)),
    ("明文口令", re.compile(r"""(?:password|passwd|pwd|密码)\s*[:=：]\s*["'][^"'\s]{6,}["']""", re.I)),
]


def git(*args):
    p = subprocess.Popen(["git"] + list(args), stdout=subprocess.PIPE,
                         stderr=subprocess.PIPE)
    out, _ = p.communicate()
    return out


def staged_files():
    raw = git("diff", "--cached", "--name-only", "--diff-filter=ACM", "-z")
    return [f for f in raw.decode("utf-8", "replace").split("\0") if f]


def skip(path):
    low = path.lower()
    if low.endswith(SKIP_EXT):
        return True
    return any(low.startswith(d) for d in SKIP_DIR)


def main():
    files = [f for f in staged_files() if not skip(f)]
    problems = []

    for path in files:
        blob = git("show", ":" + path)
        if b"\0" in blob[:4096]:
            continue
        text = blob.decode("utf-8", "replace")
        for i, line in enumerate(text.splitlines(), 1):
            if "# nosecret" in line or "nosecret" in line:
                continue
            for label, rx in RULES:
                m = rx.search(line)
                if not m:
                    continue
                hit = m.group(0)
                if hit in ALLOW_VALUES:
                    continue
                # 口令规则里 hit 含整段赋值，截短一点方便看
                if len(hit) > 60:
                    hit = hit[:57] + "..."
                problems.append("%s:%d  [%s]  %s" % (path, i, label, hit))

    if not problems:
        return 0

    print("")
    print("提交被拦下：发现可能不该公开的内容")
    print("-" * 56)
    for p in problems[:20]:
        print("  " + p)
    if len(problems) > 20:
        print("  … 另有 %d 处" % (len(problems) - 20))
    print("-" * 56)
    print("确认无害的话，在那一行末尾加  # nosecret  再提交；")
    print("确实要跳过检查：git commit --no-verify（不建议）")
    print("")
    return 1


if __name__ == "__main__":
    sys.exit(main())
