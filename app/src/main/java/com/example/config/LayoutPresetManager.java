package com.example.config;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Manages layout presets, draggable element coordinates, sizes, layer Z-order, and lock flags.
 * Persists configuration reliably across app restarts via SharedPreferences.
 */
public class LayoutPresetManager {
    private static final String PREF_NAME = "mirrorwire_layout_presets";
    private static final String KEY_CURRENT_PRESET = "current_preset";
    private static final String KEY_LAYOUT_DATA = "layout_data";

    public enum PresetType {
        DEFAULT_PIP("Default (PIP Camera)"),
        SPLIT_SCREEN("Split Screen"),
        DISPLAY_ONLY("Display Only (Fullscreen)"),
        CUSTOM("Custom Layout");

        private final String label;
        PresetType(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    public static class LayoutElement {
        public String id;
        public float xPercent;      // 0.0 to 1.0 relative to parent container
        public float yPercent;      // 0.0 to 1.0
        public float widthPercent;  // 0.0 to 1.0
        public float heightPercent; // 0.0 to 1.0
        public int zOrder;          // Higher = in front
        public boolean isLocked;
        public boolean isVisible;

        public LayoutElement(String id, float x, float y, float w, float h, int z, boolean locked, boolean visible) {
            this.id = id;
            this.xPercent = x;
            this.yPercent = y;
            this.widthPercent = w;
            this.heightPercent = h;
            this.zOrder = z;
            this.isLocked = locked;
            this.isVisible = visible;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject obj = new JSONObject();
            obj.put("id", id);
            obj.put("x", xPercent);
            obj.put("y", yPercent);
            obj.put("w", widthPercent);
            obj.put("h", heightPercent);
            obj.put("z", zOrder);
            obj.put("locked", isLocked);
            obj.put("visible", isVisible);
            return obj;
        }

        public static LayoutElement fromJson(JSONObject obj) throws JSONException {
            return new LayoutElement(
                    obj.getString("id"),
                    (float) obj.getDouble("x"),
                    (float) obj.getDouble("y"),
                    (float) obj.getDouble("w"),
                    (float) obj.getDouble("h"),
                    obj.getInt("z"),
                    obj.getBoolean("locked"),
                    obj.getBoolean("visible")
            );
        }
    }

    private final SharedPreferences mPrefs;
    private PresetType mCurrentPreset = PresetType.DEFAULT_PIP;

    private LayoutElement mDisplayElement;
    private LayoutElement mCameraElement;

    public LayoutPresetManager(Context context) {
        mPrefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        initDefaults();
        load();
    }

    private void initDefaults() {
        // Display full screen by default
        mDisplayElement = new LayoutElement("display", 0f, 0f, 1.0f, 1.0f, 0, true, true);
        // Camera PIP at top-right
        mCameraElement = new LayoutElement("camera", 0.65f, 0.03f, 0.32f, 0.28f, 1, false, true);
    }

    public void applyPreset(PresetType preset) {
        mCurrentPreset = preset;
        switch (preset) {
            case DEFAULT_PIP:
                mDisplayElement = new LayoutElement("display", 0f, 0f, 1.0f, 1.0f, 0, true, true);
                mCameraElement = new LayoutElement("camera", 0.65f, 0.03f, 0.32f, 0.28f, 1, false, true);
                break;
            case SPLIT_SCREEN:
                mDisplayElement = new LayoutElement("display", 0f, 0f, 1.0f, 0.55f, 0, false, true);
                mCameraElement = new LayoutElement("camera", 0f, 0.55f, 1.0f, 0.45f, 1, false, true);
                break;
            case DISPLAY_ONLY:
                mDisplayElement = new LayoutElement("display", 0f, 0f, 1.0f, 1.0f, 0, true, true);
                mCameraElement = new LayoutElement("camera", 0.65f, 0.03f, 0.32f, 0.28f, 1, false, false);
                break;
            case CUSTOM:
                // Keep current elements
                break;
        }
        save();
    }

    public void save() {
        try {
            JSONObject root = new JSONObject();
            root.put("preset", mCurrentPreset.name());
            root.put("display", mDisplayElement.toJson());
            root.put("camera", mCameraElement.toJson());

            mPrefs.edit()
                    .putString(KEY_CURRENT_PRESET, mCurrentPreset.name())
                    .putString(KEY_LAYOUT_DATA, root.toString())
                    .apply();
        } catch (JSONException ignored) {}
    }

    public void load() {
        String dataStr = mPrefs.getString(KEY_LAYOUT_DATA, null);
        if (dataStr != null) {
            try {
                JSONObject root = new JSONObject(dataStr);
                String presetName = root.optString("preset", PresetType.DEFAULT_PIP.name());
                mCurrentPreset = PresetType.valueOf(presetName);
                if (root.has("display")) {
                    mDisplayElement = LayoutElement.fromJson(root.getJSONObject("display"));
                }
                if (root.has("camera")) {
                    mCameraElement = LayoutElement.fromJson(root.getJSONObject("camera"));
                }
            } catch (Exception e) {
                initDefaults();
            }
        } else {
            initDefaults();
        }
    }

    public PresetType getCurrentPreset() { return mCurrentPreset; }
    public void setCurrentPreset(PresetType preset) { mCurrentPreset = preset; }

    public LayoutElement getDisplayElement() { return mDisplayElement; }
    public LayoutElement getCameraElement() { return mCameraElement; }
}
