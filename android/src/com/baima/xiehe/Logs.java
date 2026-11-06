package com.baima.xiehe;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** 内存环形日志 + filesDir/sign.log 落盘，界面里能直接看到后台发生了什么 */
public class Logs {

    private static final int MAX = 300;
    private static final String FILE_NAME = "sign.log";
    private static final ArrayDeque<String> MEM = new ArrayDeque<String>();
    private static File file;
    private static Listener listener;

    public interface Listener { void onLog(String line); }

    public static void init(Context c) {
        if (file == null && c != null) file = new File(c.getApplicationContext().getFilesDir(), FILE_NAME);
    }

    private static File f() {
        return file;
    }

    public static void add(String msg) {
        String line = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(new Date()) + "  " + msg;
        synchronized (MEM) {
            MEM.addLast(line);
            while (MEM.size() > MAX) MEM.removeFirst();
        }
        File target = f();
        if (target != null) {
            FileWriter fw = null;
            try {
                fw = new FileWriter(target, true);
                fw.write(line + "\n");
            } catch (Exception ignored) {
            } finally {
                if (fw != null) try { fw.close(); } catch (Exception ignored) {}
            }
        }
        Listener l = listener;
        if (l != null) l.onLog(line);
    }

    public static String dump() {
        StringBuilder sb = new StringBuilder();
        synchronized (MEM) {
            for (String s : MEM) sb.append(s).append('\n');
        }
        return sb.toString();
    }

    public static void setListener(Listener l) { listener = l; }

    /** 清空日志：删文件 + 清内存缓冲 */
    public static boolean clear() {
        boolean ok = true;
        synchronized (MEM) {
            MEM.clear();
        }
        File target = f();
        if (target != null && target.exists()) {
            try {
                ok = target.delete();
            } catch (Throwable t) {
                ok = false;
            }
            // 删不掉就截断
            if (!ok) {
                try { new FileWriter(target, false).close(); ok = true; } catch (Throwable ignored) { ok = false; }
            }
        }
        return ok;
    }

    /** 导出用的全文：磁盘文件优先（含历史），文件没了就退回内存 */
    public static String exportText() {
        File target = f();
        String disk = "";
        if (target != null && target.exists()) {
            FileInputStream in = null;
            try {
                in = new FileInputStream(target);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                disk = new String(bos.toByteArray(), "UTF-8");
            } catch (Throwable ignored) {
            } finally {
                if (in != null) try { in.close(); } catch (Exception ignored) { }
            }
        }
        String mem = dump();
        if (disk.length() == 0) return mem;
        if (mem.length() == 0) return disk;
        return disk + "\n----- 本次内存日志 -----\n" + mem;
    }

    public static long sizeBytes() {
        File target = f();
        return (target != null && target.exists()) ? target.length() : 0L;
    }

    public static int count() {
        synchronized (MEM) {
            return MEM.size();
        }
    }
}
