package com.nova.files

import com.nova.files.thumbs.Extractor
import com.nova.files.thumbs.FileFormats
import com.nova.files.thumbs.FormatKind
import org.junit.Assert.*
import org.junit.Test

class FileFormatsTest {

    @Test fun everyCategoryGetsASpecificFormat() {
        val expect = mapOf(
            "app.apk" to (FormatKind.APK to Extractor.APK),
            "game.xapk" to (FormatKind.APP_BUNDLE to Extractor.APP_BUNDLE),
            "x.apks" to (FormatKind.APP_BUNDLE to Extractor.APP_BUNDLE),
            "x.apkm" to (FormatKind.APP_BUNDLE to Extractor.APP_BUNDLE),
            "IMG_01.HEIC" to (FormatKind.IMAGE to Extractor.IMAGE),
            "a.webp" to (FormatKind.IMAGE to Extractor.IMAGE),
            "scan.tiff" to (FormatKind.IMAGE to Extractor.NONE),
            "logo.svg" to (FormatKind.VECTOR to Extractor.NONE),
            "DSC.NEF" to (FormatKind.RAW_IMAGE to Extractor.RAW_IMAGE),
            "clip.mkv" to (FormatKind.VIDEO to Extractor.VIDEO),
            "song.flac" to (FormatKind.AUDIO to Extractor.AUDIO),
            "track.opus" to (FormatKind.AUDIO to Extractor.AUDIO),
            "doc.pdf" to (FormatKind.PDF to Extractor.PDF),
            "cv.docx" to (FormatKind.WORD to Extractor.OOXML),
            "old.doc" to (FormatKind.WORD to Extractor.NONE),
            "b.xlsx" to (FormatKind.EXCEL to Extractor.OOXML),
            "deck.pptx" to (FormatKind.POWERPOINT to Extractor.OOXML),
            "n.odt" to (FormatKind.WORD to Extractor.ODF),
            "book.epub" to (FormatKind.EBOOK to Extractor.EPUB),
            "a.csv" to (FormatKind.CSV to Extractor.NONE),
            "p.html" to (FormatKind.WEB to Extractor.NONE),
            "d.json" to (FormatKind.DATA to Extractor.NONE),
            "x.7z" to (FormatKind.ARCHIVE to Extractor.NONE),
            "backup.tar.gz" to (FormatKind.ARCHIVE to Extractor.NONE),
            "Roboto.ttf" to (FormatKind.FONT to Extractor.FONT),
            "art.psd" to (FormatKind.DESIGN to Extractor.NONE),
            "app.db" to (FormatKind.DATABASE to Extractor.NONE),
            "a.yaml" to (FormatKind.CONFIG to Extractor.NONE),
            "Main.kt" to (FormatKind.CODE to Extractor.NONE),
            "ubuntu.iso" to (FormatKind.DISK_IMAGE to Extractor.NONE),
            "cert.pem" to (FormatKind.CERTIFICATE to Extractor.NONE),
            "movie.srt" to (FormatKind.SUBTITLE to Extractor.NONE),
        )
        for ((name, kx) in expect) {
            val f = FileFormats.of(name)
            assertEquals(name, kx.first, f.kind); assertEquals(name, kx.second, f.extractor)
        }
        assertEquals("DOCX", FileFormats.of("cv.docx").label)
        assertEquals(FormatKind.UNKNOWN, FileFormats.of("mystery.qqq").kind)
        assertEquals("QQQ", FileFormats.of("mystery.qqq").label)
        assertEquals(FormatKind.UNKNOWN, FileFormats.of("README").kind)
        assertEquals(FormatKind.FOLDER, FileFormats.of("Photos", isDir = true).kind)
    }

    @Test fun cacheKeyRefreshesWhenFileChanges() {
        val a = FileFormats.cacheKey("/s/a.jpg", 100, 1000, 128)
        assertEquals(a, FileFormats.cacheKey("/s/a.jpg", 100, 1000, 128))
        assertNotEquals(a, FileFormats.cacheKey("/s/a.jpg", 101, 1000, 128))   // size changed
        assertNotEquals(a, FileFormats.cacheKey("/s/a.jpg", 100, 1001, 128))   // modified
        assertNotEquals(a, FileFormats.cacheKey("/s/b.jpg", 100, 1000, 128))   // renamed/moved
        assertNotEquals(a, FileFormats.cacheKey("/s/a.jpg", 100, 1000, 256))   // different size request
    }

    @Test fun documentThumbnails() {
        assertEquals("docProps/thumbnail.jpeg", FileFormats.documentThumbnailEntry(listOf("[Content_Types].xml", "word/document.xml", "docProps/thumbnail.jpeg")))
        assertEquals("Thumbnails/thumbnail.png", FileFormats.documentThumbnailEntry(listOf("content.xml", "Thumbnails/thumbnail.png")))
        assertNull(FileFormats.documentThumbnailEntry(listOf("word/document.xml", "docProps/thumbnail.wmf")))
    }

    @Test fun appBundles() {
        assertEquals("icon.png", FileFormats.bundleIconEntry(listOf("manifest.json", "icon.png", "com.app.apk")))
        assertEquals("base.apk", FileFormats.bundleBaseApk(listOf("split_config.arm64_v8a.apk", "base.apk", "split_config.en.apk")))
        assertEquals("com.whatsapp.apk", FileFormats.bundleBaseApk(listOf("config.arm64_v8a.apk", "com.whatsapp.apk", "config.xxhdpi.apk"),
            mapOf("config.arm64_v8a.apk" to 9_000_000L, "com.whatsapp.apk" to 50_000_000L, "config.xxhdpi.apk" to 1_000L)))
        assertNull(FileFormats.bundleBaseApk(listOf("manifest.json", "../evil.apk")))
    }

    @Test fun epubCover() {
        assertEquals("OEBPS/content.opf", FileFormats.epubOpfPath("""<container><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""))
        val epub3 = """<package><manifest><item id="c" href="images/cover.jpg" media-type="image/jpeg" properties="cover-image"/></manifest></package>"""
        assertEquals("OEBPS/images/cover.jpg", FileFormats.epubCoverPath("OEBPS/content.opf", epub3))
        val epub2 = """<package><metadata><meta name="cover" content="cov"/></metadata><manifest><item id="cov" href="../Images/Cover%20Art.png" media-type="image/png"/></manifest></package>"""
        assertEquals("Images/Cover Art.png", FileFormats.epubCoverPath("OEBPS/content.opf", epub2))
        assertNull(FileFormats.epubCoverPath("content.opf", """<item id="x" href="../../etc/passwd" properties="cover-image"/>"""))
        assertNull(FileFormats.epubCoverPath("content.opf", "<package/>"))
    }
}
