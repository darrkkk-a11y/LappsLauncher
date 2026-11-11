package deb.runner.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import deb.runner.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Очередь установок в foreground-сервисе: установка не прерывается при повороте
 * экрана или сворачивании приложения (важно для Samsung DeX).
 */
class InstallService : Service() {

    companion object {
        private const val CHANNEL = "install"
        private const val NOTIF = 1001

        /** Живой лог текущей операции для UI. */
        val logFlow = MutableStateFlow<List<String>>(emptyList())
        val progressFlow = MutableStateFlow<Pair<Int, Int>?>(null)
        val busyFlow = MutableStateFlow(false)

        fun enqueue(context: Context, debPath: String? = null, aptNames: List<String>? = null) {
            val i = Intent(context, InstallService::class.java).apply {
                debPath?.let { putExtra("deb", it) }
                aptNames?.let { putStringArrayListExtra("apt", ArrayList(it)) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(i) else context.startService(i)
        }

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(NotificationManager::class.java)
                if (nm.getNotificationChannel(CHANNEL) == null) {
                    nm.createNotificationChannel(NotificationChannel(
                        CHANNEL, "Установка пакетов", NotificationManager.IMPORTANCE_LOW))
                }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF, buildNotification("Ожидание…"))
        val deb = intent?.getStringExtra("deb")
        val apt = intent?.getStringArrayListExtra("apt")?.toList()
        if ((deb != null || !apt.isNullOrEmpty()) && job?.isActive != true) {
            job = scope.launch { runInstall(deb, apt) }
        }
        return START_NOT_STICKY
    }

    private suspend fun runInstall(deb: String?, apt: List<String>?) {
        val app = applicationContext
        val env = DebRunnerCore.env(app)
        val pm = DebRunnerCore.packageManager(app)
        busyFlow.value = true
        logFlow.value = listOf("— старт —")
        val emit: (InstallEvent) -> Unit = { e ->
            when (e) {
                is InstallEvent.Log -> logFlow.value = logFlow.value.takeLast(400) + e.line
                is InstallEvent.Progress -> {
                    progressFlow.value = e.current to e.total
                    logFlow.value = logFlow.value.takeLast(400) + "[${e.current}/${e.total}] ${e.pkg}"
                }
                is InstallEvent.Done -> {
                    logFlow.value = logFlow.value + e.message
                    progressFlow.value = null; busyFlow.value = false
                    updateNotif(e.message)
                }
                is InstallEvent.Failed -> {
                    logFlow.value = logFlow.value + "✖ ${e.message}"
                    progressFlow.value = null; busyFlow.value = false
                    updateNotif("Ошибка: ${e.message}")
                }
            }
        }
        try {
            if (!apt.isNullOrEmpty()) pm.installFromRepo(apt, emit)
            if (deb != null) pm.installLocalDeb(java.io.File(deb), emit = emit)
        } catch (e: Exception) {
            emit(InstallEvent.Failed(e.message ?: "unknown"))
        } finally {
            busyFlow.value = false
            stopSelf()
        }
    }

    private fun updateNotif(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        ensureChannel(this)
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(deb.runner.R.string.app_name))
            .setContentText(text).setContentIntent(pi).setOngoing(true)
            .build()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
