package com.portl.shell

import android.webkit.JavascriptInterface

class AndroidBridge(
    private val storageManager: StorageManager,
    private val databaseManager: DatabaseManager
) {
    @JavascriptInterface
    fun storageGet(key: String): String = storageManager.get(key) ?: "__portl_null__"

    @JavascriptInterface
    fun storageSet(key: String, value: String): String = storageManager.set(key, value)

    @JavascriptInterface
    fun storageDelete(key: String): String = storageManager.delete(key)

    @JavascriptInterface
    fun storageClear(): String = storageManager.clear()

    @JavascriptInterface
    fun storageKeys(): String = storageManager.keys()

    @JavascriptInterface
    fun dbQuery(sql: String, paramsJson: String?): String = databaseManager.query(sql, paramsJson)
}
