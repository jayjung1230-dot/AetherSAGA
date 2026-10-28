package com.aether.reader.engine

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ContainerStabilityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testZeroByteTxtResilience() {
        val emptyFile = tempFolder.newFile("empty.txt")
        TxtContainer(emptyFile.absolutePath, false).use { c ->
            assertEquals("empty", c.title)
            assertTrue(c.chapters.isNotEmpty())
            assertEquals("(빈 문서입니다)", c.chapterText(0))
            assertEquals("", c.chapterText(99)) // out-of-bounds safety
        }
    }

    @Test
    fun testUtf8BomAndChunking() {
        val file = tempFolder.newFile("novel_utf8.txt")
        FileOutputStream(file).use { out ->
            out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())) // UTF-8 BOM
            val line = "안녕하세요. AetherReader 안정성 검증 테스트 문장입니다.\n".toByteArray(Charsets.UTF_8)
            repeat(600) { out.write(line) } // > 30KB -> multiple chunks
        }
        TxtContainer(file.absolutePath, false).use { c ->
            assertTrue("Expected at least 2 chunks for >24KB UTF-8 text", c.chapters.size >= 2)
            val first = c.chapterText(0)
            assertFalse("BOM must be stripped", first.startsWith("\uFEFF"))
            assertTrue(first.contains("안정성 검증 테스트"))
        }
    }

    @Test
    fun testLegacyKoreanCp949AutoDetection() {
        val file = tempFolder.newFile("legacy_kr.txt")
        val cp949 = Charset.forName("x-windows-949")
        val sample = "옛날 한글 인코딩(CP949/EUC-KR)으로 저장된 텍스트 파일입니다.\r\n두 번째 문단입니다."
        file.writeBytes(sample.toByteArray(cp949))

        TxtContainer(file.absolutePath, false).use { c ->
            val text = c.chapterText(0)
            assertTrue(text.contains("옛날 한글 인코딩"))
            assertTrue(text.contains("두 번째 문단입니다."))
        }
    }

    @Test
    fun testUtf16LeMultiChunking() {
        val file = tempFolder.newFile("utf16le_large.txt")
        FileOutputStream(file).use { out ->
            out.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte())) // UTF-16LE BOM
            val line = "유니코드 UTF-16LE 대용량 텍스트 줄바꿈 분할 검증입니다.\n".toByteArray(Charsets.UTF_16LE)
            repeat(450) { out.write(line) } // > 35KB -> should split cleanly on 2-byte newline
        }
        TxtContainer(file.absolutePath, false).use { c ->
            assertTrue("UTF-16LE >24KB must be split into multiple chunks", c.chapters.size >= 2)
            assertTrue(c.chapterText(0).contains("유니코드 UTF-16LE"))
            assertTrue(c.chapterText(1).contains("유니코드 UTF-16LE"))
        }
    }

    @Test
    fun testFb2XmlAndBinaryStripping() {
        val file = tempFolder.newFile("book.fb2")
        val fb2Xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook>
              <description><title-info><book-title>FB2 테스트</book-title></title-info></description>
              <body><section><title><p>제 1 장</p></title><p>본문 첫 문장입니다.</p></section></body>
              <binary id="cover.jpg" content-type="image/jpeg">AAAA_BASE64_NOISE_BBBB</binary>
            </FictionBook>
        """.trimIndent()
        file.writeText(fb2Xml, Charsets.UTF_8)

        TxtContainer(file.absolutePath, false).use { c ->
            val text = c.chapterText(0)
            assertTrue(text.contains("본문 첫 문장입니다."))
            assertFalse("Base64 binary blocks must be stripped", text.contains("AAAA_BASE64_NOISE_BBBB"))
        }
    }

    @Test
    fun testMalformedEpubAndCssStyleExtraction() {
        // Create an EPUB without META-INF/container.xml, with URL-encoded Korean hrefs and custom CSS
        val epubFile = tempFolder.newFile("malformed.epub")
        ZipOutputStream(FileOutputStream(epubFile)).use { zos ->
            zos.putNextEntry(ZipEntry("OEBPS/style.css"))
            zos.write("body { font-family: 'NanumGothic', sans-serif; line-height: 1.65; margin-left: 24px; } p { margin-bottom: 12px; }".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zos.write("""
                <package>
                  <metadata><dc:title>복구된 EPUB 제목</dc:title><dc:creator>홍길동</dc:creator></metadata>
                  <manifest>
                    <item id="css" href="style.css" media-type="text/css"/>
                    <item id="ch1" href="%EC%A0%9C1%EC%9E%A5.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine><itemref idref="ch1"/></spine>
                </package>
            """.trimIndent().toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("OEBPS/제1장.xhtml"))
            zos.write("""
                <html><head><title>무시됨</title></head>
                <body>
                  <h1>제1장 시작</h1>
                  <p>EPUB 본문 안정성 테스트입니다.</p>
                  <aside id="fn1">첫 번째 각주 설명입니다.</aside>
                </body></html>
            """.trimIndent().toByteArray())
            zos.closeEntry()
        }

        assertEquals(BookFormat.EPUB, Containers.sniffFileFormat(epubFile))

        EpubContainer(epubFile.absolutePath).use { c ->
            assertEquals("sans-serif", c.documentStyle.fontFamily)
            assertEquals(1.65f, c.documentStyle.lineHeight, 0.02f)
            assertEquals(12, c.documentStyle.paragraphSpacingDp)
            assertEquals(24, c.documentStyle.marginHorizontalDp)
            assertEquals(1, c.chapters.size)
            val text = c.chapterText(0)
            assertTrue(text.contains("EPUB 본문 안정성 테스트입니다."))
            val notes = c.footnotesForChapter(0)
            assertEquals("첫 번째 각주 설명입니다.", notes["fn1"])
        }
    }

    @Test
    fun testCbzNaturalSortAndEmptyResilience() {
        val cbzFile = tempFolder.newFile("comic.cbz")
        ZipOutputStream(FileOutputStream(cbzFile)).use { zos ->
            for (name in listOf("page10.jpg", "page2.jpg", "page1.jpg", "__MACOSX/._page1.jpg")) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(byteArrayOf(1, 2, 3))
                zos.closeEntry()
            }
        }
        assertEquals(BookFormat.CBZ, Containers.sniffFileFormat(cbzFile))
        CbzContainer(cbzFile.absolutePath).use { c ->
            assertEquals(3, c.chapters.size)
        }
    }

    @Test
    fun testMobiPalmDocUncompressedAndMagicSniffing() {
        val mobiFile = tempFolder.newFile("sample.mobi")
        val textBytes = "<html><body><p>MOBI 포맷 파서 안정성 검증 본문입니다.</p></body></html>".toByteArray(Charsets.UTF_8)

        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)
        // 32-byte PDB name
        val nameBytes = "MOBI_TEST_BOOK".toByteArray(Charsets.US_ASCII).copyOf(32)
        dos.write(nameBytes)
        dos.write(ByteArray(28)) // attributes..appInfoID
        dos.write("BOOKMOBI".toByteArray(Charsets.US_ASCII)) // offset 60: 8-byte type/creator
        dos.write(ByteArray(8))  // uniqueIDSeed + nextRecordListID
        dos.writeShort(2)        // 2 records (Rec 0 header, Rec 1 text)
        // Record list (8 bytes * 2 = 16 bytes): header ends at 78 + 16 = 94
        val rec0Offset = 94
        val rec1Offset = rec0Offset + 16
        dos.writeInt(rec0Offset)
        dos.writeInt(0)
        dos.writeInt(rec1Offset)
        dos.writeInt(0)
        // Record 0: 16-byte PalmDOC header
        dos.writeShort(1) // compression = 1 (no compression)
        dos.writeShort(0)
        dos.writeInt(textBytes.size)
        dos.writeShort(1) // 1 text record
        dos.writeShort(4096)
        dos.writeShort(0) // encryption = 0 (none)
        dos.writeShort(0)
        // Record 1: text
        dos.write(textBytes)
        dos.flush()
        mobiFile.writeBytes(bos.toByteArray())

        assertEquals(BookFormat.MOBI, Containers.sniffFileFormat(mobiFile))
        MobiContainer(mobiFile.absolutePath).use { c ->
            assertEquals("MOBI_TEST_BOOK", c.title)
            assertTrue(c.chapterText(0).contains("MOBI 포맷 파서 안정성 검증 본문입니다."))
        }
    }

    @Test
    fun testPdfMagicSniffingOnExtensionlessFile() {
        val pdfFile = tempFolder.newFile("content_msf_1000234")
        pdfFile.writeBytes("%PDF-1.7\n1 0 obj\n<<>>\nendobj\n".toByteArray(Charsets.US_ASCII))
        assertEquals(BookFormat.PDF, Containers.sniffFileFormat(pdfFile))
    }
}
