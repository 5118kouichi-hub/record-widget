package com.example.recordwidget

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TrackStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun t(n: Int, title: String = "曲$n") = Track("id$n", title, "f$n.m4a")

    @Test
    fun addKeepsOrderAndPersists() {
        val root = tmp.newFolder()
        val s = TrackStore(root)
        s.add(t(1)); s.add(t(2)); s.add(t(3))
        assertEquals(listOf(t(1), t(2), t(3)), s.all())
        assertEquals(listOf(t(1), t(2), t(3)), TrackStore(root).all())
    }

    @Test
    fun renamePersists() {
        val root = tmp.newFolder()
        val s = TrackStore(root)
        s.add(t(1)); s.add(t(2))
        s.rename("id2", "新しい名前")
        assertEquals("新しい名前", s.all()[1].title)
        assertEquals("新しい名前", TrackStore(root).all()[1].title)
        s.rename("missing", "x")
        assertEquals(2, s.all().size)
    }

    @Test
    fun removeDeletesFileAndReturnsTrack() {
        val root = tmp.newFolder()
        val s = TrackStore(root)
        s.add(t(1)); s.add(t(2))
        val f = s.fileFor(t(1))
        assertEquals(File(root, "tracks/f1.m4a"), f)
        f.parentFile!!.mkdirs()
        f.writeText("audio")
        assertEquals(t(1), s.remove("id1"))
        assertFalse(f.exists())
        assertEquals(listOf(t(2)), s.all())
        assertEquals(listOf(t(2)), TrackStore(root).all())
        assertNull(s.remove("id1"))
    }

    @Test
    fun removeWithoutFileOnDiskDoesNotCrash() {
        val s = TrackStore(tmp.newFolder())
        s.add(t(1))
        assertEquals(t(1), s.remove("id1"))
    }

    @Test
    fun moveReorders() {
        val root = tmp.newFolder()
        val s = TrackStore(root)
        s.add(t(1)); s.add(t(2)); s.add(t(3))
        s.move(0, 2)
        assertEquals(listOf("id2", "id3", "id1"), s.all().map { it.id })
        s.move(2, 0)
        assertEquals(listOf("id1", "id2", "id3"), s.all().map { it.id })
        s.move(5, 0)
        s.move(0, -1)
        assertEquals(listOf("id1", "id2", "id3"), s.all().map { it.id })
        s.move(1, 2)
        assertEquals(listOf("id1", "id3", "id2"), TrackStore(root).all().map { it.id })
    }

    @Test
    fun roundTripsSpecialCharacters() {
        val root = tmp.newFolder()
        val title = "He said \"hi\"\\ back\nnew\r\nline\ttab \u0001 日本語のタイトル 🎵"
        val s = TrackStore(root)
        s.add(Track("a\"b", title, "x.m4a"))
        s.add(t(2, ""))
        val reloaded = TrackStore(root).all()
        assertEquals(title, reloaded[0].title)
        assertEquals("a\"b", reloaded[0].id)
        assertEquals("", reloaded[1].title)
    }

    @Test
    fun corruptJsonGivesEmptyList() {
        for (bad in listOf("", "not json", "[{\"id\":", "{}", "[{\"id\":\"1\"}]", "[1,2]", "[] junk")) {
            val root = tmp.newFolder()
            File(root, "tracks.json").writeText(bad)
            val s = TrackStore(root)
            assertTrue("input: $bad", s.all().isEmpty())
            s.add(t(1))
            assertEquals(1, TrackStore(root).all().size)
        }
    }

    @Test
    fun missingFileGivesEmptyList() {
        assertTrue(TrackStore(File(tmp.root, "nope")).all().isEmpty())
    }

    @Test
    fun parsesUnicodeEscapesAndWhitespace() {
        val json = " [ { \"id\" : \"1\", \"title\":\"\\u3042\\/\", \"fileName\":\"f\" } ] "
        assertEquals(listOf(Track("1", "あ/", "f")), TrackStore.parse(json))
    }

    @Test
    fun instancesOnSameRootSeeEachOther() {
        val root = tmp.newFolder()
        val a = TrackStore(root)
        val b = TrackStore(root)
        a.add(t(1))
        assertEquals(listOf(t(1)), b.all())
        b.add(t(2))
        a.rename("id2", "改名")
        assertEquals(listOf("曲1", "改名"), b.all().map { it.title })
        b.move(0, 1)
        assertEquals(listOf("id2", "id1"), a.all().map { it.id })
        a.remove("id2")
        assertEquals(listOf("id1"), b.all().map { it.id })
    }

    @Test
    fun removeKeepsFileIfPersistFails() {
        val root = tmp.newFolder()
        val s = TrackStore(root)
        s.add(t(1))
        val f = s.fileFor(t(1))
        f.parentFile!!.mkdirs()
        f.writeText("audio")
        // Block both the temp file and the list file replacement by making tracks.json.tmp a directory.
        File(root, "tracks.json.tmp").mkdirs()
        try {
            s.remove("id1")
            org.junit.Assert.fail("remove should have thrown")
        } catch (e: java.io.IOException) {
            // expected: persisting failed
        }
        assertTrue(f.exists())
        assertEquals(listOf(t(1)), TrackStore(root).all())
    }

    @Test
    fun rejectsBadUnicodeEscape() {
        for (bad in listOf("\\u12", "\\u+123", "\\uzzzz")) {
            val json = "[{\"id\":\"1\",\"title\":\"$bad\",\"fileName\":\"f\"}]"
            try {
                TrackStore.parse(json)
                throw AssertionError("should fail: $bad")
            } catch (e: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun everyCorruptionIsBackedUp() {
        val root = tmp.newFolder()
        val list = File(root, "tracks.json")
        list.writeText("garbage")
        val s = TrackStore(root)
        s.add(t(1))
        assertEquals("garbage", File(root, "tracks.json.bak").readText())
        list.writeText("more garbage")
        s.add(t(2))
        assertEquals("more garbage", File(root, "tracks.json.bak").readText())
        assertEquals(listOf(t(2)), TrackStore(root).all())
    }
}
