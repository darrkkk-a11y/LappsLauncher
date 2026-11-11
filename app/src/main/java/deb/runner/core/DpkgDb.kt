package deb.runner.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class InstalledPackage(
    val name: String,
    val version: String,
    val architecture: String,
    val description: String,
    val installedAt: Long,
    val files: List<String>,
    val sizeBytes: Long,
    val source: String, // "deb-file" | "apt"
)

/**
 * Аналог dpkg status + file list. Хранится в filesDir/dpkg:
 *   status.json          — база установленных пакетов
 *   rootfs/              — файловое дерево (/usr, /bin, /lib ...)
 *   info/<pkg>.list      — список файлов пакета
 */
class DpkgDb(context: Context) {

    private val dir = File(context.filesDir, "dpkg").apply { mkdirs() }
    val rootfs = File(dir, "rootfs").apply { mkdirs() }
    private val infoDir = File(dir, "info").apply { mkdirs() }
    private val statusFile = File(dir, "status.json")

    @Volatile private var cache: MutableMap<String, InstalledPackage>? = null

    fun all(): List<InstalledPackage> = load().values.sortedBy { it.name.lowercase() }

    fun find(name: String): InstalledPackage? = load()[name]

    fun isInstalled(name: String): Boolean = load().containsKey(name)

    @Synchronized
    fun add(pkg: InstalledPackage) {
        val m = load().toMutableMap()
        m[pkg.name] = pkg
        save(m)
        File(infoDir, "${pkg.name}.list").writeText(pkg.files.joinToString("\n"))
    }

    @Synchronized
    fun remove(name: String): InstalledPackage? {
        val m = load().toMutableMap()
        val removed = m.remove(name) ?: return null
        save(m)
        // удаляем файлы пакета из rootfs
        File(infoDir, "$name.list").readLines().forEach { p ->
            runCatching { File(rootfs, p.removePrefix("/")).delete() }
        }
        File(infoDir, "$name.list").delete()
        return removed
    }

    private fun load(): Map<String, InstalledPackage> {
        cache?.let { return it }
        val m = LinkedHashMap<String, InstalledPackage>()
        if (statusFile.exists()) runCatching {
            val arr = JSONArray(statusFile.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                m[o.getString("name")] = InstalledPackage(
                    name = o.getString("name"),
                    version = o.getString("version"),
                    architecture = o.optString("architecture", "all"),
                    description = o.optString("description", ""),
                    installedAt = o.optLong("installedAt"),
                    files = File(infoDir, "${o.getString("name")}.list")
                        .let { if (it.exists()) it.readLines().filter(String::isNotBlank) else emptyList() },
                    sizeBytes = o.optLong("size"),
                    source = o.optString("source", "deb-file"),
                )
            }
        }
        cache = m
        return m
    }

    private fun save(m: Map<String, InstalledPackage>) {
        val arr = JSONArray()
        m.values.forEach { p ->
            arr.put(JSONObject().apply {
                put("name", p.name); put("version", p.version)
                put("architecture", p.architecture); put("description", p.description)
                put("installedAt", p.installedAt); put("size", p.sizeBytes)
                put("source", p.source)
            })
        }
        statusFile.writeText(arr.toString(2))
        cache = m.toMutableMap()
    }

    companion object {
        val DATE_FMT = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
    }
}
