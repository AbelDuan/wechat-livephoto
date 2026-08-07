package com.abel.wechatlive;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 模块首页 = 自检面板。
 * 不用连电脑、不用 adb，打开就能看到：
 *   1) 模块是否被 LSPosed 激活（isModuleActive 被 hook 后返回 true）
 *   2) 微信侧 hook 的实时日志（从落盘文件读回）
 */
public class MainActivity extends Activity {

    /**
     * 占位方法：模块未激活时恒为 false；
     * 被 MainHook 用 XC_MethodReplacement 替换后返回 true。
     * 注意：必须是方法调用，javac 不会内联，因此可被 hook 生效。
     */
    public static boolean isModuleActive() {
        return false;
    }

    private TextView statusView;
    private TextView logView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#FAFAFA"));
        int p = dp(18);
        root.setPadding(p, p, p, p);

        // ── 状态横幅 ──
        statusView = new TextView(this);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(dp(14), dp(18), dp(14), dp(18));
        root.addView(statusView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // ── 操作按钮 ──
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(0, dp(12), 0, dp(8));

        Button refresh = new Button(this);
        refresh.setText("刷新");
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                render();
            }
        });
        bar.addView(refresh, eq());

        Button clear = new Button(this);
        clear.setText("清空日志");
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int n = 0;
                for (File f : MainHook.logFiles()) {
                    if (f != null && f.exists() && f.delete()) n++;
                }
                Toast.makeText(MainActivity.this, "已清理 " + n + " 个日志文件",
                        Toast.LENGTH_SHORT).show();
                render();
            }
        });
        bar.addView(clear, eq());

        root.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // ── 日志区 ──
        logView = new TextView(this);
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        logView.setTextColor(Color.parseColor("#333333"));
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.WHITE);
        sv.setPadding(dp(10), dp(10), dp(10), dp(10));
        sv.addView(logView);
        root.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        // 读 /sdcard 日志需要存储权限
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        render();
    }

    private LinearLayout.LayoutParams eq() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(4), 0, dp(4), 0);
        return lp;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void render() {
        boolean active = isModuleActive();
        if (active) {
            statusView.setText("✅ 模块已激活\nLSPosed 已成功注入本应用");
            statusView.setTextColor(Color.parseColor("#0B6E2E"));
            statusView.setBackgroundColor(Color.parseColor("#DEF7E5"));
        } else {
            statusView.setText("❌ 模块未激活\n"
                    + "请在 LSPosed → 模块 → WechatLive 中：\n"
                    + "① 打开模块开关\n"
                    + "② 作用域同时勾选「微信」和「WechatLive」\n"
                    + "③ 强制停止本应用与微信后重新打开");
            statusView.setTextColor(Color.parseColor("#8A1414"));
            statusView.setBackgroundColor(Color.parseColor("#FDE4E4"));
        }
        logView.setText(readLog(active));
    }

    private String readLog(boolean active) {
        StringBuilder sb = new StringBuilder();
        File hit = null;
        for (File f : MainHook.logFiles()) {
            if (f != null && f.exists() && f.length() > 0) {
                hit = f;
                break;
            }
        }
        if (hit == null) {
            sb.append("（暂无日志文件）\n\n");
            sb.append("日志会写到以下任一位置：\n");
            for (File f : MainHook.logFiles()) {
                if (f != null) sb.append("  · ").append(f.getAbsolutePath()).append('\n');
            }
            sb.append('\n');
            if (active) {
                sb.append("模块已激活但还没有微信侧日志 —— 说明\n"
                        + "微信作用域可能没勾，或微信还没重启。\n"
                        + "请强制停止微信后重新打开，再回来点「刷新」。\n\n");
            }
            sb.append("若此处始终为空，可改用 root 直接查看微信私有目录：\n"
                    + "  adb shell su -c \"cat /data/data/com.tencent.mm/files/WechatLive.log\"\n"
                    + "或 logcat：adb logcat -s WCLP:V");
            return sb.toString();
        }

        sb.append("日志文件：").append(hit.getAbsolutePath())
                .append("  (").append(hit.length()).append(" B)\n")
                .append("────────────────────────────\n");
        // 只展示最后 400 行，避免 View 树刷屏卡顿
        List<String> lines = new ArrayList<String>();
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(hit));
            String ln;
            while ((ln = r.readLine()) != null) {
                lines.add(ln);
                if (lines.size() > 4000) lines.remove(0);
            }
        } catch (Throwable t) {
            sb.append("读取失败：").append(t).append('\n');
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignored) {
                }
            }
        }
        int from = Math.max(0, lines.size() - 400);
        if (from > 0) sb.append("… 省略前 ").append(from).append(" 行 …\n");
        for (int i = from; i < lines.size(); i++) {
            sb.append(lines.get(i)).append('\n');
        }
        return sb.toString();
    }
}
