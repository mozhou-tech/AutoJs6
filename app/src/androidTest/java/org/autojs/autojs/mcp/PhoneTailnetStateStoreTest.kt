package org.autojs.autojs.mcp

import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class PhoneTailnetStateStoreTest {

    @Test
    fun stateIsEncryptedAndSurvivesStoreRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = "instrumentation/${UUID.randomUUID()}"
        val plaintext = "private-tailnet-node-state"
        val encoded = Base64.encodeToString(plaintext.toByteArray(), Base64.NO_WRAP)
        val store = PhoneTailnetStateStore(context)

        assertEquals("", store.write(key, encoded))
        val stateFile = File(
            File(context.noBackupFilesDir, "phone-tailnet-state"),
            sha256(key) + ".state",
        )
        assertTrue(stateFile.isFile)
        val persisted = stateFile.readText()
        assertFalse(persisted.contains(plaintext))
        assertFalse(persisted.contains(encoded))

        val result = JsonParser.parseString(PhoneTailnetStateStore(context).read(key)).asJsonObject
        assertTrue(result.get("found").asBoolean)
        assertEquals(encoded, result.get("value").asString)

        assertEquals("", store.delete(key))
        assertFalse(stateFile.exists())
        assertFalse(JsonParser.parseString(store.read(key)).asJsonObject.get("found").asBoolean)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
