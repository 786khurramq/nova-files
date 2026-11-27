package com.nova.files

import com.nova.files.core.ExternalContent
import com.nova.files.core.ViewKind
import org.junit.Assert.*
import org.junit.Test

class ExternalContentTest {
    @Test fun classifiesByMime() {
        assertEquals(ViewKind.PDF, ExternalContent.classify("application/pdf", "x"))
        assertEquals(ViewKind.IMAGE, ExternalContent.classify("image/heic", "x"))
        assertEquals(ViewKind.VIDEO, ExternalContent.classify("video/mp4", "x"))
        assertEquals(ViewKind.AUDIO, ExternalContent.classify("audio/mpeg", "x"))
        assertEquals(ViewKind.TEXT, ExternalContent.classify("text/plain; charset=utf-8", "x"))
        assertEquals(ViewKind.TEXT, ExternalContent.classify("text/csv", "x"))
        assertEquals(ViewKind.TEXT, ExternalContent.classify("application/json", "x"))
        assertEquals(ViewKind.ZIP, ExternalContent.classify("application/zip", "x"))
        assertEquals(ViewKind.OFFICE, ExternalContent.classify("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "x"))
        assertEquals(ViewKind.OFFICE, ExternalContent.classify("application/vnd.ms-excel", "x"))
    }

    @Test fun octetStreamFallsBackToExtension() {
        val o = "application/octet-stream"
        assertEquals(ViewKind.PDF, ExternalContent.classify(o, "Invoice.PDF"))
        assertEquals(ViewKind.ZIP, ExternalContent.classify(o, "a.zip"))
        assertEquals(ViewKind.OFFICE, ExternalContent.classify(o, "report.xlsx"))
        assertEquals(ViewKind.TEXT, ExternalContent.classify(o, "notes.txt"))
        assertEquals(ViewKind.IMAGE, ExternalContent.classify(o, "IMG_1.jpg"))
        assertEquals(ViewKind.OTHER, ExternalContent.classify(o, "data.xyz"))
        assertEquals(ViewKind.OTHER, ExternalContent.classify(o, "noext"))
    }

    @Test fun blocksOwnPrivateData() {
        val roots = listOf("/data/user/0/com.nova.files", "/storage/emulated/0/Android/data/com.nova.files")
        assertFalse(ExternalContent.isPathAllowed("/data/user/0/com.nova.files/files/trusted_devices.json", roots))
        assertFalse(ExternalContent.isPathAllowed("/storage/emulated/0/Android/data/com.nova.files/files/x", roots))
        assertFalse(ExternalContent.isPathAllowed("/data/data/com.other/secret", roots))
        assertFalse(ExternalContent.isPathAllowed("/proc/self/environ", roots))
        assertTrue(ExternalContent.isPathAllowed("/storage/emulated/0/Download/a.pdf", roots))
        assertFalse(ExternalContent.isAuthorityAllowed("com.nova.files.files", "com.nova.files"))
        assertFalse(ExternalContent.isAuthorityAllowed(null, "com.nova.files"))
        assertTrue(ExternalContent.isAuthorityAllowed("com.google.android.gm.sapi", "com.nova.files"))
        assertTrue(ExternalContent.isAuthorityAllowed("media", "com.nova.files"))
    }

    @Test fun sanitisesNames() {
        assertEquals("passwd", ExternalContent.safeName("../../etc/passwd"))
        assertEquals("a_b.txt", ExternalContent.safeName("a:b.txt"))
        assertEquals("file", ExternalContent.safeName(null))
        assertEquals("file", ExternalContent.safeName("..."))
        assertEquals("Report 2026.docx", ExternalContent.safeName("Report 2026.docx"))
    }
}
