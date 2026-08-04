package org.autojs.autojs.mcp

import com.google.gson.JsonObject

internal object PhoneToolArgumentValidator {
    fun validate(schema: JsonObject, args: JsonObject) {
        val properties = schema.getAsJsonObject("properties") ?: JsonObject()
        val unknown = args.keySet().firstOrNull { !properties.has(it) }
        if (unknown != null) {
            throw PhoneToolException("INVALID_ARGUMENT", "Unknown argument: $unknown", false)
        }
        schema.getAsJsonArray("required")?.forEach { required ->
            val name = required.asString
            if (!args.has(name) || args.get(name).isJsonNull) {
                throw PhoneToolException("INVALID_ARGUMENT", "Missing required argument: $name", false)
            }
        }
        args.entrySet().forEach { (name, value) ->
            if (value.isJsonNull) return@forEach
            val field = properties.getAsJsonObject(name)
            val expected = field.get("type")?.asString
            val validType = when (expected) {
                "string" -> value.isJsonPrimitive && value.asJsonPrimitive.isString
                "boolean" -> value.isJsonPrimitive && value.asJsonPrimitive.isBoolean
                "integer" -> value.isJsonPrimitive && value.asJsonPrimitive.isNumber && runCatching {
                    value.asBigDecimal.stripTrailingZeros().scale() <= 0
                }.getOrDefault(false)
                "number" -> value.isJsonPrimitive && value.asJsonPrimitive.isNumber
                "array" -> value.isJsonArray
                "object" -> value.isJsonObject
                else -> true
            }
            if (!validType) {
                throw PhoneToolException("INVALID_ARGUMENT", "Argument '$name' must be $expected", false)
            }
            field.getAsJsonArray("enum")?.let { allowed ->
                if (allowed.none { it == value }) {
                    throw PhoneToolException(
                        "INVALID_ARGUMENT",
                        "Argument '$name' must be one of ${allowed.joinToString { it.asString }}",
                        false,
                    )
                }
            }
            if (value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
                val number = value.asDouble
                field.get("minimum")?.asDouble?.let {
                    if (number < it) throw PhoneToolException("INVALID_ARGUMENT", "Argument '$name' must be at least $it", false)
                }
                field.get("maximum")?.asDouble?.let {
                    if (number > it) throw PhoneToolException("INVALID_ARGUMENT", "Argument '$name' must be at most $it", false)
                }
            }
        }
    }
}
