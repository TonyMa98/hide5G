package com.tonyma.hide5g;

/** 模块与 App 共用的常量（键名、取值范围）。 */
public final class Config {

    public static final String AUTHORITY = "com.tonyma.hide5g.config";
    public static final String ACTION_CHANGED = "com.tonyma.hide5g.CONFIG_CHANGED";
    public static final String PREF_NAME = "hide5g";

    /** 隐藏网络制式文字。 */
    public static final String KEY_HIDE = "hide_net_type";
    /** 时钟字号，百分比 60~170。 */
    public static final String KEY_CLOCK = "clock_percent";
    /** Wi-Fi 图标水平偏移，单位 dp。 */
    public static final String KEY_WIFI = "wifi_offset_dp";
    /** 手机信号图标水平偏移，单位 dp。 */
    public static final String KEY_MOBILE = "mobile_offset_dp";
    /** 时钟垂直偏移，单位 0.1dp（-25~25 即 -2.5~+2.5dp）。 */
    public static final String KEY_CLOCK_Y = "clock_y_tenth";
    /** 通知图标相对屏幕左边的偏移，单位 dp。 */
    public static final String KEY_NOTIF_X = "notif_x_dp";
    /** 图标包开关：包里有哪些同名 XML 就替换哪些。 */
    public static final String KEY_PACK_REPLACE = "pack_replace";
    /** 图标版本号，换包时 +1，让 SystemUI 重新解码。 */
    public static final String KEY_ICON_VER = "icon_ver";

    // ---------- 新增：状态栏细节 ----------

    /** 隐藏 Wi-Fi 上下行小箭头。 */
    public static final String KEY_HIDE_WIFI_ARROW = "hide_wifi_arrow";
    /** 隐藏移动数据上下行小箭头。 */
    public static final String KEY_HIDE_DATA_ARROW = "hide_data_arrow";
    /** 隐藏 Wi-Fi 制式标识（WiFi4 / WiFi5 / WiFi6 / 6E / 7）。 */
    public static final String KEY_HIDE_WIFI_STD = "hide_wifi_std";

    /** 电池内电量数字字号，百分比 60~170。 */
    public static final String KEY_BATTERY_TEXT = "battery_text_percent";
    /** 电池内电量数字垂直偏移，单位 0.1dp（0~30 即 0~3dp）。 */
    public static final String KEY_BATTERY_TEXT_Y = "battery_text_y_tenth";

    /** 调试画板：显示控件轮廓与半透明色块。 */
    public static final String KEY_DEBUG_OVERLAY = "debug_overlay";

    /** 状态栏行高，单位 dp（0 = 系统默认，可正可负）。 */
    public static final String KEY_STATUS_HEIGHT = "status_bar_height_dp";
    /** Wi-Fi 强度为 0 时保持与信号图标的固定间距，避免重叠。 */
    public static final String KEY_WIFI_FIXED_GAP = "wifi_fixed_gap";

    /** Wi-Fi 图标画布宽度加宽/收窄，单位 0.1dp。 */
    public static final String KEY_WIFI_ICON_W = "wifi_icon_w_tenth";
    /** Wi-Fi 画布左边距增减（正 = 图标右移），单位 0.1dp。 */
    public static final String KEY_WIFI_ICON_WL = "wifi_icon_wl_tenth";
    /** Wi-Fi 画布右边距增减，单位 0.1dp。 */
    public static final String KEY_WIFI_ICON_WR = "wifi_icon_wr_tenth";
    /** 移动信号画布左边距增减，单位 0.1dp。 */
    public static final String KEY_MOBILE_ICON_WL = "mobile_icon_wl_tenth";
    /** 移动信号画布右边距增减，单位 0.1dp。 */
    public static final String KEY_MOBILE_ICON_WR = "mobile_icon_wr_tenth";
    /** Wi-Fi 图像在画布内的左右偏移，单位 0.1dp。 */
    public static final String KEY_WIFI_IMG_X = "wifi_img_x_tenth";
    /** 移动信号图标画布宽度加宽/收窄，单位 0.1dp。 */
    public static final String KEY_MOBILE_ICON_W = "mobile_icon_w_tenth";
    /** 移动信号图像在画布内的左右偏移，单位 0.1dp。 */
    public static final String KEY_MOBILE_IMG_X = "mobile_img_x_tenth";
    /** 通知图标在系统默认间距基础上再往右拉开的距离，单位 0.1dp（0 = 系统原位置）。 */
    public static final String KEY_NOTIF_GAP = "notif_gap_tenth";
    /**
     * Wi-Fi 关闭后，移动信号图标额外往左补的距离，单位 0.1dp。
     * 0 = 照旧补到 Wi-Fi 原位（会贴到电池）；越大离电池越远。
     */
    public static final String KEY_WIFI_OFF_SHIFT = "wifi_off_shift_tenth";

    /**
     * 电池图标画布左边距增减（正 = 电池右移、左侧留白变宽），单位 0.1dp。
     * 电池只有左边距、没有右边距：右边距由系统贴屏幕边缘的固定留白负责。
     */
    public static final String KEY_BATTERY_ICON_WL = "battery_icon_wl_tenth";

    // ---------- 图标位置（方案 A：纯平移，不改画布宽度） ----------

    /**
     * Wi-Fi 图标左右微调（正 = 右移、负 = 左移），单位 0.1dp。
     * 纯靠平移实现：只改 Wi-Fi 的 translationX，不碰它的画布宽度/内边距，
     * 因此动画结束后不会再有「改宽度→整排让位」的逐步跳动。
     * 只动 Wi-Fi 一个图标，移动信号与电池位置不受影响。
     */
    public static final String KEY_WIFI_NUDGE = "wifi_nudge_tenth";
    /** 移动信号图标左右微调（正 = 右移、负 = 左移），单位 0.1dp。纯平移只动移动信号。 */
    public static final String KEY_MOBILE_NUDGE = "mobile_nudge_tenth";
    /** 移动信号左边距扩展（正 = 画布往左扩、内容左边界左移），单位 0.1dp；扩画布不跳。 */
    public static final String KEY_MOBILE_LPAD = "mobile_lpad_tenth";

    // ---------- 边距 / 双卡 ----------

    /**
     * 左侧收紧：只作用于状态栏时钟 A 与通知图标，整组往右收。
     * 单位 0.1dp，0 = 系统默认位置。
     */
    public static final String KEY_MARGIN = "margin_tenth";
    /**
     * 右侧收紧：只作用于电池 / Wi-Fi / 移动信号等右侧状态图标，整组往左收。
     * 隐私小点不参与。单位 0.1dp，0 = 系统默认位置。
     */
    public static final String KEY_MARGIN_R = "margin_r_tenth";
    /** 只显示上网卡（默认数据卡）的信号，隐藏其它卡的信号图标。 */
    public static final String KEY_DATA_SIM_ONLY = "data_sim_only";
    /** 双卡信号样式：-1 不干预、0 独立、1 合并；只在广播里下发。 */
    public static final String KEY_SIM_STYLE = "sim_style";

    /** ColorOS 状态栏双卡信号样式的系统设置键（0 独立 / 1 合并）。 */
    public static final String SYS_SIM_DISPLAY_MODE = "signal_icon_display_mode";

    public static final int CLOCK_MIN = 60;
    public static final int CLOCK_MAX = 170;
    public static final int CLOCK_STEP = 5;
    public static final int CLOCK_DEF = 100;

    /**
     * 图标左右微调：存储单位是 0.1dp（调 1 = 0.1dp），范围 ±4dp。
     * 以前是整 dp、范围 ±30，一滑就是几十像素，直接把图标推到邻居身上叠起来。
     */
    public static final int OFFSET_MIN = -40;
    public static final int OFFSET_MAX = 40;
    public static final int OFFSET_DEF = 0;

    /** 存储单位（dp）与硬性上限，防止旧配置里的大数值把图标推飞。 */
    public static final float OFFSET_UNIT = 0.1f;
    public static final float OFFSET_LIMIT_DP = 4f;

    /** 时钟上下微调：全程 5dp（-2.5~+2.5），步进 0.1dp（存储单位本来就是 0.1dp）。 */
    public static final int CLOCK_Y_MIN = -25;
    public static final int CLOCK_Y_MAX = 25;
    public static final int CLOCK_Y_STEP = 1;
    public static final int CLOCK_Y_DEF = 0;

    /** 通知图标左边距微调，单位 0.1dp（最大 ±2dp），同样只做微调不做搬运。 */
    public static final int NOTIF_X_MIN = -20;
    public static final int NOTIF_X_MAX = 20;
    public static final int NOTIF_X_DEF = 0;

    /** 信号图标画布宽度增减：±8dp，0.1dp 步进。 */
    public static final int ICON_W_MIN = -80;
    public static final int ICON_W_MAX = 80;
    public static final int ICON_W_DEF = 0;

    /**
     * 画布左右边距：各 ±8dp，0.1dp 步进。
     * 左边距变大 = 图标右移、左侧留白变宽；右边距变大 = 右侧留白变宽。
     * 两者之和就是画布总宽度的增减，相邻图标照旧自动让位。
     */
    public static final int ICON_EDGE_MIN = -80;
    public static final int ICON_EDGE_MAX = 80;
    public static final int ICON_EDGE_DEF = 0;

    /**
     * 画布边距滑块在 0 附近 ±SNAP_TENTH（±3 = ±0.3dp）范围内自动吸附到 0。
     * 手动拖滑块很难精准停在 0，这个吸附解决「调不到 0」的问题。
     */
    public static final int ICON_EDGE_SNAP = 3;

    /** 图像在画布内左右偏移：±4dp，0.1dp 步进。 */
    public static final int IMG_X_MIN = -40;
    public static final int IMG_X_MAX = 40;
    public static final int IMG_X_DEF = 0;

    /** 通知图标间距微调：0~5dp，0.1dp 步进（0 = 系统默认间距，只微调不搬运）。 */
    public static final int NOTIF_GAP_MIN = 0;
    public static final int NOTIF_GAP_MAX = 50;
    public static final int NOTIF_GAP_DEF = 0;

    /**
     * Wi-Fi 关闭后的补位量：0~22dp，0.1dp 步进。
     * 上限 22dp ≈ Wi-Fi 图标的画布宽（实测 78px），拉到顶就等于「完全不补位、原地不动」。
     */
    public static final int WIFI_OFF_SHIFT_MIN = 0;
    public static final int WIFI_OFF_SHIFT_MAX = 220;
    public static final int WIFI_OFF_SHIFT_DEF = 40;

    /** 左侧 / 右侧收紧：各 0~24dp，0.1dp 步进（0 = 系统默认）。 */
    public static final int MARGIN_MIN = 0;
    public static final int MARGIN_MAX = 240;
    public static final int MARGIN_DEF = 0;

    /** 双卡信号样式取值：不干预。-1 之外的值直接写进系统设置。 */
    public static final int SIM_STYLE_NONE = -1;

    /** 电池内电量数字字号百分比范围。 */
    public static final int BTEXT_MIN = 60;
    public static final int BTEXT_MAX = 170;
    public static final int BTEXT_STEP = 5;
    public static final int BTEXT_DEF = 100;

    /** 电量数字垂直偏移：0~3dp，步进 0.1dp。 */
    public static final int BTEXT_Y_MIN = 0;
    public static final int BTEXT_Y_MAX = 30;
    public static final int BTEXT_Y_STEP = 1;
    public static final int BTEXT_Y_DEF = 0;

    /** 状态栏行高调整范围（dp）。 */
    public static final int SBH_MIN = -8;
    public static final int SBH_MAX = 16;
    public static final int SBH_DEF = 0;

    private Config() {
    }
}
