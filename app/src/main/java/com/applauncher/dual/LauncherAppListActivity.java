package com.applauncher.dual;

import com.carlos.R;
import com.carlos.common.App;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 应用选择页：主通道是 PackageManager 权限列表（QUERY_ALL_PACKAGES），
 * 另外叠加 LAUNCHER intent 查询、pm list packages -f、安装目录扫描三重兜底。
 * 默认只显示 com.mojang.minecraftpe，「显示全部应用」可看全部。
 */
public class LauncherAppListActivity extends Activity {

    public static final String EXTRA_PACKAGE = "selected_package";
    public static final String EXTRA_LABEL = "selected_label";
    public static final String EXTRA_APK_PATH = "selected_apk_path";
    public static final String TARGET_PACKAGE = "com.mojang.minecraftpe";

    private final ArrayList<InstalledAppScanner.Entry> all = new ArrayList<>();
    private final ArrayList<InstalledAppScanner.Entry> items = new ArrayList<>();
    private boolean showAll = false;
    private boolean promptShown = false;
    private TextView tvTitle;
    private Button btnShowAll;
    private View loading;
    private LinearLayout permBar;
    private EditText etSearch;
    private String query = "";
    private AppAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.launcher_app_list);
        tvTitle = findViewById(R.id.tv_title);
        btnShowAll = findViewById(R.id.btn_show_all);
        loading = findViewById(R.id.loading);
        permBar = findViewById(R.id.perm_bar);
        btnShowAll.setOnClickListener(v -> { showAll = !showAll; refresh(); });
        etSearch = findViewById(R.id.et_search);
        etSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                String q = s == null ? "" : s.toString().trim();
                if (!q.equals(query)) {
                    query = q;
                    refresh();
                }
            }
        });
        findViewById(R.id.btn_perm).setOnClickListener(v -> {
            if (!AppListPermission.openSettings(this))
                darkToast("打不开权限页，请到「设置-应用-权限」里手动开");
        });
        findViewById(R.id.btn_manual_pkg).setOnClickListener(v -> showManualPkgDialog());
        adapter = new AppAdapter();
        ListView list = findViewById(R.id.list);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, v, pos, id) -> {
            InstalledAppScanner.Entry it = items.get(pos);
            Intent result = new Intent();
            result.putExtra(EXTRA_PACKAGE, it.pkg);
            result.putExtra(EXTRA_LABEL, it.label);
            result.putExtra(EXTRA_APK_PATH, it.apkPath);
            App.log("AppList selected " + it.pkg + " apk=" + it.apkPath
                    + " lib=" + (it.libOnDisk || it.libInApk));
            setResult(RESULT_OK, result);
            finish();
        });
        showLoading(true);
        InstalledAppScanner.invalidate();
        refresh();
        loadAsync();
    }

    private boolean resumedOnce = false;

    @Override
    protected void onResume() {
        super.onResume();
        // 去权限页授权后回来，重新拉一次列表（第一次进入不重复跑）
        if (resumedOnce) {
            promptShown = false;
            InstalledAppScanner.invalidate();
            showLoading(true);
            loadAsync();
        }
        resumedOnce = true;
    }

    private void showLoading(boolean show) {
        if (loading != null) loading.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void loadAsync() {
        new Thread(() -> {
            ArrayList<InstalledAppScanner.Entry> pmList = loadFromPackageManager();
            runOnUiThread(() -> {
                all.clear();
                all.addAll(pmList);
                sortAll();
                showLoading(false);
                refresh();
                onFirstBatch();
            });
            // 文件系统扫描只做补充：不覆盖权限列表结果；pm list packages -f 也在其中
            try {
                List<InstalledAppScanner.Entry> fs = InstalledAppScanner.scan(getApplicationContext());
                ArrayList<InstalledAppScanner.Entry> extra = new ArrayList<>();
                for (InstalledAppScanner.Entry e : fs) {
                    boolean seen = false;
                    for (InstalledAppScanner.Entry p : pmList) {
                        if (e.pkg.equals(p.pkg)) { seen = true; break; }
                    }
                    if (!seen) extra.add(e);
                }
                App.log("AppList: scanner added " + extra.size() + " extra packages");
                if (!extra.isEmpty()) {
                    runOnUiThread(() -> {
                        for (InstalledAppScanner.Entry e : extra) {
                            boolean seen = false;
                            for (InstalledAppScanner.Entry p : all) {
                                if (e.pkg.equals(p.pkg)) { seen = true; break; }
                            }
                            if (!seen) all.add(e);
                        }
                        sortAll();
                        refresh();
                    });
                }
            } catch (Throwable t) {
                App.log("AppList: scanner supplement FAILED", t);
            }
        }, "load-app-list").start();
    }

    private void sortAll() {
        Collections.sort(all, (a, b) -> {
            String x = a.label == null ? a.pkg : a.label;
            String y = b.label == null ? b.pkg : b.label;
            return x.compareToIgnoreCase(y);
        });
    }

    /** 第一批结果回来后：列表空就提示权限，只提示一次 */
    private void onFirstBatch() {
        boolean empty = all.isEmpty();
        permBar.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (!empty || promptShown) return;
        promptShown = true;
        if (AppListPermission.canSeeOthers(this)) return;
        new AlertDialog.Builder(this)
                .setTitle("需要「获取应用列表」权限")
                .setMessage("系统现在只让本应用看到它自己，所以列表是空的。\n"
                        + "开启「获取应用列表」权限后返回即可刷出来；也可以先不管，手动输入包名继续用。")
                .setPositiveButton("去开启", (d, w) -> {
                    if (!AppListPermission.openSettings(this))
                        darkToast("打不开权限页，请到设置里手动开");
                })
                .setNegativeButton("手动输入包名", (d, w) -> showManualPkgDialog())
                .setNeutralButton("以后再说", null)
                .show();
    }

    /** 列表拿不到时的手动兜底：直接输入包名 */
    private void showManualPkgDialog() {
        final EditText et = new EditText(this);
        et.setHint("com.mojang.minecraftpe");
        et.setTextColor(0xFFECEFF1);
        et.setHintTextColor(0xFF90A4AE);
        et.setBackgroundColor(0xFF1E1E1E);
        new AlertDialog.Builder(this)
                .setTitle("手动输入应用包名")
                .setView(et)
                .setPositiveButton("确定", (d, w) -> {
                    String pkg = et.getText().toString().trim();
                    if (pkg.isEmpty() || pkg.contains(" ")) { darkToast("包名不合法"); return; }
                    App.log("AppList manual pkg: " + pkg);
                    String apk = InstalledAppScanner.findApkPath(this, pkg, null);
                    Intent result = new Intent();
                    result.putExtra(EXTRA_PACKAGE, pkg);
                    result.putExtra(EXTRA_LABEL, pkg);
                    result.putExtra(EXTRA_APK_PATH, apk);
                    setResult(RESULT_OK, result);
                    finish();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private ArrayList<InstalledAppScanner.Entry> loadFromPackageManager() {
        // 必须用未 hook 的 PackageManager：VA 在宿主进程里也 hook 了 IPackageManager，
        // 用 this.getPackageManager() 只会返回「已分身」的包，第一次打开时就是空列表。
        PackageManager pm = InstalledAppScanner.pickPackageManager(this);
        LinkedHashMap<String, InstalledAppScanner.Entry> map = new LinkedHashMap<>();

        // 通道 1：QUERY_ALL_PACKAGES 权限列表（主通道）
        try {
            List<ApplicationInfo> list = pm.getInstalledApplications(Compat.matchAll());
            App.log("AppList: getInstalledApplications(MATCH_ALL) -> " + (list == null ? "null" : list.size()));
            if (list == null || list.isEmpty()) {
                list = pm.getInstalledApplications(0);
                App.log("AppList: getInstalledApplications(0) -> " + (list == null ? "null" : list.size()));
            }
            if (list != null) for (ApplicationInfo ai : list) add(map, pm, ai.packageName, ai);
        } catch (Throwable t) {
            App.log("AppList: getInstalledApplications FAILED", t);
        }

        // 通道 2：LAUNCHER intent 查询，QUERY_ALL_PACKAGES 被 ROM 拦截时仍能拿到可启动应用
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> ris = pm.queryIntentActivities(i, Compat.matchAll());
            App.log("AppList: queryIntentActivities(LAUNCHER) -> " + (ris == null ? "null" : ris.size()));
            if (ris != null) for (ResolveInfo ri : ris) {
                if (ri.activityInfo != null) {
                    add(map, pm, ri.activityInfo.packageName, ri.activityInfo.applicationInfo);
                }
            }
        } catch (Throwable t) {
            App.log("AppList: queryIntentActivities FAILED", t);
        }

        // 通道 3：getInstalledPackages
        try {
            List<android.content.pm.PackageInfo> pis = pm.getInstalledPackages(Compat.matchAll());
            App.log("AppList: getInstalledPackages(MATCH_ALL) -> " + (pis == null ? "null" : pis.size()));
            if (pis != null) for (android.content.pm.PackageInfo pi : pis) {
                add(map, pm, pi.packageName, pi.applicationInfo);
            }
        } catch (Throwable t) {
            App.log("AppList: getInstalledPackages FAILED", t);
        }

        // 通道 4：目标包强制补一次
        try {
            ApplicationInfo ai = pm.getApplicationInfo(TARGET_PACKAGE, 0);
            add(map, pm, TARGET_PACKAGE, ai);
            App.log("AppList: explicit target " + TARGET_PACKAGE + " apk="
                    + (ai == null ? "null" : ai.sourceDir));
        } catch (Throwable t) {
            App.log("AppList: explicit target not visible (" + t.getMessage() + ")");
        }

        ArrayList<InstalledAppScanner.Entry> out = new ArrayList<>(map.values());
        Collections.sort(out, (a, b) -> {
            String x = a.label == null ? a.pkg : a.label;
            String y = b.label == null ? b.pkg : b.label;
            return x.compareToIgnoreCase(y);
        });
        App.log("AppList: PM total -> " + out.size() + " packages");
        return out;
    }

    private void add(Map<String, InstalledAppScanner.Entry> map, PackageManager pm,
                     String pkg, ApplicationInfo ai) {
        if (pkg == null || map.containsKey(pkg)) return;
        InstalledAppScanner.Entry e = new InstalledAppScanner.Entry();
        e.pkg = pkg;
        e.label = pkg;
        e.source = 3;
        if (ai != null) {
            e.apkPath = ai.sourceDir;
            if (ai.sourceDir != null) e.appDir = new File(ai.sourceDir).getParent();
            e.system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            try { e.label = ai.loadLabel(pm).toString(); } catch (Throwable ignored) { }
            try { e.icon = ai.loadIcon(pm); } catch (Throwable ignored) { }
            // lib 直接读手机上已有的 nativeLibraryDir，不复制
            if (ai.nativeLibraryDir != null) {
                File d = new File(ai.nativeLibraryDir);
                File[] sos = d.listFiles((dir, n) -> n.toLowerCase(Locale.US).endsWith(".so"));
                if (sos != null) {
                    for (File so : sos) { e.libOnDisk = true; e.soFiles.add(so.getAbsolutePath()); }
                }
            }
        }
        map.put(pkg, e);
    }

    private void refresh() {
        items.clear();
        String q = query.toLowerCase(Locale.US);
        for (InstalledAppScanner.Entry e : all) {
            if (!q.isEmpty()) {
                // 搜索：在已加载的全部应用里找，应用名或包名包含即可，忽略大小写
                String label = e.label == null ? "" : e.label.toLowerCase(Locale.US);
                String pkg = e.pkg == null ? "" : e.pkg.toLowerCase(Locale.US);
                if (!label.contains(q) && !pkg.contains(q)) continue;
            } else if (!(showAll || TARGET_PACKAGE.equals(e.pkg))) {
                continue;
            }
            items.add(e);
        }
        if (!q.isEmpty()) {
            tvTitle.setText("搜索「" + query + "」 " + items.size() + " 个 / 共 " + all.size() + " 个");
            btnShowAll.setText(showAll ? "只看目标应用" : "显示全部应用 (" + all.size() + ")");
        } else if (showAll) {
            tvTitle.setText("全部应用 " + items.size() + " 个 (权限列表 + 三重兜底)");
            btnShowAll.setText("只看目标应用");
        } else {
            tvTitle.setText("选择应用 (仅显示 com.mojang.minecraftpe)");
            btnShowAll.setText("显示全部应用 (" + all.size() + ")");
        }
        adapter.notifyDataSetChanged();
        if (all.isEmpty()) permBar.setVisibility(View.VISIBLE);
    }

    private void darkToast(String msg) {
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

    private class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int p) { return items.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public View getView(int p, View cv, ViewGroup parent) {
            View v = cv;
            if (v == null) {
                v = LayoutInflater.from(LauncherAppListActivity.this)
                        .inflate(R.layout.launcher_app_item, parent, false);
            }
            InstalledAppScanner.Entry it = items.get(p);
            ImageView iv = v.findViewById(R.id.iv_icon);
            iv.setImageDrawable(it.icon != null ? it.icon : getPackageManager().getDefaultActivityIcon());
            ((TextView) v.findViewById(R.id.tv_name))
                    .setText((it.label == null ? it.pkg : it.label) + (it.system ? "  [系统]" : ""));
            String nl = String.valueOf((char) 10);
            StringBuilder sb = new StringBuilder(it.pkg);
            if (it.apkPath != null) sb.append(nl).append(it.apkPath);
            sb.append(nl).append("lib: ");
            if (it.libOnDisk) sb.append("本机 ").append(it.soFiles.size()).append(" 个 .so  ");
            if (it.libInApk) sb.append("APK内 ").append(it.soInApk.size()).append(" 个 .so");
            if (!it.libOnDisk && !it.libInApk) sb.append("无");
            ((TextView) v.findViewById(R.id.tv_pkg)).setText(sb.toString());
            return v;
        }
    }
}
