package deb.runner.core

import android.content.Context

/** Синглтоны ядра среды. */
object DebRunnerCore {
    @Volatile private var env: EnvConfig? = null
    @Volatile private var apt: AptClient? = null
    @Volatile private var pm: PackageManager? = null

    fun env(context: Context): EnvConfig =
        env ?: synchronized(this) { env ?: EnvConfig(context.applicationContext).also { env = it } }

    fun apt(context: Context): AptClient =
        apt ?: synchronized(this) { apt ?: AptClient(context.applicationContext, env(context)).also { apt = it } }

    fun packageManager(context: Context): PackageManager =
        pm ?: synchronized(this) { pm ?: PackageManager(context.applicationContext, env(context), apt(context)).also { pm = it } }
}
