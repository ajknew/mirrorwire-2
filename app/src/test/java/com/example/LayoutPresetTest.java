package com.example;

import com.example.config.LayoutPresetManager;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests verifying LayoutElement serialization, coordinates, and lock status.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class LayoutPresetTest {

    @Test
    public void testLayoutElementJsonSerialization() throws Exception {
        LayoutPresetManager.LayoutElement element = new LayoutPresetManager.LayoutElement(
                "camera", 0.65f, 0.05f, 0.30f, 0.25f, 1, true, true);

        JSONObject json = element.toJson();
        assertEquals("camera", json.getString("id"));
        assertEquals(0.65, json.getDouble("x"), 0.001);
        assertEquals(0.05, json.getDouble("y"), 0.001);
        assertEquals(0.30, json.getDouble("w"), 0.001);
        assertEquals(0.25, json.getDouble("h"), 0.001);
        assertEquals(1, json.getInt("z"));
        assertTrue(json.getBoolean("locked"));
        assertTrue(json.getBoolean("visible"));

        LayoutPresetManager.LayoutElement restored = LayoutPresetManager.LayoutElement.fromJson(json);
        assertEquals(element.id, restored.id);
        assertEquals(element.xPercent, restored.xPercent, 0.001f);
        assertEquals(element.yPercent, restored.yPercent, 0.001f);
        assertEquals(element.widthPercent, restored.widthPercent, 0.001f);
        assertEquals(element.heightPercent, restored.heightPercent, 0.001f);
        assertEquals(element.zOrder, restored.zOrder);
        assertEquals(element.isLocked, restored.isLocked);
        assertEquals(element.isVisible, restored.isVisible);
    }
}
