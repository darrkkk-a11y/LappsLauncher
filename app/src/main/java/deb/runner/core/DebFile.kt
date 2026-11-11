package deb.runner.core

import android.content.Context
import org.apache.commons.compress.archivers.ar.ArArchiveEntry
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.lz4.BlockLZ4CompressorInputStream
import org.apache.commons.compress.compressors.lzma.LZMACompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Разбор формата .deb (Debian binary package):
 *   debian-binary | control.tar.(gz|xz|zst) | data.tar.(gz|xz|bz2|lzma|zst|lz4)
 */
class DebFile(private val debPath: File) {

    data class Control(
        val package_: String,
        val version: String,
        val architecture: String,
        val depends: String,
        val preDepends: String,
        val description: String,
        val maintainerScripts: Map<String, File>, // preinst/postinst/prerm/postrm -> извлечённые файлы
        val rawFields: Map<String, String>,
    ) {
        val name get() = package_
    }

    /** Зависимости в виде списка имён пакетов (без версионных ограничений). */
    fun Control.dependencyNames(): List<String> =
        (depends.split(",") + preDepends.split(","))
            .map { it.substringBefore("(").trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    private fun <T> withStreams(block: (control: InputStream, data: InputStream) -> T): T {
        var controlBytes: ByteArray? = null
        var dataBytes: ByteArray? = null
        FileInputStream(debPath).use { fis ->
            ArArchiveInputStream(BufferedInputStream(fis)).use { ar ->
                var entry: ArArchiveEntry? = ar.nextEntry
                while (entry != null) {
                    when {
                        entry.name == "control.tar" || entry.name.startsWith("control.tar") ->
                            controlBytes = ar.readBytes()
                        entry.name == "data.tar" || entry.name.startsWith("data.tar") ->
                            dataBytes = ar.readBytes()
                    }
                    entry = ar.nextEntry
                }
            }
        }
        requireNotNull(controlBytes) { "В архиве не найден control.tar — файл повреждён или это не .deb" }
        requireNotNull(dataBytes) { "В архиве не найден data.tar — файл повреждён или это не .deb" }
        return block(ByteArrayInputStream(controlBytes), ByteArrayInputStream(dataBytes))
    }

    private fun tarStream(raw: InputStream): InputStream {
        val bin = BufferedInputStream(raw)
        bin.mark(8)
        val magic = ByteArray(6)
        bin.read(magic)
        bin.reset()
        return when {
            magic.startsWith(byteArrayOf(0x1f, 0x8b.toByte())) -> GZIPInputStream(bin)
            magic.startsWith(byteArrayOf(0x28, 0xb5, 0x2f, 0xfd.toByte())) -> ZstdCompressorInputStream(bin)
            magic.startsWith(byteArrayOf(0x5d, 0x00)) -> LZMACompressorInputStream(bin)
            magic.startsWith(byteArrayOf(0x04, 0x22, 0x4d, 0x18)) -> XZCompressorInputStream(bin)
            magic[0] == 'B'.code.toByte() && magic.slice(1..3)[0] == 'Z'.code.toByte() ->
                org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(bin)
            else -> bin // несожатый tar
        }
    }

    fun readControl(tmpDir: File): Control = withStreams { control, _ ->
        val fields = LinkedHashMap<String, String>()
        val scripts = LinkedHashMap<String, File>()
        TarArchiveInputStream(tarStream(control)).use { tar ->
            var e: TarArchiveEntry? = tar.nextTarEntry
            while (e != null) {
                val name = e.name.removePrefix("./")
                if (e.isFile) {
                    val bytes = tar.readBytes()
                    if (name == "control") {
                        fields.putAll(parseControl(bytes.toString(Charsets.UTF_8)))
                    } else if (name in setOf("preinst", "postinst", "prerm", "postrm")) {
                        val f = File(tmpDir, name)
                        f.writeBytes(bytes)
                        f.setExecutable(true, true)
                        scripts[name] = f
                    }
                }
                e = tar.nextTarEntry
            }
        }
        Control(
            package_ = fields["Package"] ?: throw IllegalArgumentException("Нет поля Package в control"),
            version = fields["Version"] ?: "0",
            architecture = fields["Architecture"] ?: "all",
            depends = fields["Depends"] ?: "",
            preDepends = fields["Pre-Depends"] ?: "",
            description = fields["Description"] ?: "",
            maintainerScripts = scripts,
            rawFields = fields,
        )
    }

    private fun parseControl(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var key: String? = null
        val sb = StringBuilder()
        for (line in text.lineSequence()) {
            when {
                line.isBlank() -> { /* конец блока */ }
                line.startsWith(" ") || line.startsWith("\t") -> sb.append(line.trim()).append('\n')
                ":" in line -> {
                    key?.let { out[it] = sb.toString().trim() }
                    key = line.substringBefore(":").trim()
                    sb.setLength(0)
                    sb.append(line.substringAfter(":").trim())
                }
            }
        }
        key?.let { out[it] = sb.toString().trim() }
        return out
    }

    /**
     * Извлекает data.tar в [root] (prefix /usr/local/opt/debrunner/rootfs).
     * Возвращает список абсолютных путей установленных файлов.
     */
    fun extractData(root: File, onProgress: (Long) -> Unit = {}): List<String> {
        val files = mutableListOf<String>()
        withStreams { _, data ->
            TarArchiveInputStream(tarStream(data)).use { tar ->
                var e: TarArchiveEntry? = tar.nextTarEntry
                while (e != null) {
                    val rel = e.name.removePrefix("./").removePrefix("/")
                    val target = File(root, rel)
                    // защита от path traversal
                    if (!target.canonicalPath.startsWith(root.canonicalPath + File.separator) &&
                        target.canonicalPath != root.canonicalPath
                    ) {
                        e = tar.nextTarEntry; continue
                    }
                    when {
                        e.isDirectory -> target.mkdirs()
                        e.isSymbolicLink || e.isLink -> {
                            target.parentFile?.mkdirs()
                            if (e.isSymbolicLink) {
                                runCatching { target.delete() }
                                java.nio.file.Files.createSymbolicLink(
                                    target.toPath(),
                                    java.nio.file.Paths.get(root.absolutePath, e.linkName)
                                )
                            }
                        }
                        e.isFile -> {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { os -> tar.copyTo(os) }
                            target.setExecutable((e.mode and 0b111_000_000) != 0, false)
                            files.add("/" + rel)
                            onProgress(e.size)
                        }
                    }
                    e = tar.nextTarEntry
                }
            }
        }
        return files
    }
}
