package com.applauncher.dual;

import android.content.Context;
import android.content.SharedPreferences;

import com.carlos.common.App;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 内置默认 SO：libmcmod.so 打包在 apk 的 assets 里。
 *
 *  - 默认就是用它，用户不选也能直接启动（不再要求先选库）
 *  - 只在用户明确选了自己的库之后才让位（prefs so_source=custom）
 *  - 释放到 cache/temp.so + files/temp.so，并写 auto_inject.so 标记，
 *    这样 SoBootstrap 在虚拟进程里一定能读到
 *  - apk 升级（lastUpdateTime 变化）后自动重新释放，保证库是新的
 */
public final class BuiltinSo {

    public static final String ASSET = "libmcmod.so";
    public static final String DISPLAY = "libmcmod.so (内置默认)";

    private static final String PREFS = "launcher_prefs";
    private static final String KEY_SOURCE = "so_source";     // builtin | custom
    private static final String KEY_SEALED = "builtin_sealed_ver";
    private static final String KEY_NAME = "saved_so_name";

    private BuiltinSo() {}

    public static boolean isCustom(Context ctx) {
        return "custom".equals(prefs(ctx).getString(KEY_SOURCE, ""));
    }

    public static void markCustom(Context ctx) {
        prefs(ctx).edit().putString(KEY_SOURCE, "custom").apply();
    }

    public static void markBuiltin(Context ctx) {
        prefs(ctx).edit().putString(KEY_SOURCE, "builtin")
                .putString(KEY_NAME, DISPLAY).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static long apkVersion(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).lastUpdateTime;
        } catch (Throwable t) {
            return -1L;
        }
    }

    /** 内置库对应的真实文件（释放好之后就是 cache/temp.so） */
    public static File file(Context ctx) {
        File cache = new File(ctx.getCacheDir(), "temp.so");
        if (cache.isFile() && cache.length() > 0) return cache;
        return new File(ctx.getFilesDir(), "temp.so");
    }

    /** 保证内置库已经释放到位；用户选了自定义库时不碰它的 temp.so */
    public static File ensure(Context ctx) {
        File cache = new File(ctx.getCacheDir(), "temp.so");
        File files = new File(ctx.getFilesDir(), "temp.so");
        long ver = apkVersion(ctx);
        SharedPreferences p = prefs(ctx);

        if (cache.isFile() && cache.length() > 0 && p.getLong(KEY_SEALED, -1L) == ver) {
            writeFlag(ctx);
            App.log("BuiltinSo: already sealed, " + cache.getAbsolutePath()
                    + " len=" + cache.length() + " ver=" + ver);
            return cache;
        }

        try {
            long n1 = copyAsset(ctx, cache);
            long n2 = cachedCopy(cache, files);
            writeFlag(ctx);
            p.edit().putLong(KEY_SEALED, ver).putString(KEY_NAME, DISPLAY).apply();
            App.log("BuiltinSo: sealed asset " + ASSET + " -> " + cache.getAbsolutePath()
                    + " (" + n1 + " bytes, mirror " + n2 + ") ver=" + ver);
            return cache;
        } catch (Throwable t) {
            App.log("BuiltinSo: seal FAILED", t);
            return file(ctx);
        }
    }

    private static long copyAsset(Context ctx, File dst) throws Exception {
        InputStream in = ctx.getAssets().open(ASSET);
        FileOutputStream out = new FileOutputStream(dst, false);
        byte[] buf = new byte[65536];
        long total = 0;
        int n;
        try {
            while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); total += n; }
        } finally {
            try { in.close(); } catch (Throwable ignored) { }
            try { out.flush(); out.close(); } catch (Throwable ignored) { }
        }
        dst.setReadable(true, false);
        return total;
    }

    private static long cachedCopy(File src, File dst) {
        try {
            if (src.lastModified() == dst.lastModified() && dst.isFile()
                    && src.length() == dst.length()) return dst.length();
            InputStream in = new java.io.FileInputStream(src);
            FileOutputStream out = new FileOutputStream(dst, false);
            byte[] buf = new byte[65536];
            long total = 0;
            int n;
            try {
                while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); total += n; }
            } finally {
                try { in.close(); } catch (Throwable ignored) { }
                try { out.flush(); out.close(); } catch (Throwable ignored) { }
            }
            dst.setReadable(true, false);
            return total;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 开关标记两个目录都写，保证虚拟进程无论从哪读都能看到 */
    public static void writeFlag(Context ctx) {
        for (File dir : new File[]{ctx.getCacheDir(), ctx.getFilesDir()}) {
            try {
                File f = new File(dir, "auto_inject.so");
                if (!f.exists()) { FileOutputStream o = new FileOutputStream(f); o.write(0); o.close(); }
            } catch (Throwable t) { App.log("BuiltinSo.writeFlag failed in " + dir, t); }
        }
    }
}
