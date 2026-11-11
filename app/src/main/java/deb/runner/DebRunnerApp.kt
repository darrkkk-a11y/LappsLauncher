package deb.runner

import android.app.Application
import deb.runner.core.InstallService

class DebRunnerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        InstallService.ensureChannel(this)
    }
}
