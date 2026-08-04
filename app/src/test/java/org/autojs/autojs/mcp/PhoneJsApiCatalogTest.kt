package org.autojs.autojs.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneJsApiCatalogTest {

    @Test
    fun catalogMapsCommonApisToPreferredMcpTools() {
        val clipboard = PhoneJsApiCatalog.query("clipboard", null)
        assertEquals(listOf("getClip", "setClip"), clipboard.map { it.get("api").asString })
        assertEquals("phone_get_clipboard", clipboard.first().get("preferred_tool").asString)

        val vibrate = PhoneJsApiCatalog.query(null, "VIBRATE").single()
        assertEquals("device.vibrate", vibrate.get("api").asString)
        assertEquals("phone_vibrate", vibrate.get("preferred_tool").asString)

        val audio = PhoneJsApiCatalog.query("audio", "setMusicVolume").single()
        assertEquals("phone_audio_control", audio.get("preferred_tool").asString)

        val wake = PhoneJsApiCatalog.query("device", "keepScreenOn").single()
        assertEquals("phone_device_control", wake.get("preferred_tool").asString)
    }

    @Test
    fun catalogMarksRootOnlyKeyInjection() {
        val keyCode = PhoneJsApiCatalog.query("shell", "KeyCode").single()
        assertEquals("root", keyCode.get("requires").asString)
        assertTrue(PhoneJsApiCatalog.categories.contains("automation"))
    }
}
