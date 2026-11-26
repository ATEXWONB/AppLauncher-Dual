package com.applauncher.dual;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 注入专用的「追加式」小日志 files/inject.log。
 *
 * 为什么单独一份：log.txt 是 logcat -f 抓的，每次 APP START 会重建，
 * 一旦重启就看不到上一次到底注入成功还是失败。inject.log 只追加、不重建，
 * 每次启动/注入写一行，出问题时直接翻这个文件就能对比历史。
 */
public final class InjectLog {

    private static final String HOST_PKG = "com.applauncher.dual";
    private static final long MAX_BYTES = 512 * 1024;

    private InjectLog() { }

    public static void w(Context ctx, String msg) {
        File f = target(ctx);
        if (f == null) return;
        try {
            if (f.length() > MAX_BYTES) {
                // 太大就留后半段，避免无限增长
                f.delete();
            }
            String time = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
            FileOutputStream o = new FileOutputStream(f, true);
            try {
                o.write((time + " [" + android.os.Process.myPid() + "] " + msg + "\n").getBytes("UTF-8"));
            } finally {
                try { o.flush(); o.close(); } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
    }

    private static File target(Context ctx) {
        File[] cands = new File[]{
                new File("/data/user/0/" + HOST_PKG + "/files/inject.log"),
                new File("/data/data/" + HOST_PKG + "/files/inject.log"),
                ctx == null ? null : new File(ctx.getFilesDir(), "inject.log")
        };
        for (File f : cands) {
            if (f == null) continue;
            File dir = f.getParentFile();
            if (dir != null && dir.isDirectory()) return f;
        }
        return null;
    }
}
