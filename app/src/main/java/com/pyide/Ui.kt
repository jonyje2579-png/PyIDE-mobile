package com.pyide

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

val Mono = FontFamily.Monospace
val EditorBg = Color(0xFF1E1E1E)

/** Компактное однострочное поле ввода (обычный OutlinedTextField слишком высокий для телефона). */
@Composable
fun MiniField(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    onSend: (() -> Unit)? = null,
    onFocus: ((Boolean) -> Unit)? = null,
) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 14.sp, fontFamily = Mono),
        cursorBrush = SolidColor(Color.White),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = if (onSend != null) ImeAction.Send else ImeAction.Default
        ),
        keyboardActions = KeyboardActions(onSend = { onSend?.invoke() }),
        modifier = modifier
            .background(Color(0xFF333333), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .onFocusChanged { onFocus?.invoke(it.isFocused) },
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(hint, color = Color.Gray, fontSize = 14.sp, fontFamily = Mono)
                inner()
            }
        }
    )
}

// ---------------- Редактор ----------------

@Composable
fun EditorPane(ide: Ide, f: File, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val v = ide.texts[f.path] ?: TextFieldValue("")
    val density = LocalDensity.current
    val lhPx = with(density) { 20.sp.toPx() }
    val vs = rememberScrollState()
    val hs = rememberScrollState()

    val probs = ide.problems[f.path].orEmpty()
    val errLines = remember(probs) { probs.map { it.line }.toSet() }
    val pause = ide.runState.pause
    val dbgLine = if (pause != null && pause.file == f.path) pause.line else -1
    val match = ide.match
    val transform = remember(errLines, dbgLine, match) {
        VisualTransformation {
            TransformedText(highlight(it.text, errLines, dbgLine, match), OffsetMapping.Identity)
        }
    }

    LaunchedEffect(v.text) {
        delay(700)
        ide.lint(f)
    }
    LaunchedEffect(v.text, v.selection) {
        val pos = v.selection.start
        val c = if (pos > 0 && pos <= v.text.length) v.text[pos - 1] else ' '
        val ok = v.selection.collapsed && (c.isLetterOrDigit() || c == '_' || c == '.')
        if (!ok) {
            ide.suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(250)
        ide.complete(f)
    }
    LaunchedEffect(ide.scrollLine) {
        if (ide.scrollLine >= 0) {
            delay(60)
            vs.animateScrollTo(((ide.scrollLine - 3).coerceAtLeast(0) * lhPx).toInt())
            ide.scrollLine = -1
        }
    }

    val lines = v.text.count { it == '\n' } + 1
    val bps = ide.breakpoints[f.path].orEmpty()
    val gutter = remember(lines, bps, dbgLine) {
        buildAnnotatedString {
            for (i in 1..lines) {
                when {
                    i == dbgLine -> withStyle(SpanStyle(color = Color(0xFFFFD54F))) { append("▶$i") }
                    i in bps -> withStyle(SpanStyle(color = Color(0xFFFF5252))) { append("●$i") }
                    else -> append("$i")
                }
                if (i < lines) append("\n")
            }
        }
    }
    val style = TextStyle(
        fontFamily = Mono, fontSize = 14.sp, lineHeight = 20.sp, color = Color(0xFFE0E0E0)
    )

    Row(
        modifier
            .fillMaxWidth()
            .background(EditorBg)
            .verticalScroll(vs)
            .padding(vertical = 4.dp)
    ) {
        Text(
            gutter,
            style = style.copy(color = Color(0xFF6B7078), textAlign = TextAlign.End),
            modifier = Modifier
                .width(58.dp)
                .padding(end = 8.dp)
                .pointerInput(lines) {
                    detectTapGestures { off ->
                        val line = (off.y / lhPx).toInt() + 1
                        if (line in 1..lines) {
                            ide.toggleBreak(f, line)
                            val set = ide.breakpoints[f.path].orEmpty()
                            scope.launch(Dispatchers.IO) {
                                try { Backend.setBreaks(f.absolutePath, set) } catch (_: Exception) {}
                            }
                        }
                    }
                }
        )
        BasicTextField(
            value = v,
            onValueChange = { n -> ide.edit(f, autoIndent(v, n)) },
            textStyle = style,
            cursorBrush = SolidColor(Color.White),
            visualTransformation = transform,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false
            ),
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(hs)
                .defaultMinSize(minHeight = 300.dp)
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Tab) {
                        ide.tab(f)
                        true
                    } else false
                }
        )
    }
}

// ---------------- Подсказки и быстрые клавиши ----------------

private fun typeColor(t: String) = when (t) {
    "function" -> Color(0xFF61AFEF)
    "class" -> Color(0xFFE5C07B)
    "module" -> Color(0xFF56B6C2)
    "keyword" -> Color(0xFFC678DD)
    else -> Color(0xFFE0E0E0)
}

@Composable
fun SuggestionBar(ide: Ide, f: File) {
    if (ide.suggestions.isEmpty()) return
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF333940))
            .horizontalScroll(rememberScrollState())
    ) {
        ide.suggestions.forEach { c ->
            TextButton(
                onClick = {
                    val cur = ide.texts[f.path]
                    if (cur != null) ide.edit(f, insertAt(cur, c.rest))
                    ide.suggestions = emptyList()
                },
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) { Text(c.name, color = typeColor(c.type), fontFamily = Mono, fontSize = 14.sp) }
        }
    }
}

private data class QuickKey(val label: String, val text: String, val back: Int = 0)

private val QUICK = listOf(
    QuickKey("Tab", "    "),
    QuickKey("()", "()", 1), QuickKey("[]", "[]", 1), QuickKey("{}", "{}", 1),
    QuickKey("\"\"", "\"\"", 1), QuickKey("''", "''", 1),
    QuickKey(":", ":"), QuickKey("=", "="), QuickKey("#", "#"), QuickKey("_", "_"),
    QuickKey(".", "."), QuickKey(",", ","), QuickKey("+", "+"), QuickKey("-", "-"),
    QuickKey("*", "*"), QuickKey("/", "/"), QuickKey("<", "<"), QuickKey(">", ">"),
)

@Composable
private fun QBtn(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 10.dp)) {
        Text(label, fontFamily = Mono, fontSize = 16.sp)
    }
}

@Composable
fun QuickBar(ide: Ide, f: File) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF2B2B2B))
            .horizontalScroll(rememberScrollState())
    ) {
        QBtn("↶") { ide.undo(f) }
        QBtn("↷") { ide.redo(f) }
        QUICK.forEach { k ->
            QBtn(k.label) {
                if (k.label == "Tab") ide.tab(f)
                else {
                    val cur = ide.texts[f.path]
                    if (cur != null) ide.edit(f, insertAt(cur, k.text, k.back))
                }
            }
        }
    }
}

// ---------------- Поиск / замена ----------------

@Composable
fun FindBar(ide: Ide, f: File) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF2B2B2B))
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MiniField(ide.findText, { ide.findText = it }, "Найти", Modifier.weight(1f))
            QBtn("↑") { ide.findNext(f, false) }
            QBtn("↓") { ide.findNext(f, true) }
            QBtn("✕") { ide.findOpen = false; ide.match = null }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            MiniField(ide.replaceText, { ide.replaceText = it }, "Заменить на", Modifier.weight(1f))
            TextButton(onClick = { ide.replaceOne(f) }) { Text("Заменить", fontSize = 13.sp) }
            TextButton(onClick = { ide.replaceAll(f) }) { Text("Все", fontSize = 13.sp) }
        }
    }
}
