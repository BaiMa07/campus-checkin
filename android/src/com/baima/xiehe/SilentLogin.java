package com.baima.xiehe;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * 后台静默重登。
 *
 * 定时任务里会话过期时，不能靠拉起登录页（Android 10 以后后台不允许 startActivity），
 * 所以这里在一个带 Looper 的后台线程里建一个不显示的 WebView，加载学校登录页，
 * 注入 assets/autologin.js 那套脚本自动填表、自动过滑块，拿到 Cookie 再校验一次接口。
 * 整个过程不弹界面，也不需要用户点。
 */
public class SilentLogin {

    public static class Result {
        public boolean ok;
        public String reason = "";
        public String cookie = "";
    }

    private static String jsCache;

    /**
     * 同步执行，必须在后台线程调用。
     * 成功时返回新的 Cookie 字符串，失败返回原因。
     */
    public static Result relogin(final Context ctx, final Account a, int timeoutSeconds) {
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
        final HandlerThread thread = new HandlerThread("silent-login");
        thread.start();
        final Handler h = new Handler(thread.getLooper());
        final WebView[] holder = new WebView[1];
        final boolean[] injected = new boolean[]{false};
        final String[] fail = new String[]{""};

        h.post(new Runnable() {
            public void run() {
                try {
                    WebView w = new WebView(app);
                    holder[0] = w;
                    WebSettings s = w.getSettings();
                    s.setJavaScriptEnabled(true);
                    s.setDomStorageEnabled(true);
                    s.setDatabaseEnabled(true);
                    s.setLoadsImagesAutomatically(true);
                    s.setUserAgentString(KqApi.DEFAULT_UA);
                    w.setWebViewClient(new WebViewClient() {
                        @Override
                        public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                            if (url != null && url.contains("ids.sdxiehe.edu.cn")) injected[0] = false;
                        }

                        @Override
                        public void onPageFinished(WebView view, String url) {
                            if (url == null || !url.contains("ids.sdxiehe.edu.cn")) return;
                            if (injected[0]) return;
                            injected[0] = true;
                            String js = jsText(app);
                            if (js.length() == 0) {
                                fail[0] = "登录脚本读取失败";
                                return;
                            }
                            try {
                                view.evaluateJavascript(js + "\nwindow.__autoLogin("
                                        + JSONObject.quote(user) + ","
                                        + JSONObject.quote(pwd) + ");", null);
                                Logs.add("后台重登：已注入自动登录脚本");
                            } catch (Throwable t) {
                                fail[0] = "注入脚本失败：" + t;
                            }
                        }
                    });
                    w.loadUrl(KqApi.SERVICE);
                } catch (Throwable t) {
                    fail[0] = "WebView 初始化失败：" + t;
                    Logs.add("后台重登：" + fail[0]);
                }
            }
        });

        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                res.reason = "被中断";
                break;
            }
            String ck = cookies();
            if (ck.length() < 50) continue;
            KqApi.Resp r = KqApi.getKqInfo(ck, KqApi.DEFAULT_UA);
            if (r.state == KqApi.State.OK) {
                res.ok = true;
                res.cookie = ck;
                break;
            }
        }

        if (!res.ok) {
            if (fail[0].length() > 0) res.reason = fail[0];
            else if (res.reason.length() == 0) res.reason = "超时 " + timeoutSeconds + " 秒";
        }

        // WebView 只能在它自己的线程里销毁
        h.post(new Runnable() {
            public void run() {
                WebView w = holder[0];
                if (w != null) {
                    try {
                        w.stopLoading();
                        w.loadUrl("about:blank");
                        w.destroy();
                    } catch (Throwable ignored) { }
                }
            }
        });
        thread.quitSafely();
        return res;
    }

    private static String cookies() {
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
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
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
