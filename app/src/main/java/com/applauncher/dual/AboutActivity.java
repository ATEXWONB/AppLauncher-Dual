package com.applauncher.dual;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;

import com.carlos.R;
import com.carlos.common.App;

/**
 * 「?」按钮打开的说明页：制作初衷、运行原理、免责声明、捐赠二维码、引用仓库（可点链接跳浏览器）。
 * 链接不用 autoLink 的隐式点击，统一用显式 ACTION_VIEW，保证点一下就能跳系统浏览器。
 */
public class AboutActivity extends Activity {

    private static final String[][] REPOS = {
            {"tv_repo1", "https://github.com/asLody/VirtualApp"},
            {"tv_repo2", "https://github.com/JingMatrix/Vector"},
            {"tv_repo3", "https://github.com/7723mod/NPatch"},
            {"tv_repo4", "https://github.com/libxposed/api"},
            {"tv_repo5", "https://github.com/LuckyPray/DexKit"},
            {"tv_repo6", "https://github.com/SymonChu/I-Am-Pad"},
            {"tv_repo7", "https://github.com/NKU100/ImPad"},
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.about);
        int[] ids = {R.id.tv_repo1, R.id.tv_repo2, R.id.tv_repo3, R.id.tv_repo4,
                R.id.tv_repo5, R.id.tv_repo6, R.id.tv_repo7};
        for (int i = 0; i < ids.length && i < REPOS.length; i++) {
            bindLink(findViewById(ids[i]), REPOS[i][1]);
        }
    }

    private void bindLink(TextView tv, final String url) {
        if (tv == null) return;
        tv.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Throwable t) {
                App.log("open link failed: " + url, t);
            }
        });
    }
}
