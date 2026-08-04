package org.autojs.autojs.mcp

import com.google.gson.JsonArray

internal object PhoneJsApiBridge {

    const val RESULT_SINK_ARGUMENT = "__phoneMcpResultSink"

    private val segmentPattern = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
    private val forbiddenSegments = setOf("__proto__", "prototype", "constructor")

    fun buildScript(api: String, arguments: JsonArray, resultMode: String): String {
        val segments = validatePath(api)
        val encodedSegments = JsonArray().apply { segments.forEach(::add) }
        val encodedMode = when (resultMode) {
            "string", "discard" -> resultMode
            else -> "json"
        }
        return """
            (function () {
                var resultSink = engines.myEngine().execArgv['$RESULT_SINK_ARGUMENT'];
                var envelope;
                try {
                    var segments = $encodedSegments;
                    var args = $arguments;
                    var owner = this;
                    var fn = owner;
                    for (var i = 0; i < segments.length; i++) {
                        owner = fn;
                        fn = fn[segments[i]];
                    }
                    if (typeof fn !== 'function') {
                        throw new Error('$api is not a callable AutoJs6 API');
                    }
                    var result = fn.apply(owner, args);
                    var resultType = typeof result;
                    var value = null;
                    var serialization = '$encodedMode';
                    if ('$encodedMode' === 'string') {
                        value = String(result);
                    } else if ('$encodedMode' === 'json' && resultType !== 'undefined') {
                        try {
                            var encoded = JSON.stringify(result);
                            if (typeof encoded === 'string') {
                                value = JSON.parse(encoded);
                            } else {
                                value = String(result);
                                serialization = 'string';
                            }
                        } catch (serializationError) {
                            value = String(result);
                            serialization = 'string';
                        }
                    }
                    envelope = {
                        ok: true,
                        result_type: resultType,
                        serialization: serialization,
                        result: value
                    };
                } catch (error) {
                    envelope = {
                        ok: false,
                        error: String(error && error.message ? error.message : error)
                    };
                }
                resultSink.set(JSON.stringify(envelope));
            })();
        """.trimIndent()
    }

    fun validatePath(api: String): List<String> {
        if (api.length !in 1..128) {
            throw PhoneToolException("INVALID_ARGUMENT", "JavaScript API path must contain 1 to 128 characters", false)
        }
        return api.split('.').also { segments ->
            if (segments.any { !segmentPattern.matches(it) || it in forbiddenSegments }) {
                throw PhoneToolException("INVALID_ARGUMENT", "JavaScript API path contains an unsafe segment", false)
            }
        }
    }
}
