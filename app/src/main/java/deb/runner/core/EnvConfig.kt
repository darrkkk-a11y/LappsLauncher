package deb.runner.core

import android.content.Context
import java.io.File

/**
 * Конфигурация среды. Всё хранится в internal storage (без root):
 *   filesDir/env/bin        — бинари окружения (sh, dpkg, apt, ...)
 *   filesDir/dpkg/rootfs    — корень файловой системы пакетов (/usr, /bin ...)
 *   filesDir/apt/lists      — индексы репозиториев
 *   filesDir/apt/archives   — скачанные .deb
 *
 * Внутри среды пути выглядят как настоящая Linux-ФС: PATH=/usr/local/bin:/usr/bin:/bin,
 * префикс rootfs добавляется прозрачно через TermExec.
 */
class EnvConfig(context: Context) {

    val envDir = File(context.filesDir, "env").apply { mkdirs() }
    val binDir = File(envDir, "bin").apply { mkdirs() }
    val aptDir = File(context.filesDir, "apt").apply { mkdirs() }
    val listsDir = File(aptDir, "lists").apply { mkdirs() }
    val archivesDir = File(aptDir, "archives").apply { mkdirs() }
    val tmpDir = File(context.cacheDir, "tmp").apply { mkdirs() }

    val dpkg = DpkgDb(context)
    val rootfs: File get() = dpkg.rootfs

    var initialized: Boolean
        get() = prefs(context).getBoolean("env_init", false)
        set(v) { prefs(context).edit().putBoolean("env_init", v).apply() }

    /** Зеркало Debian. По умолчанию — официальный; можно менять в Настройках. */
    var mirror: String
        get() = prefs(context).getString("mirror", DEFAULT_MIRROR)!!
        set(v) { prefs(context).edit().putString("mirror", v.trimEnd('/')).apply() }

    var dist: String
        get() = prefs(context).getString("dist", DEFAULT_DIST)!!
        set(v) { prefs(context).edit().putString("dist", v).apply() }

    fun environment(): Map<String, String> = mapOf(
        "PATH" to "/usr/local/bin:/usr/local/sbin:/usr/bin:/usr/sbin:/bin:/sbin:" +
            binDir.absolutePath,
        "HOME" to "/root",
        "TERM" to "xterm-256color",
        "LANG" to "C.UTF-8",
        "DEBIAN_FRONTEND" to "noninteractive",
        "TMPDIR" to "/tmp",
    )

    fun sourceLines(): String =
        "deb $mirror $dist main\n" +
        "deb $mirror $dist-updates main\n"

    companion object {
        const val DEFAULT_MIRROR = "http://deb.debian.org/debian"
        const val DEFAULT_DIST = "trixie"

        private fun prefs(context: Context) =
            context.getSharedPreferences("debrunner", Context.MODE_PRIVATE)

        /** Архитектура Android → имя архитектуры Debian. */
        fun debianArch(): String {
            val abis = android.os.Build.SUPPORTED_ABIS
            return when {
                abis.any { it == "arm64-v8a" || it == "x86_64" } ->
                    if (abis.contains("x86_64")) "amd64" else "arm64"
                abis.any { it.startsWith("armeabi") } -> "armhf"
                abis.any { it.startsWith("x86") } -> "i386"
                else -> "arm64"
            }
        }
    }
}
