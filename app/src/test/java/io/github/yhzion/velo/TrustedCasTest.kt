package io.github.yhzion.velo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.Base64

class TrustedCasTest {
    @get:Rule val tmp = TemporaryFolder()

    private val der = ByteArray(200) { (it * 7).toByte() }
    private val other = ByteArray(50) { (255 - it).toByte() }

    @Test fun pemWrapsBase64In64CharacterLinesBetweenTheMarkers() {
        val text = TrustedCas.pem(der)
        val lines = text.trimEnd('\n').split("\n")
        assertEquals("-----BEGIN CERTIFICATE-----", lines.first())
        assertEquals("-----END CERTIFICATE-----", lines.last())
        val body = lines.subList(1, lines.size - 1)
        assertTrue(body.dropLast(1).all { it.length == 64 })
        assertTrue(body.last().length in 1..64)
        assertArrayEquals(der, Base64.getDecoder().decode(body.joinToString("")))
        assertTrue(text.endsWith("-----END CERTIFICATE-----\n"))
    }

    @Test fun pemOfAShortCertificate() = assertEquals(
        "-----BEGIN CERTIFICATE-----\nAQID\n-----END CERTIFICATE-----\n",
        TrustedCas.pem(byteArrayOf(1, 2, 3)),
    )

    @Test fun fileNamesComeFromTheAliasAndStayInTheDirectory() {
        assertEquals("cf4d12bf.0.pem", TrustedCas.fileName("user:cf4d12bf.0"))
        assertEquals(".._x.pem", TrustedCas.fileName("user:../x"))
        assertEquals("_.pem", TrustedCas.fileName("user:"))
    }

    @Test fun aSystemCaKeepsItsOwnFileBesideAUserCaWithTheSameHash() {
        // #237: system CAs are exported too; the same hash may name a user CA.
        assertEquals("system-cf4d12bf.0.pem", TrustedCas.fileName("system:cf4d12bf.0"))
        assertEquals("system-.._x.pem", TrustedCas.fileName("system:../x"))
    }

    @Test fun theStoreReadAndWrittenIsTheOnlyCaDirectory() {
        // A system CA the user turned off is not in the store, so the system directory goes.
        assertEquals(
            listOf("/data/cas"),
            TrustedCas.chooseDirs(stored = listOf("system-a.0.pem", "b.0.pem"), exported = "/data/cas", system = "/system/cas"),
        )
    }

    @Test fun aStoreThatCannotBeReadKeepsTheSystemDirectory() {
        // As before #237: nothing that connects now stops connecting.
        assertEquals(listOf("/system/cas", "/data/cas"), TrustedCas.chooseDirs(stored = null, exported = "/data/cas", system = "/system/cas"))
        assertEquals(listOf("/system/cas"), TrustedCas.chooseDirs(stored = listOf("system-a.0.pem"), exported = null, system = "/system/cas"))
        assertEquals(emptyList<String>(), TrustedCas.chooseDirs(stored = null, exported = null, system = null))
    }

    @Test fun aStoreWithoutSystemCasKeepsTheSystemDirectory() {
        // A store read empty, or with user CAs only, must not leave the core trusting no public CA.
        assertEquals(listOf("/system/cas", "/data/cas"), TrustedCas.chooseDirs(stored = listOf("b.0.pem"), exported = "/data/cas", system = "/system/cas"))
        assertEquals(listOf("/system/cas", "/data/cas"), TrustedCas.chooseDirs(stored = emptyList(), exported = "/data/cas", system = "/system/cas"))
    }

    @Test fun syncWritesOnePemFilePerCertificate() {
        val dir = File(tmp.root, "user-cas")
        TrustedCas.sync(dir, mapOf("a.pem" to der, "b.pem" to other))
        assertEquals(setOf("a.pem", "b.pem"), dir.list()!!.toSet())
        assertEquals(TrustedCas.pem(der), File(dir, "a.pem").readText())
        assertEquals(TrustedCas.pem(other), File(dir, "b.pem").readText())
    }

    @Test fun syncRemovesCertificatesNoLongerInstalledAndStrayFiles() {
        val dir = tmp.newFolder("user-cas")
        TrustedCas.sync(dir, mapOf("a.pem" to der, "b.pem" to other))
        File(dir, "stray").writeText("old")
        File(dir, "b.pem").writeText("changed")
        TrustedCas.sync(dir, mapOf("b.pem" to other))
        assertEquals(setOf("b.pem"), dir.list()!!.toSet())
        assertEquals(TrustedCas.pem(other), File(dir, "b.pem").readText())
    }

    @Test fun syncWithNothingInstalledLeavesAnEmptyDirectory() {
        val dir = tmp.newFolder("user-cas")
        File(dir, "a.pem").writeText(TrustedCas.pem(der))
        TrustedCas.sync(dir, emptyMap())
        assertTrue(dir.isDirectory)
        assertEquals(0, dir.list()!!.size)
    }

    // #253: the export runs off the main thread. The core gets two fixed paths at once and opens
    // no connection before the export ends, so it never reads a directory being written.

    private fun exportInto(read: () -> Map<String, ByteArray>): Triple<File, File, File> {
        val dir = File(tmp.root, "user-cas")
        val link = File(tmp.root, "system-cas")
        val system = tmp.root.resolve("system").apply { mkdirs() }
        TrustedCas.export(dir, link, system, read = read)
        return Triple(dir, link, system)
    }

    @Test fun theSystemLinkComesFirstAsTheSystemDirectoryDid() {
        // The bundle keeps the first certificate of a subject: a system CA wins over a user CA
        // with the same subject, as before #253.
        val dir = File("/data/no_backup/user-cas")
        val link = File("/data/no_backup/system-cas")
        assertArrayEquals(arrayOf(link.path, dir.path), TrustedCas.coreDirs(dir, link))
    }

    @Test fun aStoreWithSystemCasLeavesNoSystemLink() {
        val link = File(tmp.root, "system-cas")
        Files.createSymbolicLink(link.toPath(), tmp.newFolder("old-system").toPath())
        val (dir, _, _) = exportInto { mapOf("system-a.0.pem" to der, "b.0.pem" to other) }
        assertEquals(setOf("system-a.0.pem", "b.0.pem"), dir.list()!!.toSet())
        // A system CA the user turned off must not come back through the system directory.
        assertFalse(Files.exists(link.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
    }

    @Test fun aStoreWithoutSystemCasLinksTheSystemDirectory() {
        val (dir, link, system) = exportInto { mapOf("b.0.pem" to other) }
        assertEquals(setOf("b.0.pem"), dir.list()!!.toSet())
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertEquals(system.toPath(), Files.readSymbolicLink(link.toPath()))
    }

    @Test fun anUnreadableStoreLinksTheSystemDirectoryAndExportsNothing() {
        val dir = tmp.newFolder("user-cas")
        File(dir, "old.pem").writeText(TrustedCas.pem(der))
        val (_, link, system) = exportInto { throw IllegalStateException("no store") }
        assertEquals(0, dir.list()!!.size)
        assertEquals(system.toPath(), Files.readSymbolicLink(link.toPath()))
    }

    @Test fun aLinkThatIsRightIsLeftAlone() {
        // Made again, it would be missing for a moment. A directory nobody may write shows it:
        // removing or making the link there fails.
        val parent = tmp.newFolder("no_backup")
        val link = File(parent, "system-cas")
        val system = tmp.newFolder("system")
        TrustedCas.point(link, system.path)
        assertTrue(parent.setWritable(false))
        try {
            TrustedCas.point(link, system.path)
        } finally {
            parent.setWritable(true)
        }
        assertEquals(system.toPath(), Files.readSymbolicLink(link.toPath()))
    }

    @Test fun anExportThatCannotBeWrittenTrustsTheSystemDirectoryAlone() {
        val dir = File(tmp.root, "user-cas").apply { writeText("not a directory") }
        val link = File(tmp.root, "system-cas")
        val system = tmp.newFolder("system")
        val exported = TrustedCas.export(dir, link, system) { mapOf("system-a.0.pem" to der) }
        assertEquals(listOf(system.path), exported.dirs)
        assertEquals(system.toPath(), Files.readSymbolicLink(link.toPath()))
        assertTrue(exported.problems.isNotEmpty())
    }

    @Test fun aLinkThatCannotBeMadeIsReportedNotThrown() {
        val parent = tmp.newFolder("no_backup")
        val dir = File(parent, "user-cas").apply { mkdirs() }
        val link = File(parent, "system-cas")
        val system = tmp.newFolder("system")
        assertTrue(parent.setWritable(false))
        val exported = try {
            TrustedCas.export(dir, link, system) { mapOf("b.0.pem" to other) }
        } finally {
            parent.setWritable(true)
        }
        assertTrue(exported.problems.any { it.first.contains("system CA link") })
    }

    @Test fun whatWaitsForTheExportRunsOnceItEndsAndAfterwardsAtOnce() {
        val posted = ArrayList<Runnable>()
        val ready = TrustedCas.Ready { posted += it }
        val ran = ArrayList<String>()
        ready.whenReady { ran += "first" }
        ready.whenReady { ran += "second" }
        assertEquals(emptyList<String>(), ran)
        ready.open()
        assertEquals(emptyList<String>(), ran) // Only on the poster's thread.
        posted.forEach { it.run() }
        assertEquals(listOf("first", "second"), ran)
        ready.whenReady { ran += "later" }
        assertEquals(listOf("first", "second", "later"), ran)
    }

    @Test fun theExportOpensTheWayToConnectingEvenWhenTheStoreFails() {
        val opened = java.util.concurrent.CountDownLatch(1)
        val ready = TrustedCas.start(File(tmp.root, "user-cas"), File(tmp.root, "system-cas"), { it.run() },
            system = tmp.newFolder("system"), note = {}) { throw IllegalStateException("no store") }
        ready.whenReady { opened.countDown() }
        assertTrue(opened.await(5, java.util.concurrent.TimeUnit.SECONDS))
    }

    @Test fun aStoreThatHangsGivesWayToTheSystemDirectoryUntilItAnswers() {
        // A KeyStore that does not answer must not keep the app from connecting: after the
        // deadline the system directory stands in, as for a store that cannot be read. When the
        // store answers after all, its CAs replace the stand-in for the next connection.
        val dir = tmp.newFolder("user-cas")
        File(dir, "old.pem").writeText(TrustedCas.pem(der))
        val link = File(tmp.root, "system-cas")
        val system = tmp.newFolder("system")
        val release = java.util.concurrent.CountDownLatch(1)
        val opened = java.util.concurrent.CountDownLatch(1)
        val notes = java.util.Collections.synchronizedList(ArrayList<TrustedCas.Exported>())
        val ready = TrustedCas.start(dir, link, { it.run() }, system = system, note = { notes += it }, deadlineMs = 200) {
            release.await()
            mapOf("system-a.0.pem" to der)
        }
        ready.whenReady { opened.countDown() }
        assertTrue(opened.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(0, dir.list()!!.size)
        assertEquals(system.toPath(), Files.readSymbolicLink(link.toPath()))
        release.countDown()
        val deadline = System.currentTimeMillis() + 5_000
        while (notes.size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(2, notes.size)
        assertEquals(setOf("system-a.0.pem"), dir.list()!!.toSet())
        assertFalse(Files.exists(link.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertTrue(notes[1].late)
    }

    @Test fun anExportWithinTheDeadlineIsWhatTheCoreTrusts() {
        val dir = File(tmp.root, "user-cas")
        val link = File(tmp.root, "system-cas")
        val opened = java.util.concurrent.CountDownLatch(1)
        val notes = java.util.Collections.synchronizedList(ArrayList<TrustedCas.Exported>())
        val ready = TrustedCas.start(dir, link, { it.run() }, system = tmp.newFolder("system"), note = { notes += it }, deadlineMs = 1_000) {
            mapOf("system-a.0.pem" to der)
        }
        ready.whenReady { opened.countDown() }
        assertTrue(opened.await(5, java.util.concurrent.TimeUnit.SECONDS))
        Thread.sleep(1_300) // Past the deadline: nothing may undo the export.
        assertEquals(1, notes.size)
        assertFalse(notes[0].late)
        assertEquals(setOf("system-a.0.pem"), dir.list()!!.toSet())
        assertFalse(Files.exists(link.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
    }

    // #338: the export leaves the directories as they are when the files the store reads are unchanged.

    /** The files `AndroidCAStore` reads, and the store as it reads them: system CAs not turned off, and user CAs. */
    private inner class Store {
        val system = tmp.newFolder("store-system")
        val added = tmp.newFolder("store-added")
        val removed = File(tmp.root, "store-removed")
        var build = "build 1"
        var reads = 0
        /** CAs the store gives beside its files (an OEM's own, say). */
        var beside = emptyMap<String, ByteArray>()
        /** Read beside the CA files, as the conscrypt manifest is. */
        val manifest = File(tmp.root, "store-manifest").apply { writeText("version 1") }
        var check: () -> TrustedCas.Sources = {
            TrustedCas.Sources.read(listOf(system), added, removed, files = listOf(manifest), context = listOf(build))
        }

        fun read(): Map<String, ByteArray> {
            reads++
            val off = removed.list().orEmpty().toSet()
            return system.listFiles()!!.filter { it.name !in off }.associate { TrustedCas.fileName("system:${it.name}") to it.readBytes() } +
                added.listFiles()!!.associate { TrustedCas.fileName("user:${it.name}") to it.readBytes() } + beside
        }
    }

    private val dir get() = File(tmp.root, "user-cas")
    private val link get() = File(tmp.root, "system-cas")
    private val record get() = File(tmp.root, "user-cas.source")

    private fun Store.export(gate: TrustedCas.Gate = TrustedCas.Gate(), read: () -> Map<String, ByteArray> = ::read) =
        TrustedCas.export(dir, link, system, gate, last = TrustedCas.LastExport(record) { check() }, read = read)

    /** Two system CAs, so that turning one off leaves a store with system CAs ([TrustedCas.chooseDirs]). */
    private fun storeWithCas() = Store().apply {
        File(system, "a.0").writeBytes(der)
        File(system, "b.0").writeBytes(der.reversedArray())
        File(added, "cf4d12bf.0").writeBytes(other)
    }

    private val exported = setOf("system-a.0.pem", "system-b.0.pem", "cf4d12bf.0.pem")

    private fun noLink() = assertFalse(Files.exists(link.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))

    @Test fun anUnchangedStoreIsNotReadAgain() {
        val store = storeWithCas()
        assertFalse(store.export().unchanged)
        val again = store.export()
        assertEquals(1, store.reads)
        assertTrue(again.unchanged)
        assertEquals(3, again.count)
        assertEquals(listOf(dir.path), again.dirs)
        assertEquals(exported, dir.list()!!.toSet())
        noLink()
    }

    @Test fun anUnchangedStoreOfUserCasKeepsTheSystemLink() {
        val store = Store().apply { File(added, "cf4d12bf.0").writeBytes(other) }
        store.export()
        val again = store.export()
        assertEquals(1, store.reads)
        assertTrue(again.unchanged)
        assertEquals(listOf(store.system.path, dir.path), again.dirs)
        assertEquals(store.system.toPath(), Files.readSymbolicLink(link.toPath()))
    }

    @Test fun aUserCaReplacedUnderItsNameIsExportedAgain() {
        // A user CA's file is named after its subject: another CA with the same subject takes the same name, and may
        // have the same length and time. The store's aliases stay the same; the bytes do not.
        val store = storeWithCas()
        store.export()
        val file = File(store.added, "cf4d12bf.0")
        val time = file.lastModified()
        val replaced = other.reversedArray()
        file.writeBytes(replaced)
        file.setLastModified(time)
        store.export()
        assertEquals(2, store.reads)
        assertEquals(TrustedCas.pem(replaced), File(dir, "cf4d12bf.0.pem").readText())
    }

    @Test fun aSystemCaTurnedOffIsExportedAgain() {
        val store = storeWithCas()
        store.export()
        store.removed.mkdirs()
        File(store.removed, "a.0").writeBytes(der)
        store.export()
        assertEquals(2, store.reads)
        assertEquals(setOf("system-b.0.pem", "cf4d12bf.0.pem"), dir.list()!!.toSet())
        noLink()
    }

    @Test fun aChangeWhileTheStoreIsReadIsNotRecordedAsTheFilesBefore() {
        // The files are checked, then the store read: a system CA turned back on meanwhile is exported, but the
        // digest taken before the read must not stand for it. Turned off again with the same bytes, the files would
        // match that digest, and the CA turned off would stay trusted.
        val store = storeWithCas()
        store.export()
        store.removed.mkdirs()
        File(store.removed, "a.0").writeBytes(der)
        store.export {
            File(store.removed, "a.0").delete()
            store.read()
        }
        File(store.removed, "a.0").writeBytes(der)
        store.export()
        assertEquals(3, store.reads)
        assertEquals(setOf("system-b.0.pem", "cf4d12bf.0.pem"), dir.list()!!.toSet())
    }

    @Test fun aSystemLinkChangedSinceIsMadeAgain() {
        val store = Store().apply { File(added, "cf4d12bf.0").writeBytes(other) }
        store.export()
        Files.delete(link.toPath())
        store.export()
        assertEquals(2, store.reads)
        assertEquals(store.system.toPath(), Files.readSymbolicLink(link.toPath()))
    }

    @Test fun anExportedFileChangedSinceIsWrittenAgain() {
        // As a crash may leave a file written just before: the export no longer rewrites every start, so what it
        // left must still be there to be kept.
        val store = storeWithCas()
        store.export()
        File(dir, "cf4d12bf.0.pem").writeText("")
        store.export()
        assertEquals(2, store.reads)
        assertEquals(TrustedCas.pem(other), File(dir, "cf4d12bf.0.pem").readText())
    }

    @Test fun anotherBuildIsExportedAgain() {
        val store = storeWithCas()
        store.export()
        store.build = "build 2"
        store.export()
        assertEquals(2, store.reads)
    }

    @Test fun anotherConscryptModuleIsExportedAgain() {
        val store = storeWithCas()
        store.export()
        store.manifest.writeText("version 2")
        store.export()
        assertEquals(2, store.reads)
    }

    @Test fun aSideFileThatCannotBeReadCountsAsSuch() {
        // The store's code comes with the module: a manifest the app may not read is no reason to read the store on
        // every start, but its becoming readable is a change.
        val store = storeWithCas()
        assertTrue(store.manifest.setReadable(false))
        store.export()
        val again = store.export()
        assertTrue(again.unchanged)
        assertEquals(listOf(store.manifest.path), store.check().unread)
        assertTrue(store.manifest.setReadable(true))
        store.export()
        assertEquals(2, store.reads)
    }

    @Test fun aStoreWithCasBesideTheFilesCheckedIsReadEveryTime() {
        val store = storeWithCas().apply { beside = mapOf("oem.0.pem" to der) }
        store.export()
        val again = store.export()
        assertEquals(2, store.reads)
        assertTrue(again.problems.any { "outside the files checked" in it.first })
        assertEquals(exported + "oem.0.pem", dir.list()!!.toSet())
    }

    @Test fun filesThatCannotBeReadMeanTheStoreIsRead() {
        val store = storeWithCas()
        store.export()
        store.check = { throw java.io.IOException("cannot read the user CAs") }
        store.export()
        assertEquals(2, store.reads)
    }

    @Test fun aCaDirectoryThatCannotBeListedMeansTheStoreIsRead() {
        val store = storeWithCas()
        store.export()
        assertTrue(store.added.setReadable(false))
        try {
            val again = store.export()
            assertEquals(2, store.reads)
            assertTrue(again.problems.any { it.first.startsWith("CA files unreadable") })
        } finally {
            store.added.setReadable(true)
        }
    }

    @Test fun nothingIsKeptOnceTheDeadlineStoodIn() {
        val store = storeWithCas()
        store.export()
        val again = store.export(TrustedCas.Gate().apply { stoodIn = true })
        assertEquals(2, store.reads)
        assertFalse(again.unchanged)
    }

    @Test fun anExportDirectoryChangedSinceIsWrittenAgain() {
        // As the stand-in leaves it: no file of the store's.
        val store = storeWithCas()
        store.export()
        dir.listFiles()!!.forEach { it.delete() }
        store.export()
        assertEquals(2, store.reads)
        assertEquals(exported, dir.list()!!.toSet())
    }

    @Test fun aCheckThatOutlastsTheDeadlineDoesNotKeepTheStandIn() {
        // The files are as at the last export, but the check took so long that the system directory stood in: the
        // store is read, and its CAs replace the stand-in.
        val store = storeWithCas()
        store.export()
        val release = java.util.concurrent.CountDownLatch(1)
        val checked = store.check
        store.check = { release.await(); checked() }
        val notes = java.util.Collections.synchronizedList(ArrayList<TrustedCas.Exported>())
        TrustedCas.start(dir, link, { it.run() }, system = store.system, note = { notes += it }, deadlineMs = 200,
            last = TrustedCas.LastExport(record) { store.check() }, read = store::read)
        val deadline = System.currentTimeMillis() + 5_000
        while (notes.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(0, dir.list()!!.size)
        release.countDown()
        while (notes.size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(2, notes.size)
        assertFalse(notes[1].unchanged)
        assertTrue(notes[1].late)
        assertEquals(2, store.reads)
        assertEquals(exported, dir.list()!!.toSet())
    }
}
