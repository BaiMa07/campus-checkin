package com.baima.xiehe;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 学工服务 智能签到 接口封装（纯 HttpURLConnection，无第三方库）
 * 关键点：
 *  1) 关闭自动重定向，否则会话失效会被悄悄跟到登录页，永远看不到 302
 *  2) Cookie 手动拼头（framework 的 HttpURLConnection 没有 cookie jar）
 *  3) 表单体固定为 data=<URL 编码后的 JSON 文本>
 */
public class KqApi {

    public static final String BASE = "https://xg.sdxiehe.edu.cn/xsfw/sys/swmzncqapp/kqController/";
    public static final String SERVICE = "https://xg.sdxiehe.edu.cn/xsfw/sys/swmzncqapp/*default/index.do";
    public static final String REFERER = SERVICE + "#/xscq/kqqdx";
    public static final String DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 13; PGT-AN20) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";

    public enum State { OK, EXPIRED, NET_ERROR, BIZ_ERROR }

    public static class Resp {
        public State state = State.NET_ERROR;
        public String msg = "";
        public String raw = "";
        public JSONObject data;
        public KqModel model;
    }

    public static Resp getKqInfo(String cookie, String ua) {
        Resp r = post(cookie, ua, "getKqInfo.do", "{}");
        if (r.state == State.OK) r.model = KqModel.parse(r.data);
        return r;
    }

    /** 一条历史考勤记录 */
    public static class Hist {
        public String day = "";    // yyyy-MM-dd
        public String time = "";   // yyyy-MM-dd HH:mm:ss
        public String place = "";
    }

    /** 拉某个月的考勤记录；没签的日子服务端也会返回一条，这里过滤掉 */
    public static List<Hist> history(String cookie, String ua, int year, int month) {
        List<Hist> out = new ArrayList<Hist>();
        Resp r = post(cookie, ua, "getKqlsList.do",
                "{\"YEAR\":\"" + year + "\",\"MONTH\":\"" + month + "\"}");
        if (r.state != State.OK || r.raw == null) return out;
        try {
            JSONArray arr = new JSONObject(r.raw).optJSONArray("data");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String day = o.optString("RQ", "");
                String sj = o.optString("KQSJ", "");
                String wz = o.optString("KQWZXX", "");
                if (day.length() == 0) continue;
                if (sj.length() == 0 && wz.length() == 0) continue;  // 这天没签
                Hist h = new Hist();
                h.day = day;
                h.time = sj;
                h.place = wz;
                out.add(h);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    public static Resp addKqInfo(String cookie, String ua, String addr, double lng, double lat) {
        String payload = "{\"KQWZXX\":" + JSONObject.quote(addr)
                + ",\"JDZB\":\"" + fmt(lng) + "\""
                + ",\"WDZB\":\"" + fmt(lat) + "\"}";
        return post(cookie, ua, "addKqInfo.do", payload);
    }

    private static String fmt(double v) {
        return String.format(Locale.US, "%.6f", v);
    }

    private static Resp post(String cookie, String ua, String path, String jsonText) {
        Resp r = new Resp();
        HttpURLConnection conn = null;
        try {
            byte[] body = ("data=" + URLEncoder.encode(jsonText, "UTF-8")).getBytes("UTF-8");
            java.net.Proxy px = Net.proxy();
            URL target = new URL(BASE + path);
            conn = (HttpURLConnection) (px == null ? target.openConnection()
                    : target.openConnection(px));
            conn.setRequestMethod("POST");
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(body.length);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            conn.setRequestProperty("X-Requested-With", "XMLHttpRequest");
            conn.setRequestProperty("Referer", REFERER);
            conn.setRequestProperty("User-Agent", (ua == null || ua.length() == 0) ? DEFAULT_UA : ua);
            if (cookie != null && cookie.length() > 0) conn.setRequestProperty("Cookie", cookie);

            OutputStream os = conn.getOutputStream();
            os.write(body);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = String.valueOf(conn.getHeaderField("Location"));
                if (loc.contains("ids.sdxiehe.edu.cn") || loc.contains("authserver")) {
                    r.state = State.EXPIRED;
                    r.msg = "登录已失效";
                } else {
                    r.state = State.BIZ_ERROR;
                    r.msg = "重定向：" + loc;
                }
                return r;
            }

            InputStream is = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
            String text = read(is);
            r.raw = text;
            if (text == null || text.trim().length() == 0) {
                r.state = State.NET_ERROR;
                r.msg = "空响应（HTTP " + code + "）";
                return r;
            }
            String t = text.trim();
            if (t.startsWith("<") || t.contains("authserver") || t.contains("统一身份认证")) {
                r.state = State.EXPIRED;
                r.msg = "登录已失效（返回登录页）";
                return r;
            }

            JSONObject j = new JSONObject(t);
            String c = j.optString("code", "");
            if ("0".equals(c)) {
                r.state = State.OK;
                r.data = j.optJSONObject("data");
                r.msg = j.optString("msg", "");
            } else {
                r.state = State.BIZ_ERROR;
                r.msg = j.optString("msg", "code=" + c);
            }
            return r;
        } catch (Exception e) {
            r.state = State.NET_ERROR;
            r.msg = e.getClass().getSimpleName() + ": " + e.getMessage();
            return r;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String read(InputStream is) {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        try {
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return "";
        } finally {
            try { is.close(); } catch (Exception ignored) { }
        }
    }
}
