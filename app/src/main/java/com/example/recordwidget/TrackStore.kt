package com.example.recordwidget

import java.io.File

/**
 * One saved sound. A local track has its audio in `tracks/<fileName>`; a web track has
 * [url] set (streamed, nothing stored) and an empty [fileName].
 */
data class Track(
    val id: String,
    val title: String,
    val fileName: String,
    val url: String? = null,
) {
    val isRemote: Boolean get() = !url.isNullOrBlank()
}

/**
 * Persists the track list in `root/tracks.json`; audio files live in `root/tracks/`.
 *
 * There is no in-memory cache: [all] and every mutation re-read the file (read-modify-write),
 * so several instances on the same root (widget, service, activity) never go stale.
 * Operations are serialized on a process-wide lock.
 */
class TrackStore(private val root: File) {
    private val listFile = File(root, "tracks.json")

    fun all(): List<Track> = synchronized(LOCK) { load() }

    fun fileFor(t: Track): File = File(root, "tracks/" + t.fileName)

    /** Round thumbnail shown in the middle of the widget's record (may not exist). */
    fun thumbFor(id: String): File = File(root, "thumbs/$id.png")

    fun add(t: Track) {
        synchronized(LOCK) {
            val list = load()
            list.add(t)
            save(list)
        }
    }

    fun rename(id: String, title: String) {
        synchronized(LOCK) {
            val list = load()
            val i = list.indexOfFirst { it.id == id }
            if (i >= 0) {
                list[i] = list[i].copy(title = title)
                save(list)
            }
        }
    }

    fun remove(id: String): Track? {
        return synchronized(LOCK) {
            val list = load()
            val i = list.indexOfFirst { it.id == id }
            if (i < 0) return@synchronized null
            val removed = list.removeAt(i)
            save(list) // persist first; if this throws the audio file is kept
            if (!removed.isRemote) fileFor(removed).delete()
            thumbFor(removed.id).delete()
            removed
        }
    }

    fun move(from: Int, to: Int) {
        synchronized(LOCK) {
            val list = load()
            if (from in list.indices && to in list.indices && from != to) {
                list.add(to, list.removeAt(from))
                save(list)
            }
        }
    }

    /**
     * Empty if the file is absent or its JSON is malformed (the corrupt file is first copied to
     * tracks.json.bak, so the next save cannot destroy it). IOExceptions propagate so a
     * mutation aborts instead of overwriting the list with a partial one.
     */
    private fun load(): MutableList<Track> {
        if (!listFile.isFile) return mutableListOf()
        val text = listFile.readText(Charsets.UTF_8)
        return try {
            parse(text).toMutableList()
        } catch (e: IllegalArgumentException) {
            // Not swallowed: if the backup cannot be written, the mutation aborts.
            File(root, "tracks.json.bak").writeText(text, Charsets.UTF_8)
            mutableListOf()
        }
    }

    private fun save(list: List<Track>) {
        root.mkdirs()
        // Write to a temp file then rename so a crash cannot leave a half-written list.
        val tmp = File(root, "tracks.json.tmp")
        tmp.writeText(serialize(list), Charsets.UTF_8)
        if (!tmp.renameTo(listFile)) {
            listFile.writeText(serialize(list), Charsets.UTF_8)
            tmp.delete()
        }
    }

    internal companion object {
        private val LOCK = Any()

        fun serialize(list: List<Track>): String {
            val sb = StringBuilder("[")
            list.forEachIndexed { i, t ->
                if (i > 0) sb.append(',')
                sb.append("{\"id\":").append(quote(t.id))
                    .append(",\"title\":").append(quote(t.title))
                    .append(",\"fileName\":").append(quote(t.fileName))
                if (t.url != null) sb.append(",\"url\":").append(quote(t.url))
                sb.append('}')
            }
            return sb.append(']').toString()
        }

        private fun quote(s: String): String {
            val sb = StringBuilder("\"")
            for (c in s) {
                when {
                    c == '"' -> sb.append("\\\"")
                    c == '\\' -> sb.append("\\\\")
                    c == '\n' -> sb.append("\\n")
                    c == '\r' -> sb.append("\\r")
                    c == '\t' -> sb.append("\\t")
                    c < ' ' -> sb.append("\\u").append(String.format("%04x", c.code))
                    else -> sb.append(c)
                }
            }
            return sb.append('"').toString()
        }

        /** Parses the list; throws on malformed input (caller treats that as empty). */
        fun parse(json: String): List<Track> = Parser(json).parseList()

        private class Parser(private val s: String) {
            private var p = 0

            fun parseList(): List<Track> {
                val out = ArrayList<Track>()
                ws()
                expect('[')
                ws()
                if (peek() == ']') { p++ } else {
                    while (true) {
                        out.add(parseTrack())
                        ws()
                        when (next()) {
                            ',' -> ws()
                            ']' -> break
                            else -> fail()
                        }
                    }
                }
                ws()
                if (p != s.length) fail()
                return out
            }

            private fun parseTrack(): Track {
                ws()
                expect('{')
                val m = HashMap<String, String>()
                ws()
                if (peek() == '}') { p++ } else {
                    while (true) {
                        ws()
                        val k = parseString()
                        ws()
                        expect(':')
                        ws()
                        m[k] = parseString()
                        ws()
                        when (next()) {
                            ',' -> {}
                            '}' -> break
                            else -> fail()
                        }
                    }
                }
                return Track(
                    m["id"] ?: fail(),
                    m["title"] ?: fail(),
                    m["fileName"] ?: fail(),
                    m["url"],
                )
            }

            private fun parseString(): String {
                expect('"')
                val sb = StringBuilder()
                while (true) {
                    val c = next()
                    when (c) {
                        '"' -> return sb.toString()
                        '\\' -> when (next()) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            'u' -> {
                                if (p + 4 > s.length) fail()
                                val hex = s.substring(p, p + 4)
                                if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) fail()
                                sb.append(hex.toInt(16).toChar())
                                p += 4
                            }
                            else -> fail()
                        }
                        else -> sb.append(c)
                    }
                }
            }

            private fun ws() {
                while (p < s.length && s[p].isWhitespace()) p++
            }

            private fun peek(): Char = if (p < s.length) s[p] else fail()
            private fun next(): Char = if (p < s.length) s[p++] else fail()
            private fun expect(c: Char) { if (next() != c) fail() }
            private fun fail(): Nothing = throw IllegalArgumentException("bad json at $p")
        }
    }
}
