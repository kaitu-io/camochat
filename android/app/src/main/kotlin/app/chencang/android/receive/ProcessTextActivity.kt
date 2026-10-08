package app.chencang.android.receive

import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.chencang.android.MainActivity
import app.chencang.android.navigation.LaunchRequest
import app.chencang.android.navigation.toExtras
import app.chencang.shared.CcServiceLocator
import app.chencang.shared.R
import app.chencang.shared.intake.IntakeFailure
import app.chencang.shared.intake.IntakeOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Intake from outside the app: text selected in another app's "Decrypt with …" menu
 * (`ACTION_PROCESS_TEXT`), text shared to us from the system share sheet (`ACTION_SEND`
 * text/plain), or a pairing link opened through App Links (`ACTION_VIEW` https `/p/`). Everything goes through [IncomingIntake][app.chencang.shared.intake.IncomingIntake],
 * then [IntakeRouting] decides: open the app, a toast, or a short question. No UI of its own;
 * always [finish]es. Never logs the selected / shared text.
 */
class ProcessTextActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A recreation (rotation while the dialog is up) must not run the intake a second time.
        if (savedInstanceState != null) {
            finish()
            return
        }
        val text = IntentText.from(
            action = intent.action,
            processText = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT),
            sendText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT),
            dataString = intent.dataString,
        )
        val locator = CcServiceLocator.from(applicationContext)
        lifecycleScope.launch {
            val outcome = try {
                locator.incomingIntake.handle(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "intake failed: ${e.javaClass.simpleName}")
                IntakeOutcome.Failed(IntakeFailure.SAVE_FAILED)
            }
            when (val route = IntakeRouting.route(outcome)) {
                is IntakeRoute.Main -> openApp(route.request)
                is IntakeRoute.Notice -> {
                    Toast.makeText(applicationContext, route.messageRes, Toast.LENGTH_LONG).show()
                    route.then?.let { openApp(it) } ?: finish()
                }
                is IntakeRoute.Ask -> ask(route)
            }
        }
    }

    /**
     * MainActivity 是标准启动模式：NEW_TASK + CLEAR_TOP 会把已在运行的实例（含正开着的配对向导）
     * 销毁重建，再带着本次请求落到主屏。结果恒定：以新链接为准，不与旧向导页面叠加。
     */
    private fun openApp(request: LaunchRequest) {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                request.toExtras(this)
            },
        )
        finish()
    }

    private fun ask(route: IntakeRoute.Ask) {
        // There is no public DeviceDefault DayNight alert style: pick light / dark by the current mode.
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val theme = if (night) {
            android.R.style.Theme_DeviceDefault_Dialog_Alert
        } else {
            android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
        }
        var acted = false
        AlertDialog.Builder(this, theme)
            .setMessage(route.messageRes)
            .setPositiveButton(route.actionRes) { _, _ ->
                acted = true
                openApp(route.onAction)
            }
            .setNegativeButton(R.string.common_cancel, null)
            .setOnDismissListener { if (!acted) finish() }
            .show()
    }

    private companion object {
        const val TAG = "Chencang"
    }
}
