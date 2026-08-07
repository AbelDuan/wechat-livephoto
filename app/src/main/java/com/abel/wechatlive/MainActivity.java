package com.abel.wechatlive;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 模块入口页：仅用于在桌面/LSPosed 中辨识模块，并给出调试指引。
 * 作用域只勾了微信，所以这个页面自身不会被 hook，不做"是否激活"自检——
 * 判断激活与否请看 logcat 里的 "WechatLive injected" 行。
 */
public class MainActivity extends Activity {

    private static final String INFO =
            "WechatLive\n"
            + "微信发图默认「实况 + 原图」 · LSPosed 模块\n\n"
            + "── 启用步骤 ──\n"
            + "1. LSPosed → 模块 → 勾选 WechatLive\n"
            + "2. 作用域已内置为 微信(com.tencent.mm)，确认已勾上\n"
            + "3. 强制停止微信后重新打开（务必，否则 hook 不生效）\n\n"
            + "── 确认是否注入成功 ──\n"
            + "电脑执行：\n"
            + "    adb logcat -c\n"
            + "    adb logcat -s WCLP:V\n"
            + "然后打开微信。若看到：\n"
            + "    === WechatLive injected === pkg=com.tencent.mm ...\n"
            + "说明模块已注入。看不到 = 没生效（作用域/重启/LSPosed 状态问题）。\n\n"
            + "── 采集调试信息 ──\n"
            + "保持 logcat 运行，进入 微信→聊天→相册 并多选几张图，\n"
            + "日志里会输出：\n"
            + "  · onResume [进程] 真实Activity类名\n"
            + "  · >>> GALLERY-LIKE ACTIVITY: xxx\n"
            + "  · VIEW ... id=... sel=0/1 act=0/1 desc=... text=...\n"
            + "把整份日志发回，即可定位「实况」开关及其选中状态字段。";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView tv = new TextView(this);
        tv.setText(INFO);
        tv.setTextColor(Color.parseColor("#1A1A1A"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setLineSpacing(0f, 1.25f);
        int p = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 20, getResources().getDisplayMetrics());
        tv.setPadding(p, p + p / 2, p, p);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.parseColor("#FAFAFA"));
        sv.addView(tv);
        setContentView(sv);
    }
}
