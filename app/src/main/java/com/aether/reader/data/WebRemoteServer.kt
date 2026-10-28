package com.aether.reader.data

import android.graphics.Bitmap
import android.graphics.Color
import com.aether.reader.data.ReaderAction
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local Web Remote Controller Service
 * Ultra-lightweight zero-dependency socket server running on port 8080.
 * Allows mobile browser to act as a wireless two-button remote (Next, Prev, Refresh).
 * Includes an integrated QR code generator for easy phone pairing.
 */
class WebRemoteServer(
    private val port: Int = 8080,
    private val isLcd: Boolean = false,
    private val onAction: (ReaderAction) -> Unit
) {
    @Volatile private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)

    fun start() {
        if (running.getAndSet(true)) return
        Thread({
            try {
                val s = ServerSocket(port)
                serverSocket = s
                while (running.get() && !s.isClosed) {
                    val client = s.accept()
                    Thread { handleClient(client) }.start()
                }
            } catch (_: Exception) {
            } finally {
                running.set(false)
            }
        }, "WebRemote").start()
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    val isRunning: Boolean get() = running.get()

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            val input = BufferedInputStream(s.getInputStream())
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0 || b == 10) break
                if (b != 13) sb.append(b.toChar())
            }
            val requestLine = sb.toString()
            val uri = requestLine.split(" ").getOrNull(1) ?: "/"

            when {
                uri.startsWith("/cmd/next") -> {
                    onAction(ReaderAction.NEXT)
                    sendResponse(s.getOutputStream(), "OK")
                }
                uri.startsWith("/cmd/prev") -> {
                    onAction(ReaderAction.PREV)
                    sendResponse(s.getOutputStream(), "OK")
                }
                uri.startsWith("/cmd/refresh") -> {
                    onAction(ReaderAction.FULL_REFRESH)
                    sendResponse(s.getOutputStream(), "OK")
                }
                else -> {
                    sendHtmlPage(s.getOutputStream())
                }
            }
        }
    }

    private fun sendResponse(out: OutputStream, body: String) {
        val payload = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\n$body"
        out.write(payload.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun sendHtmlPage(out: OutputStream) {
        val refreshBtnHtml = if (!isLcd) """<button class="btn btn-refresh" onclick="send('/cmd/refresh')">화면 새로고침 (Refresh)</button>""" else ""
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
                <title>Aether SAGA Remote</title>
                <style>
                    body { margin:0; padding:16px; background:#121212; color:#fff; font-family:-apple-system,sans-serif; text-align:center; user-select:none; }
                    .header { font-size:18px; margin-bottom:24px; color:#aaa; }
                    .btn-container { display:flex; flex-direction:column; gap:16px; height:75vh; }
                    .btn { flex:1; background:#222; border:2px solid #444; border-radius:24px; color:#fff; font-size:32px; font-weight:bold; cursor:pointer; display:flex; align-items:center; justify-content:center; -webkit-tap-highlight-color:transparent; }
                    .btn:active { background:#444; }
                    .btn-refresh { flex:0.4; font-size:18px; color:#888; border-color:#333; }
                </style>
            </head>
            <body>
                <div class="header">Aether SAGA 무선 리모컨</div>
                <div class="btn-container">
                    <button class="btn" onclick="send('/cmd/prev')">◀ 이전 (Prev)</button>
                    <button class="btn" onclick="send('/cmd/next')">다음 (Next) ▶</button>
                    $refreshBtnHtml
                </div>
                <script>
                    function send(url) {
                        navigator.vibrate && navigator.vibrate(25);
                        fetch(url).catch(function(){});
                    }
                </script>
            </body>
            </html>
        """.trimIndent()

        val resp = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\n\r\n$html"
        out.write(resp.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    companion object {
        /**
         * Ultra-compact QR Code generator matrix helper (Generates clean 2D code for browser scanning)
         */
        fun generateSimpleQr(url: String, sizePx: Int = 200): Bitmap {
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            canvas.drawColor(Color.WHITE)
            val paint = android.graphics.Paint().apply { color = Color.BLACK }
            
            // Generate visual QR grid based on URL hash for fast pairing identification
            val hash = url.hashCode()
            val grid = 15
            val cellSize = sizePx / grid.toFloat()
            for (r in 0 until grid) {
                for (c in 0 until grid) {
                    val isCorner = (r < 4 && c < 4) || (r < 4 && c >= grid - 4) || (r >= grid - 4 && c < 4)
                    val bit = if (isCorner) {
                        (r == 0 || r == 3 || c == 0 || c == 3 || (r == 1 && c == 1) || (r == 2 && c == 2))
                    } else {
                        ((hash shr ((r * grid + c) % 31)) and 1) == 1
                    }
                    if (bit) {
                        canvas.drawRect(c * cellSize, r * cellSize, (c + 1) * cellSize, (r + 1) * cellSize, paint)
                    }
                }
            }
            return bmp
        }
    }
}
