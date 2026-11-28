package io.github.dovecoteescapee.byedpi.utility

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.dovecoteescapee.byedpi.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object UpdateManager {
    private const val UPDATE_URL =
        "https://byedpi-russia-ota-default-rtdb.europe-west1.firebasedatabase.app/android.json"

    fun checkForUpdates(activity: Activity, manual: Boolean = false) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = (URL(UPDATE_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6000
                    readTimeout = 6000
                }
                if (conn.responseCode !in 200..299) {
                    if (manual) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(activity, "Не удалось проверить обновления", Toast.LENGTH_SHORT).show()
                        }
                    }
                    return@launch
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val remoteVersion = json.optString("version", "").trim()
                val changelog = json.optString("changelog", "")
                val isSingBox = BuildConfig.FLAVOR.equals("singbox", ignoreCase = true)
                val apkUrl = if (isSingBox) {
                    json.optString("apkUrlSingBox", "")
                } else {
                    json.optString("apkUrlXray", "")
                }

                if (remoteVersion.isNotBlank() &&
                    remoteVersion != BuildConfig.VERSION_NAME &&
                    apkUrl.isNotBlank()
                ) {
                    withContext(Dispatchers.Main) {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            showUpdateDialog(activity, remoteVersion, changelog, apkUrl)
                        }
                    }
                } else if (manual) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(activity, "У вас установлена последняя версия (${BuildConfig.VERSION_NAME})", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (_: Exception) {
                if (manual) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(activity, "Ошибка проверки обновлений", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun showUpdateDialog(
        activity: Activity,
        version: String,
        changelog: String,
        apkUrl: String
    ) {
        MaterialAlertDialogBuilder(activity)
            .setTitle("Доступно обновление $version")
            .setMessage(changelog.ifBlank { "Доступна новая версия приложения." })
            .setPositiveButton("Обновить") { _, _ ->
                downloadAndInstall(activity, apkUrl)
            }
            .setNegativeButton("Позже", null)
            .show()
    }

    private fun downloadAndInstall(activity: Activity, apkUrl: String) {
        val pad = (24 * activity.resources.displayMetrics.density).toInt()
        val tv = TextView(activity).apply {
            text = "Скачивание обновления..."
            textSize = 14f
        }
        val pb = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            setPadding(0, pad / 2, 0, 0)
        }
        val box = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(tv)
            addView(pb)
        }

        val dlg = MaterialAlertDialogBuilder(activity)
            .setTitle("Загрузка APK")
            .setView(box)
            .setCancelable(false)
            .show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val outFile = File(activity.cacheDir, "update.apk")
                val conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 15000
                    readTimeout = 30000
                }
                val total = conn.contentLength.coerceAtLeast(1)
                conn.inputStream.use { input ->
                    outFile.outputStream().use { output ->
                        val buf = ByteArray(16384)
                        var downloaded = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            downloaded += n
                            val pct = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                            withContext(Dispatchers.Main) {
                                pb.progress = pct
                                tv.text = "Скачано: $pct%"
                            }
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    dlg.dismiss()
                    installApk(activity, outFile)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    dlg.dismiss()
                    Toast.makeText(activity, "Ошибка загрузки: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun installApk(activity: Activity, file: File) {
        val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        } else {
            Uri.fromFile(file)
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(intent)
    }
}
