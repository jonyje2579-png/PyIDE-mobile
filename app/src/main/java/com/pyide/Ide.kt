package com.pyide

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class Hit(val file: File, val line: Int, val text: String)

class Ide(val root: File) {
    // --- файлы и вкладки ---
    val tabs = mutableStateListOf<File>()
    val texts = mutableStateMapOf<String, TextFieldValue>()
    var active by mutableIntStateOf(0)
    var files by mutableStateOf(listOf<File>())

    // --- редактор ---
    val problems = mutableStateMapOf<String, List<Problem>>()
    var suggestions by mutableStateOf(listOf<Completion>())
    var scrollLine by mutableIntStateOf(-1)
    var status by mutableStateOf("")

    private val undoMap = mutableMapOf<String, ArrayDeque<TextFieldValue>>()
    private val redoMap = mutableMapOf<String, ArrayDeque<TextFieldValue>>()
    private var lastEdit = 0L

    // --- поиск / замена ---
    var findOpen by mutableStateOf(false)
    var findText by mutableStateOf("")
    var replaceText by mutableStateOf("")
    var match by mutableStateOf<TextRange?>(null)

    // --- запуск / отладка ---
    var console by mutableStateOf("")
    var runState by mutableStateOf(RunState(false, false, null))
    var polling = false
    var panelTab by mutableIntStateOf(0)
    var panelOpen by mutableStateOf(true)
    var panelFocused by mutableStateOf(false)
    val breakpoints = mutableStateMapOf<String, Set<Int>>()
    var evalResult by mutableStateOf("")
    private var lastPauseId = 0

    // --- терминал ---
    var repl by mutableStateOf(">>> Python REPL\n")
    var replMore by mutableStateOf(false)

    // ================= файлы =================

    fun refresh() {
        files = root.walkTopDown()
            .onEnter { it.name != ".git" && it.name != "__pycache__" }
            .filter { it.isFile }
            .sortedBy { it.path }
            .toList()
    }

    fun open(f: File) {
        if (tabs.none { it.path == f.path }) {
            tabs.add(f)
            texts[f.path] = TextFieldValue(f.readText())
        }
        active = tabs.indexOfFirst { it.path == f.path }
    }

    fun close(i: Int) {
        val p = tabs[i].path
        texts.remove(p)
        undoMap.remove(p)
        redoMap.remove(p)
        tabs.removeAt(i)
        if (i < active) active -= 1
        active = active.coerceIn(0, maxOf(0, tabs.size - 1))
    }

    fun save(f: File) {
        texts[f.path]?.let { f.writeText(it.text) }
    }

    private fun inside(f: File) = f.canonicalPath.startsWith(root.canonicalPath + File.separator)

    fun create(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        val f = File(root, clean)
        if (!inside(f)) return
        f.parentFile?.mkdirs()
        if (!f.exists()) f.writeText("")
        refresh()
        open(f)
    }

    fun rename(f: File, newName: String) {
        val n = File(root, newName.trim())
        if (newName.isBlank() || !inside(n) || n.exists()) return
        val i = tabs.indexOfFirst { it.path == f.path }
        if (i >= 0) close(i)
        n.parentFile?.mkdirs()
        f.renameTo(n)
        refresh()
        if (i >= 0) open(n)
    }

    fun delete(f: File) {
        val i = tabs.indexOfFirst { it.path == f.path }
        if (i >= 0) close(i)
        f.delete()
        breakpoints.remove(f.path)
        refresh()
    }

    // ================= правка, undo/redo =================

    fun edit(f: File, new: TextFieldValue) {
        val old = texts[f.path] ?: TextFieldValue("")
        if (new.text != old.text) {
            val now = System.currentTimeMillis()
            val st = undoMap.getOrPut(f.path) { ArrayDeque() }
            if (st.isEmpty() || now - lastEdit > 700) {
                st.addLast(old)
                if (st.size > 200) st.removeFirst()
            }
            lastEdit = now
            redoMap[f.path]?.clear()
            match = null
        }
        texts[f.path] = new
        if (new.text != old.text) save(f)
    }

    fun undo(f: File) {
        val st = undoMap[f.path] ?: return
        if (st.isEmpty()) return
        redoMap.getOrPut(f.path) { ArrayDeque() }.addLast(texts[f.path] ?: return)
        texts[f.path] = st.removeLast()
        save(f)
    }

    fun redo(f: File) {
        val st = redoMap[f.path] ?: return
        if (st.isEmpty()) return
        undoMap.getOrPut(f.path) { ArrayDeque() }.addLast(texts[f.path] ?: return)
        texts[f.path] = st.removeLast()
        save(f)
    }

    /** Tab: одно совпадение подставляется, несколько - общий префикс (список виден в полосе), иначе отступ. */
    fun tab(f: File) {
        val cur = texts[f.path] ?: return
        val s = suggestions
        when {
            s.size == 1 -> {
                val c = s[0]
                val extra = if (c.type == "function" || c.type == "class") "(" else ""
                edit(f, insertAt(cur, c.rest + extra))
                suggestions = emptyList()
            }
            s.size > 1 -> {
                val common = s.map { it.rest }.reduce { a, b -> a.commonPrefixWith(b) }
                if (common.isNotEmpty()) edit(f, insertAt(cur, common))
            }
            else -> edit(f, insertAt(cur, "    "))
        }
    }

    fun comment(f: File) {
        val v = texts[f.path] ?: return
        edit(f, toggleComment(v))
    }

    fun gotoLine(path: String, line: Int, col: Int = 0) {
        val f = File(path)
        if (!f.exists()) return
        open(f)
        val v = texts[f.path] ?: return
        val off = (offsetOfLine(v.text, line) + col).coerceIn(0, v.text.length)
        texts[f.path] = v.copy(selection = TextRange(off))
        scrollLine = line
    }

    // ================= поиск / замена =================

    fun findNext(f: File, forward: Boolean = true) {
        val v = texts[f.path] ?: return
        val q = findText
        if (q.isEmpty()) return
        var i = if (forward) v.text.indexOf(q, v.selection.max, ignoreCase = true)
        else v.text.lastIndexOf(q, v.selection.min - 1, ignoreCase = true)
        if (i < 0) {
            i = if (forward) v.text.indexOf(q, 0, ignoreCase = true)
            else v.text.lastIndexOf(q, ignoreCase = true)
        }
        if (i >= 0) {
            val r = TextRange(i, i + q.length)
            texts[f.path] = v.copy(selection = r)
            match = r
            scrollLine = lineOf(v.text, i)
            status = ""
        } else {
            match = null
            status = "Не найдено: $q"
        }
    }

    fun replaceOne(f: File) {
        val v = texts[f.path] ?: return
        val m = match
        if (m != null && m.end <= v.text.length &&
            v.text.substring(m.start, m.end).equals(findText, ignoreCase = true)
        ) {
            val t = v.text.replaceRange(m.start, m.end, replaceText)
            edit(f, v.copy(text = t, selection = TextRange(m.start + replaceText.length)))
        }
        findNext(f)
    }

    fun replaceAll(f: File) {
        val v = texts[f.path] ?: return
        if (findText.isEmpty()) return
        val re = Regex(Regex.escape(findText), RegexOption.IGNORE_CASE)
        val n = re.findAll(v.text).count()
        edit(f, v.copy(text = re.replace(v.text, Regex.escapeReplacement(replaceText))))
        status = "Заменено: $n"
    }

    suspend fun searchProject(q: String): List<Hit> = withContext(Dispatchers.IO) {
        val hits = mutableListOf<Hit>()
        if (q.isBlank()) return@withContext hits
        val all = root.walkTopDown()
            .onEnter { it.name != ".git" && it.name != "__pycache__" }
            .filter { it.isFile && it.length() < 1_000_000 }
        for (f in all) {
            val lines = try { f.readLines() } catch (e: Exception) { continue }
            for ((i, l) in lines.withIndex()) {
                if (l.contains(q, ignoreCase = true)) {
                    hits.add(Hit(f, i + 1, l.trim()))
                    if (hits.size >= 300) return@withContext hits
                }
            }
        }
        hits
    }

    // ================= умный редактор =================

    suspend fun lint(f: File) {
        val src = texts[f.path]?.text ?: return
        val r = try {
            withContext(Dispatchers.IO) { Backend.lint(src, f.absolutePath) }
        } catch (e: Exception) { emptyList() }
        problems[f.path] = r
    }

    suspend fun complete(f: File) {
        val v = texts[f.path] ?: return
        val pos = v.selection.start
        val line = lineOf(v.text, pos)
        val col = pos - (v.text.lastIndexOf('\n', pos - 1) + 1)
        val r = try {
            withContext(Dispatchers.IO) { Backend.complete(v.text, line, col, f.absolutePath) }
        } catch (e: Exception) { emptyList() }
        if (texts[f.path]?.text == v.text) suggestions = r
    }

    suspend fun format(f: File) {
        val v = texts[f.path] ?: return
        val out = withContext(Dispatchers.IO) { Backend.format(v.text) }
        if (out != v.text) {
            edit(f, v.copy(text = out, selection = TextRange(minOf(v.selection.start, out.length))))
            status = "Отформатировано"
        } else status = "Уже отформатировано"
    }

    suspend fun gotoDef(f: File) {
        val v = texts[f.path] ?: return
        val pos = v.selection.start
        val line = lineOf(v.text, pos)
        val col = pos - (v.text.lastIndexOf('\n', pos - 1) + 1)
        val defs = withContext(Dispatchers.IO) { Backend.goto(v.text, line, col, f.absolutePath) }
        val d = defs.firstOrNull { it.path.startsWith(root.path) && File(it.path).exists() }
        if (d != null) gotoLine(d.path, d.line, d.col)
        else status = if (defs.isEmpty()) "Определение не найдено" else "Определение вне проекта"
    }

    // ================= запуск / отладка =================

    fun toggleBreak(f: File, line: Int) {
        val cur = breakpoints[f.path] ?: emptySet()
        breakpoints[f.path] = if (line in cur) cur - line else cur + line
    }

    suspend fun launch(debug: Boolean) {
        val f = tabs.getOrNull(active) ?: return
        if (runState.running) return
        tabs.forEach { save(it) }
        console = "▶ ${if (debug) "Отладка" else "Запуск"} ${f.name}\n"
        panelTab = if (debug) 2 else 0
        panelOpen = true
        lastPauseId = 0
        withContext(Dispatchers.IO) {
            if (debug) breakpoints.forEach { (p, ls) -> Backend.setBreaks(p, ls) }
            Backend.start(f.absolutePath, debug)
        }
        runState = RunState(true, false, null)
        polling = true
    }

    suspend fun stop() = withContext(Dispatchers.IO) { Backend.stop() }

    suspend fun poll() {
        val (st, out) = withContext(Dispatchers.IO) { Backend.state() to Backend.drain() }
        if (out.isNotEmpty()) console = (console + out).takeLast(60000)
        runState = st
        val p = st.pause
        if (p != null && p.id != lastPauseId) {
            lastPauseId = p.id
            gotoLine(p.file, p.line)
            panelTab = 2
            panelOpen = true
        }
        if (!st.running) polling = false
    }

    suspend fun sendInput(line: String) = withContext(Dispatchers.IO) { Backend.sendInput(line) }

    suspend fun dbg(cmd: String) = withContext(Dispatchers.IO) { Backend.dbgCmd(cmd) }

    suspend fun evalExpr(expr: String) {
        evalResult = withContext(Dispatchers.IO) { Backend.dbgEval(expr) }
    }

    // ================= терминал =================

    suspend fun replSend(line: String) {
        repl += (if (replMore) "... " else ">>> ") + line + "\n"
        val (out, more) = withContext(Dispatchers.IO) { Backend.replPush(line) }
        repl = (repl + out).takeLast(60000)
        replMore = more
    }

    suspend fun replReset() {
        withContext(Dispatchers.IO) { Backend.replReset() }
        repl = ">>> Python REPL (сброшен)\n"
        replMore = false
    }
}
