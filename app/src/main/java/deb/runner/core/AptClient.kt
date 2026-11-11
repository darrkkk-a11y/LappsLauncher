package deb.runner.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

data class AptPackage(
    val name: String,
    val version: String,
    val architecture: String,
    val size: Long,
    val filename: String,   // путь в репозитории
    val description: String,
    val depends: List<String>,
)

/**
 * Минимальный apt: читает Packages.gz/xz из репозитория Debian,
 * ищет пакеты, скачивает .deb. Работает без root и без внешних бинарей.
 */
class AptClient(private val context: Context, private val env: EnvConfig) {

    private val arch = EnvConfig.debianArch()

    /** Обновляет индексы (аналог apt update). Возвращает число доступных пакетов. */
    suspend fun update(onProgress: (String) -> Unit): Int = withContext(Dispatchers.IO) {
        var total = 0
        for (component in listOf("main")) {
            for (suite in listOf(env.dist, "${env.dist}-updates")) {
                val url = "${env.mirror}/dists/$suite/binary-$arch/Packages.xz"
                val out = File(env.listsDir, "${suite}_$component.pkgs")
                onProgress("Загрузка индекса $suite…")
                val ok = try {
                    download(url, out, useXz = true)
                    true
                } catch (e: Exception) {
                    // fallback на .gz
                    try {
                        download("${env.mirror}/dists/$suite/binary-$arch/Packages.gz", out, useGz = true)
                        true
                    } catch (e2: Exception) { false }
                }
                if (ok) total += countPackages(out)
            }
        }
        require(total > 0) { "Не удалось загрузить индексы. Проверьте зеркало/сеть." }
        total
    }

    private fun countPackages(f: File): Int =
        f.readLines().count { it.startsWith("Package: ") }

    /** Ищет по индексам. query — точное имя или подстрока в имени/описании. */
    suspend fun search(query: String, limit: Int = 200): List<AptPackage> =
        withContext(Dispatchers.IO) {
            val q = query.lowercase().trim()
            val exactFirst = ArrayList<AptPackage>()
            val partial = ArrayList<AptPackage>()
            for (f in env.listsDir.listFiles { x -> x.name.endsWith(".pkgs") } ?: return@withContext emptyList()) {
                forEachBlock(f) { fields ->
                    val name = fields["Package"] ?: return@forEachBlock
                    if (name.lowercase() == q) {
                        exactFirst.add(parsePkg(fields))
                    } else if (q.isNotEmpty() &&
                        (name.lowercase().contains(q) ||
                            (fields["Description"]?.lowercase()?.contains(q) == true))
                    ) {
                        if (partial.size < limit) partial.add(parsePkg(fields))
                    }
                }
            }
            (exactFirst + partial).distinctBy { it.name }
        }

    suspend fun resolve(name: String): AptPackage? = withContext(Dispatchers.IO) {
        var found: AptPackage? = null
        for (f in env.listsDir.listFiles { x -> x.name.endsWith(".pkgs") } ?: return@withContext null) {
            forEachBlock(f) { fields ->
                if (found == null && fields["Package"] == name) found = parsePkg(fields)
            }
            if (found != null) break
        }
        found
    }

    private fun parsePkg(fields: Map<String, String>) = AptPackage(
        name = fields["Package"]!!,
        version = fields["Version"] ?: "0",
        architecture = fields["Architecture"] ?: arch,
        size = fields["Size"]?.toLongOrNull() ?: 0L,
        filename = fields["Filename"] ?: "",
        description = (fields["Description"] ?: "").lineSequence().firstOrNull()?.trim() ?: "",
        depends = (fields["Depends"] ?: "").split(",")
            .map { it.substringBefore("(").trim() }.filter { it.isNotEmpty() },
    )

    private inline fun forEachBlock(f: File, block: (Map<String, String>) -> Unit) {
        var fields = LinkedHashMap<String, String>()
        var key: String? = null
        val sb = StringBuilder()
        fun flush() {
            key?.let { fields[it] = sb.toString().trim() }
            if (fields.containsKey("Package")) block(fields)
            fields = LinkedHashMap(); key = null; sb.setLength(0)
        }
        f.bufferedReader().use { r ->
            while (true) {
                val line = r.readLine() ?: break
                when {
                    line.isBlank() -> flush()
                    line.startsWith(" ") || line.startsWith("\t") -> sb.append(' ').append(line.trim())
                    ":" in line -> {
                        key?.let { fields[it] = sb.toString().trim() }
                        key = line.substringBefore(":").trim()
                        sb.setLength(0)
                        sb.append(line.substringAfter(":").trim())
                    }
                }
            }
        }
        flush()
    }

    /** Скачивает .deb пакета в archives/. Возвращает файл. */
    suspend fun downloadDeb(pkg: AptPackage, onProgress: (Float) -> Unit = {}): File =
        withContext(Dispatchers.IO) {
            val url = "${env.mirror}/${pkg.filename}"
            val out = File(env.archivesDir, pkg.filename.substringAfterLast('/'))
            if (out.exists() && out.length() == pkg.size) return@withContext out
            download(url, out, onHttpProgress = { read, len ->
                onProgress(if (len > 0) read.toFloat() / len else -1f)
            })
            out
        }

    private fun download(
        url: String,
        out: File,
        useXz: Boolean = false,
        useGz: Boolean = false,
        onHttpProgress: ((Long, Long) -> Unit)? = null,
    ) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000; conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.connect()
        check(conn.responseCode in 200..299) { "HTTP ${conn.responseCode}: $url" }
        val len = conn.contentLength.toLong()
        var ins: java.io.InputStream = conn.inputStream
        if (useXz) ins = XZCompressorInputStream(ins)
        if (useGz) ins = GZIPInputStream(ins)
        FileOutputStream(out).use { os ->
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                os.write(buf, 0, n)
                total += n
                onHttpProgress?.invoke(total, len)
            }
        }
        ins.close(); conn.disconnect()
    }
}
