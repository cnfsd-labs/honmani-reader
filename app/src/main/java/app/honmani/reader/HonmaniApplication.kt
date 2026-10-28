package app.honmani.reader

import android.app.Application
import app.honmani.reader.diagnostics.CrashLog

class HonmaniApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        CrashLog(this).install()
    }
}
