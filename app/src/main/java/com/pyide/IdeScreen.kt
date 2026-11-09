package com.pyide

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdeScreen(ide: Ide) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<File?>(null) }

    // опрос состояния запущенной программы / отладчика
    LaunchedEffect(Unit) {
        while (true) {
            if (ide.polling) {
                try { ide.poll() } catch (_: Exception) { ide.polling = false }
            }
            delay(120)
        }
    }

    val f = ide.tabs.getOrNull(ide.active)
    val running = ide.runState.running

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Text("Файлы проекта", modifier = Modifier.padding(16.dp), fontSize = 18.sp)
                Row(Modifier.padding(horizontal = 12.dp)) {
                    Button(onClick = { newName = ""; dialog = "new" }) { Text("+ Файл") }
                    TextButton(onClick = { dialog = "search"; scope.launch { drawer.close() } }) {
                        Text("Поиск")
                    }
                }
                LazyColumn(Modifier.padding(top = 8.dp)) {
                    items(ide.files) { file ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { ide.open(file); scope.launch { drawer.close() } }
                                .padding(start = 16.dp, end = 0.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(file.relativeTo(ide.root).path, Modifier.weight(1f))
                            TextButton(onClick = {
                                newName = file.relativeTo(ide.root).path
                                renameTarget = file
                                dialog = "rename"
                            }) { Text("✎") }
                            IconButton(onClick = { ide.delete(file) }) {
                                Icon(Icons.Default.Delete, "Удалить")
                            }
                        }
                    }
                }
            }
        }
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = { Text("PyIDE") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawer.open() } }) {
                            Icon(Icons.Default.Menu, "Файлы")
                        }
                    },
                    actions = {
                        IconButton(
                            enabled = !running && f != null,
                            onClick = { scope.launch { ide.launch(false) } }
                        ) { Text("▶", color = Color(0xFF4CAF50), fontSize = 20.sp) }
                        IconButton(
                            enabled = !running && f != null,
                            onClick = { scope.launch { ide.launch(true) } }
                        ) { Text("🐞", fontSize = 18.sp) }
                        IconButton(
                            enabled = running,
                            onClick = { scope.launch { ide.stop() } }
                        ) { Text("■", color = if (running) Color(0xFFFF5252) else Color.Gray, fontSize = 20.sp) }
                        Box {
                            IconButton(onClick = { menu = true }) { Text("⋮", fontSize = 22.sp) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Найти / заменить") }, onClick = {
                                    menu = false; ide.findOpen = !ide.findOpen
                                })
                                DropdownMenuItem(text = { Text("Форматировать код") }, onClick = {
                                    menu = false; f?.let { scope.launch { ide.format(it) } }
                                })
                                DropdownMenuItem(text = { Text("К определению") }, onClick = {
                                    menu = false; f?.let { scope.launch { ide.gotoDef(it) } }
                                })
                                DropdownMenuItem(text = { Text("Закомментировать строки") }, onClick = {
                                    menu = false; f?.let { ide.comment(it) }
                                })
                                DropdownMenuItem(text = { Text("Поиск по проекту") }, onClick = {
                                    menu = false; dialog = "search"
                                })
                                DropdownMenuItem(text = { Text("Пакеты (pip)") }, onClick = {
                                    menu = false; dialog = "pip"
                                })
                                DropdownMenuItem(text = { Text("Git") }, onClick = {
                                    menu = false; dialog = "git"
                                })
                            }
                        }
                    }
                )
            }
        ) { pad ->
            val imeOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
            Column(
                Modifier
                    .padding(pad)
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                if (ide.tabs.isNotEmpty()) {
                    ScrollableTabRow(selectedTabIndex = ide.active, edgePadding = 0.dp) {
                        ide.tabs.forEachIndexed { i, file ->
                            Tab(selected = i == ide.active, onClick = { ide.active = i }) {
                                Row(
                                    Modifier.padding(start = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(file.name, maxLines = 1)
                                    IconButton(onClick = { ide.close(i) }, Modifier.size(32.dp)) {
                                        Icon(Icons.Default.Close, "Закрыть", Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                if (f != null) {
                    if (ide.findOpen) FindBar(ide, f)
                    androidx.compose.runtime.key(f.path) {
                        EditorPane(ide, f, Modifier.weight(1f))
                    }
                    SuggestionBar(ide, f)
                    QuickBar(ide, f)

                    // статус-строка: позиция, ошибка на текущей строке
                    val v = ide.texts[f.path]
                    val pos = v?.selection?.start ?: 0
                    val line = if (v != null) lineOf(v.text, pos) else 1
                    val col = if (v != null) pos - (v.text.lastIndexOf('\n', pos - 1) + 1) else 0
                    val err = ide.problems[f.path].orEmpty().firstOrNull { it.line == line }
                    Row(
                        Modifier.fillMaxWidth().background(Color(0xFF252526)).padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("$line:${col + 1}", fontSize = 12.sp, color = Color.Gray)
                        Text(
                            "  " + (err?.msg ?: ide.status),
                            fontSize = 12.sp, maxLines = 1,
                            color = if (err != null) Color(0xFFFF8A80) else Color(0xFF9DA5B4)
                        )
                    }
                } else {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("Открой файл через меню слева")
                    }
                }

                PanelHeader(ide)
                if (ide.panelOpen && (!imeOpen || ide.panelFocused)) {
                    Box(Modifier.height(190.dp).fillMaxWidth()) {
                        when (ide.panelTab) {
                            0 -> ConsolePanel(ide)
                            1 -> TerminalPanel(ide)
                            2 -> DebugPanel(ide)
                            else -> ProblemsPanel(ide)
                        }
                    }
                }
            }
        }
    }

    when (dialog) {
        "pip" -> PipDialog { dialog = "" }
        "git" -> GitDialog(ide) { dialog = "" }
        "search" -> SearchDialog(ide) { dialog = "" }
        "new", "rename" -> AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text(if (dialog == "new") "Новый файл" else "Переименовать") },
            text = {
                MiniField(newName, { newName = it }, "dir/script.py", Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = {
                    if (dialog == "new") ide.create(newName)
                    else renameTarget?.let { ide.rename(it, newName) }
                    dialog = ""
                    scope.launch { drawer.close() }
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Отмена") } }
        )
    }
}
