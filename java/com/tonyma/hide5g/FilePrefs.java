package com.tonyma.hide5g;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * 文件版 SharedPreferences。
 *
 * ColorOS 17 上系统 SharedPreferences 的 commit() 返回 true 但磁盘根本不落盘
 * （探针实测：新文件、改文件都消失），配置重启就丢。改用 filesDir 下的
 * Properties 文件自己存——普通文件写入在该设备上验证可靠。
 */
final class FilePrefs implements SharedPreferences {

    private static final HashMap<String, FilePrefs> INSTANCES = new HashMap<>();

    static synchronized FilePrefs get(Context ctx, String name) {
        FilePrefs p = INSTANCES.get(name);
        if (p == null) {
            p = new FilePrefs(ctx.getApplicationContext(), name);
            INSTANCES.put(name, p);
        }
        return p;
    }

    private final File file;
    private final Properties props = new Properties();

    private FilePrefs(Context ctx, String name) {
        file = new File(ctx.getFilesDir(), name + ".prefs");
        try {
            FileInputStream in = new FileInputStream(file);
            props.load(in);
            in.close();
        } catch (Throwable ignored) {
            // 首次运行没有文件，正常
        }
    }

    private String raw(String key) {
        return props.getProperty(key);
    }

    @Override
    public Map<String, ?> getAll() {
        HashMap<String, Object> out = new HashMap<>();
        for (String k : props.stringPropertyNames()) {
            out.put(k, props.getProperty(k));
        }
        return out;
    }

    @Override
    public String getString(String key, String defValue) {
        String v = raw(key);
        return v == null ? defValue : v;
    }

    @Override
    public Set<String> getStringSet(String key, Set<String> defValues) {
        return defValues;
    }

    @Override
    public int getInt(String key, int defValue) {
        String v = raw(key);
        if (v == null) {
            return defValue;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (Throwable t) {
            return defValue;
        }
    }

    @Override
    public long getLong(String key, long defValue) {
        String v = raw(key);
        if (v == null) {
            return defValue;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (Throwable t) {
            return defValue;
        }
    }

    @Override
    public float getFloat(String key, float defValue) {
        String v = raw(key);
        if (v == null) {
            return defValue;
        }
        try {
            return Float.parseFloat(v.trim());
        } catch (Throwable t) {
            return defValue;
        }
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        String v = raw(key);
        if (v == null) {
            return defValue;
        }
        return v.trim().equals("true") || v.trim().equals("1");
    }

    @Override
    public boolean contains(String key) {
        return props.containsKey(key);
    }

    @Override
    public Editor edit() {
        return new Ed();
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {
        // 不支持监听（模块用广播同步）
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(
            OnSharedPreferenceChangeListener l) {
        // 同上
    }

    private final class Ed implements Editor {
        private final HashMap<String, Object> put = new HashMap<>();
        private final HashSet<String> remove = new HashSet<>();
        private boolean clearAll;

        @Override
        public Editor putString(String key, String value) {
            put.put(key, value);
            return this;
        }

        @Override
        public Editor putStringSet(String key, Set<String> values) {
            return this;
        }

        @Override
        public Editor putInt(String key, int value) {
            put.put(key, value);
            return this;
        }

        @Override
        public Editor putLong(String key, long value) {
            put.put(key, value);
            return this;
        }

        @Override
        public Editor putFloat(String key, float value) {
            put.put(key, value);
            return this;
        }

        @Override
        public Editor putBoolean(String key, boolean value) {
            put.put(key, value);
            return this;
        }

        @Override
        public Editor remove(String key) {
            remove.add(key);
            return this;
        }

        @Override
        public Editor clear() {
            clearAll = true;
            return this;
        }

        @Override
        public boolean commit() {
            synchronized (FilePrefs.this) {
                applyToMemory();
                return saveFile();
            }
        }

        @Override
        public void apply() {
            commit();
        }

        private void applyToMemory() {
            if (clearAll) {
                props.clear();
            }
            for (String k : remove) {
                props.remove(k);
            }
            for (Map.Entry<String, Object> e : put.entrySet()) {
                props.setProperty(e.getKey(), String.valueOf(e.getValue()));
            }
        }

        private boolean saveFile() {
            try {
                File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
                FileOutputStream out = new FileOutputStream(tmp);
                props.store(out, "hide5g config");
                out.close();
                if (!tmp.renameTo(file)) {
                    // rename 失败兜底：直接写目标文件
                    FileOutputStream out2 = new FileOutputStream(file);
                    props.store(out2, "hide5g config");
                    out2.close();
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
                return true;
            } catch (Throwable t) {
                return false;
            }
        }
    }
}
