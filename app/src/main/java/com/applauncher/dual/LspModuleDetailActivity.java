package com.applauncher.dual;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import com.carlos.R;
import com.carlos.common.App;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 单个模块的详情页：
 *  - 显示「已激活 / 未激活」（数据来自驱动写的 npatch/activated.txt，代表框架真的加载过它）
 *  - 「在分身内打开模块界面」：把模块自己也双开一份跑在 guest 里，
 *    框架会把它的 SharedPreferences 重定向到安全区，于是在里面改的配置就是目标进程读到的配置
 *  - 直接列出安全区里的 key/value，点一行就能改（模块没有界面时也能改）
 */
public class LspModuleDetailActivity extends Activity {

    public static final String EXTRA_PKG = "pkg";

    private String pkg;
    private String label;
    private TextView tvTitle, tvStatus, tvInfo, tvPrefsHeader;
    private Button btnOpenUi, btnToggle;
    private ListView list;
    private final List<LspPrefs.Entry> entries = new ArrayList<>();
    private EntryAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.lsp_module_detail);
        pkg = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_PKG);
        if (pkg == null || pkg.isEmpty()) {
            finish();
            return;
        }
        label = getIntent().getStringExtra("label");
        tvTitle = findViewById(R.id.tv_title);
        tvStatus = findViewById(R.id.tv_status);
        tvInfo = findViewById(R.id.tv_info);
        tvPrefsHeader = findViewById(R.id.tv_prefs_header);
        btnOpenUi = findViewById(R.id.btn_open_ui);
        btnToggle = findViewById(R.id.btn_toggle);
        list = findViewById(R.id.list);
        adapter = new EntryAdapter();
        list.setAdapter(adapter);

        tvTitle.setText(label == null || label.isEmpty() ? pkg : label);
        btnOpenUi.setOnClickListener(v -> openModuleUi());
        btnToggle.setOnClickListener(v -> toggleSelected());
        findViewById(R.id.btn_refresh).setOnClickListener(v -> refresh());
        findViewById(R.id.btn_clear).setOnClickListener(v -> confirmClear());
        list.setOnItemClickListener((AdapterView<?> parent, View view, int pos, long id) -> editEntry(entries.get(pos)));

        App.log("LspModuleDetailActivity open pkg=" + pkg);
        refresh();
    }

    private void refresh() {
        boolean activated = LspPrefs.isActivated(this, pkg);
        long ts = LspPrefs.activatedAt(this, pkg);
        boolean selected = LspHooks.selected(this).contains(pkg);
        if (activated) {
            tvStatus.setText("已激活（框架已加载该模块）"
                    + (ts > 0 ? "  " + new SimpleDateFormat("MM-dd HH:mm").format(new Date(ts)) : ""));
            tvStatus.setTextColor(0xFF69F0AE);
        } else if (selected) {
            tvStatus.setText("已勾选，但还没跑过（启动一次分身即可激活）");
            tvStatus.setTextColor(0xFFFFB74D);
        } else {
            tvStatus.setText("未启用（未勾选，不会加载）");
            tvStatus.setTextColor(0xFF78909C);
        }
        tvInfo.setText("包名: " + pkg
                + "\n配置目录: " + LspPrefs.pathFor(this, pkg)
                + "\n在分身体内已安装: " + (LspCloneLaunch.installedInVa(pkg) ? "是" : "否"));
        btnToggle.setText(selected ? "取消启用" : "启用此模块");

        entries.clear();
        entries.addAll(LspPrefs.read(this, pkg));
        adapter.notifyDataSetChanged();
        tvPrefsHeader.setText(entries.isEmpty()
                ? "模块配置：还没有配置（在模块界面里改一次就会出现）"
                : "模块配置（" + entries.size() + " 项，点一行可直接改值）");
    }

    private void toggleSelected() {
        java.util.Set<String> sel = LspHooks.selected(this);
        if (sel.contains(pkg)) {
            sel.remove(pkg);
            LspHooks.disable(pkg);
        } else {
            sel.add(pkg);
        }
        LspHooks.saveSelection(this, sel);
        App.log("LspModuleDetailActivity toggle " + pkg + " selected=" + sel.contains(pkg));
        refresh();
    }

    private void openModuleUi() {
        btnOpenUi.setEnabled(false);
        btnOpenUi.setText("正在启动模块界面…");
        new Thread(() -> {
            final boolean ok = LspCloneLaunch.openModuleUi(getApplicationContext(), pkg);
            runOnUiThread(() -> {
                btnOpenUi.setEnabled(true);
                btnOpenUi.setText("在分身内打开模块界面（改配置）");
                if (!ok) {
                    toast("启动失败，看 files/log.txt");
                    return;
                }
                toast("模块界面已启动，改完配置回到这里刷新");
                // 回到桌面，让用户看到模块界面
                moveTaskToBack(true);
            });
        }, "lsp-open-module-ui").start();
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("清空模块配置")
                .setMessage("会删除 " + pkg + " 在安全区里的所有配置，模块回到默认值。继续？")
                .setPositiveButton("清空", (d, w) -> {
                    LspPrefs.clear(this, pkg);
                    refresh();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void editEntry(final LspPrefs.Entry e) {
        final EditText input = new EditText(this);
        input.setText(e.value);
        input.setSelection(input.getText().length());
        new AlertDialog.Builder(this)
                .setTitle(e.key)
                .setMessage("文件: " + e.file + "\n（布尔值填 true/false，集合每行一个）")
                .setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    String v = input.getText().toString();
                    if (LspPrefs.write(this, pkg, e.file, e.key, v)) {
                        toast("已保存，重启分身生效");
                    } else {
                        toast("保存失败，看 log.txt");
                    }
                    refresh();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    private class EntryAdapter extends BaseAdapter {
        @Override
        public int getCount() { return entries.size(); }

        @Override
        public Object getItem(int i) { return entries.get(i); }

        @Override
        public long getItemId(int i) { return i; }

        @Override
        public View getView(int position, View convertView, android.view.ViewGroup parent) {
            TextView tv = (TextView) convertView;
            if (tv == null) {
                tv = new TextView(LspModuleDetailActivity.this);
                tv.setTextSize(13);
                tv.setTextColor(0xFFECEFF1);
                tv.setPadding(24, 22, 24, 22);
            }
            tv.setText(entries.get(position).toString());
            return tv;
        }
    }
}
