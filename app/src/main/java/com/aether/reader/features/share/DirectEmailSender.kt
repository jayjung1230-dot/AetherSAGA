package com.aether.reader.features.share

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Environment
import android.util.Base64
import com.aether.reader.data.AndroidShareHelper
import com.aether.reader.data.ExportSync
import com.aether.reader.data.HighlightEntity
import com.aether.reader.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Dedicated feature module for RFC-5321 SMTP / STARTTLS / Direct-to-MX background email transmission,
 * MIME multipart/related message construction, and HTML reading note email formatting.
 */
object DirectEmailSender {

    fun encodeCoverJpegBytes(coverBitmap: Bitmap): ByteArray? = try {
        val bgBmp = Bitmap.createBitmap(
            coverBitmap.width.coerceAtLeast(1),
            coverBitmap.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bgBmp)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(coverBitmap, 0f, 0f, null)
        val bos = ByteArrayOutputStream()
        bgBmp.compress(Bitmap.CompressFormat.JPEG, 88, bos)
        bgBmp.recycle()
        bos.toByteArray()
    } catch (_: Throwable) {
        null
    }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("\n", "<br/>")

    fun buildHtmlBody(
        resolvedTitle: String,
        resolvedAuthor: String,
        resolvedFormat: String,
        progressPct: Int,
        totalChapters: Int,
        items: List<HighlightEntity>,
        imgSrc: String?
    ): String {
        val sb = StringBuilder()
        val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        sb.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"></head>")
        sb.append("<body style=\"font-family:sans-serif; color:#111; line-height:1.6; max-width:640px; margin:0 auto; padding:16px;\">")
        sb.append("<div style=\"background:#F6F5F0; border:1px solid #D8D8D2; border-radius:10px; padding:16px; margin-bottom:20px;\">")
        sb.append("<h2 style=\"margin:0 0 12px 0; font-size:18px; color:#1F5E9C;\">📚 도서 정보 (Book Information)</h2>")
        if (imgSrc != null) {
            sb.append("<div style=\"margin-bottom:14px; text-align:center;\">")
            sb.append("<img src=\"").append(imgSrc).append("\" alt=\"Book Cover\" style=\"max-width:200px; height:auto; border-radius:6px; border:1px solid #CCC;\"/>")
            sb.append("</div>")
        }
        sb.append("<p style=\"margin:4px 0;\"><b>• 도서 제목:</b> ").append(escapeHtml(resolvedTitle)).append("</p>")
        sb.append("<p style=\"margin:4px 0;\"><b>• 저자 이름:</b> ").append(escapeHtml(resolvedAuthor)).append("</p>")
        sb.append("<p style=\"margin:4px 0;\"><b>• 문서 형식:</b> ").append(escapeHtml(resolvedFormat))
        if (totalChapters > 0) sb.append(" (총 ").append(totalChapters).append("개 챕터/섹션)")
        sb.append("</p>")
        sb.append("<p style=\"margin:4px 0;\"><b>• 현재 진도:</b> ").append(progressPct).append("%</p>")
        sb.append("<p style=\"margin:4px 0;\"><b>• 공유 일시:</b> ").append(nowStr).append("</p>")
        sb.append("</div>")

        sb.append("<h3 style=\"font-size:16px; border-bottom:2px solid #222; padding-bottom:6px;\">📝 선택한 문장 및 독서 노트 (총 ")
            .append(items.size).append("건)</h3>")

        var lastCh = Int.MIN_VALUE
        for ((idx, h) in items.withIndex()) {
            if (h.chapter != lastCh) {
                val chTitle = h.chapterTitle.ifBlank { "챕터 ${h.chapter + 1}" }
                sb.append("<h4 style=\"margin:16px 0 6px 0; color:#333;\">■ [").append(escapeHtml(chTitle)).append("]</h4>")
                lastCh = h.chapter
            }
            val kindTag = when {
                h.id == 0L && idx == 0 -> "선택 문장"
                h.kind == 1 -> "북마크"
                else -> "하이라이트"
            }
            val pct = (h.progress * 100).toInt().coerceIn(0, 100)
            sb.append("<div style=\"background:#FAFAFA; border-left:4px solid #222; padding:10px 14px; margin:8px 0; border-radius:4px;\">")
            sb.append("<div style=\"font-size:12px; color:#666; margin-bottom:4px;\">")
                .append(idx + 1).append(". [").append(kindTag).append("] • 위치: ").append(pct).append("%</div>")
            sb.append("<div style=\"font-size:15px; color:#111;\">“").append(escapeHtml(h.quote.trim())).append("”</div>")
            if (h.note.isNotBlank()) {
                sb.append("<div style=\"margin-top:6px; font-size:13px; color:#1F5E9C;\">└ <b>메모:</b> ")
                    .append(escapeHtml(h.note.trim())).append("</div>")
            }
            sb.append("</div>")
        }
        sb.append("<p style=\"margin-top:24px; font-size:12px; color:#888;\">— Aether SAGA E-Book Viewer에서 자동 발송됨</p>")
        sb.append("</body></html>")
        return sb.toString()
    }

    private fun mimeEncodeHeader(text: String): String =
        "=?UTF-8?B?" + Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) + "?="

    private fun buildMimeMessage(
        fromEmail: String,
        toEmail: String,
        subject: String,
        plainText: String,
        htmlText: String,
        coverJpegBytes: ByteArray?,
        safeCoverFileName: String
    ): String {
        val boundaryRel = "====AetherRel_${System.currentTimeMillis()}===="
        val boundaryAlt = "====AetherAlt_${System.currentTimeMillis()}===="
        val sb = StringBuilder()
        sb.append("From: Aether SAGA <").append(fromEmail).append(">\r\n")
        sb.append("To: <").append(toEmail).append(">\r\n")
        sb.append("Subject: ").append(mimeEncodeHeader(subject)).append("\r\n")
        sb.append("MIME-Version: 1.0\r\n")
        sb.append("Content-Type: multipart/related; boundary=\"").append(boundaryRel).append("\"\r\n\r\n")

        sb.append("--").append(boundaryRel).append("\r\n")
        sb.append("Content-Type: multipart/alternative; boundary=\"").append(boundaryAlt).append("\"\r\n\r\n")

        sb.append("--").append(boundaryAlt).append("\r\n")
        sb.append("Content-Type: text/plain; charset=\"UTF-8\"\r\n")
        sb.append("Content-Transfer-Encoding: base64\r\n\r\n")
        sb.append(Base64.encodeToString(plainText.toByteArray(Charsets.UTF_8), Base64.DEFAULT)).append("\r\n")

        sb.append("--").append(boundaryAlt).append("\r\n")
        sb.append("Content-Type: text/html; charset=\"UTF-8\"\r\n")
        sb.append("Content-Transfer-Encoding: base64\r\n\r\n")
        sb.append(Base64.encodeToString(htmlText.toByteArray(Charsets.UTF_8), Base64.DEFAULT)).append("\r\n")
        sb.append("--").append(boundaryAlt).append("--\r\n")

        if (coverJpegBytes != null && coverJpegBytes.isNotEmpty()) {
            sb.append("--").append(boundaryRel).append("\r\n")
            sb.append("Content-Type: image/jpeg; name=\"").append(safeCoverFileName).append("\"\r\n")
            sb.append("Content-Transfer-Encoding: base64\r\n")
            sb.append("Content-ID: <book_cover>\r\n")
            sb.append("Content-Disposition: inline; filename=\"").append(safeCoverFileName).append("\"\r\n\r\n")
            sb.append(Base64.encodeToString(coverJpegBytes, Base64.DEFAULT)).append("\r\n")
        }

        sb.append("--").append(boundaryRel).append("--\r\n")
        return sb.toString()
    }

    private fun resolveSmtpConfig(userEmail: String, customHost: String, customPort: Int): Pair<String, Int> {
        if (customHost.isNotBlank()) return customHost.trim() to (if (customPort > 0) customPort else 465)
        val domain = userEmail.substringAfter('@', "").lowercase(Locale.ROOT).trim()
        return when {
            domain == "naver.com" -> "smtp.naver.com" to 465
            domain == "gmail.com" || domain == "googlemail.com" -> "smtp.gmail.com" to 465
            domain == "daum.net" || domain == "hanmail.net" -> "smtp.daum.net" to 465
            domain == "kakao.com" -> "smtp.kakao.com" to 465
            domain == "nate.com" -> "smtp.mail.nate.com" to 465
            domain.startsWith("outlook.") || domain.startsWith("hotmail.") || domain.startsWith("live.") -> "smtp.office365.com" to 587
            domain == "icloud.com" || domain == "me.com" -> "smtp.mail.me.com" to 587
            domain.startsWith("yahoo.") -> "smtp.mail.yahoo.com" to 465
            else -> "smtp.$domain" to 465
        }
    }

    private fun lookupMxHosts(domain: String): List<String> {
        val hosts = ArrayList<String>()
        try {
            val url = URL("https://dns.google/resolve?name=${Uri.encode(domain)}&type=MX")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                requestMethod = "GET"
            }
            if (conn.responseCode in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val ans = json.optJSONArray("Answer")
                if (ans != null) {
                    val pairs = ArrayList<Pair<Int, String>>()
                    for (i in 0 until ans.length()) {
                        val obj = ans.getJSONObject(i)
                        val data = obj.optString("data", "").trim()
                        val parts = data.split(Regex("\\s+"))
                        if (parts.size >= 2) {
                            val prio = parts[0].toIntOrNull() ?: 50
                            val mx = parts[1].trimEnd('.')
                            if (mx.isNotBlank()) pairs += prio to mx
                        }
                    }
                    pairs.sortedBy { it.first }.forEach { hosts += it.second }
                }
            }
            conn.disconnect()
        } catch (_: Throwable) {}

        when (domain.lowercase(Locale.ROOT)) {
            "naver.com" -> hosts.addAll(listOf("mx1.naver.com", "mx2.naver.com", "mx3.naver.com"))
            "gmail.com", "googlemail.com" -> hosts.addAll(listOf("gmail-smtp-in.l.google.com", "alt1.gmail-smtp-in.l.google.com"))
            "daum.net", "hanmail.net" -> hosts.addAll(listOf("mx1.daum.net", "mx2.daum.net"))
            "kakao.com" -> hosts.addAll(listOf("mx1.kakao.com", "mx2.kakao.com"))
            "nate.com" -> hosts.addAll(listOf("mx1.nate.com"))
        }
        return hosts.distinct()
    }

    private fun readSmtpResponse(reader: BufferedReader): Pair<Int, String> {
        val sb = StringBuilder()
        var code = -1
        while (true) {
            val line = reader.readLine() ?: break
            sb.append(line).append("\n")
            if (line.length >= 3) {
                code = line.substring(0, 3).toIntOrNull() ?: -1
                if (line.length == 3 || line[3] == ' ') break
            } else break
        }
        return code to sb.toString().trim()
    }

    private fun sendCmd(writer: PrintWriter, reader: BufferedReader, cmd: String?): Pair<Int, String> {
        if (cmd != null) {
            writer.print("$cmd\r\n")
            writer.flush()
        }
        return readSmtpResponse(reader)
    }

    private fun transmitSmtpSession(
        host: String,
        port: Int,
        useImplicitTls: Boolean,
        authUser: String?,
        authPass: String?,
        fromEmail: String,
        toEmail: String,
        mimeMessage: String
    ): Pair<Boolean, String> {
        var rawSocket: Socket? = null
        try {
            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val baseSocket = Socket()
            baseSocket.connect(InetSocketAddress(host, port), 8000)
            baseSocket.soTimeout = 12000

            val socket: Socket = if (useImplicitTls) {
                val sslSock = sslFactory.createSocket(baseSocket, host, port, true) as SSLSocket
                sslSock.startHandshake()
                sslSock
            } else {
                baseSocket
            }
            rawSocket = socket

            var reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            var writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), false)

            val (greetCode, greetMsg) = readSmtpResponse(reader)
            if (greetCode !in 200..299) return false to "서버 연결 응답 오류 ($greetCode: $greetMsg)"

            val heloHost = if (!authUser.isNullOrBlank() && authUser.contains("@")) {
                authUser.substringAfter('@').trim().ifBlank { "gmail.com" }
            } else {
                host.substringAfter("smtp.", "gmail.com")
            }

            var (ehloCode, ehloMsg) = sendCmd(writer, reader, "EHLO $heloHost")
            if (ehloCode !in 200..299) {
                val (heloCode, heloMsg) = sendCmd(writer, reader, "HELO $heloHost")
                if (heloCode !in 200..299) return false to "HELO 오류 ($heloMsg)"
                ehloMsg = heloMsg
            }

            if (!useImplicitTls && (ehloMsg.contains("STARTTLS", ignoreCase = true) || port == 587)) {
                val (tlsCode, tlsMsg) = sendCmd(writer, reader, "STARTTLS")
                if (tlsCode in 200..299) {
                    val sslSock = sslFactory.createSocket(rawSocket, host, port, true) as SSLSocket
                    sslSock.startHandshake()
                    rawSocket = sslSock
                    reader = BufferedReader(InputStreamReader(sslSock.getInputStream(), Charsets.UTF_8))
                    writer = PrintWriter(OutputStreamWriter(sslSock.getOutputStream(), Charsets.UTF_8), false)
                    val (ehlo2Code, ehlo2Msg) = sendCmd(writer, reader, "EHLO $heloHost")
                    if (ehlo2Code in 200..299) {
                        ehloMsg = ehlo2Msg
                    }
                } else if (port == 587) {
                    return false to "STARTTLS 보안 연결 거부 ($tlsCode: $tlsMsg)"
                }
            }

            if (!authUser.isNullOrBlank() && !authPass.isNullOrBlank()) {
                val cleanUser = authUser.trim()
                val cleanPass = if (cleanUser.endsWith("@gmail.com", ignoreCase = true) || cleanUser.endsWith("@googlemail.com", ignoreCase = true)) {
                    authPass.replace(" ", "").trim()
                } else {
                    authPass.trim()
                }

                var authSuccess = false
                var authFailReason = ""

                val candidates = ArrayList<String>()
                candidates.add(cleanUser)
                if (cleanUser.contains("@")) {
                    candidates.add(cleanUser.substringBefore('@'))
                } else {
                    val fallbackDomain = host.substringAfter("smtp.", "").trim()
                    if (fallbackDomain.isNotBlank()) candidates.add("$cleanUser@$fallbackDomain")
                }

                for (userAttempt in candidates) {
                    val (authCode, _) = sendCmd(writer, reader, "AUTH LOGIN")
                    if (authCode == 334) {
                        val u64 = Base64.encodeToString(userAttempt.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                        val (uCode, uMsg) = sendCmd(writer, reader, u64)
                        if (uCode == 334) {
                            val p64 = Base64.encodeToString(cleanPass.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                            val (pCode, pMsg) = sendCmd(writer, reader, p64)
                            if (pCode in 200..299) {
                                authSuccess = true
                                break
                            } else {
                                authFailReason = "비밀번호 인증 실패 (응답 $pCode: $pMsg)"
                            }
                        } else {
                            authFailReason = "아이디 인증 실패 (응답 $uCode: $uMsg)"
                        }
                    } else {
                        val plainToken = "\u0000$userAttempt\u0000$cleanPass"
                        val plain64 = Base64.encodeToString(plainToken.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                        val (pCode, pMsg) = sendCmd(writer, reader, "AUTH PLAIN $plain64")
                        if (pCode in 200..299) {
                            authSuccess = true
                            break
                        } else {
                            authFailReason = "인증 실패 (응답 $pCode: $pMsg)"
                        }
                    }
                }

                if (!authSuccess) {
                    val isGmail = host.contains("gmail", ignoreCase = true) || cleanUser.contains("gmail", ignoreCase = true)
                    val isNaver = host.contains("naver", ignoreCase = true) || cleanUser.contains("naver", ignoreCase = true)
                    val guide = when {
                        isGmail -> "\n\n💡 [Gmail 발송 해결 방법]\nGmail은 일반 계정 비밀번호 사용이 차단되어 있습니다. Google 계정 관리 → 보안 → 2단계 인증 → [앱 비밀번호(16자리)]를 생성하여 비밀번호 칸에 입력하셔야 합니다."
                        isNaver -> "\n\n💡 [네이버 메일 해결 방법]\n네이버 웹메일 환경설정 → POP3/IMAP 설정에서 [POP3/SMTP 사용] 또는 [IMAP/SMTP 사용]을 '사용함'으로 설정해야 발송할 수 있습니다."
                        else -> ""
                    }
                    return false to "SMTP 계정 인증 실패: $authFailReason$guide"
                }
            }

            val (mailCode, mailMsg) = sendCmd(writer, reader, "MAIL FROM:<$fromEmail>")
            if (mailCode !in 200..299) return false to "발신자 주소 거부 ($mailCode: $mailMsg)"

            val (rcptCode, rcptMsg) = sendCmd(writer, reader, "RCPT TO:<$toEmail>")
            if (rcptCode !in 200..299) return false to "수신자 주소 거부 ($rcptCode: $rcptMsg)"

            val (dataCode, dataMsg) = sendCmd(writer, reader, "DATA")
            if (dataCode != 354) return false to "DATA 전송 거부 ($dataCode: $dataMsg)"

            val safeMime = mimeMessage.replace("\r\n.", "\r\n..")
            writer.print(safeMime)
            if (!safeMime.endsWith("\r\n")) writer.print("\r\n")
            val (endCode, endMsg) = sendCmd(writer, reader, ".")
            sendCmd(writer, reader, "QUIT")

            return if (endCode in 200..299) {
                true to "이메일이 성공적으로 발송되었습니다.\n• 수신자: $toEmail\n• 발신 서버: $host:$port"
            } else {
                false to "메일 서버 발송 거부 ($endCode: $endMsg)"
            }
        } catch (e: Throwable) {
            return false to (e.message ?: "네트워크 연결 실패")
        } finally {
            try { rawSocket?.close() } catch (_: Throwable) {}
        }
    }

    suspend fun sendEmailDirectly(
        prefs: Prefs,
        sync: ExportSync,
        toEmail: String,
        subject: String,
        plainText: String,
        bookTitle: String,
        bookAuthor: String,
        bookFormat: String,
        progressPct: Int,
        totalChapters: Int,
        items: List<HighlightEntity>,
        coverBitmap: Bitmap?
    ): AndroidShareHelper.DirectEmailResult = withContext(Dispatchers.IO) {
        val cleanTo = toEmail.trim()
        if (cleanTo.isBlank() || !cleanTo.contains("@")) {
            return@withContext AndroidShareHelper.DirectEmailResult(false, "올바른 이메일 주소를 입력해주세요.")
        }

        val coverBytes = if (coverBitmap != null) encodeCoverJpegBytes(coverBitmap) else null
        val safeTitle = bookTitle.replace(Regex("[^\\p{L}\\p{N}_-]+"), "_").take(36).ifBlank { "book" }
        val coverFileName = "${safeTitle}_cover.jpg"

        val htmlCid = buildHtmlBody(
            resolvedTitle = bookTitle,
            resolvedAuthor = bookAuthor,
            resolvedFormat = bookFormat,
            progressPct = progressPct,
            totalChapters = totalChapters,
            items = items,
            imgSrc = if (coverBytes != null) "cid:book_cover" else null
        )

        val smtpUser = prefs.smtpUser.trim()
        val smtpPass = prefs.smtpPass.trim()
        var lastError = ""

        if (smtpUser.isNotBlank() && smtpPass.isNotBlank()) {
            val (host, port) = resolveSmtpConfig(smtpUser, prefs.smtpHost, prefs.smtpPort)
            val fromAddr = if (smtpUser.contains("@")) smtpUser else "$smtpUser@${host.substringAfter("smtp.")}"
            val mime = buildMimeMessage(fromAddr, cleanTo, subject, plainText, htmlCid, coverBytes, coverFileName)
            val (ok, msg) = transmitSmtpSession(
                host = host,
                port = port,
                useImplicitTls = (port == 465),
                authUser = smtpUser,
                authPass = smtpPass,
                fromEmail = fromAddr,
                toEmail = cleanTo,
                mimeMessage = mime
            )
            if (ok) return@withContext AndroidShareHelper.DirectEmailResult(true, msg)
            if (port == 465) {
                val (ok2, msg2) = transmitSmtpSession(
                    host = host,
                    port = 587,
                    useImplicitTls = false,
                    authUser = smtpUser,
                    authPass = smtpPass,
                    fromEmail = fromAddr,
                    toEmail = cleanTo,
                    mimeMessage = mime
                )
                if (ok2) return@withContext AndroidShareHelper.DirectEmailResult(true, msg2)
                lastError = msg2
            } else {
                lastError = msg
            }
            return@withContext AndroidShareHelper.DirectEmailResult(false, lastError)
        }

        val domain = cleanTo.substringAfter('@', "").trim()
        if (domain.isNotBlank()) {
            val mxHosts = lookupMxHosts(domain)
            val fromAddr = if (smtpUser.contains("@")) smtpUser else "noreply@$domain"
            val mime = buildMimeMessage(fromAddr, cleanTo, subject, plainText, htmlCid, coverBytes, coverFileName)
            for (mx in mxHosts.take(2)) {
                val (ok, msg) = transmitSmtpSession(
                    host = mx,
                    port = 25,
                    useImplicitTls = false,
                    authUser = null,
                    authPass = null,
                    fromEmail = fromAddr,
                    toEmail = cleanTo,
                    mimeMessage = mime
                )
                if (ok) return@withContext AndroidShareHelper.DirectEmailResult(true, msg)
                lastError = msg
            }
        }

        val dataUriImg = if (coverBytes != null) {
            "data:image/jpeg;base64," + Base64.encodeToString(coverBytes, Base64.NO_WRAP)
        } else null
        val standaloneHtml = buildHtmlBody(
            resolvedTitle = bookTitle,
            resolvedAuthor = bookAuthor,
            resolvedFormat = bookFormat,
            progressPct = progressPct,
            totalChapters = totalChapters,
            items = items,
            imgSrc = dataUriImg
        )
        try {
            saveToDocuments("${safeTitle}_${System.currentTimeMillis()}.html", standaloneHtml)
            sync.enqueue("email_outbox/${safeTitle}_${System.currentTimeMillis()}.html", standaloneHtml, "text/html")
        } catch (_: Throwable) {}

        val hint = if (smtpUser.isBlank() || smtpPass.isBlank()) {
            "수신 메일 서버($domain)가 발신 인증을 요구합니다. 창 하단의 [발신 메일(SMTP) 계정 설정]에 이메일과 앱 비밀번호를 1회 입력하시면 즉시 자동 발송됩니다."
        } else {
            "발송 실패: $lastError"
        }
        return@withContext AndroidShareHelper.DirectEmailResult(false, hint)
    }

    fun saveToDocuments(name: String, text: String): File {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "AetherSAGA")
        dir.mkdirs()
        return File(dir, name).also { it.writeText(text) }
    }
}
