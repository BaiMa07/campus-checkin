# -*- coding: utf-8 -*-
"""
一键构建 APK（不依赖 Gradle / AndroidX）
流程：下载工具链 → aapt2 编译资源 → javac → d8 → 塞 dex → zipalign → apksigner 签名 → 自检

用法：  py -3 build.py            （双击 build.bat 等价）
        py -3 build.py clean      （清掉 build/ 中间产物）
"""

import glob
import os
import shutil
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.abspath(__file__))
TOOLS = os.path.join(ROOT, "tools")
BUILD = os.path.join(ROOT, "build")
DIST = os.path.join(ROOT, "dist")
GEN = os.path.join(BUILD, "gen")
CLASSES = os.path.join(BUILD, "classes")
DEX = os.path.join(BUILD, "dex")

PKG = "com.baima.xiehe"
APP_NAME = "XieHeQianDao-2.2.apk"
MIN_SDK = "24"
TARGET_SDK = "33"
VERSION_CODE = "13"
VERSION_NAME = "2.2"

DL = {
    "build-tools_r34.zip": "https://dl.google.com/android/repository/build-tools_r34-windows.zip",
    "platform-34.zip": "https://dl.google.com/android/repository/platform-34-ext7_r03.zip",
}

JDK_CANDIDATES = [
    r"D:\Xun.LEI.YUN.PAN\Java17,,8\java17",
    r"C:\Program Files\Zulu\zulu-25",
    r"C:\Program Files\Eclipse Adoptium\jdk-17",
    r"D:\IntelliJ IDEA 2025.2.5\jbr",
]


def say(msg):
    print("\n=== %s" % msg, flush=True)


def run(cmd, cwd=ROOT):
    print("  $ %s" % " ".join('"%s"' % c if " " in c else c for c in cmd), flush=True)
    p = subprocess.run(cmd, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    out = p.stdout.decode("utf-8", "replace").strip()
    if out:
        print(out, flush=True)
    if p.returncode != 0:
        raise SystemExit("命令失败（%d）：%s" % (p.returncode, cmd[0]))
    return out


def find_jdk():
    for d in JDK_CANDIDATES:
        if os.path.isfile(os.path.join(d, "bin", "javac.exe")):
            return d
    jh = os.environ.get("JAVA_HOME", "")
    if jh and os.path.isfile(os.path.join(jh, "bin", "javac.exe")):
        return jh
    raise SystemExit("没找到 JDK 17，请改 build.py 里的 JDK_CANDIDATES")


def download():
    os.makedirs(TOOLS, exist_ok=True)
    for name, url in DL.items():
        dst = os.path.join(TOOLS, name)
        if os.path.exists(dst) and os.path.getsize(dst) > 10 * 1024 * 1024:
            print("  已存在 %s（%.0f MB）" % (name, os.path.getsize(dst) / 1048576.0))
            continue
        print("  下载 %s ..." % name, flush=True)
        env = dict(os.environ, NO_PROXY="*", no_proxy="*")
        p = subprocess.run(["curl", "-L", "--retry", "3", "-o", dst, url], env=env)
        if p.returncode != 0:
            raise SystemExit("下载失败：" + url)


def unzip_to(zip_path, dest):
    if os.path.isdir(dest) and os.listdir(dest):
        return dest
    tmp = dest + "_tmp"
    if os.path.isdir(tmp):
        shutil.rmtree(tmp)
    os.makedirs(tmp)
    with zipfile.ZipFile(zip_path) as z:
        z.extractall(tmp)
    # 压缩包里通常还有一个顶层目录，剥掉
    entries = os.listdir(tmp)
    src = tmp
    if len(entries) == 1 and os.path.isdir(os.path.join(tmp, entries[0])):
        src = os.path.join(tmp, entries[0])
    shutil.move(src, dest)
    if os.path.isdir(tmp):
        shutil.rmtree(tmp, ignore_errors=True)
    return dest


def prepare_tools():
    bt = os.path.join(TOOLS, "build-tools")
    pf = os.path.join(TOOLS, "platform")
    if not os.path.isfile(os.path.join(bt, "aapt2.exe")):
        unzip_to(os.path.join(TOOLS, "build-tools_r34.zip"), bt)
    if not os.path.isfile(os.path.join(pf, "android.jar")):
        unzip_to(os.path.join(TOOLS, "platform-34.zip"), pf)
    for p in (os.path.join(bt, "aapt2.exe"), os.path.join(bt, "zipalign.exe"),
              os.path.join(bt, "lib", "apksigner.jar"), os.path.join(bt, "lib", "d8.jar"),
              os.path.join(pf, "android.jar")):
        if not os.path.exists(p):
            raise SystemExit("工具链缺失：" + p)
    return bt, pf


def clean():
    for d in (BUILD, DIST):
        if os.path.isdir(d):
            shutil.rmtree(d, ignore_errors=True)
    print("已清理 build/ 和 dist/")


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "clean":
        clean()
        return

    jdk = find_jdk()
    java = os.path.join(jdk, "bin", "java.exe")
    javac = os.path.join(jdk, "bin", "javac.exe")
    keytool = os.path.join(jdk, "bin", "keytool.exe")
    print("JDK: %s" % jdk)

    say("1/8 准备工具链")
    download()
    bt, pf = prepare_tools()
    aapt2 = os.path.join(bt, "aapt2.exe")
    zipalign = os.path.join(bt, "zipalign.exe")
    apksigner = os.path.join(bt, "lib", "apksigner.jar")
    android_jar = os.path.join(pf, "android.jar")
    print("  build-tools: %s" % bt)
    print("  platform   : %s" % pf)

    os.makedirs(BUILD, exist_ok=True)
    for name in ("res.zip", "app-base.apk", "app-dex.apk", "app-aligned.apk"):
        f = os.path.join(BUILD, name)
        if os.path.exists(f):
            os.remove(f)

    say("2/8 编译资源（aapt2 compile）")
    run([aapt2, "compile", "--dir", "res", "-o", os.path.join(BUILD, "res.zip")])

    say("3/8 链接资源包（aapt2 link）")
    os.makedirs(GEN, exist_ok=True)
    run([aapt2, "link",
         "-o", os.path.join(BUILD, "app-base.apk"),
         "-I", android_jar,
         "--manifest", "AndroidManifest.xml",
         "-A", "assets",
         os.path.join(BUILD, "res.zip"),
         "--java", GEN,
         "--min-sdk-version", MIN_SDK,
         "--target-sdk-version", TARGET_SDK,
         "--version-code", VERSION_CODE,
         "--version-name", VERSION_NAME,
         "--auto-add-overlay"])

    say("4/8 编译 Java（javac）")
    os.makedirs(CLASSES, exist_ok=True)
    sources = glob.glob(os.path.join(ROOT, "src", "**", "*.java"), recursive=True)
    sources += glob.glob(os.path.join(GEN, "**", "R.java"), recursive=True)
    if not sources:
        raise SystemExit("没找到源码")
    run([javac, "-encoding", "UTF-8", "-source", "8", "-target", "8",
         "-bootclasspath", android_jar, "-nowarn",
         "-d", CLASSES] + sources)

    say("5/8 转 dex（d8）")
    os.makedirs(DEX, exist_ok=True)
    class_files = glob.glob(os.path.join(CLASSES, "**", "*.class"), recursive=True)
    run([java, "-cp", os.path.join(bt, "lib", "*"), "com.android.tools.r8.D8",
         "--release", "--lib", android_jar, "--min-api", MIN_SDK,
         "--output", DEX] + class_files)
    dex_file = os.path.join(DEX, "classes.dex")
    if not os.path.exists(dex_file):
        raise SystemExit("d8 没有产出 classes.dex")

    say("6/8 打包 dex（append 模式，不动 resources.arsc）")
    apk_dex = os.path.join(BUILD, "app-dex.apk")
    shutil.copy(os.path.join(BUILD, "app-base.apk"), apk_dex)
    with zipfile.ZipFile(apk_dex, "a", zipfile.ZIP_DEFLATED) as z:
        z.write(dex_file, "classes.dex")
    print("  已写入 classes.dex（%.1f KB）" % (os.path.getsize(dex_file) / 1024.0))

    say("7/8 对齐 + 签名")
    aligned = os.path.join(BUILD, "app-aligned.apk")
    run([zipalign, "-f", "-p", "4", apk_dex, aligned])

    ks = os.path.join(ROOT, "keystore", "debug.keystore")
    if not os.path.exists(ks):
        os.makedirs(os.path.dirname(ks), exist_ok=True)
        run([keytool, "-genkeypair", "-keystore", ks, "-storetype", "PKCS12",
             "-storepass", "android", "-keypass", "android",
             "-alias", "androiddebugkey", "-keyalg", "RSA", "-keysize", "2048",
             "-validity", "10950", "-dname", "CN=Android Debug,O=Android,C=US"])

    os.makedirs(DIST, exist_ok=True)
    out_apk = os.path.join(DIST, APP_NAME)
    if os.path.exists(out_apk):
        os.remove(out_apk)
    run([java, "-jar", apksigner, "sign",
         "--ks", ks, "--ks-pass", "pass:android", "--key-pass", "pass:android",
         "--ks-key-alias", "androiddebugkey", "--min-sdk-version", MIN_SDK,
         "--v1-signing-enabled", "true", "--v2-signing-enabled", "true",
         "--v3-signing-enabled", "true",
         "--out", out_apk, aligned])

    say("8/8 自检")
    run([java, "-jar", apksigner, "verify", "--verbose", out_apk])
    badging = run([aapt2, "dump", "badging", out_apk])
    for key in ("package:", "sdkVersion", "targetSdkVersion", "launchable-activity"):
        for line in badging.splitlines():
            if key in line:
                print("  " + line.strip())
                break

    size = os.path.getsize(out_apk) / 1048576.0
    print("\n构建成功：%s（%.2f MB）" % (out_apk, size))


if __name__ == "__main__":
    main()
