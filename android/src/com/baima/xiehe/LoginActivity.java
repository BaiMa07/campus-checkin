package com.baima.xiehe;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WebView 登录：学校强制滑块验证码，只能让用户手动过一次。
 * Cookie 主要靠 shouldInterceptRequest 抓真实请求头（含 HttpOnly 的 JSESSIONID），
 * CookieManager.getCookie 只做补充（它拿不到 HttpOnly）。
 *
 * 两种模式：
 *   accountId = 0  → 新增账号（登录成功后写一条数据库记录）
 *   accountId > 0  → 给已有账号续期 Cookie（会话过期时用）
 */
public class LoginActivity extends Activity {

    public static final String EXTRA_ACCOUNT_ID = "account_id";
    public static final String EXTRA_AUTO = "auto";        // 带上保存的密码自动登录
    public static final String EXTRA_USER = "user";
    public static final String EXTRA_PWD = "pwd";
    public static final String EXTRA_BATCH = "batch";   // 批量重登中的一环，登完不自动签到

    /** 后台/其他页面调用：直接用存好的密码开一个自动登录页 */
    public static boolean startAuto(android.content.Context c, Account a) {
        if (a == null) return false;
        String pwd = Secure.decrypt(a.pwdEnc);
        if (pwd.length() == 0) return false;
        String no = a.studentNo == null ? "" : a.studentNo;
        if (no.length() == 0) return false;
        Intent i = new Intent(c, LoginActivity.class);
        i.putExtra(EXTRA_ACCOUNT_ID, a.id);
        i.putExtra(EXTRA_USER, no);
        i.putExtra(EXTRA_PWD, pwd);
        i.putExtra(EXTRA_AUTO, true);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            c.startActivity(i);
            return true;
        } catch (Throwable t) {
            Logs.add("打不开自动登录页：" + t);
            return false;
        }
    }

    private String autoUser = "";
    private String autoPwd = "";
    private boolean autoLogin;          // 本次是否是自动重登
    private boolean batchMode;          // 批量重登里的一环
    private boolean autoStarted;
    private String pendingUser = "";    // 新账号登录时先暂存，入库后写进数据库
    private String pendingPwd = "";
    private static String jsCache;

    private WebView web;
    private TextView tvHint;
    private final Map<String, String> jar = new LinkedHashMap<String, String>();
    private boolean verifying;
    private long accountId;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_login);
        Logs.init(this);
        accountId = getIntent().getLongExtra(EXTRA_ACCOUNT_ID, 0L);
        autoUser = nz(getIntent().getStringExtra(EXTRA_USER));
        autoPwd = nz(getIntent().getStringExtra(EXTRA_PWD));
        autoLogin = getIntent().getBooleanExtra(EXTRA_AUTO, false);
        batchMode = getIntent().getBooleanExtra(EXTRA_BATCH, false);
        if (autoLogin && accountId > 0) {
            Account a = AccountStore.get(this).get(accountId);
            if (a != null) {
                if (autoUser.length() == 0) autoUser = nz(a.studentNo);
                if (autoPwd.length() == 0) autoPwd = Secure.decrypt(a.pwdEnc);
            }
        }
        if (autoUser.length() == 0 || autoPwd.length() == 0) autoLogin = false;

        tvHint = (TextView) findViewById(R.id.tvHint);
        if (autoLogin) {
            tvHint.setText("正在自动登录「" + autoUser + "」\n自动填表、自动过滑块，稍等 10 秒左右");
        } else if (accountId > 0) {
            Account a = AccountStore.get(this).get(accountId);
            tvHint.setText("给账号「" + (a == null ? "?" : a.title()) + "」重新登录\n"
                    + getString(R.string.login_hint));
        }

        web = (WebView) findViewById(R.id.web);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setSupportZoom(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        String ua = WebSettings.getDefaultUserAgent(this);
        ws.setUserAgentString(ua);
        Prefs.get(this).setUa(ua);

        // 手动登录时，前端把用户输入的密码交给原生保存（下回就能自动登录）
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void saveCreds(String user, String pwd) {
                try {
                    if (user == null || pwd == null || user.length() == 0 || pwd.length() == 0) return;
                    AccountStore st = AccountStore.get(LoginActivity.this);
                    Account a = st.findAny(user, null);
                    if (a == null && accountId > 0) a = st.get(accountId);
                    if (a == null) {
                        pendingUser = user;
                        pendingPwd = pwd;
                        return;
                    }
                    if (pwd.equals(Secure.decrypt(a.pwdEnc))) return;
                    st.setPwd(a.id, Secure.encrypt(pwd));
                    Logs.add("已保存「" + a.title() + "」的密码，之后可自动登录");
                } catch (Throwable ignored) { }
            }
        }, "XieHeBridge");

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                try {
                    String host = req.getUrl().getHost();
                    String ck = req.getRequestHeaders().get("Cookie");
                    if (ck != null && host != null && host.endsWith("sdxiehe.edu.cn")) merge(ck);
                } catch (Throwable ignored) { }
                return null;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                Logs.add("页面：" + url);
                if (url != null && url.contains("ids.sdxiehe.edu.cn")) {
                    // 自动勾选「7天免登录」
                    v.evaluateJavascript(
                            "(function(){var n=0;document.querySelectorAll('input[type=checkbox]')"
                                    + ".forEach(function(c){if(!c.checked){c.click();n++;}});return n;})()", null);
                    inject(v);
                    return;
                }
                if (url != null && url.startsWith("https://xg.sdxiehe.edu.cn")) verify();
            }
        });

        ((Button) findViewById(R.id.btnCancel)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                setResult(RESULT_CANCELED);
                finish();
            }
        });

        if (accountId > 0) {
            web.post(new Runnable() {
                public void run() {
                    // 直接开 service_url，让服务端自己 302 到 CAS，service 参数天然正确
                    web.loadUrl(KqApi.SERVICE);
                }
            });
        } else {
            // 添加新账号：必须先清掉 WebView 里上一个账号的登录态，
            // 否则一打开就是已登录状态，会把同一个账号又存一遍（这就是重复账号的原因）
            clearCookiesThenLoad();
        }
    }

    /** 清空 WebView 的 Cookie 再打开登录页（数据库里的账号不受影响） */
    private void clearCookiesThenLoad() {
        final CookieManager cm = CookieManager.getInstance();
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        try {
            cm.removeAllCookies(new android.webkit.ValueCallback<Boolean>() {
                public void onReceiveValue(Boolean value) {
                    latch.countDown();
                }
            });
        } catch (Throwable t) {
            latch.countDown();
        }
        Logs.add("添加新账号：已清空网页登录态，准备重新登录");
        new Thread(new Runnable() {
            public void run() {
                try { latch.await(3, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { }
                try { cm.flush(); } catch (Throwable ignored) { }
                synchronized (jar) { jar.clear(); }
                runOnUiThread(new Runnable() {
                    public void run() {
                        web.loadUrl(KqApi.SERVICE);
                    }
                });
            }
        }).start();
    }

    /** 把自动登录脚本注入当前页面 */
    private void inject(WebView v) {
        String js = jsText();
        if (js.length() == 0) return;
        if (autoLogin && !autoStarted) {
            autoStarted = true;
            Logs.add("自动登录：" + autoUser);
            js = js + "\nwindow.__autoLogin(" + JSONObject.quote(autoUser) + ","
                    + JSONObject.quote(autoPwd) + ");";
        } else {
            js = js + "\nwindow.__watchCreds&&window.__watchCreds();";
        }
        try {
            v.evaluateJavascript(js, null);
        } catch (Throwable t) {
            Logs.add("注入脚本失败：" + t);
        }
    }

    private String jsText() {
        if (jsCache != null) return jsCache;
        InputStream is = null;
        try {
            is = getAssets().open("autologin.js");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            jsCache = new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            Logs.add("读取自动登录脚本失败：" + t);
            jsCache = "";
        } finally {
            if (is != null) try { is.close(); } catch (Throwable ignored) { }
        }
        return jsCache;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private void merge(String cookieHeader) {
        if (cookieHeader == null || cookieHeader.length() == 0) return;
        synchronized (jar) {
            for (String kv : cookieHeader.split(";")) {
                String s = kv.trim();
                int i = s.indexOf('=');
                if (i > 0) jar.put(s.substring(0, i), s.substring(i + 1));
            }
        }
    }

    private String cookieString() {
        StringBuilder sb = new StringBuilder();
        synchronized (jar) {
            for (Map.Entry<String, String> e : jar.entrySet()) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
        }
        return sb.toString();
    }

    private void verify() {
        if (verifying) return;
        verifying = true;
        new Thread(new Runnable() {
            public void run() {
                KqApi.Resp ok = null;
                KqApi.Resp blocked = null;
                String cookie = "";
                for (int i = 0; i < 10; i++) {
                    collectCookies();
                    cookie = cookieString();
                    KqApi.Resp r = KqApi.getKqInfo(cookie, Prefs.get(LoginActivity.this).ua());
                    if (r.state == KqApi.State.OK) {
                        ok = r;
                        break;
                    }
                    if (r.state == KqApi.State.BLOCKED) {
                        // 风控冻结：继续登只会越冻越久，立刻停手
                        blocked = r;
                        break;
                    }
                    if (i == 0) {
                        String names;
                        synchronized (jar) { names = jar.keySet().toString(); }
                        Logs.add("Cookie 长度 " + cookie.length() + " " + names + " → " + r.msg);
                    }
                    // 每 2 秒确认一次就够，登录成功后连着刷接口容易被判会话异常
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) { }
                }
                if (blocked != null) {
                    final long until = blocked.blockedUntil;
                    Account acc = accountId > 0 ? AccountStore.get(LoginActivity.this).get(accountId) : null;
                    if (acc != null) {
                        acc.blockedUntil = until;
                        acc.lastResult = "账号被冻结，" + Guard.untilText(until) + " 后恢复";
                        acc.lastOk = Account.STATE_FAIL;
                        AccountStore.get(LoginActivity.this).update(acc);
                    }
                    verifying = false;
                    Logs.add("登录被风控拦截：" + blocked.msg);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            Toast.makeText(LoginActivity.this,
                                    "账号被学校风控冻结，" + Guard.untilText(until)
                                            + " 后自动恢复，期间别再登录了",
                                    Toast.LENGTH_LONG).show();
                            setResult(RESULT_CANCELED);
                            finish();
                        }
                    });
                    return;
                }
                if (ok == null) {
                    Logs.add("Cookie 有了但接口还没通（长度 " + cookie.length() + "）");
                    verifying = false;
                    return;
                }

                final String ck = cookie;
                Logs.add("登录成功，Cookie 长度 " + ck.length());
                saveAccount(ck, ok.model);
                runOnUiThread(new Runnable() {
                    public void run() {
                        setResult(RESULT_OK);
                        if (autoLogin && !batchMode) {
                            // 自动重登成功 -> 直接接着签到，用户不用再点
                            try {
                                Intent it = new Intent(LoginActivity.this, SignService.class);
                                it.putExtra("force", true);
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    startForegroundService(it);
                                } else {
                                    startService(it);
                                }
                                Toast.makeText(LoginActivity.this, "已自动登录，开始签到",
                                        Toast.LENGTH_SHORT).show();
                            } catch (Throwable ignored) { }
                        }
                        finish();
                    }
                });
            }
        }).start();
    }

    /** 从 WebView 的 Cookie 仓库里尽量多捞几次（页面加载过程中 Cookie 是陆续下发的） */
    private void collectCookies() {
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.flush();
            merge(cm.getCookie(KqApi.SERVICE));
            merge(cm.getCookie("https://xg.sdxiehe.edu.cn"));
            merge(cm.getCookie("https://xg.sdxiehe.edu.cn/xsfw/"));
            merge(cm.getCookie("https://ids.sdxiehe.edu.cn"));
        } catch (Throwable ignored) { }
    }

    /** 登录成功：新增或更新数据库里的账号记录 */
    private void saveAccount(String cookie, KqModel m) {
        AccountStore st = AccountStore.get(this);
        String name = "";
        String no = "";
        String dorm = "";
        if (m != null) {
            name = m.stuName;
            no = m.studentNo;
            dorm = m.dorm;
        }
        String display = name.length() > 0 ? name : no;
        if (display.length() == 0) display = no;

        Account a;
        if (accountId > 0) {
            a = st.get(accountId);
            if (a == null) a = new Account();
        } else {
            // 同一个人不重复建号：先按学号找，再按 Cookie 找
            Account exist = st.findByStudentNo(no);
            if (exist == null) exist = st.findByCookie(cookie);
            if (exist != null) {
                a = exist;
                Logs.add("该账号已存在（" + a.title() + "），改为更新登录状态");
            } else {
                a = new Account();
            }
        }
        a.cookie = cookie;
        a.ua = Prefs.get(this).ua();
        a.lastLoginAt = System.currentTimeMillis();   // 登录冷却起点
        a.blockedUntil = 0;                            // 能登进来就说明已经解冻了
        if (no.length() > 0) a.studentNo = no;
        if (dorm.length() > 0) a.dorm = dorm;
        if (display.length() > 0) {
            a.name = display;
        } else if (a.name == null || a.name.length() == 0) {
            a.name = "账号 " + (st.count() + 1);
        }

        // 刚登录成功，状态要立刻改过来，不然列表还挂着"会话失效"
        if (a.lastOk != Account.STATE_OK) {
            a.lastOk = Account.STATE_NONE;
            a.lastResult = "已登录，等待签到";
            a.lastTime = System.currentTimeMillis();
        }

        if (a.id > 0) {
            st.update(a);
            Logs.add("账号「" + a.title() + "」的登录状态已更新");
        } else {
            st.add(a);
            Logs.add("已添加账号「" + a.title() + "」，共 " + st.count() + " 个");
        }
        // 新账号这次登录时输入的密码，顺手加密存起来
        String pwd = pendingPwd;
        String user = pendingUser;
        if (pwd.length() == 0 && autoPwd.length() > 0) {
            pwd = autoPwd;
        }
        if (pwd.length() > 0) {
            st.setPwd(a.id, Secure.encrypt(pwd));
            if (a.pwdEnc.length() == 0) Logs.add("已保存「" + a.title() + "」的密码，下次自动登录");
        }
        if (user.length() > 0) Logs.add("登录学号：" + user);
        pendingUser = "";
        pendingPwd = "";
        st.dedupe();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            setResult(RESULT_CANCELED);
            finish();
        }
    }
}
