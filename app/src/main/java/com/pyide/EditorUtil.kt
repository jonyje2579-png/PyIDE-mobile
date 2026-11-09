package com.pyide

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue

private val KEYWORDS = setOf(
    "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class",
    "continue", "def", "del", "elif", "else", "except", "finally", "for", "from", "global",
    "if", "import", "in", "is", "lambda", "nonlocal", "not", "or", "pass", "raise", "return",
    "try", "while", "with", "yield", "self", "print"
)

private val TOKEN = Regex(
    """(#[^\n]*)|("{3}[\s\S]*?"{3}|'{3}[\s\S]*?'{3}|"(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*')|(\b\d+(?:\.\d+)?\b)|(\b[A-Za-z_]\w*\b)"""
)

/** Номер строки (с 1) для смещения в тексте. */
fun lineOf(t: String, off: Int): Int {
    var n = 1
    val end = off.coerceIn(0, t.length)
    for (i in 0 until end) if (t[i] == '\n') n++
    return n
}

/** Смещение начала строки (с 1). */
fun offsetOfLine(t: String, line: Int): Int {
    var off = 0
    var l = 1
    while (l < line) {
        val i = t.indexOf('\n', off)
        if (i < 0) return t.length
        off = i + 1
        l++
    }
    return off
}

fun highlight(text: String, errLines: Set<Int>, dbgLine: Int, match: TextRange?): AnnotatedString =
    buildAnnotatedString {
        append(text)
        for (m in TOKEN.findAll(text)) {
            val color = when {
                m.groups[1] != null -> Color(0xFF7F848E)
                m.groups[2] != null -> Color(0xFF98C379)
                m.groups[3] != null -> Color(0xFFD19A66)
                m.groupValues[4] in KEYWORDS -> Color(0xFFC678DD)
                else -> null
            }
            if (color != null) addStyle(SpanStyle(color = color), m.range.first, m.range.last + 1)
        }
        if (errLines.isNotEmpty() || dbgLine > 0) {
            var start = 0
            var line = 1
            while (start <= text.length) {
                var end = text.indexOf('\n', start)
                if (end < 0) end = text.length
                if (end > start) {
                    if (line in errLines) addStyle(SpanStyle(background = Color(0x55FF5555)), start, end)
                    if (line == dbgLine) addStyle(SpanStyle(background = Color(0x66FFD54F)), start, end)
                }
                if (end >= text.length) break
                start = end + 1
                line++
            }
        }
        if (match != null && match.start < match.end && match.end <= text.length) {
            addStyle(SpanStyle(background = Color(0x99FF9800)), match.start, match.end)
        }
    }

/** Автоотступ после Enter: сохраняет отступ, +4 после ":", -4 после return/pass/break/continue. */
fun autoIndent(old: TextFieldValue, new: TextFieldValue): TextFieldValue {
    if (new.text.length == old.text.length + 1 && new.selection.collapsed) {
        val pos = new.selection.start
        if (pos > 0 && new.text[pos - 1] == '\n') {
            val prevStart = new.text.lastIndexOf('\n', pos - 2) + 1
            val prev = new.text.substring(prevStart, pos - 1)
            var indent = prev.takeWhile { it == ' ' }
            val t = prev.trim()
            if (t.endsWith(":")) {
                indent += "    "
            } else if (t.startsWith("return") || t == "pass" || t == "break" || t == "continue") {
                indent = indent.dropLast(minOf(4, indent.length))
            }
            val text = new.text.substring(0, pos) + indent + new.text.substring(pos)
            return new.copy(text = text, selection = TextRange(pos + indent.length))
        }
    }
    return new
}

fun insertAt(v: TextFieldValue, s: String, back: Int = 0): TextFieldValue {
    val a = v.selection.min
    val b = v.selection.max
    return v.copy(
        text = v.text.replaceRange(a, b, s),
        selection = TextRange(a + s.length - back)
    )
}

/** Закомментировать / раскомментировать выделенные строки. */
fun toggleComment(v: TextFieldValue): TextFieldValue {
    val t = v.text
    val a = t.lastIndexOf('\n', v.selection.min - 1) + 1
    var bEnd = t.indexOf('\n', v.selection.max)
    if (bEnd < 0) bEnd = t.length
    val block = t.substring(a, bEnd).split("\n")
    val nonEmpty = block.filter { it.isNotBlank() }
    val uncomment = nonEmpty.isNotEmpty() && nonEmpty.all { it.trimStart().startsWith("#") }
    val out = block.joinToString("\n") { line ->
        if (line.isBlank()) line
        else {
            val ind = line.takeWhile { it == ' ' }
            val body = line.drop(ind.length)
            if (uncomment) ind + body.removePrefix("#").removePrefix(" ") else "$ind# $body"
        }
    }
    return v.copy(
        text = t.substring(0, a) + out + t.substring(bEnd),
        selection = TextRange(a, a + out.length)
    )
}
