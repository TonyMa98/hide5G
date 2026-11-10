package com.tonyma.hide5g;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 把设置暴露给 SystemUI 进程。
 *
 * 为什么不用 XSharedPreferences：Android 10 之后 SELinux 通常不允许 systemui
 * 直接读别的 App 的 data 目录，跨进程读文件很容易被拒；走 ContentProvider 是
 * binder 调用，没有这个限制，也不用任何权限。
 *
 * 列顺序必须与 MainHook#loadConfigAsync 的下标一一对应，只往后追加、不改前面的位置。
 * 图标包文件走 openFile：content://authority/pack/{资源名}（如 pack/stat_sys_alarm）。
 */
public class PrefsProvider extends ContentProvider {

    public static final Uri URI = Uri.parse("content://" + Config.AUTHORITY + "/config");

    static final String[] COLUMNS = {
            // 0-10：历史字段，位置不能再动
            "hide", "clock", "wifi", "mobile",
            "clock_y", "notif_x", "wifi_icon", "mobile_icon", "icon_ver",
            "mobile_primary_icon", "mobile_secondary_icon",
            // 11+
            "hide_wifi_arrow", "hide_data_arrow", "hide_wifi_std",
            "battery_icon", "battery_layers", "battery_text", "battery_text_y",
            "debug_overlay", "status_height", "wifi_fixed_gap",
            "wifi_iw", "wifi_ix", "mobile_iw", "mobile_ix", "notif_gap",
            // 26：图标包开关
            "pack_replace",
            // 27+
            "margin_tenth", "data_sim_only",
            // 29+：画布左右边距（Wi-Fi / 移动信号各自一半）
            "wifi_il", "wifi_ir", "mobile_il", "mobile_ir",
            // 33：右侧收紧（图标组 + 隐私小点往左收）
            "margin_r_tenth",
            // 34：Wi-Fi 关闭后移动信号的补位量
            "wifi_off_shift",
            // 35：电池画布左边距
            "battery_il",
            // 36：Wi-Fi 左右微调（方案 A 纯平移，只动 Wi-Fi）
            "wifi_nudge",
            // 37：移动信号左右微调（方案 A 纯平移，只动移动信号）
            "mobile_nudge",
            // 38：移动信号左边距扩展（扩画布往左，纯 padding 不跳）
            "mobile_lpad"
    };

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        Context ctx = getContext();
        // 系统 SharedPreferences 在该 ROM 上 commit 不落盘（实测），用文件版
        SharedPreferences sp = FilePrefs.get(ctx, Config.PREF_NAME);
        MatrixCursor c = new MatrixCursor(COLUMNS, 1);
        c.addRow(new Object[]{
                sp.getBoolean(Config.KEY_HIDE, true) ? 1 : 0,
                sp.getInt(Config.KEY_CLOCK, Config.CLOCK_DEF),
                sp.getInt(Config.KEY_WIFI, Config.OFFSET_DEF),
                sp.getInt(Config.KEY_MOBILE, Config.OFFSET_DEF),
                sp.getInt(Config.KEY_CLOCK_Y, Config.CLOCK_Y_DEF),
                sp.getInt(Config.KEY_NOTIF_X, Config.NOTIF_X_DEF),
                0, 0,   // 6/7：旧的单图标开关，已废弃，占位
                sp.getInt(Config.KEY_ICON_VER, 0),
                0, 0,   // 9/10：旧的主/副卡开关，已废弃，占位
                sp.getBoolean(Config.KEY_HIDE_WIFI_ARROW, false) ? 1 : 0,
                sp.getBoolean(Config.KEY_HIDE_DATA_ARROW, false) ? 1 : 0,
                sp.getBoolean(Config.KEY_HIDE_WIFI_STD, false) ? 1 : 0,
                0,      // 14：旧电池开关，已废弃
                0,      // 15：旧电池层数，已废弃
                sp.getInt(Config.KEY_BATTERY_TEXT, Config.BTEXT_DEF),
                sp.getInt(Config.KEY_BATTERY_TEXT_Y, Config.BTEXT_Y_DEF),
                sp.getBoolean(Config.KEY_DEBUG_OVERLAY, false) ? 1 : 0,
                sp.getInt(Config.KEY_STATUS_HEIGHT, Config.SBH_DEF),
                sp.getBoolean(Config.KEY_WIFI_FIXED_GAP, true) ? 1 : 0,
                sp.getInt(Config.KEY_WIFI_ICON_W, Config.ICON_W_DEF),
                sp.getInt(Config.KEY_WIFI_IMG_X, Config.IMG_X_DEF),
                sp.getInt(Config.KEY_MOBILE_ICON_W, Config.ICON_W_DEF),
                sp.getInt(Config.KEY_MOBILE_IMG_X, Config.IMG_X_DEF),
                sp.getInt(Config.KEY_NOTIF_GAP, Config.NOTIF_GAP_DEF),
                sp.getBoolean(Config.KEY_PACK_REPLACE, false) ? 1 : 0,
                sp.getInt(Config.KEY_MARGIN, Config.MARGIN_DEF),
                sp.getBoolean(Config.KEY_DATA_SIM_ONLY, false) ? 1 : 0,
                sp.getInt(Config.KEY_WIFI_ICON_WL, Config.ICON_EDGE_DEF),
                sp.getInt(Config.KEY_WIFI_ICON_WR, Config.ICON_EDGE_DEF),
                sp.getInt(Config.KEY_MOBILE_ICON_WL, Config.ICON_EDGE_DEF),
                sp.getInt(Config.KEY_MOBILE_ICON_WR, Config.ICON_EDGE_DEF),
                sp.getInt(Config.KEY_MARGIN_R, Config.MARGIN_DEF),
                sp.getInt(Config.KEY_WIFI_OFF_SHIFT, Config.WIFI_OFF_SHIFT_DEF),
                sp.getInt(Config.KEY_BATTERY_ICON_WL, Config.ICON_EDGE_DEF),
                sp.getInt(Config.KEY_WIFI_NUDGE, Config.ICON_EDGE_DEF),
                sp.getInt(Config.KEY_MOBILE_NUDGE, Config.ICON_EDGE_DEF),
                sp.getInt(Config.KEY_MOBILE_LPAD, Config.ICON_EDGE_DEF)
        });
        return c;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        java.util.List<String> seg = uri.getPathSegments();
        if (seg == null || seg.size() != 2 || !"pack".equals(seg.get(0))) {
            throw new FileNotFoundException("bad uri: " + uri);
        }
        File f = packFile(getContext(), seg.get(1));
        if (f == null || !f.exists()) {
            throw new FileNotFoundException("no pack icon: " + uri);
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** 图标包里的图标文件路径（App 私有目录 pack/资源名.xml）。 */
    public static File packFile(Context ctx, String name) {
        if (!isValidIconName(name)) {
            return null;
        }
        File dir = new File(ctx.getFilesDir(), "pack");
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        return new File(dir, name + ".xml");
    }

    /** 图标包目录。 */
    public static File packDir(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "pack");
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        return dir;
    }

    /** 允许的资源名白名单：防路径穿越，也防止读任意文件。 */
    static boolean isValidIconName(String name) {
        if (name == null || name.length() == 0 || name.length() > 100) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_')) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String getType(Uri uri) {
        if (uri != null && uri.getPathSegments() != null
                && uri.getPathSegments().size() == 2 && "pack".equals(uri.getPathSegments().get(0))) {
            return "text/xml";
        }
        return "vnd.android.cursor.item/vnd.hide5g.config";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
