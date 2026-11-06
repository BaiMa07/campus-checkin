package com.baima.xiehe;

import android.content.Context;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewDatabase;

import java.io.File;
import java.util.Locale;

/**
 * 存储占用统计与清理。
 *
 * App 占的空间主要不是账号数据，而是登录用的网页内核留下的东西：
 * app_webview 里有一个 4MB 的 BrowserMetrics-spare.pma，还有 Cache、Code Cache、
 * Service Worker、Local Storage 这些目录，每登一次都会往里写。
 * 系统设置里看到的"数据和缓存"基本就是这些，所以清理必须连 app_webview 一起清，
 * 只删 cache/ 是没用的。
 */
public class Storage {

    private static final String[] JUNK_DIRS = {
            "Cache", "Code Cache", "GPUCache", "DawnCache", "DawnGraphiteCache",
            "GrShaderCache", "ShaderCache", "Service Worker", "Session Storage",
            "Local Storage", "IndexedDB", "blob_storage", "Crashpad", "databases"
    };

    /** 递归统计目录大小 */
    public static long sizeOf(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long total = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) total += sizeOf(k);
        return total;
    }

    public static long cacheBytes(Context c) {
        return sizeOf(c.getCacheDir()) + sizeOf(c.getExternalCacheDir());
    }

    public static File webDir(Context c) {
        File f = new File(c.getApplicationInfo().dataDir, "app_webview");
        return f.exists() ? f : null;
    }

    public static long webBytes(Context c) {
        return sizeOf(webDir(c));
    }

    public static long logBytes() {
        return Logs.sizeBytes();
    }

    /** 内存 + 数据库这些真正属于账号的数据，用来告诉用户"清缓存不会动它们" */
    public static long dataBytes(Context c) {
        return sizeOf(new File(c.getApplicationInfo().dataDir, "databases"))
                + sizeOf(new File(c.getApplicationInfo().dataDir, "shared_prefs"))
                + sizeOf(c.getFilesDir());
    }

    public static String report(Context c) {
        return "缓存 " + human(cacheBytes(c))
                + " · 网页数据 " + human(webBytes(c))
                + " · 日志 " + human(logBytes());
    }

    /**
     * 清缓存：网页缓存 + 网页存储 + 临时文件。账号、密码、签到记录都不动。
     * 必须在主线程调用（WebView 只能在有 Looper 的线程创建）。
     */
    public static long clear(Context c) {
        long before = cacheBytes(c) + webBytes(c);

        // 让 WebView 自己把内存里的缓存放掉
        try {
            WebView w = new WebView(c);
            w.clearCache(true);
            w.clearFormData();
            w.clearHistory();
            w.destroy();
        } catch (Throwable ignored) { }

        try {
            WebStorage.getInstance().deleteAllData();
        } catch (Throwable ignored) { }
        try {
            WebViewDatabase.getInstance(c).clearHttpAuthUsernamePassword();
            WebViewDatabase.getInstance(c).clearFormData();
        } catch (Throwable ignored) { }
        try {
            // Cookie 也顺手清掉：签到用的是数据库里存的 Cookie 字符串，不依赖这个
            CookieManager cm = CookieManager.getInstance();
            cm.removeAllCookies(null);
            cm.flush();
        } catch (Throwable ignored) { }

        wipeChildren(c.getCacheDir());
        wipeChildren(c.getExternalCacheDir());

        File web = webDir(c);
        if (web != null) sweep(web, 0);

        long after = cacheBytes(c) + webBytes(c);
        long freed = before - after;
        Logs.add("已清理缓存与网页数据，释放 " + human(Math.max(0, freed)));
        return Math.max(0, freed);
    }

    /** 只删我们自己 App 目录下的内容 */
    private static void wipeChildren(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) deleteTree(k);
    }

    /** 在 app_webview 里按名字找垃圾目录/文件删掉，最多往下找三层 */
    private static void sweep(File dir, int depth) {
        if (dir == null || depth > 3) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            String name = k.getName();
            boolean junk = false;
            if (k.isDirectory()) {
                for (String n : JUNK_DIRS) {
                    if (n.equalsIgnoreCase(name)) { junk = true; break; }
                }
            } else if (name.startsWith("BrowserMetrics") || name.endsWith(".pma")) {
                junk = true;
            }
            if (junk) {
                deleteTree(k);
            } else if (k.isDirectory()) {
                sweep(k, depth + 1);
            }
        }
    }

    private static boolean deleteTree(File f) {
        if (f == null || !f.exists()) return true;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
        }
        return f.delete();
    }

    /** 开应用时顺手收拾一下：日志超了就截断，网页数据超过 12MB 就清一次 */
    public static void autoTrim(Context c) {
        try {
            if (Logs.sizeBytes() > 128 * 1024) Logs.trim();
            File web = webDir(c);
            if (web != null && sizeOf(web) > 12L * 1024 * 1024) {
                Logs.add("网页数据已到 " + human(sizeOf(web)) + "，自动清理一次");
                clear(c);
            }
        } catch (Throwable ignored) { }
    }

    public static String human(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.US, "%.1f KB", b / 1024.0);
        return String.format(Locale.US, "%.1f MB", b / 1048576.0);
    }
}
