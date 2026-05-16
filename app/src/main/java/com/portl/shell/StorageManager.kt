package com.portl.shell

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import java.nio.charset.StandardCharsets

class StorageManager(context: Context) {
    private val preferences = context.getSharedPreferences(STORAGE_NAMESPACE, Context.MODE_PRIVATE)
    private val storageDirectory = File(context.filesDir, "storage")

    @Synchronized
    fun get(key: String): String? {
        val stored = preferences.getString(key, null) ?: return null
        if (!stored.startsWith(FILE_SENTINEL_PREFIX)) {
            return stored
        }

        val filePath = stored.removePrefix(FILE_SENTINEL_PREFIX)
        return try {
            val file = File(filePath)
            if (file.exists()) file.readText() else null
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun set(key: String, value: String): String {
        return try {
            val existing = preferences.getString(key, null)
            if (value.toByteArray(StandardCharsets.UTF_8).size < INLINE_VALUE_THRESHOLD_BYTES) {
                cleanupFileIfSentinel(existing)
                if (!preferences.edit().putString(key, value).commit()) {
                    throw IllegalStateException("Failed to persist value")
                }
                "ok"
            } else {
                if (!storageDirectory.exists() && !storageDirectory.mkdirs()) {
                    throw IllegalStateException("Failed to create storage directory")
                }
                val targetFile = File(storageDirectory, "${sha256(key)}.json")
                targetFile.writeText(value)
                if (!preferences.edit()
                    .putString(key, FILE_SENTINEL_PREFIX + targetFile.absolutePath)
                    .commit()
                ) {
                    throw IllegalStateException("Failed to persist file reference")
                }
                "ok"
            }
        } catch (e: Exception) {
            "error:${e.message ?: "unknown error"}"
        }
    }

    @Synchronized
    fun delete(key: String): String {
        return try {
            val existing = preferences.getString(key, null)
            if (!preferences.edit().remove(key).commit()) {
                throw IllegalStateException("Failed to remove key")
            }
            cleanupFileIfSentinel(existing)
            "ok"
        } catch (e: Exception) {
            "error:${e.message ?: "unknown error"}"
        }
    }

    @Synchronized
    fun clear(): String {
        return try {
            preferences.all.values.forEach { value ->
                cleanupFileIfSentinel(value as? String)
            }
            if (!preferences.edit().clear().commit()) {
                throw IllegalStateException("Failed to clear key-value store")
            }

            if (storageDirectory.exists()) {
                storageDirectory.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        if (!file.delete()) {
                            throw IllegalStateException("Failed to delete ${file.name}")
                        }
                    }
                }
            }
            "ok"
        } catch (e: Exception) {
            "error:${e.message ?: "unknown error"}"
        }
    }

    @Synchronized
    fun keys(): String {
        val result = JSONArray()
        preferences.all.keys.forEach { key -> result.put(key) }
        return result.toString()
    }

    private fun cleanupFileIfSentinel(storedValue: String?) {
        if (storedValue == null || !storedValue.startsWith(FILE_SENTINEL_PREFIX)) {
            return
        }
        val file = File(storedValue.removePrefix(FILE_SENTINEL_PREFIX))
        if (file.exists() && !file.delete()) {
            throw IllegalStateException("Failed to delete ${file.name}")
        }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val STORAGE_NAMESPACE = "androlocal_storage"
        const val FILE_SENTINEL_PREFIX = "__file__:"
        const val INLINE_VALUE_THRESHOLD_BYTES = 4096
    }
}
