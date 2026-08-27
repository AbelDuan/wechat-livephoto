package com.abel.wechatlive;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * WechatLive v8.7 —— 微信「实况(LivePhoto) + 原图」默认开启（LibXposed 迁移版）
 *
 * ══════════════════════════════════════════════════════════════════
 * 【v8.7 框架迁移：XposedBridge API 82 → LibXposed API 102】
 *
 * 旧 API（de.robv.android.xposed）已废弃，LSPosed 2.x 主推 LibXposed：
 *   - 入口：implements IXposedHookLoadPackage → extends XposedModule
 *   - 回调：XC_MethodHook(before/after) → 拦截器链 Hooker.intercept(Chain)
 *   - 改参数：p.args[i]=x → 组装新参数后 chain.proceed(newArgs)
 *   - 改返回值：p.setResult(x) → return x；p.getResult() → chain.proceed() 的返回值
 *   - 短路：p.setResult(x); return → return x（不调用 proceed）
 *   - 日志：XposedBridge.log → 实例 log(priority, tag, msg)（经 sSelf 桥接）
 *   - 模块声明：assets/xposed_init + manifest meta-data → META-INF/xposed/
 *     （module.prop / java_init.list / scope.list）
 *   - API 102 约束：模块内禁止调用 legacy de.robv.android.xposed.*
 *
 * ══════════════════════════════════════════════════════════════════
 * 【核心方案：改写 Intent extras】
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
 * 【入口类静态初始化铁律】
 *
 * LibXposed（API 101+）在目标进程内实例化模块入口类。入口类的静态字段与
 * 静态初始化块里**绝不能碰任何 Android 框架对象**（Handler / Looper /
 * Context / Resources ...），一律懒加载（v4 曾因 Handler(Looper.getMainLooper())
 * 在 zygote 阶段 NPE 导致整个模块在所有进程加载失败）。
 */
public class MainHook extends XposedModule {

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

    // ── 纯 Java 静态字段，入口阶段安全 ──
    // v8.7：LibXposed 入口实例引用。static log() 通过它把日志写进框架日志
    // （LSPosed 管理器「日志」页可看）。非微信进程/入口未初始化时为 null，跳过。
    private static volatile MainHook sSelf;
    private static final List<String> PENDING = new ArrayList<String>();

    // ── v8.1 日志自动落盘 ──
    // 根因：flushLog 原本只在 onResume 诊断处调用。点「发表」后 SnsUploadUI 直接 finish，
    // 不再有 onResume，导致发表阶段（compress / 原图直塞）的关键日志全部烂在内存里从未落盘，
    // 用户导出时只能拿到「发表前」的快照。这里改为「有日志就自动落盘」。
    private static volatile Context sAppCtx;              // 全局 ApplicationContext（onResume 时保存）
    private static volatile long sLastFlushMs = 0L;       // 上次落盘时间（限流用）
    private static volatile boolean sFlushing = false;    // 落盘进行中：防止 IPC 自身触发的日志递归
    private static final long FLUSH_INTERVAL_MS = 1200L;  // 普通日志的落盘节流间隔
    // 已 dump 过 View 树的 Activity，避免同一界面反复 dump（一次 600+ 行，会把关键日志淹没）
    private static final Set<String> sDumpedActs = new HashSet<String>();
    // v8.4：进入相册前的来源界面。用于区分「朋友圈选图」与「聊天选图」——
    //      两者共用 AlbumPreviewUI，仅靠当前 Activity 类名无法分辨。
    private static volatile String sLastNonGallery = "";
    // v8.6：用户在本次聊天选图流程中手动取消了「原图」。置位后本流程内不再强制该键，
    //      否则读取侧兜底会把用户的取消立刻改回勾选（v8.5 的 bug）。
    private static volatile boolean sChatRawOptOut = false;
    // v8.6：本次相册选图流程的进入时刻，用来区分「界面初始化写入」与「用户手动取消」
    private static volatile long sGalleryEnterAt = 0L;
    // 进入相册后这段时间内出现的 send_raw_img=false 视为初始化写入，不算用户取消
    private static final long RAW_OPTOUT_GRACE_MS = 1200L;

    private static String sProc = "?";
    private static long sLastReport = 0L;
    private static int sForceCount = 0;
    // 当前前台 Activity 类名（onCreate/onResume 时更新，用于上下文感知强制）
    private static volatile String sCurrentActivity;
    // 是否已成功上报过一次（首次用于证明注入，之后只在相册界面心跳）
    private static volatile boolean sReportedOnce = false;

    // 微信版本号缓存（首次 onResume 时从 PackageManager 取，避免每次查询）
    private static String sWxVer;
    // 单一后台线程池：上报走它，避免每次 onResume 都 new Thread（省开销）
    private static final Executor EXEC = Executors.newSingleThreadExecutor();

    // 用户开关（默认全开；取不到配置时按默认走）
    private static volatile boolean cEnabled = true;
    private static volatile boolean cLive = true;
    private static volatile boolean cOrig = true;
    // 详细日志(导出 View 树)默认关闭——v8.9 起「详细日志」开关已从设置页移除，
    // 恒为 false（功能下线；下方 cVerbose 分支全部不再触发，保留为死代码避免大改）
    private static volatile boolean cVerbose = false;
    // 日志记录(写入 App 文件，供导出/排查)默认关闭——省电，心跳自检不受影响
    private static volatile boolean cLog = false;

    // ⚠️ 绝不能是 static final 直接 new —— 见类注释
    private static volatile Handler sHandler;
    private static volatile SimpleDateFormat sFmt;

    // ══════════════════════ 入口 ══════════════════════

    /**
     * LibXposed 生命周期回调：包已加载、classloader 就绪后调用（无 API level 限制，
     * onPackageLoaded 是 @RequiresApi(29)，旧设备不触发，故用 onPackageReady）。
     * 每个包名在进程内只回调一次；scope.list 只声明了微信，所以这里收到的
     * packageName 只可能是 com.tencent.mm（保险起见仍判断）。
     */
    @Override
    public void onPackageReady(PackageReadyParam param) {
        try {
            if (!Const.WECHAT_PKG.equals(param.getPackageName())) return;
            sSelf = this;
            sProc = myProcName();
            log("========================================");
            log("WechatLive v8.15 注入成功  proc=" + sProc);

            // 相册只在主进程，重量级 hook 只装主进程，避免 :push/:appbrand 等无谓开销
            boolean main = Const.WECHAT_PKG.equals(sProc);
            if (!main) {
                log("非主进程，跳过 hook 安装");
                return;
            }

            installExtraForcing();
            installLifecycle();
        } catch (Throwable t) {
            log("onPackageReady 异常: " + t);
        }
    }

    /** 当前进程名。Process.myProcessName() 是 API 28+，反射调用以兼容 Android 8.1。 */
    private static String myProcName() {
        try {
            Method m = android.os.Process.class.getMethod("myProcessName");
            Object r = m.invoke(null);
            return r != null ? (String) r : "?";
        } catch (Throwable t) {
            return "?";   // 极老设备兜底：非主进程判定会误装 hook，但 force 只在相册界面触发，影响极小
        }
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
        boolean moments = isMomentsPublisher(sCurrentActivity);
        if (cOrig && !moments) {
            // 「原图」按钮本身始终允许显示 —— 聊天流程要让用户能自己点。
            if (K_SHOW_RAW_BTN.equals(key)) return Boolean.TRUE;
            if (K_SEND_RAW.equals(key)) {
                // v8.6 关键修复：用户手动取消过就完全不干预。
                // 旧版无条件 return TRUE，且读取侧(getBooleanExtra/Bundle.getBoolean)
                // 还会再兜一次 → 用户取消后一打开图片，微信重新读键又拿到 true，
                // 「原图」被自动勾回去。
                if (sChatRawOptOut) return null;
                return Boolean.TRUE;   // 未取消：维持「默认开启原图」
            }
        }
        return null;
    }

    /**
     * v8.6：识别「用户在聊天流程里手动取消了原图」。
     *
     * 微信在用户点掉「原图」勾选框后，会在跳转下个界面（如 ImagePreviewUI）时
     * putExtra(send_raw_img,false)。旧版把它无脑改写成 true，用户的取消动作等于没发生。
     * 这里只认「进入相册 GRACE 毫秒之后」出现的 false 写入 —— 界面初始化阶段的写入
     * 都发生在最初几百毫秒内，用户不可能这么快点完取消，以此避开误判。
     *
     * @return true 表示这是用户的取消动作，调用方应原样放行、不要改写
     */
    private static boolean noteChatRawOptOut(String key, Object val) {
        if (!K_SEND_RAW.equals(key)) return false;
        if (!Boolean.FALSE.equals(val)) return false;
        if (sChatRawOptOut) return true;                 // 已取消过：后续一律放行
        // 朋友圈流程按钮是隐藏的，用户点不到，不参与取消判定
        if (isMomentsPublisher(sCurrentActivity) || fromMomentsFlow()) return false;
        if (!looksLikeGallery(sCurrentActivity)) return false;
        long t0 = sGalleryEnterAt;
        if (t0 <= 0L || System.currentTimeMillis() - t0 < RAW_OPTOUT_GRACE_MS) return false;
        sChatRawOptOut = true;
        log("★ [原图] 检测到用户手动取消「原图」→ 本次选图流程内不再强制（尊重用户选择）");
        return true;
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
            final Method m = findMethod(c, method, String.class, boolean.class);
            hook(m).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    if (cEnabled) {
                        try {
                            Object[] args = chain.getArgs().toArray();
                            String k = (String) args[0];
                            // v8.6：聊天流程里，界面初始化之后出现的 send_raw_img=false
                            //      就是用户点掉了「原图」。原样放行，不再改写。
                            if (!noteChatRawOptOut(k, args[1])) {
                                Boolean want = desired(k);
                                if (want != null && !want.equals(args[1])) {
                                    args[1] = want;
                                    sForceCount++;
                                    log("★ FORCE " + method + "  " + k + " : false -> true");
                                }
                            }
                            return chain.proceed(args);
                        } catch (Throwable ignored) {
                        }
                    }
                    return chain.proceed();
                }
            });
        } catch (Throwable t) {
            log("hook " + clsName + "#" + method + " 失败: " + t);
        }
    }

    private void hookReadIntent() {
        try {
            final Method m = findMethod(Intent.class, "getBooleanExtra",
                    String.class, boolean.class);
            hook(m).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    if (cEnabled) {
                        try {
                            String key = (String) chain.getArgs().get(0);
                            Boolean want = desired(key);
                            if (want != null && !want.equals(result)) {
                                result = want;
                                sForceCount++;
                                log("★ FORCE getBooleanExtra  " + key + " -> true");
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    return result;
                }
            });
        } catch (Throwable t) {
            log("hook Intent#getBooleanExtra 失败: " + t);
        }
    }

    private void hookReadBundle() {
        XposedInterface.Hooker h = new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                if (cEnabled) {
                    try {
                        String key = (String) chain.getArgs().get(0);
                        Boolean want = desired(key);
                        if (want != null && !want.equals(result)) {
                            result = want;
                            sForceCount++;
                            log("★ FORCE Bundle.getBoolean  " + key + " -> true");
                        }
                    } catch (Throwable ignored) {
                    }
                }
                return result;
            }
        };
        try {
            hook(findMethod(Bundle.class, "getBoolean", String.class, boolean.class)).intercept(h);
        } catch (Throwable t) {
            log("hook Bundle#getBoolean(String,boolean) 失败: " + t);
        }
        try {
            hook(findMethod(Bundle.class, "getBoolean", String.class)).intercept(h);
        } catch (Throwable t) {
            log("hook Bundle#getBoolean(String) 失败: " + t);
        }
    }

    // ═══════════════════ 生命周期：心跳 + 验证 ═══════════════════

    private void installLifecycle() {
        // onCreate 时尽早记录前台 Activity（早于 onResume），用于上下文感知强制，
        // 避免朋友圈发布界面在 onCreate 阶段就被误强制出幽灵原图按钮
        try {
            final Method onCreate = findMethod(Activity.class, "onCreate", Bundle.class);
            hook(onCreate).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    if (cEnabled) {
                        try {
                            Object thiz = chain.getThisObject();
                            sCurrentActivity = thiz.getClass().getName();
                            // v8.1：尽早拿到 ApplicationContext，让自动落盘从第一个 Activity 就能工作
                            if (sAppCtx == null) {
                                sAppCtx = ((Activity) thiz).getApplicationContext();
                            }
                            // v8.14：朋友圈原图功能已移除，此处不再注入/探测。
                        } catch (Throwable ignored) {
                        }
                    }
                    return chain.proceed();
                }
            });
            log("已挂载 Activity#onCreate");
        } catch (Throwable t) {
            log("挂载 Activity#onCreate 失败: " + t);
        }
        try {
            final Method onResume = findMethod(Activity.class, "onResume");
            hook(onResume).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        onResume((Activity) chain.getThisObject());
                    } catch (Throwable t) {
                        log("onResume handler error: " + t);
                    }
                    return result;
                }
            });
            log("已挂载 Activity#onResume");
        } catch (Throwable t) {
            log("挂载 Activity#onResume 失败: " + t);
        }
        // v8.1：onPause 兜底落盘。点「发表」后 SnsUploadUI 会 finish，
        // onPause 是它生命周期里最后一个可靠回调，此时把日志刷出去。
        try {
            final Method onPause = findMethod(Activity.class, "onPause");
            hook(onPause).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        Context c = ((Activity) chain.getThisObject()).getApplicationContext();
                        if (sAppCtx == null) sAppCtx = c;
                        flushLog(c);
                    } catch (Throwable ignored) {
                    }
                    return result;
                }
            });
            log("已挂载 Activity#onPause（日志兜底落盘）");
        } catch (Throwable t) {
            log("挂载 Activity#onPause 失败: " + t);
        }
    }

    /**
     * 朋友圈原图探测（诊断用，仅「朋友圈上传原图」开启时生效，默认零开销）：
     * 微信发朋友圈时会对大图做压缩再上传，真正的画质入口在压缩/编码阶段，
     * 不在 Intent extras（日志已实锤 SnsUploadUI 的 extras 无原图键）。
     * 这里 hook Bitmap.compress，当「面积较大（上传级）且调用栈来自 plugin.sns」时，
     * 打印完整调用栈——下一次开启「朋友圈上传原图」、进朋友圈点发送后导出的日志里，
     * 就能看到微信压缩图片的具体类名/方法，据此实现「复制法/转换法」真正绕过压缩。
     * 该 hook 只读、不改，且带面积阈值 + 次数上限，开关关闭时零开销。
     */







    private static void onResume(final Activity act) {
        if (act == null) return;
        final String cls = act.getClass().getName();
        sCurrentActivity = cls;   // 记录前台 Activity（供上下文感知强制使用）
        log("onResume [" + sProc + "] " + cls);

        if (!cEnabled) return;
        boolean gallery = looksLikeGallery(cls);
        // v8.4：相册界面(AlbumPreviewUI)聊天与朋友圈共用，类名分不出来。
        //      记住进入相册「之前」停留的界面，用它判断本次选图属于哪条流程。
        if (!gallery) {
            sLastNonGallery = cls;
            // v8.6：离开相册 = 本次选图流程结束。重置「用户取消原图」状态，
            //      下次进相册重新按「默认开启原图」处理。
            sChatRawOptOut = false;
            sGalleryEnterAt = 0L;
        } else if (sGalleryEnterAt == 0L) {
            // v8.6：本次选图流程开始计时（ImagePreviewUI 也算 gallery，同一流程内不重置）
            sGalleryEnterAt = System.currentTimeMillis();
        }
        // 更极致：非相册界面不心跳；仅首次 onResume 上报一次以证明注入成功
        if (!gallery && sReportedOnce) return;
        // 心跳 + 拉取开关（后台线程，不阻塞微信主线程）
        report(act.getApplicationContext(), cls);
        sReportedOnce = true;

        if (!gallery) return;     // 非相册界面：仅心跳，不做 extras 验证

        // 微信相册选择界面：把实际生效的原图/LivePhoto extras 打出来，强制是否生效一眼可见
        dumpIntentExtras(act);

        if (!cVerbose) {
            // 即使不抓 View 树，也确保上面的 extras 诊断已被写入 App 文件
            // （否则单次进入朋友圈后导出，会因 report 限流而漏掉这次诊断）。
            flushLog(act.getApplicationContext());
            return;
        }
        Handler h = ui();
        if (h == null) {
            flushLog(act.getApplicationContext());
            return;
        }
        // v8.1：同一界面只 dump 一次 View 树。一次 600+ 行，反复 dump 会把
        // 发表阶段的关键日志彻底淹没（实测 1418 行日志里 View 树占了一多半）。
        synchronized (sDumpedActs) {
            if (sDumpedActs.contains(cls)) {
                flushLog(act.getApplicationContext());
                return;
            }
            sDumpedActs.add(cls);
        }
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    View root = act.getWindow().getDecorView();
                    log("---- View 树 [" + cls + "]（同一界面仅首次打印）----");
                    dumpView(root, 0);
                    log("---- View 树结束 ----");
                } catch (Throwable t) {
                    log("dumpView error: " + t);
                }
                flushLog(act.getApplicationContext());
            }
        }, 800);
    }

    /**
     * 打印相册 Activity 实际收到的原图/LivePhoto extras —— 强制是否生效，一眼可见。
     */
    private static void dumpIntentExtras(Activity act) {
        try {
            Intent it = act.getIntent();
            if (it == null) { log("Intent extras: null"); return; }
            Bundle b = it.getExtras();
            if (b == null) { log("Intent extras: null"); return; }
            StringBuilder sb = new StringBuilder("Intent extras 关键项: ");
            String[] keys = {K_LIVE_AUTO, K_LIVE_QUERY, K_SEND_RAW, K_SHOW_RAW_BTN};
            boolean any = false;
            for (String k : keys) {
                if (b.containsKey(k)) {
                    sb.append(k).append('=').append(b.getBoolean(k)).append("  ");
                    any = true;
                }
            }
            if (!any) sb.append("(无已知键)");
            log(sb.toString());
            log("累计强制改写次数 = " + sForceCount);
        } catch (Throwable t) {
            log("dumpIntentExtras error: " + t);
        }
    }

    /** 立即把当前 PENDING 日志刷到 App 文件（绕过心跳限流），诊断抓取后确保数据落盘 */
    private static void flushLog(final Context ctx) {
        if (ctx == null) return;
        if (sAppCtx == null) sAppCtx = ctx;
        final String payload = drainPending();
        if (payload.length() == 0) return;
        final String ver = wxVersion(ctx);
        sLastFlushMs = System.currentTimeMillis();
        EXEC.execute(new Runnable() {
            @Override
            public void run() {
                // v8.1：整个 IPC 期间屏蔽自动落盘。ContentProvider.call 内部会开文件流，
                // 会被我们自己的 FileOutputStream 探针钩到 → log() → autoFlush() → 无限递归。
                sFlushing = true;
                try {
                    ContentResolver cr = ctx.getContentResolver();
                    Bundle in = new Bundle();
                    in.putString(Const.K_LAST_ACT, sCurrentActivity);
                    in.putString(Const.K_WX_VER, ver);
                    in.putString("proc", sProc);
                    in.putInt("forced", sForceCount);
                    in.putString(Const.KEY_LOG, payload);
                    cr.call(Uri.parse(Const.URI), Const.METHOD_REPORT, null, in);
                } catch (Throwable ignored) {
                } finally {
                    sFlushing = false;
                }
            }
        });
    }

    /**
     * v8.1 路径缩写：微信临时文件路径长达 150+ 字符，其中 100 字符是固定前缀。
     * 只保留「draft 目录名/文件名」，日志可读性大幅提升。
     */
    private static String shortPath(String path) {
        if (path == null) return "null";
        int i = path.indexOf("/draft/");
        if (i >= 0) return "…/draft/" + path.substring(i + 7);
        i = path.lastIndexOf('/');
        return i >= 0 ? "…/" + path.substring(i + 1) : path;
    }

    private static boolean looksLikeGallery(String cls) {
        if (cls == null) return false;
        String l = cls.toLowerCase(Locale.US);
        // 朋友圈发布界面也纳入，以便 dump 该界面的原图 extras 并帮助后续定位压缩类
        return l.contains("gallery") || l.contains("album") || l.contains("imagepreview")
                || l.contains("snsupload");
    }

    /** 朋友圈发布界面（com.tencent.mm.plugin.sns.ui.SnsUploadUI 等） */
    private static boolean isMomentsPublisher(String cls) {
        if (cls == null) return false;
        return cls.toLowerCase(Locale.US).contains("snsupload");
    }

    // ══════════════════════ 微信 UI 修复（重叠）══════════════════════

    /** 本次相册选图是否来自朋友圈流程（相册界面聊天/朋友圈共用，只能靠来源界面区分） */
    private static boolean fromMomentsFlow() {
        String s = sLastNonGallery;
        if (s == null) return false;
        String l = s.toLowerCase(Locale.US);
        return l.contains("plugin.sns") || l.contains("sns.ui");
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
        if (now - sLastReport < 1500) return; // 限流：1.5s 一次足够，减少 IPC 频率
        sLastReport = now;

        final String payload = drainPending();
        final String ver = wxVersion(ctx);
        EXEC.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ContentResolver cr = ctx.getContentResolver();
                    Bundle in = new Bundle();
                    // 键名必须用 Const 常量，与 StatusProvider 读取端严格对应
                    in.putString(Const.K_LAST_ACT, cls);   // 最近界面
                    in.putString(Const.K_WX_VER, ver);     // 微信版本
                    in.putString("proc", sProc);
                    in.putInt("forced", sForceCount);
                    if (payload.length() > 0) in.putString(Const.KEY_LOG, payload);

                    Bundle out = cr.call(Uri.parse(Const.URI),
                            Const.METHOD_REPORT, null, in);
                    if (out != null) {
                        cEnabled = out.getBoolean(Const.K_ENABLED, true);
                        cLive = out.getBoolean(Const.K_LIVE, true);
                        cOrig = out.getBoolean(Const.K_ORIG, true);
                        // v8.9：详细日志开关已移除，cVerbose 恒为 false，不再从 App 读取
                        cLog = out.getBoolean(Const.K_LOG, false);
                    }
                } catch (Throwable t) {
                    // 模块 App 被冻结/未安装时会走到这里，忽略即可
                }
            }
        });
    }

    /** 取微信版本号（宿主上下文的包名即为 com.tencent.mm）。入口阶段不会调用本方法。 */
    private static String wxVersion(Context ctx) {
        if (sWxVer != null) return sWxVer;
        try {
            PackageManager pm = ctx.getPackageManager();
            PackageInfo pi = pm.getPackageInfo(ctx.getPackageName(), 0);
            sWxVer = (pi.versionName != null) ? pi.versionName : "?";
        } catch (Throwable t) {
            sWxVer = "?";
        }
        return sWxVer;
    }

    // v8.14：朋友圈原图开关同步补拉已随该功能移除。

    // ══════════════════════════ 工具 ══════════════════════════

    /**
     * 反射找方法。先找公共方法（含继承），找不到再找声明方法并放开访问。
     * 注意：Bundle.putBoolean/getBoolean 定义在 BaseBundle，必须能查继承方法。
     * 调用方必须处于 try/catch(Throwable) 内（所有 hook 安装点均满足）。
     */
    private static Method findMethod(Class<?> cls, String name, Class<?>... pts)
            throws NoSuchMethodException {
        try {
            return cls.getMethod(name, pts);
        } catch (NoSuchMethodException e) {
            Method m = cls.getDeclaredMethod(name, pts);
            m.setAccessible(true);
            return m;
        }
    }

    /** 反射找构造器（先公共后声明）。调用方必须处于 try/catch(Throwable) 内。 */
    private static Constructor<?> findCtor(Class<?> cls, Class<?>... pts)
            throws NoSuchMethodException {
        try {
            return cls.getConstructor(pts);
        } catch (NoSuchMethodException e) {
            Constructor<?> c = cls.getDeclaredConstructor(pts);
            c.setAccessible(true);
            return c;
        }
    }

    private static Handler ui() {
        Handler h = sHandler;
        if (h != null) return h;
        synchronized (MainHook.class) {
            if (sHandler == null) {
                Looper l = Looper.getMainLooper();
                if (l == null) return null;   // 入口阶段直接放弃，绝不抛异常
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
        // 主开关：日志记录默认关闭，省电。心跳/强制计数不受影响（走独立通道）。
        if (!cLog) return;
        String line = stamp() + "  " + msg;
        try {
            android.util.Log.i(TAG, line);
        } catch (Throwable ignored) {
        }
        try {
            // v8.7：LibXposed 框架日志（LSPosed 管理器「日志」页可见）。
            // 经入口实例桥接；入口未初始化（非微信进程）时静默跳过。
            MainHook s = sSelf;
            if (s != null) s.log(android.util.Log.INFO, TAG, line);
        } catch (Throwable ignored) {
        }
        synchronized (PENDING) {
            PENDING.add(line);
            if (PENDING.size() > 4000) PENDING.remove(0);
        }
        // v8.1：自动落盘。发表主链路日志（SnsUploadUI.finish 后无 onResume）错过即丢失，
        // 故关键日志立即落盘；其余按 1.2s 节流。朋友圈原图功能已移除，关键标记恒为 false。
        autoFlush(false);
    }

    /**
     * v8.1 自动落盘：把内存里的日志推给 App，保证任何时刻「导出日志」都能拿到最新内容。
     * critical=true 时绕过节流立刻落盘（发表主链路的关键日志，错过就没了）。
     * sFlushing 守卫必不可少——落盘走 ContentProvider IPC，途中若触发探针再调 log()
     * 会无限递归。
     */
    private static void autoFlush(boolean critical) {
        if (sFlushing) return;
        Context ctx = sAppCtx;
        if (ctx == null) return;
        long now = System.currentTimeMillis();
        if (!critical && now - sLastFlushMs < FLUSH_INTERVAL_MS) return;
        sLastFlushMs = now;
        flushLog(ctx);
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
