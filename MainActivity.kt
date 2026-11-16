package com.example.phonecleaner

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.phonecleaner.Cleaner.fmt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { CleanerScreen() }
            }
        }
    }
}

@Composable
fun CleanerScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var junk by remember { mutableStateOf<List<Cleaner.Item>?>(null) }
    var large by remember { mutableStateOf<List<Cleaner.Item>?>(null) }
    var apps by remember { mutableStateOf<List<Cleaner.AppCache>?>(null) }
    var confirm by remember { mutableStateOf<Cleaner.Item?>(null) }
    var cats by remember { mutableStateOf(Cleaner.Category.values().filter { it.defaultOn }.toSet()) }
    var confirmJunk by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val legacyPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    val stat = remember(tick) { StatFs(Environment.getDataDirectory().path) }
    val total = stat.totalBytes
    val used = total - stat.availableBytes
    val ram = remember(tick) { Cleaner.ram(ctx) }

    fun open(i: Intent) {
        try { ctx.startActivity(i) } catch (e: Exception) { status = "Не удалось открыть настройки" }
    }
    fun task(msg: String, block: suspend () -> Unit) {
        scope.launch {
            busy = true; status = msg
            try { block() } catch (e: Exception) { status = "Ошибка: ${e.message}" }
            busy = false; tick++
        }
    }
    fun requestFiles() {
        status = "Разрешите доступ к файлам и вернитесь в приложение"
        if (Build.VERSION.SDK_INT >= 30)
            open(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")))
        else legacyPerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Text("Очистка телефона", style = MaterialTheme.typography.headlineMedium) }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Память: занято ${fmt(used)} из ${fmt(total)}")
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { used.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    Text("ОЗУ: свободно ${fmt(ram.first)} из ${fmt(ram.second)}")
                }
            }
        }

        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (status.isNotEmpty()) item { Text(status, color = MaterialTheme.colorScheme.primary) }

        item {
            Button(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = {
                task("Освобождаю ОЗУ…") {
                    val n = withContext(Dispatchers.IO) { Cleaner.boost(ctx) }
                    status = "Запрос на остановку фоновых процессов отправлен ($n приложений)"
                }
            }) { Text("⚡ Ускорить (освободить ОЗУ)") }
        }

        item {
            Button(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = {
                if (!Cleaner.hasFileAccess(ctx)) requestFiles()
                else task("Сканирую мусор…") {
                    val r = withContext(Dispatchers.IO) { Cleaner.scanJunk() }
                    junk = r
                    status = "Найдено: ${r.size} шт., ${fmt(r.sumOf { it.size })}"
                }
            }) { Text("🔍 Найти ненужные файлы") }
        }
        junk?.let { list ->
            Cleaner.Category.values().forEach { c ->
                val part = list.filter { it.cat == c }
                if (part.isNotEmpty()) item(key = c.name) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = c in cats,
                            onCheckedChange = { on -> cats = if (on) cats + c else cats - c }
                        )
                        Column {
                            Text(c.title)
                            Text(
                                "${part.size} шт., ${fmt(part.sumOf { it.size })}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
            val sel = list.filter { x -> x.cat?.let { c -> c in cats } == true }
            if (sel.isNotEmpty()) item {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    onClick = { confirmJunk = true }
                ) { Text("🗑 Очистить выбранное: ${sel.size} шт., ${fmt(sel.sumOf { it.size })}") }
            }
        }

        item {
            Button(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = {
                task("Очищаю кэш приложения…") {
                    val f = withContext(Dispatchers.IO) { Cleaner.clearOwnCache(ctx) }
                    status = "Кэш этого приложения очищен: ${fmt(f)}"
                }
            }) { Text("🧹 Очистить собственный кэш") }
        }

        item {
            OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = {
                if (!Cleaner.hasFileAccess(ctx)) requestFiles()
                else task("Ищу большие файлы…") {
                    val r = withContext(Dispatchers.IO) { Cleaner.scanLarge() }
                    large = r
                    status = if (r.isEmpty()) "Файлов больше 100 МБ не найдено" else "Найдено больших файлов: ${r.size}"
                }
            }) { Text("📦 Большие файлы (от 100 МБ)") }
        }
        large?.let { list ->
            items(list) { it0 ->
                RowItem(it0.file.name, "${fmt(it0.size)} • ${it0.file.parent}", "Удалить") { confirm = it0 }
            }
        }

        item {
            OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = {
                if (!Cleaner.hasUsageAccess(ctx)) {
                    status = "Включите «Доступ к данным об использовании» для этого приложения"
                    open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                } else task("Считаю кэш приложений…") {
                    apps = withContext(Dispatchers.IO) { Cleaner.appCaches(ctx) }
                    status = "Нажмите «Открыть» → Хранилище → Очистить кэш"
                }
            }) { Text("📱 Кэш других приложений") }
        }
        apps?.let { list ->
            items(list) { a ->
                RowItem(a.label, fmt(a.cache), "Открыть") {
                    open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}")))
                }
            }
        }

        item { Text("Оптимизация", style = MaterialTheme.typography.titleMedium) }
        item {
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }) { Text("🔋 Настройки энергопотребления") }
        }
        item {
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                open(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
            }) { Text("💾 Системные настройки памяти") }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (confirmJunk) {
        val sel = junk.orEmpty().filter { x -> x.cat?.let { c -> c in cats } == true }
        AlertDialog(
            onDismissRequest = { confirmJunk = false },
            title = { Text("Удалить выбранное?") },
            text = {
                Text(
                    "Будет удалено ${sel.size} шт. (${fmt(sel.sumOf { it.size })}). " +
                        "Фото, видео, документы и данные приложений не затрагиваются. " +
                        "Удаление необратимо."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmJunk = false
                    task("Очищаю…") {
                        val f = withContext(Dispatchers.IO) { Cleaner.delete(sel) }
                        junk = junk?.filter { it !in sel }
                        status = "Освобождено: ${fmt(f)}"
                    }
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmJunk = false }) { Text("Отмена") } }
        )
    }

    confirm?.let { item ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Удалить файл?") },
            text = { Text("${item.file.name}\n${fmt(item.size)}") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    task("Удаляю…") {
                        val f = withContext(Dispatchers.IO) { Cleaner.delete(listOf(item)) }
                        large = large?.filter { it != item }
                        status = "Освобождено: ${fmt(f)}"
                    }
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Отмена") } }
        )
    }
}

@Composable
fun RowItem(title: String, sub: String, action: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, maxLines = 1, style = MaterialTheme.typography.bodyLarge)
                Text(sub, maxLines = 1, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onClick) { Text(action) }
        }
    }
}
