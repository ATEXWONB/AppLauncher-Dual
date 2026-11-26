package com.applauncher.dual;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import com.carlos.common.App;
import com.lody.virtual.client.core.VirtualCore;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 已安装应用列表：主通道改回「权限列表」——QUERY_ALL_PACKAGES + PackageManager API。
 *
 * 关键点：必须用 VirtualCore.getUnHookPackageManager()。VA 在宿主进程里也 hook 了
 * IPackageManager，普通的 ctx.getPackageManager() 拿到的是虚拟包管理器，只会返回
 * 已经分身过的应用（第一次用的时候就是空列表，这就是「怎么都无法获取应用列表」的原因）。
 *
 * 文件系统扫描（/data/app 目录遍历）在 Android 11+ 已经不可靠，这里只保留为兜底，
 * 不再作为主通道。
 */
public final class InstalledAppScanner {

    private static final Pattern PKG_RE =
            Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)+$");

    public static final String TARGET_PACKAGE = "com.mojang.minecraftpe";

    public static final String[] ROOTS = new String[]{
            "/data/app", "/system/app", "/system/priv-app",
            "/system_ext/app", "/system_ext/priv-app",
            "/vendor/app", "/vendor/priv-app",
            "/product/app", "/product/priv-app",
            "/odm/app", "/odm/priv-app", "/oem/app"
    };

    public static class Entry {
        public String pkg;
        public String label;
        public String apkPath;
        public String appDir;
        public String nativeLibDir;
        public String versionName;
        public Drawable icon;
        public boolean system;
        public boolean libOnDisk;
        public boolean libInApk;
        public final ArrayList<String> soFiles = new ArrayList<>();
        public final ArrayList<String> soInApk = new ArrayList<>();
        public int source; // 3=PackageManager API
    }

    public static class SoItem {
        public String label;
        public String path;
        public String pkg;
        public String apkPath;
        public String entryName;
        public boolean inApk;
        @Override public String toString() { return label; }
    }

    private static volatile List<Entry> cache;
    private static volatile long cacheAt;
    private static final long TTL = 60000L;

    private InstalledAppScanner() {}

    /**
     * 宿主进程里的系统 PackageManager。
     * 旧版 VA（本工程用的 lib-release.aar）没有 getUnHookPackageManager，
     * 宿主进程的 PackageManager 本身就是系统的；只有虚拟进程里才会被 hook。
     * 这里额外用反射试一下 HookPackageManager，拿到就优先用它。
     */
    public static PackageManager pickPackageManager(Context ctx) {
        PackageManager system = ctx.getApplicationContext().getPackageManager();
        try {
            java.lang.reflect.Method m =
                    VirtualCore.class.getMethod("getUnHookPackageManager");
            Object pm = m.invoke(VirtualCore.get());
            if (pm instanceof PackageManager) return (PackageManager) pm;
        } catch (Throwable ignored) { }
        return system;
    }

    public static synchronized List<Entry> scan(Context ctx) {
        long now = System.currentTimeMillis();
        if (cache != null && now - cacheAt < TTL) return cache;

        PackageManager pm = pickPackageManager(ctx);
        LinkedHashMap<String, Entry> map = new LinkedHashMap<>();

        // 通道 1：QUERY_ALL_PACKAGES 权限列表（主通道）
        loadInstalledApplications(map, pm);
        // 通道 2：桌面 LAUNCHER intent（ROM 拦截 QUERY_ALL_PACKAGES 时仍能拿到可启动应用）
        loadLauncherIntent(map, pm);
        // 通道 3：目标包强制补一次
        loadSingle(map, pm, TARGET_PACKAGE);
        // 通道 4：文件系统兜底（只在前面全都拿不到时才用）
        if (map.isEmpty()) {
            App.log("Scanner: PM returned 0, fallback to filesystem scan");
            for (String root : ROOTS) scanRoot(root, map, pm);
        } else {
            // 仍然用文件系统补那些 PM 不给的包（例如分身/隐藏应用），但不覆盖 PM 结果
            LinkedHashMap<String, Entry> extra = new LinkedHashMap<>();
            for (String root : ROOTS) scanRoot(root, extra, pm);
            for (Map.Entry<String, Entry> e : extra.entrySet()) {
                if (!map.containsKey(e.getKey())) map.put(e.getKey(), e.getValue());
            }
        }

        ArrayList<Entry> out = new ArrayList<>(map.values());
        Collections.sort(out, (a, b) -> {
            String x = a.label == null ? a.pkg : a.label;
            String y = b.label == null ? b.pkg : b.label;
            return x.compareToIgnoreCase(y);
        });
        cache = out;
        cacheAt = now;
        App.log("Scanner: total -> " + out.size() + " packages (PM first)");
        return out;
    }

    public static void invalidate() {
        cache = null;
        cacheAt = 0;
    }

    private static void loadInstalledApplications(Map<String, Entry> map, PackageManager pm) {
        try {
            List<ApplicationInfo> list = pm.getInstalledApplications(Compat.matchAll());
            App.log("Scanner: getInstalledApplications(MATCH_ALL) -> "
                    + (list == null ? "null" : list.size()));
            if (list == null || list.isEmpty()) {
                list = pm.getInstalledApplications(0);
                App.log("Scanner: getInstalledApplications(0) -> "
                        + (list == null ? "null" : list.size()));
            }
            if (list == null) return;
            for (ApplicationInfo ai : list) add(map, pm, ai.packageName, ai);
        } catch (Throwable t) {
            App.log("Scanner: getInstalledApplications FAILED", t);
        }
    }

    private static void loadLauncherIntent(Map<String, Entry> map, PackageManager pm) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> ris = pm.queryIntentActivities(i, Compat.matchAll());
            App.log("Scanner: queryIntentActivities(LAUNCHER) -> "
                    + (ris == null ? "null" : ris.size()));
            if (ris == null) return;
            for (ResolveInfo ri : ris) {
                if (ri.activityInfo != null) {
                    add(map, pm, ri.activityInfo.packageName, ri.activityInfo.applicationInfo);
                }
            }
        } catch (Throwable t) {
            App.log("Scanner: queryIntentActivities FAILED", t);
        }
    }

    private static void loadSingle(Map<String, Entry> map, PackageManager pm, String pkg) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            add(map, pm, pkg, ai);
            App.log("Scanner: explicit target " + pkg + " apk=" + (ai == null ? "null" : ai.sourceDir));
        } catch (Throwable t) {
            App.log("Scanner: explicit target " + pkg + " not visible (" + t.getMessage() + ")");
        }
    }

    private static void add(Map<String, Entry> map, PackageManager pm,
                            String pkg, ApplicationInfo ai) {
        if (pkg == null || !PKG_RE.matcher(pkg).matches()) return;
        Entry e = map.get(pkg);
        if (e == null) {
            e = new Entry();
            e.pkg = pkg;
            e.label = pkg;
            e.source = 3;
            map.put(pkg, e);
        }
        if (ai == null) return;
        if (ai.sourceDir != null) e.apkPath = ai.sourceDir;
        if (e.apkPath != null) e.appDir = new File(e.apkPath).getParent();
        e.nativeLibDir = ai.nativeLibraryDir;
        e.system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        try { e.label = ai.loadLabel(pm).toString(); } catch (Throwable ignored) { }
        if (e.icon == null) {
            try { e.icon = ai.loadIcon(pm); } catch (Throwable ignored) { }
        }
        // 直接读系统里已有的 nativeLibraryDir，不复制
        if (!e.libOnDisk && ai.nativeLibraryDir != null) {
            File[] sos = new File(ai.nativeLibraryDir)
                    .listFiles((dir, n) -> n.toLowerCase(Locale.US).endsWith(".so"));
            if (sos != null) {
                for (File so : sos) { e.libOnDisk = true; e.soFiles.add(so.getAbsolutePath()); }
            }
        }
        // APK 内 lib/<abi>/ 只在磁盘上没有 .so 时才扫一遍（省时间）
        if (!e.libOnDisk && !e.libInApk && e.apkPath != null) detectLibsInApk(e, e.apkPath);
    }

    // ===== 文件系统兜底（Android 11+ 基本不可用，只作保险） =====

    private static boolean isSystemRoot(String p) {
        return p.startsWith("/system") || p.startsWith("/vendor") || p.startsWith("/product")
                || p.startsWith("/odm") || p.startsWith("/oem") || p.startsWith("/apex");
    }

    private static void scanRoot(String root, Map<String, Entry> map, PackageManager pm) {
        File d = new File(root);
        if (!d.exists()) return;
        File[] l1 = d.listFiles();
        if (l1 == null) return;
        boolean system = isSystemRoot(root);
        for (File f : l1) {
            String n = f.getName();
            if (n.startsWith(".") || n.startsWith("~")) {
                if (f.isDirectory() && n.startsWith("~~")) walk(f, system, 0, map, pm);
                continue;
            }
            if (f.isDirectory()) walk(f, system, 0, map, pm);
            else if (f.isFile() && n.endsWith(".apk")) collectApk(f, guessPkg(n), system, map, pm);
        }
    }

    private static void walk(File dir, boolean system, int depth, Map<String, Entry> map, PackageManager pm) {
        if (dir == null || !dir.isDirectory() || depth > 3) return;
        String n = dir.getName();
        if (n.equals("lib") || n.equals("oat") || n.equals("odex") || n.equals("cache")
                || n.startsWith(".")) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        File apk = null;
        for (File k : kids) {
            if (k.isFile() && k.getName().endsWith(".apk")) {
                if (k.getName().equals("base.apk")) { apk = k; break; }
                if (apk == null) apk = k;
            }
        }
        if (apk != null) {
            collectApk(apk, guessPkg(n), system, map, pm);
            return;
        }
        for (File k : kids) if (k.isDirectory()) walk(k, system, depth + 1, map, pm);
    }

    /** 目录名 <pkg>-<random> / <pkg>.apk → 包名；包名本身不含 '-' */
    private static String guessPkg(String name) {
        if (name == null) return null;
        String s = name;
        if (s.endsWith(".apk")) s = s.substring(0, s.length() - 4);
        int i = s.indexOf('-');
        if (i > 0) s = s.substring(0, i);
        return PKG_RE.matcher(s).matches() ? s : null;
    }

    private static void collectApk(File apk, String guessed, boolean system,
                                   Map<String, Entry> map, PackageManager pm) {
        String path = apk.getAbsolutePath();
        if (apk.length() <= 0) return;
        String pkg = guessed;
        Entry e = pkg == null ? null : map.get(pkg);
        if (e != null && e.apkPath != null && !system) return;
        try {
            PackageInfo pi = pm.getPackageArchiveInfo(path, 0);
            if (pi != null && pi.packageName != null) {
                pkg = pi.packageName;
                if (e == null || system) {
                    ApplicationInfo ai = pi.applicationInfo;
                    if (ai != null) {
                        ai.sourceDir = path;
                        ai.publicSourceDir = path;
                        add(map, pm, pkg, ai);
                        e = map.get(pkg);
                    }
                }
            }
        } catch (Throwable t) {
            App.log("Scanner: getPackageArchiveInfo failed " + path + " -> " + t);
        }
        if (pkg == null || !PKG_RE.matcher(pkg).matches()) return;
        if (e == null) {
            e = new Entry();
            e.pkg = pkg;
            e.label = pkg;
            e.source = 1;
            map.put(pkg, e);
        }
        e.apkPath = path;
        e.appDir = apk.getParent();
        e.versionName = null;
        e.system = system;
        File lib = new File(apk.getParentFile(), "lib");
        File[] abis = lib.listFiles();
        if (abis != null) {
            for (File abi : abis) {
                if (!abi.isDirectory()) continue;
                File[] sos = abi.listFiles((d, n) -> n.toLowerCase(Locale.US).endsWith(".so"));
                if (sos == null) continue;
                for (File so : sos) {
                    e.libOnDisk = true;
                    if (!e.soFiles.contains(so.getAbsolutePath())) e.soFiles.add(so.getAbsolutePath());
                }
            }
        }
        if (!e.libOnDisk) detectLibsInApk(e, apk.getAbsolutePath());
    }

    private static void detectLibsInApk(Entry e, String apkPath) {
        ZipFile zf = null;
        try {
            zf = new ZipFile(apkPath);
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (n.startsWith("lib/") && n.toLowerCase(Locale.US).endsWith(".so")) {
                    e.libInApk = true;
                    e.soInApk.add(n);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (zf != null) try { zf.close(); } catch (Throwable ignored) { }
        }
    }

    public static Entry find(Context ctx, String pkg) {
        if (pkg == null) return null;
        for (Entry e : scan(ctx)) if (pkg.equals(e.pkg)) return e;
        return null;
    }

    private static String fastFind(String pkg) {
        File top = new File("/data/app");
        File[] l1 = top.listFiles();
        if (l1 == null) return null;
        for (File d : l1) {
            if (d.isDirectory() && d.getName().startsWith("~~")) {
                File[] l2 = d.listFiles();
                if (l2 == null) continue;
                for (File inner : l2) {
                    String hit = matchDir(inner, pkg);
                    if (hit != null) return hit;
                }
            } else {
                String hit = matchDir(d, pkg);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private static String matchDir(File d, String pkg) {
        if (!d.isDirectory()) return null;
        String n = d.getName();
        if (!n.equals(pkg) && !n.startsWith(pkg + "-")) return null;
        File base = new File(d, "base.apk");
        if (base.isFile()) return base.getAbsolutePath();
        File[] apks = d.listFiles((dir, name) -> name.endsWith(".apk"));
        return apks != null && apks.length > 0 ? apks[0].getAbsolutePath() : null;
    }

    /** 给 VA 安装用：系统包管理器（未 hook）优先 → saved → 目录快查 → 全量/兜底 */
    public static String findApkPath(Context ctx, String pkg, String saved) {
        PackageManager pm = pickPackageManager(ctx);
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            if (ai != null && ai.sourceDir != null && new File(ai.sourceDir).isFile()) {
                App.log("Scanner: apkPath from real PackageManager=" + ai.sourceDir);
                return ai.sourceDir;
            }
        } catch (Throwable t) {
            App.log("Scanner: real PackageManager getApplicationInfo failed: " + t.getMessage());
        }
        if (saved != null && new File(saved).isFile()) {
            App.log("Scanner: apkPath from saved=" + saved);
            return saved;
        }
        String fast = fastFind(pkg);
        if (fast != null) {
            App.log("Scanner: apkPath fast=" + fast);
            return fast;
        }
        Entry e = find(ctx, pkg);
        if (e != null && e.apkPath != null && new File(e.apkPath).isFile()) {
            App.log("Scanner: apkPath scan=" + e.apkPath);
            return e.apkPath;
        }
        try {
            ApplicationInfo ai = VirtualCore.get().getHostPackageManager().getApplicationInfo(pkg, 0);
            if (ai != null && ai.sourceDir != null) {
                App.log("Scanner: apkPath host pm=" + ai.sourceDir);
                return ai.sourceDir;
            }
        } catch (Throwable ignored) { }
        App.log("Scanner: apkPath NOT FOUND for " + pkg);
        return null;
    }

    /** 已安装应用里所有 .so（磁盘 lib/ 与 APK 内 lib/<abi>/ 都列出来） */
    public static List<SoItem> listNativeLibs(Context ctx) {
        ArrayList<SoItem> out = new ArrayList<>();
        for (Entry e : scan(ctx)) {
            String shown = e.label == null ? e.pkg : e.label;
            for (String p : e.soFiles) {
                SoItem i = new SoItem();
                i.pkg = e.pkg;
                i.label = shown + " / " + new File(p).getName();
                i.path = p;
                i.inApk = false;
                out.add(i);
            }
            for (String n : e.soInApk) {
                SoItem i = new SoItem();
                i.pkg = e.pkg;
                i.label = shown + " / APK内:" + n;
                i.apkPath = e.apkPath;
                i.entryName = n;
                i.inApk = true;
                out.add(i);
            }
        }
        App.log("Scanner: native libs -> " + out.size());
        return out;
    }

    /** APK 内的 lib/<abi>/xxx.so 先提取到私有目录才能 System.load */
    public static File extractSoFromApk(Context ctx, String apkPath, String entryName) throws IOException {
        File dir = new File(ctx.getFilesDir(), "picked_libs");
        if (!dir.exists()) dir.mkdirs();
        File out = new File(dir, new File(entryName).getName());
        ZipFile zf = new ZipFile(apkPath);
        try {
            ZipEntry ze = zf.getEntry(entryName);
            if (ze == null) throw new IOException("zip entry not found: " + entryName);
            InputStream in = zf.getInputStream(ze);
            FileOutputStream fo = new FileOutputStream(out, false);
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) fo.write(b, 0, n);
            fo.flush();
            fo.close();
            in.close();
        } finally {
            zf.close();
        }
        out.setReadable(true, false);
        App.log("Scanner: extracted " + entryName + " -> " + out.getAbsolutePath());
        return out;
    }
}
