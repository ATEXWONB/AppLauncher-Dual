package com.applauncher.dual;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import com.carlos.common.App;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.ipc.VActivityManager;
import com.lody.virtual.client.ipc.VPackageManager;
import com.lody.virtual.remote.VAppInstallerParams;
import com.lody.virtual.remote.VAppInstallerResult;

import java.util.List;

/**
 * 「把模块自己也双开一份」：在分身里跑模块自己的设置界面。
 *
 * 为什么必须这样：模块界面的配置只有写在 getPrefsPath() 指向的安全区目录里，
 * 目标 App 的分身进程才读得到（见 LspPrefs 注释）。而这个重定向是框架在
 * 模块自己的进程被 hook 时才生效的（LegacyDelegateImpl.hookNewXSP），
 * 所以模块界面必须跑在我们的 guest 进程里，而不是系统里那个原生进程。
 *
 * 副作用是好事：模块在自己的界面里会检测到 Xposed 环境（XposedBridge 存在），
 * 于是显示「模块已激活」。
 */
public final class LspCloneLaunch {

    private static final String TAG = "LspCloneLaunch";

    /** 在后台线程调用。返回 true 表示已经拉起模块界面 */
    public static boolean openModuleUi(Context ctx, String pkg) {
        try {
            if (!VirtualCore.get().isAppInstalled(pkg)) {
                VAppInstallerParams params =
                        new VAppInstallerParams(VAppInstallerParams.MODE_FULL_INSTALL);
                VAppInstallerResult res = VirtualCore.get().installPackage(
                        Uri.parse("package:" + pkg), params);
                App.log("LspCloneLaunch: install " + pkg + " status="
                        + (res == null ? "null" : res.status));
                if (res == null || res.status != VAppInstallerResult.STATUS_SUCCESS) {
                    App.log("LspCloneLaunch: install failed for " + pkg);
                    return false;
                }
            }
            // 让 guest 进程里的框架知道要加载哪些模块（含模块自身）
            LspHooks.prepare(ctx, pkg);
            LspVector.preparePrefs(ctx);
            return launch(ctx, pkg);
        } catch (Throwable t) {
            App.log("LspCloneLaunch.openModuleUi failed", t);
            return false;
        }
    }

    private static boolean launch(Context ctx, String pkg) {
        try {
            Intent resolve = new Intent("android.intent.action.MAIN");
            resolve.addCategory("android.intent.category.LAUNCHER");
            resolve.setPackage(pkg);
            List<ResolveInfo> ris = VPackageManager.get().queryIntentActivities(
                    resolve, resolve.resolveType(ctx), 0, 0);
            if (ris == null || ris.isEmpty()) {
                resolve = new Intent("android.intent.action.MAIN");
                resolve.addCategory("android.intent.category.INFO");
                resolve.setPackage(pkg);
                ris = VPackageManager.get().queryIntentActivities(
                        resolve, resolve.resolveType(ctx), 0, 0);
            }
            if (ris == null || ris.isEmpty()) {
                App.log("LspCloneLaunch: no launcher activity for " + pkg);
                return false;
            }
            ActivityInfo info = ris.get(0).activityInfo;
            Intent intent = new Intent(resolve);
            intent.setFlags(0x10000000);
            intent.setClassName(info.packageName, info.name);
            int r = VActivityManager.get().startActivity(intent, 0);
            App.log("LspCloneLaunch: startActivity " + info.packageName + "/" + info.name + " ret=" + r);
            return r == 0;
        } catch (Throwable t) {
            App.log("LspCloneLaunch: launch failed", t);
            return false;
        }
    }

    /** 模块在分身里有没有装过 */
    public static boolean installedInVa(String pkg) {
        try {
            return VirtualCore.get().isAppInstalled(pkg);
        } catch (Throwable t) {
            return false;
        }
    }
}
