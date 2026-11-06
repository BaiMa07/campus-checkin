package com.baima.xiehe;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.RandomAccessFile;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** 内存环形日志 + filesDir/sign.log 落盘，界面里能直接看到后台发生了什么 */
public class Logs {

    private static final int MAX = 300;
    private static final String FILE_NAME = "sign.log";
    /** 落盘日志上限：超过 128KB 就只留最后 64KB，避免越攒越大 */
    private static final long MAX_BYTES = 128 * 1024;
    private static final long KEEP_BYTES = 64 * 1024;
    private static final Object LOCK = new Object();
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
            synchronized (LOCK) {
                FileWriter fw = null;
                try {
                    fw = new FileWriter(target, true);
                    fw.write(line + "\n");
                } catch (Exception ignored) {
                } finally {
                    if (fw != null) try { fw.close(); } catch (Exception ignored) {}
                }
                if (target.length() > MAX_BYTES) trimLocked(target);
            }
        }
        Listener l = listener;
        if (l != null) l.onLog(line);
    }

    /** 文件超限了就只保留最后一段（按行切，不留半行） */
    private static void trimLocked(File target) {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(target, "rw");
            long len = raf.length();
            long start = Math.max(0, len - KEEP_BYTES);
            raf.seek(start);
            int b;
            while ((b = raf.read()) != -1 && b != '\n') { }
            long pos = raf.getFilePointer();
            byte[] buf = new byte[(int) (len - pos)];
            raf.seek(pos);
            raf.readFully(buf);
            raf.setLength(0);
            raf.seek(0);
            raf.write(buf);
        } catch (Throwable ignored) {
        } finally {
            if (raf != null) try { raf.close(); } catch (Throwable ignored) { }
        }
    }

    /** 外部（启动时）调用的截断 */
    public static void trim() {
        File target = f();
        if (target == null || !target.exists()) return;
        synchronized (LOCK) {
            if (target.length() > MAX_BYTES) trimLocked(target);
        }
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
                synchronized (LOCK) { ok = target.delete(); }
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
