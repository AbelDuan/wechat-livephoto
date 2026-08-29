package com.abel.wechatlive;

/**
 * 模块 App 与 微信内 Hook 之间的公共常量。
 *
 * 【重要】本类以及 LogStore / StatusProvider / MainActivity 都**不得**引用任何
 * de.robv.android.xposed.* 类。原因：模块 App 自身进程里没有 XposedBridge，
 * 一旦 UI 侧的类直接或间接引用到 Hook 类，类校验就会抛 NoClassDefFoundError，
 * 表现为「打开模块就闪退」。v3 正是踩了这个坑。
 */
public final class Const {

    public static final String WECHAT_PKG = "com.tencent.mm";

    /** 模块 App 暴露的状态通道（微信进程通过它回报状态、读取开关） */
    public static final String AUTHORITY = "com.abel.wechatlive.status";
    public static final String URI = "content://" + AUTHORITY;

    public static final String METHOD_REPORT = "report";
    public static final String METHOD_PING = "ping";

    /** report 时携带的日志正文 */
    public static final String KEY_LOG = "log";

    // ── SharedPreferences ──
    public static final String PREFS = "wechatlive";

    /** 用户开关：总启用 */
    public static final String K_ENABLED = "enabled";
    /** 用户开关：自动勾选「实况」 */
    public static final String K_LIVE = "auto_live";
    /** 用户开关：自动勾选「原图」 */
    public static final String K_ORIG = "auto_orig";
    /** 用户开关：详细日志（v8.9 起设置页已移除该开关；保留常量仅为 StatusProvider 兼容历史数据） */
    public static final String K_VERBOSE = "verbose";
    /** 用户开关：日志记录（写入 App 私有文件，排障/导出用，默认关闭省电） */
    public static final String K_LOG = "enable_log";

    /** 状态：最近一次微信侧回报的时间戳 */
    public static final String K_LAST_SEEN = "last_seen";
    /** 状态：最近一次 Activity 类名 */
    public static final String K_LAST_ACT = "last_activity";
    /** 状态：微信版本 */
    public static final String K_WX_VER = "wx_ver";
    /** 状态：回报累计次数 */
    public static final String K_HITS = "hits";

    public static final String LOG_FILE = "hook.log";
    /** 日志文件超过此大小后截断保留后半段 */
    public static final long LOG_MAX = 512 * 1024L;

    private Const() {
    }
}
