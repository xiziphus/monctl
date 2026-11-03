package com.xiziphus.monctl.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.xiziphus.monctl.net.Reply
import com.xiziphus.monctl.net.message
import com.xiziphus.monctl.ui.MonctlTheme
import com.xiziphus.monctl.ui.VDialog
import kotlinx.coroutines.launch

/**
 * The one-tap confirmation behind the widget's input chips. Stays open until
 * the switch is acknowledged, so the request is not cut off by the activity
 * (and possibly the process) going away under it.
 */
class ConfirmInputActivity : ComponentActivity() {
    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_NAME = "name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val code = intent.getIntExtra(EXTRA_CODE, -1)
        val name = intent.getStringExtra(EXTRA_NAME) ?: "input $code"
        if (code < 0) {
            finish()
            return
        }

        setContent {
            MonctlTheme {
                val scope = rememberCoroutineScope()
                var busy by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf<String?>(null) }

                VDialog(
                    title = if (busy) "Switching to $name…" else "Switch to $name?",
                    text = error ?: "The monitor drops its current signal. DisplayPort is the Mac.",
                    confirm = if (error == null) "Switch" else "Retry",
                    busy = busy,
                    onConfirm = {
                        busy = true
                        scope.launch {
                            val r = WidgetActions.switchInput(applicationContext, code)
                            if (r is Reply.Ok) finish() else {
                                busy = false
                                error = r.message()
                            }
                        }
                    },
                    onDismiss = { finish() },
                )
            }
        }
    }
}
