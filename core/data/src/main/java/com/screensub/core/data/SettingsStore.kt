// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "screensub_settings",
)

/** 设置读写。所有 key 集中在这里，避免拼写漂移。 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val audioSource = stringPreferencesKey("audio_source")
        val autoFallbackToMic = booleanPreferencesKey("auto_fallback_mic")
        val micGain = floatPreferencesKey("mic_gain")

        val sourceLanguage = stringPreferencesKey("source_language")
        val modelId = stringPreferencesKey("model_id")

        val preferOnlineTranslation = booleanPreferencesKey("prefer_online_mt")
        val offlineTranslationEnabled = booleanPreferencesKey("offline_mt_enabled")
        val googleTranslationEnabled = booleanPreferencesKey("google_mt_enabled")
        val myMemoryTranslationEnabled = booleanPreferencesKey("mymemory_mt_enabled")
        val llmProvider = stringPreferencesKey("llm_provider")
        val llmTranslationEnabled = booleanPreferencesKey("llm_mt_enabled")
        val azureTranslationEnabled = booleanPreferencesKey("azure_mt_enabled")
        val aliyunTranslationEnabled = booleanPreferencesKey("aliyun_mt_enabled")
        val volcengineTranslationEnabled = booleanPreferencesKey("volcengine_mt_enabled")
        val aliyunAccessKeyId = stringPreferencesKey("aliyun_access_key_id")
        val aliyunAccessKeySecret = stringPreferencesKey("aliyun_access_key_secret")
        val volcengineAccessKeyId = stringPreferencesKey("volcengine_access_key_id")
        val volcengineSecretAccessKey = stringPreferencesKey("volcengine_secret_access_key")
        val targetLanguage = stringPreferencesKey("target_language")
        val llmBaseUrl = stringPreferencesKey("llm_base_url")
        val llmApiKey = stringPreferencesKey("llm_api_key")
        val llmModel = stringPreferencesKey("llm_model")
        val azureKey = stringPreferencesKey("azure_key")
        val azureRegion = stringPreferencesKey("azure_region")

        /** 额度监测配置（QuotaConfig 的 JSON），见 QuotaConfig.kt 的说明 */
        val quotaConfigJson = stringPreferencesKey("quota_config_json")

        val fontSize = floatPreferencesKey("subtitle_font_size")
        val textColor = intPreferencesKey("subtitle_text_color")
        val showSource = booleanPreferencesKey("subtitle_show_source")
        val backgroundAlpha = floatPreferencesKey("subtitle_bg_alpha")
        val maxLines = intPreferencesKey("subtitle_max_lines")
        val bottomMargin = intPreferencesKey("subtitle_bottom_margin")
        val draggable = booleanPreferencesKey("subtitle_draggable")

        val useHfMirror = booleanPreferencesKey("use_hf_mirror")
        val recordHistory = booleanPreferencesKey("record_history")
        val verboseLog = booleanPreferencesKey("verbose_log")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        val defaults = AppSettings.DEFAULT
        AppSettings(
            audioSource = prefs[Keys.audioSource] ?: defaults.audioSource,
            autoFallbackToMic = prefs[Keys.autoFallbackToMic] ?: defaults.autoFallbackToMic,
            micGain = prefs[Keys.micGain] ?: defaults.micGain,
            sourceLanguage = prefs[Keys.sourceLanguage] ?: defaults.sourceLanguage,
            modelId = prefs[Keys.modelId] ?: defaults.modelId,
            preferOnlineTranslation = prefs[Keys.preferOnlineTranslation]
                ?: defaults.preferOnlineTranslation,
            offlineTranslationEnabled = prefs[Keys.offlineTranslationEnabled]
                ?: defaults.offlineTranslationEnabled,
            googleTranslationEnabled = prefs[Keys.googleTranslationEnabled]
                ?: defaults.googleTranslationEnabled,
            myMemoryTranslationEnabled = prefs[Keys.myMemoryTranslationEnabled]
                ?: defaults.myMemoryTranslationEnabled,
            llmProvider = prefs[Keys.llmProvider] ?: defaults.llmProvider,
            llmTranslationEnabled = prefs[Keys.llmTranslationEnabled]
                ?: defaults.llmTranslationEnabled,
            azureTranslationEnabled = prefs[Keys.azureTranslationEnabled]
                ?: defaults.azureTranslationEnabled,
            aliyunTranslationEnabled = prefs[Keys.aliyunTranslationEnabled]
                ?: defaults.aliyunTranslationEnabled,
            volcengineTranslationEnabled = prefs[Keys.volcengineTranslationEnabled]
                ?: defaults.volcengineTranslationEnabled,
            aliyunAccessKeyId = prefs[Keys.aliyunAccessKeyId] ?: defaults.aliyunAccessKeyId,
            aliyunAccessKeySecret = prefs[Keys.aliyunAccessKeySecret]
                ?: defaults.aliyunAccessKeySecret,
            volcengineAccessKeyId = prefs[Keys.volcengineAccessKeyId]
                ?: defaults.volcengineAccessKeyId,
            volcengineSecretAccessKey = prefs[Keys.volcengineSecretAccessKey]
                ?: defaults.volcengineSecretAccessKey,
            targetLanguage = prefs[Keys.targetLanguage] ?: defaults.targetLanguage,
            llmBaseUrl = prefs[Keys.llmBaseUrl] ?: defaults.llmBaseUrl,
            llmApiKey = prefs[Keys.llmApiKey] ?: defaults.llmApiKey,
            llmModel = prefs[Keys.llmModel] ?: defaults.llmModel,
            azureKey = prefs[Keys.azureKey] ?: defaults.azureKey,
            azureRegion = prefs[Keys.azureRegion] ?: defaults.azureRegion,
            quotaConfigJson = prefs[Keys.quotaConfigJson] ?: defaults.quotaConfigJson,
            subtitleFontSizeSp = prefs[Keys.fontSize] ?: defaults.subtitleFontSizeSp,
            subtitleTextColor = prefs[Keys.textColor] ?: defaults.subtitleTextColor,
            subtitleShowSource = prefs[Keys.showSource] ?: defaults.subtitleShowSource,
            subtitleBackgroundAlpha = prefs[Keys.backgroundAlpha]
                ?: defaults.subtitleBackgroundAlpha,
            subtitleMaxLines = prefs[Keys.maxLines] ?: defaults.subtitleMaxLines,
            subtitleBottomMarginDp = prefs[Keys.bottomMargin] ?: defaults.subtitleBottomMarginDp,
            subtitleDraggable = prefs[Keys.draggable] ?: defaults.subtitleDraggable,
            useHfMirror = prefs[Keys.useHfMirror] ?: defaults.useHfMirror,
            recordHistory = prefs[Keys.recordHistory] ?: defaults.recordHistory,
            verboseLog = prefs[Keys.verboseLog] ?: defaults.verboseLog,
        )
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsDataStore.edit { prefs ->
            val current = AppSettings(
                audioSource = prefs[Keys.audioSource] ?: AppSettings.DEFAULT.audioSource,
                autoFallbackToMic = prefs[Keys.autoFallbackToMic]
                    ?: AppSettings.DEFAULT.autoFallbackToMic,
                micGain = prefs[Keys.micGain] ?: AppSettings.DEFAULT.micGain,
                sourceLanguage = prefs[Keys.sourceLanguage] ?: AppSettings.DEFAULT.sourceLanguage,
                modelId = prefs[Keys.modelId] ?: AppSettings.DEFAULT.modelId,
                preferOnlineTranslation = prefs[Keys.preferOnlineTranslation]
                    ?: AppSettings.DEFAULT.preferOnlineTranslation,
                offlineTranslationEnabled = prefs[Keys.offlineTranslationEnabled]
                    ?: AppSettings.DEFAULT.offlineTranslationEnabled,
                googleTranslationEnabled = prefs[Keys.googleTranslationEnabled]
                    ?: AppSettings.DEFAULT.googleTranslationEnabled,
                myMemoryTranslationEnabled = prefs[Keys.myMemoryTranslationEnabled]
                    ?: AppSettings.DEFAULT.myMemoryTranslationEnabled,
                llmProvider = prefs[Keys.llmProvider] ?: AppSettings.DEFAULT.llmProvider,
                llmTranslationEnabled = prefs[Keys.llmTranslationEnabled]
                    ?: AppSettings.DEFAULT.llmTranslationEnabled,
                azureTranslationEnabled = prefs[Keys.azureTranslationEnabled]
                    ?: AppSettings.DEFAULT.azureTranslationEnabled,
                aliyunTranslationEnabled = prefs[Keys.aliyunTranslationEnabled]
                    ?: AppSettings.DEFAULT.aliyunTranslationEnabled,
                volcengineTranslationEnabled = prefs[Keys.volcengineTranslationEnabled]
                    ?: AppSettings.DEFAULT.volcengineTranslationEnabled,
                aliyunAccessKeyId = prefs[Keys.aliyunAccessKeyId]
                    ?: AppSettings.DEFAULT.aliyunAccessKeyId,
                aliyunAccessKeySecret = prefs[Keys.aliyunAccessKeySecret]
                    ?: AppSettings.DEFAULT.aliyunAccessKeySecret,
                volcengineAccessKeyId = prefs[Keys.volcengineAccessKeyId]
                    ?: AppSettings.DEFAULT.volcengineAccessKeyId,
                volcengineSecretAccessKey = prefs[Keys.volcengineSecretAccessKey]
                    ?: AppSettings.DEFAULT.volcengineSecretAccessKey,
                targetLanguage = prefs[Keys.targetLanguage]
                    ?: AppSettings.DEFAULT.targetLanguage,
                llmBaseUrl = prefs[Keys.llmBaseUrl] ?: AppSettings.DEFAULT.llmBaseUrl,
                llmApiKey = prefs[Keys.llmApiKey] ?: AppSettings.DEFAULT.llmApiKey,
                llmModel = prefs[Keys.llmModel] ?: AppSettings.DEFAULT.llmModel,
                azureKey = prefs[Keys.azureKey] ?: AppSettings.DEFAULT.azureKey,
                azureRegion = prefs[Keys.azureRegion] ?: AppSettings.DEFAULT.azureRegion,
                quotaConfigJson = prefs[Keys.quotaConfigJson]
                    ?: AppSettings.DEFAULT.quotaConfigJson,
                subtitleFontSizeSp = prefs[Keys.fontSize]
                    ?: AppSettings.DEFAULT.subtitleFontSizeSp,
                subtitleTextColor = prefs[Keys.textColor]
                    ?: AppSettings.DEFAULT.subtitleTextColor,
                subtitleShowSource = prefs[Keys.showSource]
                    ?: AppSettings.DEFAULT.subtitleShowSource,
                subtitleBackgroundAlpha = prefs[Keys.backgroundAlpha]
                    ?: AppSettings.DEFAULT.subtitleBackgroundAlpha,
                subtitleMaxLines = prefs[Keys.maxLines] ?: AppSettings.DEFAULT.subtitleMaxLines,
                subtitleBottomMarginDp = prefs[Keys.bottomMargin]
                    ?: AppSettings.DEFAULT.subtitleBottomMarginDp,
                subtitleDraggable = prefs[Keys.draggable]
                    ?: AppSettings.DEFAULT.subtitleDraggable,
                useHfMirror = prefs[Keys.useHfMirror] ?: AppSettings.DEFAULT.useHfMirror,
                recordHistory = prefs[Keys.recordHistory]
                    ?: AppSettings.DEFAULT.recordHistory,
                verboseLog = prefs[Keys.verboseLog] ?: AppSettings.DEFAULT.verboseLog,
            )
            val next = transform(current)

            prefs[Keys.audioSource] = next.audioSource
            prefs[Keys.autoFallbackToMic] = next.autoFallbackToMic
            prefs[Keys.micGain] = next.micGain
            prefs[Keys.sourceLanguage] = next.sourceLanguage
            prefs[Keys.modelId] = next.modelId
            prefs[Keys.preferOnlineTranslation] = next.preferOnlineTranslation
            prefs[Keys.offlineTranslationEnabled] = next.offlineTranslationEnabled
            prefs[Keys.googleTranslationEnabled] = next.googleTranslationEnabled
            prefs[Keys.myMemoryTranslationEnabled] = next.myMemoryTranslationEnabled
            prefs[Keys.llmProvider] = next.llmProvider
            prefs[Keys.llmTranslationEnabled] = next.llmTranslationEnabled
            prefs[Keys.azureTranslationEnabled] = next.azureTranslationEnabled
            prefs[Keys.aliyunTranslationEnabled] = next.aliyunTranslationEnabled
            prefs[Keys.volcengineTranslationEnabled] = next.volcengineTranslationEnabled
            prefs[Keys.aliyunAccessKeyId] = next.aliyunAccessKeyId
            prefs[Keys.aliyunAccessKeySecret] = next.aliyunAccessKeySecret
            prefs[Keys.volcengineAccessKeyId] = next.volcengineAccessKeyId
            prefs[Keys.volcengineSecretAccessKey] = next.volcengineSecretAccessKey
            prefs[Keys.targetLanguage] = next.targetLanguage
            prefs[Keys.llmBaseUrl] = next.llmBaseUrl
            prefs[Keys.llmApiKey] = next.llmApiKey
            prefs[Keys.llmModel] = next.llmModel
            prefs[Keys.azureKey] = next.azureKey
            prefs[Keys.azureRegion] = next.azureRegion
            prefs[Keys.quotaConfigJson] = next.quotaConfigJson
            prefs[Keys.fontSize] = next.subtitleFontSizeSp
            prefs[Keys.textColor] = next.subtitleTextColor
            prefs[Keys.showSource] = next.subtitleShowSource
            prefs[Keys.backgroundAlpha] = next.subtitleBackgroundAlpha
            prefs[Keys.maxLines] = next.subtitleMaxLines
            prefs[Keys.bottomMargin] = next.subtitleBottomMarginDp
            prefs[Keys.draggable] = next.subtitleDraggable
            prefs[Keys.useHfMirror] = next.useHfMirror
            prefs[Keys.recordHistory] = next.recordHistory
            prefs[Keys.verboseLog] = next.verboseLog
        }
    }
}
