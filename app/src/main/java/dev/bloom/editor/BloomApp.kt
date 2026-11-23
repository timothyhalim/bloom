package dev.bloom.editor

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.util.Log
import org.acra.ReportField
import org.acra.config.CoreConfiguration
import org.acra.config.dialog
import org.acra.data.CrashReportData
import org.acra.data.StringFormat
import org.acra.ktx.initAcra
import org.acra.sender.ReportSender
import org.acra.sender.ReportSenderFactory
import kotlin.concurrent.thread

class BloomApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // After a crash ACRA asks whether to share the report (again on the next launch if unanswered); nothing is sent by itself.
        initAcra {
            reportFormat = StringFormat.KEY_VALUE_LIST
            // no logcat, settings or preferences: they can hold file names and other personal data
            reportContent = listOf(
                ReportField.STACK_TRACE, ReportField.APP_VERSION_NAME, ReportField.APP_VERSION_CODE, ReportField.ANDROID_VERSION,
                ReportField.BRAND, ReportField.PHONE_MODEL, ReportField.AVAILABLE_MEM_SIZE, ReportField.TOTAL_MEM_SIZE, ReportField.USER_CRASH_DATE,
            )
            dialog {
                title = "Bloom stopped"
                text = "Share the crash report? It holds the error, the app and Android versions, the phone model and its memory. No photos."
                positiveButtonText = "Share"
                negativeButtonText = "Don't share"
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Android 14+ only sends UI_HIDDEN and BACKGROUND; older versions also warn while in use (RUNNING_CRITICAL).
        // Models reload from disk on their next use. Off the main thread: a model run in progress holds the lock.
        @Suppress("DEPRECATION")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) thread {
            Segment.release()
            Log.d("Bloom", "trim memory $level: AI models freed")
        }
    }
}

/** Hands the report to the share sheet (mail, chat, notes…): no server, no address to keep. Found by ACRA through META-INF/services. */
class ShareSenderFactory : ReportSenderFactory {
    override fun create(context: Context, config: CoreConfiguration) = object : ReportSender {
        override fun send(context: Context, errorContent: CrashReportData) {
            val text = config.reportFormat.toFormattedString(errorContent, config.reportContent, "\n", "\n  ", false)
            val share = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Bloom crash report")
                .putExtra(Intent.EXTRA_TEXT, text)
            context.startActivity(Intent.createChooser(share, "Share crash report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        // starting the share sheet needs the foreground (Android 10+ blocks background activity starts)
        override fun requiresForeground() = true
    }
}
