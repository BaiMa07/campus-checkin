package com.baima.xiehe;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** getKqInfo.do 返回数据的模型 + 打卡点匹配 + 坐标抖动 */
public class KqModel {

    public String date = "";
    public String start = "";
    public String end = "";
    public boolean inWindow;      // IN_CQ_TIME
    public boolean inDate = true; // IN_DATE
    public boolean hasSigned;     // QD_INFO.hasQd
    public boolean leave;         // IS_QJ
    public String dorm = "";      // SSL_INFO.SSLDM_DISPLAY
    public String studentNo = ""; // SSL_INFO.XSBH
    public String stuName = "";   // SSL_INFO.XM

    public final List<Point> points = new ArrayList<Point>();

    public static class Point {
        public String name = "";
        public String code = "";
        public double lng;
        public double lat;
        public int radius;

        public String describe() {
            return name + "（" + lng + ", " + lat + "，半径 " + radius + " 米）";
        }
    }

    public static KqModel parse(JSONObject data) {
        KqModel m = new KqModel();
        if (data == null) return m;

        JSONObject cf = data.optJSONObject("CONFIG_INFO");
        if (cf != null) {
            m.date = cf.optString("KQRQ", "");
            m.start = cf.optString("CQKSSJ", "");
            m.end = cf.optString("CQJSSJ", "");
            m.inWindow = cf.optBoolean("IN_CQ_TIME", false);
            m.inDate = cf.optBoolean("IN_DATE", true);
            m.leave = cf.optBoolean("IS_QJ", false);
        }
        m.leave = m.leave || data.optBoolean("IS_QJ", false);

        JSONObject qd = data.optJSONObject("QD_INFO");
        if (qd != null) m.hasSigned = qd.optBoolean("hasQd", false);

        JSONObject ssl = data.optJSONObject("SSL_INFO");
        if (ssl != null) {
            m.dorm = ssl.optString("SSLDM_DISPLAY", "");
            m.studentNo = ssl.optString("XSBH", "");
            m.stuName = ssl.optString("XM", "");
        }

        JSONArray arr = data.optJSONArray("DD_LIST");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Point p = new Point();
                p.name = o.optString("QDDD", "");
                p.code = o.optString("DDDM", "");
                p.radius = o.optInt("YXFW", 50);
                try { p.lng = Double.parseDouble(o.optString("JDZB", "0")); } catch (Exception ignored) { }
                try { p.lat = Double.parseDouble(o.optString("WDZB", "0")); } catch (Exception ignored) { }
                if (p.lng != 0 && p.lat != 0) m.points.add(p);
            }
        }
        return m;
    }

    /** 宿舍名子串匹配；不行就用宿舍名里的数字匹配（排除"辅助"点） */
    public Point pickPoint() {
        if (points.isEmpty()) return null;
        if (dorm != null && dorm.length() > 0) {
            for (Point p : points) {
                if (p.name.contains(dorm)) return p;
            }
            StringBuilder digits = new StringBuilder();
            for (int i = 0; i < dorm.length(); i++) {
                char c = dorm.charAt(i);
                if (Character.isDigit(c)) digits.append(c);
            }
            String key = digits.toString();
            if (key.length() > 0) {
                for (Point p : points) {
                    if (p.name.contains(key) && !p.name.contains("辅助")) return p;
                }
            }
        }
        return null;
    }

    /** 在打卡点附近随机偏移 ±3 米，避免每次坐标一模一样 */
    public static double[] jitter(double lat, double lng) {
        Random r = new Random();
        double dLat = (r.nextDouble() * 6 - 3) / 111320.0;
        double dLng = (r.nextDouble() * 6 - 3) / (111320.0 * Math.cos(Math.toRadians(lat)));
        return new double[]{
                Double.parseDouble(String.format(Locale.US, "%.6f", lat + dLat)),
                Double.parseDouble(String.format(Locale.US, "%.6f", lng + dLng))
        };
    }
}
