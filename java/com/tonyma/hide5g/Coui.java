package com.tonyma.hide5g;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.util.Log;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.AbsSeekBar;
import android.widget.CompoundButton;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

/**
 * COUI 反射桥。
 *
 * ColorOS 的原生控件（胶囊开关、滑块）类和资源都打在 com.android.settings 里，
 * 第三方进程既 import 不到 com.coui.*，也不能拿自己的 Resources 去查它的资源 ID，
 * 唯一姿势是 createPackageContext 借它的 classLoader + resources，再反射构造。
 *
 * 借不到（非 ColorOS、Settings 被禁用、系统限制加载别人代码）就返回 null，
 * 由 MainActivity 整体退回自绘控件——设置页不能因为桥失败而打不开。
 */
public class Coui {

    private static final String TAG = "hide5G-coui";
    private static final String HOST = "com.android.settings";

    public static final String CLS_SWITCH = "com.coui.appcompat.couiswitch.COUISwitch";
    public static final String CLS_SEEKBAR = "com.coui.appcompat.seekbar.COUISeekBar";
    public static final String CLS_FAB =
            "com.coui.appcompat.floatingactionbutton.COUIFloatingButton";
    private static final String CLS_SEEK_LISTENER =
            "com.coui.appcompat.seekbar.COUISeekBar$OnSeekBarChangeListener";
    private static final String CLS_FAB_LISTENER =
            "com.coui.appcompat.floatingactionbutton.COUIFloatingButton$OnFloatingButtonClickListener";

    private static volatile Coui sInst;
    private static volatile boolean sTried;
    private boolean materialRegistered = false;

    /** 宿主的 Context（查资源表用它，用 themed 查恒为 0）。 */
    public final Context base;
    /** 带 Theme.COUI.Main 的 Context（构造控件、解析 couiColorXxx 用它）。 */
    public final Context themed;

    private Coui(Context base, Context themed) {
        this.base = base;
        this.themed = themed;
    }

    public static Coui get(Context c) {
        if (sTried) {
            return sInst;
        }
        synchronized (Coui.class) {
            if (!sTried) {
                sTried = true;
                sInst = build(c);
            }
            return sInst;
        }
    }

    private static Coui build(Context c) {
        try {
            Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
            final Context base = app.createPackageContext(HOST,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            Context cfg = base.createConfigurationContext(
                    new Configuration(base.getResources().getConfiguration()));
            boolean dark = (app.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            String style = dark ? "Theme.COUI.Main.Dark" : "Theme.COUI.Main";
            int styleId = base.getResources().getIdentifier(style, "style", HOST);
            // COUI 控件 attach 时会走 VibrateUtils.registerHapticObserver，它拿
            // getContext().getApplicationContext() 缓存全局 Context；而
            // createConfigurationContext 造出来的 Context 这个方法是返回 null 的，
            // 直接传下去就是一条 NPE 把整个设置页搞闪退。垫一层把它指回宿主 Context。
            Context cfgOwner = new ContextWrapper(cfg) {
                @Override
                public Context getApplicationContext() {
                    return base;
                }
            };
            Context themed = new ContextThemeWrapper(cfgOwner,
                    styleId != 0 ? styleId : android.R.style.Theme_DeviceDefault);
            Coui tk = new Coui(base, themed);
            Log.d(TAG, "ready style=" + styleId + " switch=" + tk.hasClass(CLS_SWITCH)
                    + " seekbar=" + tk.hasClass(CLS_SEEKBAR));
            return tk;
        } catch (Throwable t) {
            Log.d(TAG, "unavailable: " + t);
            return null;
        }
    }

    public boolean hasClass(String cls) {
        try {
            base.getClassLoader().loadClass(cls);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 反射构造 COUI 控件（构造参数传 themed，传 base 会解析不到 couiColorXxx）。 */
    public View newView(String cls) {
        try {
            Class<?> k = base.getClassLoader().loadClass(cls);
            return (View) k.getConstructor(Context.class).newInstance(themed);
        } catch (Throwable t) {
            Log.d(TAG, "newView " + cls + " failed: " + t);
            return null;
        }
    }

    public CompoundButton newSwitch() {
        View v = newView(CLS_SWITCH);
        return v instanceof CompoundButton ? (CompoundButton) v : null;
    }

    public AbsSeekBar newSeekBar() {
        View v = newView(CLS_SEEKBAR);
        return v instanceof AbsSeekBar ? (AbsSeekBar) v : null;
    }

    public interface OnSeek {
        void onProgressChanged(int progress, boolean fromUser);
    }

    /**
     * COUISeekBar 的监听器是它自家接口（不是 SeekBar.OnSeekBarChangeListener），
     * 编译期拿不到这个类，只能动态代理。挂上返回 true。
     */
    public boolean attachSeekListener(AbsSeekBar bar, final OnSeek cb) {
        try {
            ClassLoader cl = bar.getClass().getClassLoader();
            final Class<?> iface = cl.loadClass(CLS_SEEK_LISTENER);
            Object proxy = Proxy.newProxyInstance(cl, new Class<?>[]{iface},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object p, java.lang.reflect.Method m, Object[] args) {
                            if ("onProgressChanged".equals(m.getName()) && args != null && args.length >= 2) {
                                cb.onProgressChanged(((Integer) args[1]).intValue(),
                                        Boolean.TRUE.equals(args[2]));
                            }
                            return null;
                        }
                    });
            bar.getClass().getMethod("setOnSeekBarChangeListener", iface)
                    .invoke(bar, proxy);
            return true;
        } catch (Throwable t) {
            Log.d(TAG, "attachSeekListener failed: " + t);
            return false;
        }
    }

    /**
     * 注册 COUI 材质状态观察者。不注册则 STROKE/SPOTLIGHT/BLUR 静态开关恒为 false
     * → FAB 只显示纯色圆 + 阴影，缺白色描边与高光。flags=31 与系统 OPLUS 盒子一致。
     */
    public synchronized boolean registerMaterial() {
        if (materialRegistered) {
            return true;
        }
        try {
            Class<?> mgr = base.getClassLoader()
                    .loadClass("com.coui.appcompat.COUIMaterialStateManager");
            Object inst = mgr.getField("INSTANCE").get(null);
            android.content.Context appCtx =
                    getApplicationContextSafe();
            mgr.getMethod("registerMaterialStateObserver",
                    Context.class, int.class).invoke(inst, appCtx, 31);
            materialRegistered = true;
            Log.d(TAG, "material registered flags=31");
            probeMaterial();
            return true;
        } catch (Throwable t) {
            Log.d(TAG, "registerMaterial failed: " + t);
            return false;
        }
    }

    /** 只读探测 COUI 材质开关当前状态，便于确认高光/描边是否真的开启。 */
    public void probeMaterial() {
        try {
            Class<?> mgr = base.getClassLoader()
                    .loadClass("com.coui.appcompat.COUIMaterialStateManager");
            Object inst = mgr.getField("INSTANCE").get(null);
            Object stroke = mgr.getMethod("isStrokeEffectEnable").invoke(inst);
            Object blur = mgr.getMethod("isBlurEffectEnable").invoke(inst);
            Object spot = mgr.getMethod("isSpotLightEffectEnable").invoke(inst);
            Object caustic = mgr.getMethod("isCausticShadowEffectEnable").invoke(inst);
            Object feature = mgr.getMethod("isFeatureEnabled").invoke(inst);
            Log.d(TAG, "material probe: stroke=" + stroke + " blur=" + blur
                    + " spotLight=" + spot + " caustic=" + caustic + " feature=" + feature);
        } catch (Throwable t) {
            Log.d(TAG, "probeMaterial failed: " + t);
        }
    }

    /** 取一个非 null 的 app Context（Kotlin 非空参数传 null 会抛异常）。 */
    private Context getApplicationContextSafe() {
        Context ac = base.getApplicationContext();
        return ac != null ? ac : base;
    }

    /** 从宿主(Settings)资源表取 drawable；取不到返回 null。 */
    public android.graphics.drawable.Drawable drawable(String name) {
        try {
            int id = base.getResources().getIdentifier(name, "drawable", HOST);
            if (id == 0) {
                return null;
            }
            return base.getResources().getDrawable(id, themed.getTheme());
        } catch (Throwable t) {
            Log.d(TAG, "drawable " + name + " failed: " + t);
            return null;
        }
    }

    /**
     * 从任意系统包（如 com.android.systemui）的资源表取 drawable。
     * 用于取 Settings 里没有、但 SystemUI 有的图标（如 oplus_reboot）。
     * 填白以适配蓝色底。取不到返回 null。
     */
    public android.graphics.drawable.Drawable drawableFrom(String pkg, String name) {
        try {
            Context pc = base.createPackageContext(pkg,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            int id = pc.getResources().getIdentifier(name, "drawable", pkg);
            if (id == 0) {
                return null;
            }
            android.graphics.drawable.Drawable d = pc.getResources().getDrawable(id, pc.getTheme());
            if (d != null) {
                d = d.mutate();
                d.setTint(android.graphics.Color.WHITE);
            }
            return d;
        } catch (Throwable t) {
            Log.d(TAG, "drawableFrom " + pkg + "/" + name + " failed: " + t);
            return null;
        }
    }

    /**
     * 反射构造 COUI 原生彩色圆形浮动按钮（COUIFloatingButton）。
     * 顺序严格：先写 mButtonSize → 先 setMainFabDrawable(图标) → 再上色。
     * 返回 null 表示宿主没有该控件（非 ColorOS），调用方退回到自绘按钮。
     */
    public View newFab(String iconDrawableName, int bgColor, int sizeDp, final Runnable onClick) {
        return newFab(drawable(iconDrawableName), bgColor, sizeDp, onClick);
    }

    /** 用现成 Drawable 构造 FAB（图标需已填色）。 */
    public View newFab(android.graphics.drawable.Drawable icon, int bgColor, int sizeDp, final Runnable onClick) {
        try {
            Class<?> cls = base.getClassLoader().loadClass(CLS_FAB);
            View btn = (View) cls.getConstructor(Context.class).newInstance(themed);

            // ① 尺寸：反射写 mButtonSize（px），LayoutParams 会被 onMeasure 覆盖
            float density = themed.getResources().getDisplayMetrics().density;
            int sizePx = Math.round(sizeDp * density);
            try {
                java.lang.reflect.Field f = cls.getDeclaredField("mButtonSize");
                f.setAccessible(true);
                f.setInt(btn, sizePx);
            } catch (Throwable t) {
                Log.d(TAG, "mButtonSize 不可写: " + t);
            }

            // ② 图标（必须先于上色）
            if (icon != null) {
                try {
                    cls.getMethod("setMainFabDrawable",
                            android.graphics.drawable.Drawable.class).invoke(btn, icon);
                } catch (Throwable t) {
                    Log.d(TAG, "setMainFabDrawable failed: " + t);
                }
            }

            // ③ 上色（ColorStateList）
            try {
                android.content.res.ColorStateList csl =
                        android.content.res.ColorStateList.valueOf(bgColor);
                cls.getMethod("setMainFloatingButtonBackgroundColor",
                        android.content.res.ColorStateList.class).invoke(btn, csl);
            } catch (Throwable t) {
                Log.d(TAG, "setMainFloatingButtonBackgroundColor failed: " + t);
            }

            btn.setLayoutParams(new android.widget.FrameLayout.LayoutParams(sizePx, sizePx));

            // ④ 点击：COUI 监听器是接口，onClick() 无参，动态代理
            if (onClick != null) {
                try {
                    final Class<?> iface = base.getClassLoader().loadClass(CLS_FAB_LISTENER);
                    Object proxy = Proxy.newProxyInstance(iface.getClassLoader(),
                            new Class<?>[]{iface}, new InvocationHandler() {
                                @Override
                                public Object invoke(Object p, java.lang.reflect.Method m, Object[] a) {
                                    if ("onClick".equals(m.getName())) {
                                        onClick.run();
                                        return null;
                                    }
                                    if ("hashCode".equals(m.getName())) {
                                        return System.identityHashCode(p);
                                    }
                                    if ("equals".equals(m.getName())) {
                                        return p == (a != null && a.length > 0 ? a[0] : null);
                                    }
                                    if ("toString".equals(m.getName())) {
                                        return "Hide5GFabListener";
                                    }
                                    return null;
                                }
                            });
                    cls.getMethod("setFloatingButtonClickListener", iface).invoke(btn, proxy);
                } catch (Throwable t) {
                    Log.d(TAG, "setFloatingButtonClickListener failed: " + t);
                }
            }

            // ⑤ 强制开启每实例材质效果（描边/磨砂/高光/焦散阴影）。
            //    全局 registerMaterialStateObserver 只置静态开关；实例仍需 onMaterialStateChange
            //    才会真正把 mIsMaterialXxxEnabled 打开并 ensure 对应 drawable。
            forceFabMaterial(btn);
            return btn;
        } catch (Throwable t) {
            Log.d(TAG, "newFab failed: " + t);
            return null;
        }
    }

    /**
     * 直接对某个 FAB 实例调用 onMaterialStateChange(flag, true) 强制开启材质效果。
     * flag: 1=描边 2=磨砂 4=高光 8=焦散阴影。
     * 仅在 OplusView 材质版本（isMaterialVersion）为真时生效（ColorOS17 为真）。
     */
    public void forceFabMaterial(View btn) {
        try {
            Class<?> util = base.getClassLoader()
                    .loadClass("com.coui.appcompat.COUIEffectVersionUtil");
            Object mv = util.getMethod("isMaterialVersion").invoke(null);
            Log.d(TAG, "isMaterialVersion=" + mv);
            if (!Boolean.TRUE.equals(mv)) {
                return;
            }
            java.lang.reflect.Method om =
                    btn.getClass().getMethod("onMaterialStateChange", int.class, boolean.class);
            for (int flag : new int[]{1, 2, 4, 8}) {
                try {
                    om.invoke(btn, flag, true);
                    Log.d(TAG, "enable material flag=" + flag);
                } catch (Throwable e) {
                    Log.d(TAG, "enable material flag=" + flag + " failed: " + e);
                }
            }
        } catch (Throwable t) {
            Log.d(TAG, "forceFabMaterial failed: " + t);
        }
    }

    /** 解析 couiColorXxx 这类 attr 颜色；attr 必须走 themed 的 theme。 */
    public int color(String attrName, int def) {
        try {
            int id = base.getResources().getIdentifier(attrName, "attr", HOST);
            if (id == 0) {
                return def;
            }
            TypedValue tv = new TypedValue();
            if (!themed.getTheme().resolveAttribute(id, tv, true)) {
                return def;
            }
            int c = tv.resourceId != 0 ? themed.getColor(tv.resourceId) : tv.data;
            // 解析出全透明/零值说明这条 attr 其实是个 reference，宁可用默认色
            return (c & 0xFF000000) == 0 ? def : c;
        } catch (Throwable t) {
            return def;
        }
    }
}

