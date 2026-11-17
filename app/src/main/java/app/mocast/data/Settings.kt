package app.mocast.data

import android.content.Context

enum class DisplayMode {
    ORIGINAL, TRANSLATED, BILINGUAL;

    companion object {
        fun from(name: String?): DisplayMode =
            entries.firstOrNull { it.name == name } ?: BILINGUAL
    }
}

data class Settings(
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val model: String = "gpt-4o-mini",
    val targetLang: String = "简体中文",
    val displayMode: DisplayMode = DisplayMode.BILINGUAL
) {
    val ready: Boolean get() = apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()
}

class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("mocast_settings", Context.MODE_PRIVATE)

    fun load(): Settings = Settings(
        baseUrl = prefs.getString(KEY_BASE, null) ?: "https://api.openai.com/v1",
        apiKey = prefs.getString(KEY_KEY, null) ?: "",
        model = prefs.getString(KEY_MODEL, null) ?: "gpt-4o-mini",
        targetLang = prefs.getString(KEY_LANG, null) ?: "简体中文",
        displayMode = DisplayMode.from(prefs.getString(KEY_MODE, null))
    )

    fun save(settings: Settings) {
        prefs.edit()
            .putString(KEY_BASE, settings.baseUrl)
            .putString(KEY_KEY, settings.apiKey)
            .putString(KEY_MODEL, settings.model)
            .putString(KEY_LANG, settings.targetLang)
            .putString(KEY_MODE, settings.displayMode.name)
            .apply()
    }

    companion object {
        private const val KEY_BASE = "baseUrl"
        private const val KEY_KEY = "apiKey"
        private const val KEY_MODEL = "model"
        private const val KEY_LANG = "targetLang"
        private const val KEY_MODE = "displayMode"
    }
}
