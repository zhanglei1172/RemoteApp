package com.remoteapp.host.transfer

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.remoteapp.host.auth.Credential

class TransferStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private fun fails(code: String, block: () -> Unit) {
        try { block(); fail("Expected $code") } catch (error: TransferException) { assertEquals(code, error.code) }
    }

    @Test fun binaryUnicodeAndDuplicateNamesRoundTripAcrossRestart() {
        val directory = temp.newFolder()
        val store = TransferStore(directory)
        val bytes = ByteArray(180_000) { (it % 251).toByte() }
        val first = store.import("中文 + 😀.bin", bytes.inputStream())
        val second = store.import(first.name, byteArrayOf().inputStream())
        assertNotEquals(first.id, second.id)
        assertEquals(bytes.size.toLong(), first.size)
        val restored = TransferStore(directory)
        assertEquals(2, restored.list().files.size)
        assertArrayEquals(bytes, restored.open(first.id).use { it.readBytes() })
        assertEquals(0, restored.open(second.id).use { it.readBytes().size })
        restored.remove(first.id)
        fails("FILE_NOT_FOUND") { restored.open(first.id) }
    }

    @Test fun rejectsPathsHeadersAndLeavesNoPartialFilesAfterFailures() {
        val directory = temp.newFolder()
        val store = TransferStore(directory, maxFileBytes = 4, maxTotalBytes = 8)
        for (name in listOf("", "..", "../private", "a/b", "a\\b", "line\r\nHeader", "a".repeat(241))) {
            fails("INVALID_FILENAME") { store.begin(name) }
        }
        fails("FILE_TOO_LARGE") { store.import("large", ByteArray(5).inputStream()) }
        assertEquals(0, directory.listFiles()!!.size)
        fails("FILE_NOT_FOUND") { store.open("../secret") }
        val file = store.import("ok", ByteArray(4).inputStream())
        assertEquals(4, store.open(file.id).use { it.readBytes().size })
    }

    @Test fun quotasIncludeConcurrentUploadsAndAbortsReleaseReservations() {
        val store = TransferStore(temp.newFolder(), maxFileBytes = 10, maxTotalBytes = 12, maxFiles = 3)
        val a = store.begin("a")
        val b = store.begin("b")
        fails("TRANSFER_BUSY") { store.begin("c") }
        store.append(a, ByteArray(8), 8)
        fails("TRANSFER_LIMIT") { store.append(b, ByteArray(5), 5) }
        store.abort(a)
        store.append(b, ByteArray(5), 5)
        store.commit(b)
        assertEquals(5L, store.list().usedBytes)
    }

    @Test fun interruptedImportIsNotPublishedAndStaleTemporaryDirectoriesAreRemoved() {
        val directory = temp.newFolder()
        val store = TransferStore(directory)
        try { store.import("interrupted", ByteArray(10).inputStream()) { throw IllegalStateException("cancelled") } }
        catch (_: IllegalStateException) { }
        assertTrue(store.list().files.isEmpty())
        assertTrue(directory.listFiles()!!.isEmpty())
        java.io.File(directory, ".pending-abandoned").mkdir()
        assertTrue(TransferStore(directory).list().files.isEmpty())
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun truncatedUploadCannotPublishAndCanReleaseItsQuota() {
        val directory = temp.newFolder()
        val store = TransferStore(directory)
        val pending = store.begin("cancelled.bin")
        store.append(pending, byteArrayOf(1, 2, 3), 3)
        fails("INCOMPLETE_UPLOAD") { store.commit(pending, expectedLength = 100) }
        assertTrue(store.list().files.isEmpty())
        store.abort(pending)
        assertTrue(directory.listFiles()!!.isEmpty())
        val complete = store.begin("complete.bin")
        store.append(complete, byteArrayOf(1, 2, 3), 3)
        assertEquals(3L, store.commit(complete, expectedLength = 3).size)
    }

    @Test fun ticketsAreSingleUseScopedAndExpire() {
        var time = 1_000L
        val tickets = DownloadTickets { time }
        val credential = Credential("test-key", true)
        val token = tickets.issue("file-a", credential)
        val grant = tickets.consume(token)!!
        assertEquals("file-a", grant.fileId)
        assertEquals(credential, grant.credential)
        assertNull(tickets.consume(token))
        val expired = tickets.issue("file-b", credential)
        time += 30_000
        assertNull(tickets.consume(expired))
    }
}
