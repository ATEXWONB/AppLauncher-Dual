package com.applauncher.dual;

import com.carlos.R;
import com.carlos.common.App;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 自带 SO 文件浏览页。
 *  - 直接遍历内部存储目录，列出所有文件夹与文件（不按 MIME/类型过滤，.so 不会被隐藏成空目录）
 *  - 点击文件时才由启动器校验后缀必须是 .so，非 .so 拒绝并提示
 *  - 选中后返回绝对路径给 LauncherActivity
 */
public class SoPickerActivity extends Activity {

    public static final String EXTRA_SO_PATH = "so_path";
    public static final String EXTRA_BUILTIN = "use_builtin_so";

    private File root;
    private File current;
    private TextView tvPath;
    private ListView listView;
    private final ArrayList<File> items = new ArrayList<>();
    private FileAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.so_picker);

        tvPath = findViewById(R.id.tv_path);
        listView = findViewById(R.id.list);
        Button btnUp = findViewById(R.id.btn_up);
        btnUp.setOnClickListener(v -> goUp());
        Button btnManual = findViewById(R.id.btn_manual);
        btnManual.setOnClickListener(v -> showManualInput());
        Button btnAppLibs = findViewById(R.id.btn_app_libs);
        btnAppLibs.setOnClickListener(v -> showAppLibs());
        Button btnBuiltin = findViewById(R.id.btn_builtin);
        if (btnBuiltin != null) {
            btnBuiltin.setText(BuiltinSo.isCustom(this)
                    ? "切回内置库 " + BuiltinSo.ASSET + "（默认）"
                    : "内置库 " + BuiltinSo.ASSET + "（当前默认）");
            btnBuiltin.setOnClickListener(v -> {
                App.log("SoPicker: choose builtin " + BuiltinSo.ASSET);
                Intent data = new Intent();
                data.putExtra(EXTRA_BUILTIN, true);
                setResult(RESULT_OK, data);
                finish();
            });
        }

        root = Environment.getExternalStorageDirectory();
        current = root;

        adapter = new FileAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, pos, id) -> onClickItem(pos));

        checkStorageAccess();
        load(current);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] grant) {
        super.onRequestPermissionsResult(req, perms, grant);
        if (req == Compat.REQ_STORAGE) {
            if (Compat.hasLegacyStorageRead(this)) {
                App.log("SoPicker: storage granted, reload " + current);
                load(current);
            } else {
                App.log("SoPicker: storage denied");
                darkToast("没有存储权限，无法读取内部存储；可在系统设置里授予「存储」权限");
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (current != null) load(current);
    }

    /** 分版本申请存储访问：6.0~10 要运行时「存储」权限，11+ 要「所有文件访问」 */
    private void checkStorageAccess() {
        if (Compat.atLeast(30)) {
            checkAllFilesAccess();
            return;
        }
        // Android 6.0 ~ 10：没有 READ_EXTERNAL_STORAGE 就 listFiles() 返回 null
        if (!Compat.hasLegacyStorageRead(this)) {
            App.log("SoPicker: no READ_EXTERNAL_STORAGE yet (sdk=" + Build.VERSION.SDK_INT + "), requesting");
            Compat.askLegacyStorageRead(this, Compat.REQ_STORAGE);
        }
    }

    private void checkAllFilesAccess() {
        try {
            if (!Environment.isExternalStorageManager()) {
                new android.app.AlertDialog.Builder(this)
                        .setTitle("需要「所有文件访问」权限")
                        .setMessage("要浏览内部存储里的 .so 文件，请开启本应用的所有文件访问权限。")
                        .setPositiveButton("去开启", (d, w) -> {
                            try {
                                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        Uri.parse("package:" + getPackageName()));
                                startActivity(i);
                            } catch (Throwable t) {
                                try { startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); }
                                catch (Throwable t2) { App.log("open all-files-access setting failed", t2); }
                            }
                        })
                        .setNegativeButton("仍然浏览", null)
                        .show();
            }
        } catch (Throwable t) { App.log("checkAllFilesAccess failed", t); }
    }

    private void load(File dir) {
        items.clear();
        current = dir;
        tvPath.setText("当前目录: " + dir.getAbsolutePath());
        File[] fs = dir.listFiles();
        if (fs != null) {
            List<File> all = new ArrayList<>(Arrays.asList(fs));
            Collections.sort(all, (a, b) -> {
                boolean da = a.isDirectory(), db = b.isDirectory();
                if (da != db) return da ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            });
            items.addAll(all);
            App.log("SoPicker load " + dir.getAbsolutePath() + " -> " + items.size() + " entries");
        } else {
            App.log("SoPicker load " + dir.getAbsolutePath() + " -> listFiles() null (permission?)");
            darkToast("无法读取该目录（检查所有文件访问权限）");
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void onClickItem(int pos) {
        if (pos < 0 || pos >= items.size()) return;
        File f = items.get(pos);
        if (f.isDirectory()) { load(f); return; }
        String name = f.getName();
        if (!name.toLowerCase(Locale.US).endsWith(".so")) {
            darkToast("只能选择 .so 动态库文件");
            App.log("SoPicker reject non-so: " + f.getAbsolutePath());
            return;
        }
        App.log("SoPicker selected: " + f.getAbsolutePath() + " (" + f.length() + " bytes)");
        Intent data = new Intent();
        data.putExtra(EXTRA_SO_PATH, f.getAbsolutePath());
        setResult(RESULT_OK, data);
        finish();
    }

    // 兜底：目录浏览受限时直接输入 .so 完整路径
    private void showManualInput() {
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setHint("/storage/emulated/0/Download/xxx.so");
        et.setTextColor(0xFFECEFF1);
        et.setHintTextColor(0xFF90A4AE);
        et.setBackgroundColor(0xFF1E1E1E);
        et.setText((current != null ? current.getAbsolutePath() : "/storage/emulated/0") + "/");
        new android.app.AlertDialog.Builder(this)
                .setTitle("直接输入 .so 完整路径")
                .setView(et)
                .setPositiveButton("选择", (d, w) -> {
                    String p = et.getText().toString().trim();
                    if (!p.toLowerCase(Locale.US).endsWith(".so")) {
                        darkToast("只能选择 .so 动态库文件");
                        App.log("SoPicker manual reject non-so: " + p);
                        return;
                    }
                    File f = new File(p);
                    if (!f.isFile() || !f.canRead()) {
                        darkToast("文件不存在或不可读");
                        App.log("SoPicker manual unreadable: " + p);
                        return;
                    }
                    App.log("SoPicker manual selected: " + p);
                    Intent data = new Intent();
                    data.putExtra(EXTRA_SO_PATH, p);
                    setResult(RESULT_OK, data);
                    finish();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 从已安装应用的 lib/ 或 base.apk 内 lib/<abi>/ 里挑 .so，不依赖文件选择器 */
    private void showAppLibs() {
        darkToast("正在扫描已安装应用的 lib");
        new Thread(() -> {
            try {
                final List<InstalledAppScanner.SoItem> list =
                        InstalledAppScanner.listNativeLibs(getApplicationContext());
                runOnUiThread(() -> {
                    // 扫描期间可能已经返回/被关掉，界面 token 失效会抛 BadTokenException
                    if (uiGone()) {
                        App.log("showAppLibs: activity gone, skip dialog (" + list.size() + " items)");
                        return;
                    }
                    if (list.isEmpty()) { darkToast("没有找到任何 .so"); return; }
                    try {
                        final String[] labels = new String[list.size()];
                        for (int i = 0; i < list.size(); i++) labels[i] = list.get(i).label;
                        new android.app.AlertDialog.Builder(SoPickerActivity.this)
                                .setTitle("已安装应用里的 .so (" + list.size() + ")")
                                .setItems(labels, (d, w) -> pickAppSo(list.get(w)))
                                .setNegativeButton("取消", null)
                                .show();
                    } catch (Throwable t) {
                        App.log("showAppLibs: show dialog failed", t);
                        darkToast("弹出列表失败，看 log.txt");
                    }
                });
            } catch (Throwable t) {
                App.log("showAppLibs FAILED", t);
                runOnUiThread(() -> {
                    if (uiGone()) return;
                    darkToast("扫描失败，看 log.txt");
                });
            }
        }, "scan-app-libs").start();
    }

    private boolean uiGone() {
        return isFinishing() || isDestroyed();
    }

    private void pickAppSo(final InstalledAppScanner.SoItem item) {
        if (!item.inApk) {
            String name = new File(item.path).getName();
            if (!name.toLowerCase(Locale.US).endsWith(".so")) {
                darkToast("只能选择 .so 动态库文件");
                App.log("SoPicker app-so reject non-so: " + item.path);
                return;
            }
            App.log("SoPicker app-so selected: " + item.path);
            Intent data = new Intent();
            data.putExtra(EXTRA_SO_PATH, item.path);
            setResult(RESULT_OK, data);
            finish();
            return;
        }
        new Thread(() -> {
            try {
                final File f = InstalledAppScanner.extractSoFromApk(
                        getApplicationContext(), item.apkPath, item.entryName);
                runOnUiThread(() -> {
                    if (uiGone()) { App.log("pickAppSo: activity gone, skip"); return; }
                    Intent data = new Intent();
                    data.putExtra(EXTRA_SO_PATH, f.getAbsolutePath());
                    setResult(RESULT_OK, data);
                    finish();
                });
            } catch (Throwable t) {
                App.log("pickAppSo extract FAILED", t);
                runOnUiThread(() -> {
                    if (uiGone()) return;
                    darkToast("提取失败，看 log.txt");
                });
            }
        }, "extract-so").start();
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

    private void goUp() {
        if (current == null) { finish(); return; }
        if (current.equals(root) || current.getParentFile() == null) { finish(); return; }
        File parent = current.getParentFile();
        if (parent == null || !parent.exists()) { finish(); return; }
        load(parent);
    }

    @Override
    public void onBackPressed() { goUp(); }

    private class FileAdapter extends ArrayAdapter<File> {
        FileAdapter() { super(SoPickerActivity.this, 0, items); }
        @Override
        public View getView(int pos, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) v = LayoutInflater.from(getContext()).inflate(R.layout.so_item, parent, false);
            TextView tv = v.findViewById(R.id.tv_item);
            File f = items.get(pos);
            if (f.isDirectory()) {
                tv.setText("[文件夹] " + f.getName());
                tv.setTextColor(0xFF82B1FF);
            } else {
                boolean so = f.getName().toLowerCase(Locale.US).endsWith(".so");
                tv.setText((so ? "[SO] " : "[文件] ") + f.getName());
                tv.setTextColor(so ? 0xFF69F0AE : 0xFFCFD8DC);
            }
            return v;
        }
    }
}
