package org.autojs.autojs.mcp

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import com.google.gson.JsonObject
import org.autojs.autojs.apkbuilder.keystore.AESUtils
import phonetailnet.EncryptedStateStore
import java.io.File
import java.security.MessageDigest

class PhoneTailnetStateStore(context: Context) : EncryptedStateStore {

    private val directory = File(context.noBackupFilesDir, DIRECTORY_NAME)
    private val lock = Any()

    override fun read(key: String): String = synchronized(lock) {
        val file = stateFile(key)
        if (!file.exists()) return@synchronized result(found = false)
        runCatching {
            val encrypted = AtomicFile(file).readFully().toString(Charsets.UTF_8)
            val plaintextBase64 = AESUtils.decrypt(encrypted)
            Base64.decode(plaintextBase64, Base64.NO_WRAP)
            result(found = true, value = plaintextBase64)
        }.getOrElse { result(error = errorCode(it)) }
    }

    override fun write(key: String, valueBase64: String): String = synchronized(lock) {
        runCatching {
            Base64.decode(valueBase64, Base64.NO_WRAP)
            if (!directory.exists() && !directory.mkdirs()) error("state directory could not be created")
            val atomic = AtomicFile(stateFile(key))
            val stream = atomic.startWrite()
            try {
                stream.write(AESUtils.encrypt(valueBase64).toByteArray(Charsets.UTF_8))
                atomic.finishWrite(stream)
            } catch (error: Throwable) {
                atomic.failWrite(stream)
                throw error
            }
        }.exceptionOrNull()?.let(::errorCode).orEmpty()
    }

    override fun delete(key: String): String = synchronized(lock) {
        runCatching { AtomicFile(stateFile(key)).delete() }.exceptionOrNull()?.let(::errorCode).orEmpty()
    }

    private fun stateFile(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") { "%02x".format(it) }
        return File(directory, "$name.state")
    }

    private fun result(found: Boolean = false, value: String? = null, error: String? = null) =
        JsonObject().apply {
            addProperty("found", found)
            value?.let { addProperty("value", it) }
            error?.let { addProperty("error", it) }
        }.toString()

    private fun errorCode(error: Throwable): String = when (error) {
        is IllegalArgumentException -> "INVALID_STATE_DATA"
        else -> "ANDROID_KEYSTORE_FAILURE"
    }

    companion object {
        private const val DIRECTORY_NAME = "phone-tailnet-state"

        fun clear(context: Context): Boolean =
            File(context.noBackupFilesDir, DIRECTORY_NAME).deleteRecursively() &&
                File(context.noBackupFilesDir, "phone-tailnet").deleteRecursively()
    }
}
