package io.github.dovecoteescapee.byedpi.warp

import android.content.Context
import android.util.Log
import com.hiddify.core.libbox.CommandServer
import com.hiddify.core.libbox.Libbox
import com.hiddify.core.libbox.SetupOptions
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

object WarpMasqueHiddify {
    private const val TAG = "WarpMasqueHiddify"
    private var commandServer: CommandServer? = null
    private val isRunning = AtomicBoolean(false)
    private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return
        try {
            val baseDir = File(context.filesDir, "hiddify_base").apply { mkdirs() }
            val workingDir = File(context.filesDir, "hiddify_work").apply { mkdirs() }
            val tempDir = File(context.cacheDir, "hiddify_temp").apply { mkdirs() }

            val options = SetupOptions().apply {
                this.basePath = baseDir.absolutePath
                this.workingPath = workingDir.absolutePath
                this.tempPath = tempDir.absolutePath
                this.fixAndroidStack = true
                this.debug = false
            }
            Libbox.setup(options)
            isInitialized = true
            Log.i(TAG, "Hiddify Core Libbox.setup успешно выполнен")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка инициализации Libbox.setup", e)
        }
    }

    fun start(context: Context, singBoxJsonConfig: String) {
        if (isRunning.get()) {
            stop()
        }
        init(context)

        try {
            Log.i(TAG, "Проверка валидности MASQUE конфигурации через Libbox.checkConfig...")
            Libbox.checkConfig(singBoxJsonConfig)
            Log.i(TAG, "MASQUE конфигурация успешно валидирована!")
        } catch (e: Exception) {
            Log.w(TAG, "Предупреждение Libbox.checkConfig: ${e.message}")
        }

        try {
            val platform = HiddifyPlatformInterface(context)
            val handler = HiddifyCommandHandler()
            val server = CommandServer(handler, platform)
            commandServer = server
            server.start()
            server.startOrReloadService(singBoxJsonConfig, null)
            isRunning.set(true)
            Log.i(TAG, "Hiddify CommandServer успешно запущен с нативным MASQUE!")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка старта Hiddify CommandServer", e)
            throw e
        }
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        try {
            commandServer?.closeService()
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка closeService: ${e.message}")
        }
        try {
            commandServer?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка close: ${e.message}")
        }
        commandServer = null
        Log.i(TAG, "Hiddify CommandServer остановлен")
    }

    fun isServerRunning(): Boolean = isRunning.get()
}
