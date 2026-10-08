package io.github.dovecoteescapee.byedpi.security

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.utility.getPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DisguiseUiController(private val activity: Activity) {

    private val handler = Handler(Looper.getMainLooper())
    private var clockRunnable: Runnable? = null
    private var stopwatchRunnable: Runnable? = null
    private var stopwatchStartTime: Long = 0L
    private var stopwatchAccumulated: Long = 0L
    private var isStopwatchRunning: Boolean = false

    private val PREF_SAVED_NOTE = "pref_camouflage_saved_note_text"

    fun attachOverlay(
        overlayContainer: ViewGroup,
        onUnlocked: () -> Unit
    ) {
        val currentDisguise = CamouflageManager.getCurrentDisguise(activity)
        if (currentDisguise == CamouflageManager.DisguiseMode.DEFAULT ||
            !CamouflageManager.isFakeUiEnabled(activity) ||
            CamouflageManager.isUnlockedInSession
        ) {
            overlayContainer.visibility = View.GONE
            overlayContainer.removeAllViews()
            return
        }

        overlayContainer.removeAllViews()
        overlayContainer.alpha = 1f
        overlayContainer.translationY = 0f
        overlayContainer.visibility = View.VISIBLE

        when (currentDisguise) {
            CamouflageManager.DisguiseMode.CALCULATOR -> {
                setupCalculator(overlayContainer, onUnlocked)
            }
            CamouflageManager.DisguiseMode.NOTES -> {
                setupNotes(overlayContainer, onUnlocked)
            }
            CamouflageManager.DisguiseMode.CLOCK -> {
                setupClock(overlayContainer, onUnlocked)
            }
            else -> {
                overlayContainer.visibility = View.GONE
            }
        }
    }

    private fun triggerUnlock(overlayContainer: ViewGroup, onUnlocked: () -> Unit) {
        overlayContainer.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        CamouflageManager.isUnlockedInSession = true
        stopAllRunnables()

        overlayContainer.animate()
            .alpha(0f)
            .translationY(-60f)
            .setDuration(260)
            .withEndAction {
                overlayContainer.visibility = View.GONE
                overlayContainer.removeAllViews()
                Toast.makeText(activity, R.string.disguise_unlocked_toast, Toast.LENGTH_SHORT).show()
                onUnlocked()
            }
            .start()
    }

    private fun stopAllRunnables() {
        clockRunnable?.let { handler.removeCallbacks(it) }
        stopwatchRunnable?.let { handler.removeCallbacks(it) }
    }

    // ==================== 1. CALCULATOR ====================
    private fun setupCalculator(container: ViewGroup, onUnlocked: () -> Unit) {
        val view = LayoutInflater.from(activity).inflate(R.layout.view_disguise_calculator, container, false)
        container.addView(view)

        val tvExpr = view.findViewById<TextView>(R.id.tv_calc_expression)
        val tvDisplay = view.findViewById<TextView>(R.id.tv_calc_display)
        val btnEq = view.findViewById<MaterialButton>(R.id.btn_calc_eq)

        val expr = StringBuilder()
        val unlockCode = CamouflageManager.getUnlockCode(activity)

        fun updateUi() {
            if (expr.isEmpty()) {
                tvExpr.text = ""
                tvDisplay.text = "0"
            } else {
                tvDisplay.text = expr.toString()
            }
        }

        fun append(char: String) {
            expr.append(char)
            updateUi()
        }

        val buttonMappings = mapOf(
            R.id.btn_calc_0 to "0", R.id.btn_calc_1 to "1", R.id.btn_calc_2 to "2",
            R.id.btn_calc_3 to "3", R.id.btn_calc_4 to "4", R.id.btn_calc_5 to "5",
            R.id.btn_calc_6 to "6", R.id.btn_calc_7 to "7", R.id.btn_calc_8 to "8",
            R.id.btn_calc_9 to "9", R.id.btn_calc_dot to ".",
            R.id.btn_calc_bracket_open to "(", R.id.btn_calc_bracket_close to ")",
            R.id.btn_calc_add to "+", R.id.btn_calc_sub to "-",
            R.id.btn_calc_mul to "×", R.id.btn_calc_div to "÷"
        )

        for ((id, symbol) in buttonMappings) {
            view.findViewById<MaterialButton>(id)?.setOnClickListener {
                append(symbol)
            }
        }

        view.findViewById<MaterialButton>(R.id.btn_calc_c)?.setOnClickListener {
            expr.clear()
            updateUi()
        }

        view.findViewById<MaterialButton>(R.id.btn_calc_del)?.setOnClickListener {
            if (expr.isNotEmpty()) {
                expr.deleteCharAt(expr.length - 1)
                updateUi()
            }
        }

        // Long press on "=" unlocks
        btnEq.setOnLongClickListener {
            triggerUnlock(container, onUnlocked)
            true
        }

        btnEq.setOnClickListener {
            val text = expr.toString().trim()
            // Check secret code
            if (text == unlockCode || text.endsWith(unlockCode)) {
                triggerUnlock(container, onUnlocked)
                return@setOnClickListener
            }

            // Real calculator evaluation
            try {
                val result = evaluateArithmetic(text.replace("×", "*").replace("÷", "/"))
                tvExpr.text = text
                expr.clear()
                val formatted = if (result % 1.0 == 0.0) {
                    result.toLong().toString()
                } else {
                    "%.4f".format(Locale.US, result).trimEnd('0').trimEnd('.')
                }
                expr.append(formatted)
                tvDisplay.text = formatted
            } catch (e: Exception) {
                tvExpr.text = text
                tvDisplay.text = "Ошибка"
                expr.clear()
            }
        }
    }

    private fun evaluateArithmetic(expression: String): Double {
        return CalculatorEvaluator(expression).parse()
    }

    private class CalculatorEvaluator(expression: String) {
        private val clean = expression.replace(" ", "")
        private var pos = -1
        private var ch = ' '

        private fun nextChar() {
            pos++
            ch = if (pos < clean.length) clean[pos] else '\u0000'
        }

        private fun eat(charToEat: Char): Boolean {
            while (ch == ' ') nextChar()
            if (ch == charToEat) {
                nextChar()
                return true
            }
            return false
        }

        fun parse(): Double {
            if (clean.isBlank()) return 0.0
            nextChar()
            return parseExpression()
        }

        private fun parseExpression(): Double {
            var x = parseTerm()
            while (true) {
                when {
                    eat('+') -> x += parseTerm()
                    eat('-') -> x -= parseTerm()
                    else -> return x
                }
            }
        }

        private fun parseTerm(): Double {
            var x = parseFactor()
            while (true) {
                when {
                    eat('*') -> x *= parseFactor()
                    eat('/') -> {
                        val d = parseFactor()
                        x = if (d != 0.0) x / d else 0.0
                    }
                    else -> return x
                }
            }
        }

        private fun parseFactor(): Double {
            if (eat('+')) return +parseFactor()
            if (eat('-')) return -parseFactor()

            var x: Double
            val startPos = pos
            if (eat('(')) {
                x = parseExpression()
                eat(')')
            } else if ((ch in '0'..'9') || ch == '.') {
                while ((ch in '0'..'9') || ch == '.') nextChar()
                x = clean.substring(startPos, pos).toDouble()
            } else {
                x = 0.0
            }
            return x
        }
    }

    // ==================== 2. NOTES ====================
    private fun setupNotes(container: ViewGroup, onUnlocked: () -> Unit) {
        val view = LayoutInflater.from(activity).inflate(R.layout.view_disguise_notes, container, false)
        container.addView(view)

        val etNote = view.findViewById<EditText>(R.id.et_note_content)
        val btnSave = view.findViewById<MaterialButton>(R.id.btn_notes_save)
        val toolbar = view.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.notes_toolbar)

        val unlockCode = CamouflageManager.getUnlockCode(activity)
        val triggerTag = "#$unlockCode"

        // Load saved notes
        val savedText = activity.getPreferences().getString(PREF_SAVED_NOTE, "")
        etNote.setText(savedText)

        btnSave.setOnClickListener {
            val textToSave = etNote.text.toString()
            activity.getPreferences().edit()
                .putString(PREF_SAVED_NOTE, textToSave)
                .apply()
            Toast.makeText(activity, R.string.disguise_notes_saved, Toast.LENGTH_SHORT).show()
        }

        // Long press toolbar to unlock
        toolbar.setOnLongClickListener {
            triggerUnlock(container, onUnlocked)
            true
        }

        etNote.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val current = s?.toString() ?: ""
                if (current.contains(triggerTag) || current.trim() == "//$unlockCode" || current.trim() == "//vpn") {
                    // Remove trigger and unlock
                    val cleaned = current.replace(triggerTag, "").replace("//$unlockCode", "").replace("//vpn", "").trim()
                    etNote.setText(cleaned)
                    triggerUnlock(container, onUnlocked)
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    // ==================== 3. CLOCK ====================
    private fun setupClock(container: ViewGroup, onUnlocked: () -> Unit) {
        val view = LayoutInflater.from(activity).inflate(R.layout.view_disguise_clock, container, false)
        container.addView(view)

        val tvTime = view.findViewById<TextView>(R.id.tv_clock_time)
        val tvDate = view.findViewById<TextView>(R.id.tv_clock_date)
        val tvStopwatch = view.findViewById<TextView>(R.id.tv_stopwatch_display)
        val btnStartPause = view.findViewById<MaterialButton>(R.id.btn_stopwatch_start_pause)
        val btnReset = view.findViewById<MaterialButton>(R.id.btn_stopwatch_reset)
        val layoutClock = view.findViewById<View>(R.id.layout_digital_clock)

        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val dateFormat = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault())

        // Live digital clock loop
        clockRunnable = object : Runnable {
            override fun run() {
                val now = Date()
                tvTime.text = timeFormat.format(now)
                tvDate.text = dateFormat.format(now).replaceFirstChar { it.uppercase() }
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(clockRunnable!!)

        // Stopwatch loop
        stopwatchRunnable = object : Runnable {
            @SuppressLint("DefaultLocale")
            override fun run() {
                val elapsed = stopwatchAccumulated + (SystemClock.elapsedRealtime() - stopwatchStartTime)
                val millis = (elapsed % 1000) / 100
                val seconds = (elapsed / 1000) % 60
                val minutes = (elapsed / (1000 * 60)) % 60
                tvStopwatch.text = String.format("%02d:%02d.%d", minutes, seconds, millis)
                handler.postDelayed(this, 80)
            }
        }

        btnStartPause.setOnClickListener {
            if (!isStopwatchRunning) {
                stopwatchStartTime = SystemClock.elapsedRealtime()
                handler.post(stopwatchRunnable!!)
                isStopwatchRunning = true
                btnStartPause.text = activity.getString(R.string.disguise_pause)
            } else {
                stopwatchAccumulated += (SystemClock.elapsedRealtime() - stopwatchStartTime)
                stopwatchRunnable?.let { handler.removeCallbacks(it) }
                isStopwatchRunning = false
                btnStartPause.text = activity.getString(R.string.disguise_start)
            }
        }

        btnReset.setOnClickListener {
            stopwatchRunnable?.let { handler.removeCallbacks(it) }
            isStopwatchRunning = false
            stopwatchAccumulated = 0L
            btnStartPause.text = activity.getString(R.string.disguise_start)
            tvStopwatch.text = "00:00.0"
        }

        // Secret unlock: Long press clock
        layoutClock.setOnLongClickListener {
            triggerUnlock(container, onUnlocked)
            true
        }

        // Secret unlock: 4 quick taps on the clock
        var tapCount = 0
        var lastTapTime = 0L
        layoutClock.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastTapTime < 800) {
                tapCount++
                if (tapCount >= 4) {
                    tapCount = 0
                    triggerUnlock(container, onUnlocked)
                }
            } else {
                tapCount = 1
            }
            lastTapTime = now
        }
    }
}
