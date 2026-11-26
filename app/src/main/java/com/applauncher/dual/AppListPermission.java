package com.applauncher.dual;

import com.carlos.common.App;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.Settings;

import java.util.List;

/**
 * 「获取应用列表」权限的探测与跳转。
 *
 * 说明：Android 原生只有 QUERY_ALL_PACKAGES（清单声明，无运行时弹窗），
 * 但 MIUI/HyperOS、ColorOS 等 ROM 额外做了个开关「获取应用列表」，
 * 关掉时 getInstalledApplications() 只会返回自己。
 * 这里只做「探测 + 跳转授权页」，不阻塞任何功能：
 *  - 用户拒绝/取消 → 列表页可手动输入包名，SO 选择页可手动输入路径。
 */
public final class AppListPermission {

    private AppListPermission() { }

    /** 能不能看到除自己以外的应用（粗略判断，>= 2 个 launcher 就算有权限） */
    public static boolean canSeeOthers(Context ctx) {
        try {
            PackageManager pm = ctx.getApplicationContext().getPackageManager();
            Intent launcher = new Intent(Intent.ACTION_MAIN);
            launcher.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> ris = pm.queryIntentActivities(launcher, Compat.matchAll());
            int n = ris == null ? 0 : ris.size();
            if (n >= 2) return true;
            List<ApplicationInfo> apps = pm.getInstalledApplications(Compat.matchAll());
            if (apps != null && apps.size() >= 6) return true;
            App.log("AppListPermission: canSeeOthers=false (launcher=" + n
                    + " apps=" + (apps == null ? -1 : apps.size()) + ")");
            return false;
        } catch (Throwable t) {
            App.log("AppListPermission: probe failed", t);
            return false;
        }
    }

    /** 打开本应用的权限页（MIUI/HyperOS 优先，失败退回应用详情页） */
    public static boolean openSettings(Activity a) {
        // 1) MIUI / HyperOS 权限编辑页
        try {
            Intent miui = new Intent("miui.intent.action.APP_PERM_EDITOR");
            miui.setComponent(new ComponentName("com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity"));
            miui.putExtra("extra_pkgname", a.getPackageName());
            a.startActivity(miui);
            App.log("AppListPermission: opened MIUI permission editor");
            return true;
        } catch (Throwable t) {
            App.log("AppListPermission: MIUI editor failed: " + t.getMessage());
        }
        // 2) ColorOS / 通用：应用详情（里面通常有「应用权限」入口）
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + a.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
            App.log("AppListPermission: opened app details settings");
            return true;
        } catch (Throwable t) {
            App.log("AppListPermission: app details failed: " + t.getMessage());
        }
        // 3) 最后：权限总页
        try {
            a.startActivity(new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));
            return true;
        } catch (Throwable t) {
            App.log("AppListPermission: manage apps failed: " + t.getMessage());
            return false;
        }
    }
}
