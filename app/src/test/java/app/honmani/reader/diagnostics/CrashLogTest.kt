package app.honmani.reader.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager
import java.io.File

@RunWith(RobolectricTestRunner::class)
class CrashLogTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val file = File(context.filesDir, CrashLog.FILE_NAME)
    private val previous = Thread.getDefaultUncaughtExceptionHandler()

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previous)
        file.delete()
    }

    @Test
    fun aCrashIsSavedAndPassedOn() {
        var passedOn: Throwable? = null
        Thread.setDefaultUncaughtExceptionHandler { _, error -> passedOn = error }
        CrashLog(context, file).install()
        val error = IllegalStateException("page count went negative")

        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), error)

        assertTrue(passedOn === error)
        val report = CrashLog(context, file).report()
        assertTrue(report, report.contains("java.lang.IllegalStateException: page count went negative"))
        assertTrue(report, report.contains("CrashLogTest.aCrashIsSavedAndPassedOn"))
    }

    @Test
    fun withoutACrashTheReportSaysSo() {
        val report = CrashLog(context, file).report()
        assertTrue(report, report.contains("== Last crash\n(none recorded)"))
    }

    @Test
    @Config(sdk = [30])
    fun androidsRecordOfExitsIsListed() {
        val manager = context.getSystemService(ActivityManager::class.java)
        val shadow = shadowOf(manager) as ShadowActivityManager
        shadow.addApplicationExitInfo(
            ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
                .setReason(ApplicationExitInfo.REASON_LOW_MEMORY)
                .setDescription("lmk")
                .setTimestamp(0)
                .build(),
        )
        shadow.addApplicationExitInfo(
            ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
                .setProcessName("com.google.android.webview:sandboxed_process0")
                .setReason(ApplicationExitInfo.REASON_OTHER)
                .setDescription("isolated not needed")
                .setTimestamp(1)
                .build(),
        )

        val report = CrashLog(context, file).report()

        assertTrue(report, report.contains("low memory"))
        assertTrue(report, report.contains(": lmk"))
        // WebView ending a renderer no page needs is routine, not worth a line.
        assertFalse(report, report.contains("isolated not needed"))
    }
}
