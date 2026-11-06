package com.baima.xiehe;

import android.content.Context;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;

/**
 * 网络出口：检测当前公网 IP / 归属地，以及可选的 HTTP 代理。
 *
 * 装了代理之后，查 IP 和签到请求都走代理，等于换了个出口 IP。
 * 代理只影响本应用发出的请求，系统其它应用不受影响。
 */
public class Net {

    private static String host = "";
    private static int port = 0;
    private static java.util.List<String> hosts = new java.util.ArrayList<String>();
    private static java.util.List<Integer> ports = new java.util.ArrayList<Integer>();
    private static int index = 0;

    public static synchronized void init(Context c) {
        Prefs p = Prefs.get(c.getApplicationContext());
        host = p.proxyHost();
        port = p.proxyPort();
        parse(p.proxyList());
    }

    /** 解析"一行一个 host:port" */
    private static void parse(String text) {
        hosts.clear();
        ports.clear();
        index = 0;
        if (text == null) return;
        for (String line : text.split("\n")) {
            String s = line.trim();
            if (s.length() == 0 || s.startsWith("#")) continue;
            int i = s.lastIndexOf(':');
            try {
                String h = i > 0 ? s.substring(0, i) : s;
                int p = i > 0 ? Integer.parseInt(s.substring(i + 1).trim()) : 0;
                if (h.length() > 0 && p > 0 && p <= 65535) {
                    hosts.add(h);
                    ports.add(p);
                }
            } catch (Throwable ignored) { }
        }
    }

    /** 保存多代理列表（第一个立即生效） */
    public static synchronized void setList(Context c, String text) {
        Prefs.get(c.getApplicationContext()).setProxyList(text);
        parse(text);
        Logs.add("出口代理列表已更新：" + (hosts.size() == 0 ? "直连" : hosts.size() + " 个"));
    }

    public static synchronized int count() { return hosts.size(); }

    /** 换下一个出口，用于多账号各走各的 IP */
    public static synchronized void next() {
        if (hosts.size() <= 1) return;
        index = (index + 1) % hosts.size();
    }

    public static synchronized String activeText() {
        if (hosts.size() == 0) return "直连";
        return hosts.get(index) + ":" + ports.get(index);
    }

    public static synchronized void set(Context c, String h, int p) {
        host = h == null ? "" : h.trim();
        port = Math.max(0, Math.min(65535, p));
        Prefs.get(c.getApplicationContext()).setProxy(host, host.length() == 0 ? 0 : port);
    }

    public static synchronized String proxyText() {
        if (hosts.size() > 0) return hosts.get(index) + ":" + ports.get(index);
        return host.length() == 0 ? "" : (host + ":" + port);
    }

    public static synchronized Proxy proxy() {
        String h = host;
        int p = port;
        if (hosts.size() > 0) {
            h = hosts.get(index);
            p = ports.get(index);
        }
        if (h == null || h.length() == 0 || p <= 0) return null;
        try {
            return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(h, p));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 阻塞调用，要在后台线程里跑。返回给人看的多行文字。 */
    public static String check() {
        String first = query("https://ipwho.is/?lang=zh-CN");
        if (first != null) return first;
        String second = query("https://ipapi.co/json/");
        if (second != null) return second;
        return "检测失败：连不上查询服务，检查一下网络或代理地址";
    }

    private static String query(String api) {
        HttpURLConnection conn = null;
        try {
            URL u = new URL(api);
            Proxy px = proxy();
            conn = (HttpURLConnection) (px == null ? u.openConnection() : u.openConnection(px));
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13)");
            conn.setRequestProperty("Accept", "application/json");
            InputStream is = conn.getInputStream();
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[2048];
            int n;
            while ((n = is.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
            is.close();
            JSONObject j = new JSONObject(sb.toString());

            if (j.has("success") && !j.optBoolean("success", false)) return null;
            String ip = j.optString("ip", "");
            if (ip.length() == 0) return null;
            String country = j.optString("country", "");
            String region = j.optString("region", "");
            String city = j.optString("city", "");
            String isp = "";
            JSONObject c = j.optJSONObject("connection");
            if (c != null) isp = c.optString("isp", "");
            if (isp.length() == 0) isp = j.optString("org", "");

            StringBuilder out = new StringBuilder();
            out.append("出口 IP：").append(ip);
            StringBuilder loc = new StringBuilder();
            if (country.length() > 0) loc.append(country);
            if (region.length() > 0) loc.append(" ").append(region);
            if (city.length() > 0) loc.append(" ").append(city);
            if (loc.length() > 0) out.append("\n归属地：").append(loc);
            if (isp.length() > 0) out.append("\n运营商：").append(isp);
            out.append(proxy() == null ? "\n（当前直连）" : "\n（走代理 " + proxyText() + "）");
            return out.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) { }
        }
    }
}
