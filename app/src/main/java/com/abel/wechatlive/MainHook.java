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
import java.util.HashMap;
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

    // ── v7.9 缩放拦截状态（全部限次，避免异常时刷屏/连锁影响）──
    private static volatile int sScaleLogged = 0;      // createScaledBitmap 探测日志次数
    private static volatile int sBlocked = 0;          // createScaledBitmap 拦截次数
    private static volatile int sMatrixBlocked = 0;    // createBitmap(Matrix) 拦截次数
    private static volatile int sDecodeBlocked = 0;    // BitmapFactory 解码降采样拦截次数
    private static volatile boolean sLibsDumped = false;

    // ── v8.0 原图直塞状态 ──
    // 会话内抓到的「用户相册真实照片」候选（FileInputStream 探针过滤后写入）
    private static final List<String> sPhotoCandidates = new ArrayList<String>();
    // 原图宽高缓存，避免每次 compress 都重新解码 bounds
    private static final Map<String, int[]> sBoundsCache = new HashMap<String, int[]>();

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
    // v8.1 目录兜底扫描：候选池匹配不上时，从 DCIM/Pictures 按尺寸找原图
    private static volatile boolean sDirScanned = false;
    private static final List<String> sDirPhotos = new ArrayList<String>();
    // v8.1 直塞决策日志次数（未命中时说明原因，限次避免刷屏）
    private static volatile int sDecisionLogged = 0;
    // 直塞/探测自身产生的 IO 标记，防止递归触发各类探针
    private static volatile boolean sInjecting = false;
    private static volatile int sRawInjected = 0;      // 原图字节直塞成功次数
    private static volatile int sQualityBoost = 0;     // quality 拉满次数
    // v8.2 复制法：按尺寸匹配原图覆盖上传临时文件
    private static volatile int sCopyInjected = 0;     // 复制法「实际写入」次数
    private static volatile int sCopyLogged = 0;       // 复制法跳过说明限次
    private static volatile int sMainProbe = 0;        // 主图 compress 到达诊断限次
    // v8.3：temp 本来就等于原图字节（微信原图模式已生效，无需覆盖）——与实际写入分开统计，
    //       v8.2 把这两种情况都算成「注入成功」，导致统计虚高、误判。
    private static volatile int sRawSame = 0;
    private static final int COPY_FAIL = 0, COPY_SAME = 1, COPY_WRITTEN = 2;
    // v8.3 产物核验：微信可能在流关闭「之后」再次重编码上传文件，延迟回查并纠正
    private static final Set<String> sDraftDirs = new HashSet<String>();
    private static volatile long sSweepAt = 0L;        // 上次安排核验的时间，去重
    private static volatile boolean sSweeping = false; // 核验中：防止自身 IO 触发探针递归
    private static volatile int sSweepLogged = 0;      // 核验日志限次
    // v8.4 关键：temp → 该 temp 对应的原图。核验必须按这张映射回写，
    //      绝不能再用 findOriginalForDims 按尺寸重查——多图同尺寸时会全部命中同一张，
    //      把已正确的文件覆盖成别人的照片（v8.3 实测九宫格出现重复图）。
    private static final Map<String, String> sTempOrig = new HashMap<String, String>();
    // v8.4：进入相册前的来源界面。用于区分「朋友圈选图」与「聊天选图」——
    //      两者共用 AlbumPreviewUI，仅靠当前 Activity 类名无法分辨。
    private static volatile String sLastNonGallery = "";

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
        log("WechatLive v8.5 注入成功  proc=" + sProc);

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
                                // v8.1：尽早拿到 ApplicationContext，让自动落盘从第一个 Activity 就能工作
                                if (sAppCtx == null) {
                                    sAppCtx = ((Activity) param.thisObject).getApplicationContext();
                                }
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
                                    // v7.9：此刻微信的图像 native 库已加载，采集一次用于定位编码器
                                    dumpImageNativeLibs();
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
        // v8.1：onPause 兜底落盘。点「发表」后 SnsUploadUI 会 finish，
        // onPause 是它生命周期里最后一个可靠回调，此时把日志刷出去。
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onPause", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Context c = ((Activity) param.thisObject).getApplicationContext();
                        if (sAppCtx == null) sAppCtx = c;
                        flushLog(c);
                    } catch (Throwable ignored) {
                    }
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
    private void installCompressProbe() {
        try {
            XposedHelpers.findAndHookMethod(android.graphics.Bitmap.class,
                    "compress",
                    android.graphics.Bitmap.CompressFormat.class,
                    int.class,
                    java.io.OutputStream.class,
                    new XC_MethodHook() {
                        private int logged = 0;

                        /** v8.0：在编码前介入——首选原图字节直塞，兜底把 quality 拉满 */
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            if (!cMomentsRaw || sInjecting) return;
                            try {
                                android.graphics.Bitmap bmp = (android.graphics.Bitmap) p.thisObject;
                                if (bmp == null || bmp.isRecycled()) return;
                                int w = bmp.getWidth(), h = bmp.getHeight();
                                // 只处理「上传级主图」：>200 万像素。缩略图一律不动。
                                if ((long) w * h < 2000000L) return;
                                // v8.2：放宽门槛。主图 compress 可能在「上传线程」上、调用栈不含
                                // plugin.sns（缩略图却在），故改为「当前界面是朋友圈/相册」或「栈含 sns」任一即可。
                                boolean near = nearSns();
                                boolean ins = inSnsStack();
                                if (!near && !ins) return;
                                // 诊断：确认主图 compress 是否真的走到这里（之前日志里完全看不到主图 compress）
                                if (sMainProbe < 4) {
                                    sMainProbe++;
                                    log("★ [主图compress] reached " + w + "x" + h
                                            + " nearSns=" + near + " inSnsStack=" + ins);
                                }

                                boolean jpeg = String.valueOf(p.args[0]).toUpperCase(Locale.US).contains("JPEG");

                                // ① 首选：把原始 JPEG 文件字节直接写进输出流。
                                //    这样微信拿到的就是磁盘上那份原图本身——画质 100% 无损，
                                //    且 EXIF（APP1 段在文件头部）原样保留。
                                if (jpeg && sRawInjected < 8) {
                                    String src = pickOriginalFor(w, h);
                                    if (src != null) {
                                        long n = pumpOriginalInto(src, (java.io.OutputStream) p.args[2]);
                                        if (n > 0) {
                                            sRawInjected++;
                                            p.setResult(Boolean.TRUE);   // 短路：微信不再自己编码
                                            log("★ [原图直塞] " + w + "x" + h + " 已写入原始 JPEG "
                                                    + n + " 字节（EXIF 保留）第 " + sRawInjected + " 次"
                                                    + "\n    src=" + src);
                                            return;
                                        }
                                        log("★ [直塞决策] 匹配到 " + shortPath(src) + " 但写入失败，退兜底");
                                    } else if (sDecisionLogged < 6) {
                                        // v8.1：没命中一定要说清为什么，否则下一版只能靠猜。
                                        sDecisionLogged++;
                                        log("★ [直塞决策] 未匹配 目标=" + w + "x" + h
                                                + " " + candidateSummary());
                                    }
                                } else if (!jpeg && sDecisionLogged < 6) {
                                    sDecisionLogged++;
                                    log("★ [直塞决策] 跳过：非 JPEG 编码 format=" + p.args[0]
                                            + " 尺寸=" + w + "x" + h);
                                }

                                // ② 兜底：找不到可用原图时，至少把编码质量拉满（实测微信写死 70）
                                Object q0 = p.args[1];
                                int q = (q0 instanceof Integer) ? (Integer) q0 : 100;
                                if (q < 100) {
                                    p.args[1] = 100;
                                    if (sQualityBoost < 12) {
                                        sQualityBoost++;
                                        log("★ [质量提升] " + w + "x" + h
                                                + " compress quality " + q + " → 100（未匹配到原图，走无损重编码）");
                                    }
                                }
                            } catch (Throwable t) {
                                log("compress 干预异常: " + t);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            if (!cMomentsRaw || !cVerbose || logged >= 8) return;
                            try {
                                android.graphics.Bitmap bmp = (android.graphics.Bitmap) p.thisObject;
                                if (bmp == null || bmp.isRecycled()) return;
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
            log("已挂载 Bitmap.compress 原图直塞 / 质量拉满（v8.0）");
        } catch (Throwable t) {
            log("挂载 Bitmap.compress 干预失败: " + t);
        }
    }

    // ══════════════════ v8.0 原图直塞：候选管理 + JPEG 字节泵 ══════════════════

    /** 判断是否用户相册里的真实照片（排除微信内置 emoji / 模板 png / 缓存） */
    private static boolean isUserPhoto(String path) {
        if (path == null) return false;
        String low = path.toLowerCase(Locale.US);
        if (low.contains("/data/data/") || low.contains("/micromsg/")) return false;
        if (!(low.startsWith("/storage/") || low.startsWith("/sdcard/"))) return false;
        if (low.contains("/cache/") || low.contains("thumb")) return false;
        return low.endsWith(".jpg") || low.endsWith(".jpeg");
    }

    /** v8.1：把候选池里每个文件的尺寸列出来，直塞未命中时一眼看出差在哪 */
    private static String candidateSummary() {
        List<String> snapshot;
        synchronized (sPhotoCandidates) {
            snapshot = new ArrayList<String>(sPhotoCandidates);
        }
        StringBuilder sb = new StringBuilder("候选池=").append(snapshot.size());
        int n = 0;
        for (int i = snapshot.size() - 1; i >= 0 && n < 6; i--, n++) {
            String path = snapshot.get(i);
            int[] wh = boundsOf(path);
            sb.append("\n    ").append(wh == null ? "?x?" : (wh[0] + "x" + wh[1]))
                    .append("  ").append(shortPath(path));
        }
        if (sDirScanned) sb.append("\n    (目录扫描池=").append(sDirPhotos.size()).append(")");
        return sb.toString();
    }

    /** 记录一个候选原图（去重，最多 16 条） */
    private static void addPhotoCandidate(String path) {
        synchronized (sPhotoCandidates) {
            sPhotoCandidates.remove(path);          // 移到队尾（最近使用优先）
            sPhotoCandidates.add(path);
            while (sPhotoCandidates.size() > 16) sPhotoCandidates.remove(0);
        }
    }

    /**
     * 找出「像素尺寸与当前待编码 Bitmap 完全一致」的原图文件。
     * 只接受精确相等：若宽高互换（EXIF 旋转 90°），说明微信已把图转正，
     * 此时直塞原图会导致方向错误，宁可退回质量拉满。
     */
    private static String pickOriginalFor(int w, int h) {
        List<String> snapshot;
        synchronized (sPhotoCandidates) {
            snapshot = new ArrayList<String>(sPhotoCandidates);
        }
        String hit = matchIn(snapshot, w, h);
        if (hit != null) return hit;

        // v8.1 兜底：候选池没命中就直接扫相册目录。
        // 实测探针只抓到 2 张图里的 1 张（另一张走 MediaStore Uri，不经 FileInputStream(File)），
        // 而我们真正需要的只是「尺寸对得上的那个文件」，从磁盘找同样可靠。
        List<String> dir = scanPhotoDirs();
        hit = matchIn(dir, w, h);
        if (hit != null) {
            addPhotoCandidate(hit);     // 命中后纳入候选池，后续同尺寸图秒中
            log("★ [直塞决策] 候选池未命中，目录扫描命中 " + shortPath(hit));
        }
        return hit;
    }

    /**
     * 在给定路径列表里找尺寸匹配的原图（倒序＝最近优先）。
     * 除精确相等外，还接受「宽高互换 + 原图 EXIF 确实标了 90/270 度旋转」的情况：
     * 此时微信是按 EXIF 把图转正了才拿到 bitmap，而我们塞回去的原图仍带同一份 EXIF，
     * 接收端照样会转正，最终显示方向一致——不接受这种匹配会白白漏掉一大半竖拍照片。
     */
    private static String matchIn(List<String> list, int w, int h) {
        String swapped = null;
        String fuzzy = null;
        for (int i = list.size() - 1; i >= 0; i--) {
            String path = list.get(i);
            try {
                File f = new File(path);
                if (!f.isFile()) continue;
                long len = f.length();
                if (len < 300 * 1024L || len > 40 * 1024 * 1024L) continue;  // 太小不是原图，太大不敢塞
                int[] wh = boundsOf(path);
                if (wh == null) continue;
                if (wh[0] == w && wh[1] == h) return path;                    // 精确命中，最优
                if (fuzzy == null && Math.abs(wh[0] - w) <= 4 && Math.abs(wh[1] - h) <= 4) {
                    fuzzy = path;                                             // 容差命中(±4px)，次优
                }
                if (swapped == null && wh[0] == h && wh[1] == w && isRotated90(path)) {
                    swapped = path;                                           // 宽高互换+旋转，最后兜底
                }
            } catch (Throwable ignored) {
            }
        }
        if (swapped != null) {
            log("★ [直塞决策] 按 EXIF 旋转匹配（宽高互换）命中 " + shortPath(swapped));
        } else if (fuzzy != null) {
            log("★ [直塞决策] 容差匹配(±4px)命中 " + shortPath(fuzzy));
        }
        return swapped != null ? swapped : fuzzy;
    }

    /** 同 matchIn，但不打日志——供复制法在关闭临时文件时按尺寸静默找原图 */
    private static String matchInQuiet(List<String> list, int w, int h) {
        String swapped = null;
        String fuzzy = null;
        for (int i = list.size() - 1; i >= 0; i--) {
            String path = list.get(i);
            try {
                File f = new File(path);
                if (!f.isFile()) continue;
                long len = f.length();
                if (len < 300 * 1024L || len > 40 * 1024 * 1024L) continue;
                int[] wh = boundsOf(path);
                if (wh == null) continue;
                if (wh[0] == w && wh[1] == h) return path;
                if (fuzzy == null && Math.abs(wh[0] - w) <= 4 && Math.abs(wh[1] - h) <= 4) fuzzy = path;
                if (swapped == null && wh[0] == h && wh[1] == w && isRotated90(path)) swapped = path;
            } catch (Throwable ignored) {
            }
        }
        return swapped != null ? swapped : fuzzy;
    }

    /** v8.2：按临时文件当前尺寸，在候选池+相册目录里找尺寸一致的原图（复制法用） */
    private static String findOriginalForDims(int w, int h) {
        List<String> cands;
        synchronized (sPhotoCandidates) { cands = new ArrayList<String>(sPhotoCandidates); }
        String hit = matchInQuiet(cands, w, h);
        if (hit != null) return hit;
        return matchInQuiet(scanPhotoDirs(), w, h);
    }

    /** 原图 EXIF 是否标记了 90/270 度旋转（此时解码转正后宽高会互换） */
    private static boolean isRotated90(String path) {
        boolean prev = sInjecting;
        sInjecting = true;
        try {
            int o = new android.media.ExifInterface(path)
                    .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1);
            return o == 5 || o == 6 || o == 7 || o == 8;
        } catch (Throwable t) {
            return false;
        } finally {
            sInjecting = prev;
        }
    }

    /**
     * v8.1 扫描相册目录，取最近修改的 jpg（只扫一次并缓存）。
     * 只看文件名和 mtime，不解码，开销极小；真正的尺寸比对在 matchIn 里按需做。
     */
    private static List<String> scanPhotoDirs() {
        if (sDirScanned) return sDirPhotos;
        sDirScanned = true;
        boolean prev = sInjecting;
        sInjecting = true;              // 屏蔽自身 IO 触发探针
        try {
            String[] dirs = {
                    "/storage/emulated/0/DCIM/Camera",
                    "/storage/emulated/0/DCIM",
                    "/storage/emulated/0/Pictures",
                    "/storage/emulated/0/Pictures/WeiXin",
            };
            List<File> all = new ArrayList<File>();
            for (String d : dirs) {
                File dir = new File(d);
                if (!dir.isDirectory()) continue;
                File[] fs = dir.listFiles();
                if (fs == null) continue;
                for (File f : fs) {
                    if (!f.isFile()) continue;
                    String low = f.getName().toLowerCase(Locale.US);
                    if (!(low.endsWith(".jpg") || low.endsWith(".jpeg"))) continue;
                    if (f.length() < 300 * 1024L) continue;
                    all.add(f);
                }
                if (all.size() > 400) break;
            }
            // 按修改时间降序，只留最近 120 个——刚拍/刚选的图必在其中
            java.util.Collections.sort(all, new java.util.Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    long d = b.lastModified() - a.lastModified();
                    return d > 0 ? 1 : (d < 0 ? -1 : 0);
                }
            });
            for (int i = all.size() - 1; i >= 0 && sDirPhotos.size() < 120; i--) {
                sDirPhotos.add(all.get(i).getAbsolutePath());   // 倒序放入，使最新的在队尾
            }
            log("★ [直塞决策] 相册目录扫描完成，候选 " + sDirPhotos.size() + " 个文件");
        } catch (Throwable t) {
            log("相册目录扫描失败: " + t);
        } finally {
            sInjecting = prev;
        }
        return sDirPhotos;
    }

    /** 解码图片宽高（带缓存，避免重复 IO） */
    private static int[] boundsOf(String path) {
        int[] c = sBoundsCache.get(path);
        if (c != null) return c;
        boolean prev = sInjecting;
        sInjecting = true;                 // 避免自身的解码/读文件再次触发各类探针
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(path, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int[] wh = new int[]{o.outWidth, o.outHeight};
            sBoundsCache.put(path, wh);
            return wh;
        } catch (Throwable t) {
            return null;
        } finally {
            sInjecting = prev;
        }
    }

    /**
     * 把原始 JPEG 字节写进输出流，返回写入字节数（失败返回 0）。
     * 会截断到 JPEG 的 EOI 标记：Motion Photo(MVIMG) 在 JPEG 尾部附了一段 MP4 视频，
     * 直接整份塞进去会让上传体积翻倍且可能被服务端拒绝。
     */
    private static long pumpOriginalInto(String path, java.io.OutputStream os) {
        if (os == null) return 0;
        boolean prev = sInjecting;
        sInjecting = true;
        try {
            File f = new File(path);
            long len = f.length();
            if (len <= 0 || len > 40 * 1024 * 1024L) return 0;
            byte[] data = new byte[(int) len];
            FileInputStream in = new FileInputStream(f);
            try {
                int off = 0, r;
                while (off < data.length && (r = in.read(data, off, data.length - off)) > 0) off += r;
                if (off < data.length) return 0;
            } finally {
                try { in.close(); } catch (Throwable ignored) { }
            }
            if (data.length < 4 || (data[0] & 0xFF) != 0xFF || (data[1] & 0xFF) != 0xD8) return 0; // 不是 JPEG
            int end = jpegEndOffset(data);
            if (end <= 0 || end > data.length) end = data.length;
            os.write(data, 0, end);
            os.flush();
            return end;
        } catch (Throwable t) {
            log("原图直塞失败: " + t);
            return 0;
        } finally {
            sInjecting = prev;
        }
    }

    /** 扫描 JPEG 段结构，返回 EOI(FFD9) 之后的偏移；解析不出来返回全长 */
    private static int jpegEndOffset(byte[] d) {
        try {
            int i = 2;                       // 跳过 SOI(FFD8)
            int n = d.length;
            while (i < n - 1) {
                if ((d[i] & 0xFF) != 0xFF) { i++; continue; }
                int m = d[i + 1] & 0xFF;
                if (m == 0xFF) { i++; continue; }                       // 填充字节
                if (m == 0xD8 || m == 0x01 || (m >= 0xD0 && m <= 0xD7)) { i += 2; continue; }
                if (m == 0xD9) return i + 2;                            // EOI
                if (i + 3 >= n) break;
                int segLen = ((d[i + 2] & 0xFF) << 8) | (d[i + 3] & 0xFF);
                if (segLen < 2) break;
                if (m == 0xDA) {                                        // SOS：后面是熵编码数据
                    int j = i + 2 + segLen;
                    while (j < n - 1) {
                        if ((d[j] & 0xFF) != 0xFF) { j++; continue; }
                        int m2 = d[j + 1] & 0xFF;
                        if (m2 == 0x00 || m2 == 0xFF || (m2 >= 0xD0 && m2 <= 0xD7)) { j += 2; continue; }
                        if (m2 == 0xD9) return j + 2;                   // 找到 EOI
                        break;                                          // 其它 marker（progressive 的下一段）
                    }
                    if (j >= n - 1) break;
                    i = j;
                    continue;
                }
                i += 2 + segLen;
            }
        } catch (Throwable ignored) {
        }
        return d.length;
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
                if (sCopying || sSweeping || !cMomentsRaw || logged >= 60) return;
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
                    if (!cVerbose) return;      // v8.1：写探测纯诊断，默认不打，避免淹没关键日志
                    if (seen.contains(path)) return;
                    seen.add(path);
                    logged++;
                    // v8.1：不再打完整调用栈（一条 30+ 行，1418 行日志里 889 行都是它）
                    // 这里是「流刚打开」的时刻，新建文件必然 0 字节，打 size=0 纯属误导
                    long sz = safeSize(path);
                    log("★ [写] " + shortPath(path) + (sz > 0 ? " 追加于 " + sz + "B" : " (新建)"));
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
                if (sSweeping || !cMomentsRaw || logged >= 20) return;
                try {
                    Object a0 = p.args[0];
                    String path = a0 instanceof File ? ((File) a0).getAbsolutePath()
                            : (a0 instanceof String ? (String) a0 : null);
                    if (path == null || !isMomentsFile(path)) return;
                    if (!cVerbose) return;      // v8.1：RAF 探测纯诊断，默认不打
                    if (seen.contains(path)) return;
                    seen.add(path);
                    logged++;
                    log("★ [RAF] " + shortPath(path));
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
                                if (!cVerbose) return;      // v8.1：读探测纯诊断，默认不打
                                if (seen.contains(path)) return;
                                seen.add(path);
                                logged++;
                                log("★ [读] " + shortPath(path));
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
                if (sCopying || sInjecting || sSweeping || !cMomentsRaw) return;
                try {
                    Object a0 = p.args[0];
                    String path = a0 instanceof File ? ((File) a0).getAbsolutePath()
                            : (a0 instanceof String ? (String) a0 : null);
                    if (path == null) return;
                    // v8.0：只认用户相册里的真实照片（/storage 下的 jpg）。
                    // 旧版本把微信内置 emoji / wxa 模板 png 也当原图记下来，日志噪音极大且会污染匹配。
                    if (!isUserPhoto(path)) return;
                    // v8.1 放宽：只要当前处在朋友圈/相册上下文，读到的相册照片一律纳入候选。
                    // 旧版要求调用栈含特定 sns 类名，结果两张图只抓到一张——另一张走了
                    // 别的读取路径。候选池最终还要按尺寸精确匹配，多收几个没有副作用。
                    if (!nearSns() && !looksLikeGallery(sCurrentActivity)) return;
                    boolean isNew;
                    synchronized (sPhotoCandidates) {
                        isNew = !sPhotoCandidates.contains(path);
                    }
                    sLastOriginalPath = path;
                    addPhotoCandidate(path);            // v8.0：纳入原图直塞候选池
                    if (isNew) log("★ [朋友圈原图读取] path=" + path);
                } catch (Throwable ignored) {
                }
            }
        };
        try { XposedHelpers.findAndHookConstructor(FileInputStream.class, File.class, srcHook); }
        catch (Throwable t) { log("hook FIS(File) 失败: " + t); }
        try { XposedHelpers.findAndHookConstructor(FileInputStream.class, String.class, srcHook); }
        catch (Throwable t) { log("hook FIS(String) 失败: " + t); }

        // ② 覆盖：FileOutputStream 关闭后，按临时文件「当前尺寸」静默匹配原图并覆盖
        //    v8.2：不再用单一 sLastOriginalPath（多图时会被串味），改为读临时文件尺寸，
        //    在候选池+相册目录里找尺寸一致的原图——编码器无关，主图走哪条编码链都能接住。
        try {
            XposedHelpers.findAndHookMethod(FileOutputStream.class, "close", new XC_MethodHook() {
                @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (sCopying || sSweeping || !cMomentsRaw) return;
                try {
                    String temp = sFosTemp.remove(p.thisObject);
                    if (temp == null) return;
                    if (isVideoRemux(temp)) return;   // 实况视频流，绝不覆盖
                    int[] twh = boundsOf(temp);       // 临时文件已被微信写入（重编码后）的尺寸
                    if (twh == null || twh[0] <= 0 || twh[1] <= 0) {
                        if (sCopyLogged < 6) { sCopyLogged++;
                            log("★ [复制法跳过] 临时文件无法读尺寸 temp=" + shortPath(temp));
                        }
                        return;
                    }
                    String orig = findOriginalForDims(twh[0], twh[1]);
                    if (orig != null) {
                        int r = overwriteTempWithOriginal(temp, orig);
                        if (r == COPY_WRITTEN) {
                            sCopyInjected++;
                            log("★ [复制法注入] temp=" + shortPath(temp) + " " + twh[0] + "x" + twh[1]
                                    + " ← " + shortPath(orig) + " 已覆盖 " + safeSize(temp)
                                    + "B 第" + sCopyInjected + "次");
                        } else if (r == COPY_SAME) {
                            sRawSame++;
                            log("★ [原图确认] temp=" + shortPath(temp) + " " + twh[0] + "x" + twh[1]
                                    + " 与原图 " + shortPath(orig) + " 字节完全一致 " + safeSize(temp)
                                    + "B（微信原图模式已生效）第" + sRawSame + "次");
                        }
                        // v8.4：把「此刻已验证正确」的 temp→原图 配对钉死。
                        // 后续核验只认这张表——绝不再按尺寸重查（多图同尺寸会串到同一张，
                        // v8.3 因此把正确的图覆盖成了别人的照片）。
                        if (r == COPY_WRITTEN || r == COPY_SAME) {
                            synchronized (sTempOrig) {
                                if (sTempOrig.size() < 64) sTempOrig.put(temp, orig);
                            }
                        }
                        // 微信有可能在流关闭之后再重编码一次，安排一次延迟核验兜底
                        rememberDraftDir(temp);
                        scheduleSweep();
                    } else if (sCopyLogged < 6) {
                        sCopyLogged++;
                        log("★ [复制法跳过] 无尺寸匹配原图 temp=" + shortPath(temp)
                                + " " + twh[0] + "x" + twh[1] + " " + candidateSummary());
                    }
                } catch (Throwable ignored) {
                }
                }
            });
            log("已挂载 朋友圈复制法（原图覆盖上传临时文件，v8.2 按尺寸匹配）");
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
        // ① Bitmap.createScaledBitmap —— 探测 + 拦截（命中即短路返回原图，等于取消降采样）
        XC_MethodHook scale = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (!cMomentsRaw) return;
                try {
                    android.graphics.Bitmap src = (android.graphics.Bitmap) p.args[0];
                    if (src == null || src.isRecycled()) return;
                    int sw = src.getWidth(), sh = src.getHeight();
                    if (sw * sh < 100000) return;   // 只关心上传级大图降采样
                    int dw = (Integer) p.args[1];
                    int dh = (Integer) p.args[2];
                    boolean shrink = (dw < sw || dh < sh);
                    if (!shrink) return;
                    if (!nearSns()) return;             // 廉价前置：不在朋友圈相关界面直接跳过
                    boolean sns = inSnsStack();
                    if (sScaleLogged < 12) {
                        sScaleLogged++;
                        log("★ [朋友圈缩放探测] 源=" + sw + "x" + sh + " -> 目标=" + dw + "x" + dh
                                + "  sns=" + sns + "  " + p.method.getName()
                                + "\n    栈: " + stackBrief());
                    }
                    // 仅拦截 sns 上下文下的「上传级大图」降采样：源长边 > 2560 说明这是原图在被压。
                    // ⚠ 必须同时要求「目标也是大图」(长边 >= 1000)：
                    //   实测预览阶段会有 4096x3072 -> 267x200 这类缩略图缩放，若也拦截，
                    //   相当于拿 48MB 的全尺寸 Bitmap 去当 267x200 的缩略图用，十几次就 OOM/卡死。
                    if (sns && Math.max(sw, sh) > 2560 && Math.max(dw, dh) >= 1000 && sBlocked < 24) {
                        sBlocked++;
                        p.setResult(src);   // 直接返回原图，取消这次降采样
                        log("★ [朋友圈拦截] 已阻止降采样 " + sw + "x" + sh + " -> " + dw + "x" + dh
                                + "（返回原图，第 " + sBlocked + " 次）");
                    }
                } catch (Throwable ignored) {
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod(android.graphics.Bitmap.class, "createScaledBitmap",
                    android.graphics.Bitmap.class, int.class, int.class, boolean.class, scale);
            log("已挂载 Bitmap.createScaledBitmap 缩放拦截");
        } catch (Throwable t) {
            log("hook createScaledBitmap 失败: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(android.media.ThumbnailUtils.class, "extractThumbnail",
                    android.graphics.Bitmap.class, int.class, int.class, scale);
            XposedHelpers.findAndHookMethod(android.media.ThumbnailUtils.class, "extractThumbnail",
                    android.graphics.Bitmap.class, int.class, int.class, int.class, scale);
            log("已挂载 ThumbnailUtils.extractThumbnail 缩放拦截");
        } catch (Throwable t) {
            log("hook ThumbnailUtils 失败: " + t);
        }

        // ② Bitmap.createBitmap(src, x,y,w,h, Matrix, filter) —— Matrix 缩放路径（createScaledBitmap 的底层，
        //    也是自研压缩代码最常用的入口）。若 Matrix 是「缩小」且源为上传级大图，则去掉变换。
        try {
            XposedHelpers.findAndHookMethod(android.graphics.Bitmap.class, "createBitmap",
                    android.graphics.Bitmap.class, int.class, int.class, int.class, int.class,
                    android.graphics.Matrix.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            if (!cMomentsRaw) return;
                            try {
                                android.graphics.Matrix m = (android.graphics.Matrix) p.args[5];
                                if (m == null) return;
                                android.graphics.Bitmap src = (android.graphics.Bitmap) p.args[0];
                                if (src == null || src.isRecycled()) return;
                                if (Math.max(src.getWidth(), src.getHeight()) <= 2560) return;
                                float[] v = new float[9];
                                m.getValues(v);
                                float sx = Math.abs(v[0]), sy = Math.abs(v[4]);
                                // 只处理纯缩放（无旋转/错切），且确实在缩小
                                if (Math.abs(v[1]) > 0.001f || Math.abs(v[3]) > 0.001f) return;
                                if (sx >= 0.98f && sy >= 0.98f) return;
                                if (sx < 0.05f || sy < 0.05f) return;   // 极小缩略图不动
                                if (!nearSns() || !inSnsStack()) return;
                                if (sMatrixBlocked >= 24) return;
                                sMatrixBlocked++;
                                p.args[5] = null;   // 去掉缩放变换 → 输出原尺寸
                                log("★ [朋友圈拦截] Matrix 缩放已取消 sx=" + sx + " sy=" + sy
                                        + " 源=" + src.getWidth() + "x" + src.getHeight()
                                        + "（第 " + sMatrixBlocked + " 次）"
                                        + (sMatrixBlocked <= 3 ? "\n    栈: " + stackBrief() : ""));
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            log("已挂载 Bitmap.createBitmap(Matrix) 缩放拦截");
        } catch (Throwable t) {
            log("hook createBitmap(Matrix) 失败: " + t);
        }

        // ③ BitmapFactory 解码降采样：inSampleSize>1 / inScaled 会在「解码阶段」就把图缩小。
        //    sns 上下文下强制全尺寸解码。
        XC_MethodHook decOpt = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (!cMomentsRaw) return;
                try {
                    android.graphics.BitmapFactory.Options o = null;
                    for (Object a : p.args) {
                        if (a instanceof android.graphics.BitmapFactory.Options) {
                            o = (android.graphics.BitmapFactory.Options) a;
                            break;
                        }
                    }
                    if (o == null || o.inJustDecodeBounds) return;
                    // 精确判定「真的会缩小」：inSampleSize>1，或 density 缩放确实生效
                    boolean densityScale = o.inScaled && o.inDensity > 0 && o.inTargetDensity > 0
                            && o.inDensity != o.inTargetDensity;
                    if (o.inSampleSize <= 1 && !densityScale) return;
                    // ⚠ inSampleSize 很大（>4）说明微信要的是缩略图/预览图（实测预览阶段用 15，
                    //   即 1/15 尺寸）。强行解成全尺寸会把 48MB 的 Bitmap 当缩略图用，直接 OOM。
                    //   上传主图的降采样倍率很小，卡在 4 以内足够覆盖。
                    if (o.inSampleSize > 4) return;
                    if (!nearSns() || !inSnsStack()) return;
                    if (sDecodeBlocked >= 16) return;
                    sDecodeBlocked++;
                    log("★ [朋友圈拦截] 解码降采样已取消 inSampleSize=" + o.inSampleSize
                            + " inScaled=" + o.inScaled + "  " + p.method.getName()
                            + (sDecodeBlocked <= 3 ? "\n    栈: " + stackBrief() : ""));
                    o.inSampleSize = 1;
                    o.inScaled = false;
                    o.inDensity = 0;
                    o.inTargetDensity = 0;
                } catch (Throwable ignored) {
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod(android.graphics.BitmapFactory.class, "decodeFile",
                    String.class, android.graphics.BitmapFactory.Options.class, decOpt);
            XposedHelpers.findAndHookMethod(android.graphics.BitmapFactory.class, "decodeStream",
                    java.io.InputStream.class, android.graphics.Rect.class,
                    android.graphics.BitmapFactory.Options.class, decOpt);
            XposedHelpers.findAndHookMethod(android.graphics.BitmapFactory.class, "decodeByteArray",
                    byte[].class, int.class, int.class,
                    android.graphics.BitmapFactory.Options.class, decOpt);
            XposedHelpers.findAndHookMethod(android.graphics.BitmapFactory.class, "decodeFileDescriptor",
                    java.io.FileDescriptor.class, android.graphics.Rect.class,
                    android.graphics.BitmapFactory.Options.class, decOpt);
            log("已挂载 BitmapFactory 解码降采样拦截");
        } catch (Throwable t) {
            log("hook BitmapFactory options 失败: " + t);
        }

        // ④ native 编码器嗅探在「进入朋友圈发布界面」时执行（那时图像库才加载完），见 onCreate 钩。
    }

    /**
     * 廉价前置判定：当前前台界面是否与朋友圈相关。
     * 压缩常发生在后台线程（此时用户可能已离开发布界面回到时间线），
     * 所以只要界面名里带 sns/Sns 就放行；非朋友圈场景则完全零开销，
     * 避免每次 Bitmap 解码都去遍历调用栈拖慢微信。
     */
    private static boolean nearSns() {
        String c = sCurrentActivity;
        if (c == null) return false;
        return c.contains("sns") || c.contains("Sns");
    }

    /** 当前调用栈是否来自朋友圈（sns）相关代码 */
    private static boolean inSnsStack() {
        try {
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            for (StackTraceElement e : st) {
                String cn = e.getClassName();
                if (cn.contains("plugin.sns") || cn.contains(".sns.")
                        || cn.contains("SnsUpload") || cn.contains("hf4")
                        || cn.contains("lf4") || cn.contains("gf4")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 精简调用栈（跳过系统帧，取微信侧前 10 帧） */
    private static String stackBrief() {
        StringBuilder sb = new StringBuilder();
        try {
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            int n = 0;
            for (StackTraceElement e : st) {
                String cn = e.getClassName();
                if (cn.startsWith("java.lang.Thread") || cn.startsWith("de.robv.android.xposed")
                        || cn.startsWith("com.abel.wechatlive")) {
                    continue;
                }
                sb.append(cn).append('.').append(e.getMethodName()).append(" ← ");
                if (++n >= 10) break;
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    /** 列出微信进程里加载的图像相关 native 库（只打一次），用于定位 native 编码器 */
    private static void dumpImageNativeLibs() {
        if (sLibsDumped) return;
        sLibsDumped = true;
        EXEC.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    java.io.BufferedReader r = new java.io.BufferedReader(
                            new java.io.FileReader("/proc/self/maps"));
                    java.util.LinkedHashSet<String> hit = new java.util.LinkedHashSet<>();
                    String line;
                    while ((line = r.readLine()) != null) {
                        int i = line.lastIndexOf('/');
                        if (i < 0 || !line.endsWith(".so")) continue;
                        String so = line.substring(i + 1);
                        String low = so.toLowerCase();
                        if (low.contains("image") || low.contains("jpeg") || low.contains("jpg")
                                || low.contains("webp") || low.contains("heif") || low.contains("pic")
                                || low.contains("codec") || low.contains("wechatmm")
                                || low.contains("mmjpeg") || low.contains("skia")) {
                            hit.add(so);
                        }
                    }
                    r.close();
                    log("★ [native 图像库] " + (hit.isEmpty() ? "(无匹配)" : hit.toString()));
                } catch (Throwable t) {
                    log("读取 native 库列表失败: " + t);
                }
            }
        });
    }

    /**
     * 是否「可覆盖的图片类临时文件」。
     * v8.0 收紧：只认 base（pre_temp_sns_live_photo<32hex>）和 _parse_ 解析图。
     * 实测 v7.9 会把 8.5MB 原图写进 _remux_thumb_（缩略图），导致缩略图异常膨胀——必须排除。
     */
    private static boolean isImageTemp(String path) {
        if (path == null) return false;
        String name = new File(path).getName();
        String pre = "pre_temp_sns_live_photo";
        if (!name.startsWith(pre)) return false;
        // 基础上传文件：pre_temp_sns_live_photo<hash>（hash 直接接在后面，无额外下划线段）。
        // _parse / _remux / _thumb 都是派生文件，绝不塞原图。
        return name.indexOf('_', pre.length()) < 0;
    }

    /** 是否纯视频 remux（实况视频流）或缩略图，复制法必须跳过 */
    private static boolean isVideoRemux(String path) {
        if (path == null) return false;
        String name = new File(path).getName();
        return name.contains("_thumb")
                || name.matches("pre_temp_sns_live_photo_remux_[0-9a-fA-F]{32}");
    }

    /** 把原图覆盖进朋友圈上传临时文件（复制法核心动作）。一次成功即停，避免反复写。 */
    /**
     * 用原图字节覆盖上传临时文件。
     * @return COPY_WRITTEN=实际写入 / COPY_SAME=本已是原图字节(无需写) / COPY_FAIL=跳过或失败
     * 注意：本方法只在失败时打日志，成功/幂等的措辞交给调用方——v8.2 两边都打导致
     *      「跳过」和「注入成功」同时出现，日志自相矛盾。
     */
    private static int overwriteTempWithOriginal(String temp, String original) {
        if (sCopying) return COPY_FAIL;   // 防止覆盖动作自身的流递归触发
        sCopying = true;
        try {
            File src = new File(original);
            File dst = new File(temp);
            if (!src.exists() || src.length() < 1024) {
                log("复制法跳过: 原图不存在或过小 original=" + original);
                return COPY_FAIL;
            }
            if (dst.exists() && dst.length() == src.length()) {
                return COPY_SAME;         // 字节数一致 = 微信自己就复制了原图，别白写一遍
            }
            FileInputStream in = new FileInputStream(src);
            java.io.FileOutputStream out = new java.io.FileOutputStream(dst, false);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            out.close();
            return COPY_WRITTEN;
        } catch (Throwable t) {
            log("复制法覆盖失败: temp=" + temp + " err=" + t);
            return COPY_FAIL;
        } finally {
            sCopying = false;
        }
    }

    /** 记住上传临时文件所在的 draft 目录，供发表后延迟核验 */
    private static void rememberDraftDir(String temp) {
        try {
            File p = new File(temp).getParentFile();
            if (p == null) return;
            synchronized (sDraftDirs) {
                if (sDraftDirs.size() < 32) sDraftDirs.add(p.getAbsolutePath());
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 安排一次延迟核验。
     * 关键：流 close 时 temp 可能已是原图，但微信随后还会在上传链里解码→（我们取消缩放）→重编码，
     * 有可能把原图字节又盖回去。等几秒回查，才知道「最终躺在磁盘上的」到底是不是原图。
     */
    private static void scheduleSweep() {
        long now = System.currentTimeMillis();
        if (now - sSweepAt < 4000L) return;      // 4s 内已安排过，多图共用一次
        sSweepAt = now;
        try {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try { Thread.sleep(7000L); } catch (Throwable ignored) { }
                    sweepDraftDirs();
                }
            }, "wl-sweep");
            t.setDaemon(true);
            t.start();
        } catch (Throwable ignored) {
        }
    }

    /** 回查 draft 目录里的上传主文件：与原图字节一致则确认，被重编码则回写原图 */
    private static void sweepDraftDirs() {
        if (sSweeping) return;
        sSweeping = true;
        try {
            List<String> dirs;
            synchronized (sDraftDirs) { dirs = new ArrayList<String>(sDraftDirs); }
            for (String d : dirs) {
                File[] fs = new File(d).listFiles();
                if (fs == null) continue;
                for (File f : fs) {
                    try {
                        String path = f.getAbsolutePath();
                        if (!isImageTemp(path)) continue;    // 只核验基础上传文件
                        long len = f.length();
                        if (len < 1024) continue;
                        int[] wh = boundsOf(path);
                        if (wh == null) continue;
                        // v8.4：只认注入时钉下的配对。按尺寸重查会张冠李戴（见 sTempOrig 注释）。
                        String orig;
                        synchronized (sTempOrig) { orig = sTempOrig.get(path); }
                        if (orig == null) {
                            // 没有配对记录 = 这个文件不是我们经手的，只观测不回写，避免误伤。
                            if (sSweepLogged < 10) {
                                sSweepLogged++;
                                log("★ [产物核验] " + shortPath(path) + " " + wh[0] + "x" + wh[1]
                                        + " " + len + "B 无配对记录，跳过回写（仅观测）");
                            }
                            continue;
                        }
                        long ol = new File(orig).length();
                        if (sSweepLogged >= 10) continue;
                        sSweepLogged++;
                        if (len == ol) {
                            log("★ [产物核验] " + shortPath(path) + " " + wh[0] + "x" + wh[1]
                                    + " = 原图字节 " + len + "B ✓ 原图+EXIF 已保住");
                        } else {
                            int r = overwriteTempWithOriginal(path, orig);
                            log("★ [产物核验] " + shortPath(path) + " " + wh[0] + "x" + wh[1]
                                    + " 被重编码 " + len + "B ≠ 原图 " + shortPath(orig) + " " + ol + "B → "
                                    + (r == COPY_WRITTEN ? "已回写原图 ✓" : "回写失败 ✗"));
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            sSweeping = false;
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
        // v8.4：相册界面(AlbumPreviewUI)聊天与朋友圈共用，类名分不出来。
        //      记住进入相册「之前」停留的界面，用它判断本次选图属于哪条流程。
        if (!gallery) sLastNonGallery = cls;
        // 更极致：非相册界面不心跳；仅首次 onResume 上报一次以证明注入成功
        if (!gallery && sReportedOnce) return;
        // 心跳 + 拉取开关（后台线程，不阻塞微信主线程）
        report(act.getApplicationContext(), cls);
        sReportedOnce = true;

        if (!gallery) return;     // 非相册界面：仅心跳，不做 extras 验证

        // 微信相册选择界面（非 SnsUploadUI 发布界面）。
        // v8.5：朋友圈流程不再尝试挪动「原图」按钮（它与照片/制作视频重叠且挡住点击），
        //       改为直接隐藏；发不发原图完全由模块强制注入 send_raw_img=true 决定。
        //       聊天流程保持微信默认外观（并复位可能残留的隐藏/位移）。
        if (!moments) {
            if (cMomentsRaw && fromMomentsFlow()) {
                hideMomentsRawButton(act);
            } else {
                restoreRawButtonLayout(act);
            }
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
            ps.append("\n  ★ 注入统计：原图直塞=" + sRawInjected
                    + "  质量拉满=" + sQualityBoost + "  复制法覆盖=" + sCopyInjected
                    + "  原图确认=" + sRawSame);
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

    /**
     * v8.5：朋友圈流程 —— 直接隐藏「原图」按钮。
     *
     * 为什么不再"挪开"它（v8.1~v8.4 的思路已废弃）：
     *  - 该按钮是模块强开出来的（key_force_show_raw_image_button），微信自己在朋友圈
     *    相册里本就不显示它，因此它没有属于自己的布局位置，必然与照片网格 /「制作视频」
     *    抢占同一块贴底区域；无论横移还是上抬，都只是换个地方继续挡住点击。
     *  - 用户实测：重叠区域会吃掉触摸事件，导致按钮本身和下面的照片都点不中。
     *
     * 现在：把整个按钮容器 setVisibility(GONE)（GONE 而非 INVISIBLE，才会真正让出
     * 触摸区域与布局空间）。是否发原图不再依赖这个 UI 开关，而是由模块强制注入
     * send_raw_img=true 决定——即"由 APK 里的「朋友圈上传原图」开关统一控制"。
     */
    private static void hideMomentsRawButton(final Activity act) {
        final Handler h = ui();
        if (h == null) return;
        sLastHideSig = Integer.MIN_VALUE;   // 每次进相册重置，保证本次隐藏有一条日志可查
        // 「原图」按钮常在勾选图片之后才出现，且微信可能重建/重显 → 多轮重试（幂等）
        for (int delay : new int[]{300, 900, 1800, 3200, 5000}) {
            scheduleHideRawButton(act, h, delay);
        }
    }

    /** 上次隐藏结果签名，用于多轮重试时去重日志 */
    private static int sLastHideSig = Integer.MIN_VALUE;

    private static void scheduleHideRawButton(final Activity act, Handler h, final int delay) {
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    View root = act.getWindow().getDecorView();
                    TextView yuanTv = findText(root, "原图");
                    if (yuanTv == null) return;                     // 还没出现（或已被隐藏）

                    View yuan = buttonContainerOf(yuanTv);          // 图标+文字的整体容器

                    // 安全阀：容器若混进了「完成/预览/发送/制作视频」等关键按钮，说明识别过宽，
                    // 直接 GONE 会让用户发不出去 → 退化为只隐藏「原图」文字与它同级的圆圈图标。
                    boolean tooWide = containsAnyText(yuan, KEY_BTN_TEXTS);
                    if (tooWide) yuan = yuanTv;

                    // 清掉历史版本可能残留的位移，避免隐藏后再显示时布局是歪的
                    yuanTv.setTranslationX(0); yuanTv.setTranslationY(0);
                    yuan.setTranslationX(0);   yuan.setTranslationY(0);

                    boolean changed = yuan.getVisibility() != View.GONE;
                    yuan.setVisibility(View.GONE);
                    yuanTv.setVisibility(View.GONE);
                    if (tooWide) setSiblingIconsVisibility(yuanTv, View.GONE);  // 圆圈也要藏

                    if (changed && sLastHideSig != 1) {
                        sLastHideSig = 1;
                        log("★ [UI] 朋友圈流程：已隐藏「原图」按钮"
                                + (tooWide ? "（安全模式：仅文字+图标）" : "")
                                + "；是否发原图由模块强制 send_raw_img=true 决定");
                    }
                } catch (Throwable t) {
                    log("UI 隐藏原图按钮失败: " + t);
                }
            }
        }, delay);
    }

    /**
     * 聊天等非朋友圈流程：把可能残留的位移 / 隐藏全部复位，让「原图」按钮回到微信默认外观。
     * 必要性：相册 Activity 与 View 会被复用——旧版残留的 translation 会让文字继续隐身，
     * v8.5 朋友圈流程的 GONE 也可能被带到聊天流程里，必须显式还原成 VISIBLE。
     */
    private static void restoreRawButtonLayout(final Activity act) {
        final Handler h = ui();
        if (h == null) return;
        for (final int delay : new int[]{450, 1500}) {
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        View root = act.getWindow().getDecorView();
                        TextView yuanTv = findText(root, "原图");
                        if (yuanTv == null) return;
                        View yuan = buttonContainerOf(yuanTv);
                        boolean dirty = yuanTv.getTranslationX() != 0 || yuanTv.getTranslationY() != 0
                                || yuan.getTranslationX() != 0 || yuan.getTranslationY() != 0
                                || yuan.getVisibility() != View.VISIBLE
                                || yuanTv.getVisibility() != View.VISIBLE;
                        yuanTv.setTranslationX(0); yuanTv.setTranslationY(0);
                        yuan.setTranslationX(0);   yuan.setTranslationY(0);
                        yuan.setVisibility(View.VISIBLE);
                        yuanTv.setVisibility(View.VISIBLE);
                        setSiblingIconsVisibility(yuanTv, View.VISIBLE);   // 圆圈图标一并还原
                        View makeTv = findTextContains(root, "制作视频");
                        if (makeTv != null) {
                            makeTv.setTranslationX(0); makeTv.setTranslationY(0);
                            View make = buttonContainerOf(makeTv);
                            make.setTranslationX(0);   make.setTranslationY(0);
                        }
                        if (dirty) log("★ [UI] 非朋友圈流程，已复位「原图」按钮为微信默认外观");
                    } catch (Throwable ignored) {
                    }
                }
            }, delay);
        }
    }

    /**
     * 由标签 TextView 上溯到「整个按钮」的容器：取第一个同时含有图片子视图的祖先。
     * 微信的原图按钮是 RelativeLayout[ WeImageView 圆圈, TextView "原图" ]，
     * 只移动 TextView 会让文字脱离图标并被容器裁剪——这正是 v8.1~v8.3 的 bug。
     */
    private static View buttonContainerOf(View label) {
        View cur = label;
        for (int i = 0; i < 3; i++) {
            android.view.ViewParent p = cur.getParent();
            if (!(p instanceof ViewGroup)) break;
            ViewGroup g = (ViewGroup) p;
            if (g.getChildCount() > 4) break;        // 太大了，不像单个按钮，停在上一层
            cur = g;
            if (hasImageChild(g)) return g;          // 含图标 → 这就是按钮整体
        }
        return cur;
    }

    private static boolean hasImageChild(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c instanceof android.widget.ImageView) return true;
            String n = c.getClass().getSimpleName().toLowerCase(Locale.US);
            if (n.contains("image") || n.contains("checkbox")) return true;
        }
        return false;
    }

    // v8.5：relaxClip / rectOf 随「挪开按钮」方案一并移除（改为直接隐藏，无需算重叠矩形）。

    /** 隐藏容器前的安全阀名单：容器里出现这些文字，说明识别过宽，绝不能整块 GONE */
    private static final String[] KEY_BTN_TEXTS = {"完成", "预览", "发送", "制作视频"};

    /** 子树中是否出现了给定文字之一 */
    private static boolean containsAnyText(View v, String[] subs) {
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null) {
                String s = t.toString();
                for (String sub : subs) {
                    if (s.contains(sub)) return true;
                }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (containsAnyText(g.getChildAt(i), subs)) return true;
            }
        }
        return false;
    }

    /** 把标签同级的图标（圆圈/勾选框）一并设为指定可见性——安全模式下的补充处理 */
    private static void setSiblingIconsVisibility(View label, int vis) {
        try {
            android.view.ViewParent p = label.getParent();
            if (!(p instanceof ViewGroup)) return;
            ViewGroup g = (ViewGroup) p;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c == label) continue;
                String n = c.getClass().getSimpleName().toLowerCase(Locale.US);
                if (c instanceof android.widget.ImageView
                        || n.contains("image") || n.contains("checkbox")) {
                    c.setVisibility(vis);
                }
            }
        } catch (Throwable ignored) {
        }
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
        // v8.1：自动落盘。关键标记（★ 且属于发表主链路）立即落盘，其余按 1.2s 节流。
        // 不这样做的话，点「发表」后 SnsUploadUI 直接 finish、不再触发 onResume，
        // 发表阶段的 compress / 原图直塞日志会全部留在内存里，导出时根本看不到。
        boolean critical = msg.startsWith("★ [原图直塞]")
                || msg.startsWith("★ [直塞决策]")
                || msg.startsWith("★ [质量提升]")
                || msg.startsWith("★ [朋友圈拦截]")
                || msg.startsWith("★ [朋友圈压缩探测]");
        autoFlush(critical);
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
