package com.tonyma.hide5g;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 极简 VectorDrawable XML 渲染器。
 *
 * 系统的 Drawable.createFromXml 只接受 APK 内编译过的二进制 XML 解析器
 * （XmlBlock.Parser），从文本流读出来的普通 XmlPullParser 会在
 * obtainStyledAttributes 里抛 ClassCastException，所以这里自己解析渲染。
 *
 * 支持：vector / group（嵌套、pivot/rotate/scale/translate）/ path /
 * clip-path、fillType=evenOdd、fill 与 stroke 全套（颜色 alpha、fillAlpha、
 * strokeAlpha、lineCap、lineJoin）。?attr / @ 引用按白色处理（颜色交给系统 tint）。
 * 不支持：trimPath、渐变、动画、layer-list（解析失败返回 null，回退系统原图标）。
 */
final class VecDrawable extends Drawable {

    /** 渲染指令（矩阵已在解析期烘焙进 path）。 */
    private static final class Op {
        final Path path;
        final boolean clip;
        final int fillColor;      // 0 = 不填充
        final int strokeColor;    // 0 = 不描边
        final float strokeWidth;
        final Paint.Cap cap;
        final Paint.Join join;

        Op(Path path, boolean clip, int fillColor, int strokeColor,
           float strokeWidth, Paint.Cap cap, Paint.Join join) {
            this.path = path;
            this.clip = clip;
            this.fillColor = fillColor;
            this.strokeColor = strokeColor;
            this.strokeWidth = strokeWidth;
            this.cap = cap;
            this.join = join;
        }
    }

    private static final int WHITE = 0xFFFFFFFF;

    private final List<Op> ops = new ArrayList<>();
    private final float vw;
    private final float vh;
    private final int iw;
    private final int ih;
    private int baseAlpha = 255;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 系统给的着色（ImageView tintList / Drawable.setTint），null = 不上色。 */
    private android.graphics.PorterDuffColorFilter tintFilter;
    private static final java.util.concurrent.atomic.AtomicInteger DBG_DRAW =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger DBG_BATT =
            new java.util.concurrent.atomic.AtomicInteger();
    private android.content.res.ColorStateList tintList;
    private int tintColor;
    /** 外部 setColorFilter 传进来的过滤器，优先级高于 tint。 */
    private ColorFilter externalFilter;

    private VecDrawable(float vw, float vh, int iw, int ih) {
        this.vw = vw;
        this.vh = vh;
        this.iw = iw;
        this.ih = ih;
        fillPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
    }

    /** 上一次解析失败的原因（调用方拿去做日志）。 */
    static String lastError = "";

    // ---------------- 单色判定 ----------------
    // 系统状态栏图标是单色的，上色（tint / colorFilter）才有意义；
    // 用户自己画的彩色图标不该被染成一种颜色。

    private int monoRgb = -1;
    private boolean mono = true;

    /** 资源名，仅用于日志定位。 */
    String name = "";

    private void noteColor(int c) {
        if (c == 0) {
            return;
        }
        int rgb = c & 0xFFFFFF;
        if (monoRgb < 0) {
            monoRgb = rgb;
        } else if (rgb != monoRgb) {
            mono = false;
        }
    }

    /** 图形是否只有一种颜色（只有单色图才允许被系统色覆盖）。 */
    boolean isMonochrome() {
        return mono && monoRgb >= 0;
    }

    /** 单色图的那个色（0xRRGGBB）。 */
    int monoColor() {
        return monoRgb;
    }

    /** 复制一份（ops 只读可共享），给不同控件各自的上色用。 */
    VecDrawable copy() {        VecDrawable d = new VecDrawable(vw, vh, iw, ih);
        d.ops.addAll(ops);
        d.mono = mono;
        d.monoRgb = monoRgb;
        d.name = name;
        // 颜色也要带过去；但 alpha/bounds 必须各管各的，
        // 否则一个 View 淡出（setAlpha）会把共用这张图的其它图标一起变淡。
        d.tintColor = tintColor;
        d.tintList = tintList;
        d.tintFilter = tintFilter;
        return d;
    }

    /** 当前的着色（外部 colorFilter 优先），供上级继续传递。 */
    int currentTint() {
        return tintColor;
    }

    /**
     * 发一份独立的副本。
     *
     * 缓存里那张是"模板"，View 会往 drawable 上写 bounds / alpha / colorFilter，
     * 多个控件共用一个实例会互相污染（下拉时图标淡出后一直半透明就是这个）。
     */
    static Drawable dup(Drawable d) {
        if (d instanceof VecDrawable) {
            return ((VecDrawable) d).copy();
        }
        if (d instanceof PackLayer.ClipLevelDrawable) {
            return ((PackLayer.ClipLevelDrawable) d).copy();
        }
        return d;
    }

    /** 解析 vector XML 文本；失败返回 null（调用方回退系统原图标）。 */
    static Drawable parse(android.content.res.Resources res, String xml) {
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(true);
            XmlPullParser p = factory.newPullParser();
            p.setInput(new java.io.StringReader(xml));
            float density = res.getDisplayMetrics().density;
            VecDrawable out = read(p, density);
            if (out == null) {
                lastError = "no <vector> root, head=" + head(xml);
                return null;
            }
            if (out.ops.isEmpty()) {
                lastError = "no ops, head=" + head(xml);
                return null;
            }
            lastError = "";
            return out;
        } catch (Throwable t) {
            lastError = t + " head=" + head(xml);
            return null;
        }
    }

    private static String head(String xml) {
        if (xml == null) {
            return "null";
        }
        String s = xml.replace('\n', ' ').trim();
        return s.length() > 70 ? s.substring(0, 70) : s;
    }

    // ---------------- XML 读取 ----------------

    private static VecDrawable read(XmlPullParser p, float density) throws Exception {
        VecDrawable d = null;
        List<Matrix> stack = new ArrayList<>();
        Matrix cur = new Matrix();
        int e = p.getEventType();
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG) {
                String name = p.getName();
                if (name.equals("vector") && d == null) {
                    d = new VecDrawable(
                            num(attr(p, "viewportWidth"), 24f),
                            num(attr(p, "viewportHeight"), 24f),
                            Math.round(num(attr(p, "width"), 24f) * density),
                            Math.round(num(attr(p, "height"), 24f) * density));
                    stack.add(cur);
                } else if (d != null && name.equals("group")) {
                    cur = group(cur, attr(p, "translateX"), attr(p, "translateY"),
                            attr(p, "rotation"), attr(p, "scaleX"), attr(p, "scaleY"),
                            attr(p, "pivotX"), attr(p, "pivotY"));
                    stack.add(cur);
                } else if (d != null && name.equals("path")) {
                    Path path = null;
                    try {
                        path = pathData(attr(p, "pathData"));
                    } catch (Throwable t) {
                        // 单条 path 坏了只丢这一条，不要连累整个图标
                        lastError = "bad pathData: " + t.getMessage();
                    }
                    if (path != null) {
                        path.transform(cur);
                        float alpha = num(attr(p, "fillAlpha"), 1f);
                        int fill = color(attr(p, "fillColor"), alpha);
                        float sAlpha = num(attr(p, "strokeAlpha"), 1f);
                        int stroke = color(attr(p, "strokeColor"), sAlpha);
                        float sw = num(attr(p, "strokeWidth"), 0f);
                        String ft = attr(p, "fillType");
                        if (ft != null && ft.contains("even")) {
                            path.setFillType(Path.FillType.EVEN_ODD);
                        }
                        d.ops.add(new Op(path, false, fill, stroke, sw,
                                cap(attr(p, "strokeLineCap")),
                                join(attr(p, "strokeLineJoin"))));
                        d.noteColor(fill);
                        d.noteColor(stroke);
                    }
                } else if (d != null && name.equals("clip-path")) {
                    Path path = null;
                    try {
                        path = pathData(attr(p, "pathData"));
                    } catch (Throwable t) {
                        lastError = "bad clip pathData: " + t.getMessage();
                    }
                    if (path != null) {
                        path.transform(cur);
                        d.ops.add(new Op(path, true, 0, 0, 0, null, null));
                    }
                }
            } else if (e == XmlPullParser.END_TAG) {
                String name = p.getName();
                if ((name.equals("vector") || name.equals("group")) && !stack.isEmpty()) {
                    stack.remove(stack.size() - 1);
                    cur = stack.isEmpty() ? new Matrix() : stack.get(stack.size() - 1);
                }
            }
            e = p.next();
        }
        return d;
    }

    /** group 的局部变换矩阵 = T(pivot) · R · S · T(-pivot) · T(translate)。 */
    private static Matrix group(Matrix parent, String tx, String ty, String rot,
                                String sx, String sy, String px, String py) {
        float pivotX = num(px, 0f);
        float pivotY = num(py, 0f);
        Matrix local = new Matrix();
        local.postTranslate(-pivotX, -pivotY);
        local.postScale(num(sx, 1f), num(sy, 1f));
        local.postRotate(num(rot, 0f));
        local.postTranslate(pivotX, pivotY);
        local.postTranslate(num(tx, 0f), num(ty, 0f));
        Matrix m = new Matrix(parent);
        m.preConcat(local);
        return m;
    }

    // ---------------- pathData 解析 ----------------

    /** Android pathData 语法（SVG path 子集）→ Path。 */
    private static Path pathData(String d) {
        if (d == null || d.trim().isEmpty()) {
            return null;
        }
        try {
            P s = new P(d);
            Path path = new Path();
            char cmd = 0;
            float cx = 0, cy = 0;
            float lastCx = 0, lastCy = 0;     // 上一个三次/二次控制点（S/T 反射用）
            float lastQx = 0, lastQy = 0;
            while (!s.eof()) {
                char c = s.peekCmd();
                if (c != 0) {
                    cmd = c;
                    s.i++;
                } else if (cmd == 0) {
                    break;
                } else if (cmd == 'M') {
                    cmd = 'L';   // M 后隐式重复按 L 处理
                }
                switch (cmd) {
                    case 'M': case 'm': {
                        float x = s.f(), y = s.f();
                        if (cmd == 'm') {
                            x += cx;
                            y += cy;
                        }
                        path.moveTo(x, y);
                        cx = x;
                        cy = y;
                        break;
                    }
                    case 'L': case 'l': {
                        float x = s.f(), y = s.f();
                        if (cmd == 'l') {
                            x += cx;
                            y += cy;
                        }
                        path.lineTo(x, y);
                        cx = x;
                        cy = y;
                        break;
                    }
                    case 'H': case 'h': {
                        float x = s.f();
                        if (cmd == 'h') {
                            x += cx;
                        }
                        path.lineTo(x, cy);
                        cx = x;
                        break;
                    }
                    case 'V': case 'v': {
                        float y = s.f();
                        if (cmd == 'v') {
                            y += cy;
                        }
                        path.lineTo(cx, y);
                        cy = y;
                        break;
                    }
                    case 'C': case 'c': {
                        float x1 = s.f(), y1 = s.f(), x2 = s.f(), y2 = s.f(),
                                x = s.f(), y = s.f();
                        if (cmd == 'c') {
                            x1 += cx; y1 += cy; x2 += cx; y2 += cy; x += cx; y += cy;
                        }
                        path.cubicTo(x1, y1, x2, y2, x, y);
                        lastCx = x2;
                        lastCy = y2;
                        cx = x;
                        cy = y;
                        break;
                    }
                    case 'S': case 's': {
                        float x2 = s.f(), y2 = s.f(), x = s.f(), y = s.f();
                        if (cmd == 's') {
                            x2 += cx; y2 += cy; x += cx; y += cy;
                        }
                        float x1 = 2 * cx - lastCx;
                        float y1 = 2 * cy - lastCy;
                        path.cubicTo(x1, y1, x2, y2, x, y);
                        lastCx = x2;
                        lastCy = y2;
                        cx = x;
                        cy = y;
                        break;
                    }
                    case 'Q': case 'q': {
                        float x1 = s.f(), y1 = s.f(), x = s.f(), y = s.f();
                        if (cmd == 'q') {
                            x1 += cx; y1 += cy; x += cx; y += cy;
                        }
                        path.quadTo(x1, y1, x, y);
                        lastQx = x1;
                        lastQy = y1;
                        cx = x;
                        cy = y;
                        break;
                    }
                    case 'T': case 't': {
                        float x = s.f(), y = s.f();
                        if (cmd == 't') {
                            x += cx; y += cy;
                        }
                        float x1 = 2 * cx - lastQx;
                        float y1 = 2 * cy - lastQy;
                        path.quadTo(x1, y1, x, y);
                        lastQx = x1;
                        lastQy = y1;
                        cx = x;
                        cy = y;
                        break;
                    }
                    case 'A': case 'a': {
                        float rx = s.f(), ry = s.f(), rot = s.f();
                        boolean large = s.f() != 0f;
                        boolean sweep = s.f() != 0f;
                        float x = s.f(), y = s.f();
                        if (cmd == 'a') {
                            x += cx; y += cy;
                        }
                        arcTo(path, cx, cy, rx, ry, rot, large, sweep, x, y);
                        cx = x;
                        cy = y;
                        break;
                    }
                    case 'Z': case 'z': {
                        path.close();
                        break;
                    }
                    default:
                        return null;   // 未知命令：整个文件放弃
                }
                // S 的反射控制点基于上一条 C/S；这里简化：非 C/S 时按当前点处理
                if (cmd != 'C' && cmd != 'c' && cmd != 'S' && cmd != 's') {
                    lastCx = cx;
                    lastCy = cy;
                }
                if (cmd != 'Q' && cmd != 'q' && cmd != 'T' && cmd != 't') {
                    lastQx = cx;
                    lastQy = cy;
                }
            }
            return path;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 椭圆弧 → 三段以内的三次贝塞尔（SVG 规范的端点参数化）。 */
    private static void arcTo(Path path, float x0, float y0,
                              float rx, float ry, float angleDeg,
                              boolean large, boolean sweep,
                              float x, float y) {
        if (x0 == x && y0 == y) {
            return;
        }
        rx = Math.abs(rx);
        ry = Math.abs(ry);
        if (rx == 0 || ry == 0) {
            path.lineTo(x, y);
            return;
        }
        double rad = Math.toRadians(angleDeg);
        double cosT = Math.cos(rad);
        double sinT = Math.sin(rad);
        double dx = (x0 - x) / 2.0;
        double dy = (y0 - y) / 2.0;
        double x1p = cosT * dx + sinT * dy;
        double y1p = -sinT * dx + cosT * dy;
        double lambda = x1p * x1p / (rx * rx) + y1p * y1p / (ry * ry);
        if (lambda > 1) {
            double k = Math.sqrt(lambda);
            rx *= k;
            ry *= k;
        }
        double num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p;
        double den = rx * rx * y1p * y1p + ry * ry * x1p * x1p;
        double co = Math.sqrt(Math.max(0, num / Math.max(den, 1e-12)));
        if (large == sweep) {
            co = -co;
        }
        double cxp = co * rx * y1p / ry;
        double cyp = -co * ry * x1p / rx;
        double ccx = cosT * cxp - sinT * cyp + (x0 + x) / 2.0;
        double ccy = sinT * cxp + cosT * cyp + (y0 + y) / 2.0;

        double ux = (x1p - cxp) / rx;
        double uy = (y1p - cyp) / ry;
        double vx = (-x1p - cxp) / rx;
        double vy = (-y1p - cyp) / ry;
        double a1 = Math.atan2(uy, ux);
        double a2 = Math.atan2(vy, vx);
        double da = a2 - a1;
        if (!sweep && da > 0) {
            da -= 2 * Math.PI;
        } else if (sweep && da < 0) {
            da += 2 * Math.PI;
        }
        int seg = (int) Math.ceil(Math.abs(da) / (Math.PI / 2));
        double delta = da / seg;
        double t = 4.0 / 3.0 * Math.tan(delta / 4.0);
        double a = a1;
        double px = pointX(ccx, rx, ry, cosT, sinT, Math.cos(a), Math.sin(a));
        double py = pointY(ccy, rx, ry, cosT, sinT, Math.cos(a), Math.sin(a));
        for (int i = 0; i < seg; i++) {
            double an = a + delta;
            double c1 = Math.cos(a), s1 = Math.sin(a);
            double c2 = Math.cos(an), s2 = Math.sin(an);
            double qx = pointX(ccx, rx, ry, cosT, sinT, c2, s2);
            double qy = pointY(ccy, rx, ry, cosT, sinT, c2, s2);
            // 椭圆空间里的导数映射回世界坐标
            double dpx1 = -rx * s1 * cosT - ry * c1 * sinT;
            double dpy1 = -rx * s1 * sinT + ry * c1 * cosT;
            double dpx2 = -rx * s2 * cosT - ry * c2 * sinT;
            double dpy2 = -rx * s2 * sinT + ry * c2 * cosT;
            path.cubicTo(
                    (float) (px + t * dpx1), (float) (py + t * dpy1),
                    (float) (qx - t * dpx2), (float) (qy - t * dpy2),
                    (float) qx, (float) qy);
            px = qx;
            py = qy;
            a = a2;
        }
    }

    private static double pointX(double cx, double rx, double ry,
                                 double cosT, double sinT, double c, double s) {
        return cx + rx * c * cosT - ry * s * sinT;
    }

    private static double pointY(double cy, double rx, double ry,
                                 double cosT, double sinT, double c, double s) {
        return cy + rx * c * sinT + ry * s * cosT;
    }

    /** pathData 数字读取器：支持隐式重复、连字符/逗号/换行分隔、"1.5.5" 拆分。 */
    private static final class P {
        final String s;
        int i;

        P(String s) {
            this.s = s;
        }

        boolean eof() {
            skip();
            return i >= s.length();
        }

        char peekCmd() {
            skip();
            if (i >= s.length()) {
                return 0;
            }
            char c = s.charAt(i);
            return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ? c : 0;
        }

        float f() {
            skip();
            int start = i;
            // 负号/正号：官方 pathData 里负数密集出现（如 c-0.13-0.18-0.2），
            // 不认的话整条 path 直接抛异常，整个图标就废了
            if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) {
                i++;
            }
            boolean dot = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c >= '0' && c <= '9') {
                    i++;
                } else if (c == '.' && !dot) {
                    dot = true;
                    i++;
                } else if ((c == 'e' || c == 'E') && i > start
                        && i + 1 < s.length()
                        && (Character.isDigit(s.charAt(i + 1))
                        || ((s.charAt(i + 1) == '+' || s.charAt(i + 1) == '-')
                        && i + 2 < s.length()
                        && Character.isDigit(s.charAt(i + 2))))) {
                    i += 2;
                } else {
                    break;
                }
            }
            if (i == start) {
                throw new NumberFormatException("bad pathData @" + i);
            }
            String tok = s.substring(start, i);
            if (tok.equals("-") || tok.equals("+") || tok.equals(".")) {
                throw new NumberFormatException("bad number '" + tok + "' @" + start);
            }
            return Float.parseFloat(tok);
        }

        private void skip() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == ',' || c == '\n' || c == '\r' || c == '\t') {
                    i++;
                } else {
                    break;
                }
            }
        }
    }

    // ---------------- 属性工具 ----------------

    private static String attr(XmlPullParser p, String name) {
        for (int i = 0; i < p.getAttributeCount(); i++) {
            String n = p.getAttributeName(i);
            if (n == null) {
                continue;
            }
            // 解析器可能开启也可能没开启命名空间处理：
            // 开启时是 "pathData"，没开启时是 "android:pathData"，两种都要认
            if (n.equals(name) || n.endsWith(":" + name)) {
                return p.getAttributeValue(i);
            }
        }
        return null;
    }

    private static float num(String v, float def) {
        if (v == null) {
            return def;
        }
        try {
            return Float.parseFloat(v.replaceAll("[a-zA-Z%]+$", "").trim());
        } catch (Throwable t) {
            return def;
        }
    }

    /** 尺寸字符串（"16.0dip"/"24dp"/裸数字）→ 数值（已由 num 处理单位后缀）。 */
    private static float strip(String v, float density) {
        return num(v, 24f);
    }

    /** 颜色：#rgb/#argb/#rrggbb/#aarrggbb；?attr/@ 引用/解析不了 → 白色 × alpha。 */
    private static int color(String v, float alpha) {
        int c = WHITE;
        if (v != null && v.startsWith("#")) {
            try {
                String h = v.substring(1);
                if (h.length() == 3) {
                    c = 0xFF000000 | (expand(h.substring(0, 1)) << 20)
                            | (expand(h.substring(1, 2)) << 12)
                            | (expand(h.substring(2, 3)) << 4);
                } else if (h.length() == 4) {
                    c = (expand(h.substring(0, 1)) << 28)
                            | (expand(h.substring(1, 2)) << 20)
                            | (expand(h.substring(2, 3)) << 12)
                            | (expand(h.substring(3, 4)) << 4);
                } else if (h.length() == 6) {
                    c = (int) (0xFF000000L | Long.parseLong(h, 16));
                } else if (h.length() == 8) {
                    c = (int) (Long.parseLong(h, 16));
                }
            } catch (Throwable ignored) {
                c = WHITE;
            }
        }
        if (alpha >= 1f) {
            return c;
        }
        if (alpha <= 0f) {
            return 0;
        }
        return (Math.round(Color.alpha(c) * alpha) << 24) | (c & 0xFFFFFF);
    }

    private static int expand(String h) {
        return Integer.parseInt(h, 16) * 17;
    }

    private static Paint.Cap cap(String v) {
        if (v == null) {
            return Paint.Cap.BUTT;
        }
        if (v.contains("round")) {
            return Paint.Cap.ROUND;
        }
        if (v.contains("square")) {
            return Paint.Cap.SQUARE;
        }
        return Paint.Cap.BUTT;
    }

    private static Paint.Join join(String v) {
        if (v == null) {
            return Paint.Join.MITER;
        }
        if (v.contains("round")) {
            return Paint.Join.ROUND;
        }
        if (v.contains("bevel")) {
            return Paint.Join.BEVEL;
        }
        return Paint.Join.MITER;
    }

    // ---------------- 绘制 ----------------

    /** 所有绘制操作里最大的路径包围盒（viewport 单位），用来判断"形状本身有没有铺满 viewBox"。 */
    private String pathBox() {
        android.graphics.RectF t = new android.graphics.RectF();
        android.graphics.RectF r = new android.graphics.RectF();
        boolean any = false;
        for (Op op : ops) {
            if (op.clip) {
                continue;
            }
            op.path.computeBounds(r, true);
            if (!any) {
                t.set(r);
                any = true;
            } else {
                t.union(r);
            }
        }
        return any ? String.format(java.util.Locale.US, "%.2f,%.2f~%.2f,%.2f",
                t.left, t.top, t.right, t.bottom) : "-";
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty() || vw <= 0 || vh <= 0) {
            return;
        }
        int save = canvas.save();
        if (name.contains("battery") || DBG_BATT.getAndIncrement() < 40) {
            android.util.Log.e("hide5G", "batt vec " + name
                    + " @" + System.identityHashCode(this)
                    + " bounds=" + b.toShortString() + " " + b.width() + "x" + b.height()
                    + " intrinsic=" + iw + "x" + ih + " vp=" + vw + "x" + vh
                    + " lvl=" + getLevel() + " alpha=" + baseAlpha
                    + " clip=" + canvas.getClipBounds().toShortString()
                    + " path=" + pathBox()
                    + " cf=" + (externalFilter != null ? "ext"
                    : (tintFilter != null ? "tint" : "none")));
        }
        canvas.translate(b.left, b.top);
        canvas.scale(b.width() / vw, b.height() / vh);
        ColorFilter cf = externalFilter != null ? externalFilter : tintFilter;
        if (b.width() > 90 && DBG_DRAW.getAndIncrement() < 30) {
            android.util.Log.d("hide5G", "vec draw vw=" + vw + "x" + vh
                    + " bounds=" + b.width() + "x" + b.height()
                    + " a=" + baseAlpha
                    + " cf=" + (externalFilter != null ? "ext" : (tintFilter != null ? "tint" : "none"))
                    + " tint=" + Integer.toHexString(tintColor)
                    + " ops=" + ops.size()
                    + " c0=" + (ops.isEmpty() ? "-" : Integer.toHexString(ops.get(0).fillColor)));
        }
        for (Op op : ops) {
            if (op.clip) {
                canvas.clipPath(op.path);
                continue;
            }
            if (op.fillColor != 0) {
                fillPaint.setColor(scaleAlpha(op.fillColor));
                fillPaint.setColorFilter(cf);
                canvas.drawPath(op.path, fillPaint);
            }
            if (op.strokeColor != 0 && op.strokeWidth > 0) {
                strokePaint.setColor(scaleAlpha(op.strokeColor));
                strokePaint.setColorFilter(cf);
                strokePaint.setStrokeWidth(op.strokeWidth);
                strokePaint.setStrokeCap(op.cap);
                strokePaint.setStrokeJoin(op.join);
                canvas.drawPath(op.path, strokePaint);
            }
        }
        canvas.restoreToCount(save);
    }

    /** setAlpha 的 alpha 与颜色自带 alpha 相乘（Paint.setAlpha 是替换不是相乘）。 */
    private int scaleAlpha(int color) {
        int a = Color.alpha(color);
        if (baseAlpha >= 255 || a == 0) {
            return color;
        }
        return (Math.round(a * baseAlpha / 255f) << 24) | (color & 0xFFFFFF);
    }

    @Override
    public void setAlpha(int alpha) {
        baseAlpha = alpha;
        invalidateSelf();
    }

    /** 基类的 getAlpha 只回自己那份，这里回真正参与绘制的 baseAlpha。 */
    @Override
    public int getAlpha() {
        return baseAlpha;
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        // 以前直接忽略，结果 StatusBarIconView 这类用 setColorFilter 上色的地方
        // 染不上，图标就停在 XML 里的原色（白底白图）。现在老老实实记下来。
        externalFilter = cf;
        invalidateSelf();
    }

    @Override
    public ColorFilter getColorFilter() {
        return externalFilter;
    }

    @Override
    public void setTintList(android.content.res.ColorStateList tint) {
        // 系统在不该着色时会给 null，这时保留我们自己设的颜色，别清成白的
        if (tint == null) {
            return;
        }
        tintList = tint;
        tintColor = tint.getColorForState(getState(), tint.getDefaultColor());
        tintFilter = new android.graphics.PorterDuffColorFilter(tintColor,
                android.graphics.PorterDuff.Mode.SRC_IN);
        invalidateSelf();
    }

    @Override
    public void setTint(int tintColor) {
        setTintList(android.content.res.ColorStateList.valueOf(tintColor));
    }

    @Override
    public boolean isStateful() {
        return tintList != null && tintList.isStateful();
    }

    @Override
    protected boolean onStateChange(int[] state) {
        if (tintList == null) {
            return false;
        }
        int c = tintList.getColorForState(state, tintList.getDefaultColor());
        if (c != tintColor) {
            tintColor = c;
            tintFilter = new android.graphics.PorterDuffColorFilter(c,
                    android.graphics.PorterDuff.Mode.SRC_IN);
            return true;
        }
        return false;
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return iw;
    }

    @Override
    public int getIntrinsicHeight() {
        return ih;
    }

    @Override
    public void setBounds(int l, int t, int r, int b) {
        super.setBounds(l, t, r, b);
        if (name.contains("battery")) {
            String from = "?";
            StackTraceElement[] st = new StackTraceElement[0];
            try {
                st = new Throwable().getStackTrace();
                if (st.length > 2) {
                    from = st[1].getClassName() + "." + st[1].getMethodName()
                            + ":" + st[1].getLineNumber();
                }
            } catch (Throwable ignored) {
                // ignore
            }
            android.util.Log.e("hide5G", "batt setBounds " + name + " @"
                    + System.identityHashCode(this) + " -> " + (r - l) + "x" + (b - t)
                    + "@" + l + "," + t + " from " + from);
        }
    }
}
