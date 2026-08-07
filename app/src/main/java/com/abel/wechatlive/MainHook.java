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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

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
    // 会话内捕获的「原图」路径（相册选中时记下），供诊断与 v7.7 复制法定位压缩源
    private static final List<String> sSelectedPaths = new ArrayList<String>();

    // ── v7.7 复制法状态 ──
    // 最近一次在朋友圈压缩链里被微信读取的原始图片路径（FileInputStream 探针抓到）
    private static volatile String sLastOriginalPath;
    // 复制法覆盖进行中标记：避免覆盖动作自身打开的流再次触发探针/递归
    private static volatile boolean sCopying = false;
    // 记录微信正在写入的「基础临时文件」对应的 FileOutputStream 实例（WeakHashMap 避免泄漏）
    private static final Map<Object, String> sFosTemp = new WeakHashMap<Object, String>();

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
    // 详细日志(导出 View 树)默认关闭——这是最大的功耗点，排障时再打开
    private static volatile boolean cVerbose = false;
    // 日志记录(写入 App 文件，供导出/排查)默认关闭——省电，心跳自检不受影响
    private static volatile boolean cLog = false;
    // 朋友圈上传原图（默认关闭；开启后朋友圈发布界面会强制原图键，可能与「制作视频」按钮重叠）
    private static volatile boolean cMomentsRaw = false;

    // ⚠️ 绝不能是 static final 直接 new —— 见类注释
    private static volatile Handler sHandler;
    private static volatile SimpleDateFormat sFmt;

    // ══════════════════════════ 入口 ══════════════════════════

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!Const.WECHAT_PKG.equals(lp.packageName)) return;
        sProc = lp.processName;

        log("========================================");
        log("WechatLive v7.8 注入成功  proc=" + sProc);

        // 相册只在主进程，重量级 hook 只装主进程，避免 :push/:appbrand 等无谓开销
        boolean main = Const.WECHAT_PKG.equals(sProc);
        if (!main) {
            log("非主进程，跳过 hook 安装");
            return;
        }

        installExtraForcing();
        installLifecycle();
        installCompressProbe();
        installMomentsProbe();
        installPathCapture();
        installMomentsCopy();   // v7.7：复制法（用原图替换朋友圈上传临时文件，绕过压缩）
        installMomentsScaleProbe(); // v7.8：朋友圈缩放探测（定位内存降采样入口，若注入仍压缩则精准定位）
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
            // 原图按钮/原图开关：相册/聊天发送流程强开；
            // 朋友圈发布界面默认排除（避免与「制作视频」重叠的幽灵按钮）。
            if (!isMomentsPublisher(sCurrentActivity)) {
                if (K_SEND_RAW.equals(key)) return Boolean.TRUE;
                if (K_SHOW_RAW_BTN.equals(key)) return Boolean.TRUE;
            }
        }
        if (cMomentsRaw && (isMomentsPublisher(sCurrentActivity) || looksLikeGallery(sCurrentActivity))) {
            // 朋友圈上传原图：单独开关控制。开启后在「相册选择界面」(构建启动 SnsUploadUI 的
            // Intent 时)与 SnsUploadUI 自身都强制原图键，确保键真实存在(containsKey 通过)，
            // 而非仅在读取侧覆盖值——实测 SnsUploadUI 的 Intent 不含该键会导致强制失效。
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
        // onCreate 时尽早记录前台 Activity（早于 onResume），用于上下文感知强制，
        // 避免朋友圈发布界面在 onCreate 阶段就被误强制出幽灵原图按钮
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                sCurrentActivity = param.thisObject.getClass().getName();
                                // v7.8：朋友圈发布界面直接把原图键注入 Intent，保证键真实存在。
                                // 读取侧覆盖值(旧方案)在 SnsUploadUI 的 Intent 不含该键时无效
                                // （微信可能靠 containsKey 判断），这里在 WeChat 读取前写入 Bundle。
                                if (cMomentsRaw && isMomentsPublisher(sCurrentActivity)) {
                                    Intent it = ((Activity) param.thisObject).getIntent();
                                    if (it != null) {
                                        it.putExtra(K_SEND_RAW, true);
                                        it.putExtra(K_SHOW_RAW_BTN, true);
                                        log("★ [朋友圈注入] SnsUploadUI Intent 已注入 send_raw_img=true");
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            log("已挂载 Activity#onCreate");
        } catch (Throwable t) {
            log("挂载 Activity#onCreate 失败: " + t);
        }
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

    /**
     * 朋友圈原图探测（诊断用，仅「朋友圈上传原图」开启时生效，默认零开销）：
     * 微信发朋友圈时会对大图做压缩再上传，真正的画质入口在压缩/编码阶段，
     * 不在 Intent extras（日志已实锤 SnsUploadUI 的 extras 无原图键）。
     * 这里 hook Bitmap.compress，当「面积较大（上传级）且调用栈来自 plugin.sns」时，
     * 打印完整调用栈——下一次开启「朋友圈上传原图」、进朋友圈点发送后导出的日志里，
     * 就能看到微信压缩图片的具体类名/方法，据此实现「复制法/转换法」真正绕过压缩。
     * 该 hook 只读、不改，且带面积阈值 + 次数上限，开关关闭时零开销。
     */
    private void installCompressProbe() {
        try {
            XposedHelpers.findAndHookMethod(android.graphics.Bitmap.class,
                    "compress",
                    android.graphics.Bitmap.CompressFormat.class,
                    int.class,
                    java.io.OutputStream.class,
                    new XC_MethodHook() {
                        private int logged = 0;
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            if (!cMomentsRaw || logged >= 12) return;
                            try {
                                android.graphics.Bitmap bmp = (android.graphics.Bitmap) p.thisObject;
                                int area = bmp.getWidth() * bmp.getHeight();
                                if (area < 100000) return; // 只关心上传级大图压缩，忽略缩略图
                                StackTraceElement[] st = new Throwable().getStackTrace();
                                boolean sns = false;
                                StringBuilder sb = new StringBuilder();
                                for (StackTraceElement e : st) {
                                    String cn = e.getClassName();
                                    if (cn.contains("plugin.sns") || cn.contains(".sns.")
                                            || cn.endsWith(".sns.ui") || cn.contains("sns.model")) {
                                        sns = true;
                                    }
                                    if (sb.length() < 1400) {
                                        sb.append("\n    ").append(cn).append('.').append(e.getMethodName());
                                    }
                                    if (sb.length() >= 1400) break;
                                }
                                if (!sns) return;
                                logged++;
                                log("★ [朋友圈压缩探测] Bitmap.compress 面积=" + area
                                        + " quality=" + p.args[1] + " 调用栈(top→底):" + sb);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            log("已挂载 Bitmap.compress 朋友圈压缩探测（开启「朋友圈上传原图」后，发朋友圈可抓压缩栈）");
        } catch (Throwable t) {
            log("挂载 Bitmap.compress 探测失败: " + t);
        }
    }

    /**
     * 朋友圈原图「复制法」定位探针（诊断用，仅「朋友圈上传原图」开启时生效，默认零开销）。
     * 实测：微信发朋友圈的压缩不走 Java Bitmap.compress（v7.4 探针零命中），而是通过文件写出
     * 完成（v7.5 已实锤微信文件写经 com.tencent.mm.vfs → java.io.FileOutputStream，`.ini` 即此路径）。
     * 这里钩住三处文件操作，凡路径命中朋友圈图片相关目录/扩展名，即打印：路径 + 文件大小 + 完整调用栈。
     *   ① java.io.FileOutputStream —— 压缩结果落盘（上传临时文件）
     *   ② java.io.RandomAccessFile —— 部分写盘路径（防 Bitmap.compress 之外的 native 旁路）
     *   ③ android.graphics.BitmapFactory.decodeFile —— 微信读取草稿/原图准备处理
     * 去重按「完整路径」(每个临时文件只报一次)，写 60 次 / 读 30 次 / RAF 20 次上限。
     * 开关关闭时完全不挂载热路径（零开销）。
     */
    private void installMomentsProbe() {
        XC_MethodHook logWrite = new XC_MethodHook() {
            private int logged = 0;
            private final Set<String> seen = new HashSet<String>();
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (sCopying || !cMomentsRaw || logged >= 60) return;
                try {
                    Object a0 = p.args[0];
                    String path = a0 instanceof File ? ((File) a0).getAbsolutePath()
                            : (a0 instanceof String ? (String) a0 : null);
                    if (path == null || !isMomentsFile(path)) return;
                    // 记录「图片类临时文件」(base / _parse / _remux_thumb，排除纯视频 remux)，
                    // 供复制法在流关闭后把原图覆盖进去。
                    if (isImageTemp(path)) {
                        sFosTemp.put(p.thisObject, path);
                    }
                    if (seen.contains(path)) return;
                    seen.add(path);
                    logged++;
                    long sz = safeSize(path);
                    StringBuilder sb = new StringBuilder();
                    for (StackTraceElement e : new Throwable().getStackTrace()) {
                        if (sb.length() < 3000) sb.append("\n    ").append(e.getClassName()).append('.').append(e.getMethodName());
                        else break;
                    }
                    log("★ [朋友圈写探测] path=" + path + " size=" + sz + " 调用栈(top→底):" + sb);
                } catch (Throwable ignored) {
                }
            }
        };
        try { XposedHelpers.findAndHookConstructor(FileOutputStream.class, File.class, logWrite); }
        catch (Throwable t) { log("hook FOS(File) 失败: " + t); }
        try { XposedHelpers.findAndHookConstructor(FileOutputStream.class, File.class, boolean.class, logWrite); }
        catch (Throwable t) { log("hook FOS(File,bool) 失败: " + t); }
        try { XposedHelpers.findAndHookConstructor(FileOutputStream.class, String.class, logWrite); }
        catch (Throwable t) { log("hook FOS(String) 失败: " + t); }
        try { XposedHelpers.findAndHookConstructor(FileOutputStream.class, String.class, boolean.class, logWrite); }
        catch (Throwable t) { log("hook FOS(String,bool) 失败: " + t); }

        XC_MethodHook logRaf = new XC_MethodHook() {
            private int logged = 0;
            private final Set<String> seen = new HashSet<String>();
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (!cMomentsRaw || logged >= 20) return;
                try {
                    Object a0 = p.args[0];
                    String path = a0 instanceof File ? ((File) a0).getAbsolutePath()
                            : (a0 instanceof String ? (String) a0 : null);
                    if (path == null || !isMomentsFile(path)) return;
                    if (seen.contains(path)) return;
                    seen.add(path);
                    logged++;
                    StringBuilder sb = new StringBuilder();
                    for (StackTraceElement e : new Throwable().getStackTrace()) {
                        if (sb.length() < 3000) sb.append("\n    ").append(e.getClassName()).append('.').append(e.getMethodName());
                        else break;
                    }
                    log("★ [朋友圈RandomAccessFile探测] path=" + path + " 调用栈(top→底):" + sb);
                } catch (Throwable ignored) {
                }
            }
        };
        try { XposedHelpers.findAndHookConstructor(RandomAccessFile.class, File.class, String.class, logRaf); }
        catch (Throwable t) { log("hook RAF(File,mode) 失败: " + t); }
        try { XposedHelpers.findAndHookConstructor(RandomAccessFile.class, String.class, String.class, logRaf); }
        catch (Throwable t) { log("hook RAF(String,mode) 失败: " + t); }

        try {
            XposedHelpers.findAndHookMethod(android.graphics.BitmapFactory.class, "decodeFile",
                    String.class, new XC_MethodHook() {
                        private int logged = 0;
                        private final Set<String> seen = new HashSet<String>();
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            if (!cMomentsRaw || logged >= 30) return;
                            try {
                                String path = (String) p.args[0];
                                if (path == null || !isMomentsFile(path)) return;
                                if (seen.contains(path)) return;
                                seen.add(path);
                                logged++;
                                StringBuilder sb = new StringBuilder();
                                for (StackTraceElement e : new Throwable().getStackTrace()) {
                                    if (sb.length() < 3000) sb.append("\n    ").append(e.getClassName()).append('.').append(e.getMethodName());
                                    else break;
                                }
                                log("★ [朋友圈读探测] path=" + path + " 调用栈(top→底):" + sb);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
        } catch (Throwable t) { log("hook BitmapFactory.decodeFile 失败: " + t); }

        log("已挂载朋友圈文件读写探针(v7.6: 命中即报路径+大小+完整栈；发朋友圈并点发表后可抓压缩/读取栈)");
    }

    /**
     * v7.7 复制法核心：用「用户选的原图」替换微信为朋友圈生成的上传临时文件(pre_temp_sns_live_photo*)，
     * 从而绕过微信的压缩管线，发出原图。
     *
     * 原理（基于 v7.5/v7.6 日志实锤）：
     *   进入 SnsUploadUI 时，微信在链
     *     SnsUploadUI.V6 → gf4.a.h → plugin.sns.ui.n1.h → hf4.b1.h → hf4.b1.s → hf4.l0.p → lf4.a.d → vfs.w6.d → FileOutputStream(pre_temp_sns_live_photo*)
     *   里把原图读出来、编码/缩放后写到 MicroMsg 的 draft 临时文件——这个临时文件就是最终上传的内容。
     *
     * 做法：
     *   ① 用 FileInputStream 探针，在「朋友圈压缩链」里抓到微信读取的【原始图片路径】(sLastOriginalPath)。
     *      （实测 BitmapFactory.decodeFile 零命中，说明微信走流拷贝而非 decodeFile，故必须钩 FileInputStream）
     *   ② 在 FileOutputStream 关闭后，若它是「基础临时文件」，则把对应原图字节覆盖进去
     *      （按"已关闭基础临时文件计数"顺序映射到 sSelectedPaths，使第 N 个临时文件 ↔ 第 N 张原图）。
     *
     * 仅「朋友圈上传原图」开启时挂载热路径；其余情况零开销。覆盖动作一次成功即停（避免反复写）。
     */
    private void installMomentsCopy() {
        // ① 源捕获：微信在压缩链里用 FileInputStream 读原图
        XC_MethodHook srcHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (sCopying || !cMomentsRaw) return;
                try {
                    Object a0 = p.args[0];
                    String path = a0 instanceof File ? ((File) a0).getAbsolutePath()
                            : (a0 instanceof String ? (String) a0 : null);
                    if (path == null) return;
                    String low = path.toLowerCase(Locale.US);
                    // 原图在 DCIM/Pictures 等外部目录，不在 MicroMsg 草稿里；且是图片
                    if (path.contains("/MicroMsg/")) return;
                    if (!(low.endsWith(".jpg") || low.endsWith(".jpeg") || low.endsWith(".png")
                            || low.endsWith(".webp"))) return;
                    // 调用栈必须来自朋友圈压缩链，避免误抓其它读图
                    StackTraceElement[] st = new Throwable().getStackTrace();
                    boolean sns = false;
                    for (StackTraceElement e : st) {
                        String cn = e.getClassName();
                        if (cn.contains("plugin.sns") || cn.contains("hf4") || cn.contains("lf4")
                                || cn.contains("n1.") || cn.contains("gf4") || cn.contains("b1.")
                                || cn.contains("l0.") || cn.contains("vfs")) {
                            sns = true;
                            break;
                        }
                    }
                    if (!sns) return;
                    sLastOriginalPath = path;
                    log("★ [朋友圈原图读取] path=" + path);
                } catch (Throwable ignored) {
                }
            }
        };
        try { XposedHelpers.findAndHookConstructor(FileInputStream.class, File.class, srcHook); }
        catch (Throwable t) { log("hook FIS(File) 失败: " + t); }
        try { XposedHelpers.findAndHookConstructor(FileInputStream.class, String.class, srcHook); }
        catch (Throwable t) { log("hook FIS(String) 失败: " + t); }

        // ② 覆盖：FileOutputStream 关闭后，把对应原图写回基础临时文件
        try {
            XposedHelpers.findAndHookMethod(FileOutputStream.class, "close", new XC_MethodHook() {
                @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (sCopying || !cMomentsRaw) return;
                try {
                    String temp = sFosTemp.remove(p.thisObject);
                    if (temp == null) return;
                    if (isVideoRemux(temp)) return;   // 实况视频流，绝不覆盖
                    // 用最近一次在朋友圈压缩链里抓到的原图覆盖（FIS 探针比 Intent 路径更可靠）
                    if (sLastOriginalPath != null) {
                        overwriteTempWithOriginal(temp, sLastOriginalPath);
                    }
                } catch (Throwable ignored) {
                }
                }
            });
            log("已挂载 朋友圈复制法（原图覆盖上传临时文件，v7.7）");
        } catch (Throwable t) {
            log("hook FileOutputStream.close 失败: " + t);
        }
    }

    /**
     * v7.8 朋友圈缩放探测：定位微信「内存降采样」入口。
     * v7.7 复制法对实况图无效——日志实锤 base temp 在 SnsUploadUI.onCreate 就已是原图大小，
     * 发表时微信在内存里把原图降采样后直接上传，根本不落我们能覆盖的临时文件。
     * 因此真正的画质开关是「让微信自己决定不降采样」，而这依赖 send_raw_img 键。
     * 本探测 hook Bitmap.createScaledBitmap / ThumbnailUtils.extractThumbnail，
     * 在 sns 上下文、源图较大时打印 源尺寸→目标尺寸，便于 v7.8 若注入仍压缩时精准定位压缩函数。
     * 仅「朋友圈上传原图」开启时挂载，限次 10，普通缩略图忽略，开关关闭零开销。
     */
    private void installMomentsScaleProbe() {
        XC_MethodHook scale = new XC_MethodHook() {
            private int logged = 0;
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (!cMomentsRaw || logged >= 10) return;
                try {
                    android.graphics.Bitmap src = (android.graphics.Bitmap) p.args[0];
                    if (src == null || src.isRecycled()) return;
                    int sw = src.getWidth(), sh = src.getHeight();
                    if (sw * sh < 100000) return;   // 只关心上传级大图降采样
                    int dw = (Integer) p.args[1];
                    int dh = (Integer) p.args[2];
                    logged++;
                    log("★ [朋友圈缩放探测] 源=" + sw + "x" + sh
                            + " -> 目标=" + dw + "x" + dh + "  " + p.method.getName());
                } catch (Throwable ignored) {
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod(android.graphics.Bitmap.class, "createScaledBitmap",
                    android.graphics.Bitmap.class, int.class, int.class, boolean.class, scale);
            log("已挂载 Bitmap.createScaledBitmap 缩放探测");
        } catch (Throwable t) {
            log("hook createScaledBitmap 失败: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(android.media.ThumbnailUtils.class, "extractThumbnail",
                    android.graphics.Bitmap.class, int.class, int.class, scale);
            XposedHelpers.findAndHookMethod(android.media.ThumbnailUtils.class, "extractThumbnail",
                    android.graphics.Bitmap.class, int.class, int.class, int.class, scale);
            log("已挂载 ThumbnailUtils.extractThumbnail 缩放探测");
        } catch (Throwable t) {
            log("hook ThumbnailUtils 失败: " + t);
        }
    }

    /** 是否「图片类临时文件」：pre_temp_sns_live_photo* 中、排除纯视频 remux（无 _thumb 后缀） */
    private static boolean isImageTemp(String path) {
        if (path == null) return false;
        String name = new File(path).getName();
        if (!name.startsWith("pre_temp_sns_live_photo")) return false;
        // 纯视频 remux（pre_temp_sns_live_photo_remux_<32hex>，无 _thumb）= 实况的视频流，
        // 不能用原图覆盖；其余（base / _parse_<hash> / _remux_thumb_<hash>）都是图片，可覆盖。
        return !name.matches("pre_temp_sns_live_photo_remux_[0-9a-fA-F]{32}");
    }

    /** 是否纯视频 remux（实况视频流），复制法必须跳过，否则会把视频写成图片字节 */
    private static boolean isVideoRemux(String path) {
        if (path == null) return false;
        return new File(path).getName().matches("pre_temp_sns_live_photo_remux_[0-9a-fA-F]{32}");
    }

    /** 把原图覆盖进朋友圈上传临时文件（复制法核心动作）。一次成功即停，避免反复写。 */
    private static void overwriteTempWithOriginal(String temp, String original) {
        if (sCopying) return;          // 防止覆盖动作自身的流递归触发
        sCopying = true;
        try {
            File src = new File(original);
            File dst = new File(temp);
            if (!src.exists() || src.length() < 1024) {
                log("复制法跳过: 原图不存在或过小 original=" + original);
                return;
            }
            long cur = dst.length();
            if (cur == src.length() && dst.exists()) {
                log("复制法跳过: 临时文件已为原图大小 temp=" + temp);
                return;
            }
            FileInputStream in = new FileInputStream(src);
            java.io.FileOutputStream out = new java.io.FileOutputStream(dst, false);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            out.close();
            log("★ [复制法] 已用原图覆盖上传临时文件 temp=" + temp
                    + " 原图=" + original + " size=" + dst.length());
        } catch (Throwable t) {
            log("复制法覆盖失败: temp=" + temp + " err=" + t);
        } finally {
            sCopying = false;
        }
    }

    /** 是否朋友圈相关图片文件：MicroMsg 目录下、命中草稿/临时/上传目录或图片扩展名（排除 db/ini） */
    private static boolean isMomentsFile(String path) {
        if (path == null || !path.contains("/MicroMsg/")) return false;
        String low = path.toLowerCase(Locale.US);
        if (low.endsWith(".ini") || low.endsWith(".db") || low.endsWith(".db-wal")
                || low.endsWith(".db-shm") || low.endsWith(".xml")) return false;
        return low.contains("pre_temp") || low.contains("/draft/") || low.contains("sns")
                || low.endsWith(".jpg") || low.endsWith(".jpeg") || low.endsWith(".png")
                || low.endsWith(".webp");
    }

    private static long safeSize(String path) {
        try { return new File(path).length(); } catch (Throwable t) { return -1L; }
    }

    /**
     * 捕获相册选中的「原图」路径（只读，存入会话列表 sSelectedPaths）。
     * 朋友圈真正的画质入口是「压缩源文件路径」——复制法需要把上传临时文件替换成这张原图。
     * 这里在 Intent.putExtra(String, 路径列表) 时记下图片/视频路径，供下一版(复制法)使用，
     * 也便于本次日志直接看到「用户选的原图」与「微信压缩写出的临时文件」的对应关系。
     * 开关关闭时零开销。
     */
    private void installPathCapture() {
        XC_MethodHook cap = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (!cMomentsRaw) return;
                try {
                    String k = (String) p.args[0];
                    if (k == null) return;
                    String kl = k.toLowerCase(Locale.US);
                    if (!(kl.contains("path") || kl.contains("media") || kl.contains("image")
                            || kl.contains("sns") || kl.contains("select"))) return;
                    Object v = p.args[1];
                    if (v instanceof ArrayList) {
                        for (Object o : (ArrayList<?>) v) if (o instanceof String) addSelected((String) o);
                    } else if (v instanceof String[]) {
                        for (String s : (String[]) v) if (s != null) addSelected(s);
                    } else if (v instanceof String) {
                        addSelected((String) v);
                    }
                } catch (Throwable ignored) {
                }
            }
        };
        try { XposedHelpers.findAndHookMethod(Intent.class, "putExtra", String.class, ArrayList.class, cap); }
        catch (Throwable t) { log("hook putExtra(ArrayList) 失败: " + t); }
        try { XposedHelpers.findAndHookMethod(Intent.class, "putExtra", String.class, String[].class, cap); }
        catch (Throwable t) { log("hook putExtra(String[]) 失败: " + t); }
        try { XposedHelpers.findAndHookMethod(Intent.class, "putExtra", String.class, String.class, cap); }
        catch (Throwable t) { log("hook putExtra(String) 失败: " + t); }
        log("已挂载相册选中原图路径捕获（朋友圈上传原图开启时生效）");
    }

    private static void addSelected(String s) {
        if (s == null || s.length() < 4) return;
        String low = s.toLowerCase(Locale.US);
        if (low.endsWith(".jpg") || low.endsWith(".jpeg") || low.endsWith(".png")
                || low.endsWith(".webp") || low.endsWith(".mp4") || low.endsWith(".mov")) {
            synchronized (sSelectedPaths) {
                if (!sSelectedPaths.contains(s)) sSelectedPaths.add(s);
                if (sSelectedPaths.size() > 30) sSelectedPaths.remove(0);
            }
        }
    }

    private static void onResume(final Activity act) {
        if (act == null) return;
        final String cls = act.getClass().getName();
        sCurrentActivity = cls;   // 记录前台 Activity（供上下文感知强制使用）
        log("onResume [" + sProc + "] " + cls);

        if (!cEnabled) return;
        boolean gallery = looksLikeGallery(cls);
        boolean moments = isMomentsPublisher(cls);
        // 更极致：非相册界面不心跳；仅首次 onResume 上报一次以证明注入成功
        if (!gallery && sReportedOnce) return;
        // 心跳 + 拉取开关（后台线程，不阻塞微信主线程）
        report(act.getApplicationContext(), cls);
        sReportedOnce = true;

        if (!gallery) return;     // 非相册界面：仅心跳，不做 extras 验证

        // 微信相册选择界面（非 SnsUploadUI 发布界面）：修复「原图」与「制作视频」按钮重叠
        if (cMomentsRaw && !moments) {
            fixMomentsButtonOverlap(act);
        }

        // 相册 / 朋友圈发布界面：把实际生效的 extras 打出来
        dumpIntentExtras(act, moments);

        if (moments) {
            log("★ 朋友圈发布界面：已抓取该界面全部 Intent extras（见上方）。");
            log("  诊断已落盘——请在本界面停留约 1 秒，再回 App「导出日志」即可拿到完整抓取。");
            log("  如需 View 树，请开启「详细日志」后重新进入本界面。");
            StringBuilder ps = new StringBuilder("★ 本次会话已捕获原图路径(Intent=" + sSelectedPaths.size()
                    + "  FileInputStream探针=" + (sLastOriginalPath != null ? 1 : 0) + "):");
            synchronized (sSelectedPaths) {
                for (String s : sSelectedPaths) ps.append("\n    [Intent] ").append(s);
            }
            if (sLastOriginalPath != null) ps.append("\n    [FileInputStream] ").append(sLastOriginalPath);
            log(ps.toString());
        }

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
                flushLog(act.getApplicationContext());
            }
        }, 800);
    }

    /**
     * 打印相册/朋友圈 Activity 实际收到的 extras —— 强制成功与否，一眼可见。
     * 朋友圈走 moments 分支：枚举全部 extras 键，找出原图/画质相关键，
     * 为后续「转换法/复制法」定位微信压缩入口提供线索。
     */
    private static void dumpIntentExtras(Activity act, boolean moments) {
        try {
            Intent it = act.getIntent();
            if (it == null) { log("Intent extras: null"); return; }
            Bundle b = it.getExtras();
            if (b == null) { log("Intent extras: null"); return; }
            if (moments) {
                StringBuilder sb = new StringBuilder(
                        "Intent extras(全部, 朋友圈) [count=" + b.size() + "]: ");
                boolean first = true;
                for (String k : b.keySet()) {
                    if (!first) sb.append("  ");
                    first = false;
                    sb.append(k).append('=').append(truncate(b.get(k)));
                }
                log(sb.toString());
            } else {
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
            }
            log("累计强制改写次数 = " + sForceCount);
        } catch (Throwable t) {
            log("dumpIntentExtras error: " + t);
        }
    }

    /** 值可能很大（Parcelable / 数组），截短以免刷屏 */
    private static String truncate(Object v) {
        if (v == null) return "null";
        String s = v.toString();
        if (s.length() > 90) s = s.substring(0, 90) + "...(len=" + s.length() + ")";
        return s;
    }

    /** 立即把当前 PENDING 日志刷到 App 文件（绕过心跳限流），诊断抓取后确保数据落盘 */
    private static void flushLog(final Context ctx) {
        if (ctx == null) return;
        final String payload = drainPending();
        if (payload.length() == 0) return;
        final String ver = wxVersion(ctx);
        EXEC.execute(new Runnable() {
            @Override
            public void run() {
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
                }
            }
        });
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

    /**
     * 修复微信相册选择界面「原图」与「制作视频」按钮重叠。
     * 根因：模块在相册里强制 key_force_show_raw_image_button=true，让微信渲染出「原图」按钮，
     * 它和「制作视频」按钮在同一个底栏撞在一起。本方法在布局完成后把「原图」左移；
     * 若已贴左仍重叠，则把「制作视频」右移。纯视觉、try/catch 包裹，仅 cMomentsRaw 开启时执行。
     */
    private static void fixMomentsButtonOverlap(final Activity act) {
        final Handler h = ui();
        if (h == null) return;
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    View root = act.getWindow().getDecorView();
                    TextView yuan = findText(root, "原图");
                    View make = findTextContains(root, "制作视频");
                    if (yuan == null || make == null) return;  // 两个按钮没同时出现，无需修复
                    yuan.setTranslationX(0);
                    make.setTranslationX(0);
                    int[] rl = new int[2], ml = new int[2];
                    yuan.getLocationOnScreen(rl);
                    make.getLocationOnScreen(ml);
                    int gap = dpAct(act, 8);
                    int yuanRight = rl[0] + yuan.getWidth();
                    int makeLeft = ml[0];
                    if (yuanRight > makeLeft - gap) {
                        int shift = yuanRight - (makeLeft - gap);   // 需要左移的像素
                        int minLeft = dpAct(act, 4);
                        int allowed = rl[0] - minLeft;              // 最多左移到距屏左 4dp
                        int applied = Math.min(shift, allowed);
                        if (applied > 0) {
                            yuan.setTranslationX(-applied);
                            log("★ [UI] 原图左移 " + applied + "px 以避免与「制作视频」重叠");
                        } else {
                            // 原图已贴左，改为把「制作视频」右移
                            int need = yuanRight - makeLeft + gap;
                            if (need > 0) {
                                make.setTranslationX(need);
                                log("★ [UI] 「制作视频」右移 " + need + "px 以避免与「原图」重叠");
                            }
                        }
                    }
                } catch (Throwable t) {
                    log("UI 重叠修复失败: " + t);
                }
            }
        }, 450);
    }

    private static TextView findText(View v, String exact) {
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && t.toString().trim().equals(exact)) return (TextView) v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView r = findText(g.getChildAt(i), exact);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static View findTextContains(View v, String sub) {
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && t.toString().contains(sub)) return v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findTextContains(g.getChildAt(i), sub);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static int dpAct(Activity act, int v) {
        try {
            return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                    act.getResources().getDisplayMetrics());
        } catch (Throwable t) {
            return v * 3;
        }
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
                        cVerbose = out.getBoolean(Const.K_VERBOSE, false);
                        cLog = out.getBoolean(Const.K_LOG, false);
                        cMomentsRaw = out.getBoolean(Const.K_MOMENTS_RAW, false);
                    }
                } catch (Throwable t) {
                    // 模块 App 被冻结/未安装时会走到这里，忽略即可
                }
            }
        });
    }

    /** 取微信版本号（宿主上下文的包名即为 com.tencent.mm）。zygote 阶段不会调用本方法。 */
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
        // 主开关：日志记录默认关闭，省电。心跳/强制计数不受影响（走独立通道）。
        if (!cLog) return;
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
