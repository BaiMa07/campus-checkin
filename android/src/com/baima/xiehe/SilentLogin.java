package com.baima.xiehe;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.WindowManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 后台静默重登。
 *
 * 定时任务里会话过期时不能拉起登录页（Android 10 以后后台禁止 startActivity），
 * 所以这里建一个不显示的 WebView 放在主线程上跑登录流程：自动填表、自动过滑块。
 * 调用方在后台线程里等，WebView 的创建、注入、Cookie 读取都切回主线程执行，
 * 避免跨线程操作 WebView 造成互相等待。
 */
public class SilentLogin {

    public static class Result {
        public boolean ok;
        public String reason = "";
        public String cookie = "";
    }

    private static String jsCache;

    /** 同步执行，必须在后台线程调用（内部会把 WebView 操作切到主线程） */
    public static Result relogin(Context ctx, Account a, int timeoutSeconds) {
        final Result res = new Result();
        final String pwd = Secure.decrypt(a.pwdEnc);
        final String user = a.studentNo == null ? "" : a.studentNo;
        if (pwd.length() == 0) {
            res.reason = "没有保存密码";
            return res;
        }
        if (user.length() == 0) {
            res.reason = "没有学号";
            return res;
        }

        final Context app = ctx.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        final android.os.HandlerThread webThread = new android.os.HandlerThread("silent-web");
        webThread.start();
        final Handler webHandler = new Handler(webThread.getLooper());
        final WebView[] holder = new WebView[1];
        final AtomicBoolean injected = new AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicInteger rounds =
                new java.util.concurrent.atomic.AtomicInteger(0);
        final AtomicReference<String> fail = new AtomicReference<String>("");
        final CountDownLatch ready = new CountDownLatch(1);

        webHandler.post(new Runnable() {
            public void run() {
                try {
                    WebView w = new WebView(app);
                    holder[0] = w;
                    WebSettings s = w.getSettings();
                    s.setJavaScriptEnabled(true);
                    s.setDomStorageEnabled(true);
                    s.setDatabaseEnabled(true);
                    s.setUserAgentString(KqApi.DEFAULT_UA);
                    w.setWebViewClient(new WebViewClient() {
                        @Override
                        public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                            Log.i("SilentLogin", "start " + url);
                        }

                        @Override
                        public void onReceivedError(WebView view, android.webkit.WebResourceRequest req,
                                                    android.webkit.WebResourceError err) {
                            Log.i("SilentLogin", "error " + req.getUrl() + " " + err.getDescription());
                        }

                        public void onPageFinished(final WebView view, final String url) {
                            Log.i("SilentLogin", "finish " + url);
                            if (url == null || !url.contains("ids.sdxiehe.edu.cn")) return;
                            final String js = jsText(app);
                            if (js.length() == 0) {
                                fail.set("登录脚本读取失败");
                                return;
                            }
                            if (rounds.incrementAndGet() > 3) {
                                fail.set("登录页反复重载，自动登录没成功");
                                return;
                            }
                            // 注意：这里不能用 view.post()，WebView 没挂到窗口上时它不会执行
                            webHandler.post(new Runnable() {
                                public void run() {
                                    Log.i("SilentLogin", "inject on " + url);
                                    try {
                                        view.evaluateJavascript(js + "\nwindow.__autoLogin("
                                                + JSONObject.quote(user) + ","
                                                + JSONObject.quote(pwd) + ");", null);
                                        if (rounds.get() <= 1) {
                                            Logs.add("后台重登：已注入自动登录脚本");
                                        }
                                    } catch (Throwable t) {
                                        fail.set("注入脚本失败：" + t);
                                    }
                                }
                            });
                        }
                    });
                    // 先清掉 WebView 里的旧 Cookie，否则会直接跳过登录页
                    try {
                        CookieManager cm = CookieManager.getInstance();
                        cm.removeAllCookies(null);
                        cm.flush();
                        Log.i("SilentLogin", "cookies cleared");
                    } catch (Throwable ignored) { }

                    String loginUrl = "https://ids.sdxiehe.edu.cn/authserver/login?service="
                            + java.net.URLEncoder.encode(KqApi.SERVICE, "UTF-8");
                    // 关键：不挂到窗口上的 WebView 不会执行 JS；这里挂一个 360x640、1% 不透明的悬浮窗，
    // 尺寸要给够，滑块要拿元素尺寸算拖动距离，1x1 的话算不出来
                    WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
                    WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                            360, 640,
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                                    : WindowManager.LayoutParams.TYPE_PHONE,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                            PixelFormat.TRANSLUCENT);
                    lp.gravity = Gravity.TOP | Gravity.START;
                    lp.x = 0;
                    lp.y = 0;
                    lp.alpha = 0.01f;   // 1% 透明度，基本看不见
                    try {
                        wm.addView(w, lp);
                        Log.i("SilentLogin", "attached to overlay");
                    } catch (Throwable t) {
                        Log.i("SilentLogin", "overlay attach failed: " + t);
                        fail.set("没有悬浮窗权限，无法后台登录");
                    }

                    w.loadUrl(loginUrl);
                    Log.i("SilentLogin", "webview created, loading " + loginUrl);
                    // 页面不可见时 WebView 会节流定时器，这行让脚本照常跑
                    try { w.resumeTimers(); } catch (Throwable ignored) { }
                    ready.countDown();
                } catch (Throwable t) {
                    fail.set("WebView 初始化失败：" + t);
                    Logs.add("后台重登：" + fail.get());
                    ready.countDown();
                }
            }
        });

        try {
            boolean ok = ready.await(15, TimeUnit.SECONDS);
            Log.i("SilentLogin", "ready=" + ok);
            Logs.add("后台重登：WebView 就绪=" + ok);
        } catch (InterruptedException ignored) { }

        int tick = 0;
        String lastCk = "";
        long lastCheckAt = 0;

        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                res.reason = "被中断";
                break;
            }
            String f0 = fail.get();
            if (f0 != null && f0.length() > 0) break;
            String ck = cookiesOn(webHandler, 3);
            tick++;
            if (tick % 5 == 0) {
                Logs.add("后台重登：等待中，Cookie 长度 " + ck.length());
            }
            if (ck.length() < 50) continue;
            // Cookie 没变化就不用反复验：刚登录就连着打接口，最容易被判"会话异常"
            if (ck.equals(lastCk) && System.currentTimeMillis() - lastCheckAt < 6000) continue;
            lastCk = ck;
            lastCheckAt = System.currentTimeMillis();
            KqApi.Resp r = KqApi.getKqInfo(ck, KqApi.DEFAULT_UA);
            if (r.state == KqApi.State.OK) {
                res.ok = true;
                res.cookie = ck;
                break;
            }
            if (r.state == KqApi.State.BLOCKED) {
                res.reason = r.msg;
                break;
            }
        }

        if (!res.ok) {
            String f = fail.get();
            if (f != null && f.length() > 0) res.reason = f;
            else if (res.reason.length() == 0) res.reason = "超时 " + timeoutSeconds + " 秒";
        }

        // 销毁回到它自己的线程
        webHandler.post(new Runnable() {
            public void run() {
                WebView w = holder[0];
                if (w != null) {
                    try {
                        WindowManager wm2 = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
                        wm2.removeView(w);
                    } catch (Throwable ignored) { }
                    try {
                        w.stopLoading();
                        w.loadUrl("about:blank");
                        w.destroy();
                    } catch (Throwable ignored) { }
                }
            }
        });
        try { Thread.sleep(300); } catch (InterruptedException ignored) { }
        webThread.quitSafely();
        return res;
    }

    /** Cookie 在 WebView 自己的线程上读，同步等它返回 */
    private static String cookiesOn(Handler h, int timeoutSec) {
        final String[] out = new String[]{""};
        final CountDownLatch latch = new CountDownLatch(1);
        h.post(new Runnable() {
            public void run() {
                try {
                    CookieManager cm = CookieManager.getInstance();
                    cm.flush();
                    StringBuilder sb = new StringBuilder();
                    String a = cm.getCookie(KqApi.SERVICE);
                    String b = cm.getCookie("https://xg.sdxiehe.edu.cn");
                    if (a != null) sb.append(a);
                    if (b != null && !b.equals(a)) {
                        if (sb.length() > 0) sb.append("; ");
                        sb.append(b);
                    }
                    out[0] = sb.toString();
                } catch (Throwable ignored) {
                } finally {
                    latch.countDown();
                }
            }
        });
        try {
            latch.await(timeoutSec, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) { }
        return out[0];
    }

    private static String jsText(Context c) {
        if (jsCache != null) return jsCache;
        InputStream in = null;
        try {
            in = c.getAssets().open("autologin.js");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            jsCache = new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            Logs.add("读取自动登录脚本失败：" + t);
            jsCache = "";
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) { }
        }
        return jsCache;
    }
}
