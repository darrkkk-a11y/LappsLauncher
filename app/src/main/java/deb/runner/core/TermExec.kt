package deb.runner.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

data class ExecResult(val exitCode: Int, val output: String)

/**
 * Запуск команд внутри среды: рабочий каталог — rootfs, поэтому пути вида
 * /usr/bin/hello резолвятся ядром относительно rootfs (unshare не нужен —
 * мы просто подменяем корень путём относительных путей и PATH).
 *
 * Для исполняемых файлов из пакетов реальный путь: rootfs + path.
 */
object TermExec {

    suspend fun run(
        rootfs: File,
        env: Map<String, String>,
        command: String,
        args: List<String> = emptyList(),
        stdin: String? = null,
        timeoutSec: Long = 120,
        onLine: ((String) -> Unit)? = null,
    ): ExecResult = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        try {
            val pb = ProcessBuilder(buildList {
                add(command); addAll(args)
            })
            pb.directory(rootfs)
            pb.redirectErrorStream(true)
            // Все переменные среды передаются как есть; пути внутри скриптов
            // интерпретируются our-wrapper'ами (см. assets setup).
            pb.environment().clear()
            env.forEach { (k, v) -> pb.environment()[k] = v }
            pb.environment()["ANDROID_ROOTFS"] = rootfs.absolutePath

            val p = pb.start()
            if (stdin != null) p.outputStream.use { it.write(stdin.toByteArray()) }

            BufferedReader(InputStreamReader(p.inputStream)).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    sb.append(line).append('\n')
                    onLine?.invoke(line)
                }
            }
            val ok = p.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!ok) { p.destroyForcibly(); ExecResult(124, sb.toString() + "\n[timeout]") }
            else ExecResult(p.exitValue(), sb.toString())
        } catch (e: Exception) {
            ExecResult(127, sb.toString() + "\n[exec error] ${e.message}")
        }
    }
}
