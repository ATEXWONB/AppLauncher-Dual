package com.applauncher.dual;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import com.carlos.common.App;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.remote.VAppInstallerParams;
import com.lody.virtual.remote.VAppInstallerResult;
import com.lody.virtual.sandxposed.XposedModuleProfile;

import java.util.HashSet;
import java.util.Set;

/**
 * LSP/Xposed 模块的「勾选记录」+「启动时落地」。
 *
 * 记录：launcher_prefs 里的 lsp_modules（StringSet，存模块包名）
 * 落地：启动分身时
 *   1) 打开 VA 的 Xposed 总开关（XposedModuleProfile.enbaleXposed(true)）
 *   2) 勾选的模块按「免拷贝」方式装进 VA（package: URI，不复制 APK）
 *   3) 逐个 XposedModuleProfile.enableModule(pkg,true)
 *      —— VA 的 VAppManagerService 取模块列表时会按这个开关过滤，
 *         所以「没勾的就不会被 guest 进程加载」。
 *
 * 注意：VA 内的 XposedCompat 只认 assets/xposed_init（经典 API）。
 *       libxposed 新 API（LSPosed 101/102）模块即使勾上也加载不了，界面里已标灰。
 */
public class LspHooks {

    public static final String PREFS = "launcher_prefs";
    public static final String KEY_MODULES = "lsp_modules";

    public static Set<String> selected(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> s = p.getStringSet(KEY_MODULES, null);
        return s == null ? new HashSet<String>() : new HashSet<String>(s);
    }

    public static void saveSelection(Context c, Set<String> sel) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putStringSet(KEY_MODULES, new HashSet<String>(sel)).apply();
    }

    public static int selectedCount(Context c) {
        return selected(c).size();
    }

    /** 启动目标 App 之前调用（后台线程），任何异常都不允许影响启动 */
    public static void prepare(Context c, String targetPkg) {
        try {
            Set<String> sel = selected(c);
            App.log("LSP prepare: target=" + targetPkg + ", selected=" + sel.size() + " " + sel);
            if (sel.isEmpty()) {
                App.log("LSP prepare: nothing selected, xposed settings untouched");
                return;
            }
            // 引擎已换成 NPatch/Vector（见 LspVector，在 guest 进程里免打包启动），
            // VA 自带的那套 SandHook Xposed 因为缺 libsandhook.so 只会刷错误日志，这里显式关掉，
            // 避免两套框架互相踩。模块不再往 VA 里装，Vector 直接从系统已安装的模块 APK 读取。
            try {
                XposedModuleProfile.enbaleXposed(false);
            } catch (Throwable t) {
                App.log("LSP prepare: enbaleXposed(false) failed", t);
            }
            App.log("LSP prepare: vector engine will load " + sel.size() + " modules in guest, "
                    + "vaXposed=" + XposedModuleProfile.isXposedEnable());
            String cfg = writeModulesTxt(c, sel);
            App.log("LSP prepare: modules.txt=" + cfg);
        } catch (Throwable t) {
            App.log("LSP prepare failed (启动不受影响)", t);
        }
    }

    /**
     * 在宿主进程里把「勾选的模块包名 -> 宿主 APK 路径」写成清单文件。
     * 必须在这里做：guest 进程里的 PackageManager 是 VA 的虚拟 PM，看不到宿主装的模块，
     * 所以在 guest 里查 getApplicationInfo 会 NameNotFoundException。
     */
    public static String writeModulesTxt(Context c, Set<String> sel) {
        try {
            StringBuilder sb = new StringBuilder("# pkg\tapkPath\n");
            int n = 0;
            for (String pkg : sel) {
                try {
                    android.content.pm.ApplicationInfo ai =
                            c.getPackageManager().getApplicationInfo(pkg, 0);
                    if (ai == null || ai.sourceDir == null) continue;
                    if (!new java.io.File(ai.sourceDir).isFile()) continue;
                    sb.append(pkg).append('\t').append(ai.sourceDir).append('\n');
                    n++;
                } catch (Throwable t) {
                    App.log("LSP: module not installed as host apk: " + pkg);
                }
            }
            java.io.File dir = new java.io.File(c.getFilesDir(), "npatch");
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            java.io.File f = new java.io.File(dir, "modules.txt");
            java.io.FileOutputStream out = new java.io.FileOutputStream(f, false);
            try {
                out.write(sb.toString().getBytes("UTF-8"));
                out.flush();
            } finally {
                out.close();
            }
            App.log("LSP: modules.txt rows=" + n + " path=" + f.getAbsolutePath());
            return n == 0 ? null : f.getAbsolutePath();
        } catch (Throwable t) {
            App.log("LSP writeModulesTxt failed", t);
            return null;
        }
    }

    /** 取消勾选时立刻把 VA 里的开关关掉（模块仍留在 VA，但不会被加载） */
    public static void disable(String pkg) {
        try {
            XposedModuleProfile.enableModule(pkg, false);
            App.log("LSP enableModule(" + pkg + ")=false");
        } catch (Throwable t) {
            App.log("LSP disable " + pkg + " failed (VA 还没起来也没关系)", t);
        }
    }
}
