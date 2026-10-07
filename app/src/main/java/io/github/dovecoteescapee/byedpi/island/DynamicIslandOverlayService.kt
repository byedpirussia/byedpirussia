package io.github.dovecoteescapee.byedpi.island

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.activities.MainActivity
import io.github.dovecoteescapee.byedpi.data.AppStatus
import io.github.dovecoteescapee.byedpi.openflux.OpenFluxManager
import io.github.dovecoteescapee.byedpi.openflux.OpenFluxVpnService
import io.github.dovecoteescapee.byedpi.services.appStatus
import io.github.dovecoteescapee.byedpi.utility.isDynamicIslandEnabled
import io.github.dovecoteescapee.byedpi.vless.VlessManager
import io.github.dovecoteescapee.byedpi.vless.VlessVpnService
import io.github.dovecoteescapee.byedpi.warp.WarpVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class DynamicIslandOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var islandView: View? = null
    private var isExpanded = false
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val autoCollapseRunnable = Runnable {
        collapseIsland()
    }

    companion object {
        var isServiceRunning = false
            private set

        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            val intent = Intent(context, DynamicIslandOverlayService::class.java)
            try {
                context.startService(intent)
            } catch (e: Exception) {
                // Ignore if background start is restricted
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, DynamicIslandOverlayService::class.java)
            context.stopService(intent)
        }

        fun updateServiceState(context: Context) {
            if (context.isDynamicIslandEnabled() && Settings.canDrawOverlays(context)) {
                if (!isServiceRunning) start(context)
            } else {
                if (isServiceRunning) stop(context)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        isServiceRunning = true
        initOverlay()
        observeServices()
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        removeOverlay()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val inflater = LayoutInflater.from(this)
        islandView = inflater.inflate(R.layout.layout_dynamic_island, null)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dpToPx(8) // Сверху, прямо под/вокруг выреза фронтальной камеры
        }

        setupViews()
        setupTouch(params)

        try {
            windowManager?.addView(islandView, params)
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun removeOverlay() {
        if (islandView != null) {
            try {
                windowManager?.removeView(islandView)
            } catch (e: Exception) {
                // Ignore
            }
            islandView = null
        }
    }

    private fun setupViews() {
        val root = islandView ?: return
        val collapsedLayout = root.findViewById<View>(R.id.island_collapsed)
        val expandedLayout = root.findViewById<View>(R.id.island_expanded)
        val btnCollapse = root.findViewById<View>(R.id.btn_island_collapse)

        collapsedLayout.setOnClickListener {
            expandIsland()
        }

        btnCollapse.setOnClickListener {
            collapseIsland()
        }

        // Кнопки выбора режимов
        root.findViewById<View>(R.id.btn_mode_byedpi).setOnClickListener {
            ServiceSwitchController.switchTo(this, ServiceSwitchController.MODE_BYEDPI)
            collapseWithDelay()
        }

        root.findViewById<View>(R.id.btn_mode_warp).setOnClickListener {
            ServiceSwitchController.switchTo(this, ServiceSwitchController.MODE_WARP)
            collapseWithDelay()
        }

        root.findViewById<View>(R.id.btn_mode_vless).setOnClickListener {
            ServiceSwitchController.switchTo(this, ServiceSwitchController.MODE_VLESS)
            collapseWithDelay()
        }

        root.findViewById<View>(R.id.btn_mode_openflux).setOnClickListener {
            ServiceSwitchController.switchTo(this, ServiceSwitchController.MODE_OPENFLUX)
            collapseWithDelay()
        }

        root.findViewById<MaterialButton>(R.id.btn_island_stop).setOnClickListener {
            ServiceSwitchController.stopAll(this)
            collapseWithDelay()
        }

        root.findViewById<MaterialButton>(R.id.btn_island_open_app).setOnClickListener {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
            collapseIsland()
        }

        updateUi()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouch(params: WindowManager.LayoutParams) {
        val root = islandView ?: return
        val collapsedLayout = root.findViewById<View>(R.id.island_collapsed)

        var initialY = 0
        var initialTouchY = 0f
        var isMoved = false

        collapsedLayout.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialY = params.y
                    initialTouchY = event.rawY
                    isMoved = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaY = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(deltaY) > 10) {
                        isMoved = true
                        params.y = Math.max(0, initialY + deltaY)
                        try {
                            windowManager?.updateViewLayout(islandView, params)
                        } catch (e: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isMoved) {
                        collapsedLayout.performClick()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun expandIsland() {
        isExpanded = true
        val root = islandView ?: return
        root.findViewById<View>(R.id.island_collapsed).visibility = View.GONE
        root.findViewById<View>(R.id.island_expanded).visibility = View.VISIBLE
        updateUi()

        handler.removeCallbacks(autoCollapseRunnable)
        handler.postDelayed(autoCollapseRunnable, 6000)
    }

    private fun collapseIsland() {
        isExpanded = false
        handler.removeCallbacks(autoCollapseRunnable)
        val root = islandView ?: return
        root.findViewById<View>(R.id.island_collapsed).visibility = View.VISIBLE
        root.findViewById<View>(R.id.island_expanded).visibility = View.GONE
        updateUi()
    }

    private fun collapseWithDelay() {
        updateUi()
        handler.removeCallbacks(autoCollapseRunnable)
        handler.postDelayed({ collapseIsland() }, 350)
    }

    private fun observeServices() {
        scope.launch {
            WarpVpnService.isRunning.collectLatest { updateUi() }
        }
        scope.launch {
            WarpVpnService.warpPingMs.collectLatest { updateUi() }
        }
        scope.launch {
            VlessVpnService.isRunning.collectLatest { updateUi() }
        }
        scope.launch {
            OpenFluxVpnService.isRunning.collectLatest { updateUi() }
        }
    }

    private fun updateUi() {
        val root = islandView ?: return

        val activeMode = ServiceSwitchController.getActiveMode()
        val collapsedText = root.findViewById<TextView>(R.id.island_collapsed_text)
        val collapsedDot = root.findViewById<View>(R.id.island_collapsed_dot)
        val collapsedBadge = root.findViewById<TextView>(R.id.island_collapsed_badge)

        val expandedIcon = root.findViewById<ImageView>(R.id.island_expanded_icon)
        val expandedTitle = root.findViewById<TextView>(R.id.island_expanded_title)
        val expandedSubtitle = root.findViewById<TextView>(R.id.island_expanded_subtitle)

        val warpPing = WarpVpnService.warpPingMs.value

        var color = Color.parseColor("#4CAF50")
        var title = "ByeDPI"
        var subtitle = "DPI Desync Fix активен"
        var badge = "🟢"

        when (activeMode) {
            ServiceSwitchController.MODE_WARP -> {
                color = Color.parseColor("#FF9800")
                val pingStr = if (warpPing != null && warpPing >= 0) " (${warpPing}ms)" else ""
                title = "WARP$pingStr"
                subtitle = "Cloudflare AmneziaWG (AWG 2.0)$pingStr"
                badge = "⚡"
                expandedIcon.setImageResource(R.drawable.ic_shield_check_24)
            }
            ServiceSwitchController.MODE_VLESS -> {
                color = Color.parseColor("#AB47BC")
                val cfg = VlessManager.getSelectedConfig(this)?.name ?: "VLESS"
                title = "VLESS ($cfg)"
                subtitle = "Xray Core • $cfg"
                badge = "🌐"
                expandedIcon.setImageResource(R.drawable.ic_bolt_24)
            }
            ServiceSwitchController.MODE_OPENFLUX -> {
                color = Color.parseColor("#00BCD4")
                val cfg = OpenFluxManager.getSelectedConfig(this)?.name ?: "OpenFLUX"
                title = "OpenFLUX"
                subtitle = "Туннель через белые списки ($cfg)"
                badge = "🚀"
                expandedIcon.setImageResource(R.drawable.ic_bolt_24)
            }
            ServiceSwitchController.MODE_BYEDPI -> {
                color = Color.parseColor("#4CAF50")
                title = "ByeDPI"
                subtitle = "Защита от ТСПУ и замедлений"
                badge = "🟢"
                expandedIcon.setImageResource(R.drawable.ic_shield_check_24)
            }
            else -> {
                color = Color.parseColor("#78909C")
                title = "ByeDPI Выкл"
                subtitle = "Все службы отключены"
                badge = "⚪"
                expandedIcon.setImageResource(R.drawable.ic_shield_off_24)
            }
        }

        collapsedText.text = title
        collapsedBadge.text = badge

        val dotDrawable = collapsedDot.background as? GradientDrawable
        dotDrawable?.setColor(color)

        expandedIcon.setColorFilter(color)
        expandedTitle.text = title
        expandedSubtitle.text = subtitle

        // Подсветка активной кнопки режима
        highlightChip(root.findViewById(R.id.btn_mode_byedpi), activeMode == ServiceSwitchController.MODE_BYEDPI)
        highlightChip(root.findViewById(R.id.btn_mode_warp), activeMode == ServiceSwitchController.MODE_WARP)
        highlightChip(root.findViewById(R.id.btn_mode_vless), activeMode == ServiceSwitchController.MODE_VLESS)
        highlightChip(root.findViewById(R.id.btn_mode_openflux), activeMode == ServiceSwitchController.MODE_OPENFLUX)
    }

    private fun highlightChip(chip: LinearLayout, isActive: Boolean) {
        val bg = chip.background as? GradientDrawable
        if (isActive) {
            bg?.setColor(Color.parseColor("#40FFFFFF"))
            bg?.setStroke(dpToPx(1), Color.parseColor("#80FFFFFF"))
        } else {
            bg?.setColor(Color.parseColor("#18FFFFFF"))
            bg?.setStroke(dpToPx(1), Color.parseColor("#26FFFFFF"))
        }
    }
}
