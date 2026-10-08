package io.github.dovecoteescapee.byedpi.security

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.CountDownTimer
import android.view.LayoutInflater
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.dovecoteescapee.byedpi.R

object ModifiedBannerDialog {
    private const val GITHUB_RELEASES_URL = "https://github.com/byedpirussia/byedpirussia/releases"

    fun show(activity: Activity, onContinue: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return

        val challengeToken = TamperGuard.startBannerChallenge()

        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_modified_banner, null)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(view)
            .setCancelable(false)
            .create()

        dialog.setCanceledOnTouchOutside(false)

        val btnGithub = view.findViewById<MaterialButton>(R.id.btn_tamper_github)
        val btnContinue = view.findViewById<MaterialButton>(R.id.btn_tamper_continue)

        var countDownTimer: CountDownTimer? = null

        btnGithub.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_RELEASES_URL))
                activity.startActivity(intent)
            } catch (e: Exception) {
                // Ignore browser opening errors
            }
        }

        btnContinue.isEnabled = false
        btnContinue.text = activity.getString(R.string.tamper_banner_btn_continue_format, 7)

        countDownTimer = object : CountDownTimer(7000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                if (activity.isFinishing || activity.isDestroyed) {
                    cancel()
                    return
                }
                val secondsLeft = (millisUntilFinished / 1000L) + 1L
                btnContinue.text = activity.getString(R.string.tamper_banner_btn_continue_format, secondsLeft)
            }

            override fun onFinish() {
                if (activity.isFinishing || activity.isDestroyed) return
                btnContinue.text = activity.getString(R.string.tamper_banner_btn_continue)
                btnContinue.isEnabled = true
            }
        }.start()

        btnContinue.setOnClickListener {
            countDownTimer?.cancel()
            val ackSuccess = TamperGuard.acknowledgeBanner(activity, challengeToken)
            if (ackSuccess) {
                dialog.dismiss()
                onContinue()
            }
        }

        dialog.show()
    }
}
