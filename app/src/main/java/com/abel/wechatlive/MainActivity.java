package com.abel.wechatlive;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 模块首页 = 控制面板 + 自检面板。
 *
 * 【铁律】本类不得 import / 引用任何 de.robv.android.xposed.* 或 MainHook。
 * 模块 App 自己的进程里没有 XposedBridge，一旦引用就会 NoClassDefFoundError 闪退。
 * v3 的闪退就是 MainActivity 调了 MainHook.logFiles() 引起的。
 *
 * 激活状态怎么判断：
 *   LSPosed 的作用域列表会过滤掉模块自身，无法「勾选模块自己」，
 *   所以传统 isModuleActive() 自 hook 方案不可用。
 *   这里改成看「微信侧 Hook 的心跳」——微信进程每次 Activity onResume
 *   都会通过 StatusProvider 回报一次时间戳。有心跳 = 真的在微信里跑起来了，
 *   这个信号比"模块自身被注入"更有意义。
 */
public class MainActivity extends Activity {

    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);
    /** 心跳在这个时间内算「运行中」 */
    private static final long FRESH_MS = 3 * 60 * 1000L;

    private TextView statusView;
    private TextView detailView;
    private TextView logView;
    private CheckBox cbEnabled, cbLive, cbOrig, cbVerbose;

    private final Handler ticker = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            render();
            ticker.postDelayed(this, 2000);
        }
    };

    private SharedPreferences sp() {
        return getSharedPreferences(Const.PREFS, Context.MODE_PRIVATE);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            buildUi();
            render();
        } catch (Throwable t) {
            // 宁可把异常显示出来，也绝不闪退
            showFatal(t);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ticker.removeCallbacks(tick);
        ticker.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ticker.removeCallbacks(tick);
    }

    // ────────────────────────────── UI ──────────────────────────────

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F5F6F8"));
        int p = dp(16);
        root.setPadding(p, p, p, dp(10));

        // ── 状态横幅 ──
        statusView = new TextView(this);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        statusView.setTypeface(Typeface.DEFAULT_BOLD);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(dp(14), dp(16), dp(14), dp(16));
        root.addView(statusView, mw());

        detailView = new TextView(this);
        detailView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        detailView.setTextColor(Color.parseColor("#555555"));
        detailView.setPadding(dp(4), dp(10), dp(4), dp(6));
        root.addView(detailView, mw());

        // ── 开关区 ──
        TextView head = new TextView(this);
        head.setText("功能开关（改完立即生效，无需重启微信）");
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setTextColor(Color.parseColor("#222222"));
        head.setPadding(dp(4), dp(8), dp(4), dp(2));
        root.addView(head, mw());

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.WHITE);
        box.setPadding(dp(10), dp(6), dp(10), dp(6));

        cbEnabled = addCheck(box, Const.K_ENABLED, true,
                "启用模块（总开关，关闭=暂停一切操作）");
        cbLive = addCheck(box, Const.K_LIVE, true,
                "默认开启「实况」(Gallery_LivePhoto_Auto_Enable)");
        cbOrig = addCheck(box, Const.K_ORIG, true,
                "默认开启「原图」(send_raw_img)");
        cbVerbose = addCheck(box, Const.K_VERBOSE, true,
                "详细日志（导出 View 树，排障用）");

        root.addView(box, mw());

        // ── 按钮 ──
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(0, dp(10), 0, dp(6));
        bar.addView(btn("刷新", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                render();
            }
        }), eq());
        bar.addView(btn("复制日志", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyLog();
            }
        }), eq());
        bar.addView(btn("清空日志", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LogStore.clear(MainActivity.this);
                sp().edit().remove(Const.K_HITS).apply();
                toast("已清空");
                render();
            }
        }), eq());
        root.addView(bar, mw());

        // ── 日志区 ──
        logView = new TextView(this);
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        logView.setTextColor(Color.parseColor("#2E2E2E"));
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.WHITE);
        sv.setPadding(dp(8), dp(8), dp(8), dp(8));
        sv.addView(logView);
        root.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private CheckBox addCheck(LinearLayout parent, final String key,
                              boolean def, String label) {
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        cb.setTextColor(Color.parseColor("#222222"));
        cb.setChecked(sp().getBoolean(key, def));
        cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                sp().edit().putBoolean(key, checked).apply();
                toast(checked ? "已开启" : "已关闭");
            }
        });
        parent.addView(cb, mw());
        return cb;
    }

    private Button btn(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setOnClickListener(l);
        return b;
    }

    // ────────────────────────────── 渲染 ──────────────────────────────

    private void render() {
        try {
            SharedPreferences sp = sp();
            long seen = sp.getLong(Const.K_LAST_SEEN, 0L);
            int hits = sp.getInt(Const.K_HITS, 0);
            long age = System.currentTimeMillis() - seen;

            if (seen <= 0L) {
                paint("#8A1414", "#FDE4E4",
                        "❌ 未检测到注入\n微信里还没有跑起来");
                detailView.setText(
                        "请依次确认：\n"
                                + "① LSPosed → 模块 → WechatLive 开关已打开\n"
                                + "② 作用域勾选「微信」（模块自身不用勾，也勾不上，这是 LSPosed 的正常行为）\n"
                                + "③ 强制停止微信后重新打开，随便进一次聊天页\n"
                                + "④ 回到本页面等 2 秒自动刷新\n"
                                + "如果仍然没有，看 LSPosed → 日志 → 模块日志 里有没有 [WCLP] 开头的行");
            } else if (age < FRESH_MS) {
                paint("#0B6E2E", "#DEF7E5",
                        "✅ 运行中\n微信侧 Hook 心跳正常");
                detailView.setText(detail(seen, age, hits, sp));
            } else {
                paint("#7A5200", "#FFF3D6",
                        "⚠️ 曾经注入过，当前无心跳\n微信可能已退出或被冻结");
                detailView.setText(detail(seen, age, hits, sp));
            }

            List<String> lines = LogStore.tail(this, 400);
            if (lines.isEmpty()) {
                logView.setText("（暂无日志）\n\n"
                        + "日志由微信进程通过 ContentProvider 送进来，\n"
                        + "存在本应用私有目录，不需要任何存储权限。\n\n"
                        + "进微信 → 聊天 → 相册，再回来看这里。");
            } else {
                StringBuilder sb = new StringBuilder();
                for (String s : lines) sb.append(s).append('\n');
                logView.setText(sb);
            }
        } catch (Throwable t) {
            if (logView != null) logView.setText(stack(t));
        }
    }

    private String detail(long seen, long age, int hits, SharedPreferences sp) {
        return "最近心跳：" + FMT.format(new Date(seen)) + "（" + ago(age) + "前）\n"
                + "累计回报：" + hits + " 次\n"
                + "微信版本：" + sp.getString(Const.K_WX_VER, "?") + "\n"
                + "最近界面：" + sp.getString(Const.K_LAST_ACT, "?");
    }

    private void paint(String fg, String bg, String text) {
        statusView.setText(text);
        statusView.setTextColor(Color.parseColor(fg));
        statusView.setBackgroundColor(Color.parseColor(bg));
    }

    private String ago(long ms) {
        if (ms < 1000) return "刚刚";
        long s = ms / 1000;
        if (s < 60) return s + " 秒";
        long m = s / 60;
        if (m < 60) return m + " 分";
        return (m / 60) + " 小时";
    }

    private void copyLog() {
        try {
            StringBuilder sb = new StringBuilder();
            for (String s : LogStore.tail(this, 3000)) sb.append(s).append('\n');
            if (sb.length() == 0) {
                toast("日志为空");
                return;
            }
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("WechatLive", sb.toString()));
                toast("已复制 " + sb.length() + " 字符");
            }
        } catch (Throwable t) {
            toast("复制失败：" + t);
        }
    }

    // ────────────────────────────── 兜底 ──────────────────────────────

    private void showFatal(Throwable t) {
        try {
            ScrollView sv = new ScrollView(this);
            TextView tv = new TextView(this);
            tv.setPadding(dp(16), dp(16), dp(16), dp(16));
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setTextIsSelectable(true);
            tv.setText("界面初始化异常（已拦截，未闪退）：\n\n" + stack(t));
            sv.addView(tv);
            setContentView(sv);
        } catch (Throwable ignored) {
        }
    }

    private String stack(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private LinearLayout.LayoutParams mw() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams eq() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        return lp;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
