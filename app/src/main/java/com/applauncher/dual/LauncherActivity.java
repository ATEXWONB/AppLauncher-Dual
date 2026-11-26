package com.applauncher.dual;

import com.carlos.R;
import com.carlos.common.App;
import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Looper;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.ipc.VActivityManager;
import com.lody.virtual.client.ipc.VPackageManager;
import com.lody.virtual.os.VUserManager;
import com.lody.virtual.remote.InstalledAppInfo;
import com.lody.virtual.remote.VAppInstallerParams;
import com.lody.virtual.remote.VAppInstallerResult;

/**
 * 启动器主界面：
 *  - 记住上次选择的 App 和 SO，进入即可直接点“启动双开”
 *  - 单分身：只启动 user0 一个虚拟进程，绝不创建 user1 / 第二个克隆
 *  - 启动后延迟执行 SO 热加载（root: TInjector ptrace；无 root: VA 进程内 System.load 降级）
 *  - Toast 只在最终结果弹一次，中间过程全部写 files/log.txt
 */
public class LauncherActivity extends Activity {

    private static final int REQ_SELECT_APP = 2001;
    private static final int REQ_SELECT_SO = 2002;
    private static final int REQ_PERMS = 3001;
    private static final String PKG = "com.mojang.minecraftpe";
    private static final String PREFS = "launcher_prefs";
    private static final String KEY_PKG = "saved_package";
    private static final String KEY_LABEL = "saved_label";
    private static final String KEY_SO_NAME = "saved_so_name";
    private static final String KEY_APK_PATH = "saved_apk_path";
    private static final String KEY_DISCLAIMER = "disclaimer_accepted";

    private TextView tvSelected, tvSo, tvInfo;
    private Button btnLaunch, btnInject, btnLsp;
    private final AtomicBoolean launchInFlight = new AtomicBoolean(false);
    private volatile long lastLaunchAt = 0L;
    private String selectedPkg, selectedLabel, soName;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.launcher_main);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        App.log("LauncherActivity env: " + Compat.deviceLine());

        tvSelected = findViewById(R.id.tv_selected);
        tvSo = findViewById(R.id.tv_so);
        tvInfo = findViewById(R.id.tv_info);
        btnLaunch = findViewById(R.id.btn_launch);
        btnInject = findViewById(R.id.btn_inject);
        btnLsp = findViewById(R.id.btn_lsp);
        Button btnSelect = findViewById(R.id.btn_select);
        Button btnSelectSo = findViewById(R.id.btn_select_so);

        // 恢复上次选择
        selectedPkg = prefs.getString(KEY_PKG, null);
        selectedLabel = prefs.getString(KEY_LABEL, null);
        soName = prefs.getString(KEY_SO_NAME, null);
        if (selectedPkg == null) selectedPkg = PKG;
        if (!BuiltinSo.isCustom(this)) {
            // 默认就用 apk 里内置的 libmchook.so，用户不选也能直接启动
            soName = BuiltinSo.DISPLAY;
            ensureBuiltinSoAsync(false);
        }
        updateInjectUI();
        if (btnInject != null) btnInject.setOnClickListener(v -> setInjectEnabled(!injectEnabled()));
        if (btnLsp != null) btnLsp.setOnClickListener(v -> {
            startActivity(new Intent(this, LspModuleActivity.class));
        });
        Button btnDataDir = findViewById(R.id.btn_datadir);
        if (btnDataDir != null) {
            updateDataDirUI(btnDataDir);
            btnDataDir.setOnClickListener(v -> showDataDirDialog(btnDataDir));
        }
        Button btnAbout = findViewById(R.id.btn_about);
        if (btnAbout != null) btnAbout.setOnClickListener(v -> {
            try {
                startActivity(new Intent(this, AboutActivity.class));
            } catch (Throwable t) {
                App.log("open about failed", t);
            }
        });
        updateUI();

        btnSelect.setOnClickListener(v -> {
            Intent i = new Intent(this, LauncherAppListActivity.class);
            startActivityForResult(i, REQ_SELECT_APP);
        });
        btnSelectSo.setOnClickListener(v -> {
            // 用自带文件浏览页，绕开系统文件选择器对 .so 的 MIME 过滤（很多机器上会显示成空目录）
            Intent i = new Intent(this, SoPickerActivity.class);
            startActivityForResult(i, REQ_SELECT_SO);
        });
        btnLaunch.setOnClickListener(v -> launchDual(false));
        // 长按 = 明确要重启分身；普通点击永远只保留一个
        btnLaunch.setOnLongClickListener(v -> { launchDual(true); return true; });

        requestPermissionsIfNeeded();
        showDisclaimerIfNeeded();
    }

    /** 首次进入：强制阅读免责声明，10 秒倒计时结束后「确定」才从灰变蓝可点 */
    private void showDisclaimerIfNeeded() {
        if (prefs.getBoolean(KEY_DISCLAIMER, false)) {
            maybeAskAppListPermission();
            return;
        }
        final android.app.Dialog dlg = new android.app.Dialog(this);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dlg.setContentView(R.layout.dialog_disclaimer);
        dlg.setCancelable(false);
        dlg.setCanceledOnTouchOutside(false);
        android.view.Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.92f);
            w.setLayout(width, android.view.WindowManager.LayoutParams.WRAP_CONTENT);
            w.setGravity(android.view.Gravity.CENTER);
        }

        final Button agree = dlg.findViewById(R.id.btn_disc_agree);
        styleAgreeButton(agree, false);
        final android.os.Handler handler = new android.os.Handler(Looper.getMainLooper());
        final int[] left = {10};
        Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed() || !dlg.isShowing()) return;
                if (left[0] > 0) {
                    agree.setText("确定（" + left[0] + " 秒）");
                    left[0]--;
                    handler.postDelayed(this, 1000);
                } else {
                    agree.setText("确定");
                    agree.setEnabled(true);
                    styleAgreeButton(agree, true);
                }
            }
        };
        handler.post(tick);

        agree.setOnClickListener(v -> {
            prefs.edit().putBoolean(KEY_DISCLAIMER, true).apply();
            try { dlg.dismiss(); } catch (Throwable ignored) { }
            maybeAskAppListPermission();
        });
        try {
            dlg.show();
        } catch (Throwable t) {
            // 弹窗失败不能把用户卡死在首屏：直接放行，下次启动再尝试
            App.log("disclaimer show failed", t);
            maybeAskAppListPermission();
        }
    }

    private void styleAgreeButton(Button b, boolean enabled) {
        if (b == null) return;
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setCornerRadius(getResources().getDisplayMetrics().density * 10);
        g.setColor(enabled ? 0xFF1E88E5 : 0xFF3C3C3C);
        b.setBackground(g);
        b.setTextColor(enabled ? 0xFFFFFFFF : 0xFF9E9E9E);
    }

    /** 首次进来自检「获取应用列表」权限，只问一次；拒绝也不影响其它功能 */
    private void maybeAskAppListPermission() {
        if (prefs.getBoolean("asked_applist_perm", false)) return;
        if (AppListPermission.canSeeOthers(this)) {
            prefs.edit().putBoolean("asked_applist_perm", true).apply();
            return;
        }
        prefs.edit().putBoolean("asked_applist_perm", true).apply();
        new android.app.AlertDialog.Builder(this)
                .setTitle("需要「获取应用列表」权限")
                .setMessage("系统现在只让本应用看到它自己，应用列表会是空的。\n"
                        + "开启「获取应用列表」权限后就能直接选应用；不开启也可以手动输入包名，"
                        + "其它功能照常。")
                .setPositiveButton("去开启", (d, w) -> {
                    if (!AppListPermission.openSettings(this))
                        toastOnce("打不开权限页，请到「设置-应用-权限」里手动开");
                })
                .setNegativeButton("以后再说", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        try { updateUI(); } catch (Throwable ignored) { }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        if (req == REQ_SELECT_APP) {
            String pkg = data.getStringExtra(LauncherAppListActivity.EXTRA_PACKAGE);
            String label = data.getStringExtra(LauncherAppListActivity.EXTRA_LABEL);
            if (pkg != null) {
                selectedPkg = pkg;
                selectedLabel = label;
                String apkPath = data.getStringExtra(LauncherAppListActivity.EXTRA_APK_PATH);
                prefs.edit().putString(KEY_PKG, pkg).putString(KEY_LABEL, label)
                        .putString(KEY_APK_PATH, apkPath).apply();
                App.log("selected app saved: " + pkg + " apk=" + apkPath);
                updateUI();
            }
        } else if (req == REQ_SELECT_SO) {
            if (data.getBooleanExtra(SoPickerActivity.EXTRA_BUILTIN, false)) {
                useBuiltinSo();
                return;
            }
            String path = data.getStringExtra(SoPickerActivity.EXTRA_SO_PATH);
            if (path != null && !path.isEmpty()) {
                copySoFromPath(path);
            } else {
                // 兼容旧的系统文件选择器返回值
                Uri uri = data.getData();
                if (uri != null) copySoToCache(uri);
            }
        }
    }

    /**
     * 数据目录开关。内部私有目录（/data/user/0/...）兼容性最好；
     * 切到 Android/media 后文件管理器能直接看到，但 media 走 FUSE，
     * Minecraft 这类用 LevelDB 存档的游戏会报 "IO error: Invalid argument (22)" 进不去世界。
     */
    private boolean extMediaDir() {
        return prefs.getBoolean("ext_media_dir", false);
    }

    private void updateDataDirUI(Button b) {
        b.setText(extMediaDir() ? "数据目录: Android/media（可见）" : "数据目录: 内部私有（兼容最佳）");
        b.setTextColor(extMediaDir() ? 0xFF80CBC4 : 0xFFFFCC80);
    }

    private void showDataDirDialog(Button b) {
        boolean on = extMediaDir();
        String cur = on
                ? "/storage/emulated/0/Android/media/com.applauncher.dual"
                : "/data/user/0/com.applauncher.dual/files/vm";
        String msg = "当前: " + cur + "\n\n"
                + "外部（Android/media）优点：文件管理器/电脑能直接打开分身数据。\n"
                + "缺点：Android/media 走 FUSE，Minecraft（及所有用 LevelDB 存档的游戏）"
                + "在外部目录下会打不开世界（IO error 22），必须用内部目录才能进游戏。\n\n"
                + "切换到外部后，新的写入落到 media；旧数据不会自动搬家，需要的话手动拷。"
                + "切换对下次启动生效。";
        new android.app.AlertDialog.Builder(this)
                .setTitle(on ? "改用内部目录？" : "改用 Android/media？")
                .setMessage(msg)
                .setPositiveButton(on ? "改用内部" : "改用外部", (d, w) -> {
                    prefs.edit().putBoolean("ext_media_dir", !on).apply();
                    App.log("ext_media_dir -> " + !on);
                    updateDataDirUI(b);
                    toastOnce(!on ? "已切到 Android/media，重启分身后生效"
                            : "已切回内部目录，重启分身后生效");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 注入开关：某些游戏自带 bytehook/shadowhook，和 VA 的 native hook 冲突会 SIGSEGV，可临时关掉 */
    private boolean injectEnabled() {
        return prefs.getBoolean("inject_enabled", true);
    }

    private void setInjectEnabled(boolean on) {
        prefs.edit().putBoolean("inject_enabled", on).apply();
        if (!on) clearInjectFlag();
        App.log("inject_enabled -> " + on);
        updateInjectUI();
        toastOnce(on ? "注入已开启" : "注入已关闭，本次启动不加载 SO");
    }

    private void clearInjectFlag() {
        for (File dir : new File[]{getCacheDir(), getFilesDir()}) {
            try {
                File f = new File(dir, "auto_inject.so");
                if (f.exists()) App.log("clearInjectFlag delete=" + f.delete() + " " + f.getAbsolutePath());
            } catch (Throwable t) { App.log("clearInjectFlag failed", t); }
        }
    }

    private void updateInjectUI() {
        if (btnInject == null) return;
        boolean on = injectEnabled();
        btnInject.setText(on ? "注入: 开（点我关闭）" : "注入: 关（点我开启）");
        btnInject.setTextColor(on ? 0xFF69F0AE : 0xFFFF8A80);
    }

    /** 切回内置库（用户也可以随时选自己的库把内置库顶掉） */
    private void useBuiltinSo() {
        BuiltinSo.markBuiltin(this);
        soName = BuiltinSo.DISPLAY;
        updateUI();
        ensureBuiltinSoAsync(true);
    }

    /** 释放内置 libmchook.so 到 cache/temp.so + 写 auto_inject.so 标记 */
    private void ensureBuiltinSoAsync(boolean toast) {
        final Context ctx = getApplicationContext();
        new Thread(() -> {
            File f = BuiltinSo.ensure(ctx);
            App.log("ensureBuiltinSo: " + (f == null ? "null" : f.getAbsolutePath()
                    + " len=" + f.length() + " readable=" + f.canRead()));
            runOnUiThread(() -> {
                updateUI();
                if (toast) toastOnce("已使用内置库 " + BuiltinSo.ASSET);
            });
        }, "builtin-so").start();
    }

    private void copySoToCache(Uri uri) {
        final String srcDesc = String.valueOf(uri);
        final String displayName = getDisplayName(uri);
        new Thread(() -> {
            try {
                InputStream in = getContentResolver().openInputStream(uri);
                if (in == null) { App.log("openInputStream null: " + uri); return; }
                doCopySo(in, displayName, srcDesc);
            } catch (Throwable t) {
                App.log("copySoToCache FAILED", t);
                runOnUiThread(() -> toastOnce("SO 复制失败，看 log.txt"));
            }
        }, "copy-so").start();
    }

    // 自带文件浏览页返回绝对路径；启动器在这里再校验一次后缀必须是 .so
    private void copySoFromPath(String path) {
        File src = new File(path);
        final String displayName = src.getName();
        if (!displayName.toLowerCase(java.util.Locale.US).endsWith(".so")) {
            App.log("copySoFromPath reject non-so: " + path);
            toastOnce("只能选择 .so 动态库文件");
            return;
        }
        if (!src.isFile() || !src.canRead()) {
            App.log("copySoFromPath unreadable: " + path);
            toastOnce("SO 文件不可读，看 log.txt");
            return;
        }
        final String srcDesc = path;
        new Thread(() -> {
            try {
                doCopySo(new java.io.FileInputStream(path), displayName, srcDesc);
            } catch (Throwable t) {
                App.log("copySoFromPath FAILED", t);
                runOnUiThread(() -> toastOnce("SO 复制失败，看 log.txt"));
            }
        }, "copy-so").start();
    }

    private void doCopySo(InputStream in, String displayName, String srcDesc) throws Exception {
        File cache = getCacheDir();
        File files = getFilesDir();
        App.log("doCopySo: cache=" + cache.getAbsolutePath() + " exists=" + cache.exists()
                + " canWrite=" + cache.canWrite() + " | files=" + files.getAbsolutePath()
                + " exists=" + files.exists() + " canWrite=" + files.canWrite());

        File target = resolveWritableTarget(cache, files);
        FileOutputStream out = new FileOutputStream(target, false);
        byte[] buf = new byte[65536];
        int n, total = 0;
        while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); total += n; }
        out.flush(); out.close(); in.close();
        target.setReadable(true, false);

        writeFlag();
        BuiltinSo.markCustom(this);   // 用户自己选了库，内置默认库让位
        prefs.edit().putString(KEY_SO_NAME, displayName).apply();
        App.log("copySoToCache: " + srcDesc + " -> " + target.getAbsolutePath()
                + " (" + total + " bytes), name=" + displayName);
        runOnUiThread(() -> { soName = displayName; updateUI(); toastOnce("SO 已保存: " + displayName); });
    }

    /** cache/temp.so 为首选（用户指定路径）；不可写时删除重建，仍失败则回退 files/temp.so */
    private File resolveWritableTarget(File cache, File files) {
        File primary = new File(cache, "temp.so");
        try {
            if (primary.exists()) {
                App.log("target exists: len=" + primary.length()
                        + " canWrite=" + primary.canWrite() + " canRead=" + primary.canRead());
                if (!primary.canWrite()) {
                    boolean deleted = primary.delete();
                    App.log("target not writable, delete=" + deleted + " (root-owned leftover?)");
                }
            }
            if (!cache.exists()) App.log("cache.mkdirs=" + cache.mkdirs());
            FileOutputStream probe = new FileOutputStream(primary, false);
            probe.close();
            App.log("primary target OK: " + primary.getAbsolutePath());
            return primary;
        } catch (Throwable t) {
            App.log("primary target FAILED -> fallback to files/", t);
        }
        File fallback = new File(files, "temp.so");
        try {
            if (fallback.exists() && !fallback.canWrite()) fallback.delete();
            FileOutputStream probe = new FileOutputStream(fallback, false);
            probe.close();
            App.log("fallback target OK: " + fallback.getAbsolutePath());
            return fallback;
        } catch (Throwable t) {
            App.log("fallback target FAILED", t);
            throw new RuntimeException("no writable directory for temp.so", t);
        }
    }

    /** 开关标记两个目录都写，保证虚拟进程无论从哪读都能看到 */
    private void writeFlag() {
        for (File dir : new File[]{getCacheDir(), getFilesDir()}) {
            try {
                File f = new File(dir, "auto_inject.so");
                if (!f.exists()) { FileOutputStream o = new FileOutputStream(f); o.write(0); o.close(); }
                App.log("writeFlag: " + f.getAbsolutePath() + " exists=" + f.exists());
            } catch (Throwable t) { App.log("writeFlag failed in " + dir, t); }
        }
    }

    /** 优先 cache（用户指定路径），若 files 里的副本更新则用 files */
    private File findSoFile() {
        File a = new File(getCacheDir(), "temp.so");
        File b = new File(getFilesDir(), "temp.so");
        if (!a.exists()) return b;
        if (!b.exists()) return a;
        return (b.lastModified() > a.lastModified()) ? b : a;
    }

    private File findFlagFile() {
        File a = new File(getCacheDir(), "auto_inject.so");
        if (a.exists()) return a;
        return new File(getFilesDir(), "auto_inject.so");
    }

    private String getDisplayName(Uri uri) {
        String name = null;
        try {
            android.database.Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx);
                c.close();
            }
        } catch (Throwable ignored) {}
        if (name == null) {
            String p = uri.getPath();
            name = p == null ? "temp.so" : new File(p).getName();
        }
        return name;
    }

    private void updateUI() {
        tvSelected.setText((selectedLabel != null ? selectedLabel + "\n" : "") + selectedPkg);
        tvSo.setText("SO: " + (soName != null ? soName : "未选择"));
        try {
            int n = LspHooks.selectedCount(this);
            if (btnLsp != null) btnLsp.setText("LSP模块（已选 " + n + "）");
        } catch (Throwable ignored) { }
        try {
            String info = "模式: 单分身 (仅 user0)\n长按启动按钮=强制重启";
            info += "\n注入=" + (injectEnabled() ? "开" : "关");
            info += "\n库=" + (BuiltinSo.isCustom(this) ? "自定义" : "内置默认(" + BuiltinSo.ASSET + ")");
            File tmp = findSoFile();
            info += "\ntemp.so=" + (tmp.exists() ? (tmp.length()/1024) + "KB" : "无");
            info += "\n引擎=" + (VirtualCore.get().isEngineLaunched() ? "就绪" : "未启动");
            tvInfo.setText(info);
        } catch (Throwable t) { tvInfo.setText(""); }
    }

    // ===== 最终只弹一次 Toast；中间信息全部写 log.txt =====
    private void toastOnce(String msg) {
        App.log("TOAST: " + msg);
        if (Looper.myLooper() == Looper.getMainLooper()) showDarkToast(msg);
        else runOnUiThread(() -> showDarkToast(msg));
    }

    private void showDarkToast(String msg) {
        try {
            TextView tv = new TextView(this);
            tv.setText(msg);
            tv.setTextColor(0xFFECEFF1);
            tv.setTextSize(15);
            tv.setPadding(30, 20, 30, 20);
            tv.setBackgroundColor(android.graphics.Color.argb(240, 32, 32, 32));
            Toast t = new Toast(this);
            t.setDuration(Toast.LENGTH_SHORT);
            t.setView(tv);
            t.show();
        } catch (Throwable t) {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        }
    }

    // ===== VA 引擎/用户准备 =====
    private void ensureSecondUser() {
        try {
            int count = VUserManager.get().getUserCount();
            App.log("VA user count=" + count);
            if (count < 2) {
                com.lody.virtual.os.VUserInfo u = VUserManager.get().createUser("分身", 0);
                App.log("createUser(分身) -> " + (u == null ? "null" : ("id=" + u.id)));
            }
        } catch (Throwable t) { App.log("ensureSecondUser failed", t); }
    }

    /**
     * 启动前清理旧进程 —— 关键是「等它真的死掉」。
     * VA 的 killAppByPkg 是异步的，旧进程可能还在跑 native 析构（几秒）。
     * 以前只 sleep 300ms 就 startActivity，VA 会以为该包进程还活着，
     * 只是把旧任务提到前台 → 旧进程随后死掉 → 表现就是「第一次启动没反应，回启动器再点一次就好了」。
     */
    private void cleanStale(String pkg) {
        long t0 = android.os.SystemClock.uptimeMillis();
        int alive = findRunningGuestPid();
        if (alive <= 0) {
            App.log("cleanStale: no guest process, skip");
            return;
        }
        App.log("cleanStale: kill guest pid=" + alive);
        try { VActivityManager.get().killAppByPkg(pkg, 0); } catch (Throwable t) { App.log("kill user0 failed", t); }
        if (!waitGuestGone(4000)) {
            App.log("cleanStale: still alive after 4s, second kill");
            try { VActivityManager.get().killAppByPkg(pkg, 0); } catch (Throwable t) { App.log("kill user0 retry failed", t); }
            if (!waitGuestGone(2000)) {
                App.log("cleanStale: still alive, force proceed anyway");
            }
        }
        App.log("cleanStale done in " + (android.os.SystemClock.uptimeMillis() - t0) + "ms");
    }

    /** 轮询直到分身进程消失；返回 true=已消失 */
    private boolean waitGuestGone(long timeoutMs) {
        long t0 = android.os.SystemClock.uptimeMillis();
        while (android.os.SystemClock.uptimeMillis() - t0 < timeoutMs) {
            if (findRunningGuestPid() <= 0) return true;
            try { Thread.sleep(200); } catch (InterruptedException ignored) { }
        }
        return findRunningGuestPid() <= 0;
    }

    /** 轮询直到分身进程出现；返回 pid，超时返回 -1 */
    private int waitGuestPid(long timeoutMs) {
        long t0 = android.os.SystemClock.uptimeMillis();
        while (android.os.SystemClock.uptimeMillis() - t0 < timeoutMs) {
            int pid = findRunningGuestPid();
            if (pid > 0) {
                App.log("waitGuestPid: appeared pid=" + pid + " after "
                        + (android.os.SystemClock.uptimeMillis() - t0) + "ms");
                return pid;
            }
            try { Thread.sleep(250); } catch (InterruptedException ignored) { }
        }
        return -1;
    }

    /** 观察窗口内进程是否稳定存活；返回 true=稳定 */
    private boolean watchGuestStable(int pid, long windowMs, int gen) {
        long t0 = android.os.SystemClock.uptimeMillis();
        while (android.os.SystemClock.uptimeMillis() - t0 < windowMs) {
            if (launchGen != gen) {
                App.log("watchGuestStable: cancelled by user action");
                return true; // 用户手动接管了，不当作失败
            }
            int now = findRunningGuestPid();
            if (now <= 0) {
                App.log("watchGuestStable: pid=" + pid + " disappeared after "
                        + (android.os.SystemClock.uptimeMillis() - t0) + "ms");
                return false;
            }
            try { Thread.sleep(300); } catch (InterruptedException ignored) { }
        }
        App.log("watchGuestStable: alive after " + windowMs + "ms, ok");
        return true;
    }

    /** 引擎没就绪时先等它，别一上来就 startActivity（冷启动第一次最容易失败） */
    private boolean ensureEngineReady() {
        for (int i = 0; i < 6; i++) {
            try { if (VirtualCore.get().isEngineLaunched()) return true; } catch (Throwable t) { App.log("isEngineLaunched failed", t); }
            try {
                App.log("ensureEngineReady: waiting engine, round " + (i + 1));
                VirtualCore.get().waitForEngine();
            } catch (Throwable t) { App.log("waitForEngine failed", t); }
            try { if (VirtualCore.get().isEngineLaunched()) return true; } catch (Throwable ignored) { }
            try { Thread.sleep(1000); } catch (InterruptedException ignored) { }
        }
        App.log("ensureEngineReady: TIMEOUT, continue anyway");
        return false;
    }

    private void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    private int launchUserDirect(int userId, String pkg) {
        Context context = VirtualCore.get().getContext();
        VPackageManager pm = VPackageManager.get();
        Intent resolve = new Intent("android.intent.action.MAIN");
        resolve.addCategory("android.intent.category.INFO");
        resolve.setPackage(pkg);
        List<ResolveInfo> ris = pm.queryIntentActivities(resolve, resolve.resolveType(context), 0, userId);
        if (ris == null || ris.isEmpty()) {
            resolve = new Intent("android.intent.action.MAIN");
            resolve.addCategory("android.intent.category.LAUNCHER");
            resolve.setPackage(pkg);
            ris = pm.queryIntentActivities(resolve, resolve.resolveType(context), 0, userId);
        }
        if (ris == null || ris.isEmpty()) { App.log("resolve empty for user=" + userId); return -1; }
        ActivityInfo info = ris.get(0).activityInfo;
        App.log("resolved user=" + userId + " -> " + info.packageName + "/" + info.name);
        Intent intent = new Intent(resolve);
        intent.setFlags(0x10000000);
        intent.setClassName(info.packageName, info.name);
        int r = VActivityManager.get().startActivity(intent, userId);
        App.log("startActivity user=" + userId + " ret=" + r + " (0=START_SUCCESS)");
        return r;
    }

        private volatile int launchGen = 0;
    private static final int MAX_AUTO_RETRY = 3;

    private void setLaunchButtonEnabled(final boolean on) {
        runOnUiThread(() -> { if (btnLaunch != null) btnLaunch.setEnabled(on); });
    }

    /** 单分身启动：只保留 user0 的一个虚拟进程；失败自动重试，最多 3 次 */
    private void launchDual(boolean force) {
        long now = android.os.SystemClock.uptimeMillis();
        if (!launchInFlight.compareAndSet(false, true)) {
            App.log("launchDual IGNORED: already in flight (fast double tap)");
            return;
        }
        if (!force && now - lastLaunchAt < 1500L) {
            App.log("launchDual IGNORED: cooldown " + (now - lastLaunchAt) + "ms");
            launchInFlight.set(false);
            return;
        }
        lastLaunchAt = now;
        final int gen = ++launchGen;   // 用户每次点击都作废上一轮自动重试
        setLaunchButtonEnabled(false);

        final String pkg = selectedPkg;
        App.log("=== launchDual begin pkg=" + pkg + " force=" + force + " gen=" + gen + " (SINGLE USER0 ONLY) ===");

        // 已有同名分身进程在跑：弹窗问一下，确定就杀掉重启，取消就什么都不做
        int running = findRunningGuestPid();
        // 注意：模块自己的界面也是我们的 guest 进程（见 LspCloneLaunch），
        // 不能因为「随便有个 :pN 进程在跑」就弹重启，先向 VA 确认目标 App 自己是不是在跑。
        if (running > 0 && !isSelectedAppRunning(pkg)) {
            App.log("launchDual: pid=" + running + " 不是 " + pkg + " 的进程（可能是模块界面），忽略");
            running = -1;
        }
        if (!force && running > 0) {
            App.log("launchDual: guest already running pid=" + running + " -> ask restart");
            launchInFlight.set(false);
            final int pid = running;
            runOnUiThread(() -> {
                if (btnLaunch != null) btnLaunch.setEnabled(true);
                askRestartRunning(pid);
            });
            return;
        }

        new Thread(() -> {
            boolean ok = false;
            String why = "未知原因";
            int attempt = 0;
            try {
                // 进入工作线程就把 'in flight' 闩交出去：后续每一轮自己 CAS，
                // 用户中途手动点击会 bump gen 并 CAS 成功，自动重试自动让位。
                launchInFlight.set(false);
                ensureEngineReady();
                while (attempt < MAX_AUTO_RETRY) {
                    if (launchGen != gen) { App.log("launch loop: cancelled by user"); return; }
                    attempt++;
                    App.log("=== launch attempt " + attempt + "/" + MAX_AUTO_RETRY + " ===");
                    if (!launchInFlight.compareAndSet(false, true)) {
                        App.log("auto retry skipped: user took over");
                        return;
                    }
                    boolean started = launchOnce(gen, attempt);
                    launchInFlight.set(false);
                    setLaunchButtonEnabled(true);
                    if (launchGen != gen) return;
                    if (!started) {
                        why = "startActivity 被 VA 拒绝";
                        App.log("attempt " + attempt + ": " + why);
                        sleepQuiet(1000);
                        continue;
                    }
                    // 兜底：等进程真的出现，再观察一段时间确认它没被秒杀
                    int pid = waitGuestPid(8000);
                    if (pid <= 0) {
                        why = "8 秒内没有分身进程出现";
                        App.log("attempt " + attempt + ": " + why);
                        continue;
                    }
                    if (!watchGuestStable(pid, 6000, gen)) {
                        why = "进程起来后立刻消失（被 VA/系统杀掉或崩溃）";
                        App.log("attempt " + attempt + ": " + why);
                        continue;
                    }
                    if (injectEnabled()) verifyInjected(pid);
                    ok = true;
                    break;
                }
            } catch (Throwable t) {
                App.log("launchDual FAILED", t);
                why = "异常 " + t;
            } finally {
                launchInFlight.set(false);
                setLaunchButtonEnabled(true);
            }

            final boolean fok = ok;
            final int fattempt = attempt;
            final String fwhy = why;
            App.log("launch result: ok=" + fok + " attempts=" + fattempt + " why=" + fwhy);

            if (fok) {
                final String fpkg = pkg;
                new Thread(() -> {
                    sleepQuiet(2000);
                    doSoInject(fpkg);
                }, "inject-after-launch").start();
                runOnUiThread(() -> {
                    toastOnce("已启动 1 个分身 (user0)");
                    updateUI();
                });
            } else {
                runOnUiThread(() -> toastOnce("启动失败（自动重试 " + fattempt + " 次）：" + fwhy + "，请再手动点一次"));
            }
        }, "dual-launch").start();
    }

    /** 单次尝试：确认引擎 -> 清理旧克隆 -> 免拷贝安装 -> 清理残留进程 -> 拉起 */
    private boolean launchOnce(int gen, int attempt) {
        try {
            String pkg = selectedPkg;
            App.log("VA user count=" + VUserManager.get().getUserCount() + " (only user0 is used)");
            removeSecondUserLeftovers(pkg);

            String apkPath = resolveApkPath(pkg);
            App.log("apkPath=" + apkPath);

            boolean installed = VirtualCore.get().isAppInstalled(pkg);
            boolean dynamic = false;
            if (installed) {
                InstalledAppInfo info = VirtualCore.get().getInstalledAppInfo(pkg, 0);
                dynamic = info != null && info.dynamic;
                App.log("existing install: dynamic=" + dynamic);
            }
            if (!installed || !dynamic) {
                if (!installed) {
                    App.log("install(dynamic/no-copy) begin pkg=" + pkg);
                } else {
                    App.log("convert to dynamic(no-copy) pkg=" + pkg);
                }
                VAppInstallerParams params =
                        new VAppInstallerParams(VAppInstallerParams.MODE_FULL_INSTALL);
                if (installed) {
                    // 已装过（旧版本是拷贝模式）：跳过版本比较，强制转成 dynamic，数据目录保留
                    params.addInstallFlags(VAppInstallerParams.FLAG_INSTALL_OVERRIDE_NO_CHECK);
                }
                VAppInstallerResult res =
                        VirtualCore.get().installPackage(Uri.parse("package:" + pkg), params);
                App.log("install result: pkg=" + (res == null ? "null" : res.packageName)
                        + " status=" + (res == null ? "null" : res.status));
                if (res == null || res.status != VAppInstallerResult.STATUS_SUCCESS) {
                    final int st = res == null ? -1 : res.status;
                    runOnUiThread(() -> toastOnce("安装失败 status=" + st + "，看 log.txt"));
                    return false;
                }
            } else {
                App.log("already installed in VA as dynamic (no copy), single clone kept");
            }

            // 把勾选的 LSP/Xposed 模块灌进 VA 并打开开关（失败不影响启动）
            LspHooks.prepare(this, pkg);

            // 注入延迟自适应：库在 MC 初始化早期 hook 容易把它弄崩，
            // 第一次 2s，重试时依次往后挪到 3.5s / 5s，给目标 App 更长的初始化时间。
            try {
                if (injectEnabled()) {
                    int delay = 2000 + (attempt - 1) * 1500;
                    prefs.edit().putInt("inject_delay_ms", delay).apply();
                    InjectLog.w(this, "attempt=" + attempt + " inject_delay_ms=" + delay);
                }
            } catch (Throwable ignored) { }

            // 注入库：必须在 guest 进程创建之前把 flag/temp.so 备好，否则 guest 的 2s 检查点看不到
            prepareInjectBeforeLaunch();

            cleanStale(pkg);
            if (launchGen != gen) { App.log("launchOnce: cancelled before start"); return false; }

            int r = launchUserDirect(0, pkg);
            App.log("launch result: user0=" + (r == 0) + " ret=" + r + " (user1 NEVER launched)");
            return r == 0;
        } catch (Throwable t) {
            App.log("launchOnce FAILED", t);
            return false;
        }
    }
    private boolean restartDialogShown = false;

    /** 已有同名分身进程在跑时的二次确认：确定=杀掉重启，取消=什么都不做 */
    private void askRestartRunning(final int pid) {
        if (isFinishing() || isDestroyed() || restartDialogShown) return;
        restartDialogShown = true;
        final String who = (selectedLabel != null ? selectedLabel : selectedPkg);
        try {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("分身已经在运行")
                    .setMessage("「" + who + "」的分身进程正在运行 (pid=" + pid + ")。\n\n"
                            + "是否杀死当前正在运行的分身，并重新启动？")
                    .setPositiveButton("确定", (d, w) -> {
                        restartDialogShown = false;
                        App.log("askRestartRunning: confirmed -> kill pid=" + pid + " & relaunch");
                        launchDual(true);
                    })
                    .setNegativeButton("取消", (d, w) -> {
                        restartDialogShown = false;
                        App.log("askRestartRunning: cancelled by user, keep pid=" + pid);
                    })
                    .setOnCancelListener(d -> restartDialogShown = false)
                    .show();
        } catch (Throwable t) {
            restartDialogShown = false;
            App.log("askRestartRunning: show dialog failed", t);
        }
    }

    /** 目标 App 在 VA 的 user0 里到底有没有在跑（模块界面等其他 guest 不算） */
    private boolean isSelectedAppRunning(String pkg) {
        try {
            boolean r = VActivityManager.get().isAppRunning(pkg, 0, false);
            App.log("isAppRunning(" + pkg + ",0)=" + r);
            return r;
        } catch (Throwable t) {
            App.log("isAppRunning query failed, assume running", t);
            return true;   // 查不了就保持老行为（宁可多问一次）
        }
    }

    /** 找到正在运行的 VA 客户进程（普通点击时据此直接复用，绝不新建第二个） */
    private int findRunningGuestPid() {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            List<android.app.ActivityManager.RunningAppProcessInfo> ps = am.getRunningAppProcesses();
            if (ps != null) {
                for (android.app.ActivityManager.RunningAppProcessInfo i : ps) {
                    String n = i.processName;
                    if (n != null && n.matches(".*:p\\d+.*")) {
                        App.log("findRunningGuestPid: " + n + " pid=" + i.pid);
                        return i.pid;
                    }
                }
            }
        } catch (Throwable t) { App.log("findRunningGuestPid failed", t); }
        try {
            File[] dirs = new File("/proc").listFiles((d, n) -> n.matches("\\d+"));
            if (dirs != null) {
                for (File d : dirs) {
                    String cmd = readCmdline(new File(d, "cmdline"));
                    if (cmd.contains(":p0") || cmd.contains("(分身)")) {
                        int pid = Integer.parseInt(d.getName());
                        App.log("findRunningGuestPid via /proc: " + cmd + " pid=" + pid);
                        return pid;
                    }
                }
            }
        } catch (Throwable t) { App.log("proc scan failed", t); }
        return -1;
    }

    private String readCmdline(File f) {
        java.io.FileInputStream in = null;
        try {
            byte[] b = new byte[256];
            in = new java.io.FileInputStream(f);
            int n = in.read(b);
            if (n <= 0) return "";
            return new String(b, 0, n, "UTF-8").replace('\0', ' ').trim();
        } catch (Throwable t) { return ""; }
        finally { if (in != null) try { in.close(); } catch (Throwable ignored) { } }
    }

    /** 清掉历史遗留的 user1 克隆，保证以后永远只有一份分身数据 */
    private void removeSecondUserLeftovers(String pkg) {
        try {
            VActivityManager.get().killAppByPkg(pkg, 1);
            App.log("killAppByPkg(pkg,1) leftover done");
        } catch (Throwable t) { App.log("kill user1 leftover skipped", t); }
        try {
            boolean r = VirtualCore.get().uninstallPackageAsUser(pkg, 1);
            App.log("uninstallPackageAsUser(1," + pkg + ")=" + r + " (no second clone)");
        } catch (Throwable t) { App.log("uninstall user1 skipped", t); }
    }

    /** 主通道：系统 PackageManager（宿主进程未 hook）直接给 sourceDir */
    private String resolveApkPath(String pkg) {
        try {
            ApplicationInfo ai = VirtualCore.get().getHostPackageManager().getApplicationInfo(pkg, 0);
            if (ai != null && ai.sourceDir != null && new File(ai.sourceDir).isFile()) {
                App.log("resolveApkPath by host PM: " + ai.sourceDir);
                return ai.sourceDir;
            }
        } catch (Throwable t) {
            App.log("resolveApkPath host PM failed: " + t);
        }
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            if (ai != null && ai.sourceDir != null && new File(ai.sourceDir).isFile()) {
                App.log("resolveApkPath by pm: " + ai.sourceDir);
                return ai.sourceDir;
            }
        } catch (Throwable t) {
            App.log("resolveApkPath pm failed: " + t);
        }
        String saved = prefs.getString(KEY_APK_PATH, null);
        String found = InstalledAppScanner.findApkPath(this, pkg, saved);
        if (found != null) return found;
        App.log("resolveApkPath: scanner returned null, fallback to inline scan");
        String[] roots = {"/data/app", "/system/app", "/system/priv-app",
                "/system_ext/app", "/system_ext/priv-app", "/vendor/app",
                "/vendor/priv-app", "/product/app", "/product/priv-app"};
        for (String root : roots) {
            File top = new File(root);
            File[] l1 = top.listFiles();
            if (l1 == null) continue;
            for (File d : l1) {
                if (d.isDirectory() && d.getName().startsWith("~~")) {
                    File[] l2 = d.listFiles();
                    if (l2 == null) continue;
                    for (File inner : l2) {
                        String hit = matchApkDir(inner, pkg);
                        if (hit != null) { App.log("resolveApkPath by dir: " + hit); return hit; }
                    }
                } else {
                    String hit = matchApkDir(d, pkg);
                    if (hit != null) { App.log("resolveApkPath by dir: " + hit); return hit; }
                }
            }
        }
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            if (ai != null && ai.sourceDir != null) {
                App.log("resolveApkPath by PackageManager: " + ai.sourceDir);
                return ai.sourceDir;
            }
        } catch (Throwable t) { App.log("getApplicationInfo fallback failed", t); }
        throw new IllegalStateException("cannot locate base.apk for " + pkg);
    }

    private String matchApkDir(File d, String pkg) {
        if (!d.isDirectory()) return null;
        String n = d.getName();
        if (!n.equals(pkg) && !n.startsWith(pkg + "-")) return null;
        File base = new File(d, "base.apk");
        if (base.isFile()) return base.getAbsolutePath();
        File[] apks = d.listFiles((dir, name) -> name.endsWith(".apk"));
        if (apks != null && apks.length > 0) return apks[0].getAbsolutePath();
        return null;
    }


    /** 拉起 guest 之前就把注入条件备好：库文件就绪 + auto_inject.so 标记写好 */
    private void prepareInjectBeforeLaunch() {
        try {
            if (!injectEnabled()) {
                clearInjectFlag();
                InjectLog.w(this, "prepare: inject DISABLED, flag cleared");
                return;
            }
            if (!BuiltinSo.isCustom(this)) {
                BuiltinSo.ensure(getApplicationContext());
            }
            File so = findSoFile();
            if (!so.isFile() || so.length() <= 0) {
                InjectLog.w(this, "prepare: SKIP, temp.so missing " + so.getAbsolutePath());
                App.log("prepareInject: temp.so missing -> skip");
                return;
            }
            writeFlag();
            InjectLog.w(this, "prepare: OK so=" + so.getAbsolutePath() + " len=" + so.length()
                    + " custom=" + BuiltinSo.isCustom(this));
            App.log("prepareInject: ready so=" + so.getAbsolutePath() + " len=" + so.length());
        } catch (Throwable t) {
            InjectLog.w(this, "prepare: FAILED " + t);
            App.log("prepareInject failed", t);
        }
    }

    /** 启动后检查 guest 进程里到底有没有真的映射 temp.so（/proc/pid/maps） */
    private void verifyInjected(int pid) {
        String path = "";
        try {
            path = findSoFile().getAbsolutePath();
            boolean loaded = false;
            String hit = null;
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/" + pid + "/maps"));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.contains("temp.so")) { loaded = true; hit = line.trim(); break; }
                }
            } finally {
                try { r.close(); } catch (Throwable ignored) { }
            }
            InjectLog.w(this, "verify pid=" + pid + " temp.so mapped=" + loaded
                    + (hit != null ? " | " + hit : ""));
            App.log("verifyInject: pid=" + pid + " temp.so mapped=" + loaded);
            if (!loaded) {
                App.log("verifyInject: temp.so NOT mapped in pid=" + pid
                        + " (进程可能在注入前就已存在，或 System.load 失败，详见 inject.log)");
            }
        } catch (Throwable t) {
            InjectLog.w(this, "verify FAILED " + t + " path=" + path);
        }
    }

    // ===== SO 热加载：优先 root + TInjector，降级 VA 进程内 System.load =====
    private void doSoInject(String pkg) {
        if (!injectEnabled()) {
            App.log("doSoInject: DISABLED by user switch, skip loading SO");
            clearInjectFlag();
            return;
        }
        if (!BuiltinSo.isCustom(this)) {
            BuiltinSo.ensure(getApplicationContext());   // 数据被清过也照样有内置库
        }
        File so = findSoFile();
        File flag = findFlagFile();
        if (!flag.exists() || !so.exists()) {
            App.log("doSoInject: skip (no so selected)");
            return;
        }
        App.log("=== doSoInject begin pkg=" + pkg + " so=" + so.getAbsolutePath() + " ===");
        // 主路径：SoBootstrap 由 App.onCreate() 字节码注入触发，在虚拟进程内延迟 2 秒 System.load(temp.so)。
        // 这是唯一对 VA 沙盒进程肯定生效的路径（VA 进程由 VA 自己 fork，不经过系统 zygote）。
        App.log("doSoInject: in-process inject handled by SoBootstrap.onCreate hook (flag=" + flag.exists() + ")");
        // 附加尝试：若设备有 root，额外跑一次 TInjector 的 zygote 注入（对真实系统进程/未来新进程有效）。
        // 失败不影响主路径，纯粹多一层覆盖。
        if (hasRoot()) {
            injectViaTInjector(pkg, so);
        } else {
            App.log("doSoInject: no root, skip TInjector (in-process inject still active)");
        }
    }

    private boolean hasRoot() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/su", "-c", "id -u"});
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            r.close();
            boolean root = "0".equals(line == null ? null : line.trim());
            App.log("hasRoot=" + root + " (id -u=" + line + ")");
            return root;
        } catch (Throwable t) { App.log("hasRoot: no su (" + t.getMessage() + "), in-process inject still active"); return false; }
    }

    private void injectViaTInjector(String pkg, File so) {
        try {
            String nativeLibDir = getApplicationInfo().nativeLibraryDir;
            File tinjectorSrc = new File(nativeLibDir, "libtinjector.so");
            File work = new File(getCacheDir(), "tinjector");
            copyFile(tinjectorSrc, work);
            work.setExecutable(true, false);

            String libDir = getCacheDir().getAbsolutePath();
            String cmd = work.getAbsolutePath() + " -n " + pkg + " " + so.getAbsolutePath();
            App.log("injectViaTInjector cmd=" + cmd);
            Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/su", "-c",
                    "export LD_LIBRARY_PATH=" + nativeLibDir + ":" + libDir + " ; " + cmd});
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getErrorStream()));
            String line;
            while ((line = r.readLine()) != null) App.log("TInjector: " + line);
            r.close();
            int code = p.waitFor();
            App.log("injectViaTInjector exit=" + code);
        } catch (Throwable t) { App.log("injectViaTInjector FAILED", t); }
    }

    private void copyFile(File src, File dst) throws Exception {
        InputStream in = new java.io.FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst, false);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.flush(); out.close(); in.close();
        dst.setExecutable(true, false);
    }

    // ===== 首次权限自动申请 =====
    private void requestPermissionsIfNeeded() {
        boolean first = !prefs.getBoolean("perms_done", false);
        java.util.List<String> need = new java.util.ArrayList<>();
        // 通知权限每次启动都检查（用户可能在系统里撤销），其它权限只问一次
        if (Build.VERSION.SDK_INT >= 33) {
            if (!Compat.hasPostNotification(this)) need.add(Manifest.permission.POST_NOTIFICATIONS);
            if (first && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.READ_MEDIA_IMAGES);
        } else if (first) {
            if (!Compat.hasLegacyStorageRead(this))
                need.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        if (!need.isEmpty()) {
            ActivityCompat.requestPermissions(this, need.toArray(new String[0]), REQ_PERMS);
        }
        if (first) prefs.edit().putBoolean("perms_done", true).apply();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] grant) {
        super.onRequestPermissionsResult(req, perms, grant);
        if (req == REQ_PERMS) prefs.edit().putBoolean("perms_done", true).apply();
    }
}
