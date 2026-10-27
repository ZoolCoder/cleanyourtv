package com.zoolcoder.tv.calendar;

import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** SharedPreferences in memory, for JVM tests of classes that keep their state there. */
final class FakePrefs implements SharedPreferences {
    final Map<String, Object> values = new HashMap<>();

    @Override
    public Map<String, ?> getAll() {
        return new HashMap<>(values);
    }

    @Override
    public String getString(String key, String fallback) {
        Object v = values.get(key);
        return v == null ? fallback : (String) v;
    }

    @Override
    public Set<String> getStringSet(String key, Set<String> fallback) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getInt(String key, int fallback) {
        Object v = values.get(key);
        return v == null ? fallback : (Integer) v;
    }

    @Override
    public long getLong(String key, long fallback) {
        Object v = values.get(key);
        return v == null ? fallback : (Long) v;
    }

    @Override
    public float getFloat(String key, float fallback) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean getBoolean(String key, boolean fallback) {
        Object v = values.get(key);
        return v == null ? fallback : (Boolean) v;
    }

    @Override
    public boolean contains(String key) {
        return values.containsKey(key);
    }

    @Override
    public Editor edit() {
        return new Editor() {
            final Map<String, Object> changes = new HashMap<>();
            boolean clear;

            @Override
            public Editor putString(String key, String value) {
                changes.put(key, value);
                return this;
            }

            @Override
            public Editor putStringSet(String key, Set<String> value) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Editor putInt(String key, int value) {
                changes.put(key, value);
                return this;
            }

            @Override
            public Editor putLong(String key, long value) {
                changes.put(key, value);
                return this;
            }

            @Override
            public Editor putFloat(String key, float value) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Editor putBoolean(String key, boolean value) {
                changes.put(key, value);
                return this;
            }

            @Override
            public Editor remove(String key) {
                changes.put(key, null);
                return this;
            }

            @Override
            public Editor clear() {
                clear = true;
                return this;
            }

            @Override
            public boolean commit() {
                if (clear) values.clear();
                for (Map.Entry<String, Object> e : changes.entrySet()) {
                    if (e.getValue() == null) values.remove(e.getKey());
                    else values.put(e.getKey(), e.getValue());
                }
                return true;
            }

            @Override
            public void apply() {
                commit();
            }
        };
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
    }
}
