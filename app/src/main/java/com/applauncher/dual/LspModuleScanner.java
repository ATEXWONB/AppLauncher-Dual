package com.applauncher.dual;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;

import com.carlos.common.App;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipFile;

/**
 * 扫描设备上已安装的 Xposed / LSPosed 模块。
 *
 * 判定依据（按优先级）：
 *  1) APK 内存在 assets/xposed_init        -> 经典 Xposed 模块，VA 的 XposedCompat 能加载（可勾选）
 *  2) APK 内存在 META-INF/xposed/java_init.list / native_init.list / module.prop
 *                                          -> libxposed 新 API（LSPosed API 101/102），VA 内核不认识（只展示）
 *  3) AndroidManifest 里声明了 xposedmodule / xposedminversion / xposedscope
 *                                          -> 疑似模块，但 APK 内没有 xposed_init（只展示）
 */
public class LspModuleScanner {

    public static class ModuleInfo {
        public String pkg;
        public String label;
        public String apkPath;
        public String kind;      // classic / newapi / declared
        public String note;
        public int api;          // 模块声明的 API 版本（经典 94 / 现代 101、102）
        public boolean selectable;
    }

    public static ArrayList<ModuleInfo> scan(Context ctx) {
        ArrayList<ModuleInfo> out = new ArrayList<>();
        PackageManager pm = ctx.getPackageManager();
        List<ApplicationInfo> apps;
        try {
            apps = pm.getInstalledApplications(0);
        } catch (Throwable t) {
            App.log("LSP scan: getInstalledApplications failed", t);
            return out;
        }
        if (apps == null) return out;
        String self = ctx.getPackageName();
        for (ApplicationInfo ai : apps) {
            if (ai == null || ai.packageName == null) continue;
            if (ai.packageName.equals(self)) continue;
            try {
                ModuleInfo m = inspect(pm, ai);
                if (m != null) out.add(m);
            } catch (Throwable t) {
                App.log("LSP scan: inspect failed for " + ai.packageName, t);
            }
        }
        Collections.sort(out, new Comparator<ModuleInfo>() {
            @Override
            public int compare(ModuleInfo a, ModuleInfo b) {
                if (a.selectable != b.selectable) return a.selectable ? -1 : 1;
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        App.log("LSP scan: " + out.size() + " module-like apps found");
        return out;
    }

    private static ModuleInfo inspect(PackageManager pm, ApplicationInfo ai) {
        boolean classic = false, newApi = false, declared = false;

        String apk = ai.sourceDir;
        if (apk != null && new File(apk).isFile()) {
            ZipFile zip = null;
            try {
                zip = new ZipFile(apk);
                classic = zip.getEntry("assets/xposed_init") != null;
                newApi = zip.getEntry("META-INF/xposed/java_init.list") != null
                        || zip.getEntry("META-INF/xposed/native_init.list") != null
                        || zip.getEntry("META-INF/xposed/module.prop") != null;
            } catch (Throwable ignored) {
            } finally {
                if (zip != null) try { zip.close(); } catch (Throwable ignored) { }
            }
        }

        try {
            Bundle md = ai.metaData;
            if (md != null && (md.containsKey("xposedmodule")
                    || md.containsKey("xposedminversion")
                    || md.containsKey("xposedscope")
                    || md.containsKey("xposeddescription"))) {
                declared = true;
            }
        } catch (Throwable ignored) { }

        if (!classic && !newApi && !declared) return null;

        ModuleInfo m = new ModuleInfo();
        m.pkg = ai.packageName;
        m.apkPath = apk;
        try {
            m.label = String.valueOf(pm.getApplicationLabel(ai));
        } catch (Throwable t) {
            m.label = ai.packageName;
        }
        if (newApi) {
            m.kind = "newapi";
            // Vector core-837 自带 libxposed API 101/102 的现代模块加载链路，
            // 已实测 io.github.symonchu.impadpro 1.2.1 能在 guest 进程里加载成功。
            m.selectable = true;
            m.api = readModernApi(apk);
            m.note = "加载方式：libxposed 现代 API（声明 " + m.api + "，内核按 "
                    + clampModern(m.api) + " 加载）";
        } else if (classic) {
            m.kind = "classic";
            m.selectable = true;
            m.api = readLegacyApi(ai);
            m.note = "加载方式：经典 Xposed API（声明 " + m.api + "，内核按 94 兼容加载）";
        } else {
            m.kind = "declared";
            m.selectable = false;
            m.note = "声明了 xposed 模块，但 APK 内没有可识别的模块入口，无法加载";
        }
        return m;
    }

    /** 现代模块：读 META-INF/xposed/module.prop 的 targetApiVersion（缺省 101） */
    private static int readModernApi(String apk) {
        if (apk == null) return 101;
        ZipFile zip = null;
        try {
            zip = new ZipFile(apk);
            java.util.zip.ZipEntry e = zip.getEntry("META-INF/xposed/module.prop");
            if (e == null) return 101;
            java.util.Properties p = new java.util.Properties();
            try (java.io.InputStream in = zip.getInputStream(e)) {
                p.load(new java.io.InputStreamReader(in, "UTF-8"));
            }
            int target = parseIntPrefix(p.getProperty("targetApiVersion"));
            if (target <= 0) target = parseIntPrefix(p.getProperty("minApiVersion"));
            return target <= 0 ? 101 : target;
        } catch (Throwable ignored) {
            return 101;
        } finally {
            if (zip != null) try { zip.close(); } catch (Throwable ignored) { }
        }
    }

    private static int clampModern(int api) {
        if (api <= 101) return 101;
        return Math.min(api, 102);
    }

    /** 经典模块：读 manifest 的 xposedminversion，缺省按 94 */
    private static int readLegacyApi(ApplicationInfo ai) {
        try {
            Bundle md = ai.metaData;
            if (md != null) {
                Object v = md.get("xposedminversion");
                if (v instanceof Integer) return (Integer) v;
                if (v != null) {
                    int p = parseIntPrefix(String.valueOf(v));
                    if (p > 0) return p;
                }
            }
        } catch (Throwable ignored) { }
        return 94;
    }

    private static int parseIntPrefix(String s) {
        if (s == null) return 0;
        s = s.trim();
        int end = 0;
        while (end < s.length() && Character.isDigit(s.charAt(end))) end++;
        try {
            return end == 0 ? 0 : Integer.parseInt(s.substring(0, end));
        } catch (Throwable ignored) {
            return 0;
        }
    }
}
