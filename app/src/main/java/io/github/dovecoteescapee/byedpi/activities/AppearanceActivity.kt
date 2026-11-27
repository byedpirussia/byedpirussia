package io.github.dovecoteescapee.byedpi.activities

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.databinding.ActivityAppearanceBinding
import io.github.dovecoteescapee.byedpi.utility.AppearanceManager

class AppearanceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppearanceBinding

    private val presetThemes = listOf(
        "blue" to 0xFF3F51B5.toInt(),    // Indigo
        "emerald" to 0xFF00897B.toInt(), // Teal / Emerald
        "crimson" to 0xFFD32F2F.toInt(), // Crimson
        "amber" to 0xFFEF6C00.toInt(),   // Amber
        "cyan" to 0xFF0284C7.toInt(),    // Sky / Cyan
        "purple" to 0xFF7B1FA2.toInt()   // Purple
    )

    private val fontPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            if (AppearanceManager.saveCustomFont(this, uri)) {
                binding.chipFontCustom.isChecked = true
                updatePreview()
                showRestartBanner()
                Toast.makeText(this, "Шрифт загружен!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Не удалось загрузить файл шрифта", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        val scaledConfig = AppearanceManager.applyFontScale(newBase, newBase.resources.configuration)
        super.attachBaseContext(newBase.createConfigurationContext(scaledConfig))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppearanceBinding.inflate(layoutInflater)
        setContentView(binding.root)
        AppearanceManager.applyPostInflation(this)

        binding.toolbar.setNavigationOnClickListener { finish() }
        io.github.dovecoteescapee.byedpi.utility.BottomNavHelper.setup(
            this,
            io.github.dovecoteescapee.byedpi.utility.BottomNavHelper.Tab.APPEARANCE
        )

        setupColorPresets()
        setupThemeChips()
        setupFontChips()
        setupLanguageChips()
        setupRadiusChips()
        setupScaleChips()
        setupSwitches()

        binding.btnRestartApp.setOnClickListener {
            AppearanceManager.restartApp(this)
        }

        updatePreview()
    }

    private fun setupColorPresets() {
        val current = AppearanceManager.getAccentColor(this)
        binding.colorPresetsRow.removeAllViews()
        val sizePx = dpToPx(38)
        val marginPx = dpToPx(8)

        presetThemes.forEach { (themeKey, color) ->
            val circle = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                    marginEnd = marginPx
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    if (color == current) {
                        setStroke(dpToPx(3), Color.WHITE)
                    }
                }
                setOnClickListener {
                    AppearanceManager.setAccentColor(this@AppearanceActivity, color)
                    androidx.preference.PreferenceManager.getDefaultSharedPreferences(this@AppearanceActivity)
                        .edit().putString("accent_color", themeKey).apply()
                    setupColorPresets()
                    updatePreview()
                    showRestartBanner()
                }
            }
            binding.colorPresetsRow.addView(circle)
        }

        binding.btnCustomColor.setOnClickListener {
            showColorPickerDialog()
        }
    }

    private fun showColorPickerDialog() {
        val initialColor = AppearanceManager.getAccentColor(this)
        val hsv = FloatArray(3)
        Color.colorToHSV(initialColor, hsv)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(20), dpToPx(16), dpToPx(20), dpToPx(8))
        }

        val previewBox = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(48)).apply {
                bottomMargin = dpToPx(12)
            }
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(12).toFloat()
                setColor(initialColor)
            }
        }
        val hexLabel = TextView(this).apply {
            text = String.format("#%06X", 0xFFFFFF and initialColor)
            textSize = 14f
            setPadding(0, 0, 0, dpToPx(8))
        }

        fun updateColorBox() {
            val c = Color.HSVToColor(hsv)
            (previewBox.background as GradientDrawable).setColor(c)
            hexLabel.text = String.format("#%06X", 0xFFFFFF and c)
        }

        fun createSlider(label: String, max: Int, initial: Int, onChanged: (Int) -> Unit): SeekBar {
            val tv = TextView(this).apply {
                text = label
                textSize = 12f
            }
            container.addView(tv)
            return SeekBar(this).apply {
                this.max = max
                progress = initial
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        onChanged(p)
                        updateColorBox()
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
                container.addView(this)
            }
        }

        container.addView(previewBox)
        container.addView(hexLabel)
        createSlider("Оттенок (Hue)", 360, hsv[0].toInt()) { hsv[0] = it.toFloat() }
        createSlider("Насыщенность", 100, (hsv[1] * 100).toInt()) { hsv[1] = it / 100f }
        createSlider("Яркость", 100, (hsv[2] * 100).toInt()) { hsv[2] = it / 100f }

        MaterialAlertDialogBuilder(this)
            .setTitle("Свой акцентный цвет")
            .setView(container)
            .setPositiveButton("Выбрать") { _, _ ->
                val chosen = Color.HSVToColor(hsv)
                AppearanceManager.setAccentColor(this, chosen)
                setupColorPresets()
                updatePreview()
                showRestartBanner()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun setupThemeChips() {
        when (AppearanceManager.getThemeMode(this)) {
            "light" -> binding.chipThemeLight.isChecked = true
            "dark" -> binding.chipThemeDark.isChecked = true
            "amoled" -> binding.chipThemeAmoled.isChecked = true
            else -> binding.chipThemeSystem.isChecked = true
        }
        binding.chipGroupTheme.setOnCheckedStateChangeListener { _, checkedIds ->
            val mode = when (checkedIds.firstOrNull()) {
                R.id.chip_theme_light -> "light"
                R.id.chip_theme_dark -> "dark"
                R.id.chip_theme_amoled -> "amoled"
                else -> "system"
            }
            AppearanceManager.setThemeMode(this, mode)
            AppearanceManager.applyThemeAndLocale(this)
            updatePreview()
            showRestartBanner()
        }
    }

    private fun setupFontChips() {
        when (AppearanceManager.getFontMode(this)) {
            "system" -> binding.chipFontSystem.isChecked = true
            "custom" -> binding.chipFontCustom.isChecked = true
            else -> binding.chipFontGoogleSans.isChecked = true
        }
        binding.chipGroupFont.setOnCheckedStateChangeListener { _, checkedIds ->
            val mode = when (checkedIds.firstOrNull()) {
                R.id.chip_font_system -> "system"
                R.id.chip_font_custom -> "custom"
                else -> "google_sans"
            }
            AppearanceManager.setFontMode(this, mode)
            updatePreview()
            showRestartBanner()
        }
        binding.btnUploadFont.setOnClickListener {
            fontPickerLauncher.launch(arrayOf("font/ttf", "application/x-font-ttf", "application/octet-stream", "*/*"))
        }
    }

    private fun setupLanguageChips() {
        when (AppearanceManager.getLanguage(this)) {
            "ru" -> binding.chipLangRu.isChecked = true
            "en" -> binding.chipLangEn.isChecked = true
            "fa" -> binding.chipLangFa.isChecked = true
            else -> binding.chipLangSystem.isChecked = true
        }
        binding.chipGroupLang.setOnCheckedStateChangeListener { _, checkedIds ->
            val lang = when (checkedIds.firstOrNull()) {
                R.id.chip_lang_ru -> "ru"
                R.id.chip_lang_en -> "en"
                R.id.chip_lang_fa -> "fa"
                else -> "system"
            }
            AppearanceManager.setLanguage(this, lang)
            showRestartBanner()
        }
    }

    private fun setupRadiusChips() {
        when (AppearanceManager.getCardRadiusDp(this)) {
            12 -> binding.chipRadius12.isChecked = true
            32 -> binding.chipRadius32.isChecked = true
            else -> binding.chipRadius24.isChecked = true
        }
        binding.chipGroupRadius.setOnCheckedStateChangeListener { _, checkedIds ->
            val dp = when (checkedIds.firstOrNull()) {
                R.id.chip_radius_12 -> 12
                R.id.chip_radius_32 -> 32
                else -> 24
            }
            AppearanceManager.setCardRadiusDp(this, dp)
            updatePreview()
            showRestartBanner()
        }
    }

    private fun setupScaleChips() {
        val scale = AppearanceManager.getFontScale(this)
        when {
            scale < 0.95f -> binding.chipScaleSmall.isChecked = true
            scale > 1.05f -> binding.chipScaleLarge.isChecked = true
            else -> binding.chipScaleNormal.isChecked = true
        }
        binding.chipGroupScale.setOnCheckedStateChangeListener { _, checkedIds ->
            val newScale = when (checkedIds.firstOrNull()) {
                R.id.chip_scale_small -> 0.9f
                R.id.chip_scale_large -> 1.15f
                else -> 1.0f
            }
            AppearanceManager.setFontScale(this, newScale)
            updatePreview()
            showRestartBanner()
        }
    }

    private fun setupSwitches() {
        binding.switchPrivacy.isChecked = AppearanceManager.isPrivacyMode(this)
        binding.switchPrivacy.setOnCheckedChangeListener { _, isChecked ->
            AppearanceManager.setPrivacyMode(this, isChecked)
            updatePreview()
            showRestartBanner()
        }

        binding.switchAnimations.isChecked = AppearanceManager.isAnimationsEnabled(this)
        binding.switchAnimations.setOnCheckedChangeListener { _, isChecked ->
            AppearanceManager.setAnimationsEnabled(this, isChecked)
            showRestartBanner()
        }
    }

    private fun updatePreview() {
        val accent = AppearanceManager.getAccentColor(this)
        val radiusPx = dpToPx(AppearanceManager.getCardRadiusDp(this)).toFloat()
        val privacy = AppearanceManager.isPrivacyMode(this)
        val scale = AppearanceManager.getFontScale(this)

        binding.previewCardStatus.radius = radiusPx
        binding.previewCardModule.radius = radiusPx
        binding.previewCardStatus.strokeColor = accent
        binding.previewCardModule.strokeColor = ColorUtils.setAlphaComponent(accent, 90)
        binding.previewIconBg.setCardBackgroundColor(ColorUtils.setAlphaComponent(accent, 38))
        binding.previewIcon.imageTintList = ColorStateList.valueOf(accent)
        binding.previewSwitch.thumbTintList = ColorStateList.valueOf(accent)
        binding.previewSwitch.trackTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 110))

        binding.previewSubtitle.text = if (privacy) {
            "Сервер: •••.•••.•••.•••:••• • 42 ms"
        } else {
            "Сервер: 162.159.192.1:500 • 42 ms"
        }

        binding.previewTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * scale)
        binding.previewSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * scale)
        binding.previewModuleTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * scale)
        binding.previewModuleSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * scale)

        val tf = AppearanceManager.getActiveTypeface(this) ?: Typeface.DEFAULT
        binding.previewTitle.typeface = tf
        binding.previewSubtitle.typeface = tf
        binding.previewModuleTitle.typeface = tf
        binding.previewModuleSub.typeface = tf
    }

    private fun showRestartBanner() {
        if (binding.restartBanner.visibility != View.VISIBLE) {
            if (AppearanceManager.isAnimationsEnabled(this)) {
                binding.restartBanner.alpha = 0f
                binding.restartBanner.visibility = View.VISIBLE
                binding.restartBanner.animate().alpha(1f).setDuration(200).start()
            } else {
                binding.restartBanner.alpha = 1f
                binding.restartBanner.visibility = View.VISIBLE
            }
        }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()
}
