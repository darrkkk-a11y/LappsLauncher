package deb.runner.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

sealed class InstallEvent {
    data class Log(val line: String) : InstallEvent()
    data class Progress(val current: Int, val total: Int, val pkg: String) : InstallEvent()
    data class Done(val message: String) : InstallEvent()
    data class Failed(val message: String) : InstallEvent()
}

/**
 * Аналог dpkg -i с автоматическим разрешением зависимостей через AptClient.
 * Порядок: collect deps (BFS) → topo sort → download → extract → register → postinst.
 */
class PackageManager(
    private val context: Context,
    private val env: EnvConfig,
    private val apt: AptClient,
) {
    private val mutex = Mutex()
    private val db get() = env.dpkg

    data class Plan(
        val rootName: String,
        val toInstall: List<AptPackage>,      // уже в репозитории, отсутствуют
        val missing: List<String>,            // не найдены в индексах
        val localDepNames: List<String>,      // имена зависимостей локального .deb
    )

    /** План установки для локального .deb: какие зависимости нужно доустановить из apt. */
    suspend fun planForLocalDeb(debFile: File): Pair<DebFile.Control, Plan> = withContext(Dispatchers.IO) {
        val deb = DebFile(debFile)
        val ctrl = deb.readControl(File(env.tmpDir, "control-${System.nanoTime()}").apply { mkdirs() })
        planForDeps(ctrl.dependencyNames()) to ctrl
    }

    suspend fun planForDeps(depNames: List<String>): Plan {
        val toInstall = LinkedHashMap<String, AptPackage>()
        val missing = mutableListOf<String>()
        val queue = ArrayDeque(depNames)
        val seen = HashSet(depNames)
        while (queue.isNotEmpty()) {
            val name = queue.removeFirst()
            if (db.isInstalled(name)) continue
            val pkg = apt.resolve(name)
            if (pkg == null) { missing.add(name); continue }
            toInstall[name] = pkg
            for (d in pkg.depends) if (seen.add(d)) queue.addLast(d)
        }
        return Plan(
            rootName = depNames.firstOrNull() ?: "",
            toInstall = topoSort(toInstall.values.toList()),
            missing = missing,
            localDepNames = depNames,
        )
    }

    /** Простая топологическая сортировка по Depends (циклы разрываются эвристикой). */
    private fun topoSort(pkgs: List<AptPackage>): List<AptPackage> {
        val byName = pkgs.associateBy { it.name }
        val result = ArrayList<AptPackage>(pkgs.size)
        val state = mutableMapOf<String, Int>() // 0 none,1 visiting,2 done
        fun visit(p: AptPackage, depth: Int) {
            when (state[p.name]) {
                2 -> return
                1 -> { state[p.name] = 2; return } // цикл — пропускаем
            }
            state[p.name] = 1
            if (depth < 64) p.depends.forEach { d -> byName[d]?.let { visit(it, depth + 1) } }
            state[p.name] = 2
            result.add(p)
        }
        pkgs.forEach { visit(it, 0) }
        return result
    }

    suspend fun installFromRepo(
        names: List<String>,
        emit: (InstallEvent) -> Unit,
    ): Boolean = mutex.withLock {
        try {
            val plan = planForDeps(names)
            if (plan.missing.isNotEmpty())
                emit(InstallEvent.Log("⚠ Не найдены в репозитории: ${plan.missing.joinToString()}"))
            if (plan.toInstall.isEmpty()) {
                emit(InstallEvent.Done("Все пакеты уже установлены")); return@withLock true
            }
            runCatching { apt.update { emit(InstallEvent.Log(it)) } }
            installList(plan.toInstall, emit)
        } catch (e: Exception) {
            emit(InstallEvent.Failed(e.message ?: "Ошибка apt")); false
        }
    }

    /** Установка локального .deb + его недостающих зависимостей из репозитория. */
    suspend fun installLocalDeb(
        debPath: File,
        alsoInstallPlan: Boolean = true,
        emit: (InstallEvent) -> Unit,
    ): Boolean = mutex.withLock {
        try {
            val (plan, ctrl) = planForLocalDeb(debPath)
            emit(InstallEvent.Log("Пакет: ${ctrl.name} ${ctrl.version} (${ctrl.architecture})"))
            if (plan.missing.isNotEmpty())
                emit(InstallEvent.Log("⚠ Зависимости не найдены в индексах: ${plan.missing.joinToString()}. Обновите списки (apt update)."))
            if (alsoInstallPlan && plan.toInstall.isNotEmpty()) {
                emit(InstallEvent.Log("Требуется доустановить ${plan.toInstall.size} пакетов: " +
                    plan.toInstall.joinToString { "${it.name}-${it.version}" }))
                installList(plan.toInstall, emit)
            }
            installOne(debPath, ctrl, emit)
            emit(InstallEvent.Done("✅ ${ctrl.name} установлен"))
            true
        } catch (e: Exception) {
            emit(InstallEvent.Failed("Установка не удалась: ${e.message}")); false
        }
    }

    private suspend fun installList(pkgs: List<AptPackage>, emit: (InstallEvent) -> Unit) {
        pkgs.forEachIndexed { i, p ->
            emit(InstallEvent.Progress(i + 1, pkgs.size, p.name))
            emit(InstallEvent.Log("↓ Скачивание ${p.name} ${p.version}…"))
            val f = apt.downloadDeb(p)
            val deb = DebFile(f)
            val ctrl = deb.readControl(File(env.tmpDir, "c-${p.name}-${System.nanoTime()}").apply { mkdirs() })
            installOne(f, ctrl, emit)
        }
    }

    private suspend fun installOne(debFile: File, ctrl: DebFile.Control, emit: (InstallEvent) -> Unit) =
        withContext(Dispatchers.IO) {
            val existing = db.find(ctrl.name)
            if (existing != null && existing.version == ctrl.version) {
                emit(InstallEvent.Log("${ctrl.name} уже установлен (${ctrl.version}) — переустановка"))
            }
            // prerm старой версии
            existing?.let { runScriptQuiet("$name-prerm", it, "upgrade") }

            emit(InstallEvent.Log("Извлечение ${ctrl.name}…"))
            var bytes = 0L
            val files = DebFile(debFile).extractData(db.rootfs) { bytes += it }
            db.add(InstalledPackage(
                name = ctrl.name, version = ctrl.version, architecture = ctrl.architecture,
                description = ctrl.description.lineSequence().firstOrNull()?.trim() ?: "",
                installedAt = System.currentTimeMillis(),
                files = files, sizeBytes = bytes, source = "deb-file",
            ))
            //保存 scripts for later removal
            val scriptDir = File(env.envDir, "scripts/${ctrl.name}").apply { mkdirs() }
            ctrl.maintainerScripts.forEach { (n, f) -> f.copyTo(File(scriptDir, n), overwrite = true) }

            runCtrlScript(ctrl.maintainerScripts["preinst"], ctrl.name, "install", ctrl.version, emit)
            runCtrlScript(ctrl.maintainerScripts["postinst"], ctrl.name, "configure", ctrl.version, emit)
            emit(InstallEvent.Log("✔ ${ctrl.name} ${ctrl.version} готов"))
        }

    suspend fun remove(name: String, emit: (InstallEvent) -> Unit): Boolean = mutex.withLock {
        try {
            val pkg = db.find(name) ?: error("Пакет $name не установлен")
            // проверка обратных зависимостей
            val dependents = db.all().filter { db ->
                env.dpkg.find(db.name)?.let { false } == false && dependsOn(db, name)
            }
            if (dependents.isNotEmpty())
                emit(InstallEvent.Log("⚠ От $name зависят: ${dependents.joinToString { it.name }}"))
            val scriptDir = File(env.envDir, "scripts/$name")
            runCtrlScript(File(scriptDir, "prerm"), name, "remove", pkg.version, emit)
            db.remove(name)
            runCtrlScript(File(scriptDir, "postrm"), name, "remove", pkg.version, emit)
            scriptDir.deleteRecursively()
            emit(InstallEvent.Done("🗑 $name удалён"))
            true
        } catch (e: Exception) {
            emit(InstallEvent.Failed(e.message ?: "Ошибка удаления")); false
        }
    }

    private fun dependsOn(pkg: InstalledPackage, dep: String): Boolean {
        // читаем сохранённый control если есть, иначе по описанию нельзя — используем saved deps
        val saved = File(env.envDir, "deps/${pkg.name}").takeIf { it.exists() }
            ?.readLines() ?: emptyList()
        return dep in saved
    }

    /** Обновление всех установленных пакетов, для которых в индексах есть версия новее. */
    suspend fun upgradeAll(emit: (InstallEvent) -> Unit): Int = mutex.withLock {
        var count = 0
        val installed = db.all()
        for (pkg in installed) {
            val latest = apt.resolve(pkg.name) ?: continue
            if (compareVersions(latest.version, pkg.version) > 0) {
                emit(InstallEvent.Progress(++count, installed.size, pkg.name))
                emit(InstallEvent.Log("↑ ${pkg.name}: ${pkg.version} → ${latest.version}"))
                val f = apt.downloadDeb(latest)
                val ctrl = DebFile(f).readControl(File(env.tmpDir, "u-${pkg.name}").apply { mkdirs() })
                installOne(f, ctrl, emit)
            }
        }
        if (count == 0) emit(InstallEvent.Done("Все пакеты обновлены")) 
        else emit(InstallEvent.Done("Обновлено пакетов: $count"))
        count
    }

    /** Сравнение версий Debian (upstream-revision, цифры/буквы). */
    fun compareVersions(a: String, b: String): Int {
        fun split(v: String) = v.substringBeforeLast('-', v) to v.substringAfterLast('-', "")
        val (au, ar) = split(a); val (bu, br) = split(b)
        cmpPart(au, bu).let { if (it != 0) return it }
        return cmpPart(ar, br)
    }

    private fun cmpPart(a: String, b: String): Int {
        var i = 0; var j = 0
        while (i < a.length || j < b.length) {
            var na = 0L; var nb = 0L
            while (i < a.length && a[i].isDigit()) { na = na * 10 + (a[i] - '0'); i++ }
            while (j < b.length && b[j].isDigit()) { nb = nb * 10 + (b[j] - '0'); j++ }
            if (na != nb) return if (na > nb) 1 else -1
            val ca = if (i < a.length) a[i] else '\u0000'
            val cb = if (j < b.length) b[j] else '\u0000'
            if (ca != cb) return if (ca > cb) 1 else -1
            i++; j++
        }
        return 0
    }

    private fun runCtrlScript(script: File?, name: String, cmd: String, version: String, emit: (InstallEvent) -> Unit) {
        if (script == null || !script.exists()) return
        val res = kotlinx.coroutines.runBlocking {
            TermExec.run(rootfs = db.rootfs, env = env.environment(), command = "/system/bin/sh",
                args = listOf("-c", "bash_placeholder"), timeoutSec = 5).let { // не используем bash
                TermExec.run(db.rootfs, env.environment(), "/system/bin/sh",
                    listOf(script.absolutePath, cmd, version), timeoutSec = 60)
            }
        }
        if (res.exitCode != 0) emit(InstallEvent.Log("⚠ $name ($cmd): код ${res.exitCode}"))
        else if (res.output.isNotBlank()) emit(InstallEvent.Log(res.output.trim()))
    }

    private suspend fun runScriptQuiet(prefix: String, pkg: InstalledPackage, cmd: String) {
        val s = File(env.envDir, "scripts/${pkg.name}/$prefix".replace("-$prefix", "-$prefix"))
        // prerm/postrm лежат как prerm/postrm
        val script = File(env.envDir, "scripts/${pkg.name}/${prefix.substringAfter("-")}")
        if (script.exists()) TermExec.run(db.rootfs, env.environment(), "/system/bin/sh",
            listOf(script.absolutePath, cmd, pkg.version), timeoutSec = 30)
    }

    /** Кандидаты на «приложение»: ELF-бинарники в PATH-директориях rootfs. */
    suspend fun executables(): List<String> = withContext(Dispatchers.IO) {
        val dirs = listOf("usr/local/bin", "usr/bin", "bin", "usr/local/sbin", "usr/sbin", "sbin")
        dirs.flatMap { d ->
            File(db.rootfs, d).listFiles()?.filter { it.isFile && it.canExecute() && looksLikeElf(it) }
                ?.map { it.name } ?: emptyList()
        }.sorted()
    }

    private fun looksLikeElf(f: File): Boolean = runCatching {
        f.inputStream().use { ins ->
            val m = ByteArray(4); ins.read(m)
            m[0] == 0x7f.toByte() && m[1] == 'E'.code.toByte() && m[2] == 'L'.code.toByte() && m[3] == 'F'.code.toByte()
        }
    }.getOrDefault(false)
}
