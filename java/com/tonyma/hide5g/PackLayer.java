package com.tonyma.hide5g;

import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.Log;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.StringReader;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 图标收拢（pack）用的 layer-list 解析器。
 *
 * 系统把状态栏图标画成 layer-list 时，真正的可绘制对象包在 &lt;item&gt; 里，
 * 电池这类还会再套一层 &lt;clip&gt;。这里把那层 XML 拆成 Drawable，
 * 并用 ClipLevelDrawable 复刻 &lt;clip&gt; 按 level 从左/上填充的行为。
 */
final class PackLayer {

    /** 按名字去别家包里取图标的回调。 */
    interface IconResolver {
        Drawable load(String name);
    }

    private PackLayer() {
    }

    /**
     * 只有恰好一个 &lt;item&gt; 且能解析出 Drawable 时才接管；多层的图标形状复杂，交回系统画。
     */
    static Drawable parse(Resources res, String xml, IconResolver resolver) {
        if (xml == null) {
            return null;
        }
        if (!xml.contains("layer-list")) {
            return null;
        }
        try {
            XmlPullParserFactory f = XmlPullParserFactory.newInstance();
            f.setNamespaceAware(true);
            XmlPullParser p = f.newPullParser();
            p.setInput(new StringReader(xml));
            int ev = p.getEventType();
            int n = 0;
            Drawable last = null;
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && "item".equals(p.getName())) {
                    Drawable d = readItem(res, p, resolver);
                    if (d != null) {
                        n++;
                        last = d;
                    }
                }
                ev = p.next();
            }
            return n == 1 ? last : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 读一个 &lt;item&gt; 子树。item 自己带 drawable 就用它，
     * 里面再出现 &lt;clip&gt; 时用 clip 包一层（level 决定露出多少）。
     */
    private static Drawable readItem(Resources res, XmlPullParser p, IconResolver resolver)
            throws Exception {
        String dr = attr(p, "drawable");
        int depth = p.getDepth();
        Drawable out = dr == null ? null : resolve(dr, resolver);
        while (true) {
            int ev = p.next();
            if (ev == XmlPullParser.END_DOCUMENT) {
                return out;
            }
            if (ev == XmlPullParser.END_TAG && p.getDepth() == depth) {
                return out;
            }
            if (ev != XmlPullParser.START_TAG) {
                continue;
            }
            if (!"clip".equals(p.getName())) {
                continue;
            }
            Drawable inner = resolve(attr(p, "drawable"), resolver);
            if (inner == null) {
                continue;
            }
            out = new ClipLevelDrawable(inner,
                    !"vertical".equals(orientation(attr(p, "clipOrientation"))));
        }
    }

    /** 只认 "@pkg:type/name" 形式；无名可取时返回 null，让上层放弃接管。 */
    private static Drawable resolve(String name, IconResolver resolver) {
        if (name == null || resolver == null) {
            return null;
        }
        int slash = name.lastIndexOf('/');
        if (slash < 0) {
            return null;
        }
        if (slash == name.length() - 1) {
            return null;
        }
        name = name.substring(slash + 1).trim();
        if (name.isEmpty()) {
            return null;
        }
        return resolver.load(name);
    }

    /** android: 命名空间优先，取不到再按裸属性名取一次。 */
    private static String attr(XmlPullParser p, String name) {
        String v = p.getAttributeValue("http://schemas.android.com/apk/res/android", name);
        return v != null ? v : p.getAttributeValue(null, name);
    }

    /** 把 "horizontal"/"vertical"/"0x1"/"0x2"/"1"/"2" 归一成前两种字面量。 */
    private static String orientation(String s) {
        if (s == null) {
            return "";
        }
        s = s.trim().toLowerCase();
        if ("0x1".equals(s) || (s.endsWith("1") && s.length() <= 3)) {
            return "horizontal";
        }
        if ("0x2".equals(s) || (s.endsWith("2") && s.length() <= 3)) {
            return "vertical";
        }
        return s;
    }

    /**
     * &lt;clip&gt; 的替身：level 在 (0, 10000) 之间时按水平方向裁剪，
     * 其余（0 或满格）直接原样画，省掉一次 canvas save。
     */
    static final class ClipLevelDrawable extends Drawable {
        private static final AtomicInteger DBG = new AtomicInteger();

        private final Drawable inner;
        private final boolean horizontal;

        ClipLevelDrawable(Drawable inner, boolean horizontal) {
            this.inner = inner;
            this.horizontal = horizontal;
            Rect b = inner.getBounds();
            if (b == null || b.isEmpty()) {
                setBounds(0, 0, inner.getIntrinsicWidth(), inner.getIntrinsicHeight());
            } else {
                setBounds(b);
            }
        }

        /** 每个视图要一份自己的实例，不能共享 level/状态。 */
        ClipLevelDrawable copy() {
            return new ClipLevelDrawable(VecDrawable.dup(inner), horizontal);
        }

        Drawable innerDrawable() {
            return inner;
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            if (b.isEmpty()) {
                return;
            }
            int level = getLevel();
            if (DBG.getAndIncrement() < 40) {
                Log.d("hide5G", "clip draw level=" + level + " bounds=" + b.width() + "x"
                        + b.height() + " alpha=" + Integer.toHexString(inner.getAlpha())
                        + " inner=" + inner.getClass().getSimpleName());
            }
            if (level <= 0 || level >= 10000) {
                inner.setBounds(b);
                inner.draw(c);
                return;
            }
            int save = c.save();
            float frac = level / 10000f;
            if (horizontal) {
                c.clipRect(b.left, b.top, b.left + (int) (b.width() * frac), b.bottom);
            } else {
                c.clipRect(b.left, b.top, b.right, b.bottom - (int) (b.height() * frac));
            }
            inner.setBounds(b);
            inner.draw(c);
            c.restoreToCount(save);
        }

        @Override
        protected boolean onLevelChange(int level) {
            invalidateSelf();
            return true;
        }

        @Override
        public void setAlpha(int a) {
            inner.setAlpha(a);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            inner.setColorFilter(cf);
            invalidateSelf();
        }

        @Override
        public void setTint(int color) {
            inner.setTint(color);
            invalidateSelf();
        }

        @Override
        public void setTintList(ColorStateList tint) {
            inner.setTintList(tint);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return inner.getIntrinsicWidth();
        }

        @Override
        public int getIntrinsicHeight() {
            return inner.getIntrinsicHeight();
        }

        @Override
        public boolean isStateful() {
            return inner.isStateful();
        }

        @Override
        protected boolean onStateChange(int[] states) {
            return inner.setState(states);
        }
    }
}
