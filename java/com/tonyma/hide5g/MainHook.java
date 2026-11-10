package com.tonyma.hide5g;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * ColorOS 17 状态栏定制。
 *
 * 三个功能：
 * 1) 隐藏网络制式文字：OplusNetworkTypeText 那一族单例的 getPrefix/getSuffix 返回空串
 *    （文字是 dex 常量 + Compose 渲染，没有承载它的 TextView，改 alpha / 宽高都没用）。
 * 2) 时钟缩放：给 StatClock 设 setScaleX/Y，pivot 取「左边缘 + 竖直中线」，
 *    缩放前后视觉中心重合，即与原来的位置垂直居中对齐（不改字号，因为 Compose
 *    托管的 TextView 不会重新测量，改字号会被裁切且基线漂移）。
 * 3) 信号图标左右偏移：StatusIconContainer 每次 layout 后给 Wi-Fi / 手机信号两个子 View
 *    设 translationX（translationX 不会被 layout 重置，但会被重新赋值为别的值，所以每次
 *    layout 都刷一遍最稳）。
 *
 * 设置通过 ContentProvider 从 App 读进来，改动通过广播实时下发，不用重启 SystemUI
 * （只有「隐藏制式」这个开关需要重绘，界面上会自动重启一次 SystemUI）。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "hide5G";

    private static final String SYSTEMUI_PKG = "com.android.systemui";

    private static final String NET_TEXT_BASE =
            "com.android.systemui.statusbar.pipeline.mobile.data.OplusNetworkTypeText";

    /** 所有制式对象（APK 被 R8 裁掉了 InnerClasses 属性，这里按名字逐个取）。 */
    private static final String[] NET_TEXT_OBJECTS = new String[]{
            "E", "FiveDotFiveG", "FiveG", "FiveGA", "FiveGBasic", "FiveGE", "FiveGPlus",
            "FiveGPlusPlus", "FiveGSA", "FiveGUC", "FiveGUWB", "FourDotFiveG",
            "FourDotFiveGPlus", "FourG", "FourGPlus", "G", "H", "HPlus", "LTE",
            "LTECA5GE", "LTEPlus", "NR5G", "NR5GPlus", "OneX", "ThreeG", "ThreeGPlus",
            "Tigo5GConnected", "Tigo5GNotRestricted", "TwoG", "Unknown"
    };

    private static final String CLOCK_CLASS = "com.oplus.systemui.statusbar.widget.StatClock";
    private static final String ICON_CONTAINER_CLASS =
            "com.android.systemui.statusbar.phone.StatusIconContainer";
    private static final String NOTIF_CONTAINER_CLASS =
            "com.android.systemui.statusbar.phone.NotificationIconContainer";

    // ---------- 运行期配置 ----------

    private static volatile boolean hideNetType = true;
    private static volatile int clockPercent = Config.CLOCK_DEF;
    private static volatile int wifiOffsetDp = Config.OFFSET_DEF;
    private static volatile int mobileOffsetDp = Config.OFFSET_DEF;
    private static volatile int clockYTenth = Config.CLOCK_Y_DEF;
    private static volatile int notifXDp = Config.NOTIF_X_DEF;
    private static volatile int iconVer = 0;
    /** 图标包替换总开关：包里有同名 XML 就换掉系统图标。 */
    private static volatile boolean packOn = false;

    // ---------- 新增功能 ----------
    private static volatile boolean hideWifiArrow = false;
    private static volatile boolean hideDataArrow = false;
    private static volatile boolean hideWifiStd = false;
    private static volatile int batteryTextPercent = Config.BTEXT_DEF;
    private static volatile int batteryTextYTenth = Config.BTEXT_Y_DEF;
    private static volatile boolean debugOverlay = false;
    private static volatile int statusBarHeightDp = Config.SBH_DEF;
    /** 信号图标画布宽度增减（0.1dp）。 */
    private static volatile int wifiIconWTenth = Config.ICON_W_DEF;
    private static volatile int mobileIconWTenth = Config.ICON_W_DEF;
    /** 画布左右边距增减（0.1dp）：左边距 + 右边距 = 画布宽度的变化量。 */
    private static volatile int wifiIconLTenth = Config.ICON_EDGE_DEF;
    private static volatile int wifiIconRTenth = Config.ICON_EDGE_DEF;
    private static volatile int mobileIconLTenth = Config.ICON_EDGE_DEF;
    private static volatile int mobileIconRTenth = Config.ICON_EDGE_DEF;
    /** 图像在画布内的左右偏移（0.1dp）。 */
    private static volatile int wifiImgXTenth = Config.IMG_X_DEF;
    private static volatile int mobileImgXTenth = Config.IMG_X_DEF;
    /** 通知图标与时钟数字的间距（0.1dp）。 */
    private static volatile int notifGapTenth = Config.NOTIF_GAP_DEF;
    /** Wi-Fi 图标 GONE 之后，移动信号额外往左补的量（0.1dp）。 */
    private static volatile int wifiOffShiftTenth = Config.WIFI_OFF_SHIFT_DEF;
    /** 电池画布左边距（0.1dp）：正 = 电池右移、左侧留白变宽。电池无右边距。 */
    private static volatile int batteryIconLTenth = Config.ICON_EDGE_DEF;
    /** 方案 A：Wi-Fi 图标左右微调（0.1dp，纯平移只动 Wi-Fi）。 */
    private static volatile int wifiNudgeTenth = Config.ICON_EDGE_DEF;
    /** 方案 A：移动信号左右微调（0.1dp，纯平移只动移动信号）。 */
    private static volatile int mobileNudgeTenth = Config.ICON_EDGE_DEF;
    /** 左侧收紧：时钟往右收的量（0.1dp）。 */
    /** 左侧收紧：只作用于状态栏时钟 A + 通知图标（整组往右收），单位 0.1dp。 */
    private static volatile int marginTenth = Config.MARGIN_DEF;
    /** 右侧收紧：只作用于电池 / Wi-Fi / 移动信号等右侧图标（整组往左收），单位 0.1dp。 */
    private static volatile int marginRTenth = Config.MARGIN_DEF;
    /** 只显示上网卡信号。 */
    private static volatile boolean dataSimOnly = false;
    /** 双卡信号样式：-1 不干预，0 独立，1 合并。 */
    private static volatile int simStyle = Config.SIM_STYLE_NONE;

    /**
     * 注意：这里不能直接 new Handler(...) 做静态字段。
     * LSPosed 加载模块类的时机很早，主 Looper 可能还没就绪，静态初始化一抛异常
     * 整个 MainHook 就 "Failed to load class"，模块静默失效。改成懒加载。
     */
    private static Handler sMain;

    private static Handler main() {
        Handler h = sMain;
        if (h == null) {
            synchronized (MainHook.class) {
                h = sMain;
                if (h == null) {
                    h = new Handler(Looper.getMainLooper());
                    sMain = h;
                }
            }
        }
        return h;
    }

    /** 每个时钟控件的基准字号（未缩放前），用于百分比换算。 */
    private static final WeakHashMap<TextView, Float> BASE_SIZE = new WeakHashMap<>();

    private static final List<WeakReference<TextView>> CLOCKS = new ArrayList<>();
    private static final List<WeakReference<ViewGroup>> CONTAINERS = new ArrayList<>();

    private static Context sCtx;
    private static ClassLoader sLoader;

    private static final java.util.concurrent.atomic.AtomicInteger DRAW =
            new java.util.concurrent.atomic.AtomicInteger();

    private static final java.util.concurrent.atomic.AtomicInteger ALL =
            new java.util.concurrent.atomic.AtomicInteger();

    private static final java.util.concurrent.atomic.AtomicInteger DUMPED =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 每个图标容器只全量打印一次结构，方便核对 slot 与可见性。 */
    private static final java.util.Set<View> CONTAINERS_DUMPED =
            java.util.Collections.newSetFromMap(
                    java.util.Collections.synchronizedMap(new java.util.WeakHashMap<View, Boolean>()));

    /** 新功能的调试计数（只打前几次，避免刷日志）。 */
    private static final java.util.concurrent.atomic.AtomicInteger DBG =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 电池着色探针的打印次数上限。 */
    private static final java.util.concurrent.atomic.AtomicInteger BATT_DBG =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 电池画成不透明（默认开，验证后再决定要不要做成设置项）。 */
    /** 2026-11-07 停用：图标包已提供正确的电池颜色/透明度，不再强制实心覆盖。 */
    private static volatile boolean batterySolid = false;

    private static final java.util.concurrent.atomic.AtomicInteger SOLID_DBG =
            new java.util.concurrent.atomic.AtomicInteger();

    private static final java.util.concurrent.atomic.AtomicInteger DRAW_DBG =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 图标包命中的独立计数（顺序无关，免得被别的日志挤掉）。 */
    private static final java.util.concurrent.atomic.AtomicInteger PKDBG =
            new java.util.concurrent.atomic.AtomicInteger();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || !SYSTEMUI_PKG.equals(lpparam.packageName)) {
            return;
        }
        try {
            XposedBridge.hookAllMethods(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Context ctx = (Context) param.thisObject;
                    if (ctx == null) {
                        return;
                    }
                    install(ctx.getClassLoader(), ctx);
                }
            });
        } catch (Throwable t) {
            log("arm failed: " + t);
        }
    }

    /**
     * Wi-Fi / 移动信号 View 的兜底接管。
     *
     * ColorOS 17 上这两个图标的 View 不在 StatusIconContainer 的子树里（Compose 在
     * 另一个容器里摆的），容器遍历根本扫不到，于是「画布左右边距」只对信号生不生效、
     * Wi-Fi 纹丝不动。这里直接盯 View 的 attach / layout：类名带 WifiView / MobileView
     * 的登记进来，之后每次 layout 都按当前配置刷一遍（写值幂等，不会抖）。
     */
    private static void hookIconViewWatcher() {
        try {
            XposedBridge.hookAllMethods(android.view.View.class, "onAttachedToWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                Object o = p.thisObject;
                                if (!(o instanceof View)) {
                                    return;
                                }
                                View v = (View) o;
                                String cn = v.getClass().getName();
                                if (cn.contains("WifiView") || cn.contains("MobileView")) {
                                    ICON_VIEWS.put(v, Boolean.TRUE);
                                    applySiblingOffset(v);
                                    applyIconWidth(v);
                                }
                            } catch (Throwable ignored) {
                                // ignore
                            }
                        }
                    });
            XposedBridge.hookAllMethods(android.view.View.class, "onLayout",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                Object o = p.thisObject;
                                if (!(o instanceof View)) {
                                    return;
                                }
                                View v = (View) o;
                                if (!ICON_VIEWS.containsKey(v)) {
                                    return;
                                }
                                applySiblingOffset(v);
                                applyIconWidth(v);
                            } catch (Throwable ignored) {
                                // ignore
                            }
                        }
                    });
        } catch (Throwable t) {
            log("hookIconViewWatcher failed: " + t);
        }
    }

    private static void install(ClassLoader cl, Context ctx) {
        sCtx = ctx;
        sLoader = cl;
        int net = hookNetworkTypeText(cl);
        int clock = hookClock(cl);
        int icons = hookStatusIcons(cl);
        int notif = hookNotifArea(cl);
        int custom = hookCustomIcons();
        int batteryText = hookBatteryText(cl);
        int layout = hookLayoutTweaks(cl);
        int battPaint = hookBatteryPaint(cl);
        hookIconViewWatcher();
        registerReceiver(ctx);
        log("installed: netType=" + net + " clock=" + clock + " icons=" + icons
                + " notif=" + notif + " custom=" + custom
                + " btext=" + batteryText + " layout=" + layout
                + " battPaint=" + battPaint);
        loadConfigAsync();
    }

    // ---------- 电池着色探针 ----------

    /**
     * ColorOS 的电池是自研 Drawable（HorizontalBatteryContentDrawable）自己画三层：
     * 外框 / 电量层 / frame 描边。颜色不走 View 的 tint，全在 Drawable 里。
     * 这里盯住它的 updateDrawableTint，把三层最终拿到的色值打出来。
     */
    private static int hookBatteryPaint(ClassLoader cl) {
        Class<?> base = findClass(
                "com.oplus.systemui.statusbar.pipeline.battery.ui.drawable.BatteryBarDrawableBase", cl);
        if (base == null) {
            log("battPaint: base class not found");
            return 0;
        }
        try {
            Method m = base.getDeclaredMethod("updateDrawableTint");
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object o = param.thisObject;
                        solidBattery(o);
                        if (BATT_DBG.getAndIncrement() > 12) {
                            return;
                        }
                        log("BATT-PAINT " + o.getClass().getSimpleName()
                                + " mode=" + battInt(o, "frameColorMode")
                                + " outline=" + hexColor(battInt(o, "outlineColor"))
                                + " bg=" + hexColor(battInt(o, "backgroundColor"))
                                + " prog=" + hexColor(battInt(o, "progressColor"))
                                + " | outside=" + battDrawable(o, "outsideDrawable")
                                + " inside=" + battDrawable(o, "insideDrawable")
                                + " frame=" + battDrawable(o, "frameDrawable")
                                + " | subBg=" + battDrawable(o, "bgDrawable")
                                + " subProg=" + battDrawable(o, "progressDrawable")
                                + " charging=" + battDrawable(o, "chargingDrawable"));
                    } catch (Throwable ignored) {
                        // ignore
                    }
                }
            });
            hookBatteryDraw(base);
            return 1;
        } catch (Throwable t) {
            log("battPaint hook failed: " + t);
            return 0;
        }
    }

    /** 绘制期对照：draw 时用的 outside 实例是不是我们改过的那个。 */
    private static void hookBatteryDraw(Class<?> base) {
        try {
            Method m = base.getDeclaredMethod("draw", android.graphics.Canvas.class);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (DRAW_DBG.getAndIncrement() > 3) {
                            return;
                        }
                        Object o = p.thisObject;
                        Drawable out = battDrawableObj(o, "outsideDrawable");
                        Drawable fr = battDrawableObj(o, "frameDrawable");
                        log("BATT-DRAW this=" + System.identityHashCode(o)
                                + " out=" + (out == null ? "null"
                                : out.getClass().getSimpleName() + "#" + System.identityHashCode(out)
                                + "/tint=" + hexColor(out instanceof VecDrawable
                                ? ((VecDrawable) out).currentTint() : 0)
                                + "/a=" + out.getAlpha())
                                + " frame=" + (fr == null ? "null"
                                : fr.getClass().getSimpleName() + "#" + System.identityHashCode(fr))
                                + " | bounds out=" + boundsOf(out) + " frame=" + boundsOf(fr)
                                + " inside=" + boundsOf(battDrawableObj(o, "insideDrawable"))
                                + " bg=" + boundsOf(battDrawableObj(o, "bgDrawable"))
                                + " prog=" + boundsOf(battDrawableObj(o, "progressDrawable"))
                                + " this=" + boundsOfSelf(o));
                        probeBatteryLayers(o);
                    } catch (Throwable ignored) {
                        // ignore
                    }
                }
            });
        } catch (Throwable t) {
            log("battDraw hook failed: " + t);
        }
    }

    /**
     * 逐层离屏渲染，量每层"真正画到像素"的范围。
     * bounds 一样不代表墨迹一样 —— 这里绕开缩放参数，直接看墨迹。
     */
    private static void probeBatteryLayers(Object o) {
        String[] keys = {"outsideDrawable", "insideDrawable", "frameDrawable"};
        for (String k : keys) {
            probeOne(k, battDrawableObj(o, k));
        }
    }

    private static void probeOne(String tag, Drawable d) {
        if (d == null) {
            return;
        }
        android.graphics.Rect b = d.copyBounds();
        int w = b.width();
        int h = b.height();
        if (w <= 0 || h <= 0 || w > 256 || h > 256) {
            return;
        }
        int oldAlpha = d.getAlpha();
        int oldLevel = d.getLevel();
        android.graphics.ColorFilter oldCf = d.getColorFilter();
        try {
            Drawable c = VecDrawable.dup(d);
            c.setBounds(0, 0, w, h);
            c.setAlpha(255);
            c.setLevel(10000);
            if (c instanceof VecDrawable) {
                c.setTint(0xFF000000);
            }
            android.graphics.Bitmap bmp = android.graphics.Bitmap
                    .createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(bmp);
            c.draw(cv);
            int top = -1;
            int bot = -1;
            int left = -1;
            int right = -1;
            for (int y = 0; y < h; y++) {
                int rowL = -1;
                int rowR = -1;
                for (int x = 0; x < w; x++) {
                    if (android.graphics.Color.alpha(bmp.getPixel(x, y)) > 16) {
                        if (rowL < 0) {
                            rowL = x;
                        }
                        rowR = x;
                    }
                }
                if (rowL < 0) {
                    continue;
                }
                if (top < 0) {
                    top = y;
                }
                bot = y;
                if (left < 0 || rowL < left) {
                    left = rowL;
                }
                if (rowR > right) {
                    right = rowR;
                }
            }
            StringBuilder prof = new StringBuilder();
            int lim = Math.min(4, h);
            for (int y = 0; y < lim; y++) {
                prof.append(y).append(':').append(android.graphics.Color
                        .alpha(bmp.getPixel(w / 2, y))).append(' ');
            }
            prof.append("| ");
            for (int y = Math.max(0, h - lim); y < h; y++) {
                prof.append(y).append(':').append(android.graphics.Color
                        .alpha(bmp.getPixel(w / 2, y))).append(' ');
            }
            bmp.recycle();
            log("BATT-PROBE " + tag + " layer=" + w + "x" + h
                    + " ink=" + (right - left + 1) + "x" + (bot - top + 1)
                    + "@" + left + "," + top
                    + " cls=" + d.getClass().getSimpleName()
                    + " name=" + (d instanceof VecDrawable ? ((VecDrawable) d).name : "-")
                    + " lvl=" + oldLevel + " edge[" + prof + "]");
        } catch (Throwable t) {
            log("BATT-PROBE " + tag + " failed: " + t);
        } finally {
            d.setBounds(b);
            d.setAlpha(oldAlpha);
            d.setLevel(oldLevel);
            d.setColorFilter(oldCf);
        }
    }

    private static int battInt(Object o, String name) {
        try {
            java.lang.reflect.Field f = o.getClass().getField(name);
            f.setAccessible(true);
            return f.getInt(o);
        } catch (Throwable t) {
            return Integer.MIN_VALUE;
        }
    }

    /**
     * 把电池的三层从"系统给的半透色"拉成不透明。
     *
     * 系统值（实测 HorizontalBatteryContentDrawable）：
     *   backgroundColor = #4dffffff（30% 白）→ 外框整块半透
     *   outlineColor    = #ccffffff（80% 白）+ frameColorMode=0 → 再乘一次 0x4d
     *   progressColor   = #f0ffffff / 充电时才是绿
     * isShowPercentIn（数字在电池里）时子类 updateDrawableTint 直接走父类并 return，
     * 子类的 bgDrawable / progressDrawable 压根没人上色 —— 电量层保持图标包的 #000000。
     * 这里补上：颜色沿用系统的，只把 alpha 提到 FF。
     */
    private static void solidBattery(Object o) {
        if (!batterySolid) {
            return;
        }
        try {
            int bg = battInt(o, "backgroundColor");
            int outline = battInt(o, "outlineColor");
            int prog = battInt(o, "progressColor");
            Drawable outside = battDrawableObj(o, "outsideDrawable");
            Drawable frame = battDrawableObj(o, "frameDrawable");
            Drawable subBg = battDrawableObj(o, "bgDrawable");
            Drawable subProgress = battDrawableObj(o, "progressDrawable");
            // 实心色：用系统给图标的前景色（outlineColor）烘掉透明度，
            // 亮度就和 WiFi / 信号一致，不会比邻居亮一档。
            int solid = bake(outline);
            int solidProg = bake(prog);
            if (outside != null) {
                outside.setAlpha(255);
                outside.setColorFilter(null);
                outside.setTint(solid);
            }
            if (subBg != null) {
                subBg.setAlpha(255);
                subBg.setTint(solid);
            }
            if (frame != null) {
                frame.setAlpha(255);
                frame.setColorFilter(null);
                frame.setTint(solid);
            }
            if (subProgress != null) {
                subProgress.setAlpha(255);
                subProgress.setTint(solidProg);
            }
            // 注意：不要改 backgroundColor / outlineColor 字段本身 ——
            // 充电、低电量时系统要拿它们算颜色，改脏了充电态就失去绿色。
            if (SOLID_DBG.getAndIncrement() < 4) {
                log("BATT-SOLID bg=" + hexColor(bg) + " outline=" + hexColor(outline)
                        + "->" + hexColor(solid)
                        + " this=" + System.identityHashCode(o)
                        + " outside=" + (outside == null ? "null"
                        : outside.getClass().getSimpleName() + "#" + System.identityHashCode(outside)
                        + "/" + hexColor(
                        outside instanceof VecDrawable ? ((VecDrawable) outside).currentTint() : 0)));
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /**
     * 把"半透的亮度"烘进 RGB，再置为不透明。
     *
     * 状态栏图标用的是 #e6ffffff 这类带 alpha 的白，视觉亮度 = 白 × 90%。
     * 直接把 alpha 拉满会变纯白、比邻居亮一档；按 alpha 预乘成 #ffe6e6e6
     * 才是"和别的图标一样亮，但实心不透背景"。
     */
    private static int bake(int c) {
        if (c == Integer.MIN_VALUE) {
            return c;
        }
        int a = c >>> 24;
        if (a == 0) {
            return c;
        }
        if (a == 0xFF) {
            return c;
        }
        int r = ((c >> 16) & 0xFF) * a / 255;
        int g = ((c >> 8) & 0xFF) * a / 255;
        int b = (c & 0xFF) * a / 255;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** 只把 alpha 拉满，色相保持系统给的值（充电绿/低电红都跟着走）。 */
    private static int opaque(int c) {
        if (c == Integer.MIN_VALUE || (c >>> 24) == 0) {
            return c;
        }
        return c | 0xFF000000;
    }

    private static void battSetInt(Object o, String name, int v) {
        if (v == Integer.MIN_VALUE) {
            return;
        }
        try {
            java.lang.reflect.Field f = o.getClass().getField(name);
            f.setAccessible(true);
            f.setInt(o, v);
        } catch (Throwable ignored) {
            // ignore
        }
    }



    private static String battDrawable(Object o, String name) {
        Drawable dr = battDrawableObj(o, name);
        if (dr == null) {
            return "null";
        }
        return dr.getClass().getSimpleName() + "(a=" + dr.getAlpha()
                + ",lvl=" + dr.getLevel() + ")";
    }

    private static Drawable battDrawableObj(Object o, String name) {
        try {
            Class<?> c = o.getClass();
            java.lang.reflect.Field f = null;
            while (c != null && f == null) {
                try {
                    f = c.getDeclaredField(name);
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
            if (f == null) {
                return null;
            }
            f.setAccessible(true);
            Object d = f.get(o);
            return d instanceof Drawable ? (Drawable) d : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String boundsOf(Drawable d) {
        if (d == null) {
            return "null";
        }
        android.graphics.Rect r = d.copyBounds();
        return r.width() + "x" + r.height() + "@" + r.left + "," + r.top
                + " i=" + d.getIntrinsicWidth() + "x" + d.getIntrinsicHeight()
                + " lvl=" + d.getLevel();
    }

    private static String boundsOfSelf(Object o) {
        try {
            android.graphics.Rect r = (android.graphics.Rect) o.getClass()
                    .getMethod("copyBounds").invoke(o);
            return r == null ? "?" : r.width() + "x" + r.height() + "@" + r.left + "," + r.top;
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String hexColor(int v) {
        if (v == Integer.MIN_VALUE) {
            return "?";
        }
        String s = Integer.toHexString(v);
        while (s.length() < 8) {
            s = "0" + s;
        }
        return "#" + s;
    }

    // ---------- 1) 网络制式文字 ----------

    private static int hookNetworkTypeText(ClassLoader cl) {
        int hooked = 0;
        for (String name : NET_TEXT_OBJECTS) {
            Class<?> clazz = findClass(NET_TEXT_BASE + "$" + name, cl);
            if (clazz == null) {
                continue;
            }
            for (String m : new String[]{"getPrefix", "getSuffix"}) {
                try {
                    Method method = clazz.getDeclaredMethod(m);
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (hideNetType) {
                                param.setResult("");
                            }
                        }
                    });
                } catch (Throwable t) {
                    log("hook " + name + "#" + m + " failed: " + t);
                }
            }
            hooked++;
        }
        return hooked;
    }

    // ---------- 2) 时钟缩放 ----------

    private static int hookClock(ClassLoader cl) {
        XC_MethodHook sizeHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!(param.thisObject instanceof TextView)) {
                    return;
                }
                TextView tv = (TextView) param.thisObject;
                if (!isClock(tv)) {
                    return;
                }
                rememberClock(tv);
                // 框架刚设进去的字号就是基准值，先记下来（预留：某些版本仍需改字号）
                BASE_SIZE.put(tv, tv.getTextSize());
                applyClock(tv);
            }
        };
        int n = 0;
        try {
            XposedBridge.hookAllMethods(TextView.class, "setTextSize", sizeHook);
            n++;
        } catch (Throwable t) {
            log("hook setTextSize failed: " + t);
        }
        Class<?> clazz = findClass(CLOCK_CLASS, cl);
        if (clazz != null) {
            XC_MethodHook baseHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    TextView tv = (TextView) param.thisObject;
                    if (!BASE_SIZE.containsKey(tv)) {
                        BASE_SIZE.put(tv, tv.getTextSize());
                        log("clock base=" + tv.getTextSize() + " @" + param.method.getName());
                    }
                    rememberClock(tv);
                    applyClock(tv);
                }
            };
            for (String m : new String[]{"onAttachedToWindow", "reloadStatClockDimens"}) {
                try {
                    XposedBridge.hookAllMethods(clazz, m, baseHook);
                    n++;
                } catch (Throwable t) {
                    log("hook " + m + " failed: " + t);
                }
            }
            try {
                XposedBridge.hookAllMethods(clazz, "setText", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        TextView tv = (TextView) param.thisObject;
                        rememberClock(tv);
                        applyClock(tv);
                    }
                });
                n++;
            } catch (Throwable t) {
                log("hook setText failed: " + t);
            }
        }
        // 通知面板展开进度：时钟 A→B 交接渐退的驱动信号。
        // onPanelExpansionChanged(expansion, expanded, tracking, origin, panelType) 每帧回调；
        // 只认 NOTIFICATION 面板 —— 控制中心走 QS 面板，不该动时钟。
        try {
            Class<?> sesm = findClass("com.android.systemui.shade.ShadeExpansionStateManager", cl);
            if (sesm != null) {
                XposedBridge.hookAllMethods(sesm, "onPanelExpansionChanged", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (param.args == null || param.args.length < 5
                                    || !(param.args[0] instanceof Float)) {
                                return;
                            }
                            Object type = param.args[4];
                            String origin = String.valueOf(param.args[3]);
                            // 诊断打点：只在一次下拉的「起手」帧打，区分左边=通知中心、
                            // 右边=控制中心（QS_PANEL / SeparateQS）。逐帧打会很快耗尽计数且刷屏。
                            if ((origin.contains("startOpening")
                                    || origin.contains("TrackingStarted")
                                    || origin.contains("SeparateQS"))
                                    && DBG_PANEL.getAndIncrement() < 200) {
                                log("PANEL p=" + param.args[0]
                                        + " exp=" + param.args[1]
                                        + " trk=" + param.args[2]
                                        + " origin=" + origin
                                        + " type=" + type);
                            }
                            float p = (Float) param.args[0];
                            boolean tracking = param.args.length > 2
                                    && Boolean.TRUE.equals(param.args[2]);
                            String tn = type == null ? "" : String.valueOf(type);
                            if (tn.contains("QS")) {
                                // 右侧下滑 = 控制中心。时钟 A 的渐退不归它管，但通知图标
                                // 一样会被面板盖住：之前只认 NOTIFICATION，控制中心下拉时
                                // 那排应用图标就一直挂着不动。
                                float oldQ = sQsExpand;
                                boolean closingQ = p < oldQ - 0.0005f;
                                sQsTracking = tracking;
                                sQsLastClosing = closingQ;
                                if (p < 0.05f || (closingQ && !tracking && p < 0.25f)) {
                                    p = 0f;
                                }
                                sQsExpand = p;
                                sQsExpandAt = System.currentTimeMillis();
                                if (Math.abs(p - oldQ) > 0.0005f) {
                                    applyNotifAlphaAll();
                                    scheduleClockSettle();
                                }
                                return;
                            }
                            if (!tn.contains("NOTIFICATION")) {
                                return;
                            }
                            float old = sNotifExpand;
                            // 只看「下降方向」：各版本那个 expanded 布尔语义不一致，
                            // 用它判定会把展开 snap 过程误判成收起、把动画掐断。
                            boolean closing = p < old - 0.0005f;
                            sNotifTracking = tracking;
                            // 记下最近一次的「方向」，settle 兜底只会把真正在收起的方向
                            // 强制归零；展开中段（p<0.5）停顿时不该误判成已收起，否则
                            // 通知图标会在继续下滑时又冒出来。
                            sNotifLastClosing = closing;
                            // 收起到底、或收起方向且已接近底部 → 明确判定收起完成，
                            // 不让尾段残值把展开分数永久卡在 0.1x。
                            if (p < 0.05f || (closing && !tracking && p < 0.25f)) {
                                p = 0f;
                            }
                            sNotifExpand = p;
                            sNotifExpandAt = System.currentTimeMillis();
                            // 进度在动（或刚判定收起）→ 立刻重推，不等 settle。
                            // A 的缩放只由 taper 单值决定，不再看系统是否在 morph。
                            if (Math.abs(p - old) > 0.0005f) {
                                for (TextView tv : aliveClocks()) {
                                    applyClock(tv);
                                }
                                // 通知图标逐帧淡出（收起判定 p=0 时立刻恢复不透明）
                                applyNotifAlphaAll();
                                // 图标只在收起到底后同步：下拉过程中状态栏被面板盖住、
                                // 时钟正被系统 morph 挪来挪去，firstClock 会抓到大时钟 B
                                // 当参照物，图标直接被搬到错误位置（表现就是"图标乱飞"）。
                                if (p <= 0f) {
                                    for (View nv : aliveNotifs()) {
                                        applyNotifX(nv);
                                    }
                                }
                                scheduleClockSettle();
                            }
                        } catch (Throwable ignored) {
                            // ignore
                        }
                    }
                });
                n++;
            } else {
                log("ShadeExpansionStateManager not found (时钟渐退不可用)");
            }
        } catch (Throwable t) {
            log("hook expansion failed: " + t);
        }
        return n;
    }

    private static boolean isClock(TextView tv) {
        String cn = tv.getClass().getName();
        return cn.contains("Clock") || cn.contains("clock");
    }

    /**
     * 通知面板 / QS 头部那只「大时钟」是系统下拉 morph 用的假时钟，
     * 和状态栏 A 是两个不同的 View。它必须完全由系统驱动 —— 之前用全局
     * clockSysAt 让位，结果假时钟一动、状态栏 A 也跟着不敢写缩放，尺寸就飘了。
     * 这里按父链把它挑出来直接放过。
     */
    private static boolean isShadeClock(View v) {
        // StatClock 是状态栏时钟专用类，无条件认定为 A —— 宁可漏放一只假时钟，
        // 也不能因为父链判断偏差把状态栏 A 整个跳过（那样模块就等于没生效）。
        if (CLOCK_CLASS.equals(v.getClass().getName())) {
            return false;
        }
        boolean inStatusBar = false;
        boolean inShade = false;
        View cur = v;
        for (int i = 0; i < 20 && cur != null; i++) {
            String cn = cur.getClass().getName();
            if (cn.contains("PhoneStatusBarView")
                    || cn.contains("StartSideExceptHeadsUpLayout")) {
                inStatusBar = true;
                break;
            }
            if (cn.contains("QuickStatusBarHeader") || cn.contains("ShadeHeader")
                    || cn.contains("NotificationShadeWindowView")
                    || cn.contains("QSContainer") || cn.contains("Keyguard")) {
                inShade = true;
            }
            if (!(cur.getParent() instanceof View)) {
                break;
            }
            cur = (View) cur.getParent();
        }
        return inShade && !inStatusBar;
    }

    /**
     * 锁屏那一套时钟（大号时间 + 日期）不归我们管。
     *
     * 锁屏的大时间也是 TextView 子类（ClockTimeView），类名同样带 Clock，会被
     * isClock 认成状态栏时钟 —— 于是一调「字号」，锁屏时间被放大到占半屏，日期
     * 跟着挤压重叠。这里按父链认出锁屏组件，直接放过。
     */
    private static boolean isLockScreenClock(View v) {
        View cur = v;
        int guard = 0;
        while (cur != null && guard++ < 16) {
            String cn = cur.getClass().getName();
            if (cn.contains("Keyguard") || cn.contains("ClockTimeView")
                    || cn.contains("ClockViewRoot") || cn.contains("DateMessage")
                    || cn.contains("MyCustomized")) {
                return true;
            }
            android.view.ViewParent p;
            try {
                p = cur.getParent();
            } catch (Throwable t) {
                return false;
            }
            cur = p instanceof View ? (View) p : null;
        }
        return false;
    }

    /**
     * 时钟缩放。
     *
     * 不用 setTextSize：StatClock 由 Compose 托管，字号变了它不会重新测量/重新定位，
     * 结果就是文字被裁一半、基线也跑偏（放大往下坠、缩小往上飘）。
     *
     * 改用渲染变换 setScaleX/Y，pivot 取「左边缘 × 竖直中线 (0, height/2)」：
     *  - pivotX = 0，文字左边缘不动，放大只往右长；
     *  - pivotY = height/2，缩放前后视觉中心完全重合，A 在状态栏里始终垂直居中；
     *  - 交接帧 taper=0 时 scale=1，锚点在哪都不影响终点，所以为了居中选中线
     *    不会破坏 A→B 交接（终点仍是模块偏移归零的原厂态）。
     */
    private static void applyClock(TextView tv) {
        if (tv == null) {
            return;
        }
        try {
            if (isLockScreenClock(tv)) {
                // 锁屏时钟：还回原样，别参与缩放，也别再当"时钟"收拢
                CLOCK_VIEWS.remove(tv);
                if (Math.abs(tv.getScaleX() - 1f) > 0.001f
                        || Math.abs(tv.getScaleY() - 1f) > 0.001f) {
                    tv.setScaleX(1f);
                    tv.setScaleY(1f);
                }
                return;
            }
            if (isShadeClock(tv)) {
                // 面板假时钟：一个像素都别碰，系统的 morph 放大曲线要原样跑完
                CLOCK_VIEWS.remove(tv);
                return;
            }
            int h = tv.getHeight();
            if (h <= 0) {
                // 还没 layout，等 OnLayoutChange 回调再处理
                return;
            }
            // 登记成时钟：探针靠这个集合认出它，用 +margin（往右）而不是图标的 -margin。
            CLOCK_VIEWS.put(tv, Boolean.TRUE);

            // 位移按 View 单独让位：系统真在动「这个」View 的 translationX 时，
            // 交给探针每帧写「系统值 + 常量」，不在这里抢写，避免动画卡帧。
            Long drv = CLOCK_SYS_AT.get(tv);
            boolean systemDriving = drv != null
                    && System.currentTimeMillis() - drv < 300L;

            // 状态栏 A 的缩放由模块独占：系统从不改它（系统改的是上面的假时钟）。
            // 这里无条件按 taper 写入，不看任何「系统正在动画」标志 —— 一旦让位，
            // A 就会停在某个残值上，表现就是一会儿 120% 一会儿 100%。
            float taper = clockTaper();
            float s = 1f + (clockPercent / 100f - 1f) * taper;
            // 竖直中线：放大后文字上下等量外扩，视觉中心不动 = 在状态栏行里垂直居中。
            // taper=0 时 scale=1，pivot 与交接终点无关。
            float pivotX = 0f;
            float pivotY = h / 2f;
            if (Math.abs(tv.getScaleX() - s) >= 0.001f
                    || Math.abs(tv.getScaleY() - s) >= 0.001f
                    || Math.abs(tv.getPivotY() - pivotY) >= 0.5f
                    || Math.abs(tv.getPivotX() - pivotX) >= 0.5f) {
                if (DBG_CLOCK.getAndIncrement() < 400) {
                    log("APPLY " + tv.getClass().getSimpleName() + " s=" + s
                            + " (was " + tv.getScaleX() + ") pct=" + clockPercent
                            + " taper=" + Math.round(taper * 100) + "%");
                }
                tv.setPivotX(pivotX);
                tv.setPivotY(pivotY);
                tv.setScaleX(s);
                tv.setScaleY(s);
            }

            // 系统刚（300ms 内）驱动过这个时钟 → 它正在跑下拉/收起的位移动画。
            // 这时候任何自己算目标值的写入都会把动画顶掉（表现就是动画卡一下再跳），
            // 所以整段交给探针：探针只做「系统值 + 常量」，动画曲线原样保留。
            // （systemDriving 已在缩放段前声明）

            // 上下微调：translationY 不参与布局，只挪绘制位置（和缩放互不影响）
            float density = tv.getResources().getDisplayMetrics().density;
            float ty = clockYTenth / 10f * density;
            if (Math.abs(ty) >= 0.2f && !systemDriving
                    && Math.abs(tv.getTranslationY() - ty) > 0.2f) {
                tv.setTranslationY(ty);
            }

            // 两侧收拢：时钟整体右移，下拉时同样随进度收回（A 缓慢向左滑回原厂位）。
            // 系统从没驱动过它时（首次进桌面），探针不会触发，这里补一次基线；
            // 之后一律交给探针续写。
            if (!systemDriving) {
                float margin = marginTenth / 10f * density * taper;
                Float sysRec = ICON_SYS_TX.get(tv);
                float want = (sysRec != null ? sysRec : 0f) + margin;
                if (Math.abs(tv.getTranslationX() - want) > 0.2f) {
                    // 必须包 IN_TX：不然探针会把自己这一次写当成「系统写入」，
                    // 刷新 clockSysAt 并把 sys+margin 记成新基线，于是下一帧又叠一次 margin。
                    IN_TX.set(Boolean.TRUE);
                    try {
                        tv.setTranslationX(want);
                    } finally {
                        IN_TX.set(Boolean.FALSE);
                    }
                }
            }
        } catch (Throwable t) {
            log("applyClock failed: " + t);
        }
    }

    // ---------- 3) 信号图标偏移 ----------

    private static int hookStatusIcons(ClassLoader cl) {
        Class<?> clazz = findClass(ICON_CONTAINER_CLASS, cl);
        if (clazz == null) {
            return 0;
        }
        hookTranslationProbe();
        hookClipSource();
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.thisObject instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) param.thisObject;
                    rememberContainer(g);
                    // 图标往左溢出显示时不能被任何一层裁掉（见 relaxIconClipping）
                    relaxIconClipping(g);
                    // onViewAdded 也要立刻补偏移：展开通知面板时 Compose 会新建一批
                    // 图标 View，而这时不会再走 StatusIconContainer.onLayout。
                    applyIcons(g, true);
                }
            }
        };
        int n = 0;
        try {
            XposedBridge.hookAllMethods(clazz, "onLayout", hook);
            n++;
        } catch (Throwable t) {
            log("hook onLayout failed: " + t);
        }
        try {
            XposedBridge.hookAllMethods(clazz, "onViewAdded", hook);
            n++;
        } catch (Throwable t) {
            log("hook onViewAdded failed: " + t);
        }

        // ---------- 解除「右侧图标最多显示 4 个」 ----------
        // SystemUI 的 StatusIconContainer.onMeasure 在「所有可见图标总宽 > 容器宽」
        // （本机实测容器 382px，一个图标 46~77px）时把 mNeedsUnderflow 置 true；
        // updateStates() 随即把放不下的图标切成 dot / hidden，表现就是 WiFi、移动信号、
        // 网速、NFC 一多起来，最左边那几个直接不显示。
        // 图标是 left=0 + translationX 排的，继续往左排不会挤走电池，所以只要：
        //   1) 把限制开关清掉；2) 关掉自己与所有祖先的裁剪，让负 translationX 画得出来。
        try {
            XposedBridge.hookAllMethods(clazz, "setShouldRestrictIcons", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length > 0) {
                        param.args[0] = Boolean.FALSE;
                    }
                }
            });
            n++;
        } catch (Throwable t) {
            log("hook setShouldRestrictIcons failed: " + t);
        }
        try {
            XposedBridge.hookAllMethods(clazz, "updateStates", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    clearIconLimit(param.thisObject);
                    if (param.thisObject instanceof View) {
                        relaxIconClipping((View) param.thisObject);
                    }
                }
            });
            n++;
        } catch (Throwable t) {
            log("hook updateStates failed: " + t);
        }
        return n;
    }

    /** 反射缓存：StatusIconContainer 里两个「限制/溢出」boolean 字段。 */
    private static final java.util.Map<Class<?>, java.lang.reflect.Field[]> ICON_LIMIT_FIELDS =
            java.util.Collections.synchronizedMap(
                    new java.util.HashMap<Class<?>, java.lang.reflect.Field[]>());

    /** 把 mShouldRestrictIcons / mNeedsUnderflow 清成 false，超宽的图标不再被隐藏。 */
    private static void clearIconLimit(Object o) {
        if (o == null) {
            return;
        }
        try {
            Class<?> c = o.getClass();
            java.lang.reflect.Field[] fs = ICON_LIMIT_FIELDS.get(c);
            if (fs == null) {
                java.util.ArrayList<java.lang.reflect.Field> found =
                        new java.util.ArrayList<>();
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (f.getType() != boolean.class) {
                        continue;
                    }
                    String name = f.getName().toLowerCase(java.util.Locale.US);
                    // 字段名按关键字匹配：ROM 升级后即便混淆/改名也能命中
                    if (name.contains("restricticon") || name.contains("underflow")) {
                        f.setAccessible(true);
                        found.add(f);
                    }
                }
                fs = found.toArray(new java.lang.reflect.Field[0]);
                ICON_LIMIT_FIELDS.put(c, fs);
                log("iconLimit fields=" + fs.length + " on " + c.getSimpleName());
            }
            for (java.lang.reflect.Field f : fs) {
                try {
                    f.setBoolean(o, false);
                } catch (Throwable ignored) {
                    // 单个字段写不进去不影响其它字段
                }
            }
        } catch (Throwable t) {
            log("clearIconLimit failed: " + t);
        }
    }

    /** 图标靠负 translationX 往左溢出：容器自己 + 整条父链都不能裁剪子 View。 */
    private static void relaxIconClipping(View v) {
        try {
            View cur = v;
            for (int i = 0; i < 16 && cur != null; i++) {
                if (cur instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) cur;
                    if (g.getClipChildren()) {
                        g.setClipChildren(false);
                    }
                    if (g.getClipToPadding()) {
                        g.setClipToPadding(false);
                    }
                }
                if (cur.getClipBounds() != null) {
                    cur.setClipBounds(null);
                }
                android.view.ViewParent p = cur.getParent();
                cur = (p instanceof View) ? (View) p : null;
            }
        } catch (Throwable ignored) {
            // 父链还没 attach 时跳过，下一次 onLayout 会再试
        }
    }

    /**
     * 偏移以 0.1dp 为单位存储，并夹到 ±4dp。
     * 图标是 Compose 摆的，改不动 layout，只能 translationX 视觉平移；平移不占位，
     * 所以幅度一大就会盖住邻居（Wi-Fi 压到移动信号、通知整排压过来）。夹死后就只是微调。
     */
    private static float offsetPx(int tenthDp, float density) {
        float dp = tenthDp * Config.OFFSET_UNIT;
        if (dp > Config.OFFSET_LIMIT_DP) {
            dp = Config.OFFSET_LIMIT_DP;
        } else if (dp < -Config.OFFSET_LIMIT_DP) {
            dp = -Config.OFFSET_LIMIT_DP;
        }
        return dp * density;
    }

    /**
     * StatusIconContainer 的子 View 全部 lay 在 left=0，靠 translationX 排开（ColorOS 自定义布局），
     * 所以偏移只能叠加在系统算好的 translationX 上，不能直接覆盖 ——
     * 覆盖就等于把所有图标都搬回原点，一堆图标叠在一起。
     * 而且只在 onLayout 之后写：那是系统刚重算完基线的时刻，反复调用不会累积漂移。
     */
    /** 图标原始画布宽度（只记一次，宽度滑块在此基础上加减）。 */
    private static final java.util.Map<View, Integer> ICON_BASE_W =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<View, Integer>());

    /** 图标原来的左右 padding（我们改过之后要能收回去）。 */
    private static final java.util.Map<View, int[]> ICON_BASE_PAD =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<View, int[]>());

    /** 已经被我们改过尺寸的图标，边距归零时要还原一次。 */
    private static final java.util.Map<View, Boolean> ICON_RESIZED =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<View, Boolean>());

    /**
     * 系统给这个图标排出来的原始 translationX（基线），以及我们上次写下去的值。
     * 下拉通知栏 / 展开状态栏都会再走一遍 onLayout，但系统多数时候不会重算
     * translationX —— 读到的还是我们自己写进去的那个值。直接 "当前值 + 偏移"
     * 就会一次比一次跑得远，所以要认得出"这是我自己写的"，回到基线再加。
     */
    private static final java.util.Map<View, Float> ICON_BASE_TX =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<View, Float>());
    private static final java.util.Map<View, Float> ICON_LAST_TX =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<View, Float>());

    /**
     * 系统自己写下来的 translationX —— 只由 setTranslationX 探针记录，别的入口一概不碰。
     *
     * 这是唯一可信的"系统真值"：探针拿到的参数一定是系统（布局/Compose/动画）写的，
     * 我们自己的写入被 IN_TX 挡住不会进到这里。
     *
     * 之前用 ICON_BASE_TX 在 applyIcons 里「值变了就当系统重排、重记基线」，下拉动画期间
     * 系统每帧写一个新值，每帧都被当成重排，于是基线一步步吃掉我们的偏移，偏移量翻倍，
     * 视觉上就是图标突然瞬移一下。
     */
    private static final java.util.Map<View, Float> ICON_SYS_TX =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<View, Float>());

    /**
     * 每个图标最后一次被「系统」写 translationX 的时刻（只由探针记，我们的写入被
     * IN_TX 挡住不会进这里）。图标路径据此让位：300ms 内系统还在逐帧驱动它，说明
     * 下拉/收起动画正跑着，layout 回调这时再写一次「基线+偏移」就会和探针抢写、
     * 差一帧的移动量来回顶 = 左右抖。时钟路径早有同款闸门（CLOCK_SYS_AT），这里补上。
     */
    private static final java.util.Map<View, Long> ICON_SYS_AT =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<View, Long>());

    /**
     * 时钟 View 集合（applyClock 认领时登记）。探针靠它认出时钟 ——
     * 时钟的收拢方向和图标相反（往右），必须单独算。
     */
    private static final java.util.Map<View, Boolean> CLOCK_VIEWS =
            java.util.Collections.synchronizedMap(
                    new java.util.WeakHashMap<View, Boolean>());

    /** 系统最后一次驱动任一时钟 translationX 的时间（粗判：面板是否正在动画）。 */
    private static volatile long clockSysAt = 0L;
    /** 每个时钟 View 各自被系统驱动的时间。假时钟的 morph 不该连累状态栏 A。 */
    private static final java.util.Map<View, Long> CLOCK_SYS_AT =
            java.util.Collections.synchronizedMap(
                    new java.util.WeakHashMap<View, Long>());

    // ---------- 时钟 A→B 交接渐退（taper） ----------
    //
    // 系统从不移动状态栏时钟 A：通知面板里另有一只「假时钟」初始与 A 重合，
    // 下拉时一边从 A 的位置滑到 B 的位置，一边把字号从 1 缩放到 base；
    // A 只是被面板盖住。所以交接点不是 B 的坐标，而是「原厂 A」——
    // 只要 A 在系统接手之前把模块加的自定义量（右移、放大）全部撤成 0，
    // 剩下的路系统自己按原厂曲线走到 B，全程无缝，也不用知道 B 在哪。
    //
    // 因此 taper 是唯一状态：1 = 完整自定义态；0 = 完全原厂。它只由展开分数 p
    // 决定，不看时间、不看系统是否在 morph —— 同一个 p 永远得到同一个尺寸，
    // 不会再出现「一会儿 120% 一会儿 100%」。

    /** 通知面板展开分数 0..1；-1 = 还没收到过回调（按 0 处理 = 完整保留）。 */
    private static volatile float sNotifExpand = -1f;
    /** 最后一次展开回调的时刻，只用于「回调尾段丢失」的停摆兜底。 */
    private static volatile long sNotifExpandAt = 0L;
    /** 最后一次回调时用户是否还在跟手拖动：跟手期间不做停摆兜底。 */
    private static volatile boolean sNotifTracking = false;
    /** 跟手结束后多久没回调就判定收起完成（ColorOS 收起尾段常丢回调）。 */
    private static final long EXPAND_SETTLE_MS = 350L;
    /** 最近一次展开回调的方向：true = 正在收起。settle 兜底只认收起方向，避免
     *  展开中段停顿被误判成收起、把通知图标又放出来。 */
    private static volatile boolean sNotifLastClosing = false;
    /** 控制中心（右侧下滑）展开分数：只驱动通知图标淡出，不参与时钟渐退。 */
    private static volatile float sQsExpand = -1f;
    private static volatile long sQsExpandAt = 0L;
    private static volatile boolean sQsTracking = false;
    private static volatile boolean sQsLastClosing = false;
    /** 渐退在这个展开度收干净（要赶在系统 morph 起跳之前）。 */
    private static final float TAPER_END = 0.38f;

    /** 渐退系数：1 = 完整保留模块偏移/放大；0 = 完全回到原厂。smoothstep 缓动。 */
    private static float clockTaper() {
        float p = sNotifExpand;
        if (p <= 0f) {
            return 1f;
        }
        if (p >= TAPER_END) {
            return 0f;
        }
        float q = p / TAPER_END;
        return 1f - q * q * (3f - 2f * q);
    }

    /**
     * 展开停稳后的兜底重推：补写最终状态，并处理回调尾段丢失。
     * 懒初始化：模块类在 zygote 极早期加载，那时主线程 Looper 还没 prepare，
     * 静态 new Handler() 会 NPE 导致整个模块类加载失败。
     */
    private static android.os.Handler sClockSettleHandler;
    private static boolean sClockSettleQueued = false;

    private static void scheduleClockSettle() {
        if (sClockSettleQueued) {
            return;
        }
        android.os.Looper looper = android.os.Looper.getMainLooper();
        if (looper == null) {
            return;
        }
        android.os.Handler h = sClockSettleHandler;
        if (h == null) {
            h = sClockSettleHandler = new android.os.Handler(looper);
        }
        sClockSettleQueued = true;
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                sClockSettleQueued = false;
                // ColorOS 收起动画尾段常直接停发回调，分数卡在 0.1x 永不归零，
                // 表现就是 A 一直被压在 1.0。跟手已结束、分数停在前半段且回调停摆
                // → 明确判定「已收起」，归零后立刻把 A 写回设定尺寸。
                // 完全展开（p >= 0.5）时不碰，面板开着就保持原厂态。
                // 只在「确实在收起方向」且尾段丢回调时才强制归零——展开中段停靠
                // （p<0.5）不碰，否则继续下滑时通知图标会又被放出来。
                if (!sNotifTracking && sNotifLastClosing && sNotifExpand > 0f
                        && sNotifExpand < 0.5f
                        && System.currentTimeMillis() - sNotifExpandAt > EXPAND_SETTLE_MS) {
                    sNotifExpand = 0f;
                    log("expand settle: force collapsed");
                }
                // 控制中心同理：收起尾段丢回调会把分数卡在 0.1x，图标就一直不回来
                if (!sQsTracking && sQsLastClosing && sQsExpand > 0f
                        && sQsExpand < 0.5f
                        && System.currentTimeMillis() - sQsExpandAt > EXPAND_SETTLE_MS) {
                    sQsExpand = 0f;
                }
                for (TextView tv : aliveClocks()) {
                    applyClock(tv);
                }
                // 只在收起状态下补推图标（下拉中 firstClock 会抓错参照物，见 expansion 回调）
                if (iconExpand() <= 0f) {
                    for (View nv : aliveNotifs()) {
                        applyNotifX(nv);
                    }
                }
                // 尾段回调丢失被强制判成收起时，也要把 alpha 写回 1
                applyNotifAlphaAll();
            }
        }, 250L);
    }

    /**
     * Wi-Fi 消失/回来那一趟：系统动画期间每帧都在重写移动图标的 translationX
     * （探针会在每帧系统值上叠加常量偏移，动画形状不变）；动画一停系统就不再写，
     * 于是「最后一帧系统值 + 常量偏移」就固定下来。问题在于动画尾段系统可能
     * 直接把 translationX 设成终值而不走 setter 分支被探针漏掉，或者最后一帧的
     * 系统值不是期望终点。等动画彻底停稳后（250ms 之后）用 ICON_BASE_TX 里
     * 记录的最新系统真值重推一次「真值 + 常量偏移」即可平滑归位，且只有这一次，
     * 不再在动画期间逐帧抢写（那正是「停下又瞬移」的根因）。
     */
    private static android.os.Handler sIconSettleHandler;
    private static boolean sIconSettleQueued = false;

    private static void scheduleIconSettle() {
        // 关 WiFi 时「动画结束后又跳一下」疑似由这里的延迟强制重推造成：动画收尾时
        // reapplyOffsets 用一帧前的旧基线写值 → 在动画末尾又多跳一下。暂时停用，让
        // 系统动画自己收尾、不做任何后续强写。
        if (true) {
            return;
        }
        if (sIconSettleQueued) {
            return;
        }
        android.os.Looper looper = android.os.Looper.getMainLooper();
        if (looper == null) {
            return;
        }
        android.os.Handler h = sIconSettleHandler;
        if (h == null) {
            h = sIconSettleHandler = new android.os.Handler(looper);
        }
        sIconSettleQueued = true;
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                sIconSettleQueued = false;
                try {
                    reapplyOffsets();
                } catch (Throwable t) {
                    log("icon settle failed: " + t);
                }
            }
        }, 350L);
    }

    private static final java.util.concurrent.atomic.AtomicInteger DEBUG_LAYOUT =
            new java.util.concurrent.atomic.AtomicInteger();
    /** 抓「动画结束后又自己跳一下」这个 bug 用：三个计数器的统一预算。 */
    private static final int DBG_ON = 400;
    private static final java.util.concurrent.atomic.AtomicInteger DBG_IG =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger DBG_STX =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger DBG_CLOCK =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger DBG_PANEL =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger DBG_CLIP =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger DBG_MG =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger lpadDiag =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 已经认领过的图标 View（applyIcons 时登记）。 */
    private static final java.util.Map<View, Boolean> MANAGED =
            java.util.Collections.synchronizedMap(
                    new java.util.WeakHashMap<View, Boolean>());
    /** 防重入：我们自己的 setTranslationX 会再次进 hook，必须跳过。 */
    private static final ThreadLocal<Boolean> IN_TX = new ThreadLocal<Boolean>();

    /**
     * 补位量（像素）：因为藏掉非上网卡信号图标，这个 View 需要往左多挪多少。
     * 由 applyIcons 每次布局重算，offsetForView 统一读，保证 onLayout 与
     * setTranslationX 探针两个入口口径一致。
     */
    private static final java.util.Map<View, Float> ICON_COLLAPSE =
            java.util.Collections.synchronizedMap(
                    new java.util.WeakHashMap<View, Float>());

    private static float collapseOf(View v) {
        Float f = ICON_COLLAPSE.get(v);
        return f == null ? 0f : f;
    }

    private static boolean isIconView(View v) {
        String cn = v.getClass().getName();
        return cn.contains("ModernStatusBarWifiView")
                || cn.contains("MobileView")
                || cn.contains("SingleBindableStatusBarComposeIconView")
                || isBatteryView(v);
    }

    /** 电池图标：ColorOS 用 StatBatteryMeterView（id=battery），它是图标容器的兄弟节点。 */
    private static boolean isBatteryView(View v) {
        if (v == null) {
            return false;
        }
        String cn = v.getClass().getName();
        if (cn.contains("BatteryMeterView") || cn.endsWith("StatBatteryMeterView")) {
            return true;
        }
        int id = v.getId();
        if (id != android.view.View.NO_ID) {
            try {
                String n = v.getResources().getResourceEntryName(id);
                return "battery".equals(n);
            } catch (Throwable ignored) {
                // ignore
            }
        }
        return false;
    }

    /** Wi-Fi 图标判定（类名或 slot 名，两条路都认）。 */
    private static boolean isWifiView(String cn, String slot) {
        return cn.contains("Wifi") || "wifi".equals(slot);
    }

    /** 移动信号图标判定。 */
    private static boolean isMobileView(String cn, String slot) {
        return isMobileSlot(slot) || "stacked_mobile".equals(slot)
                || cn.contains("SingleBindableStatusBarComposeIconView");
    }

    /**
     * 单个图标要叠的偏移量（像素）。
     *
     * 右侧图标（电池 / Wi-Fi / 移动信号…）要叠的偏移量（像素）。默认偏移
     * 只来自「右侧收紧」；左侧收紧不碰这一侧。Wi-Fi / 移动信号再各自叠自己的微调。
     * 这里同时是 onLayout 和 setTranslationX 探针的唯一入口，两边算出来的口径
     * 必须一致，否则系统一重写就会跳回原位。
     */
    private static float offsetForView(View v) {
        float d = v.getResources().getDisplayMetrics().density;
        float off = -(marginRTenth / 10f * d);
        // 补位：「只显示上网卡」藏掉的那块宽度，由它左边的图标往右挪来吃掉，
        // 否则被藏的那张卡会留一个空洞（图标是靠 translationX 摆的，GONE 不让位）。
        off += collapseOf(v);
        String cn = v.getClass().getName();
        String slot = slotOf(v);
        boolean isWifi = isWifiView(cn, slot);
        boolean isMobile = isMobileView(cn, slot);
        boolean isBattery = isBatteryView(v);
        if (isWifi) {
            // 方案 A：Wi-Fi 左右微调，纯 translationX 平移，只动 Wi-Fi 自身。
            off += Math.round(wifiNudgeTenth * Config.OFFSET_UNIT * d);
        } else if (isMobile) {
            // 方案 A：移动信号左右微调，纯 translationX 平移，只动移动信号自身。
            off += Math.round(mobileNudgeTenth * Config.OFFSET_UNIT * d);
            // 左边距：用户明确要求固定为 0（不做任何左侧补偿/平移）。
            // 因此这里不再叠加箭头槽补偿 / WiFi 开额外补偿；右边距扩展仍走 padding。
            applyMobileLPad(v);
        } else if (isBattery) {
            // 电池不再叠加任何间距滑块（位置完全交给系统）。
        }
        return off;
    }

    /** 移动信号"左边距扩展"量（0.1dp，正 = 画布往左扩）。 */
    private static volatile int mobileLPadTenth = Config.ICON_EDGE_DEF;

    /**
     * 移动信号"右边距扩展"：改 right padding（WRAP_CONTENT 会把 padding 计入测量宽度，
     * 于是画布自然往右扩，不硬改 lp.width，不触发之前的逐帧跳）。
     * 仅正数有效（往右扩）；负值按 0 处理。
     *
     * 注意：箭头槽补偿不在这里做（改用 translationX，见 offsetForView），
     * 以免在布局过程中反复 setPadding 触发 requestLayout，开关 WiFi 时造成抽动。
     */
    private static void applyMobileLPad(View v) {
        float d = v.getResources().getDisplayMetrics().density;
        int tenth = mobileLPadTenth;
        int pr = tenth > 0 ? Math.round(tenth * Config.OFFSET_UNIT * d) : 0;
        if (v.getPaddingRight() != pr) {
            v.setPadding(0, v.getPaddingTop(), pr, v.getPaddingBottom());
        }
    }

    /** 数据箭头槽补偿量（dp）：把关 WiFi 时被箭头槽挤到左边的信号格推回右侧。 */
    private static final float MOBILE_ARROW_SLOT_COMP_DP = 4.9f;
    /** Wi-Fi 显示时额外右移量（dp）：弥补 Wi-Fi 开关导致的图标宽度差 ~11px（3.14dp）。 */
    private static final float MOBILE_WIFI_ON_EXTRA_DP = 3.1f;
    /**
     * 移动信号图标的固定测量宽度（px）。关 WiFi 时 Compose 数据箭头槽会让宽度 77→88，导致
     * 左邻 NFC/蓝牙被挤左 11px（抽动）。钉到较宽的 88px，两种状态一致即不再抽动。
     * 0 = 不固定（保留系统自然宽度）。若机型/图标不同需调整，取实测较宽值。
     */
    private static volatile int MOBILE_ICON_FIXED_W_PX = 88;

    /** 上一次布局里 Wi-Fi 图标是否已被藏掉；翻转那一次要越过动画闸门。 */
    private static volatile boolean lastWifiGone = false;
    private static volatile long lastWifiFlipAt = 0L;
    /**
     * Wi-Fi 图标 GONE ↔ VISIBLE 一翻转，容器宽度立刻变 78 像素，但系统往往
     * 已经在动画尾巴上、之后不再重写移动图标的 translationX —— 于是补位量
     * 停在旧值上（表现：关掉 Wi-Fi 后移动图标还是贴着电池）。
     * 翻转后的一小段窗口里允许强写一次：基线用的是探针记下的系统真值，
     * 不会翻倍，而且几个容器（锁屏 / 下拉 / 主状态栏）都能各落一次位。
     */
    private static boolean wifiGoneFlip(ViewGroup container) {
        Boolean gone = null;
        try {
            int n = container.getChildCount();
            for (int i = 0; i < n; i++) {
                View c = container.getChildAt(i);
                if (c != null && c.getClass().getName().contains("WifiView")) {
                    gone = Boolean.valueOf(c.getVisibility() == View.GONE);
                    break;
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        // 容器里根本没有 Wi-Fi 图标（下拉面板、锁屏那几个）：不参与状态判定
        if (gone == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (gone.booleanValue() != lastWifiGone) {
            lastWifiGone = gone.booleanValue();
            lastWifiFlipAt = now;
        }
        return now - lastWifiFlipAt < 400L;
    }

    /** applySiblingOffset 走的是图标自己的 onLayout，容器得从父节点取。 */
    private static boolean wifiGoneFlipOf(View v) {
        android.view.ViewParent p = v.getParent();
        return p instanceof ViewGroup && wifiGoneFlip((ViewGroup) p);
    }

    /**
     * 当前容器里 Wi-Fi 图标是否处于「已显示」状态（vis != GONE）。
     * Wi-Fi 开/关时移动信号图标的内部宽度会差 ~11px，导致蓝牙↔信号墨迹间隙不一致；
     * 用它做条件补偿：Wi-Fi 开时额外右移一点，把两种状态下的间隙拉平。
     * 容器里没有 Wi-Fi 图标（下拉/锁屏）时返回 null，表示不参与。
     */
    private static Boolean wifiShownOf(View v) {
        android.view.ViewParent p = v.getParent();
        if (!(p instanceof ViewGroup)) {
            return null;
        }
        try {
            ViewGroup container = (ViewGroup) p;
            int n = container.getChildCount();
            for (int i = 0; i < n; i++) {
                View c = container.getChildAt(i);
                if (c != null && c.getClass().getName().contains("WifiView")) {
                    return Boolean.valueOf(c.getVisibility() != View.GONE);
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return null;    }

    /**
     * 配置变化后主动把所有已认领图标重推一遍。
     * 系统不一定会再调一次 setTranslationX，光靠 requestLayout 有可能等到下次
     * 下拉才生效，滑滑块就会"没反应"。
     */
    private static void reapplyOffsets() {
        // 用 ICON_BASE_TX 而不是 MANAGED：顶部状态栏那排图标不是每个都经过
        // StatusIconContainer.onLayout，只有 setTranslationX 探针见过它们，
        // 只按 MANAGED 推会漏掉一半（表现就是滑边距"没反应"）。
        java.util.List<View> views;
        synchronized (ICON_BASE_TX) {
            views = new ArrayList<>(ICON_BASE_TX.keySet());
        }
        for (View v : views) {
            if (v == null) {
                continue;
            }
            Float base = ICON_BASE_TX.get(v);
            if (base == null) {
                continue;
            }
            // 系统 300ms 内还在逐帧驱动这个图标（动画正跑）→ 别抢写，否则会拿一帧前的
            // 旧基线把系统的平滑动画打断成「瞬移」。等动画停稳后那次无动画 layout 再归位。
            Long drvAt = ICON_SYS_AT.get(v);
            if (drvAt != null && System.currentTimeMillis() - drvAt < 300L) {
                continue;
            }
            float want = base + offsetForView(v);
            if (Math.abs(v.getTranslationX() - want) < 0.5f) {
                continue;
            }
            IN_TX.set(Boolean.TRUE);
            try {
                v.setTranslationX(want);
            } catch (Throwable ignored) {
                // ignore
            } finally {
                IN_TX.set(Boolean.FALSE);
            }
            ICON_LAST_TX.put(v, want);
        }
        // Wi-Fi / 信号单独接管的这批（不在容器里，ICON_BASE_TX 也没它们的记录）
        java.util.List<View> extra;
        synchronized (ICON_VIEWS) {
            extra = new ArrayList<>(ICON_VIEWS.keySet());
        }
        for (View v : extra) {
            if (v == null) {
                continue;
            }
            applySiblingOffset(v);
            applyIconWidth(v);
        }
    }

    /**
     * 真正的根因就在这里：
     *
     * ColorOS 的状态栏图标是 Compose 托管的，下拉 / 收起时它会**直接**给图标
     * View 写 translationX（走 View.setTranslationX，不经过 StatusIconContainer
     * 的 onLayout）。我们只在 onLayout 里补偏移，于是系统一写，我们的偏移就被
     * 覆盖掉，而且再也不会有 onLayout 事件来修复 —— 表现就是"下拉第一次，位置
     * 就永久变了"。
     *
     * 所以这里直接盯住 setTranslationX：系统写多少，我们立刻改写成
     * "系统值 + 我们的偏移"，并把该值记成新基线。下拉 / 收起 / 换壁纸 / 切主题
     * 全部自动跟上。
     */
    private static void hookTranslationProbe() {
        try {
            XposedBridge.hookAllMethods(android.view.View.class, "setTranslationX",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                if (Boolean.TRUE.equals(IN_TX.get())) {
                                    return;
                                }
                                if (!(p.thisObject instanceof android.view.View)) {
                                    return;
                                }
                                android.view.View v = (android.view.View) p.thisObject;
                                boolean isClock = CLOCK_VIEWS.containsKey(v);
                                if (!isClock && !MANAGED.containsKey(v) && !isIconView(v)) {
                                    return;
                                }
                                if (!(p.args[0] instanceof Float)) {
                                    return;
                                }
                                float sys = (Float) p.args[0];
                                // 系统真值，只在这里记 —— applyIcons 的基线一律读它。
                                ICON_SYS_TX.put(v, sys);
                                // 顺手记「系统刚动过这个 View」的时刻：layout 路径据此让位，
                                // 动画期间不抢写（见 ICON_SYS_AT）。
                                ICON_SYS_AT.put(v, System.currentTimeMillis());

                                // 时钟：下拉 / 收起时 ColorOS 会自己给它做位移动画，
                                // 我们只要在每帧的系统值上加同一个常量，动画形状就完全不变；
                                // 反过来说，如果在 applyClock 里自己算目标值去覆盖，动画会卡帧。
                                if (isClock) {
                                    boolean wasDriving =
                                            System.currentTimeMillis() - clockSysAt < 300L;
                                    long now = System.currentTimeMillis();
                                    clockSysAt = now;
                                    // 只标记「这个」View。系统 morph 驱动的是面板假时钟，
                                    // 用全局时间戳会让状态栏 A 陪着一起让位、不写缩放。
                                    CLOCK_SYS_AT.put(v, now);
                                    float t = clockTaper();
                                    float mg = marginTenth / 10f
                                            * v.getResources().getDisplayMetrics().density * t;
                                    // morph/手势起跳打点：看系统开始动时钟时的展开进度，校准 TAPER_END
                                    if (!wasDriving && DBG_CLOCK.getAndIncrement() < 20) {
                                        log("clock-drive p=" + sNotifExpand
                                                + " taper=" + Math.round(t * 100) + "%"
                                                + " sys=" + Math.round(sys));
                                    }
                                    if (Math.abs(mg) < 0.5f) {
                                        ICON_LAST_TX.put(v, sys);
                                        return;
                                    }
                                    IN_TX.set(Boolean.TRUE);
                                    try {
                                        v.setTranslationX(sys + mg);
                                    } finally {
                                        IN_TX.set(Boolean.FALSE);
                                    }
                                    ICON_LAST_TX.put(v, sys + mg);
                                    return;
                                }

                                float off = offsetForView(v);
                                ICON_BASE_TX.put(v, sys);
                                String pcn = v.getClass().getName();
                                String pslot = slotOf(v);
                                if ((isWifiView(pcn, pslot) || isMobileView(pcn, pslot))
                                        && DBG_MG.getAndIncrement() < DBG_ON) {
                                    Long at = ICON_SYS_AT.get(v);
                                    log("MG t=" + System.currentTimeMillis()
                                            + " id=" + System.identityHashCode(v)
                                            + " " + pslot + " sys=" + Math.round(sys)
                                            + " col=" + Math.round(collapseOf(v))
                                            + " off=" + Math.round(off)
                                            + " want=" + Math.round(sys + off)
                                            + " w=" + v.getWidth()
                                            + " age=" + (at == null ? -1
                                                    : System.currentTimeMillis() - at));
                                }
                                if (DBG_STX.getAndIncrement() < DBG_ON) {
                                    log("sysTx t=" + System.currentTimeMillis()
                                            + " " + pslot + "/"
                                            + v.getClass().getSimpleName()
                                            + " " + Math.round(v.getTranslationX())
                                            + " -> " + Math.round(sys)
                                            + " off=" + Math.round(off));
                                }
                                if (Math.abs(off) < 0.5f) {
                                    ICON_LAST_TX.put(v, sys);
                                    return;
                                }
                                float want = sys + off;
                                IN_TX.set(Boolean.TRUE);
                                try {
                                    v.setTranslationX(want);
                                } finally {
                                    IN_TX.set(Boolean.FALSE);
                                }
                                ICON_LAST_TX.put(v, want);
                                // 负偏移 = 图标往左溢出容器，能不能画出来全看祖先不裁。
                                // 下拉动画里 ColorOS 会往图标上方临时塞新 wrapper
                                // （clipChildren 默认 true），而 onLayout/onViewAdded/
                                // updateStates 那三个 relaxIconClipping 入口都不会为它触发，
                                // 于是这一瞬间左半边被切掉。探针是动画期间唯一的写入者，
                                // 每帧写完顺手沿父链关几层；已是 false 就只有两次字段读。
                                if (off < -0.5f) {
                                    unclip(v, 6);
                                }
                            } catch (Throwable ignored) {
                                // ignore
                            }
                        }
                    });
        } catch (Throwable t) {
            log("probe setTranslationX failed: " + t);
        }
    }

    /**
     * 调画布：width = 原宽 + 左边距 + 右边距，同时用 padding 把图形整体推到
     * 「左边距」那个位置。padding 会让内容区不变（= 原宽），所以图标本身不会被拉伸，
     * 只是左右留白变了；画布总宽变了，后面的图标照旧跟着让位。
     *
     * 只改 lp.width，值没变就跳过，避免 setLayoutParams 触发无限 layout。
     */
    private static void resizeIcon(View child, int padL, int padR) {
        try {
            ViewGroup.LayoutParams lp = child.getLayoutParams();
            if (lp == null) {
                return;
            }
            Integer base = ICON_BASE_W.get(child);
            if (base == null) {
                base = lp.width > 0 ? lp.width : child.getWidth();
                if (base <= 0) {
                    return;
                }
                ICON_BASE_W.put(child, base);
                ICON_BASE_PAD.put(child, new int[]{child.getPaddingLeft(), child.getPaddingRight()});
            }
            int[] basePad = ICON_BASE_PAD.get(child);
            // 注意：这里绝对不能动 padding —— 这几个 View 内部是按固定尺寸自绘的，
            // padding 只会把内容区裁窄（实测信号图标被裁成一条竖线），不会把图挪过去。
            // 「左边距」靠 offsetForView 里的 translationX 平移实现。
            int want = Math.max(1, base + padL + padR);
            if (lp.width != want) {
                if (DBG_IG.getAndIncrement() < DBG_ON) {
                    log("RW t=" + System.currentTimeMillis()
                            + " " + slotOf(child) + " w " + lp.width + "->" + want
                            + " base=" + base + " pl=" + padL + " pr=" + padR);
                }
                lp.width = want;
                child.setLayoutParams(lp);
            }
            if (basePad == null) {
                ICON_BASE_PAD.put(child, new int[]{child.getPaddingLeft(), child.getPaddingRight()});
            }
            ICON_RESIZED.put(child, Boolean.TRUE);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** 边距滑块都归零：把宽高和 padding 还原成系统给的样子。 */
    private static void restoreIcon(View child) {
        try {
            Integer base = ICON_BASE_W.get(child);
            ViewGroup.LayoutParams lp = child.getLayoutParams();
            if (base != null && lp != null && lp.width != base) {
                lp.width = base;
                child.setLayoutParams(lp);
            }
            int[] basePad = ICON_BASE_PAD.get(child);
            if (basePad != null) {
                child.setPadding(basePad[0], child.getPaddingTop(), basePad[1],
                        child.getPaddingBottom());
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    private static void applyIcons(ViewGroup container, boolean afterLayout) {
        if (container == null) {
            return;
        }
        try {
            if (!isStatusBar(container)) {
                return;
            }
            // 密度必须用真值：以前 Math.round(3.5)=4，偏移被放大 14%，更容易压到邻居身上
            float d = container.getResources().getDisplayMetrics().density;
            float marginPx = marginRTenth / 10f * d;
            int n = container.getChildCount();
            if (!afterLayout) {
                return;
            }
            // 双卡：先数一遍有几张卡各自占了一个信号图标（合并态只有 1 个合成图标，不能动手）
            int mobileViews = 0;
            boolean hasVisible = false;
            boolean hasSimIcon = false;
            for (int i = 0; i < n; i++) {
                View c = container.getChildAt(i);
                if (c == null) {
                    continue;
                }
                String sl = slotOf(c);
                if (isMobileSlot(sl)) {
                    mobileViews++;
                }
                if (isMobileSlot(sl) || "stacked_mobile".equals(sl) || "wifi".equals(sl)) {
                    hasSimIcon = true;
                }
                if (c.getVisibility() == View.VISIBLE) {
                    hasVisible = true;
                }
            }
            // 隐藏池里的容器全是 v=8 的占位图标，不打日志；只打真正装了信号图标的那个
            if (hasSimIcon && hasVisible && CONTAINERS_DUMPED.add(container)) {
                StringBuilder sb = new StringBuilder("icons@" + System.identityHashCode(container)
                        + " n=" + n + " mobileViews=" + mobileViews + " |");
                for (int i = 0; i < n; i++) {
                    View c = container.getChildAt(i);
                    if (c == null) {
                        continue;
                    }
                    sb.append(' ').append(i).append(':')
                            .append(c.getClass().getSimpleName())
                            .append('/').append(slotOf(c))
                            .append(" v=").append(c.getVisibility())
                            .append(" w=").append(c.getWidth())
                            .append(" tx=").append(Math.round(c.getTranslationX()));
                }
                log(sb.toString());
            }
            // 「只显示上网卡」：先定谁要藏，再统一改可见性。
            // 藏掉之后必须自己补位 —— 这批图标是 left=0 + translationX 摆的，
            // 光设 GONE 系统不会把后面的图标挪过来，就会留一个空洞。
            boolean[] hideSim = new boolean[n];
            for (int i = 0; i < n; i++) {
                View c = container.getChildAt(i);
                if (c == null) {
                    continue;
                }
                hideSim[i] = shouldHideSim(c, slotOf(c), mobileViews);
            }
            // 补位要在改可见性之前算：宽度得先量到（藏完宽度就没了）
            computeCollapse(container, n, hideSim);
            // Wi-Fi 消失/回来的那一趟布局要强制落一次位（系统之后不会再写）
            boolean wifiFlip = wifiGoneFlip(container);
            if (wifiFlip) {
                startFlipSample();
                // 动画停稳后重推一次（只一次），不在动画期间逐帧抢写，避免「停下又瞬移」
                scheduleIconSettle();
            }
            // 两侧收拢：所有图标一起往左，量一样所以组内间距不变
            for (int i = 0; i < container.getChildCount(); i++) {
                View child = container.getChildAt(i);
                if (child == null) {
                    continue;
                }
                String cn = child.getClass().getName();
                String slot = slotOf(child);
                // 只显示上网卡：把非数据卡的信号图标藏掉
                applySimVisibility(child, hideSim[i]);
                boolean isWifi = cn.contains("Wifi") || "wifi".equals(slot);
                boolean isMobileIcon = isMobileSlot(slot) || "stacked_mobile".equals(slot)
                        || cn.contains("SingleBindableStatusBarComposeIconView");
                float offset = offsetForView(child);
                // 基线一律读「系统真值」（探针记的）。只有在系统还没写过这个 View 时才退化成
                // 当 0 —— 绝不能拿当前 translationX 当基线：下拉动画期间系统每帧改值，
                // 一旦把自己写的 sys+offset 当成新基线，偏移就会翻倍、视觉上瞬移。
                MANAGED.put(child, Boolean.TRUE);
                // 固定移动信号宽度（消除 WiFi 开关时数据箭头槽导致的 77↔88 抽动）
                pinMobileWidth(child);
                float cur = child.getTranslationX();
                Float sysRec = ICON_SYS_TX.get(child);
                float base = sysRec != null ? sysRec : 0f;
                ICON_BASE_TX.put(child, base);
                float tx = base + offset;
                ICON_LAST_TX.put(child, tx);
                // 系统 300ms 内还在逐帧驱动这个图标 → 下拉/收起动画正跑，layout
                // 这时写「基线+偏移」会拿着一帧前的旧基线，和探针的每帧「实时值+偏移」
                // 互相顶 = 左右抖。整段让给探针续写，动画停下后自然由下一次无动画
                // layout 归位（和 applyClock 的 systemDriving 闸门同款）。
                Long drvAt = ICON_SYS_AT.get(child);
                boolean systemDriving = drvAt != null
                        && System.currentTimeMillis() - drvAt < 300L;
                if ((isWifi || isMobileIcon) && DBG_IG.getAndIncrement() < DBG_ON) {
                    log("L t=" + System.currentTimeMillis()
                            + " id=" + System.identityHashCode(child) + " "
                            + cn.substring(cn.lastIndexOf('.') + 1) + "/" + slot
                            + " cur=" + Math.round(cur) + " sys="
                            + (sysRec == null ? "null" : String.valueOf(Math.round(sysRec)))
                            + " base=" + Math.round(base)
                            + " off=" + Math.round(offset) + " -> " + Math.round(tx)
                            + " w=" + child.getWidth()
                            + " flip=" + wifiFlip
                            + (systemDriving ? " [driving]" : "")
                            + " stk=" + stk());
                }
                if ((!systemDriving) && Math.abs(cur - tx) > 0.5f) {
                    IN_TX.set(Boolean.TRUE);
                    try {
                        child.setTranslationX(tx);
                    } finally {
                        IN_TX.set(Boolean.FALSE);
                    }
                }
                // 关裁剪和写位置是两回事：动画期间写位置让位给探针，
                // 但负偏移导致的左溢出随时可能被新插的祖先裁掉，unclip 必须照跑。
                if (marginPx > 0.5f || offset < -0.5f) {
                    unclip(container, 2);
                }
                applyIconWidth(child);
            }
            // Wi-Fi / 信号可能挂在别的容器里，整树再扫一遍才不会漏
            applyTreeIcons(container);
            if (ICON_VIEWS.isEmpty()) {
                collectIconViews();
            }
            // 电池：ColorOS 把 StatBatteryMeterView 挂在 system_icons 里、和图标容器平级，
            // 不在上面那个循环里，得单独按兄弟节点捞一遍，否则收拢时只有电池不动。
            applySiblingMargin(container);
        } catch (Throwable t) {
            log("applyIcons failed: " + t);
        }
    }

    /**
     * 图标容器旁边那排「非容器内」的兄弟图标（目前是电池）也跟着一起左移。
     * 它们的基线同样记在 ICON_BASE_TX，配置变化时由 reapplyOffsets 统一重推。
     *
     * 两种窗口层级不一样：
     * - 状态栏：system_icons › [StatusIconContainer, StatBatteryMeterView]（父级就是兄弟）
     * - 下拉头部：icons › StatusIconContainer，电池挂在更上一层（quick_qs_status_icons）
     * 所以往上找两级。
     */
    private static void applySiblingMargin(ViewGroup container) {
        View level = container;
        for (int up = 0; up < 2; up++) {
            android.view.ViewParent vp;
            try {
                vp = level.getParent();
            } catch (Throwable t) {
                return;
            }
            if (!(vp instanceof ViewGroup)) {
                return;
            }
            ViewGroup parent = (ViewGroup) vp;
            int n;
            try {
                n = parent.getChildCount();
            } catch (Throwable t) {
                return;
            }
            for (int i = 0; i < n; i++) {
                View c;
                try {
                    c = parent.getChildAt(i);
                } catch (Throwable t) {
                    continue;
                }
                if (c == null || c == container || !isBatteryView(c)) {
                    continue;
                }
                applySiblingOffset(c);
            }
            level = parent;
        }
    }

    // ------------------------------------------------------------------
    // 采样器：从「Wi-Fi 消失/回来那趟强制落位」起连 3 秒、每 33ms 打一次移动图标的
    // 屏幕绝对 x / translationX / 画布宽 / 容器右边缘。
    // 动画结束后那一下「自己瞬移」是谁改的，看哪个量先变就知道：
    //   tx 变 → 又有人写了一次平移（对照 MG/sysTx 的始作俑者）
    //   w 变  → 画布宽被改了，右边整排图标跟着让位
    //   cx 变 → 容器右边缘自己动了，不是我们的锅
    //   都不变但肉眼还在跳 → 是 Compose 自己的绘制
    // ------------------------------------------------------------------
    private static volatile long sSmpUntil = 0L;
    private static volatile long sSmpGen = 0L;
    private static final int[] SMP_PT = new int[2];

    private static void startFlipSample() {
        startSampleFor(3000);
    }

    private static void startSampleFor(final long durMs) {
        final long gen = ++sSmpGen;
        sSmpUntil = System.currentTimeMillis() + durMs;
        main().post(new Runnable() {
            private int seq;

            @Override
            public void run() {
                try {
                    long t = System.currentTimeMillis();
                    if (gen != sSmpGen) {
                        return;
                    }
                    if (t > sSmpUntil) {
                        return;
                    }
                    StringBuilder sb = new StringBuilder("SMP t=").append(t)
                            .append(" n=").append(seq++);
                    sb.append(" |");
                    synchronized (CONTAINERS) {
                        for (WeakReference<ViewGroup> r : CONTAINERS) {
                            ViewGroup g = r.get();
                            if (g == null || g.getChildCount() == 0) {
                                continue;
                            }
                            g.getLocationOnScreen(SMP_PT);
                            int gx = SMP_PT[0];
                            sb.append("C").append(System.identityHashCode(g) % 10000)
                                    .append("@").append(gx).append(' ');
                            int n = g.getChildCount();
                            for (int i = 0; i < n; i++) {
                                View v = g.getChildAt(i);
                                if (v == null) {
                                    continue;
                                }
                                String cn = v.getClass().getName();
                                String slot = slotOf(v);
                                boolean mw = isMobileView(cn, slot);
                                if (!mw && !isWifiView(cn, slot)
                                        && !isBatteryView(v)) {
                                    continue;
                                }
                                v.getLocationOnScreen(SMP_PT);
                                sb.append(mw ? " mob" : isWifiView(cn, slot) ? " ifi" : " bat")
                                        .append(" sx=").append(SMP_PT[0])
                                        .append(" tx=").append(Math.round(v.getTranslationX()))
                                        .append(" w=").append(v.getWidth())
                                        .append(" vis=").append(v.getVisibility());
                            }
                        }
                    }
                    log(sb.toString());
                    if (t <= sSmpUntil) {
                        main().postDelayed(this, 33L);
                    }
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        });
    }

    /**
     * Wi-Fi / 移动信号并不总是 StatusIconContainer 的直接子 View（ColorOS 17 上
     * 它们挂在 Compose 的另一个容器里），只遍历 container 的子节点会漏掉 Wi-Fi，
     * 表现就是「滑边距只动信号、Wi-Fi 纹丝不动」。这里顺带把容器往上两层的整棵
     * 子树扫一遍，把真正的图标 View 也纳入管理。
     */
    private static final java.util.Map<View, Boolean> TREE_SEEN =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<View, Boolean>());

    /** 被接管的 Wi-Fi / 移动信号 View（由 attach 探针登记，layout 时刷配置）。 */
    private static final java.util.Map<View, Boolean> ICON_VIEWS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<View, Boolean>());

    /**
     * 从状态栏根节点把 Wi-Fi / 信号 View 收进 ICON_VIEWS。
     *
     * 这两个 View 的类都 override 了 onAttachedToWindow / onLayout，hook 基类方法
     * 抓不到，所以只能在树里自己找。只在还没收全时扫，扫到就够用了
     * （配置变化走 reapplyOffsets 逐个刷，不需要每次布局都找）。
     */
    private static void collectIconViews() {
        try {
            ViewGroup root = sSbRoot.get();
            if (root == null) {
                root = findStatusBarRoot();
                if (root != null) {
                    sSbRoot = new WeakReference<>(root);
                }
            }
            if (root == null) {
                return;
            }
            collectWalk(root, 0);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    private static void collectWalk(View v, int depth) {
        if (v == null || depth > 16) {
            return;
        }
        String cn = v.getClass().getName();
        if (cn.contains("WifiView") || cn.contains("MobileView")) {
            if (ICON_VIEWS.put(v, Boolean.TRUE) == null) {
                log("icon view " + cn.substring(cn.lastIndexOf('.') + 1)
                        + "/" + slotOf(v));
            }
            applySiblingOffset(v);
            applyIconWidth(v);
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            int n;
            try {
                n = g.getChildCount();
            } catch (Throwable t) {
                return;
            }
            for (int i = 0; i < n; i++) {
                View c;
                try {
                    c = g.getChildAt(i);
                } catch (Throwable t) {
                    continue;
                }
                collectWalk(c, depth + 1);
            }
        }
    }

    private static void applyTreeIcons(ViewGroup container) {
        View level = container;
        for (int up = 0; up < 4; up++) {
            android.view.ViewParent vp;
            try {
                vp = level.getParent();
            } catch (Throwable t) {
                return;
            }
            if (!(vp instanceof ViewGroup)) {
                return;
            }
            ViewGroup parent = (ViewGroup) vp;
            walkIconTree(parent, 8);
            level = parent;
        }
    }

    private static void walkIconTree(ViewGroup g, int depth) {
        int n;
        try {
            n = g.getChildCount();
        } catch (Throwable t) {
            return;
        }
        for (int i = 0; i < n; i++) {
            View c;
            try {
                c = g.getChildAt(i);
            } catch (Throwable t) {
                continue;
            }
            if (c == null) {
                continue;
            }
            String cn = c.getClass().getName();
            if (cn.contains("WifiView") || cn.contains("MobileView")) {
                if (TREE_SEEN.put(c, Boolean.TRUE) == null) {
                    log("tree icon " + cn.substring(cn.lastIndexOf('.') + 1)
                            + "/" + slotOf(c) + " w=" + c.getWidth());
                }
                applySiblingOffset(c);
                applyIconWidth(c);
            } else if (depth > 0 && c instanceof ViewGroup) {
                walkIconTree((ViewGroup) c, depth - 1);
            }
        }
    }

    /** 单个图标的画布宽度/左右边距（onLayout 的 children 循环与整树扫描共用）。 */
    private static void applyIconWidth(View child) {
        // 方案 A：彻底放弃「改画布宽度」调间距（那会在动画结束后逐步改宽 → 整排让位 → 瞬移）。
        // 间距全部改由 offsetForView 的纯 translationX 实现。这里只负责把历史上被改过的
        // 宽度/内边距还原回系统原始值，避免旧版本残留导致尺寸错乱。
        if (ICON_RESIZED.remove(child) != null) {
            restoreIcon(child);
        }
    }

    /**
     * 把移动信号图标的 LayoutParams.width 钉到固定值 MOBILE_ICON_FIXED_W_PX，消除 WiFi
     * 开关时因数据箭头槽导致的宽度 77↔88 变化（右对齐时挤压左邻 → 抽动）。
     * 值没变就跳过，避免 setLayoutParams 触发无限 layout。只对移动 slot 生效。
     */
    private static void pinMobileWidth(View c) {
        if (MOBILE_ICON_FIXED_W_PX <= 0) {
            return;
        }
        try {
            String cn = c.getClass().getName();
            String slot = slotOf(c);
            if (!isMobileView(cn, slot)) {
                return;
            }
            // 双重保险：
            // 1) lp.width 钉死；
            // 2) minimumWidth 兜底：即使 lp 是 WRAP_CONTENT、系统重测，测量结果也至少 88px。
            ViewGroup.LayoutParams lp = c.getLayoutParams();
            if (lp != null && lp.width != MOBILE_ICON_FIXED_W_PX) {
                lp.width = MOBILE_ICON_FIXED_W_PX;
                c.setLayoutParams(lp);
            }
            if (c.getMinimumWidth() != MOBILE_ICON_FIXED_W_PX) {
                c.setMinimumWidth(MOBILE_ICON_FIXED_W_PX);
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** 给单个兄弟图标（电池）叠加右侧收紧的偏移。 */
    private static void applySiblingOffset(View c) {
        MANAGED.put(c, Boolean.TRUE);
        // 固定移动信号图标宽度，消除 WiFi 开关时的抽动：
        // 关 WiFi 时数据箭头槽出现 → Compose 图标宽度 77→88（+11px）→ 右对齐排布下左邻
        // NFC/蓝牙被挤左 11px = 抽动。把 lp.width 钉到固定 88px，两状态一致即不再抽动。
        pinMobileWidth(c);
        float offset = offsetForView(c);
        float cur = c.getTranslationX();
        Float sysRec = ICON_SYS_TX.get(c);
        float base = sysRec != null ? sysRec : 0f;
        ICON_BASE_TX.put(c, base);
        float tx = base + offset;
        ICON_LAST_TX.put(c, tx);
        // 同 applyIcons：系统正在逐帧驱动这个图标（下拉/收起动画在跑）就别抢写，
        // 让探针续写「实时值+偏移」，否则旧基线和实时基线差一帧互顶 = 左右抖。
        Long drvAt = ICON_SYS_AT.get(c);
        boolean systemDriving = drvAt != null
                && System.currentTimeMillis() - drvAt < 300L;
        if (DBG_IG.getAndIncrement() < DBG_ON) {
            log("BAT t=" + System.currentTimeMillis()
                    + " cur=" + Math.round(cur) + " base=" + Math.round(base)
                    + " off=" + Math.round(offset) + " -> " + Math.round(tx)
                    + (systemDriving ? " [driving]" : ""));
        }
        if ((!systemDriving) && Math.abs(cur - tx) > 0.5f) {
            IN_TX.set(Boolean.TRUE);
            try {
                c.setTranslationX(tx);
            } catch (Throwable ignored) {
                // ignore
            } finally {
                IN_TX.set(Boolean.FALSE);
            }
        }
        // 同 applyIcons：unclip 不受 systemDriving 闸门影响。层数放宽到 4，
        // 电池这排兄弟图标的父链比容器内图标更深一层。
        if (marginRTenth > 0 || offset < -0.5f) {
            unclip(c, 4);
        }
    }

    // ---------- 3.1) 两侧收拢 + 只显示上网卡 ----------

    /**
     * 图标 View 的 slot 名（wifi / mobile / mobile2 / battery …）。
     * 来自父类 ModernStatusBarView.slot，公开字段 + getter 都有，两条路都试一遍。
     */
    private static final java.util.HashMap<Class<?>, Object> SLOT_ACCESS = new java.util.HashMap<>();

    private static String slotOf(View v) {
        if (v == null) {
            return null;
        }
        Class<?> c = v.getClass();
        Object acc;
        synchronized (SLOT_ACCESS) {
            acc = SLOT_ACCESS.get(c);
        }
        if (acc == null) {
            try {
                acc = c.getMethod("getSlot");
            } catch (Throwable t) {
                try {
                    acc = c.getField("slot");
                } catch (Throwable t2) {
                    acc = Boolean.FALSE;
                }
            }
            synchronized (SLOT_ACCESS) {
                SLOT_ACCESS.put(c, acc);
            }
        }
        try {
            Object o;
            if (acc instanceof java.lang.reflect.Method) {
                o = ((java.lang.reflect.Method) acc).invoke(v);
            } else if (acc instanceof java.lang.reflect.Field) {
                o = ((java.lang.reflect.Field) acc).get(v);
            } else {
                return null;
            }
            return o instanceof String ? (String) o : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** getSlotTag()：ColorOS 自己用来区分同类 slot 的序号，双重卡时可能就是卡槽号。 */
    private static int slotTagOf(View v) {
        if (v == null) {
            return -1;
        }
        try {
            java.lang.reflect.Method m = v.getClass().getMethod("getSlotTag");
            Object o = m.invoke(v);
            return o instanceof Integer ? (Integer) o : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static boolean isMobileSlot(String slot) {        return slot != null && slot.startsWith("mobile");
    }

    /** slot -> subId。AOSP 约定：默认数据卡那一格就叫 "mobile"，其余是 "mobile" + subId。 */
    private static int subIdOfSlot(String slot) {
        if (!isMobileSlot(slot)) {
            return -1;
        }
        String tail = slot.substring("mobile".length());
        if (tail.isEmpty()) {
            // 裸 "mobile" 就是默认数据卡自己的格子
            return defaultDataSubId();
        }
        if (tail.matches("\\d+")) {
            try {
                return Integer.parseInt(tail);
            } catch (Throwable t) {
                return -1;
            }
        }
        return -1;
    }

    private static int defaultDataSubId() {
        try {
            return android.telephony.SubscriptionManager.getDefaultDataSubscriptionId();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int countMobileViews(ViewGroup container) {
        int n = 0;
        for (int i = 0; i < container.getChildCount(); i++) {
            if (isMobileSlot(slotOf(container.getChildAt(i)))) {
                n++;
            }
        }
        return n;
    }

    private static final java.util.HashSet<String> SLOT_LOGGED = new java.util.HashSet<>();
    /** 我们自己藏掉的视图。只有这个集合里的才允许被我们恢复成 VISIBLE。 */
    private static final java.util.Set<View> SIM_HIDDEN =
            java.util.Collections.newSetFromMap(
                    java.util.Collections.synchronizedMap(new java.util.WeakHashMap<View, Boolean>()));

    /**
     * 只显示上网卡：非数据卡的信号图标直接 GONE。
     * 只有当容器里确实存在 ≥2 个独立的信号图标时才动手 —— 合并态只有一个合成图标，
     * 藏掉就什么都没了。
     *
     * 注意：藏掉之后系统不会把后面的图标往前挪（这批图标是 left=0 + translationX 摆的），
     * 所以调用方必须同时用 {@link #computeCollapse} 算补位量。
     */
    private static boolean shouldHideSim(View child, String slot, int mobileViews) {
        if (!isMobileSlot(slot) && !"stacked_mobile".equals(slot)) {
            return false;
        }
        // ColorOS 的 payload：getSlotTag() 对信号图标返回的就是 subId（实测 2 = 移动、1 = 电信），
        // 合并图标返回 0。slot 名一律是 "mobile"，区分不了卡，所以只能靠这个。
        int subId = slotTagOf(child);
        if (subId <= 0) {
            subId = subIdOfSlot(slot);
        }
        int dataSub = defaultDataSubId();
        boolean hide = dataSimOnly && mobileViews >= 2
                && subId > 0 && dataSub > 0 && subId != dataSub;
        if (SLOT_LOGGED.add(slot + "|" + subId + "|" + mobileViews + "|" + hide)) {
            log("sim slot=" + slot + " subId=" + subId + " dataSub=" + dataSub
                    + " views=" + mobileViews + " only=" + dataSimOnly + " hide=" + hide);
        }
        return hide;
    }

    /** 按 shouldHideSim 的结论实际改可见性；只恢复我们自己藏过的，别把系统的翻出来。 */
    private static boolean applySimVisibility(View child, boolean hide) {
        if (hide) {
            SIM_HIDDEN.add(child);
            if (child.getVisibility() != View.GONE) {
                child.setVisibility(View.GONE);
                return true;
            }
        } else if (SIM_HIDDEN.remove(child)) {
            // 只管我们自己藏过的，别把系统因为飞行模式/无服务藏掉的图标翻出来
            if (child.getVisibility() != View.VISIBLE) {
                child.setVisibility(View.VISIBLE);
                return true;
            }
        }
        return false;
    }

    /** 藏掉的图标测不到宽度时（GONE 之后是 0）用这个缓存兜底。 */
    private static final java.util.Map<View, Integer> ICON_HIDDEN_W =
            java.util.Collections.synchronizedMap(
                    new java.util.WeakHashMap<View, Integer>());

    private static int widthOf(View v) {
        int w = v.getWidth();
        if (w > 0) {
            ICON_HIDDEN_W.put(v, w);
            return w;
        }
        Integer cached = ICON_HIDDEN_W.get(v);
        return cached == null ? 0 : cached;
    }

    /**
     * 算补位量：藏掉一张卡之后，把**排在它左边**的图标整体往右推它那么宽。
     *
     * 为什么是推左边而不是收右边：图标是紧挨着从左到右排的（实测 694→740→817→893→975，
     * 首尾相接），右边还有电池当锚点，往左收会在 WiFi 和电池之间裂出新的空档。
     * 左边是通知图标与状态栏之间的天然空白，把这段空白吃掉最不容易看出来。
     */
    private static void computeCollapse(ViewGroup container, int n, boolean[] hide) {
        int[] wid = new int[n];
        for (int i = 0; i < n; i++) {
            View c = container.getChildAt(i);
            if (c == null) {
                continue;
            }
            wid[i] = widthOf(c);
        }
        if (DBG_IG.getAndIncrement() < DBG_ON) {
            StringBuilder sb = new StringBuilder("CC t=").append(System.currentTimeMillis())
                    .append(" id=").append(System.identityHashCode(container) % 10000)
                    .append(" cw=").append(container.getWidth()).append(" |");
            for (int i = 0; i < n; i++) {
                View c = container.getChildAt(i);
                if (c == null) {
                    continue;
                }
                String sl = slotOf(c);
                if (!isMobileView(c.getClass().getName(), sl)
                        && !isWifiView(c.getClass().getName(), sl)) {
                    continue;
                }
                sb.append(' ').append(sl).append(hide[i] ? "!" : "=")
                        .append(wid[i]).append("/v").append(c.getVisibility());
            }
            log(sb.toString());
        }
        for (int i = 0; i < n; i++) {
            View c = container.getChildAt(i);
            if (c == null) {
                continue;
            }
            if (hide[i]) {
                ICON_COLLAPSE.remove(c);
                continue;
            }
            float sum = 0f;
            for (int j = 0; j < n; j++) {
                if (j == i || !hide[j]) {
                    continue;
                }
                // 只按容器索引比大小，绝不用 translationX 判前后 ——
                // translationX 被系统反复重写，用它排序会把同一张卡判成「在隐藏项左边」，
                // 于是可见的那张卡被推了整整一格宽，直接压到 Wi-Fi 上。
                // 容器里的子项是按显示顺序排的（索引越大越靠右），这才可靠。
                if (j > i) {
                    sum += wid[j];
                }
            }
            if (Math.abs(collapseOf(c) - sum) > 0.5f) {
                ICON_COLLAPSE.put(c, sum);
            }
        }
    }

    /**
     * 双卡信号样式直接写 ColorOS 的系统设置（SystemUI 有 WRITE_SETTINGS 权限）：
     * <pre>signal_icon_display_mode  0 = 独立，1 = 合并</pre>
     * 好处是改完立刻生效，而且系统设置页里看到的就是同一个值 —— 相当于把它覆盖了。
     * 「只显示上网卡」必须先切成独立，否则只有一个合成图标，没法单独隐藏。
     */
    private static void applySimStyle() {
        Context ctx = sCtx;
        if (ctx == null) {
            return;
        }
        int v;
        if (dataSimOnly) {
            v = 0;
        } else if (simStyle >= 0) {
            v = simStyle;
        } else {
            return;
        }
        try {
            android.content.ContentResolver cr = ctx.getContentResolver();
            int cur = android.provider.Settings.System.getInt(cr,
                    Config.SYS_SIM_DISPLAY_MODE, -1);
            if (cur != v) {
                boolean ok = android.provider.Settings.System.putInt(cr,
                        Config.SYS_SIM_DISPLAY_MODE, v);
                log("simStyle " + cur + " -> " + v + " (only=" + dataSimOnly + ") ok=" + ok);
            }
        } catch (Throwable t) {
            log("simStyle write failed: " + t);
        }
    }

    /**
     * 状态栏和下拉控制中心头部各有一套 StatusIconContainer（id 都是 statusIcons），
     * 两套图标必须用同一份参数处理，否则收起态调好了、下拉后又回到另一个位置，
     * 用户看到的就是"两种布局"。所以父链上只要是状态栏或通知面板就都算。
     */
    private static boolean isStatusBar(View v) {
        try {
            View cur = v;
            for (int i = 0; i < 24 && cur != null; i++) {
                String cn = cur.getClass().getName();
                if (cn.contains("PhoneStatusBarView") || cn.contains("EndSideContentLayout")
                        || cn.contains("StartSideExceptHeadsUpLayout")
                        || cn.contains("NotificationShadeWindowView")
                        || cn.contains("QuickStatusBarHeader")) {
                    return true;
                }
                if (!(cur.getParent() instanceof View)) {
                    break;
                }
                cur = (View) cur.getParent();
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String chain(View v) {
        StringBuilder sb = new StringBuilder();
        View cur = v;
        for (int i = 0; i < 14 && cur != null; i++) {
            sb.append(cur.getClass().getSimpleName()).append(" < ");
            if (!(cur.getParent() instanceof View)) {
                break;
            }
            cur = (View) cur.getParent();
        }
        return sb.toString();
    }

    // ---------- 4) 通知图标左边距 ----------

    private static int hookNotifArea(ClassLoader cl) {
        Class<?> clazz = findClass(NOTIF_CONTAINER_CLASS, cl);
        if (clazz == null) {
            log("notif container not found");
            return 0;
        }
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.thisObject instanceof View) {
                    View v = (View) param.thisObject;
                    rememberNotif(v);
                    applyNotifX(v);
                    // 下拉期间系统会重建图标 View，新建的默认 alpha=1，补一次淡出态
                    applyNotifAlpha(v);
                }
            }
        };
        int n = 0;
        try {
            XposedBridge.hookAllMethods(clazz, "onLayout", hook);
            n++;
        } catch (Throwable t) {
            log("hook notif onLayout failed: " + t);
        }
        return n;
    }

    /**
     * 通知图标那一排外面是 AlphaOptimizedFrameLayout id=notification_icon_area，
     * 直接给它设 translationX 就能整体平移，不用动布局（布局是 Compose 摆的）。
     * 平移可能超出父容器宽度，顺手把祖先的 clipChildren 关掉，免得被裁。
     */
    /** 通知图标区的原始 translationX 基线（只记一次，避免每次叠加漂移）。 */
    private static final java.util.Map<View, Float> NOTIF_BASE =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<View, Float>());
    /** 收起态算好的通知图标偏移：下拉期间直接复用，整排图标全程不动。 */
    private static final java.util.Map<View, Float> NOTIF_GAP_TX =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<View, Float>());

    // ---------- 通知图标下拉直接消失 ----------
    // A 下滑时往左收敛、通知图标位置不动，两者越离越远；不做渐变，
    // 一下滑（展开分数 > 0）即刻隐去，收起（p = 0）立刻复原。
    private static float notifAlphaFor(float p) {
        return p <= 0f ? 1f : 0f;
    }

    /**
     * 通知图标看的是「两个面板里更开的那个」：左侧通知中心、右侧控制中心都会把那排
     * 应用图标盖住，任一拉开就得隐；两边都回到 0 才复原。
     */
    private static float iconExpand() {
        float a = sNotifExpand > 0f ? sNotifExpand : 0f;
        float b = sQsExpand > 0f ? sQsExpand : 0f;
        return Math.max(a, b);
    }

    /** 定位真正要淡出 / 平移的 View：优先状态栏的 notification_icon_area。 */
    private static View notifTarget(View container) {
        View target = findAncestorById(container, "notification_icon_area", 3);
        if (target != null) {
            return target;
        }
        // 下拉面板 / 锁屏里也有同款容器，没有状态栏那个 id 就别动
        return isStatusBar(container) ? container : null;
    }

    /** 只认收起态状态栏那一排图标，别把面板/QS 头部的同款容器一起淡掉。 */
    private static boolean inCollapsedStatusBar(View v) {
        View cur = v;
        for (int i = 0; i < 14 && cur != null; i++) {
            String cn = cur.getClass().getName();
            if (cn.contains("PhoneStatusBarView")) {
                return true;
            }
            if (cn.contains("NotificationShadeWindowView")
                    || cn.contains("QuickStatusBarHeader")) {
                return false;
            }
            if (!(cur.getParent() instanceof View)) {
                break;
            }
            cur = (View) cur.getParent();
        }
        return false;
    }

    private static void applyNotifAlpha(View container) {
        try {
            if (!inCollapsedStatusBar(container)) {
                return;
            }
            View target = notifTarget(container);
            if (target == null) {
                return;
            }
            float a = notifAlphaFor(iconExpand());
            setAlphaPin(target, a);
        } catch (Throwable t) {
            log("applyNotifAlpha failed: " + t);
        }
    }

    /**
     * ColorOS 会在下拉 p≈0.13~0.24 这段自己跑一个 alpha 动画，把 notification_icon_area
     * 从 0 一路拉回 1；它的写入发生在我们这帧回调之后，合成出来的画面就是「下滑中途
     * 通知图标又冒出来」。逐帧重写压不住，所以沿单子链往下再钉一层：父层被系统抬上去
     * 也没用，透明度是相乘的，最深那层是 0 就还是看不见。
     */
    private static void setAlphaPin(View target, float a) {
        writeAlpha(target, a);
        View cur = target;
        for (int i = 0; i < 4 && cur instanceof ViewGroup; i++) {
            ViewGroup g = (ViewGroup) cur;
            if (g.getChildCount() != 1) {
                break;
            }
            View c = g.getChildAt(0);
            if (c == null) {
                break;
            }
            writeAlpha(c, a);
            cur = c;
        }
    }

    private static void writeAlpha(View v, float a) {
        if (Math.abs(v.getAlpha() - a) > 0.004f) {
            v.setAlpha(a);
        }
    }

    private static void applyNotifAlphaAll() {
        for (View v : aliveNotifs()) {
            applyNotifAlpha(v);
        }
    }

    private static void applyNotifX(View container) {
        applyNotifX(container, false);
    }

    /** force=true：配置刚变，必须按新值重算，忽略下拉动画的缓存。 */
    private static void applyNotifX(View container, boolean force) {
        try {
            float density = container.getResources().getDisplayMetrics().density;
            View target = notifTarget(container);
            if (target == null) {
                return;
            }
            // 基线只记一次：这个容器每帧都重新 layout，直接覆盖位置会让整排通知塌回原点
            Float base = NOTIF_BASE.get(target);
            if (base == null) {
                base = target.getTranslationX();
                NOTIF_BASE.put(target, base);
            }
            float tx;
            // 下拉/收起动画里沿用收起态算好的偏移，整排图标全程不动（避免抓到
            // 面板大时钟 B 或实时 scale，之前就是这么「图标乱飞」的）。但配置刚变
            // 时必须强制重算，否则滑块拖动会被缓存吞掉：前几档没反应、某一刻
            // 缓存失效才一次性跳过去 —— 就是用户看到的「突然瞬移」。
            if (!force && (iconExpand() > 0f
                    || System.currentTimeMillis() - clockSysAt < 300L)) {
                Float cached = NOTIF_GAP_TX.get(target);
                if (cached == null) {
                    return;
                }
                tx = cached;
            } else {
                // 统一成一条增量公式，完全不读 View 几何（不再受时钟 View 右侧
                // 留白、实时 scale、抓错参照物影响）：
                //  - fine   旧的「左边距」微调（±2dp）
                //  - follow 左侧收紧时 A 的右移量，图标等量跟随 → 与 A 的距离恒定
                //  - gap    在系统默认间距基础上往右拉开，0 = 系统原位置
                // gap=0 与 gap=0.1dp 共用同一套基准，不再有 0→小值的固定跳变。
                float limit = 2f * density;
                float fine = offsetPx(notifXDp, density);
                if (fine > limit) {
                    fine = limit;
                } else if (fine < -limit) {
                    fine = -limit;
                }
                float follow = marginTenth * Config.OFFSET_UNIT * density * clockTaper();
                float gap = notifGapTenth * Config.OFFSET_UNIT * density;
                tx = fine + follow + gap;
                NOTIF_GAP_TX.put(target, tx);
                if (DBG.getAndIncrement() < 12) {
                    log("notifX gap=" + notifGapTenth + " follow=" + Math.round(follow)
                            + " tx=" + Math.round(tx) + " force=" + force);
                }
            }
            float want = base + tx;
            if (Math.abs(target.getTranslationX() - want) > 0.5f) {
                target.setTranslationX(want);
                if (notifXDp != 0 || notifGapTenth > 0 || marginTenth > 0) {
                    unclip(target, 3);
                }
            }
            // 隐私小点不再参与任何收紧：只把旧版本可能写过的偏移一次性归零，
            // 之后完全交回系统（不隐藏、不移动）。
            if (sPrivacyReleased.compareAndSet(false, true)) {
                movePrivacyChip(0f);
            }
        } catch (Throwable t) {
            log("applyNotifX failed: " + t);
        }
    }

    /** 隐私小点 window 根 view 缓存（反射拿，找到一次就缓存）。 */
    private static java.lang.ref.WeakReference<View> sPrivacyChip = null;
    /** 隐私点偏移归零只做一次：之后模块不再碰这个 window。 */
    private static final java.util.concurrent.atomic.AtomicBoolean sPrivacyReleased =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 把隐私小点 window 往左挪 px（负 translationX）。找不到/失败静默跳过。 */
    private static void movePrivacyChip(float px) {
        try {
            View chip = sPrivacyChip != null ? sPrivacyChip.get() : null;
            if (chip == null || !chip.isAttachedToWindow()) {
                Object glob = Class.forName("android.view.WindowManagerGlobal")
                        .getMethod("getInstance").invoke(null);
                @SuppressWarnings("unchecked")
                java.util.ArrayList<View> views =
                        (java.util.ArrayList<View>) glob.getClass().getField("mViews").get(glob);
                synchronized (views) {
                    for (int k = views.size() - 1; k >= 0; k--) {
                        View v = views.get(k);
                        try {
                            if (v.getId() != View.NO_ID && "privacy_chip_window"
                                    .equals(v.getResources().getResourceEntryName(v.getId()))) {
                                chip = v;
                                sPrivacyChip = new java.lang.ref.WeakReference<>(v);
                                break;
                            }
                        } catch (Throwable ignored) {
                            // 没有 id 的 window 根，跳过
                        }
                    }
                }
            }
            if (chip != null) {
                float want = -px;
                if (Math.abs(chip.getTranslationX() - want) > 0.5f) {
                    chip.setTranslationX(want);
                }
            }
        } catch (Throwable ignored) {
            // 隐私点 window 不存在或反射受限：静默跳过
        }
    }

    private static TextView firstClock() {
        for (TextView tv : aliveClocks()) {
            // CLOCK_VIEWS 里只有状态栏 A（假时钟/锁屏钟会被 remove），
            // 别把通知面板的大时钟 B 当参照物，否则图标会被搬到半屏外。
            if (tv != null && tv.getWidth() > 0 && tv.isShown()
                    && CLOCK_VIEWS.containsKey(tv)) {
                return tv;
            }
        }
        return null;
    }

    private static View findAncestorById(View v, String idName, int maxDepth) {
        View cur = v;
        for (int i = 0; i < maxDepth && cur != null; i++) {
            try {
                if (cur.getId() != View.NO_ID
                        && idName.equals(cur.getResources().getResourceEntryName(cur.getId()))) {
                    return cur;
                }
            } catch (Throwable ignored) {
                // ignore
            }
            if (!(cur.getParent() instanceof View)) {
                return null;
            }
            cur = (View) cur.getParent();
        }
        return null;
    }

    private static void unclip(View v, int levels) {
        View cur = v;
        for (int i = 0; i < levels && cur != null; i++) {
            if (cur.getClipBounds() != null) {
                cur.setClipBounds(null);
            }
            if (cur.getParent() instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) cur.getParent();
                if (g.getClipChildren()) {
                    g.setClipChildren(false);
                }
                if (g.getClipToPadding()) {
                    g.setClipToPadding(false);
                }
                if (g.getClipBounds() != null) {
                    g.setClipBounds(null);
                }
                cur = g;
            } else {
                return;
            }
        }
    }

    /**
     * 关掉裁剪只是「事后擦屁股」：下拉 morph 期间 ColorOS 会在同一帧里又把某个
     * 祖先的 clipChildren / clipToPadding 设回 true（或塞一个 clipBounds），顺序在
     * 我们探针 unclip 之后，于是这一帧照裁不误 —— 表现就是移动信号左缘瞬间被切
     * 掉几像素、下一帧又恢复。这里直接在 setters 源头拦：只要目标是我们图标的
     * 祖先，就不允许它重新开启裁剪。
     */
    private static void hookClipSource() {
        XC_MethodHook boolHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length > 0 && Boolean.TRUE.equals(param.args[0])
                        && param.thisObject instanceof ViewGroup
                        && isAncestorOfIcon((ViewGroup) param.thisObject)) {
                    param.args[0] = Boolean.FALSE;
                    if (DBG_CLIP.getAndIncrement() < 40) {
                        log("clipSrc " + param.method.getName() + " "
                                + param.thisObject.getClass().getSimpleName()
                                + " forced-off");
                    }
                }
            }
        };
        try {
            XposedBridge.hookAllMethods(ViewGroup.class, "setClipChildren", boolHook);
            XposedBridge.hookAllMethods(ViewGroup.class, "setClipToPadding", boolHook);
        } catch (Throwable t) {
            log("hookClipSource bool failed: " + t);
        }
        try {
            XposedBridge.hookAllMethods(View.class, "setClipBounds", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length > 0 && param.args[0] != null
                            && param.thisObject instanceof View) {
                        View v = (View) param.thisObject;
                        if (isIconView(v)
                                || (v instanceof ViewGroup && isAncestorOfIcon((ViewGroup) v))) {
                            param.args[0] = null;
                            if (DBG_CLIP.getAndIncrement() < 40) {
                                log("clipSrc setClipBounds "
                                        + v.getClass().getSimpleName() + " forced-null");
                            }
                        }
                    }
                }
            });
        } catch (Throwable t) {
            log("hookClipBounds failed: " + t);
        }
    }

    /** g 是否是我们任一接管图标的祖先（沿图标父链向上比对）。 */
    private static boolean isAncestorOfIcon(ViewGroup g) {
        java.util.List<View> icons = new ArrayList<>();
        synchronized (MANAGED) {
            icons.addAll(MANAGED.keySet());
        }
        synchronized (ICON_VIEWS) {
            icons.addAll(ICON_VIEWS.keySet());
        }
        for (View v : icons) {
            android.view.ViewParent p = v == null ? null : v.getParent();
            for (int i = 0; i < 24 && p instanceof View; i++) {
                if (p == g) {
                    return true;
                }
                p = ((View) p).getParent();
            }
        }
        return false;
    }

    // ---------- 5) 自定义 Wi-Fi / 信号图标 ----------

    /**
     * 两个图标最终都是「按资源 id 取 Drawable」：
     * 信号是 AppCompatResources.getDrawable → Resources#getDrawableForDensity，
     * Wi-Fi 是 ImageView#setImageResource → 同一个方法。
     * 所以只要在这一层把结果换掉，上层的着色（信号是 Compose 的 SrcIn ColorFilter、
     * Wi-Fi 是 ImageView 的 tintList）会自动套到我们的图形上，颜色永远是系统的。
     */
    private static int hookCustomIcons() {
        int n = 0;
        // 1) 兜底：任何按资源 id 取图的地方（Resources 内部有 drawable 缓存，
        //    所以这条只在首次加载时命中，后面的路径靠下面两个）
        try {
            XposedBridge.hookAllMethods(android.content.res.Resources.class,
                    "getDrawableForDensity", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (param.thisObject instanceof android.content.res.Resources
                                        && param.args != null && param.args.length > 0
                                        && param.args[0] instanceof Integer) {
                                    maybeReplace(param, (android.content.res.Resources) param.thisObject,
                                            (Integer) param.args[0]);
                                }
                            } catch (Throwable t) {
                                log("replaceIcon failed: " + t);
                            }
                        }
                    });
            n++;
        } catch (Throwable t) {
            log("hook getDrawableForDensity failed: " + t);
        }
        // 2) Compose 那边（信号图标）每次都走 AppCompatResources.getDrawable，不受缓存影响
        try {
            Class<?> acr = findClass("androidx.appcompat.content.res.AppCompatResources", sLoader);
            if (acr != null) {
                XposedBridge.hookAllMethods(acr, "getDrawable", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (param.args != null && param.args.length >= 2
                                    && param.args[0] instanceof Context
                                    && param.args[1] instanceof Integer) {
                                maybeReplace(param, ((Context) param.args[0]).getResources(),
                                        (Integer) param.args[1]);
                            }
                        } catch (Throwable t) {
                            log("acr replace failed: " + t);
                        }
                    }
                });
                n++;
            } else {
                log("AppCompatResources not found");
            }
        } catch (Throwable t) {
            log("hook AppCompatResources failed: " + t);
        }
        // 3) Wi-Fi 图标是 ImageView#setImageResource
        try {
            XposedBridge.hookAllMethods(android.widget.ImageView.class, "setImageResource",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!(param.thisObject instanceof ImageView)
                                        || param.args == null || param.args.length < 1
                                        || !(param.args[0] instanceof Integer)) {
                                    return;
                                }
                                ImageView iv = (ImageView) param.thisObject;
                                int id = (Integer) param.args[0];
                                if (classify(iv.getResources(), id) != 0) {
                                    // 记下"哪个 View 正在显示哪张系统图标"，
                                    // 开关晚于开机打开时也能回头补刷
                                    synchronized (LAST_ICONS) {
                                        LAST_ICONS.put(iv, id);
                                    }
                                }
                                Drawable orig = iv.getDrawable();
                                Drawable d = customFor(iv.getResources(), id, orig);
                                if (d != null && !sameIcon(d, iv.getDrawable())) {
                                    inheritPaint(iv, d);
                                    iv.setImageDrawable(d);
                                }
                            } catch (Throwable t) {
                                log("iv replace failed: " + t);
                            }
                        }
                    });
            n++;
        } catch (Throwable t) {
            log("hook setImageResource failed: " + t);
        }
        return n;
    }

    /**
     * 把一个 View 上"已经生效的着色"搬到新 Drawable 上。
     * ColorOS 的图标 View 多数组不在 XML 里，而是运行时 setColorFilter/tintList 上去的；
     * 我们中途换图不会触发它重新上色，不搬就会掉回原色（白底白图）。
     */
    private static void inheritPaint(ImageView iv, Drawable d) {
        if (iv == null || d == null) {
            return;
        }
        try {
            android.content.res.ColorStateList csl = iv.getImageTintList();
            if (csl != null) {
                d.setTintList(csl);
            }
        } catch (Throwable ignored) {
            // ignore
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.graphics.ColorFilter cf = iv.getColorFilter();
                if (cf != null) {
                    d.setColorFilter(cf);
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** 两张图是不是"同一张同色"，是的话就不用再 setImageDrawable 折腾一遍（会把着色洗掉）。 */
    private static boolean sameIcon(Drawable a, Drawable b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof VecDrawable && b instanceof VecDrawable) {
            return ((VecDrawable) a).currentTint() == ((VecDrawable) b).currentTint();
        }
        return a == b;
    }

    /** 命中就替换成自定义图形，否则保持原样。 */    private static void maybeReplace(XC_MethodHook.MethodHookParam param,
                                     android.content.res.Resources res, int id) {
        if (!anyIconTweak()) {
            return;
        }
        if (Boolean.TRUE.equals(IN_BUILD.get())) {
            return;
        }
        Object r = param.getResult();
        if (!(r instanceof Drawable)) {
            return;
        }
        Drawable d = customFor(res, id, (Drawable) r);
        if (d != null) {
            param.setResult(d);
            if ((d instanceof VecDrawable || d instanceof PackLayer.ClipLevelDrawable)
                    && PKDBG.getAndIncrement() < 30) {
                try {
                    log("pack replaced: " + res.getResourceEntryName(id)
                            + " via " + param.method.getName());
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        }
    }

    /** 命中分类且图标包里有同名 XML 就替换，否则保持原样。 */
    private static Drawable customFor(android.content.res.Resources res, int id) {
        return customFor(res, id, null);
    }

    /**
     * @param orig 系统原本那张图标（用来取"此刻该用什么颜色"）。可为 null。
     */
    private static Drawable customFor(android.content.res.Resources res, int id, Drawable orig) {
        if (Boolean.TRUE.equals(IN_BUILD.get())) {
            return null;
        }
        int kind = classify(res, id);
        if (kind == K_NONE) {
            return null;
        }
        // 箭头 / 制式标识：命中开关就换成等尺寸的透明图（保留占位，布局不塌）
        // 注：数据箭头也是画在图标预留画布内的 Compose 自绘元素，用等尺寸透明图即可
        // 做到"看不见且不改变其它图标位置"（实测稳态下信号 icon 宽、电池位置均不变）。
        if (kind == K_WIFI_ARROW && hideWifiArrow) {
            return blankFor(res, id);
        }
        if (kind == K_DATA_ARROW && hideDataArrow) {
            return blankFor(res, id);
        }
        if (kind == K_WIFI_STD && hideWifiStd) {
            return blankFor(res, id);
        }
        if (!packOn) {
            return null;
        }
        String name;
        try {
            name = res.getResourceEntryName(id);
        } catch (Throwable ignored) {
            return null;
        }
        if (name == null) {
            return null;
        }
        Drawable cached = FIT_CACHE.get(id);
        if (cached != null) {
            // 每次都发一份独立的：Compose / ImageView 会往 drawable 上写 alpha、
            // bounds、colorFilter，共用同一个实例会互相污染（下拉通知栏时图标
            // 被淡出后一直半透明，就是这个问题）。
            return VecDrawable.dup(cached);
        }
        Drawable d = packDrawable(res, name);
        if (d == null) {
            return null;
        }
        if (d instanceof VecDrawable) {
            // 每个 id 一份，各自上各自的颜色；模板本身不带色
            VecDrawable v = ((VecDrawable) d).copy();
            int c = 0;
            int own = v.monoColor();
            // 黑的/白的视为"没有自己的颜色"，跟随系统（白底黑图、暗底白图）；
            // 用户自己填了彩色（比如鲜绿电池）就保留他的，不要被系统色盖掉
            boolean neutral = own == 0x000000 || own == 0xFFFFFF;
            if (v.isMonochrome() && neutral) {
                // 用户包里的 XML 是从系统包导出的，属性引用在导出时被固化成白色，
                // 一旦系统这层没给我们 tint（Compose / setColorFilter 路径），
                // 白底上就是白图标 —— 所以这里主动从系统原图标取当前实际颜色。
                c = iconPaintColor(orig, name);
                if (c != 0) {
                    v.setTint(c);
                }
            }
            d = v;
        } else if (d instanceof PackLayer.ClipLevelDrawable) {
            // 进度层里那张图形同样是导出的单色图（#000000），不主动上色的话
            // 深色状态栏上就是一团黑
            Drawable inner = ((PackLayer.ClipLevelDrawable) d).innerDrawable();
            if (inner instanceof VecDrawable) {
                VecDrawable v = (VecDrawable) inner;
                int own = v.monoColor();
                boolean neutral = own == 0x000000 || own == 0xFFFFFF;
                if (v.isMonochrome() && neutral) {
                    int c = iconPaintColor(orig, name);
                    if (c != 0) {
                        v.setTint(c);
                    }
                }
            }
        }
        FIT_CACHE.put(id, d);
        if (name.contains("battery") || name.contains("charge")) {
            int tn = 0;
            Drawable probe = d;
            if (probe instanceof PackLayer.ClipLevelDrawable) {
                probe = ((PackLayer.ClipLevelDrawable) probe).innerDrawable();
            }
            if (probe instanceof VecDrawable) {
                tn = ((VecDrawable) probe).currentTint();
            }
            log("pack battery " + name + " cls=" + d.getClass().getSimpleName()
                    + " tint=" + Integer.toHexString(tn) + " lvl-mode="
                    + (d instanceof PackLayer.ClipLevelDrawable ? "clip" : "plain"));
        }
        return d;
    }

    /**
     * 取系统原图标"此刻真正显示的颜色"：直接把原 Drawable 画到小位图上看像素。
     * 比读 VectorDrawable 私有字段可靠，也不用关心它内部是 ?attr 还是 tint。
     */
    private static int iconPaintColor(Drawable src, String name) {
        if (src == null) {
            return 0;
        }
        if (src instanceof VecDrawable) {
            // 已经是我们自己的图（刷新路径），直接沿用它的着色
            return ((VecDrawable) src).currentTint();
        }
        final int n = 40;
        android.graphics.Bitmap bmp = null;
        try {
            bmp = android.graphics.Bitmap.createBitmap(n, n,
                    android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(bmp);
            android.graphics.Rect old = src.copyBounds();
            src.setBounds(0, 0, n, n);
            try {
                src.draw(cv);
            } finally {
                src.setBounds(old);
            }
            int best = 0;
            int bestA = 0;
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    int p = bmp.getPixel(x, y);
                    int a = android.graphics.Color.alpha(p);
                    if (a > bestA) {
                        bestA = a;
                        best = p;
                    }
                }
            }
            if (bestA < 8) {
                return 0;
            }
            return 0xFF000000 | (best & 0xFFFFFF);
        } catch (Throwable t) {
            return 0;
        } finally {
            if (bmp != null) {
                bmp.recycle();
            }
        }
    }

    private static Drawable blankFor(android.content.res.Resources res, int id) {
        Drawable cached = FIT_CACHE.get(id);
        if (cached != null) {
            return cached;
        }
        int w = 0;
        int h = 0;
        try {
            IN_BUILD.set(Boolean.TRUE);
            Drawable orig;
            try {
                orig = res.getDrawable(id, null);
            } finally {
                IN_BUILD.remove();
            }
            if (orig != null) {
                w = orig.getIntrinsicWidth();
                h = orig.getIntrinsicHeight();
            }
        } catch (Throwable ignored) {
            // ignore
        }
        BlankDrawable bd = new BlankDrawable(w, h);
        FIT_CACHE.put(id, bd);
        return bd;
    }

    /** 只要图标包开着或有隐藏开关在动图标外观，就走替换流程。 */
    private static boolean anyIconTweak() {
        return packOn || hideWifiArrow || hideDataArrow || hideWifiStd;
    }

    private static final java.util.HashMap<Integer, Drawable> FIT_CACHE = new java.util.HashMap<>();

    /** ImageView -> 它最后一次被设置的系统图标资源 id（用于配置变更后补刷）。 */
    private static final java.util.WeakHashMap<ImageView, Integer> LAST_ICONS =
            new java.util.WeakHashMap<>();

    private static final ThreadLocal<Boolean> IN_BUILD = new ThreadLocal<>();

    private static final java.util.HashMap<Integer, Integer> KIND_CACHE = new java.util.HashMap<>();

    /** 资源分类结果（结果缓存，避免每帧查资源名）。 */
    private static final int K_NONE = 0;
    private static final int K_WIFI_ARROW = 1;
    private static final int K_DATA_ARROW = 2;
    private static final int K_WIFI_STD = 3;
    /** 状态栏图标：图标包里有同名 XML 就替换。 */
    private static final int K_PACK = 4;

    private static int classify(android.content.res.Resources res, int id) {
        Integer cached = KIND_CACHE.get(id);
        if (cached != null) {
            return cached;
        }
        int kind = K_NONE;
        try {
            String n = res.getResourceEntryName(id);
            if (n == null) {
                n = "";
            }
            n = n.toLowerCase();
            kind = classifyName(n);
        } catch (Throwable ignored) {
            // ignore
        }
        KIND_CACHE.put(id, kind);
        return kind;
    }

    /** 纯名字分类，方便单测与日志核对。 */
    private static int classifyName(String n) {
        // ---- 箭头：stat_signal_activity_wifi_* / stat_signal_activity_* / *_stacked_activity_* ----
        if (n.contains("activity")) {
            if (n.contains("wifi")) {
                return K_WIFI_ARROW;
            }
            if (n.contains("inout") || n.contains("_in") || n.contains("_out")
                    || n.contains("default") || n.contains("stat_signal_activity")) {
                return K_DATA_ARROW;
            }
            return K_NONE;
        }
        // ---- Wi-Fi 制式标识：stat_signal_wifi_6 / _6e / _7（不含 "signal" 子串）----
        if (n.startsWith("stat_signal_wifi_")) {
            String tail = n.substring("stat_signal_wifi_".length());
            if (tail.matches("\\d+[a-z]?")) {
                return K_WIFI_STD;
            }
        }
        // ---- 其余状态栏图标（电池/WiFi/信号/蓝牙/NFC/闹钟…）：包里有同名就换 ----
        if (n.startsWith("stat_")) {
            return K_PACK;
        }
        return K_NONE;
    }

    // ---------- 本地缓存 ----------
    //
    // 配置和图标包都放在模块 App 的私有目录，由 App 的 ContentProvider 提供。
    // 但 ColorOS 会把后台的 App 冻结，SystemUI 冷启动时 provider 根本连不上
    // （logcat: "Failed to find provider info for com.tonyma.hide5g.config"），
    // 配置就会退回默认值、图标包整体失效。
    // 所以这里把配置和包内 XML 各存一份到 SystemUI 自己的数据目录，
    // provider 不可用时直接读副本。

    /** SystemUI 进程私有缓存目录。 */
    private static java.io.File cacheDir() {
        Context c = sCtx;
        if (c == null) {
            return null;
        }
        try {
            java.io.File d = new java.io.File(c.getFilesDir(), "hide5g");
            if (!d.exists() && !d.mkdirs()) {
                return null;
            }
            return d;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 把当前内存里的配置落一份到本地，下次 provider 不可用时还原。 */
    private static void saveConf() {
        java.io.File d = cacheDir();
        if (d == null) {
            return;
        }
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(hideNetType ? 1 : 0).append('|')
                    .append(clockPercent).append('|')
                    .append(wifiOffsetDp).append('|')
                    .append(mobileOffsetDp).append('|')
                    .append(clockYTenth).append('|')
                    .append(notifXDp).append('|')
                    .append(iconVer).append('|')
                    .append(hideWifiArrow ? 1 : 0).append('|')
                    .append(hideDataArrow ? 1 : 0).append('|')
                    .append(hideWifiStd ? 1 : 0).append('|')
                    .append(batteryTextPercent).append('|')
                    .append(batteryTextYTenth).append('|')
                    .append(debugOverlay ? 1 : 0).append('|')
                    .append(statusBarHeightDp).append('|')
                    .append(wifiIconWTenth).append('|')
                    .append(wifiImgXTenth).append('|')
                    .append(mobileIconWTenth).append('|')
                    .append(mobileImgXTenth).append('|')
                    .append(notifGapTenth).append('|')
                    .append(packOn ? 1 : 0).append('|')
                    .append(marginTenth).append('|')
                    .append(dataSimOnly ? 1 : 0).append('|')
                    .append(wifiIconLTenth).append('|')
                    .append(wifiIconRTenth).append('|')
                    .append(mobileIconLTenth).append('|')
                    .append(mobileIconRTenth).append('|')
                    .append(marginRTenth).append('|')
                    .append(wifiOffShiftTenth).append('|')
                    .append(batteryIconLTenth).append('|')
                    .append(wifiNudgeTenth).append('|')
                    .append(mobileNudgeTenth).append('|')
                    .append(mobileLPadTenth);
            java.io.FileOutputStream o =
                    new java.io.FileOutputStream(new java.io.File(d, "conf.txt"));
            try {
                o.write(sb.toString().getBytes("UTF-8"));
            } finally {
                o.close();
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** 读本地副本；没有或损坏返回 false。 */
    private static boolean loadConf() {
        try {
            java.io.File d = cacheDir();
            if (d == null) {
                return false;
            }
            byte[] raw = readLocal(new java.io.File(d, "conf.txt"));
            if (raw == null) {
                return false;
            }
            String[] a = new String(raw, "UTF-8").trim().split("\\|");
            if (a.length < 20) {
                return false;
            }
            int i = 0;
            hideNetType = "1".equals(a[i++]);
            clockPercent = Integer.parseInt(a[i++]);
            wifiOffsetDp = Integer.parseInt(a[i++]);
            mobileOffsetDp = Integer.parseInt(a[i++]);
            clockYTenth = Integer.parseInt(a[i++]);
            notifXDp = Integer.parseInt(a[i++]);
            iconVer = Integer.parseInt(a[i++]);
            hideWifiArrow = "1".equals(a[i++]);
            hideDataArrow = "1".equals(a[i++]);
            hideWifiStd = "1".equals(a[i++]);
            batteryTextPercent = Integer.parseInt(a[i++]);
            batteryTextYTenth = Integer.parseInt(a[i++]);
            debugOverlay = "1".equals(a[i++]);
            statusBarHeightDp = Integer.parseInt(a[i++]);
            wifiIconWTenth = Integer.parseInt(a[i++]);
            wifiImgXTenth = Integer.parseInt(a[i++]);
            mobileIconWTenth = Integer.parseInt(a[i++]);
            mobileImgXTenth = Integer.parseInt(a[i++]);
            notifGapTenth = Integer.parseInt(a[i++]);
            packOn = "1".equals(a[i++]);
            if (i < a.length) {
                marginTenth = Integer.parseInt(a[i++]);
            }
            if (i < a.length) {
                dataSimOnly = "1".equals(a[i++]);
            }
            if (i < a.length) {
                wifiIconLTenth = Integer.parseInt(a[i++]);
            }
            if (i < a.length) {
                wifiIconRTenth = Integer.parseInt(a[i++]);
            }
            if (i < a.length) {
                mobileIconLTenth = Integer.parseInt(a[i++]);
            }
            if (i < a.length) {
                mobileIconRTenth = Integer.parseInt(a[i++]);
            }
            if (i < a.length) {
                marginRTenth = Integer.parseInt(a[i++]);
            }
            if (i < a.length) {
                wifiOffShiftTenth = Integer.parseInt(a[i++]);
            }
            if (i < a.length) {
                batteryIconLTenth = Integer.parseInt(a[i++]);
            }
            if (i < a.length) {
                wifiNudgeTenth = Integer.parseInt(a[i++]);
            }
            if (i < a.length) {
                mobileNudgeTenth = Integer.parseInt(a[i++]);
            }
            if (i < a.length) {
                mobileLPadTenth = Integer.parseInt(a[i++]);
            }
            log("config from local cache: pack=" + packOn + " ver=" + iconVer
                    + " debug=" + debugOverlay);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static byte[] readLocal(java.io.File f) {
        if (f == null || !f.exists()) {
            return null;
        }
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            byte[] raw = readAll(in);
            return raw.length > 0 ? raw : null;
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }

    private static void writeLocal(java.io.File f, byte[] raw) {
        if (f == null) {
            return;
        }
        try {
            java.io.FileOutputStream o = new java.io.FileOutputStream(f);
            try {
                o.write(raw);
            } finally {
                o.close();
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** 包内单个 XML 的本地副本路径。 */
    private static java.io.File packCacheFile(String name) {
        java.io.File d = cacheDir();
        if (d == null || name == null || name.isEmpty()
                || name.contains("/") || name.contains("..")) {
            return null;
        }
        java.io.File p = new java.io.File(d, "pack");
        if (!p.exists() && !p.mkdirs()) {
            return null;
        }
        return new java.io.File(p, name + ".xml");
    }

    /** 换包了（iconVer 变了）：本地副本全清，强制重新从 provider 拉。 */
    private static void clearPackFiles() {
        java.io.File d = cacheDir();
        if (d == null) {
            return;
        }
        try {
            java.io.File p = new java.io.File(d, "pack");
            java.io.File[] fs = p.listFiles();
            if (fs != null) {
                for (java.io.File f : fs) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    // ---------- 图标包加载 ----------

    private static final java.util.HashMap<String, Drawable> PACK_CACHE = new java.util.HashMap<>();
    private static final java.util.HashSet<String> PACK_MISS = new java.util.HashSet<>();
    /** 正在解析的图标名（layer-list 会引用包内另一张图，防止自引用转圈）。 */
    private static final ThreadLocal<java.util.HashSet<String>> PACK_STACK =
            new ThreadLocal<java.util.HashSet<String>>() {
                @Override
                protected java.util.HashSet<String> initialValue() {
                    return new java.util.HashSet<>();
                }
            };

    /** 按名字从图标包里取图（layer-list 里的 @drawable/xxx 也走这里）。 */
    private static final PackLayer.IconResolver PACK_RESOLVER = new PackLayer.IconResolver() {
        @Override
        public Drawable load(String name) {
            return packDrawable(null, name);
        }
    };

    /** 图标包里的同名 VectorDrawable；缺文件/解析失败都会记负缓存，避免反复打开。 */
    private static Drawable packDrawable(android.content.res.Resources res, String name) {
        Drawable c = PACK_CACHE.get(name);
        if (c != null) {
            return c;
        }
        if (PACK_MISS.contains(name)) {
            return null;
        }
        java.util.HashSet<String> stack = PACK_STACK.get();
        if (stack.contains(name)) {
            return null;   // layer-list 自引用
        }
        stack.add(name);
        java.io.InputStream in = null;
        try {
            Context ctx = sCtx;
            if (ctx == null) {
                return null;
            }
            if (res == null) {
                res = ctx.getResources();
            }
            java.io.File local = packCacheFile(name);
            // 先用本地副本（provider 连不上时也能用），没有再找 App 要
            byte[] raw = readLocal(local);
            if (raw == null) {
                in = ctx.getContentResolver().openInputStream(
                        android.net.Uri.parse("content://" + Config.AUTHORITY + "/pack/" + name));
                if (in == null) {
                    PACK_MISS.add(name);
                    return null;
                }
                raw = readAll(in);
                writeLocal(local, raw);
            }
            // 系统的 Drawable.createFromXml 只认 APK 内编译过的二进制 XML 解析器
            // （XmlBlock.Parser），文本流解析出的 XmlPullParser 会抛 ClassCastException，
            // 所以用自己的 VectorDrawable 渲染器（?attr 引用按白色处理，颜色交给 tint）
            Drawable d = VecDrawable.parse(res, new String(raw, "UTF-8"));
            if (d == null) {
                String text = new String(raw, "UTF-8");
                // 电量进度这类图层不是 <vector>，而是 layer-list > item > clip
                d = PackLayer.parse(res, text, PACK_RESOLVER);
            }
            if (d == null) {
                log("pack xml parse failed: " + name + " [" + VecDrawable.lastError
                        + "] bytes=" + raw.length);
                PACK_MISS.add(name);
                return null;
            }
            PACK_CACHE.put(name, d);
            if (d instanceof VecDrawable) {
                ((VecDrawable) d).name = name;
            }
            if (PKDBG.getAndIncrement() < 30) {
                log("pack hit: " + name + " local=" + (local != null && local.exists())
                        + " size=" + raw.length);
            }
            return d;
        } catch (Throwable t) {
            PACK_MISS.add(name);
            log("packDrawable(" + name + ") failed: " + t);
            return null;
        } finally {
            stack.remove(name);
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }

    /** 透明占位图：尺寸与原图一致，一个像素都不画，但布局位置照旧保留。 */
    private static final class BlankDrawable extends Drawable {
        private final int w;
        private final int h;

        BlankDrawable(int w, int h) {
            this.w = w;
            this.h = h;
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            // 故意留空
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSPARENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return w;
        }

        @Override
        public int getIntrinsicHeight() {
            return h;
        }

        @Override
        public void setTintList(android.content.res.ColorStateList tint) {
        }

        @Override
        public void setTint(int tintColor) {
        }
    }

    // ---------- 6) 电池内电量数字（字号 / 垂直偏移） ----------

    private static final java.util.HashMap<Integer, Boolean> BTEXT_ID_CACHE =
            new java.util.HashMap<>();

    /** 我们上一次给这个 TextView 设的字号，用来分辨「系统又改了一次」。 */
    private static final WeakHashMap<TextView, Float> BTEXT_LAST = new WeakHashMap<>();

    /** 防递归：我们自己调 setTextSize 时不要被当成系统行为。 */
    private static final ThreadLocal<Boolean> IN_APPLY_BTEXT = new ThreadLocal<>();

    private static final List<WeakReference<TextView>> BTEXTS = new ArrayList<>();

    private static int hookBatteryText(ClassLoader cl) {
        int n = 0;
        XC_MethodHook attach = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!(param.thisObject instanceof TextView)) {
                    return;
                }
                TextView tv = (TextView) param.thisObject;
                if (!isBatteryText(tv)) {
                    return;
                }
                rememberBatteryText(tv);
                if (!BASE_SIZE.containsKey(tv)) {
                    BASE_SIZE.put(tv, tv.getTextSize());
                }
                applyBatteryText(tv);
            }
        };
        try {
            XposedBridge.hookAllMethods(TextView.class, "onAttachedToWindow", attach);
            n++;
        } catch (Throwable t) {
            log("hook batteryText attach failed: " + t);
        }

        XC_MethodHook size = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(IN_APPLY_BTEXT.get())) {
                    return;
                }
                if (!(param.thisObject instanceof TextView)) {
                    return;
                }
                TextView tv = (TextView) param.thisObject;
                if (!isBatteryText(tv)) {
                    return;
                }
                float cur = tv.getTextSize();
                Float last = BTEXT_LAST.get(tv);
                // 与上次我们设的值不同 → 系统重新设过，这个新值就是基准
                if (last == null || Math.abs(cur - last) > 0.1f) {
                    BASE_SIZE.put(tv, cur);
                }
                applyBatteryText(tv);
            }
        };
        try {
            XposedBridge.hookAllMethods(TextView.class, "setTextSize", size);
            n++;
        } catch (Throwable t) {
            log("hook batteryText setTextSize failed: " + t);
        }
        return n;
    }

    /** 电量数字的控件 id 名（ColorOS 是 battery_percentage_view / battery_text）。 */
    private static boolean isBatteryText(View v) {
        int id = v.getId();
        if (id == View.NO_ID) {
            return false;
        }
        Boolean cached = BTEXT_ID_CACHE.get(id);
        if (cached != null) {
            return cached;
        }
        boolean ok = false;
        try {
            String n = v.getResources().getResourceEntryName(id);
            if (n != null) {
                n = n.toLowerCase();
                ok = n.contains("battery_percentage") || n.contains("battery_percent")
                        || n.equals("battery_text");
            }
        } catch (Throwable ignored) {
            // ignore
        }
        BTEXT_ID_CACHE.put(id, ok);
        return ok;
    }

    private static void applyBatteryText(TextView tv) {
        if (tv == null) {
            return;
        }
        boolean noSize = batteryTextPercent == Config.BTEXT_DEF;
        boolean noShift = batteryTextYTenth == Config.BTEXT_Y_DEF;
        Float base = BASE_SIZE.get(tv);
        if (base == null || base <= 0f) {
            base = tv.getTextSize();
            BASE_SIZE.put(tv, base);
        }
        float target = noSize ? base : base * batteryTextPercent / 100f;
        if (Math.abs(tv.getTextSize() - target) > 0.1f) {
            BTEXT_LAST.put(tv, target);
            IN_APPLY_BTEXT.set(Boolean.TRUE);
            try {
                tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, target);
            } catch (Throwable t) {
                log("setTextSize(battery) failed: " + t);
            } finally {
                IN_APPLY_BTEXT.remove();
            }
        }
        float ty = noShift ? 0f
                : batteryTextYTenth / 10f * tv.getResources().getDisplayMetrics().density;
        if (Math.abs(tv.getTranslationY() - ty) > 0.2f) {
            tv.setTranslationY(ty);
        }
    }

    private static void rememberBatteryText(TextView tv) {
        synchronized (BTEXTS) {
            for (WeakReference<TextView> r : BTEXTS) {
                if (r.get() == tv) {
                    return;
                }
            }
            BTEXTS.add(new WeakReference<>(tv));
        }
        try {
            tv.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int l, int t, int r, int b,
                                           int ol, int ot, int or, int ob) {
                    if (v instanceof TextView) {
                        applyBatteryText((TextView) v);
                    }
                }
            });
        } catch (Throwable ignored) {
            // ignore
        }
    }

    private static List<TextView> aliveBatteryTexts() {
        List<TextView> out = new ArrayList<>();
        synchronized (BTEXTS) {
            for (int i = BTEXTS.size() - 1; i >= 0; i--) {
                TextView tv = BTEXTS.get(i).get();
                if (tv == null) {
                    BTEXTS.remove(i);
                } else {
                    out.add(tv);
                }
            }
        }
        return out;
    }

    // ---------- 7) 布局：状态栏行高 / Wi-Fi 固定间距 / 调试画板 ----------

    private static final java.util.HashMap<Integer, Integer> DIMEN_CACHE = new java.util.HashMap<>();

    /** 1 = 状态栏行高，2 = Wi-Fi 与信号的间距，3 = 电池数字字号，4 = 电池数字下边距。 */
    private static int dimenKind(android.content.res.Resources res, int id) {
        Integer cached = DIMEN_CACHE.get(id);
        if (cached != null) {
            return cached;
        }
        int kind = 0;
        try {
            String n = res.getResourceEntryName(id);
            if (n != null) {
                n = n.toLowerCase();
                if ("status_bar_height".equals(n)) {
                    kind = 1;
                } else if ("status_bar_wifi_signal_spacer_width".equals(n)) {
                    kind = 2;
                } else if ("battery_percent_in_text_size".equals(n)
                        || "battery_percent_text_size".equals(n)) {
                    kind = 3;
                } else if ("op_status_bar_battery_text_margin_bottom".equals(n)) {
                    kind = 4;
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        DIMEN_CACHE.put(id, kind);
        return kind;
    }

    private static final String SB_WINDOW_CLASS =
            "com.android.systemui.statusbar.phone.StatusBarWindowView";

    private static WeakReference<ViewGroup> sSbRoot = new WeakReference<>(null);

    private static int hookLayoutTweaks(ClassLoader cl) {
        int n = 0;
        // --- 尺寸资源：状态栏高度 / Wi-Fi 间距 ---
        XC_MethodHook dimen = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (!(param.thisObject instanceof android.content.res.Resources)) {
                        return;
                    }
                    if (param.args == null || param.args.length < 1
                            || !(param.args[0] instanceof Integer)) {
                        return;
                    }
                    Object r = param.getResult();
                    if (!(r instanceof Integer) && !(r instanceof Float)) {
                        return;
                    }
                    android.content.res.Resources res =
                            (android.content.res.Resources) param.thisObject;
                    int kind = dimenKind(res, (Integer) param.args[0]);
                    if (kind == 0) {
                        return;
                    }
                    float base = r instanceof Integer ? (Integer) r : (Float) r;
                    float v = base;
                    float density = res.getDisplayMetrics().density;
                    if (kind == 1 && statusBarHeightDp != Config.SBH_DEF) {
                        v = base + statusBarHeightDp * density;
                    } else if (kind == 2) {
                        // WiFi 与移动数据之间的间距永远为 0（用户固定，不可调）
                        v = 0;
                    } else if (kind == 3 && batteryTextPercent != Config.BTEXT_DEF) {
                        // 电池数字是 Compose 画的，只能从字号资源下手
                        v = base * batteryTextPercent / 100f;
                    } else if (kind == 4 && batteryTextYTenth != Config.BTEXT_Y_DEF) {
                        // margin_bottom 越大内容越靠上，所以要往下移就减
                        v = base - batteryTextYTenth / 10f * density;
                    }
                    if (v == base) {
                        return;
                    }
                    param.setResult(r instanceof Integer ? (Object) (int) v : (Object) v);
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        };
        for (String m : new String[]{"getDimension", "getDimensionPixelSize",
                "getDimensionPixelOffset"}) {
            try {
                XposedBridge.hookAllMethods(android.content.res.Resources.class, m, dimen);
                n++;
            } catch (Throwable t) {
                log("hook Resources#" + m + " failed: " + t);
            }
        }

        // --- 调试画板：直接接在状态栏窗口的绘制里（overlay 在这个宿主上不生效） ---
        Class<?> sbw = findClass(SB_WINDOW_CLASS, cl);
        if (sbw == null) {
            sbw = findClass("com.android.systemui.statusbar.window.StatusBarWindowView", cl);
        }
        if (sbw == null) {
            sbw = findClass("com.android.systemui.shade.StatusBarWindowView", cl);
        }
        if (sbw == null) {
            sbw = findClass("com.android.systemui.shade.NotificationShadeWindowView", cl);
        }
        if (sbw != null) {
            log("draw board host class = " + sbw.getName()
                    + " hasDispatchDraw=" + hasMethod(sbw, "dispatchDraw")
                    + " hasOnDraw=" + hasMethod(sbw, "onDraw"));
            try {
                XposedBridge.hookAllMethods(sbw, "onAttachedToWindow", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.thisObject instanceof ViewGroup) {
                            ViewGroup g = (ViewGroup) param.thisObject;
                            sSbRoot = new WeakReference<>(g);
                            applyDebugOverlay();
                            applyStatusBarHeight();
                        }
                    }
                });
                n++;
            } catch (Throwable t) {
                log("hook onAttachedToWindow failed: " + t);
            }
            // 优先 dispatchDraw（重写了才有）；没有就退回 View#draw 只在根视图上画
            boolean hooked = false;
            if (hasMethod(sbw, "dispatchDraw")) {
                try {
                    XposedBridge.hookAllMethods(sbw, "dispatchDraw", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            onDrawBoard(param);
                        }
                    });
                    hooked = true;
                    n++;
                } catch (Throwable t) {
                    log("hook dispatchDraw failed: " + t);
                }
            }
            if (!hooked) {
                log("draw board: 该窗口没有 dispatchDraw，改用覆盖层 View");
            }
        } else {
            log("StatusBarWindowView not found (调试画板不可用)");
        }
        return n;
    }

    /** 这个方法是不是在 clazz 自己身上声明的（hookAllMethods 只 hook 声明的方法）。 */
    private static boolean hasMethod(Class<?> clazz, String name) {
        try {
            for (java.lang.reflect.Method m : clazz.getDeclaredMethods()) {
                if (m.getName().equals(name)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return false;
    }

    private static final java.util.concurrent.atomic.AtomicInteger DBG_HIT =
            new java.util.concurrent.atomic.AtomicInteger();

    private static void onDrawBoard(XC_MethodHook.MethodHookParam param) {
        if (!debugOverlay) {
            return;
        }
        try {
            if (!(param.thisObject instanceof ViewGroup)
                    || param.args == null || param.args.length < 1
                    || !(param.args[0] instanceof android.graphics.Canvas)) {
                return;
            }
            ViewGroup g = (ViewGroup) param.thisObject;
            if (sSbRoot.get() != g) {
                sSbRoot = new WeakReference<>(g);
            }
            if (DBG_HIT.getAndIncrement() == 0) {
                log("draw board first frame: " + g.getClass().getSimpleName()
                        + " " + g.getWidth() + "x" + g.getHeight());
            }
            drawDebugLayer((android.graphics.Canvas) param.args[0], g);
        } catch (Throwable ignored) {
            // 画板出错绝不能影响状态栏绘制
        }
    }

    private static int sSbhBase = -1;

    /**
     * 状态栏行高：改的是窗口根 View 的 LayoutParams，不是 dimen。
     * dimen 只是「最初算高度时读的那个数」，窗口尺寸由 WindowManager 下发的
     * LayoutParams 决定，所以只有直接改 LayoutParams + requestLayout 才会真的变高变矮。
     */
    private static void applyStatusBarHeight() {
        ViewGroup root = sSbRoot.get();
        if (root == null) {
            return;
        }
        try {
            ViewGroup.LayoutParams lp = root.getLayoutParams();
            if (lp == null) {
                return;
            }
            if (sSbhBase <= 0) {
                sSbhBase = root.getHeight() > 0 ? root.getHeight() : lp.height;
            }
            if (sSbhBase <= 0) {
                return;
            }
            int target = sSbhBase + Math.round(statusBarHeightDp
                    * root.getResources().getDisplayMetrics().density);
            if (target <= 0) {
                target = sSbhBase;
            }
            if (lp.height != target) {
                lp.height = target;
                root.setLayoutParams(lp);
                root.requestLayout();
                log("statusbar height " + sSbhBase + " -> " + target
                        + " (dp=" + statusBarHeightDp + ")");
            }
        } catch (Throwable t) {
            log("applyStatusBarHeight failed: " + t);
        }
    }

    private static View sBoardView;    /**
     * 调试画板：往状态栏窗口里塞一个铺满的透明子 View，在它的 onDraw 里画。
     * 试过 ViewOverlay 和 hook dispatchDraw/View#draw，在这个窗口上都不生效
     * （overlay 不触发重绘、窗口类又没重写这几个方法），加子 View 最稳。
     */
    private static void applyDebugOverlay() {
        ViewGroup root = sSbRoot.get();
        if (root == null) {
            root = findStatusBarRoot();
            if (root != null) {
                sSbRoot = new WeakReference<>(root);
            }
        }
        if (!debugOverlay) {
            detachBoardView();
            if (root != null) {
                root.invalidate();
            }
            return;
        }
        if (root == null) {
            log("draw board: 找不到状态栏根 View");
            return;
        }
        attachBoardView(root);
    }

    private static void attachBoardView(final ViewGroup root) {
        try {
            View cur = sBoardView;
            if (cur != null && cur.getParent() == root) {
                cur.invalidate();
                return;
            }
            detachBoardView();
            View v = new View(root.getContext()) {
                @Override
                protected void onDraw(android.graphics.Canvas canvas) {
                    if (!debugOverlay) {
                        return;
                    }
                    try {
                        drawDebugLayer(canvas, root);
                    } catch (Throwable ignored) {
                        // 画板出错绝不能影响状态栏绘制
                    }
                    invalidate();
                }
            };
            v.setWillNotDraw(false);
            v.setClickable(false);
            v.setFocusable(false);
            root.addView(v, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            sBoardView = v;
            log("draw board view attached -> " + root.getClass().getSimpleName()
                    + " " + root.getWidth() + "x" + root.getHeight());
        } catch (Throwable t) {
            log("attachBoardView failed: " + t);
        }
    }

    private static void detachBoardView() {
        View v = sBoardView;
        sBoardView = null;
        if (v != null && v.getParent() instanceof ViewGroup) {
            try {
                ((ViewGroup) v.getParent()).removeView(v);
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }

    /** 从 WindowManagerGlobal 里捞出状态栏那个窗口根 View。 */
    private static ViewGroup findStatusBarRoot() {
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Object inst = wmg.getMethod("getInstance").invoke(null);
            java.lang.reflect.Field f = wmg.getDeclaredField("mViews");
            f.setAccessible(true);
            Object raw = f.get(inst);
            if (!(raw instanceof List)) {
                return null;
            }
            List<?> views = new ArrayList<>((List<?>) raw);
            for (Object o : views) {
                if (o instanceof ViewGroup) {
                    String cn = o.getClass().getName();
                    if (cn.endsWith("StatusBarWindowView")
                            || cn.endsWith("NotificationShadeWindowView")) {
                        return (ViewGroup) o;
                    }
                }
            }
        } catch (Throwable t) {
            log("findStatusBarRoot failed: " + t);
        }
        return null;
    }

    /** 只用 RGB，透明度在绘制时单独拼，便于整体调淡。 */
    private static final int[] DBG_COLORS = {
            0xFF3B30, 0x34C759, 0x007AFF, 0xFFCC00,
            0xAF52DE, 0x32ADE2, 0xFF9500, 0xFF2D55
    };
    /** 填充透明度：嵌套层会叠加，必须压得很低才不会糊住图标。 */
    private static final int DBG_FILL_ALPHA = 0x0E;   // ≈5.5%
    private static final int DBG_STROKE_ALPHA = 0x4A; // ≈29%

    private static final android.graphics.Paint DBG_FILL =
            new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private static final android.graphics.Paint DBG_STROKE =
            new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private static final android.graphics.Paint DBG_TEXT =
            new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private static boolean dbgReady;

    /**
     * 给状态栏里每个可见子控件刷半透明底色 + 描边 + 同级序号。
     * 颜色按同级序号轮转，嵌套层各画各的，一眼能看出层级、顺序和间距。
     */
    private static void drawDebugLayer(android.graphics.Canvas c, ViewGroup host) {
        if (!dbgReady) {
            float d = host.getResources().getDisplayMetrics().density;
            DBG_FILL.setStyle(android.graphics.Paint.Style.FILL);
            DBG_STROKE.setStyle(android.graphics.Paint.Style.STROKE);
            DBG_STROKE.setStrokeWidth(1f * d);
            DBG_TEXT.setTextSize(8f * d);
            DBG_TEXT.setColor(0xB3FFFFFF);
            DBG_TEXT.setShadowLayer(3f, 0f, 0f, 0xFF000000);
            dbgReady = true;
        }
        paintGroup(c, host, 0, 0, 0);
    }

    /** 图标行每个子项（NFC/蓝牙/网速/信号/WiFi/电池）的稳定独立配色。 */
    private static final java.util.Map<View, Integer> DBG_ICON_COLOR =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<View, Integer>());
    private static final java.util.concurrent.atomic.AtomicInteger DBG_ICON_SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    private static void paintGroup(android.graphics.Canvas c, ViewGroup g,
                                   int ox, int oy, int depth) {
        if (depth > 26) {
            return;
        }
        int gl = ox, gt = oy;
        int gr = ox + g.getWidth(), gb = oy + g.getHeight();
        int n = g.getChildCount();
        for (int i = 0; i < n; i++) {
            View ch = g.getChildAt(i);
            if (ch == null || ch == sBoardView || ch.getVisibility() != View.VISIBLE) {
                continue;
            }
            // 这套 UI 的图标全部 left=0、靠 translationX 排列，必须把 tx 算进坐标，
            // 否则所有图标的色块都会叠在容器左端
            int l = ox + ch.getLeft() + Math.round(ch.getTranslationX());
            int t = oy + ch.getTop();
            int r = l + ch.getWidth();
            int b = oy + ch.getBottom();
            if (r - l <= 0 || b - t <= 0) {
                continue;
            }
            String cn = ch.getClass().getName();
            // 图标行子项固定用各自独立的颜色，且永远填色（不参与重合跳过规则）
            boolean isIconChild = cn.endsWith("StatIconView")
                    || cn.endsWith("NetworkSpeedView")
                    || cn.endsWith("OplusSingleBindableStatusBarComposeIconView")
                    || cn.endsWith("OplusModernStatusBarWifiView")
                    || cn.endsWith("StatBatteryMeterView");
            int rgb;
            if (isIconChild) {
                Integer asg = DBG_ICON_COLOR.get(ch);
                if (asg == null) {
                    asg = (int) (DBG_ICON_SEQ.getAndIncrement() % DBG_COLORS.length);
                    DBG_ICON_COLOR.put(ch, asg);
                }
                rgb = DBG_COLORS[asg];
            } else {
                rgb = DBG_COLORS[i % DBG_COLORS.length];
            }
            // 与父容器完全重合的子容器不再填色，否则同区域反复叠色会把图标糊掉
            boolean sameAsParent = (l == gl && t == gt && r == gr && b == gb);
            if (!sameAsParent || isIconChild) {
                // 图标行子项透明度更高：它们各自只画一层，不存在多层叠色问题
                int fa = isIconChild ? 0x30 : DBG_FILL_ALPHA;
                DBG_FILL.setColor((fa << 24) | rgb);
                c.drawRect(l, t, r, b, DBG_FILL);
            }
            int sa = isIconChild ? 0x90 : DBG_STROKE_ALPHA;
            DBG_STROKE.setColor((sa << 24) | rgb);
            c.drawRect(l, t, r, b, DBG_STROKE);
            c.drawText(String.valueOf(i), l + 2, t + DBG_TEXT.getTextSize(), DBG_TEXT);
            if (ch instanceof ViewGroup) {
                paintGroup(c, (ViewGroup) ch, l, t, depth + 1);
            }
        }
    }


    // ---------- 配置 ----------

    private static void registerReceiver(Context ctx) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) {
                    return;
                }
                if ("com.tonyma.hide5g.DUMP".equals(intent.getAction())) {
                    dumpWindows();
                    return;
                }
                if ("com.tonyma.hide5g.SAMPLE".equals(intent.getAction())) {
                    startSampleFor(intent.getIntExtra("ms", 3000));
                    return;
                }
                if ("com.tonyma.hide5g.TEST".equals(intent.getAction())) {
                    int i = 0;
                    int[] loc = new int[2];
                    for (TextView tv : aliveClocks()) {
                        tv.getLocationOnScreen(loc);
                        if (i++ < 4) {
                            log("TEST clock " + tv.getClass().getSimpleName()
                                    + " size=" + tv.getWidth() + "x" + tv.getHeight()
                                    + " ts=" + tv.getTextSize()
                                    + " @" + loc[0] + "," + loc[1]
                                    + " pivot=" + tv.getPivotX() + "," + tv.getPivotY()
                                    + " scale=" + tv.getScaleX() + "," + tv.getScaleY()
                                    + " ty=" + tv.getTranslationY()
                                    + " gravity=" + tv.getGravity()
                                    + " pct=" + clockPercent);
                        }
                        tv.setScaleX(1.8f);
                        tv.setScaleY(1.8f);
                    }
                    log("TEST: clocks=" + aliveClocks().size());
                    return;
                }
                hideNetType = intent.getBooleanExtra(Config.KEY_HIDE, hideNetType);
                clockPercent = intent.getIntExtra(Config.KEY_CLOCK, clockPercent);
                wifiOffsetDp = intent.getIntExtra(Config.KEY_WIFI, wifiOffsetDp);
                mobileOffsetDp = intent.getIntExtra(Config.KEY_MOBILE, mobileOffsetDp);
                clockYTenth = intent.getIntExtra(Config.KEY_CLOCK_Y, clockYTenth);
                notifXDp = intent.getIntExtra(Config.KEY_NOTIF_X, notifXDp);
                int oldVer = iconVer;
                iconVer = intent.getIntExtra(Config.KEY_ICON_VER, iconVer);
                hideWifiArrow = intent.getBooleanExtra(Config.KEY_HIDE_WIFI_ARROW, hideWifiArrow);
                hideDataArrow = intent.getBooleanExtra(Config.KEY_HIDE_DATA_ARROW, hideDataArrow);
                hideWifiStd = intent.getBooleanExtra(Config.KEY_HIDE_WIFI_STD, hideWifiStd);
                batteryTextPercent =
                        intent.getIntExtra(Config.KEY_BATTERY_TEXT, batteryTextPercent);
                batteryTextYTenth =
                        intent.getIntExtra(Config.KEY_BATTERY_TEXT_Y, batteryTextYTenth);
                debugOverlay = intent.getBooleanExtra(Config.KEY_DEBUG_OVERLAY, debugOverlay);
                statusBarHeightDp =
                        intent.getIntExtra(Config.KEY_STATUS_HEIGHT, statusBarHeightDp);
                wifiIconWTenth = intent.getIntExtra(Config.KEY_WIFI_ICON_W, wifiIconWTenth);
                wifiImgXTenth = intent.getIntExtra(Config.KEY_WIFI_IMG_X, wifiImgXTenth);
                mobileIconWTenth = intent.getIntExtra(Config.KEY_MOBILE_ICON_W, mobileIconWTenth);
                mobileImgXTenth = intent.getIntExtra(Config.KEY_MOBILE_IMG_X, mobileImgXTenth);
                wifiIconLTenth = intent.getIntExtra(Config.KEY_WIFI_ICON_WL, wifiIconLTenth);
                wifiIconRTenth = intent.getIntExtra(Config.KEY_WIFI_ICON_WR, wifiIconRTenth);
                mobileIconLTenth = intent.getIntExtra(Config.KEY_MOBILE_ICON_WL, mobileIconLTenth);
                mobileIconRTenth = intent.getIntExtra(Config.KEY_MOBILE_ICON_WR, mobileIconRTenth);
                notifGapTenth = intent.getIntExtra(Config.KEY_NOTIF_GAP, notifGapTenth);
                wifiOffShiftTenth =
                        intent.getIntExtra(Config.KEY_WIFI_OFF_SHIFT, wifiOffShiftTenth);
                batteryIconLTenth =
                        intent.getIntExtra(Config.KEY_BATTERY_ICON_WL, batteryIconLTenth);
                wifiNudgeTenth =
                        intent.getIntExtra(Config.KEY_WIFI_NUDGE, wifiNudgeTenth);
                mobileNudgeTenth =
                        intent.getIntExtra(Config.KEY_MOBILE_NUDGE, mobileNudgeTenth);
                mobileLPadTenth =
                        intent.getIntExtra(Config.KEY_MOBILE_LPAD, mobileLPadTenth);
                marginTenth = intent.getIntExtra(Config.KEY_MARGIN, marginTenth);
                marginRTenth = intent.getIntExtra(Config.KEY_MARGIN_R, marginRTenth);
                dataSimOnly = intent.getBooleanExtra(Config.KEY_DATA_SIM_ONLY, dataSimOnly);
                int oldSimStyle = simStyle;
                simStyle = intent.getIntExtra(Config.KEY_SIM_STYLE, simStyle);
                if (simStyle != oldSimStyle || dataSimOnly) {
                    applySimStyle();
                }
                packOn = intent.getBooleanExtra(Config.KEY_PACK_REPLACE, packOn);
                if (iconVer != oldVer) {
                    // 换包了：本地 XML 副本是旧的，清掉重新拉
                    clearPackFiles();
                }
                // 每次收到推送都把配置落一份，下次 SystemUI 冷启动时 provider
                // 可能连不上（App 被冻结），用这份副本顶上
                saveConf();
                log("config: hide=" + hideNetType + " clock=" + clockPercent
                        + " wifi=" + wifiOffsetDp + " mobile=" + mobileOffsetDp
                        + " clockY=" + clockYTenth + " notifX=" + notifXDp
                        + " ver=" + iconVer
                        + " | arrows=" + (hideWifiArrow ? "wifi" : "-")
                        + "/" + (hideDataArrow ? "data" : "-")
                        + " std=" + hideWifiStd
                        + " pack=" + packOn + " ver=" + iconVer
                        + " btext=" + batteryTextPercent + "%/+" + batteryTextYTenth
                        + " debug=" + debugOverlay + " sbh=" + statusBarHeightDp
                        
                        + " wiw=" + wifiIconWTenth + " wix=" + wifiImgXTenth
                        + " miw=" + mobileIconWTenth + " mix=" + mobileImgXTenth
                        + " ngap=" + notifGapTenth + " woff=" + wifiOffShiftTenth);
                applyAll();
            }
        };
        IntentFilter filter = new IntentFilter(Config.ACTION_CHANGED);
        filter.addAction("com.tonyma.hide5g.DUMP");
        filter.addAction("com.tonyma.hide5g.SAMPLE");
        filter.addAction("com.tonyma.hide5g.TEST");
        try {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            try {
                ctx.registerReceiver(receiver, filter);
            } catch (Throwable t2) {
                log("registerReceiver failed: " + t2);
            }
        }
    }

    private static void loadConfigAsync() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    boolean got = false;
                    Cursor c = sCtx.getContentResolver()
                            .query(PrefsProvider.URI, null, null, null, null);
                    if (c != null) {
                        if (c.moveToFirst()) {
                            got = true;
                            hideNetType = c.getInt(0) == 1;
                            clockPercent = c.getInt(1);
                            wifiOffsetDp = c.getInt(2);
                            mobileOffsetDp = c.getInt(3);
                            clockYTenth = c.getInt(4);
                            notifXDp = c.getInt(5);
                            iconVer = c.getInt(8);
                            if (c.getColumnCount() > 20) {
                                hideWifiArrow = c.getInt(11) == 1;
                                hideDataArrow = c.getInt(12) == 1;
                                hideWifiStd = c.getInt(13) == 1;
                                batteryTextPercent = c.getInt(16);
                                batteryTextYTenth = c.getInt(17);
                                debugOverlay = c.getInt(18) == 1;
                                statusBarHeightDp = c.getInt(19);
                            }
                            if (c.getColumnCount() > 25) {
                                wifiIconWTenth = c.getInt(21);
                                wifiImgXTenth = c.getInt(22);
                                mobileIconWTenth = c.getInt(23);
                                mobileImgXTenth = c.getInt(24);
                                notifGapTenth = c.getInt(25);
                            }
                            if (c.getColumnCount() > 26) {
                                packOn = c.getInt(26) == 1;
                            }
                            if (c.getColumnCount() > 27) {
                                marginTenth = c.getInt(27);
                            }
                            if (c.getColumnCount() > 28) {
                                dataSimOnly = c.getInt(28) == 1;
                            }
                            if (c.getColumnCount() > 29) {
                                wifiIconLTenth = c.getInt(29);
                            }
                            if (c.getColumnCount() > 30) {
                                wifiIconRTenth = c.getInt(30);
                            }
                            if (c.getColumnCount() > 31) {
                                mobileIconLTenth = c.getInt(31);
                            }
                            if (c.getColumnCount() > 32) {
                                mobileIconRTenth = c.getInt(32);
                            }
                            if (c.getColumnCount() > 33) {
                                marginRTenth = c.getInt(33);
                            }
                            if (c.getColumnCount() > 34) {
                                wifiOffShiftTenth = c.getInt(34);
                            }
                            if (c.getColumnCount() > 35) {
                                batteryIconLTenth = c.getInt(35);
                            }
                            if (c.getColumnCount() > 36) {
                                wifiNudgeTenth = c.getInt(36);
                            }
                            if (c.getColumnCount() > 37) {
                                mobileNudgeTenth = c.getInt(37);
                            }
                            if (c.getColumnCount() > 38) {
                                mobileLPadTenth = c.getInt(38);
                            }
                        }
                        c.close();
                    }
                    if (!got) {
                        // provider 连不上（App 被冻结/未安装）：退回上次存的副本
                        loadConf();
                    } else {
                        // 注意：不要在这里清本地图标副本。
                        // 冷启动时内存里的 iconVer 还是初值 0，跟真实版本永远不等，
                        // 一清就会把「provider 不可用时唯一的兜底」删掉，
                        // 结果就是 App 冻结时图标全失效。
                        // 换包由广播路径（内存里版本是准的）负责清。
                        saveConf();
                    }
                } catch (Throwable t) {
                    log("loadConfig failed, try local cache: " + t);
                    loadConf();
                }
                main().post(new Runnable() {
                    @Override
                    public void run() {
                        applyAll();
                    }
                });
            }
        }, "hide5g-config").start();
    }

    private static void applyAll() {
        // 只有"图标本身会变"的开关变了才清缓存重建；拖偏移/调字号时重建会
        // 让所有图标重新走一遍 setImageDrawable，颜色也跟着掉（白底白图）。
        String sig = iconSignature();
        boolean iconChanged = !sig.equals(lastIconSig);
        lastIconSig = sig;
        if (iconChanged) {
            synchronized (MainHook.class) {
                FIT_CACHE.clear();
                PACK_CACHE.clear();
                PACK_MISS.clear();
            }
        }
        // 状态栏根 View 不一定走 onAttachedToWindow 被我们捕获，这里补一次
        if (sSbRoot.get() == null) {
            ViewGroup g = findStatusBarRoot();
            if (g != null) {
                sSbRoot = new WeakReference<>(g);
            }
        }
        applySimStyle();
        // 配置变了（字号 / 间距 / 收紧量）→ 稳态图标偏移必须重算
        NOTIF_GAP_TX.clear();
        List<TextView> clocks = aliveClocks();
        List<ViewGroup> groups = aliveContainers();
        List<View> notifs = aliveNotifs();
        log("applyAll clocks=" + clocks.size() + " containers=" + groups.size()
                + " notif=" + notifs.size());
        startFlipSample();
        for (TextView tv : clocks) {
            applyClock(tv);
        }
        for (ViewGroup g : groups) {
            // 偏移只在 onLayout 之后叠加，这里只负责让布局重跑一次，新值就会跟着生效
            g.requestLayout();
        }
        // 系统不一定再调 setTranslationX，滑滑块后主动重推一遍，立刻见效
        reapplyOffsets();
        for (View v : notifs) {
            // 配置刚变：强制按新值重算，别被下拉动画的缓存吃掉
            applyNotifX(v, true);
        }
        applyNotifAlphaAll();
        for (TextView tv : aliveBatteryTexts()) {
            applyBatteryText(tv);
        }
        applyDebugOverlay();
        applyStatusBarHeight();
        if (iconChanged) {
            refreshIcons();
        }
    }

    /** 影响图标外观的开关快照：只有它变了才值得重建全部图标。 */
    private static String lastIconSig = "";

    private static String iconSignature() {
        return packOn + "|" + iconVer + "|" + hideWifiArrow + "|" + hideDataArrow
                + "|" + hideWifiStd;
    }

    // ---------- 弱引用登记 ----------

    private static void rememberClock(TextView tv) {
        synchronized (CLOCKS) {
            for (WeakReference<TextView> r : CLOCKS) {
                if (r.get() == tv) {
                    return;
                }
            }
            CLOCKS.add(new WeakReference<>(tv));
        }
        // 每次 layout 都重算一次 pivot：高度可能变（折叠屏/横竖屏/时钟换行），
        // 而且首次 layout 时 height 还是 0，只有这里能拿到真实高度。
        try {
            tv.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int l, int t, int r, int b,
                                           int ol, int ot, int or, int ob) {
                    if (v instanceof TextView) {
                        applyClock((TextView) v);
                    }
                }
            });
        } catch (Throwable t) {
            log("addOnLayoutChangeListener failed: " + t);
        }
    }

    private static void rememberContainer(ViewGroup g) {
        synchronized (CONTAINERS) {
            for (WeakReference<ViewGroup> r : CONTAINERS) {
                if (r.get() == g) {
                    return;
                }
            }
            CONTAINERS.add(new WeakReference<>(g));
        }
    }

    /** 配置变化后把已经显示出来的系统图标换成/换回自定义图形。 */
    private static void refreshIcons() {
        List<ImageView> views = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        synchronized (LAST_ICONS) {
            for (java.util.Map.Entry<ImageView, Integer> e : LAST_ICONS.entrySet()) {
                views.add(e.getKey());
                ids.add(e.getValue());
            }
        }
        int n = 0;
        for (int i = 0; i < views.size(); i++) {
            ImageView iv = views.get(i);
            if (iv == null) {
                continue;
            }
            int id = ids.get(i);
            Drawable d = customFor(iv.getResources(), id, iv.getDrawable());
            if (d != null) {
                if (iv.getDrawable() != d) {
                    inheritPaint(iv, d);
                    iv.setImageDrawable(d);
                    n++;
                }
            } else if (iv.getDrawable() instanceof BlankDrawable) {
                // 开关被关掉：把系统原图标放回去（会重新走一遍 hook，此时 customFor 返回 null）
                iv.setImageResource(id);
                n++;
            }
        }
        if (n > 0) {
            log("refreshIcons: replaced " + n + " views");
        }
    }

    private static List<TextView> aliveClocks() {
        List<TextView> out = new ArrayList<>();
        synchronized (CLOCKS) {
            for (int i = CLOCKS.size() - 1; i >= 0; i--) {
                TextView tv = CLOCKS.get(i).get();
                if (tv == null) {
                    CLOCKS.remove(i);
                } else {
                    out.add(tv);
                }
            }
        }
        return out;
    }

    private static List<ViewGroup> aliveContainers() {
        List<ViewGroup> out = new ArrayList<>();
        synchronized (CONTAINERS) {
            for (int i = CONTAINERS.size() - 1; i >= 0; i--) {
                ViewGroup g = CONTAINERS.get(i).get();
                if (g == null) {
                    CONTAINERS.remove(i);
                } else {
                    out.add(g);
                }
            }
        }
        return out;
    }

    private static final List<WeakReference<View>> NOTIFS = new ArrayList<>();

    private static void rememberNotif(View v) {
        synchronized (NOTIFS) {
            for (WeakReference<View> r : NOTIFS) {
                if (r.get() == v) {
                    return;
                }
            }
            NOTIFS.add(new WeakReference<>(v));
        }
    }

    private static List<View> aliveNotifs() {
        List<View> out = new ArrayList<>();
        synchronized (NOTIFS) {
            for (int i = NOTIFS.size() - 1; i >= 0; i--) {
                View v = NOTIFS.get(i).get();
                if (v == null) {
                    NOTIFS.remove(i);
                } else {
                    out.add(v);
                }
            }
        }
        return out;
    }

    // ---------- 调试 ----------

    /** 把状态栏窗口的 View 树打出来（类名 + 位置 + 平移），用来定位图标到底挂在哪个 View 上。 */
    private static void dumpTreeFromRoot() {
        try {
            ViewGroup root = sSbRoot.get();
            if (root == null) {
                root = findStatusBarRoot();
                if (root != null) {
                    sSbRoot = new WeakReference<>(root);
                }
            }
            if (root == null) {
                log("T no root");
                return;
            }
            int[] budget = {90};
            dumpTree(root, 0, budget);
        } catch (Throwable t) {
            log("dumpTree failed: " + t);
        }
    }

    private static void dumpTree(View v, int depth, int[] budget) {
        if (v == null || budget[0] <= 0 || depth > 14) {
            return;
        }
        budget[0]--;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            sb.append("..");
        }
        sb.append(v.getClass().getSimpleName())
                .append(" [").append(v.getLeft()).append(',').append(v.getTop())
                .append(',').append(v.getRight()).append(',').append(v.getBottom()).append(']')
                .append(" w=").append(v.getWidth()).append('x').append(v.getHeight())
                .append(" pad=").append(v.getPaddingLeft()).append('/')
                .append(v.getPaddingTop()).append('/')
                .append(v.getPaddingRight()).append('/')
                .append(v.getPaddingBottom())
                .append(" tx=").append(Math.round(v.getTranslationX()))
                .append(" vis=").append(v.getVisibility());
        if (v instanceof android.widget.ImageView) {
            android.graphics.drawable.Drawable dw =
                    ((android.widget.ImageView) v).getDrawable();
            if (dw != null) {
                sb.append(" dw=").append(dw.getClass().getSimpleName())
                        .append(' ').append(dw.getIntrinsicWidth())
                        .append('x').append(dw.getIntrinsicHeight());
            }
        }
        log("T " + sb);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            int n = g.getChildCount();
            for (int i = 0; i < n; i++) {
                dumpTree(g.getChildAt(i), depth + 1, budget);
            }
        }
    }

    private static void dumpWindows() {
        dumpTreeFromRoot();
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Object inst = wmg.getMethod("getInstance").invoke(null);
            java.lang.reflect.Field f = wmg.getDeclaredField("mViews");
            f.setAccessible(true);
            Object raw = f.get(inst);
            if (!(raw instanceof List)) {
                log("DUMP: mViews is " + raw);
                return;
            }
            List<?> views = new ArrayList<>((List<?>) raw);
            log("DUMP windows=" + views.size());
            for (Object o : views) {
                if (!(o instanceof View)) {
                    continue;
                }
                View root = (View) o;
                if (root.getVisibility() != View.VISIBLE) {
                    continue;
                }
                String rcn = root.getClass().getName();
                boolean full = rcn.contains("StatusBarWindowView")
                        || rcn.contains("NotificationShadeWindowView")
                        || rcn.contains("NotificationShade");
                log("DUMP root " + root.getClass().getSimpleName()
                        + " " + root.getWidth() + "x" + root.getHeight()
                        + (full ? " (full)" : ""));
                if (!full) {
                    continue;
                }
                ALL.set(360);
                walk(root, 0);
                ALL.set(0);
            }
        } catch (Throwable t) {
            log("DUMP failed: " + t);
        }
    }

    private static void walk(View v, int depth) {
        if (depth > 26) {
            return;
        }
        String id = "NO_ID";
        try {
            if (v.getId() != View.NO_ID) {
                id = v.getResources().getResourceEntryName(v.getId());
            }
        } catch (Throwable ignored) {
            // ignore
        }
        String cn = v.getClass().getSimpleName();
        String text = "";
        String ts = "";
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            text = t == null ? "" : t.toString();
            if (text.length() > 14) {
                text = text.substring(0, 14) + "…";
            }
            ts = " ts=" + ((TextView) v).getTextSize();
        }
        if (v instanceof ImageView) {
            android.graphics.drawable.Drawable d = ((ImageView) v).getDrawable();
            if (d != null) {
                ts += " drawable=" + d.getClass().getSimpleName()
                        + (d instanceof android.graphics.drawable.BitmapDrawable ? "" : "");
            }
        }
        boolean battProbe = cn.contains("Battery") || id.toLowerCase().contains("battery");
        boolean interesting = ALL.get() > 0 || cn.contains("Clock")
                || id.toLowerCase().contains("clock") || cn.contains("Compose")
                || !text.isEmpty() || battProbe;
        if (ALL.get() > 0) {
            ALL.decrementAndGet();
        }
        if (interesting) {
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            String slot = slotOf(v);
            log("DUMP " + pad(depth) + cn + (slot == null ? "" : "/" + slot)
                    + (slot == null ? "" : " tag=" + slotTagOf(v))
                    + " id=" + id + ts
                    + (v.getVisibility() == View.VISIBLE ? "" : " v" + v.getVisibility())
                    + " " + v.getWidth() + "x" + v.getHeight() + " @" + loc[0] + "," + loc[1]
                    + ((cn.contains("Clock") || cn.contains("Fake")
                    || id.toLowerCase().contains("clock"))
                    ? " sx=" + v.getScaleX() + " sy=" + v.getScaleY()
                    + " tx=" + v.getTranslationX() + " ty=" + v.getTranslationY() : "")
                    + (text.isEmpty() ? "" : " text=" + text)
                    + (battProbe ? batteryProbe(v) : ""));
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                walk(g.getChildAt(i), depth + 1);
            }
        }
    }

    private static String pad(int d) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d; i++) {
            sb.append("  ");
        }
        return sb.toString();
    }

    /** 电池节点专项探针：alpha / tint / drawable 类型与 level，用来定位「半透」和「不绿」的源头。 */
    private static String batteryProbe(View v) {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append(" vAlpha=").append(v.getAlpha());
            android.widget.ImageView iv = null;
            if (v instanceof android.widget.ImageView) {
                iv = (android.widget.ImageView) v;
            } else if (v instanceof ViewGroup) {
                iv = findImageView((ViewGroup) v, 3);
            }
            if (iv == null) {
                sb.append(" noIV");
                return sb.toString();
            }
            sb.append(" ivAlpha=").append(iv.getAlpha())
                    .append(" imgAlpha=").append(iv.getImageAlpha());
            try {
                android.content.res.ColorStateList csl = iv.getImageTintList();
                sb.append(" tint=").append(csl == null ? "null"
                        : Integer.toHexString(csl.getDefaultColor()));
            } catch (Throwable ignored) {
                // ignore
            }
            Drawable d = iv.getDrawable();
            if (d == null) {
                sb.append(" d=null");
            } else {
                sb.append(" d=").append(d.getClass().getSimpleName())
                        .append(" dAlpha=").append(d.getAlpha())
                        .append(" lvl=").append(d.getLevel())
                        .append(" lvlMode=").append(levelModeOf(d))
                        .append(" ").append(d.getBounds().width()).append("x")
                        .append(d.getBounds().height());
                if (d instanceof android.graphics.drawable.LayerDrawable) {
                    android.graphics.drawable.LayerDrawable ld =
                            (android.graphics.drawable.LayerDrawable) d;
                    sb.append(" layers=").append(ld.getNumberOfLayers());
                    for (int i = 0; i < ld.getNumberOfLayers(); i++) {
                        Drawable c = ld.getDrawable(i);
                        sb.append(" [").append(i).append(':')
                                .append(c == null ? "null" : c.getClass().getSimpleName())
                                .append(" lvl=").append(c == null ? -1 : c.getLevel())
                                .append(" mode=").append(c == null ? "?" : levelModeOf(c))
                                .append("]");
                    }
                }
            }
        } catch (Throwable t) {
            sb.append(" probeErr=").append(t);
        }
        return sb.toString();
    }

    private static android.widget.ImageView findImageView(ViewGroup g, int depth) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c instanceof android.widget.ImageView) {
                return (android.widget.ImageView) c;
            }
            if (depth > 0 && c instanceof ViewGroup) {
                android.widget.ImageView r = findImageView((ViewGroup) c, depth - 1);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    /** level-list 之外，layer 的 level 是否真的被用来裁剪（描出 level 机制）。 */
    private static String levelModeOf(Drawable d) {
        if (d instanceof PackLayer.ClipLevelDrawable) {
            return "clip";
        }
        if (d instanceof android.graphics.drawable.LevelListDrawable) {
            return "levellist";
        }
        if (d instanceof VecDrawable) {
            return "vector";
        }
        return "-";
    }

    // ---------- 工具 ----------

    private static Class<?> findClass(String name, ClassLoader cl) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 一行 caller 栈：只取前若干帧、只留「类.方法」，用来把每次写 translationX
     * 的始作俑者认出来（我们自己的布局路径 / 系统的动画 / Xposed 派发）。
     */
    private static String stk() {
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        StringBuilder sb = new StringBuilder();
        int kept = 0;
        for (int i = 3; i < st.length && kept < 10; i++) {
            String cn = st[i].getClassName();
            if (cn.equals("android.os.Handler")
                    || cn.equals("android.view.Choreographer")
                    || cn.startsWith("de.robv.android.xposed.")
                    || cn.equals("com.android.internal.os.ZygoteInit")
                    || cn.equals("com.android.internal.os.RuntimeInit")
                    || cn.contains("hide5g.MainHook")
                    || cn.equals("java.lang.reflect.Method")) {
                continue;
            }
            if (kept > 0) {
                sb.append(" <- ");
            }
            int d = cn.lastIndexOf('.');
            sb.append(d > 0 ? cn.substring(d + 1) : cn)
                    .append('.')
                    .append(st[i].getMethodName())
                    .append(':')
                    .append(st[i].getLineNumber());
            kept++;
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    private static void log(String msg) {
        XposedBridge.log(TAG + ": " + msg);
    }
}
