package com.applauncher.dual;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.TextView;

import com.carlos.R;
import com.carlos.common.App;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * LSP 模块选择页：
 *  - 进页面即后台扫描本机模块
 *  - 每一行一个勾选框；勾选/取消立刻写入 launcher_prefs.lsp_modules
 *  - 经典 API 模块可勾选；新 API（libxposed 101/102）与缺 xposed_init 的只展示、不可勾
 */
public class LspModuleActivity extends Activity {

    private final ArrayList<LspModuleScanner.ModuleInfo> data = new ArrayList<>();
    private final HashSet<String> selected = new HashSet<>();
    private ModuleAdapter adapter;
    private TextView tvCount;
    private volatile boolean uiGone = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.lsp_modules);
        selected.addAll(LspHooks.selected(this));
        App.log("LspModuleActivity open, selected=" + selected);

        tvCount = findViewById(R.id.tv_count);
        ListView list = findViewById(R.id.list);
        adapter = new ModuleAdapter();
        list.setAdapter(adapter);

        Button btnAll = findViewById(R.id.btn_all);
        Button btnNone = findViewById(R.id.btn_none);
        Button btnDone = findViewById(R.id.btn_done);
        btnAll.setOnClickListener(v -> selectAll(true));
        btnNone.setOnClickListener(v -> selectAll(false));
        btnDone.setOnClickListener(v -> finish());

        updateCount();
        new Thread(() -> {
            final ArrayList<LspModuleScanner.ModuleInfo> found;
            try {
                found = LspModuleScanner.scan(this);
            } catch (Throwable t) {
                App.log("LspModuleActivity scan failed", t);
                return;
            }
            runOnUiThread(() -> {
                if (uiGone || isFinishing()) return;
                data.clear();
                data.addAll(found);
                adapter.notifyDataSetChanged();
                updateCount();
            });
        }, "lsp-scan").start();
    }

    @Override
    protected void onDestroy() {
        uiGone = true;
        super.onDestroy();
    }

    private void selectAll(boolean on) {
        for (LspModuleScanner.ModuleInfo m : data) {
            if (!m.selectable) continue;
            if (on) selected.add(m.pkg);
            else selected.remove(m.pkg);
        }
        LspHooks.saveSelection(this, selected);
        if (!on) {
            for (LspModuleScanner.ModuleInfo m : data) {
                if (m.selectable) LspHooks.disable(m.pkg);
            }
        }
        adapter.notifyDataSetChanged();
        updateCount();
        App.log("LspModuleActivity selectAll=" + on + ", now=" + selected.size());
    }

    private void toggle(String pkg, boolean on) {
        if (on) selected.add(pkg);
        else {
            selected.remove(pkg);
            LspHooks.disable(pkg);
        }
        LspHooks.saveSelection(this, selected);
        updateCount();
        App.log("LspModuleActivity toggle " + pkg + "=" + on + ", total=" + selected.size());
    }

    private void updateCount() {
        int selectable = 0, unsupported = 0;
        for (LspModuleScanner.ModuleInfo m : data) {
            if (m.selectable) selectable++;
            else unsupported++;
        }
        int chosen = 0;
        for (LspModuleScanner.ModuleInfo m : data) {
            if (m.selectable && selected.contains(m.pkg)) chosen++;
        }
        tvCount.setText("已选 " + selected.size() + " 个模块"
                + (data.isEmpty() ? "（扫描中…）"
                : "（可加载 " + selectable + " / 不支持 " + unsupported + "）")
                + "，本页勾选 " + chosen);
    }

    private class ModuleAdapter extends BaseAdapter {
        @Override
        public int getCount() { return data.size(); }

        @Override
        public Object getItem(int i) { return data.get(i); }

        @Override
        public long getItemId(int i) { return i; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(LspModuleActivity.this)
                        .inflate(R.layout.lsp_module_item, parent, false);
            }
            final LspModuleScanner.ModuleInfo m = data.get(position);
            TextView tvLabel = v.findViewById(R.id.tv_label);
            TextView tvPkg = v.findViewById(R.id.tv_pkg);
            TextView tvNote = v.findViewById(R.id.tv_note);
            TextView tvStatus = v.findViewById(R.id.tv_status);
            CheckBox cb = v.findViewById(R.id.cb_module);

            tvLabel.setText(m.label);
            tvLabel.setTextColor(m.selectable ? 0xFFECEFF1 : 0xFF757575);
            tvPkg.setText(m.pkg);
            tvNote.setText(m.note);
            tvNote.setVisibility(View.VISIBLE);

            boolean on = m.selectable && selected.contains(m.pkg);
            boolean active = LspPrefs.isActivated(LspModuleActivity.this, m.pkg);
            if (active) {
                tvStatus.setText("● 已激活（已加载）　点此改配置");
                tvStatus.setTextColor(0xFF69F0AE);
                tvStatus.setVisibility(View.VISIBLE);
            } else if (on) {
                tvStatus.setText("○ 已勾选，未跑过");
                tvStatus.setTextColor(0xFFFFB74D);
                tvStatus.setVisibility(View.VISIBLE);
            } else {
                tvStatus.setText("");
                tvStatus.setVisibility(View.GONE);
            }

            // 点行（不是点勾选框）进详情页：看激活状态 + 打开模块界面改配置
            final boolean selectable = m.selectable;
            v.setOnClickListener(selectable ? view -> openDetail(m) : null);

            cb.setOnCheckedChangeListener(null);
            cb.setEnabled(m.selectable);
            cb.setChecked(on);
            if (m.selectable) {
                cb.setOnCheckedChangeListener((btn, checked) -> toggle(m.pkg, checked));
            }
            return v;
        }

        void openDetail(LspModuleScanner.ModuleInfo m) {
            try {
                android.content.Intent it = new android.content.Intent(
                        LspModuleActivity.this, LspModuleDetailActivity.class);
                it.putExtra(LspModuleDetailActivity.EXTRA_PKG, m.pkg);
                it.putExtra("label", m.label);
                startActivity(it);
            } catch (Throwable t) {
                App.log("openDetail failed", t);
            }
        }
    }
}
