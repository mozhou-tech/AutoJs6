package org.autojs.autojs.mcp

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class PhoneToolArgumentValidatorTest {

    @Test
    fun sequenceSubtoolArgumentsUseTheAdvertisedSchema() {
        val spec = PhoneToolSpecs.all.single { it.name == "phone_ui_action" }
        val args = JsonObject().apply {
            addProperty("lease_id", "lease")
            addProperty("action", "click")
            addProperty("unexpected", true)
        }

        val error = try {
            PhoneToolArgumentValidator.validate(spec.inputSchema, args)
            fail("Expected invalid argument")
            null
        } catch (error: PhoneToolException) {
            error
        }

        assertEquals("INVALID_ARGUMENT", error?.code)
        assertEquals("Unknown argument: unexpected", error?.message)
    }
}
