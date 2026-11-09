package com.pyide

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val Log = TextStyle(fontFamily = Mono, fontSize = 13.sp, color = Color(0xFFCFCFCF))
private val PanelBg = Color(0xFF111111)

@Composable
private fun SmallBtn(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(
        onClick = onClick, enabled = enabled,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        modifier = Modifier.height(32.dp)
    ) { Text(label, fontSize = 13.sp) }
}

// ---------------- Заголовок панели ----------------

@Composable
fun PanelHeader(ide: Ide) {
    val n = ide.tabs.getOrNull(ide.active)?.let { ide.problems[it.path]?.size } ?: 0
    val names = listOf("Консоль", "Терминал", "Отладка", "Проблемы" + if (n > 0) " ($n)" else "")
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF2B2B2B)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        names.forEachIndexed { i, name ->
            TextButton(
                onClick = {
                    if (ide.panelTab == i) ide.panelOpen = !ide.panelOpen
                    else { ide.panelTab = i; ide.panelOpen = true }
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Text(
                    name, fontSize = 13.sp,
                    color = if (ide.panelTab == i) Color.White else Color(0xFF8A8F98)
                )
            }
        }
        Spacer(Modifier.weight(1f))
        TextButton(
            onClick = { ide.panelOpen = !ide.panelOpen },
            contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = Modifier.height(34.dp)
        ) { Text(if (ide.panelOpen) "▾" else "▴") }
    }
}

// ---------------- Консоль ----------------

@Composable
fun ConsolePanel(ide: Ide) {
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    var input by remember { mutableStateOf("") }
    LaunchedEffect(ide.console.length) {
        delay(30)
        scroll.scrollTo(scroll.maxValue)
    }
    fun send() {
        if (!ide.runState.running) return
        val line = input
        input = ""
        scope.launch { ide.sendInput(line) }
    }
    Column(Modifier.fillMaxSize().background(PanelBg)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallBtn("■ Стоп", ide.runState.running) { scope.launch { ide.stop() } }
            SmallBtn("Очистить") { ide.console = "" }
            if (ide.runState.waitingInput) {
                Text("ждёт ввод ↓", color = Color(0xFFFFD54F), fontSize = 12.sp)
            }
        }
        Text(
            ide.console, style = Log,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(horizontal = 8.dp)
        )
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            MiniField(
                input, { input = it }, "Ввод для input() → Enter",
                Modifier.weight(1f), onSend = { send() },
                onFocus = { ide.panelFocused = it }
            )
            SmallBtn("▶", ide.runState.running) { send() }
        }
    }
}

// ---------------- Терминал (REPL) ----------------

@Composable
fun TerminalPanel(ide: Ide) {
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    var input by remember { mutableStateOf("") }
    LaunchedEffect(ide.repl.length) {
        delay(30)
        scroll.scrollTo(scroll.maxValue)
    }
    fun send() {
        val line = input
        input = ""
        scope.launch { ide.replSend(line) }
    }
    Column(Modifier.fillMaxSize().background(PanelBg)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallBtn("Сбросить") { scope.launch { ide.replReset() } }
            SmallBtn("Очистить") { ide.repl = "" }
        }
        Text(
            ide.repl, style = Log,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(horizontal = 8.dp)
        )
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (ide.replMore) "... " else ">>> ", style = Log)
            MiniField(
                input, { input = it }, "Python-команда",
                Modifier.weight(1f), onSend = { send() },
                onFocus = { ide.panelFocused = it }
            )
            SmallBtn("▶") { send() }
        }
    }
}

// ---------------- Отладка ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DebugPanel(ide: Ide) {
    val scope = rememberCoroutineScope()
    var expr by remember { mutableStateOf("") }
    val p = ide.runState.pause
    Column(Modifier.fillMaxSize().background(PanelBg)) {
        if (p == null) {
            Text(
                if (ide.runState.running) "Программа выполняется…"
                else "Тапни по номеру строки, чтобы поставить точку останова (●), затем нажми 🐞 вверху.",
                style = Log, modifier = Modifier.padding(12.dp)
            )
            return@Column
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            SmallBtn("▶ Дальше") { scope.launch { ide.dbg("c") } }
            SmallBtn("→ Шаг") { scope.launch { ide.dbg("n") } }
            SmallBtn("↓ В функцию") { scope.launch { ide.dbg("s") } }
            SmallBtn("↑ Выйти") { scope.launch { ide.dbg("r") } }
            SmallBtn("■ Стоп") { scope.launch { ide.stop() } }
        }
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            MiniField(
                expr, { expr = it }, "Выражение (eval)", Modifier.weight(1f),
                onSend = { scope.launch { ide.evalExpr(expr) } },
                onFocus = { ide.panelFocused = it }
            )
            SmallBtn("=") { scope.launch { ide.evalExpr(expr) } }
        }
        if (ide.evalResult.isNotEmpty()) {
            Text("→ ${ide.evalResult}", style = Log.copy(color = Color(0xFF98C379)),
                modifier = Modifier.padding(horizontal = 8.dp))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)) {
            item { Text("Стек вызовов", style = Log.copy(color = Color(0xFF61AFEF))) }
            items(p.stack) { s ->
                Text(
                    "${s.fn}()  ${File(s.file).name}:${s.line}", style = Log,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { ide.gotoLine(s.file, s.line) }
                        .padding(vertical = 2.dp)
                )
            }
            item { Text("Переменные", style = Log.copy(color = Color(0xFF61AFEF))) }
            items(p.vars) { v ->
                Text("${v.name}: ${v.type} = ${v.repr}", style = Log, modifier = Modifier.padding(vertical = 1.dp))
            }
        }
    }
}

// ---------------- Проблемы ----------------

@Composable
fun ProblemsPanel(ide: Ide) {
    val f = ide.tabs.getOrNull(ide.active)
    val list = f?.let { ide.problems[it.path] }.orEmpty()
    Column(Modifier.fillMaxSize().background(PanelBg)) {
        if (list.isEmpty()) {
            Text("Ошибок не найдено ✓", style = Log, modifier = Modifier.padding(12.dp))
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                items(list) { pr ->
                    Text(
                        "${pr.line}:${pr.col + 1}  ${pr.msg}",
                        style = Log.copy(color = Color(0xFFFF8A80)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { f?.let { ide.gotoLine(it.path, pr.line, pr.col) } }
                            .padding(vertical = 4.dp)
                    )
                }
            }
        }
    }
}

// ---------------- Диалог: пакеты (pip) ----------------

@Composable
fun PipDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var log by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Пакеты (pip)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Ставит чисто-Python пакеты с PyPI, а пакеты с C-кодом берёт готовыми " +
                        "Android-сборками из репозитория Chaquopy (pandas, pillow, scipy…). " +
                        "Уже встроены: numpy, requests, jedi, pyflakes, autopep8, dulwich.",
                    fontSize = 12.sp
                )
                MiniField(name, { name = it }, "например: rich  или  flask click", Modifier.fillMaxWidth())
                Row {
                    SmallBtn("Pandas") { name = "pandas" }
                    SmallBtn("Pillow") { name = "pillow" }
                    SmallBtn("SciPy") { name = "scipy" }
                }
                OutlinedButton(
                    enabled = !busy && name.isNotBlank(),
                    onClick = {
                        scope.launch {
                            busy = true
                            log = "Установка…"
                            log = withContext(Dispatchers.IO) { Backend.pip(name) }
                            busy = false
                        }
                    }
                ) { Text(if (busy) "Подожди…" else "Установить") }
                Text(log, style = Log, modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )
}

// ---------------- Диалог: поиск по проекту ----------------

@Composable
fun SearchDialog(ide: Ide, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf(listOf<Hit>()) }
    var searched by remember { mutableStateOf(false) }
    fun go() { scope.launch { hits = ide.searchProject(q); searched = true } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Поиск по проекту") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MiniField(q, { q = it }, "Что искать", Modifier.weight(1f), onSend = { go() })
                    SmallBtn("Найти") { go() }
                }
                if (searched && hits.isEmpty()) Text("Ничего не найдено", fontSize = 13.sp)
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(hits) { h ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { ide.gotoLine(h.file.path, h.line); onDismiss() }
                                .padding(vertical = 4.dp)
                        ) {
                            Text("${h.file.relativeTo(ide.root).path}:${h.line}", fontSize = 12.sp, color = Color(0xFF61AFEF))
                            Text(h.text, style = Log, maxLines = 1)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )
}

// ---------------- Диалог: Git ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GitDialog(ide: Ide, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var dir by remember { mutableStateOf(".") }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var out by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun run(cmd: String, a: String = "", b: String = "", c: String = "") {
        scope.launch {
            busy = true
            out = "…"
            out = withContext(Dispatchers.IO) {
                try { Backend.git(cmd, dir, a, b, c) } catch (e: Exception) { "Ошибка: $e" }
            }
            ide.refresh()
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(0.97f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("Git") },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MiniField(dir, { dir = it }, "Папка репозитория (. = корень проекта)", Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(enabled = !busy, onClick = { run("init") }) { Text("Init", fontSize = 12.sp) }
                    OutlinedButton(enabled = !busy, onClick = { run("status") }) { Text("Status", fontSize = 12.sp) }
                    OutlinedButton(enabled = !busy, onClick = { run("diff") }) { Text("Diff", fontSize = 12.sp) }
                    OutlinedButton(enabled = !busy, onClick = { run("log") }) { Text("Log", fontSize = 12.sp) }
                }
                Text("Коммит", fontSize = 12.sp, color = Color(0xFF61AFEF))
                MiniField(name, { name = it }, "Имя автора", Modifier.fillMaxWidth())
                MiniField(email, { email = it }, "Email автора", Modifier.fillMaxWidth())
                MiniField(msg, { msg = it }, "Сообщение коммита", Modifier.fillMaxWidth())
                OutlinedButton(enabled = !busy, onClick = { run("commit", msg, name, email) }) {
                    Text("Добавить всё и закоммитить", fontSize = 12.sp)
                }
                Text("Удалённый репозиторий (токен в память, на диск не пишется)", fontSize = 12.sp, color = Color(0xFF61AFEF))
                MiniField(url, { url = it }, "https://github.com/user/repo.git", Modifier.fillMaxWidth())
                MiniField(user, { user = it }, "Логин", Modifier.fillMaxWidth())
                MiniField(token, { token = it }, "Токен (PAT)", Modifier.fillMaxWidth(), password = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(enabled = !busy && url.isNotBlank(), onClick = {
                        val nm = url.trimEnd('/').substringAfterLast('/').removeSuffix(".git")
                        dir = nm
                        run("clone", url, user, token)
                    }) { Text("Clone", fontSize = 12.sp) }
                    OutlinedButton(enabled = !busy && url.isNotBlank(), onClick = { run("remote", url) }) {
                        Text("Remote", fontSize = 12.sp)
                    }
                    OutlinedButton(enabled = !busy, onClick = { run("push", user, token) }) { Text("Push", fontSize = 12.sp) }
                    OutlinedButton(enabled = !busy, onClick = { run("pull", user, token) }) { Text("Pull", fontSize = 12.sp) }
                }
                Text(out, style = Log, modifier = Modifier.fillMaxWidth().background(PanelBg).padding(8.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )
}
