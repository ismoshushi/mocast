package app.mocast.data

import android.content.Context

enum class DisplayMode {
    ORIGINAL, TRANSLATED, BILINGUAL;

    companion object {
        fun from(name: String?): DisplayMode =
            entries.firstOrNull { it.name == name } ?: BILINGUAL
    }
}

/** 外观：跟随系统 / 白日 / 夜色 */
enum class ThemeMode {
    SYSTEM, LIGHT, DARK;

    companion object {
        fun from(name: String?): ThemeMode =
            entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/** 翻译模式：逐句完整翻译 / 提炼关键词 */
enum class TranslationMode(val label: String) {
    FULL("完整翻译"), KEYWORD("关键词翻译");

    companion object {
        fun from(name: String?): TranslationMode =
            entries.firstOrNull { it.name == name } ?: FULL
    }
}

/**
 * 学习者词汇层级。关键词模式只标注超出所选层级的生词。
 * 词量参考常见大纲/考试要求：中考 1600、高考 3500、CET4 4500、CET6 6000、考研 8000、专八 12000+。
 */
enum class KeywordLevel(val label: String, val vocab: String, val hint: String) {
    JUNIOR("初中", "1600", "初中大纲以内（约 1600 词）的词不算生词"),
    SENIOR("高中", "3500", "高中大纲以内（约 3500 词）的词不算生词"),
    CET4("四级", "4500", "大学四级以内（约 4500 词）的词不算生词"),
    CET6("六级", "6000", "大学六级以内（约 6000 词）的词不算生词"),
    POSTGRAD("考研", "8000", "考研大纲以内（约 8000 词）的词不算生词"),
    TEM8("专八", "12000+", "专八/雅思托福范围（约 12000+ 词）以内不算生词");

    companion object {
        fun from(name: String?): KeywordLevel =
            entries.firstOrNull { it.name == name } ?: SENIOR
    }
}

data class Settings(
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val model: String = "gpt-4o-mini",
    val targetLang: String = "简体中文",
    val displayMode: DisplayMode = DisplayMode.BILINGUAL,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val translationMode: TranslationMode = TranslationMode.FULL,
    val keywordLevel: KeywordLevel = KeywordLevel.SENIOR,
    val autoExtend: Boolean = true
) {
    val ready: Boolean get() = apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()

    /** 缓存/请求用的变体标识：不同模式与难度互不覆盖 */
    val translationVariant: String
        get() = buildString {
            append(targetLang)
            append('_')
            append(translationMode.name)
            if (translationMode == TranslationMode.KEYWORD) {
                append('_')
                append(keywordLevel.name)
            }
        }
}

class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("mocast_settings", Context.MODE_PRIVATE)

    fun load(): Settings = Settings(
        baseUrl = prefs.getString(KEY_BASE, null) ?: "https://api.openai.com/v1",
        apiKey = prefs.getString(KEY_KEY, null) ?: "",
        model = prefs.getString(KEY_MODEL, null) ?: "gpt-4o-mini",
        targetLang = prefs.getString(KEY_LANG, null) ?: "简体中文",
        displayMode = DisplayMode.from(prefs.getString(KEY_MODE, null)),
        themeMode = ThemeMode.from(prefs.getString(KEY_THEME, null)),
        translationMode = TranslationMode.from(prefs.getString(KEY_TR_MODE, null)),
        keywordLevel = KeywordLevel.from(prefs.getString(KEY_TR_LEVEL, null)),
        autoExtend = prefs.getBoolean(KEY_AUTO_EXTEND, true)
    )

    fun save(settings: Settings) {
        prefs.edit()
            .putString(KEY_BASE, settings.baseUrl)
            .putString(KEY_KEY, settings.apiKey)
            .putString(KEY_MODEL, settings.model)
            .putString(KEY_LANG, settings.targetLang)
            .putString(KEY_MODE, settings.displayMode.name)
            .putString(KEY_THEME, settings.themeMode.name)
            .putString(KEY_TR_MODE, settings.translationMode.name)
            .putString(KEY_TR_LEVEL, settings.keywordLevel.name)
            .putBoolean(KEY_AUTO_EXTEND, settings.autoExtend)
            .apply()
    }

    companion object {
        private const val KEY_BASE = "baseUrl"
        private const val KEY_KEY = "apiKey"
        private const val KEY_MODEL = "model"
        private const val KEY_LANG = "targetLang"
        private const val KEY_MODE = "displayMode"
        private const val KEY_THEME = "themeMode"
        private const val KEY_TR_MODE = "translationMode"
        private const val KEY_TR_LEVEL = "keywordLevel"
        private const val KEY_AUTO_EXTEND = "autoExtend"
    }
}
