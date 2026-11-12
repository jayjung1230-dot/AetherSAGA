package com.aether.reader.features.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Base64
import com.aether.reader.data.AppDatabase
import com.aether.reader.data.ExportQueueEntity
import com.aether.reader.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.HttpURLConnection
import java.net.URL

/** Minimal WebDAV client (PUT/GET with Basic auth). Remote files are written flat into the configured folder URL. */
class WebDavClient(private val base: String, private val user: String, private val pass: String) {
    private fun open(path: String, method: String): HttpURLConnection {
        val c = URL(base.trimEnd('/') + "/" + path.trimStart('/')).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 8000
        c.readTimeout = 15000
        if (user.isNotBlank()) {
            c.setRequestProperty(
                "Authorization",
                "Basic " + Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP)
            )
        }
        return c
    }

    fun put(path: String, content: String, mime: String): Boolean = try {
        val c = open(path, "PUT")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "$mime; charset=utf-8")
        c.outputStream.use { it.write(content.toByteArray(Charsets.UTF_8)) }
        (c.responseCode in 200..299).also { c.disconnect() }
    } catch (_: Exception) {
        false
    }

    fun get(path: String): String? = try {
        val c = open(path, "GET")
        if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null
    } catch (_: Exception) {
        null
    }
}

/** Offline export queue: entries are persisted in Room and flushed whenever a network becomes available. */
class WebDavSyncManager(private val ctx: Context, private val prefs: Prefs, private val db: AppDatabase) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    fun client(): WebDavClient? = prefs.webdavUrl.takeIf { it.isNotBlank() }?.let { WebDavClient(it, prefs.webdavUser, prefs.webdavPass) }

    fun enqueue(remotePath: String, content: String, mime: String) {
        scope.launch {
            db.exportQueue().upsert(ExportQueueEntity(remotePath = remotePath, content = content, mime = mime, createdAt = System.currentTimeMillis()))
            flush()
        }
    }

    fun flush() {
        scope.launch {
            mutex.withLock {
                val c = client() ?: return@launch
                for (e in db.exportQueue().all()) {
                    if (c.put(e.remotePath, e.content, e.mime)) db.exportQueue().delete(e.id) else break
                }
            }
        }
    }

    suspend fun pending(): Int = db.exportQueue().count()

    fun registerNetworkTrigger() {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    flush()
                }
            })
        } catch (_: Exception) {}
    }
}
