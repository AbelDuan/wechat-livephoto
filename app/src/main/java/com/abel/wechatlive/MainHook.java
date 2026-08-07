package com.abel.wechatlive;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * WechatLive v5 —— 微信「实况(LivePhoto) + 原图」默认开启
 *
 * ══════════════════════════════════════════════════════════════════
 * 【核心方案变更：从"点击 UI"改为"改写 Intent extras"】
 *
 * 从 LSPosed 日志里实锤到：微信启动相册界面时，Intent extras 里明文携带
 *   AlbumPreviewUI:  Gallery_LivePhoto_Need_Query=true
 *                    Gallery_LivePhoto_Auto_Enable=false   ← 实况总开关
 *   ImagePreviewUI:  send_raw_img=false                    ← 原图
 *                    key_force_show_raw_image_button=false
 *
 * 这些键**没有被混淆**，是微信自己决定"这次相册要不要默认开实况/原图"的源头。
 * 只要在 Intent/Bundle 读写这些键时把值改成 true，就等于微信自己决定默认开启：
 *   - 缩略图网格多选 → 直接发送，全部是实况 + 原图
 *   - 不需要点任何按钮，不存在循环点击 / 重开取消 / 滑动不触发
 *   - 不依赖微信混淆类名，微信升级后大概率仍然有效
 *
 * ══════════════════════════════════════════════════════════════════
 * 【v4 为什么完全没跑起来 —— 已修复】
 *
 * v4 有一行静态字段初始化：
 *     private static final Handler H = new Handler(Looper.getMainLooper());
 *
 * LSPosed 是在 **zygote** 里 Class.newInstance() 实例化模块类的
 * （forkCommon → loadModule → initModule）。那个时刻主线程 Looper 尚未准备好，
 * Looper.getMainLooper() 返回 null → Handler 构造函数 NPE
 * → ExceptionInInitializerError → 整个模块在**所有进程**加载失败。
 *
 * 症状就是：装了、勾了、作用域也对，但完全没反应、一条日志都没有。
 *
 * ✅ 铁律：模块入口类的静态初始化块里**绝不能碰任何 Android 框架对象**
 *    （Handler / Looper / Context / Resources ...）。一律改为懒加载。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "WCLP";

    // ── 微信 Intent extras 键（明文，来自 LSPosed 日志实测）──
    /** 相册是否默认开启实况 */
    private static final String K_LIVE_AUTO = "Gallery_LivePhoto_Auto_Enable";
    /** 相册是否查询实况资源（不开这个，实况根本不会被加载） */
    private static final String K_LIVE_QUERY = "Gallery_LivePhoto_Need_Query";
    /** 是否以原图发送 */
    private static final String K_SEND_RAW = "send_raw_img";
    /** 强制显示原图按钮 */
    private static final String K_SHOW_RAW_BTN = "key_force_show_raw_image_button";

    // ── 纯 Java 静态字段，zygote 阶段安全 ──
    private static final List<String> PENDING = new ArrayList<String>();

    private static String sProc = "?";
    private static long sLastReport = 0L;
    private static int sForceCount = 0;

    // 用户开关（默认全开；取不到配置时按默认走）
    private static volatile boolean cEnabled = true;
    private static volatile boolean cLive = true;
    private static volatile boolean cOrig = true;
    private static volatile boolean cVerbose = true;

    // ⚠️ 绝不能是 static final 直接 new —— 见类注释
    private static volatile Handler sHandler;
    private static volatile SimpleDateFormat sFmt;

    // ══════════════════════════ 入口 ══════════════════════════

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!Const.WECHAT_PKG.equals(lp.packageName)) return;
        sProc = lp.processName;

        log("========================================");
        log("WechatLive v5 注入成功  proc=" + sProc);

        // 相册只在主进程，重量级 hook 只装主进程，避免 :push/:appbrand 等无谓开销
        boolean main = Const.WECHAT_PKG.equals(sProc);
        if (!main) {
            log("非主进程，跳过 hook 安装");
            return;
        }

        installExtraForcing();
        installLifecycle();
    }

    // ═══════════════ 核心：改写 Intent / Bundle 里的开关键 ═══════════════

    /**
     * 目标值判定。返回 null 表示"这个键我们不关心，别动"。
     * 注意 key_is_raw_image_button_disable 语义是"禁用"，不能改。
     */
    private static Boolean desired(String key) {
        if (key == null) return null;
        if (cLive) {
            if (K_LIVE_AUTO.equals(key)) return Boolean.TRUE;
            if (K_LIVE_QUERY.equals(key)) return Boolean.TRUE;
        }
        if (cOrig) {
            if (K_SEND_RAW.equals(key)) return Boolean.TRUE;
            if (K_SHOW_RAW_BTN.equals(key)) return Boolean.TRUE;
        }
        return null;
    }

    private void installExtraForcing() {
        // ① 写入侧（最关键）：微信构造 Intent 时就把值改掉。
        //    这样值是真的被写进 Bundle 的，跨进程 parcel 之后依然为 true，
        //    下游任何读法（getBooleanExtra / getExtras().getBoolean）都天然拿到 true。
        hookWrite("android.content.Intent", "putExtra");
        hookWrite("android.os.BaseBundle", "putBoolean");
        hookWrite("android.os.Bundle", "putBoolean");

        // ② 读取侧兜底：万一某处的值不是经 putExtra 写进来的（比如从 Parcel 直接还原），
        //    在读的那一刻再改一次。
        hookReadIntent();
        hookReadBundle();

        log("已安装 Intent/Bundle 强制改写（实况=" + cLive + " 原图=" + cOrig + "）");
    }

    /** hook 形如 putExtra(String,boolean) / putBoolean(String,boolean) 的写入方法 */
    private void hookWrite(String clsName, final String method) {
        try {
            Class<?> c = Class.forName(clsName);
            XposedHelpers.findAndHookMethod(c, method, String.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            if (!cEnabled) return;
                            try {
                                String k = (String) p.args[0];
                                Boolean want = desired(k);
                                if (want != null && !want.equals(p.args[1])) {
                                    p.args[1] = want;
                                    sForceCount++;
                                    log("★ FORCE " + method + "  " + k + " : false -> true");
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
        } catch (Throwable t) {
            log("hook " + clsName + "#" + method + " 失败: " + t);
        }
    }

    private void hookReadIntent() {
        try {
            XposedHelpers.findAndHookMethod(Intent.class, "getBooleanExtra",
                    String.class, boolean.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            if (!cEnabled) return;
                            try {
                                Boolean want = desired((String) p.args[0]);
                                if (want != null && !want.equals(p.getResult())) {
                                    p.setResult(want);
                                    sForceCount++;
                                    log("★ FORCE getBooleanExtra  " + p.args[0] + " -> true");
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
        } catch (Throwable t) {
            log("hook Intent#getBooleanExtra 失败: " + t);
        }
    }

    private void hookReadBundle() {
        XC_MethodHook h = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (!cEnabled) return;
                try {
                    Boolean want = desired((String) p.args[0]);
                    if (want != null && !want.equals(p.getResult())) {
                        p.setResult(want);
                        sForceCount++;
                        log("★ FORCE Bundle.getBoolean  " + p.args[0] + " -> true");
                    }
                } catch (Throwable ignored) {
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod(Bundle.class, "getBoolean",
                    String.class, boolean.class, h);
        } catch (Throwable t) {
            log("hook Bundle#getBoolean(String,boolean) 失败: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(Bundle.class, "getBoolean", String.class, h);
        } catch (Throwable t) {
            log("hook Bundle#getBoolean(String) 失败: " + t);
        }
    }

    // ═══════════════════ 生命周期：心跳 + 验证 ═══════════════════

    private void installLifecycle() {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        onResume((Activity) param.thisObject);
                    } catch (Throwable t) {
                        log("onResume handler error: " + t);
                    }
                }
            });
            log("已挂载 Activity#onResume");
        } catch (Throwable t) {
            log("挂载 Activity#onResume 失败: " + t);
        }
    }

    private static void onResume(final Activity act) {
        if (act == null) return;
        final String cls = act.getClass().getName();
        log("onResume [" + sProc + "] " + cls);

        // 心跳 + 拉取开关（后台线程，不阻塞微信主线程）
        report(act.getApplicationContext(), cls);

        if (!cEnabled) return;
        if (!looksLikeGallery(cls)) return;

        // 相册界面：把实际生效的 extras 打出来，直接验证强制是否成功
        dumpIntentExtras(act);

        if (!cVerbose) return;
        Handler h = ui();
        if (h == null) return;
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    View root = act.getWindow().getDecorView();
                    log("---- View 树 [" + cls + "] ----");
                    dumpView(root, 0);
                    log("---- View 树结束 ----");
                } catch (Throwable t) {
                    log("dumpView error: " + t);
                }
            }
        }, 800);
    }

    /** 打印相册 Activity 实际收到的 extras —— 强制成功与否，一眼可见 */
    private static void dumpIntentExtras(Activity act) {
        try {
            Intent it = act.getIntent();
            if (it == null) return;
            Bundle b = it.getExtras();
            if (b == null) return;
            StringBuilder sb = new StringBuilder("Intent extras 关键项: ");
            String[] keys = {K_LIVE_AUTO, K_LIVE_QUERY, K_SEND_RAW, K_SHOW_RAW_BTN};
            for (String k : keys) {
                if (b.containsKey(k)) {
                    sb.append(k).append('=').append(b.getBoolean(k)).append("  ");
                }
            }
            log(sb.toString());
            log("累计强制改写次数 = " + sForceCount);
        } catch (Throwable t) {
            log("dumpIntentExtras error: " + t);
        }
    }

    private static boolean looksLikeGallery(String cls) {
        if (cls == null) return false;
        String l = cls.toLowerCase(Locale.US);
        return l.contains("gallery") || l.contains("album") || l.contains("imagepreview");
    }

    // ══════════════════════ View 树 dump（诊断用）══════════════════════

    private static void dumpView(View v, int depth) {
        if (v == null || depth > 24) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(v.getClass().getSimpleName());
        try {
            int id = v.getId();
            if (id != View.NO_ID) {
                sb.append(" id=").append(
                        v.getResources().getResourceEntryName(id));
            }
        } catch (Throwable ignored) {
        }
        sb.append(" clk=").append(v.isClickable())
                .append(" sel=").append(v.isSelected())
                .append(" act=").append(v.isActivated())
                .append(" en=").append(v.isEnabled())
                .append(" vis=").append(v.getVisibility() == View.VISIBLE);
        CharSequence d = v.getContentDescription();
        if (d != null) sb.append(" desc=\"").append(d).append('"');
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && t.length() > 0) sb.append(" text=\"").append(t).append('"');
        }
        Object tag = v.getTag();
        if (tag != null) sb.append(" tag=").append(tag);
        log(sb.toString());

        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                dumpView(g.getChildAt(i), depth + 1);
            }
        }
    }

    // ══════════════════ 与模块 App 通信（ContentProvider）══════════════════

    private static void report(final Context ctx, final String cls) {
        if (ctx == null) return;
        long now = System.currentTimeMillis();
        if (now - sLastReport < 800) return; // 限流
        sLastReport = now;

        final String payload = drainPending();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ContentResolver cr = ctx.getContentResolver();
                    Bundle in = new Bundle();
                    in.putString("activity", cls);
                    in.putString("proc", sProc);
                    in.putInt("forced", sForceCount);
                    if (payload.length() > 0) in.putString(Const.KEY_LOG, payload);

                    Bundle out = cr.call(Uri.parse(Const.URI),
                            Const.METHOD_REPORT, null, in);
                    if (out != null) {
                        cEnabled = out.getBoolean(Const.K_ENABLED, true);
                        cLive = out.getBoolean(Const.K_LIVE, true);
                        cOrig = out.getBoolean(Const.K_ORIG, true);
                        cVerbose = out.getBoolean(Const.K_VERBOSE, true);
                    }
                } catch (Throwable t) {
                    // 模块 App 被冻结/未安装时会走到这里，忽略即可
                }
            }
        }, "wclp-report").start();
    }

    // ══════════════════════════ 工具 ══════════════════════════

    private static Handler ui() {
        Handler h = sHandler;
        if (h != null) return h;
        synchronized (MainHook.class) {
            if (sHandler == null) {
                Looper l = Looper.getMainLooper();
                if (l == null) return null;   // zygote 阶段直接放弃，绝不抛异常
                sHandler = new Handler(l);
            }
            return sHandler;
        }
    }

    private static String stamp() {
        SimpleDateFormat f = sFmt;
        if (f == null) {
            synchronized (MainHook.class) {
                if (sFmt == null) {
                    sFmt = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
                }
                f = sFmt;
            }
        }
        return f.format(new Date());
    }

    private static void log(String msg) {
        String line = stamp() + "  " + msg;
        try {
            android.util.Log.i(TAG, line);
        } catch (Throwable ignored) {
        }
        try {
            XposedBridge.log("[" + TAG + "] " + line);
        } catch (Throwable ignored) {
        }
        synchronized (PENDING) {
            PENDING.add(line);
            if (PENDING.size() > 4000) PENDING.remove(0);
        }
    }

    private static String drainPending() {
        StringBuilder sb = new StringBuilder();
        synchronized (PENDING) {
            for (String s : PENDING) sb.append(s).append('\n');
            PENDING.clear();
        }
        return sb.toString();
    }
}
