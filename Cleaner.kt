package com.example.phonecleaner

import android.Manifest
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.storage.StorageManager
import java.io.File

object Cleaner {
    enum class Category(val title: String, val defaultOn: Boolean) {
        TEMP("Временные файлы (.tmp, .temp, .chk, .dmp)", true),
        LOG("Логи (.log)", true),
        THUMBS("Кэш миниатюр (.thumbnails, .cache)", true),
        EMPTY("Пустые папки", true),
        APK("Установочные файлы APK в «Загрузках»", false),
        BACKUP("Резервные копии (.bak, .old)", false)
    }

    data class Item(val file: File, val size: Long, val cat: Category? = null)
    data class AppCache(val label: String, val pkg: String, val cache: Long)

    // Стандартные папки, которые никогда не считаются «пустыми мусорными»
    private val keepDirs = setOf(
        "DCIM", "Pictures", "Download", "Documents", "Music", "Movies",
        "Podcasts", "Ringtones", "Notifications", "Alarms", "Audiobooks"
    )

    fun fmt(b: Long): String {
        val u = arrayOf("Б", "КБ", "МБ", "ГБ")
        var v = b.toDouble(); var i = 0
        while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
        return "%.1f %s".format(v, u[i])
    }

    fun hasFileAccess(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        else @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun walk(root: File) =
        root.walkTopDown().onEnter { !it.absolutePath.startsWith("$root/Android") }

    /** Ищет только по списку категорий. Фото, видео, документы и данные приложений не трогает. */
    fun scanJunk(): List<Item> {
        val root = Environment.getExternalStorageDirectory()
        val out = mutableListOf<Item>()
        for (f in walk(root)) {
            if (f == root) continue
            val path = f.absolutePath
            if (f.isDirectory) {
                val protectedDir = f.name.startsWith(".") || (f.parentFile == root && f.name in keepDirs)
                if (!protectedDir && f.list()?.isEmpty() == true) out += Item(f, 0, Category.EMPTY)
            } else {
                val ext = f.extension.lowercase()
                val cat = when {
                    path.contains("/.thumbnails/") || path.contains("/.cache/") -> Category.THUMBS
                    ext in setOf("tmp", "temp", "chk", "dmp") -> Category.TEMP
                    ext == "log" -> Category.LOG
                    ext in setOf("bak", "old") -> Category.BACKUP
                    ext == "apk" && path.contains("/Download") -> Category.APK
                    else -> null
                }
                if (cat != null) out += Item(f, f.length(), cat)
            }
        }
        return out
    }

    fun scanLarge(minBytes: Long = 100L * 1024 * 1024): List<Item> =
        walk(Environment.getExternalStorageDirectory())
            .filter { it.isFile && it.length() >= minBytes }
            .map { Item(it, it.length()) }
            .sortedByDescending { it.size }
            .take(20)
            .toList()

    fun delete(items: List<Item>): Long {
        var freed = 0L
        for (i in items) if (i.file.delete()) freed += i.size
        return freed
    }

    fun clearOwnCache(ctx: Context): Long {
        var freed = 0L
        listOfNotNull(ctx.cacheDir, ctx.externalCacheDir).forEach { d ->
            freed += d.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
            d.listFiles()?.forEach { it.deleteRecursively() }
        }
        return freed
    }

    fun appCaches(ctx: Context): List<AppCache> {
        val pm = ctx.packageManager
        val ssm = ctx.getSystemService(StorageStatsManager::class.java)
        val user = Process.myUserHandle()
        return pm.getInstalledApplications(0).mapNotNull { ai ->
            try {
                val s = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, ai.packageName, user)
                AppCache(ai.loadLabel(pm).toString(), ai.packageName, s.cacheBytes)
            } catch (e: Exception) { null }
        }.filter { it.cache > 0 }.sortedByDescending { it.cache }.take(30)
    }

    fun ram(ctx: Context): Pair<Long, Long> {
        val mi = ActivityManager.MemoryInfo()
        ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        return mi.availMem to mi.totalMem
    }

    /** Просит систему завершить фоновые процессы пользовательских приложений. */
    fun boost(ctx: Context): Int {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val apps = ctx.packageManager.getInstalledApplications(0).filter {
            it.packageName != ctx.packageName && (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0
        }
        apps.forEach { am.killBackgroundProcesses(it.packageName) }
        return apps.size
    }
}
