package com.tonyma.hide5g;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import java.lang.ref.WeakReference;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.AbsSeekBar;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;

/** MIUIX 风格的设置页：开关 + 滑块，改完立刻下发到 SystemUI。 */
public class MainActivity extends Activity {

    /** 强调色：默认 #005BFD；能解析到 COUI 主题主色时跟着系统走，和原生控件同色。 */
    private static int ACCENT = 0xFF005BFD;

    private boolean dark;
    private int cBg;
    private int cCard;
    private int cText;
    private int cSub;
    private int cDivider;
    private int cTrack;
    private int cTrackOff;
    /** 粗滑块轨道的实色底（参考图 #EDEDED），不走开关用的半透明色。 */
    private int cSliderTrack;
    /** 开关收起态轨道：必须是实色且和白卡片拉得开，半透明会「看不见」。 */
    private int cSwitchOff;

    private SharedPreferences sp;

    private boolean hideNetType;
    private int clockPercent;
    private int wifiOffset;
    private int mobileOffset;
    private int clockY;
    private int notifX;
    private int wifiIconW;
    private int wifiImgX;
    private int mobileIconW;
    private int mobileImgX;
    /** 画布左右边距（0.1dp）：左边距 = 图标往右让出的空白，右边距 = 右侧空白。 */
    private int wifiIconWL;
    private int wifiIconWR;
    private int mobileIconWL;
    private int mobileIconWR;
    private int notifGap;
    /** Wi-Fi 关闭后移动信号往左补的量（0.1dp）。 */
    private int wifiOffShift;
    /** 电池画布左边距（0.1dp）：正 = 电池右移、左侧间隙变宽。电池无右边距。 */
    private int batteryIconWL;
    /** 方案 A：Wi-Fi 图标左右微调（0.1dp，纯平移只动 Wi-Fi）。 */
    private int wifiNudge;
    /** 方案 A：移动信号左右微调（0.1dp，纯平移只动移动信号）。 */
    private int mobileNudge;
    /** 移动信号右边距扩展（0.1dp，正=画布往右扩）。 */
    private int mobileLPad;
    private boolean packOn;
    private int iconVer;

    // ---------- 边距 / 双卡 ----------
    /** 左侧收紧：时钟 A + 通知图标往右收（0.1dp）。 */
    private int margin;
    /** 右侧收紧：电池 / Wi-Fi / 移动信号等右侧图标往左收（0.1dp）。 */
    private int marginR;
    /** 只显示上网卡信号。 */
    private boolean dataSimOnly;
    /** 双卡信号是否合并（= 系统设置 signal_icon_display_mode 的值）。 */
    private boolean simMerged = true;
    /** 待下发的双卡样式：-1 = 这轮不动系统设置。 */
    private int simStyleSent = Config.SIM_STYLE_NONE;

    // ---------- 新增 ----------
    private boolean hideWifiArrow;
    private boolean hideDataArrow;
    private boolean hideWifiStd;
    private int batteryTextPercent;
    private int batteryTextY;
    private boolean debugOverlay;
    private int statusBarHeight;
    private boolean wifiFixedGap;

    private TextView btextValue;
    private TextView btextYValue;
    private TextView sbhValue;

    private TextView clockValue;
    private TextView wifiValue;
    private TextView mobileValue;
    private TextView clockYValue;
    private TextView notifXValue;
    private TextView packValue;

    private static final int REQ_EXPORT_PACK = 120;
    private static final int REQ_IMPORT_PACK = 121;
    /** 导出中的临时 zip，保存到用户选的位置时用。 */
    private File pendingExport;

    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onPause() {
        super.onPause();
        // ColorOS 会把退到后台的 Activity 很快冻住，apply() 的后台落盘会被截断，
        // 设置就只留在内存里（重启回默认）。退后台时同步把当前值整份写一次盘。
        if (sp == null) {
            return;
        }
        sp.edit()
                .putBoolean(Config.KEY_HIDE, hideNetType)
                .putInt(Config.KEY_CLOCK, clockPercent)
                .putInt(Config.KEY_WIFI, wifiOffset)
                .putInt(Config.KEY_MOBILE, mobileOffset)
                .putInt(Config.KEY_CLOCK_Y, clockY)
                .putInt(Config.KEY_NOTIF_X, notifX)
                .putInt(Config.KEY_WIFI_ICON_W, wifiIconW)
                .putInt(Config.KEY_WIFI_ICON_WL, wifiIconWL)
                .putInt(Config.KEY_WIFI_ICON_WR, wifiIconWR)
                .putInt(Config.KEY_MOBILE_ICON_WL, mobileIconWL)
                .putInt(Config.KEY_MOBILE_ICON_WR, mobileIconWR)
                .putInt(Config.KEY_WIFI_IMG_X, wifiImgX)
                .putInt(Config.KEY_MOBILE_ICON_W, mobileIconW)
                .putInt(Config.KEY_MOBILE_IMG_X, mobileImgX)
                .putInt(Config.KEY_NOTIF_GAP, notifGap)
                .putInt(Config.KEY_WIFI_OFF_SHIFT, wifiOffShift)
                .putInt(Config.KEY_BATTERY_ICON_WL, batteryIconWL)
                .putInt(Config.KEY_ICON_VER, iconVer)
                .putBoolean(Config.KEY_PACK_REPLACE, packOn)
                .putBoolean(Config.KEY_HIDE_WIFI_ARROW, hideWifiArrow)
                .putBoolean(Config.KEY_HIDE_DATA_ARROW, hideDataArrow)
                .putBoolean(Config.KEY_HIDE_WIFI_STD, hideWifiStd)
                .putInt(Config.KEY_BATTERY_TEXT, batteryTextPercent)
                .putInt(Config.KEY_BATTERY_TEXT_Y, batteryTextY)
                .putBoolean(Config.KEY_DEBUG_OVERLAY, debugOverlay)
                .putInt(Config.KEY_STATUS_HEIGHT, statusBarHeight)
                .putBoolean(Config.KEY_WIFI_FIXED_GAP, wifiFixedGap)
                .putInt(Config.KEY_MARGIN, margin)
                .putInt(Config.KEY_MARGIN_R, marginR)
                .putBoolean(Config.KEY_DATA_SIM_ONLY, dataSimOnly)
                .commit();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        initColors();
        applySystemBars();

        sp = FilePrefs.get(this, Config.PREF_NAME);
        hideNetType = sp.getBoolean(Config.KEY_HIDE, true);
        clockPercent = sp.getInt(Config.KEY_CLOCK, Config.CLOCK_DEF);
        wifiOffset = sp.getInt(Config.KEY_WIFI, Config.OFFSET_DEF);
        mobileOffset = sp.getInt(Config.KEY_MOBILE, Config.OFFSET_DEF);
        clockY = sp.getInt(Config.KEY_CLOCK_Y, Config.CLOCK_Y_DEF);
        notifX = sp.getInt(Config.KEY_NOTIF_X, Config.NOTIF_X_DEF);
        wifiIconW = sp.getInt(Config.KEY_WIFI_ICON_W, Config.ICON_W_DEF);
        wifiIconWL = sp.getInt(Config.KEY_WIFI_ICON_WL, Config.ICON_EDGE_DEF);
        wifiIconWR = sp.getInt(Config.KEY_WIFI_ICON_WR, Config.ICON_EDGE_DEF);
        mobileIconWL = sp.getInt(Config.KEY_MOBILE_ICON_WL, Config.ICON_EDGE_DEF);
        mobileIconWR = sp.getInt(Config.KEY_MOBILE_ICON_WR, Config.ICON_EDGE_DEF);
        wifiImgX = sp.getInt(Config.KEY_WIFI_IMG_X, Config.IMG_X_DEF);
        mobileIconW = sp.getInt(Config.KEY_MOBILE_ICON_W, Config.ICON_W_DEF);
        mobileImgX = sp.getInt(Config.KEY_MOBILE_IMG_X, Config.IMG_X_DEF);
        // 旧版本这个滑块到 24dp，缩到 5dp 后要把存量配置夹回来，
        // 否则旧值会继续下发、显示成 24.0dp 但滑块只能拉到 5dp。
        notifGap = Math.max(Config.NOTIF_GAP_MIN, Math.min(Config.NOTIF_GAP_MAX,
                sp.getInt(Config.KEY_NOTIF_GAP, Config.NOTIF_GAP_DEF)));
        wifiOffShift = Math.max(Config.WIFI_OFF_SHIFT_MIN,
                Math.min(Config.WIFI_OFF_SHIFT_MAX,
                        sp.getInt(Config.KEY_WIFI_OFF_SHIFT, Config.WIFI_OFF_SHIFT_DEF)));
        batteryIconWL = sp.getInt(Config.KEY_BATTERY_ICON_WL, Config.ICON_EDGE_DEF);
        wifiNudge = sp.getInt(Config.KEY_WIFI_NUDGE, Config.ICON_EDGE_DEF);
        mobileNudge = sp.getInt(Config.KEY_MOBILE_NUDGE, Config.ICON_EDGE_DEF);
        mobileLPad = sp.getInt(Config.KEY_MOBILE_LPAD, Config.ICON_EDGE_DEF);
        packOn = sp.getBoolean(Config.KEY_PACK_REPLACE, false);
        iconVer = sp.getInt(Config.KEY_ICON_VER, 0);
        hideWifiArrow = sp.getBoolean(Config.KEY_HIDE_WIFI_ARROW, false);
        hideDataArrow = sp.getBoolean(Config.KEY_HIDE_DATA_ARROW, false);
        hideWifiStd = sp.getBoolean(Config.KEY_HIDE_WIFI_STD, false);
        batteryTextPercent = sp.getInt(Config.KEY_BATTERY_TEXT, Config.BTEXT_DEF);
        batteryTextY = sp.getInt(Config.KEY_BATTERY_TEXT_Y, Config.BTEXT_Y_DEF);
        debugOverlay = sp.getBoolean(Config.KEY_DEBUG_OVERLAY, false);
        statusBarHeight = sp.getInt(Config.KEY_STATUS_HEIGHT, Config.SBH_DEF);
        // 默认开：Wi-Fi 强度为 0 时图标会塌下来和移动信号叠成一坨，固定间距能避免
        wifiFixedGap = sp.getBoolean(Config.KEY_WIFI_FIXED_GAP, true);
        margin = sp.getInt(Config.KEY_MARGIN, Config.MARGIN_DEF);
        marginR = sp.getInt(Config.KEY_MARGIN_R, Config.MARGIN_DEF);
        dataSimOnly = sp.getBoolean(Config.KEY_DATA_SIM_ONLY, false);
        // 合并/独立是系统设置里的真实状态，直接读，不在自己这边另存一份
        try {
            simMerged = android.provider.Settings.System.getInt(getContentResolver(),
                    Config.SYS_SIM_DISPLAY_MODE, 1) == 1;
        } catch (Throwable ignored) {
            simMerged = true;
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(cBg);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        // 底部留出悬浮按钮的高度，防止最后一行被盖住
        root.setPadding(dp(16), dp(12), dp(16), dp(100));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        frame.addView(scroll, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 底部悬浮按钮：平时改动实时生效不用按；隐藏5G 等少数项需要它立即生效。
        // 优先用 ColorOS 原生 COUIFloatingButton（真实 GPU 材质：磨砂+焦散阴影+熔球过渡），
        // 拿不到才退回自绘 FabButton。
        final Runnable onRestartClick = new Runnable() {
            @Override
            public void run() {
                restartSystemUi(false);
            }
        };
        View restartBtn = null;
        Coui fabTk = Coui.get(this);
        if (fabTk != null) {
            // 注册 COUI 材质观察者，才能拿到白描边+高光（否则只有纯色圆+阴影）。
            fabTk.registerMaterial();
            // 自定义重启图标（用户提供的 SVG 转 vector，白色填充）。
            android.graphics.drawable.Drawable rebootIcon = null;
            try {
                int id = getResources().getIdentifier("ic_restart_custom", "drawable", getPackageName());
                if (id != 0) {
                    rebootIcon = getResources().getDrawable(id, getTheme());
                }
            } catch (Throwable ignored) {
            }
            if (rebootIcon != null) {
                rebootIcon = rebootIcon.mutate();
                rebootIcon.setTint(Color.WHITE);
            } else {
                rebootIcon = fabTk.drawableFrom("com.android.systemui", "oplus_reboot");
            }
            restartBtn = fabTk.newFab(rebootIcon, ACCENT, 56, onRestartClick);
        }
        int fabTouch;
        if (restartBtn != null) {
            fabTouch = dp(56 + 24);
            android.widget.FrameLayout.LayoutParams rlp =
                    new android.widget.FrameLayout.LayoutParams(fabTouch, fabTouch, Gravity.BOTTOM | Gravity.END);
            rlp.rightMargin = dp(24);
            rlp.bottomMargin = dp(16);
            frame.addView(restartBtn, rlp);
        } else {
            final FabButton fallback = new FabButton(this);
            fallback.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    onRestartClick.run();
                }
            });
            restartBtn = fallback;
            fabTouch = dp(56 + 24);
            android.widget.FrameLayout.LayoutParams rlp =
                    new android.widget.FrameLayout.LayoutParams(fabTouch, fabTouch, Gravity.BOTTOM | Gravity.END);
            rlp.rightMargin = dp(24);
            rlp.bottomMargin = dp(16);
            frame.addView(restartBtn, rlp);
        }

        // 按钮固定停留，不随滚动移动（用户要求固定不动）。

        applyInsets(frame);
        setContentView(frame);

        buildHeader(root);
        buildDebugSection(root);
        buildWifiSection(root);
        buildDataSection(root);
        buildSimSection(root);
        buildBatterySection(root);
        buildClockSection(root);
        buildMarginSection(root);
        buildCustomIconSection(root);
        buildFooter(root);

        // 调试入口：-e reset_sliders 1 把滑块全部归零
        if (getIntent() != null && getIntent().getBooleanExtra("reset_sliders", false)) {
            clockPercent = Config.CLOCK_DEF;
            wifiOffset = Config.OFFSET_DEF;
            mobileOffset = Config.OFFSET_DEF;
            clockY = Config.CLOCK_Y_DEF;
            notifX = Config.NOTIF_X_DEF;
            wifiIconW = Config.ICON_W_DEF;
            wifiIconWL = Config.ICON_EDGE_DEF;
            wifiIconWR = Config.ICON_EDGE_DEF;
            mobileIconWL = Config.ICON_EDGE_DEF;
            mobileIconWR = Config.ICON_EDGE_DEF;
            wifiImgX = Config.IMG_X_DEF;
            mobileIconW = Config.ICON_W_DEF;
            mobileImgX = Config.IMG_X_DEF;
            notifGap = Config.NOTIF_GAP_DEF;
            wifiOffShift = Config.WIFI_OFF_SHIFT_DEF;
            batteryIconWL = Config.ICON_EDGE_DEF;
            wifiNudge = Config.ICON_EDGE_DEF;
            mobileNudge = Config.ICON_EDGE_DEF;
            mobileLPad = Config.ICON_EDGE_DEF;
            margin = Config.MARGIN_DEF;
            marginR = Config.MARGIN_DEF;
            sp.edit()
                    .putInt(Config.KEY_CLOCK, clockPercent)
                    .putInt(Config.KEY_WIFI, wifiOffset)
                    .putInt(Config.KEY_MOBILE, mobileOffset)
                    .putInt(Config.KEY_CLOCK_Y, clockY)
                    .putInt(Config.KEY_NOTIF_X, notifX)
                    .putInt(Config.KEY_MARGIN, margin)
                    .putInt(Config.KEY_MARGIN_R, marginR)
                    .commit();
            push(false);
        }

        // 调试入口：-e reset_icons 1 清掉图标包并关掉相关开关
        if (getIntent() != null && getIntent().getBooleanExtra("reset_icons", false)) {
            packOn = false;
            hideWifiArrow = false;
            hideDataArrow = false;
            hideWifiStd = false;
            debugOverlay = false;
            statusBarHeight = Config.SBH_DEF;
            wifiFixedGap = true;
            batteryTextPercent = Config.BTEXT_DEF;
            batteryTextY = Config.BTEXT_Y_DEF;
            iconVer++;
            deletePackDir();
            savePackFlags();
            sp.edit()
                    .putBoolean(Config.KEY_HIDE_WIFI_ARROW, false)
                    .putBoolean(Config.KEY_HIDE_DATA_ARROW, false)
                    .putBoolean(Config.KEY_HIDE_WIFI_STD, false)
                    .putBoolean(Config.KEY_DEBUG_OVERLAY, false)
                    .putInt(Config.KEY_STATUS_HEIGHT, Config.SBH_DEF)
                    .putBoolean(Config.KEY_WIFI_FIXED_GAP, false)
                    .putInt(Config.KEY_BATTERY_TEXT, Config.BTEXT_DEF)
                    .putInt(Config.KEY_BATTERY_TEXT_Y, Config.BTEXT_Y_DEF)
                    .commit();
            push(false);
        }
        // 调试入口：-e export_pack /path/out.zip 直接导出（不弹保存窗口）；
        //           -e import_pack /path/in.zip 直接导入（不弹选择器）
        String packExport = getIntent() == null ? null : getIntent().getStringExtra("export_pack");
        if (packExport != null) {
            final File target = new File(packExport);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        final int n = buildPackZip(target);
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                toast("已导出 " + n + " 个图标到 " + target);
                            }
                        });
                    } catch (final Throwable t) {
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                toast("导出失败：" + t);
                            }
                        });
                    }
                }
            }, "hide5g-pack-export-file").start();
        }
        String packImport = getIntent() == null ? null : getIntent().getStringExtra("import_pack");
        if (packImport != null) {
            importPack(Uri.fromFile(new File(packImport)));
        }
    }

    // ---------------- UI ----------------

    private void buildHeader(LinearLayout root) {
        TextView title = text("hide5G", 28, true);
        title.setTextColor(cText);
        title.setPadding(dp(8), 0, 0, 0);
        root.addView(title, top(dp(10), 0, 0, 0));

        TextView sub = text("状态栏定制 · ColorOS", 13, false);
        sub.setTextColor(cSub);
        sub.setPadding(dp(8), 0, 0, 0);
        root.addView(sub, top(dp(4)));
    }

    /** 通用开关行。 */
    private View switchRow(String title, String hint, boolean checked, final OnIconToggle toggle) {
        LinearLayout row = row();
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        TextView label = text(title, 16, false);
        label.setTextColor(cText);
        column.addView(label);
        TextView sub = text(hint, 12, false);
        sub.setTextColor(cSub);
        column.addView(sub, top(dp(3)));
        row.addView(column, weight(1));

        CompoundButton sw = null;
        Coui tk = Coui.get(this);
        if (tk != null) {
            sw = tk.newSwitch();
        }
        if (sw != null) {
            // 系统偏好布局里开关写死 clickable=false（点击由外层 preference 行转发过去），
            // 我们这行没有转发逻辑，不自己打开就永远收不到触摸。
            sw.setClickable(true);
            sw.setFocusable(true);
        } else {
            // COUI 借不到（非 ColorOS / Settings 不可加载）才用自绘兜底
            sw = styledSwitch();
        }
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                toggle.onToggle(isChecked);
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = dp(12);
        row.addView(sw, slp);
        return row;
    }

    /** COUI 不可用时的兜底开关。 */
    private Switch styledSwitch() {
        Switch s = new Switch(this);
        styleSwitch(s);
        return s;
    }

    /**
     * 自绘胶囊开关（照 uiverse quiet-panther-93 的结构，配色换成主题蓝）。
     *
     * 三个要点：轨道是实色胶囊（开=蓝、关=灰）；圆点比轨道小一圈、四周等距留白，
     * 所以永远缩在胶囊内部不会顶到边；按住时圆点横向拉长成药丸、靠住将要移动的那端，
     * 松手回圆。
     *
     * Android 17 平台 Switch 用的是 M3 素材：thumb 天生比轨道大一圈、轨道是半透明色，
     * 再套上 setThumbTintList/setTrackTintList 就变成「一颗大白球骑在一条几乎看不见的小条上」，
     * 收起态在白卡片上直接消失、打开态只剩淡蓝一小截。这里换成定尺寸自绘，
     * 开关状态靠实色对比而不是 tint。
     */
    private void styleSwitch(Switch sw) {
        int trackW = dp(46);
        int trackH = dp(26);
        int gap = dp(3);
        StateListDrawable track = new StateListDrawable();
        track.addState(new int[]{android.R.attr.state_checked},
                pill(trackW, trackH, ACCENT, trackH / 2f));
        track.addState(new int[]{}, pill(trackW, trackH, cSwitchOff, trackH / 2f));

        final Drawable normal = switchThumb(trackH, gap, trackH - 2 * gap);
        final Drawable wide = switchThumb(trackH, gap, dp(34));
        sw.setTrackDrawable(track);
        sw.setThumbDrawable(normal);
        sw.setTrackTintList(null);
        sw.setThumbTintList(null);
        sw.setSplitTrack(false);
        sw.setSwitchMinWidth(trackW);
        sw.setSwitchPadding(0);
        sw.setThumbTextPadding(0);
        sw.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                int a = ev.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) {
                    sw.setThumbDrawable(wide);
                    sw.requestLayout();
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    sw.setThumbDrawable(normal);
                    sw.requestLayout();
                }
                // 返回 false：事件继续交给 Switch 自己，点击切换不受影响
                return false;
            }
        });
    }

    /**
     * 开关圆点：白心 + 一圈蓝描边，外面再套一层透明垫（宽=圆点+两侧留白）。
     * Switch 是把 thumb 直接贴着轨道两端摆的，有了这圈垫子，圆点在两端也各自留 3dp；
     * 同一套图层换个更宽的圆点尺寸，就是按住时的药丸态。
     */
    private Drawable switchThumb(int trackH, int gap, int knobW) {
        int d = trackH - 2 * gap;
        int ring = Math.max(1, dp(2.2f));
        GradientDrawable pad = new GradientDrawable();
        pad.setColor(Color.TRANSPARENT);
        pad.setSize(knobW + 2 * gap, trackH);
        LayerDrawable ld = new LayerDrawable(new Drawable[]{
                pad,
                pill(knobW, d, ACCENT, d / 2f),
                pill(knobW - 2 * ring, d - 2 * ring, Color.WHITE, (d - 2 * ring) / 2f)});
        ld.setLayerInset(1, gap, gap, gap, gap);
        int r2 = gap + ring;
        ld.setLayerInset(2, r2, r2, r2, r2);
        return ld;
    }

    /** 实色胶囊 / 圆点：圆角给到短边一半，宽大于高时自然就是药丸形。 */
    private GradientDrawable pill(int w, int h, int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setSize(w, h);
        d.setCornerRadius(radius);
        return d;
    }

    // ---------------- 控件面板 ----------------

    private void buildDebugSection(LinearLayout root) {
        LinearLayout card = card();
        card.addView(switchRow("控件面板", "给状态栏控件加底色和边框，查看层级与间距",
                debugOverlay, new OnIconToggle() {
                    @Override
                    public void onToggle(boolean on) {
                        debugOverlay = on;
                        sp.edit().putBoolean(Config.KEY_DEBUG_OVERLAY, on).apply();
                        push(false);
                        toast(on ? "已显示控件面板" : "已关闭控件面板");
                    }
                }));
        root.addView(card, top(dp(16)));
    }

    // ---------------- Wi-Fi ----------------

    private void buildWifiSection(LinearLayout root) {
        root.addView(section("WiFi"), top(dp(24)));
        LinearLayout card = card();

        TextView nudgeValue = valueLabel();
        View nudgeBar = slider(0, Config.ICON_EDGE_MAX - Config.ICON_EDGE_MIN,
                wifiNudge - Config.ICON_EDGE_MIN, new SimpleSeekListener() {
            @Override
            public void onChanged(View seekBar, int progress, boolean fromUser) {
                wifiNudge = snapEdgeBar(seekBar,
                        Config.ICON_EDGE_MIN + progress, fromUser);
                nudgeValue.setText(tenthText(wifiNudge));
                if (fromUser) {
                    sp.edit().putInt(Config.KEY_WIFI_NUDGE, wifiNudge).apply();
                    pushDebounced();
                }
            }
        });
        nudgeValue.setText(tenthText(wifiNudge));
        card.addView(sliderBlock("左右微调wifi图标",
                "只平移wifi图标",
                nudgeValue, nudgeBar));
        card.addView(divider());

        card.addView(switchRow("隐藏数据箭头", "Wi-Fi 图标旁的收发箭头", hideWifiArrow,
                new OnIconToggle() {
                    @Override
                    public void onToggle(boolean on) {
                        hideWifiArrow = on;
                        sp.edit().putBoolean(Config.KEY_HIDE_WIFI_ARROW, on).apply();
                        push(false);
                    }
                }));
        root.addView(card);
    }

    // ---------------- 数据（手机信号） ----------------

    private void buildDataSection(LinearLayout root) {
        root.addView(section("数据"), top(dp(24)));
        LinearLayout card = card();

        TextView nudgeValue = valueLabel();
        View nudgeBar = slider(0, Config.ICON_EDGE_MAX - Config.ICON_EDGE_MIN,
                mobileNudge - Config.ICON_EDGE_MIN, new SimpleSeekListener() {
            @Override
            public void onChanged(View seekBar, int progress, boolean fromUser) {
                mobileNudge = snapEdgeBar(seekBar,
                        Config.ICON_EDGE_MIN + progress, fromUser);
                nudgeValue.setText(tenthText(mobileNudge));
                if (fromUser) {
                    sp.edit().putInt(Config.KEY_MOBILE_NUDGE, mobileNudge).apply();
                    pushDebounced();
                }
            }
        });
        nudgeValue.setText(tenthText(mobileNudge));
        card.addView(sliderBlock("左右微调手机信号图标",
                "只平移信号图标",
                nudgeValue, nudgeBar));
        card.addView(divider());

        // 右边距扩展：给信号右边加空白（画布往右扩，信号右边的图标被推开），纯 padding 不跳。
        TextView lpadValue = valueLabel();
        View lpadBar = slider(0, Config.ICON_EDGE_MAX - Config.ICON_EDGE_MIN,
                mobileLPad - Config.ICON_EDGE_MIN, new SimpleSeekListener() {
            @Override
            public void onChanged(View seekBar, int progress, boolean fromUser) {
                mobileLPad = snapEdgeBar(seekBar,
                        Config.ICON_EDGE_MIN + progress, fromUser);
                lpadValue.setText(tenthText(mobileLPad));
                if (fromUser) {
                    sp.edit().putInt(Config.KEY_MOBILE_LPAD, mobileLPad).apply();
                    pushDebounced();
                }
            }
        });
        lpadValue.setText(tenthText(mobileLPad));
        card.addView(sliderBlock("给信号右侧加空白",
                "推开右边的图标",
                lpadValue, lpadBar));
        card.addView(divider());

        card.addView(switchRow("隐藏5g", "隐藏5G / 4G等制式文字",
                hideNetType, new OnIconToggle() {
                    @Override
                    public void onToggle(boolean on) {
                        hideNetType = on;
                        sp.edit().putBoolean(Config.KEY_HIDE, on).apply();
                        push(false);
                    }
                }));
        card.addView(divider());

        card.addView(switchRow("隐藏数据箭头", "信号图标旁的上下箭头", hideDataArrow,
                new OnIconToggle() {
                    @Override
                    public void onToggle(boolean on) {
                        hideDataArrow = on;
                        sp.edit().putBoolean(Config.KEY_HIDE_DATA_ARROW, on).apply();
                        push(false);
                    }
                }));
        root.addView(card);
    }

    // ---------------- 双卡 ----------------

    private void buildSimSection(LinearLayout root) {
        root.addView(section("双卡"), top(dp(24)));
        LinearLayout card = card();

        card.addView(switchRow("双卡信号合并",
                "改写系统设置　与系统设置页同步",
                simMerged, new OnIconToggle() {
                    @Override
                    public void onToggle(boolean on) {
                        simMerged = on;
                        simStyleSent = on ? 1 : 0;
                        push(false);
                    }
                }));
        card.addView(divider());
        card.addView(switchRow("只显示上网卡信号",
                "隐藏非数据卡信号　开启时自动切为独立显示",
                dataSimOnly, new OnIconToggle() {
                    @Override
                    public void onToggle(boolean on) {
                        dataSimOnly = on;
                        sp.edit().putBoolean(Config.KEY_DATA_SIM_ONLY, on).commit();
                        // 关掉时把用户选过的合并/独立还回去
                        if (!on) {
                            simStyleSent = simMerged ? 1 : 0;
                        }
                        push(false);
                    }
                }));
        root.addView(card);
    }

    // ---------------- 状态栏元素开关 ----------------

    // ---------------- 电池 ----------------

    private void buildBatterySection(LinearLayout root) {
        root.addView(section("电池"), top(dp(24)));
        LinearLayout card = card();

        btextValue = valueLabel();
        View sizeBar = slider(0, (Config.BTEXT_MAX - Config.BTEXT_MIN) / Config.BTEXT_STEP,
                (batteryTextPercent - Config.BTEXT_MIN) / Config.BTEXT_STEP, new SimpleSeekListener() {
            @Override
            public void onChanged(View seekBar, int progress, boolean fromUser) {
                batteryTextPercent = Config.BTEXT_MIN + progress * Config.BTEXT_STEP;
                btextValue.setText(batteryTextPercent + "%");
                if (fromUser) {
                    sp.edit().putInt(Config.KEY_BATTERY_TEXT, batteryTextPercent).apply();
                    pushDebounced();
                }
            }
        });
        btextValue.setText(batteryTextPercent + "%");
        card.addView(sliderBlock("电量字号", "电池内百分比文字大小", btextValue, sizeBar));
        card.addView(divider());

        // 电池文字上下微调：不同字体调字号后可能在 Y 轴出现偏差，用此项修正。
        btextYValue = valueLabel();
        View btextYBar = slider(0, (Config.BTEXT_Y_MAX - Config.BTEXT_Y_MIN) / Config.BTEXT_Y_STEP,
                (batteryTextY - Config.BTEXT_Y_MIN) / Config.BTEXT_Y_STEP, new SimpleSeekListener() {
            @Override
            public void onChanged(View seekBar, int progress, boolean fromUser) {
                batteryTextY = Config.BTEXT_Y_MIN + progress * Config.BTEXT_Y_STEP;
                btextYValue.setText(tenthText(batteryTextY));
                if (fromUser) {
                    sp.edit().putInt(Config.KEY_BATTERY_TEXT_Y, batteryTextY).apply();
                    pushDebounced();
                }
            }
        });
        btextYValue.setText(tenthText(batteryTextY));
        card.addView(sliderBlock("上下微调",
                "不同字体调整字号之后可能在Y轴上出现偏差\n可以使用此选项来修复",
                btextYValue, btextYBar));
        root.addView(card);
    }

    // ---------------- 时钟 ----------------

    private void buildClockSection(LinearLayout root) {
        root.addView(section("时钟"), top(dp(24)));

        LinearLayout card = card();
        clockValue = valueLabel();

        View bar = slider(0, (Config.CLOCK_MAX - Config.CLOCK_MIN) / Config.CLOCK_STEP,
                (clockPercent - Config.CLOCK_MIN) / Config.CLOCK_STEP, new SimpleSeekListener() {
            @Override
            public void onChanged(View seekBar, int progress, boolean fromUser) {
                clockPercent = Config.CLOCK_MIN + progress * Config.CLOCK_STEP;
                clockValue.setText(clockPercent + "%");
                if (fromUser) {
                    sp.edit().putInt(Config.KEY_CLOCK, clockPercent).apply();
                    pushDebounced();
                }
            }
        });
        clockValue.setText(clockPercent + "%");
        card.addView(sliderBlock("字号", "系统默认 100%", clockValue, bar));
        card.addView(divider());

        clockYValue = valueLabel();
        View yBar = slider(0, (Config.CLOCK_Y_MAX - Config.CLOCK_Y_MIN) / Config.CLOCK_Y_STEP,
                (clockY - Config.CLOCK_Y_MIN) / Config.CLOCK_Y_STEP, new SimpleSeekListener() {
            @Override
            public void onChanged(View seekBar, int progress, boolean fromUser) {
                clockY = Config.CLOCK_Y_MIN + progress * Config.CLOCK_Y_STEP;
                clockYValue.setText(tenthText(clockY));
                if (fromUser) {
                    sp.edit().putInt(Config.KEY_CLOCK_Y, clockY).apply();
                    pushDebounced();
                }
            }
        });
        clockYValue.setText(tenthText(clockY));
        card.addView(sliderBlock("上下微调", "不同字体调整字号之后可能在Y轴上出现偏差\n可以使用此选项来修复", clockYValue, yBar));
        root.addView(card);
    }

    // ---------------- 边距 ----------------

    private void buildMarginSection(LinearLayout root) {
        root.addView(section("边距"), top(dp(24)));
        LinearLayout card = card();

        final TextView mValue = valueLabel();
        View mBar = slider(0, Config.MARGIN_MAX,
                margin, new SimpleSeekListener() {
            @Override
            public void onChanged(View seekBar, int progress, boolean fromUser) {
                margin = progress;
                mValue.setText(dpText(margin));
                if (fromUser) {
                    sp.edit().putInt(Config.KEY_MARGIN, margin).apply();
                    pushDebounced();
                }
            }
        });
        mValue.setText(dpText(margin));
        card.addView(sliderBlock("左侧向内",
                "时钟与通知图标整体右移",
                mValue, mBar));
        card.addView(divider());

        final TextView rValue = valueLabel();
        View rBar = slider(0, Config.MARGIN_MAX,
                marginR, new SimpleSeekListener() {
            @Override
            public void onChanged(View seekBar, int progress, boolean fromUser) {
                marginR = progress;
                rValue.setText(dpText(marginR));
                if (fromUser) {
                    sp.edit().putInt(Config.KEY_MARGIN_R, marginR).apply();
                    pushDebounced();
                }
            }
        });
        rValue.setText(dpText(marginR));
        card.addView(sliderBlock("右侧向内",
                "右侧图标整体左移",
                rValue, rBar));
        card.addView(divider());

        final TextView gValue = valueLabel();
        View gBar = slider(0, Config.NOTIF_GAP_MAX,
                notifGap, new SimpleSeekListener() {
            @Override
            public void onChanged(View seekBar, int progress, boolean fromUser) {
                notifGap = progress;
                gValue.setText(dpText(notifGap));
                if (fromUser) {
                    sp.edit().putInt(Config.KEY_NOTIF_GAP, notifGap).apply();
                    pushDebounced();
                }
            }
        });
        gValue.setText(dpText(notifGap));
        card.addView(sliderBlock("通知图标与时钟间距",
                "在默认间距上微调",
                gValue, gBar));
        root.addView(card);
    }

    private void buildCustomIconSection(LinearLayout root) {
        root.addView(section("图标"), top(dp(24)));

        LinearLayout card = card();
        card.addView(actionRow("上传图标包",
                "选择 zip 图标包，同名文件自动替换",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        pickPack();
                    }
                }));
        card.addView(divider());
        card.addView(actionRow("下载官方包",
                "导出全部状态栏图标（原生 XML，可改）",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        startPackExport();
                    }
                }));
        card.addView(divider());
        card.addView(actionRow("清除替换", "删除已导入图标，恢复系统原状",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        clearPack();
                    }
                }));
        root.addView(card);

        packValue = text(packStatus(), 12, false);
        packValue.setTextColor(cSub);
        packValue.setLineSpacing(dp(3), 1f);
        root.addView(packValue, top(dp(8), dp(4), dp(4), 0));
    }

    private String packStatus() {
        int n = packCount();
        return n == 0
                ? "图标包为空：先「下载官方包」，改好后同名上传即可替换。"
                : "已导入 " + n + " 个图标。" + (packOn ? "替换已生效。" : "开关未打开，暂不替换。");
    }

    /** 一行操作入口：标题 + 副标题 + 点击。 */
    private View actionRow(String title, String hint, View.OnClickListener click) {
        LinearLayout row = row();
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        TextView label = text(title, 16, false);
        label.setTextColor(cText);
        column.addView(label);
        TextView sub = text(hint, 12, false);
        sub.setTextColor(cSub);
        column.addView(sub, top(dp(3)));
        row.addView(column, weight(1));
        // 只有标题+描述的行看不出可点，补一个箭头
        TextView chevron = new TextView(this);
        chevron.setText("›");
        chevron.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
        chevron.setTextColor(cSub);
        chevron.setPadding(dp(8), 0, dp(2), 0);
        row.addView(chevron);
        row.setOnClickListener(click);
        return row;
    }

    private interface OnIconToggle {
        void onToggle(boolean on);
    }

    /**
     * 图标相关的开关统一用 commit() 同步落盘。
     * ColorOS 会在 Activity 退到后台后立刻冻结进程，apply() 的异步写盘会被截断，
     * 结果就是图标文件写进去了、开关状态却没存下来。
     */
    private void savePackFlags() {
        if (sp == null) {
            sp = FilePrefs.get(this, Config.PREF_NAME);
        }
        sp.edit()
                .putInt(Config.KEY_ICON_VER, iconVer)
                .putBoolean(Config.KEY_PACK_REPLACE, packOn)
                .commit();
    }

    private File packDir() {
        File dir = new File(getFilesDir(), "pack");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    private int packCount() {
        File[] fs = packDir().listFiles();
        int n = 0;
        if (fs != null) {
            for (File f : fs) {
                if (f.isFile() && f.getName().endsWith(".xml")) {
                    n++;
                }
            }
        }
        return n;
    }

    private void deletePackDir() {
        File[] fs = packDir().listFiles();
        if (fs != null) {
            for (File f : fs) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        refreshPackStatus();
    }

    private void refreshPackStatus() {
        if (packValue != null) {
            packValue.setText(packStatus());
        }
    }

    private void pickPack() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/zip");
            startActivityForResult(i, REQ_IMPORT_PACK);
        } catch (Throwable t) {
            toast("无法打开文件选择器：" + t.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        if (requestCode == REQ_IMPORT_PACK) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    importPack(uri);
                }
            }, "hide5g-pack-import").start();
        } else if (requestCode == REQ_EXPORT_PACK) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    String err = null;
                    try {
                        // pendingExport 只在本进程存活期间有效；若选保存位置时进程被杀
                        // 重建，就用固定缓存路径兜底（导出本来就是写到这个文件）
                        File src = pendingExport != null && pendingExport.exists()
                                ? pendingExport
                                : new File(getCacheDir(), "hide5g-iconpack.zip");
                        InputStream in = new java.io.FileInputStream(src);
                        java.io.OutputStream out = getContentResolver().openOutputStream(uri);
                        if (out == null) {
                            err = "写不进这个位置";
                        } else {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = in.read(buf)) > 0) {
                                out.write(buf, 0, n);
                            }
                            out.close();
                        }
                        in.close();
                    } catch (Throwable t) {
                        err = t.toString();
                    }
                    final String message = err;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            toast(message == null ? "已保存" : "保存失败：" + message);
                        }
                    });
                }
            }, "hide5g-pack-save").start();
        }
    }

    /** 从 SystemUI 导出全部状态栏矢量图标，打成 zip，交给用户选位置保存。 */
    private void startPackExport() {
        toast("正在导出…");
        final File tmp = new File(getCacheDir(), "hide5g-iconpack.zip");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final int count = buildPackZip(tmp);
                    pendingExport = tmp;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            toast("已导出 " + count + " 个图标，选择保存位置");
                            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                            i.addCategory(Intent.CATEGORY_OPENABLE);
                            i.setType("application/zip");
                            i.putExtra(Intent.EXTRA_TITLE, "hide5g-iconpack.zip");
                            try {
                                startActivityForResult(i, REQ_EXPORT_PACK);
                            } catch (Throwable t) {
                                toast("无法打开保存窗口：" + t.getMessage());
                            }
                        }
                    });
                } catch (final Throwable t) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            toast("导出失败：" + t);
                        }
                    });
                }
            }
        }, "hide5g-pack-export").start();
    }

    /** 把清单里的 SystemUI 资源逐个序列化成文本 XML 打进 zip，返回成功数。 */
    private int buildPackZip(File target) throws Exception {
        android.content.pm.PackageManager pm = getPackageManager();
        android.content.res.Resources sys = pm.getResourcesForApplication(
                IconPackNames.SYSTEM_UI_PKG);
        int n = 0;
        java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(
                new FileOutputStream(target));
        StringBuilder catalog = new StringBuilder();
        for (String name : IconPackNames.ALL) {
            int id = sys.getIdentifier(name, "drawable", IconPackNames.SYSTEM_UI_PKG);
            if (id == 0) {
                continue;
            }
            try {
                String xml = serializeDrawableXml(sys, id);
                zos.putNextEntry(new java.util.zip.ZipEntry("res/drawable/" + name + ".xml"));
                zos.write(xml.getBytes("UTF-8"));
                zos.closeEntry();
                n++;
                catalog.append(name).append('\n');
            } catch (Throwable t) {
                catalog.append(name).append("  (导出失败: ").append(t).append(")\n");
            }
        }
        zos.putNextEntry(new java.util.zip.ZipEntry("pack.json"));
        zos.write(("{\"name\":\"ColorOS17-native\",\"count\":" + n + "}").getBytes("UTF-8"));
        zos.closeEntry();
        zos.putNextEntry(new java.util.zip.ZipEntry("catalog.txt"));
        zos.write(catalog.toString().getBytes("UTF-8"));
        zos.closeEntry();
        zos.close();
        return n;
    }

    /** 把 SystemUI 的二进制 XML 资源读回文本 VectorDrawable XML。 */
    private String serializeDrawableXml(android.content.res.Resources sys, int id) throws Exception {
        final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
        org.xmlpull.v1.XmlPullParser xp = sys.getXml(id);
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        int e = xp.getEventType();
        while (e != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (e == org.xmlpull.v1.XmlPullParser.START_TAG) {
                sb.append('<').append(xp.getName());
                boolean hasAndroid = false;
                for (int i = 0; i < xp.getAttributeCount(); i++) {
                    if (ANDROID_NS.equals(xp.getAttributeNamespace(i))) {
                        hasAndroid = true;
                        break;
                    }
                }
                if (hasAndroid) {
                    sb.append(" xmlns:android=\"").append(ANDROID_NS).append('"');
                }
                for (int i = 0; i < xp.getAttributeCount(); i++) {
                    String v = xp.getAttributeValue(i);
                    if (v == null) {
                        continue;
                    }
                    // 主题色引用（?attr/… 或编译后的 ?123）：设置进程解析不了，统一换成白色，
                    // 状态栏最终会用系统 tint 重新着色
                    if (v.startsWith("?")) {
                        v = "#FFFFFFFF";
                    } else if (v.startsWith("@")) {
                        String inner = v.substring(1);
                        if (inner.matches("\\d+")) {
                            // 编译后的 @123456 引用：还原成 @类型/名字，SystemUI 进程里能解析
                            try {
                                v = "@" + sys.getResourceName(Integer.parseInt(inner));
                            } catch (Throwable ignored) {
                                v = "#FFFFFFFF";
                            }
                        }
                    }
                    String ns = xp.getAttributeNamespace(i);
                    String name = ANDROID_NS.equals(ns)
                            ? "android:" + xp.getAttributeName(i) : xp.getAttributeName(i);
                    sb.append(' ').append(name).append("=\"").append(escXml(v)).append('"');
                }
                sb.append('>');
            } else if (e == org.xmlpull.v1.XmlPullParser.END_TAG) {
                sb.append("</").append(xp.getName()).append('>');
            } else if (e == org.xmlpull.v1.XmlPullParser.TEXT) {
                String t = xp.getText();
                if (t != null) {
                    sb.append(escXml(t));
                }
            }
            e = xp.next();
        }
        sb.append('\n');
        return sb.toString();
    }

    private static String escXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /** 解压图标包：res/drawable/原名.xml 或平铺 原名.xml 都认；覆盖旧包。 */
    private void importPack(Uri uri) {
        String err = null;
        int n = 0;
        try {
            byte[] raw = readAll(uri);
            java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(
                    new java.io.ByteArrayInputStream(raw));
            File dir = packDir();
            // 先收进内存，再「先写后删」：避免 hook 在删除和写入之间读到空目录
            java.util.HashMap<String, byte[]> entries = new java.util.HashMap<>();
            java.util.zip.ZipEntry ze;
            while ((ze = zin.getNextEntry()) != null) {
                if (ze.isDirectory()) {
                    continue;
                }
                String path = ze.getName();
                int slash = path.lastIndexOf('/');
                String base = slash >= 0 ? path.substring(slash + 1) : path;
                if (!base.toLowerCase().endsWith(".xml")) {
                    continue;   // pack.json / catalog.txt / 其它文件：跳过
                }
                base = base.substring(0, base.length() - 4).toLowerCase();
                if (!base.matches("[a-z0-9_]+") || base.length() > 100) {
                    continue;
                }
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int k;
                while ((k = zin.read(buf)) > 0) {
                    bos.write(buf, 0, k);
                }
                byte[] data = bos.toByteArray();
                // 必须长得像 XML（防误传二进制文件）
                int head = 0;
                while (head < data.length && (data[head] == '\n' || data[head] == '\r'
                        || data[head] == ' ' || data[head] == '\t')) {
                    head++;
                }
                if (head >= data.length || data[head] != '<') {
                    continue;
                }
                entries.put(base + ".xml", data);
            }
            zin.close();
            // 先写（覆盖同名），再删掉包里没有的旧文件
            for (java.util.Map.Entry<String, byte[]> e : entries.entrySet()) {
                FileOutputStream out = new FileOutputStream(new File(dir, e.getKey()));
                out.write(e.getValue());
                out.close();
            }
            File[] oldFiles = dir.listFiles();
            if (oldFiles != null) {
                for (File f : oldFiles) {
                    if (f.isFile() && !entries.containsKey(f.getName())) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                }
            }
            n = entries.size();
        } catch (Throwable t) {
            err = t.toString();
        }
        final int count = n;
        if (err == null) {
            // 落盘和下发必须同步做完：ColorOS 会在 Activity 退后台后立刻冻结进程
            iconVer++;
            if (count > 0) {
                packOn = true;   // 导入了包就顺手把开关打开
            }
            savePackFlags();
            push(false);
        }
        final String message = err;
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (message != null) {
                    toast("导入失败：" + message);
                    return;
                }
                refreshPackStatus();
                toast("已导入 " + count + " 个图标");
            }
        });
    }

    private void clearPack() {
        int n = packCount();
        if (n == 0) {
            toast("图标包本来就是空的");
            return;
        }
        deletePackDir();
        packOn = false;
        iconVer++;
        savePackFlags();
        push(false);
        toast("已清除 " + n + " 个图标");
    }

    private byte[] readAll(Uri uri) throws Exception {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new IllegalStateException("读不到文件");
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        in.close();
        return bos.toByteArray();
    }

    private void buildFooter(LinearLayout root) {
        // 恢复默认设置：不在卡片里占位，放个文字链就行
        TextView reset = text("恢复默认设置", 14, false);
        reset.setTextColor(ACCENT);
        reset.setPadding(0, dp(10), 0, dp(2));
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resetAll();
            }
        });
        root.addView(reset);

        TextView footer = text("滑块与开关实时生效；隐藏5G 等少数改动需按底部「重启系统界面」。\n"
                + "需在 LSPosed 中启用本模块，作用域勾选「系统界面」。", 12, false);
        footer.setTextColor(cSub);
        footer.setLineSpacing(dp(4), 1f);
        root.addView(footer, top(dp(8), dp(8), dp(8), 0));

        TextView ver = text("hide5G 7.0 · " + Build.MANUFACTURER + " " + Build.MODEL
                + " · Android " + Build.VERSION.RELEASE, 11, false);
        ver.setTextColor(cSub);
        ver.setAlpha(0.7f);
        root.addView(ver, top(dp(10), dp(4), dp(4), 0));
    }

    // ---------------- 交互 ----------------

    private void resetAll() {
        hideNetType = true;
        clockPercent = Config.CLOCK_DEF;
        wifiOffset = Config.OFFSET_DEF;
        mobileOffset = Config.OFFSET_DEF;
        clockY = Config.CLOCK_Y_DEF;
        notifX = Config.NOTIF_X_DEF;
        wifiIconW = Config.ICON_W_DEF;
        wifiIconWL = Config.ICON_EDGE_DEF;
        wifiIconWR = Config.ICON_EDGE_DEF;
        wifiImgX = Config.IMG_X_DEF;
        mobileIconW = Config.ICON_W_DEF;
        mobileIconWL = Config.ICON_EDGE_DEF;
        mobileIconWR = Config.ICON_EDGE_DEF;
        mobileImgX = Config.IMG_X_DEF;
        notifGap = Config.NOTIF_GAP_DEF;
        wifiOffShift = Config.WIFI_OFF_SHIFT_DEF;
        batteryIconWL = Config.ICON_EDGE_DEF;
        wifiNudge = Config.ICON_EDGE_DEF;
        mobileNudge = Config.ICON_EDGE_DEF;
        mobileLPad = Config.ICON_EDGE_DEF;
        packOn = false;
        hideWifiArrow = false;
        hideDataArrow = false;
        hideWifiStd = false;
        batteryTextPercent = Config.BTEXT_DEF;
        batteryTextY = Config.BTEXT_Y_DEF;
        debugOverlay = false;
        statusBarHeight = Config.SBH_DEF;
        wifiFixedGap = true;
        iconVer++;
        deletePackDir();
        sp.edit()
                .putBoolean(Config.KEY_HIDE, true)
                .putInt(Config.KEY_CLOCK, clockPercent)
                .putInt(Config.KEY_WIFI, wifiOffset)
                .putInt(Config.KEY_MOBILE, mobileOffset)
                .putInt(Config.KEY_CLOCK_Y, clockY)
                .putInt(Config.KEY_NOTIF_X, notifX)
                .putInt(Config.KEY_WIFI_ICON_W, wifiIconW)
                .putInt(Config.KEY_WIFI_ICON_WL, wifiIconWL)
                .putInt(Config.KEY_WIFI_ICON_WR, wifiIconWR)
                .putInt(Config.KEY_MOBILE_ICON_WL, mobileIconWL)
                .putInt(Config.KEY_MOBILE_ICON_WR, mobileIconWR)
                .putInt(Config.KEY_WIFI_IMG_X, wifiImgX)
                .putInt(Config.KEY_MOBILE_ICON_W, mobileIconW)
                .putInt(Config.KEY_MOBILE_IMG_X, mobileImgX)
                .putInt(Config.KEY_NOTIF_GAP, notifGap)
                .putInt(Config.KEY_WIFI_OFF_SHIFT, wifiOffShift)
                .putInt(Config.KEY_BATTERY_ICON_WL, batteryIconWL)
                .putInt(Config.KEY_WIFI_NUDGE, wifiNudge)
                .putInt(Config.KEY_MOBILE_NUDGE, mobileNudge)
                .putInt(Config.KEY_MOBILE_LPAD, mobileLPad)
                .putInt(Config.KEY_ICON_VER, iconVer)
                .putBoolean(Config.KEY_PACK_REPLACE, false)
                .putBoolean(Config.KEY_HIDE_WIFI_ARROW, false)
                .putBoolean(Config.KEY_HIDE_DATA_ARROW, false)
                .putBoolean(Config.KEY_HIDE_WIFI_STD, false)
                .putInt(Config.KEY_BATTERY_TEXT, Config.BTEXT_DEF)
                .putInt(Config.KEY_BATTERY_TEXT_Y, Config.BTEXT_Y_DEF)
                .putBoolean(Config.KEY_DEBUG_OVERLAY, false)
                .putInt(Config.KEY_STATUS_HEIGHT, Config.SBH_DEF)
                .putBoolean(Config.KEY_WIFI_FIXED_GAP, true)
                .        commit();
        recreate();
        push(false);
    }

    private long lastPush;
    private final Runnable pushTask = new Runnable() {
        @Override
        public void run() {
            push(true);
        }
    };

    private void pushDebounced() {
        ui.removeCallbacks(pushTask);
        ui.postDelayed(pushTask, 90);
    }

    private void push(boolean silent) {
        Intent i = new Intent(Config.ACTION_CHANGED);
        i.setPackage("com.android.systemui");
        i.putExtra(Config.KEY_HIDE, hideNetType);
        i.putExtra(Config.KEY_CLOCK, clockPercent);
        i.putExtra(Config.KEY_WIFI, wifiOffset);
        i.putExtra(Config.KEY_MOBILE, mobileOffset);
        i.putExtra(Config.KEY_CLOCK_Y, clockY);
        i.putExtra(Config.KEY_NOTIF_X, notifX);
        i.putExtra(Config.KEY_WIFI_ICON_W, wifiIconW);
        i.putExtra(Config.KEY_WIFI_ICON_WL, wifiIconWL);
        i.putExtra(Config.KEY_WIFI_ICON_WR, wifiIconWR);
        i.putExtra(Config.KEY_MOBILE_ICON_WL, mobileIconWL);
        i.putExtra(Config.KEY_MOBILE_ICON_WR, mobileIconWR);
        i.putExtra(Config.KEY_WIFI_IMG_X, wifiImgX);
        i.putExtra(Config.KEY_MOBILE_ICON_W, mobileIconW);
        i.putExtra(Config.KEY_MOBILE_IMG_X, mobileImgX);
        i.putExtra(Config.KEY_NOTIF_GAP, notifGap);
        i.putExtra(Config.KEY_WIFI_OFF_SHIFT, wifiOffShift);
        i.putExtra(Config.KEY_BATTERY_ICON_WL, batteryIconWL);
        i.putExtra(Config.KEY_WIFI_NUDGE, wifiNudge);
        i.putExtra(Config.KEY_MOBILE_NUDGE, mobileNudge);
        i.putExtra(Config.KEY_MOBILE_LPAD, mobileLPad);
        i.putExtra(Config.KEY_ICON_VER, iconVer);
        i.putExtra(Config.KEY_PACK_REPLACE, packOn);
        i.putExtra(Config.KEY_HIDE_WIFI_ARROW, hideWifiArrow);
        i.putExtra(Config.KEY_HIDE_DATA_ARROW, hideDataArrow);
        i.putExtra(Config.KEY_HIDE_WIFI_STD, hideWifiStd);
        i.putExtra(Config.KEY_BATTERY_TEXT, batteryTextPercent);
        i.putExtra(Config.KEY_BATTERY_TEXT_Y, batteryTextY);
        i.putExtra(Config.KEY_DEBUG_OVERLAY, debugOverlay);
        i.putExtra(Config.KEY_STATUS_HEIGHT, statusBarHeight);
        i.putExtra(Config.KEY_WIFI_FIXED_GAP, wifiFixedGap);
        i.putExtra(Config.KEY_MARGIN, margin);
        i.putExtra(Config.KEY_MARGIN_R, marginR);
        i.putExtra(Config.KEY_DATA_SIM_ONLY, dataSimOnly);
        // 只有刚拨过「双卡信号合并」才带这个值；平时发 -1 = 不干预系统设置
        i.putExtra(Config.KEY_SIM_STYLE, simStyleSent);
        simStyleSent = Config.SIM_STYLE_NONE;
        try {
            sendBroadcast(i);
            if (!silent) {
                lastPush = System.currentTimeMillis();
            }
        } catch (Throwable t) {
            toast("下发失败：" + t.getMessage());
        }
    }

    private void restartSystemUi(final boolean silent) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok;
                String err = "";
                try {
                    Process p = Runtime.getRuntime()
                            .exec(new String[]{"su", "-c", "killall com.android.systemui"});
                    BufferedReader reader =
                            new BufferedReader(new InputStreamReader(p.getErrorStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    ok = p.waitFor() == 0;
                    err = sb.toString().trim();
                } catch (Throwable t) {
                    ok = false;
                    err = t.toString();
                }
                final boolean success = ok;
                final String message = err;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (success) {
                            if (!silent) {
                                toast("已重启系统界面");
                            }
                        } else {
                            toast("重启失败，请手动重启：" + message);
                        }
                    }
                });
            }
        }, "hide5g-restart").start();
    }

    // ---------------- 组件工厂 ----------------

    private void initColors() {
        Coui tk = Coui.get(this);
        if (tk != null) {
            ACCENT = tk.color("couiColorPrimary", ACCENT);
            Log.d("hide5G-coui", "primary=" + Integer.toHexString(ACCENT));
        }
        // 配色对齐 COS17 / COUI 原生预设：页面 #F0F1F2、卡片纯白、标题 .9、说明 .54。
        if (dark) {
            cBg = 0xFF000000;
            cCard = 0xFF1E1E1E;
            cText = 0xE6FFFFFF;
            cSub = 0x8AFFFFFF;
            cDivider = 0x33FFFFFF;
            cTrack = 0x33FFFFFF;
            cTrackOff = 0x33FFFFFF;
            cSliderTrack = 0xFF3A3A3D;
            cSwitchOff = 0x40FFFFFF;
        } else {
            cBg = 0xFFF0F1F2;
            cCard = 0xFFFFFFFF;
            cText = 0xE6000000;
            cSub = 0x8A000000;
            cDivider = 0x1F000000;
            cTrack = 0x1F000000;
            cTrackOff = 0x1F000000;
            cSliderTrack = 0xFFEDEDED;
            cSwitchOff = 0x29000000;
        }
    }

    private void applySystemBars() {
        Window w = getWindow();
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController c = w.getInsetsController();
                if (c != null) {
                    int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    c.setSystemBarsAppearance(dark ? 0 : mask, mask);
                }
            } else {
                int flags = w.getDecorView().getSystemUiVisibility();
                if (dark) {
                    flags &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
                } else {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                }
                w.getDecorView().setSystemUiVisibility(flags);
            }
        } catch (Throwable ignore) {
            // 忽略
        }
    }

    private void applyInsets(final View root) {
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int top;
                int bottom;
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets bars =
                            insets.getInsets(WindowInsets.Type.systemBars());
                    top = bars.top;
                    bottom = bars.bottom;
                } else {
                    top = insets.getSystemWindowInsetTop();
                    bottom = insets.getSystemWindowInsetBottom();
                }
                v.setPadding(0, top, 0, bottom);
                return insets;
            }
        });
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cCard);
        bg.setCornerRadius(dp(24));
        card.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        card.setLayoutParams(lp);
        return card;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setMinimumHeight(dp(52));
        return row;
    }

    private LinearLayout sliderBlock(String title, String hint, TextView value, View bar) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(8));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = text(title, 16, false);
        label.setTextColor(cText);
        head.addView(label, weight(1));

        head.addView(value);
        box.addView(head);

        TextView sub = text(hint, 12, false);
        sub.setTextColor(cSub);
        box.addView(sub, top(dp(3)));

        box.addView(bar, top(dp(6)));
        return box;
    }

    private TextView valueLabel() {
        TextView v = new TextView(this);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        // 数值是这行里最该被看到的东西，用强调色而不是和说明文字同级的灰
        v.setTextColor(ACCENT);
        return v;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(cDivider);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.7f)));
        lp.leftMargin = dp(16);
        v.setLayoutParams(lp);
        return v;
    }

    /**
     * 所有滑块统一走这里。
     *
     * 首选系统原生 COUISeekBar：轨道磨砂、圆点带熔球和焦散阴影，是 GPU 着色器画的，
     * 自绘很难做到与原厂一致，优先使用系统 COUI 控件；借不到时才退回自绘兜底。
     */
    private View slider(int min, int max, int progress, final SimpleSeekListener cb) {
        Coui tk = Coui.get(this);
        if (tk != null) {
            final AbsSeekBar co = tk.newSeekBar();
            if (co != null && tk.attachSeekListener(co, new Coui.OnSeek() {
                @Override
                public void onProgressChanged(int p, boolean fromUser) {
                    cb.onProgressChanged(co, p, fromUser);
                }
            })) {
                co.setMax(max - min);
                co.setProgress(Math.max(0, Math.min(max - min, progress - min)));
                co.setLayoutParams(rowLp());
                return co;
            }
        }
        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(Math.max(0, Math.min(max - min, progress - min)));
        bar.setSplitTrack(false);
        int trackH = dp(22);
        int knobD = dp(16);
        bar.setThumb(placeholder(knobD, trackH));
        bar.setProgressDrawable(new FillTrack(bar, cSliderTrack, ACCENT, Color.WHITE,
                trackH, knobD, dp(2.2f), dp(1.5f)));
        bar.setPadding(0, dp(8), 0, dp(8));
        bar.setMinimumHeight(trackH + dp(16));
        bar.setLayoutParams(rowLp());
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                cb.onProgressChanged(s, p, fromUser);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        bar.invalidate();
        return bar;
    }

    private LinearLayout.LayoutParams rowLp() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** 透明占位 thumb：只负责给 SeekBar 留触摸几何，看得见的圆点由 FillTrack 画。 */
    private Drawable placeholder(int w, int h) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.TRANSPARENT);
        d.setSize(w, h);
        return d;
    }

    /** 灰底胶囊 + 蓝填充到圆心 + 白心蓝圈圆点，全程锁在胶囊内部。 */
    private static class FillTrack extends Drawable {
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final WeakReference<SeekBar> barRef;
        private final int trackH;
        private final int knobD;
        private final int ring;
        private final int gap;

        FillTrack(SeekBar bar, int trackColor, int fillColor, int dotColor,
                  int trackHpx, int knobDpx, int ringPx, int gapPx) {
            bgPaint.setColor(trackColor);
            fgPaint.setColor(fillColor);
            ringPaint.setColor(fillColor);
            dotPaint.setColor(dotColor);
            barRef = new WeakReference<>(bar);
            trackH = trackHpx;
            knobD = knobDpx;
            ring = ringPx;
            gap = gapPx;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (b.isEmpty()) {
                return;
            }
            int top = b.top + Math.max(0, (b.height() - trackH) / 2);
            int bottom = top + trackH;
            float cr = trackH / 2f;
            canvas.drawRoundRect(b.left, top, b.right, bottom, cr, cr, bgPaint);

            SeekBar bar = barRef.get();
            float frac = 0f;
            if (bar != null && bar.getMax() > 0) {
                frac = (float) bar.getProgress() / bar.getMax();
            }
            frac = Math.max(0f, Math.min(1f, frac));

            float kr = knobD / 2f;
            float lo = b.left + kr + gap;
            float hi = b.right - kr - gap;
            float center = hi < lo ? (b.left + b.right) / 2f
                    : Math.max(lo, Math.min(hi, knobCenter(bar, b, frac)));

            // 蓝段画到圆心（不是圆边），圆点再压上去：中间不可能露出灰底。
            if (center > b.left + 1f) {
                canvas.drawRoundRect(b.left, top, center, bottom, cr, cr, fgPaint);
            }
            float midY = (top + bottom) / 2f;
            canvas.drawCircle(center, midY, kr, ringPaint);
            canvas.drawCircle(center, midY, Math.max(0f, kr - ring), dotPaint);
        }

        /**
         * 圆点中心的跟随位置。AbsSeekBar 每帧都会把真实 thumb 的 bounds 写回 thumb
         * drawable，取上一帧的值最贴近手指；拿不到（首帧）再按进度线性推算。
         */
        private float knobCenter(SeekBar bar, Rect b, float frac) {
            Drawable th = bar != null ? bar.getThumb() : null;
            Rect tb = th == null ? null : th.getBounds();
            if (tb != null && tb.width() > 0) {
                return tb.exactCenterX();
            }
            return b.left + frac * (b.right - b.left);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        /**
         * AbsSeekBar 用 progressDrawable 的 intrinsic height 定位轨道带，再把圆点
         * 竖直居中到这条带上。不返回就会拿到 -1，圆点整体往轨道下方坠一截 ——
         * 之前那个「蓝环挂在灰条下面」就是这个。
         */
        @Override
        public int getIntrinsicHeight() {
            return trackH;
        }
    }

    // ---------------- 滑块颗粒感震动 ----------------

    /**
     * ColorOS / Oplus 私有震动常量（从本机 /system/framework/oplus-framework.jar
     * 反编译得到：com.oplus.view.OplusHapticFeedbackConstants）。
     * 302 = GRANULAR_SHORT_VIBRATE，就是时钟滚轮那种细颗粒短震；
     * 305 = STEPPING_SHORT_VIBRATE，档位步进短震，作为备用。
     * 只传 int 数值，不引用隐藏类及其字段，普通 App 也能调用。
     */
    private static final int OPLUS_GRANULAR_SHORT_VIBRATE = 302;
    private static final int OPLUS_STEPPING_SHORT_VIBRATE = 305;
    private static final long TICK_MIN_INTERVAL_MS = 38L;
    private static final java.util.concurrent.atomic.AtomicBoolean DBG_HAPTIC =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static volatile Boolean sOplusHaptic = null;

    private static boolean hasOplusHaptic() {
        Boolean b = sOplusHaptic;
        if (b == null) {
            boolean found = false;
            try {
                Class.forName("com.oplus.view.OplusHapticFeedbackConstants");
                found = true;
            } catch (Throwable ignored) {
            }
            if (!found) {
                String brand = (Build.BRAND + " " + Build.MANUFACTURER)
                        .toLowerCase(java.util.Locale.US);
                found = brand.contains("oplus") || brand.contains("oneplus")
                        || brand.contains("oppo") || brand.contains("realme");
            }
            b = found ? Boolean.TRUE : Boolean.FALSE;
            sOplusHaptic = b;
        }
        return b;
    }

    /** 极轻的一颗 tick：ColorOS 走 ROM 私有颗粒感常量，其它 ROM 逐级回退。 */
    private static void tickHaptic(View v) {
        if (v == null) {
            return;
        }
        int flags = HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING;
        String branch = "";
        try {
            if (hasOplusHaptic()) {
                if (v.performHapticFeedback(OPLUS_GRANULAR_SHORT_VIBRATE, flags)) {
                    branch = "oplus-granular";
                } else if (v.performHapticFeedback(OPLUS_STEPPING_SHORT_VIBRATE, flags)) {
                    branch = "oplus-stepping";
                }
            } else if (v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK, flags)) {
                branch = "clock-tick";
            }
        } catch (Throwable ignored) {
            // ROM 不认这个 effectId 时可能抛异常，继续走兜底
        }
        if (branch.isEmpty()) {
            // 系统触感反馈被关 / effectId 不被支持：用最轻的预置 tick 兜底
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    Vibrator vib = (Vibrator) v.getContext()
                            .getSystemService(Context.VIBRATOR_SERVICE);
                    if (vib != null) {
                        vib.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK));
                        branch = "effect-tick";
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (DBG_HAPTIC.compareAndSet(false, true)) {
            Log.d("hide5G", "haptic branch=" + branch);
        }
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) {
            tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        }
        tv.setTextColor(cText);
        return tv;
    }

    private TextView section(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setTextColor(cSub);
        tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        // COS17 分组标题：与卡片左边对齐（root 已有 16dp），再加 4dp 让文字略进一点。
        tv.setPadding(dp(20), 0, dp(16), dp(2));
        return tv;
    }

    private static String offsetText(int dp) {
        if (dp == 0) {
            return "0 dp";
        }
        return (dp > 0 ? "+" : "") + dp + " dp";
    }

    /** 0.1dp 单位转显示文本（单方向滑块用，不带正负号）。 */
    private static String dpText(int tenth) {
        return String.format(java.util.Locale.US, "%.1f dp", tenth / 10f);
    }

    /** 0.1dp 单位转显示文本。 */
    private static String tenthText(int tenth) {
        if (tenth == 0) {
            return "0 dp";
        }
        String v = String.format(java.util.Locale.US, "%.1f", tenth / 10f);
        return (tenth > 0 ? "+" : "") + v + " dp";
    }

    /**
     * 边距吸附：实际值在 ±ICON_EDGE_SNAP（±0.3dp）内归 0，否则原样返回。
     * 手动拖滑块精确停到 0 很难，靠近 0 时自动吸到 0。
     */
    private static int snapEdge(int tenth) {
        return Math.abs(tenth) <= Config.ICON_EDGE_SNAP ? 0 : tenth;
    }

    /**
     * 对边距滑块的值做吸附：先把实际值吸附到 0（若靠近），
     * 若发生吸附则在拖动中把滑块进度回拉到 0 位（progress = 0 - ICON_EDGE_MIN）。
     * 返回吸附后的实际值（供调用方存储/显示）。
     */
    private static int snapEdgeBar(View bar, int actual, boolean fromUser) {
        int snapped = snapEdge(actual);
        if (snapped != actual && fromUser) {
            int target = snapped - Config.ICON_EDGE_MIN;
            try {
                if (bar instanceof android.widget.ProgressBar) {
                    ((android.widget.ProgressBar) bar).setProgress(target);
                }
            } catch (Throwable ignored) {
                // ignore
            }
        }
        return snapped;
    }

    private LinearLayout.LayoutParams top(int t) {
        return top(t, 0, 0, 0);
    }

    private LinearLayout.LayoutParams top(int t, int l, int r, int b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = t;
        lp.leftMargin = l;
        lp.rightMargin = r;
        lp.bottomMargin = b;
        return lp;
    }

    private LinearLayout.LayoutParams weight(float w) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
        return lp;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /**
     * 所有滑块的统一监听器基类：进度每变一格就给一颗极轻的颗粒感 tick，
     * 并按时间节流，快速拖动时不会连成一片嗡嗡声。
     * 原生 COUISeekBar 和自绘 SeekBar 都汇到这里，回调参数用 View 才能两边通用。
     */
    private abstract static class SimpleSeekListener {
        private int lastProgress = Integer.MIN_VALUE;
        private long lastTickAt = 0L;

        public final void onProgressChanged(View bar, int progress, boolean fromUser) {
            if (fromUser && progress != lastProgress) {
                long now = SystemClock.uptimeMillis();
                if (now - lastTickAt >= TICK_MIN_INTERVAL_MS) {
                    lastTickAt = now;
                    tickHaptic(bar);
                }
                lastProgress = progress;
            }
            onChanged(bar, progress, fromUser);
        }

        protected abstract void onChanged(View bar, int progress, boolean fromUser);
    }

    /**
     * COUI 风格悬浮圆按钮（FAB）。纯 Canvas 绘制，近似组件库的「凝光」玻璃质感：
     * - 底层：柔和的径向投影，模拟外发光/浮起
     * - 主体：蓝色径向渐变（顶部略亮、底部略深），模拟球面受光
     * - 内发光：中心偏上的白色径向高光（innerGlowAlpha≈0.3）
     * - 表层：左上角一道斜向高光玻璃反光
     * - 图标：中心重启（旋转箭头）图标
     */
    private class FabButton extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private android.graphics.RadialGradient baseGrad;
        private android.graphics.RadialGradient glowGrad;
        private android.graphics.LinearGradient sheenGrad;
        private android.graphics.RadialGradient shadowGrad;
        private float pressScale = 1f;

        FabButton(Context c) {
            super(c);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
            p.setStyle(Paint.Style.FILL);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

        private int topColor(int base) {
            // 顶部提亮 ~14%
            int r = Math.min(255, (int) (Color.red(base) * 1.14f) + 10);
            int g = Math.min(255, (int) (Color.green(base) * 1.14f) + 10);
            int b = Math.min(255, (int) (Color.blue(base) * 1.14f) + 10);
            return Color.rgb(r, g, b);
        }

        private int bottomColor(int base) {
            // 底部加深 ~12%
            int r = (int) (Color.red(base) * 0.86f);
            int g = (int) (Color.green(base) * 0.86f);
            int b = (int) (Color.blue(base) * 0.86f);
            return Color.rgb(r, g, b);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            int pad = dp(12);
            float cx = w / 2f, cy = h / 2f;
            float r = Math.min(w, h) / 2f - pad;      // 圆半径
            float top = cy - r, bottom = cy + r;

            // 主体球面渐变：光源在左上，半径覆盖整圆
            baseGrad = new android.graphics.RadialGradient(
                    cx - r * 0.35f, cy - r * 0.4f, r * 1.7f,
                    new int[]{topColor(ACCENT), ACCENT, bottomColor(ACCENT)},
                    new float[]{0f, 0.55f, 1f}, android.graphics.Shader.TileMode.CLAMP);

            // 内发光：中心偏上一团白色柔和光
            glowGrad = new android.graphics.RadialGradient(
                    cx, cy - r * 0.28f, r * 0.95f,
                    new int[]{0x59FFFFFF, 0x22FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.5f, 1f}, android.graphics.Shader.TileMode.CLAMP);

            // 左上玻璃反光带
            sheenGrad = new android.graphics.LinearGradient(
                    cx - r, top, cx + r * 0.3f, cy,
                    new int[]{0x40FFFFFF, 0x00FFFFFF},
                    null, android.graphics.Shader.TileMode.CLAMP);

            // 柔和径向投影（圆心略下移）
            shadowGrad = new android.graphics.RadialGradient(
                    cx, bottom - r * 0.15f, r * 1.25f,
                    new int[]{0x33004C99, 0x18004C99, 0x00000000},
                    new float[]{0f, 0.62f, 1f}, android.graphics.Shader.TileMode.CLAMP);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.save();
            canvas.scale(pressScale, pressScale, getWidth() / 2f, getHeight() / 2f);
            int pad = dp(12);
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float r = Math.min(getWidth(), getHeight()) / 2f - pad;

            // 1. 投影
            p.setShader(shadowGrad);
            p.setStrokeWidth(0f);
            canvas.drawCircle(cx, cy, r * 1.18f, p);

            // 2. 主体
            p.setShader(baseGrad);
            canvas.drawCircle(cx, cy, r, p);

            // 3. 内发光
            p.setShader(glowGrad);
            canvas.drawCircle(cx, cy, r, p);

            // 4. 玻璃反光（裁剪到圆内）
            canvas.save();
            android.graphics.Path clip = new android.graphics.Path();
            clip.addCircle(cx, cy, r, android.graphics.Path.Direction.CW);
            canvas.clipPath(clip);
            p.setShader(sheenGrad);
            canvas.drawRect(cx - r, cy - r, cx + r, cy + r, p);
            canvas.restore();

            // 5. 边缘描边（细白，模拟玻璃边）
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1f));
            p.setColor(0x33FFFFFF);
            canvas.drawCircle(cx, cy, r - dp(0.5f), p);

            // 6. 白边框 + 重启图标
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2.2f));
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(Color.WHITE);
            float iconR = r * 0.46f;
            android.graphics.RectF arc = new android.graphics.RectF(
                    cx - iconR, cy - iconR, cx + iconR, cy + iconR);
            canvas.drawArc(arc, -40f, 300f, false, p);

            // 箭头（在圆弧缺口处，指向旋转方向）
            double ang = Math.toRadians(-40f);
            float ax = (float) (cx + iconR * Math.cos(ang));
            float ay = (float) (cy + iconR * Math.sin(ang));
            float ah = dp(4.2f);   // 箭头半长
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2.2f));
            // 两笔构成 V 形箭头，方向沿切线
            double tanAng = ang + Math.PI / 2;
            float tx = (float) Math.cos(tanAng), ty = (float) Math.sin(tanAng);
            float nx = (float) Math.cos(ang), ny = (float) Math.sin(ang);
            canvas.drawLine(ax, ay, ax + tx * ah, ay + ty * ah, p);
            canvas.drawLine(ax, ay, ax + nx * ah * 0.9f - tx * ah * 0.2f,
                    ay + ny * ah * 0.9f - ty * ah * 0.2f, p);

            p.setStyle(Paint.Style.FILL);
            canvas.restore();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    animateScale(0.92f, 120);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    animateScale(1f, 180);
                    break;
            }
            return super.onTouchEvent(e);
        }

        private void animateScale(float target, long dur) {
            android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(pressScale, target);
            va.setDuration(dur);
            va.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(android.animation.ValueAnimator a) {
                    pressScale = (float) a.getAnimatedValue();
                    invalidate();
                }
            });
            va.start();
        }
    }
}
