// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.app

import android.content.Context
import androidx.room.Room
import com.screensub.core.asr.AsrEngine
import com.screensub.core.asr.ModelCatalog
import com.screensub.core.asr.SherpaAsrEngine
import com.screensub.core.asr.SourceLanguage
import com.screensub.core.asr.VadSegmenter
import com.screensub.core.common.AppDispatchers
import com.screensub.core.common.Log
import com.screensub.core.common.LogTags
import com.screensub.core.common.AppResult
import com.screensub.core.common.TranslatorIds
import com.screensub.core.data.billing.CloudBillingProbe
import kotlinx.coroutines.delay
import com.screensub.core.data.AppSettings
import com.screensub.core.data.ModelDownloader
import com.screensub.core.data.ProbePolicy
import com.screensub.core.data.QuotaRepository
import com.screensub.core.data.SettingsStore
import com.screensub.core.data.db.ScreenSubDatabase
import com.screensub.core.data.db.GlossaryEntity
import com.screensub.core.mt.AliyunTranslator
import com.screensub.core.mt.AzureTranslator
import com.screensub.core.mt.GlossaryProtector
import com.screensub.core.mt.LlmBalanceProbes
import com.screensub.core.mt.LlmPresets
import com.screensub.core.mt.LlmTranslator
import com.screensub.core.mt.QuotaGate
import com.screensub.core.mt.ResilientTranslator
import com.screensub.core.mt.TranslationSettings
import com.screensub.core.mt.TranslatorFactory
import com.screensub.core.mt.VolcengineTranslator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 手动依赖容器。
 *
 * 不用 Hilt：这个工程规模没必要引入注解处理器（还会拖慢增量编译）。
 * 全局单例由 Application 持有，Service 与 Activity 都从 `(application as ScreenSubApp).container` 拿。
 */
class AppContainer(private val context: Context) {

    companion object {
        private const val TAG = LogTags.DATA
    }

    private val appContext = context.applicationContext

    val settingsStore = SettingsStore(appContext)

    /** 默认用小镜像，首次用到才真正建库 */
    val database: ScreenSubDatabase by lazy {
        Room.databaseBuilder(appContext, ScreenSubDatabase::class.java, ScreenSubDatabase.NAME)
            // 保留给"没有对应 Migration 的未来版本"兜底；
            // 只要版本能配上 Migration 就不会真的清库（v1→v2 已显式提供）
            .fallbackToDestructiveMigration()
            .addMigrations(ScreenSubDatabase.MIGRATION_1_2)
            .build()
    }

    private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.io)

    /**
     * 用量统计与额度判定。
     *
     * 注意它是 `by lazy` 的：构造时要访问 database（读历史用量），
     * 而 database 本身也是 lazy，链式触发即可，不要在这里立刻求值。
     */
    val quotaRepository: QuotaRepository by lazy {
        QuotaRepository(database.usageDao(), settingsStore, scope)
    }

    /** 常驻内存的最新设置，避免每次都要 suspend 读 */
    private val _settings = MutableStateFlow(AppSettings.DEFAULT)
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /**
     * 待展示的额度提示弹窗。
     *
     * 为什么放内存而不是持久化：用户是在看视频时收到提示的，
     * 切回 App 就能看到——此时前台服务还在，进程活着。
     * 真被杀掉进程的情况下，弹窗丢了也无所谓（额度信息在设置页里一直可见）。
     */
    private val _quotaNotice = MutableStateFlow<QuotaNotice?>(null)
    val quotaNotice: StateFlow<QuotaNotice?> = _quotaNotice.asStateFlow()

    fun raiseQuotaNotice(notice: QuotaNotice) {
        _quotaNotice.value = notice
    }

    fun dismissQuotaNotice() {
        _quotaNotice.value = null
    }

    init {
        scope.launch {
            settingsStore.settings.collect { newSettings ->
                _settings.value = newSettings
                Log.enabled = BuildConfig.DEBUG || newSettings.verboseLog
                // 用量面板要按"填了几家显示几家"渲染，所以把集合推给仓库
                quotaRepository.updateConfigured(
                    configured = newSettings.configuredPaidEngineIds(),
                    enabled = newSettings.enabledPaidEngineIds(),
                )
            }
        }
        // 平台用量：启动拉一次，之后每 6 小时一次。
        // 账单/报表都是 T+1，查太勤没有意义；打开用量页时会额外拉一次。
        scope.launch {
            // 等设置从 DataStore 加载出来，否则第一次会拿到全空的 Key
            delay(2_000L)
            while (true) {
                runCatching { refreshCloudUsage() }
                delay(6 * 60 * 60 * 1_000L)
            }
        }
    }

    /**
     * 从平台拉**真实**用量（账号级，含其他设备/其他程序）。
     *
     * 两家都能拿到按天字符数：阿里云走 `GetTranslateReport`，
     * 火山走 `ListBillDetail` 的 `Count × Unit`。都是 T+1，
     * 且火山的账单行要次日下午才落库（实测 10-27 的行 `TradeTime` 是 10-28 15:09），
     * 所以这里 6 小时一轮 —— 只要有一天在 15 点之后刷新过，昨天数就能进来。
     * 整条链路是尽力而为：失败只记日志，不影响翻译。
     */
    fun refreshCloudUsage() {
        val s = currentSettings()
        if (s.aliyunAccessKeyId.isNotBlank() && s.aliyunAccessKeySecret.isNotBlank()) {
            scope.launch {
                quotaRepository.refreshCloud(
                    TranslatorIds.ALIYUN,
                    CloudBillingProbe.Credentials(
                        accessKeyId = s.aliyunAccessKeyId,
                        secretAccessKey = s.aliyunAccessKeySecret,
                    ),
                )
            }
        }
        if (s.volcengineAccessKeyId.isNotBlank() && s.volcengineSecretAccessKey.isNotBlank()) {
            scope.launch {
                quotaRepository.refreshCloud(
                    TranslatorIds.VOLCENGINE,
                    CloudBillingProbe.Credentials(
                        accessKeyId = s.volcengineAccessKeyId,
                        secretAccessKey = s.volcengineSecretAccessKey,
                    ),
                )
            }
        }

        // LLM 账户余额：只有 DeepSeek / Kimi / 硅基流动 有接口。
        // 余额 ≤ 0 时 QuotaRepository 会强制停用该引擎——调用注定失败，不必每句去撞网络。
        if (s.llmApiKey.isNotBlank() && LlmBalanceProbes.supports(s.llmProvider)) {
            val baseUrl = s.llmBaseUrl.ifBlank {
                LlmPresets.ALL.firstOrNull { it.id == s.llmProvider }?.baseUrl ?: ""
            }
            if (baseUrl.isNotBlank()) {
                scope.launch {
                    val cfg = LlmTranslator.LlmConfig(
                        baseUrl = baseUrl,
                        apiKey = s.llmApiKey,
                        model = s.llmModel,
                    )
                    when (val r = LlmBalanceProbes.query(s.llmProvider, cfg)) {
                        is AppResult.Ok -> quotaRepository.applyPlatformBalance(
                            TranslatorIds.LLM, r.value.yuan,
                        )

                        is AppResult.Err -> Log.w(TAG, "LLM 余额查询失败：${r.error.message}")
                    }
                }
            }
        }
    }

    fun currentSettings(): AppSettings = _settings.value

    fun modelDownloader(): ModelDownloader =
        ModelDownloader(appContext, _settings.value.useHfMirror)

    // ---------------- 引擎装配 ----------------

    /**
     * 装配 ASR 引擎。模型未下载时返回 null，UI 应引导去下载。
     */
    fun buildAsrEngine(settings: AppSettings): AsrEngine? {
        val spec = ModelCatalog.findById(settings.modelId) ?: ModelCatalog.SENSE_VOICE
        val downloader = ModelDownloader(appContext, settings.useHfMirror)

        if (!downloader.isModelReady(spec)) {
            Log.w(TAG, "模型 ${spec.id} 未下载完成，无法启动识别")
            return null
        }

        val language = SourceLanguage.fromCode(settings.sourceLanguage)
        return SherpaAsrEngine(
            modelSpec = spec,
            modelDir = downloader.modelDir(spec),
            language = language,
        )
    }

    /** 装配 VAD。模型缺失时返回 null。 */
    fun buildVad(): VadSegmenter? {
        val vadFile = File(appContext.filesDir, "models/silero_vad.onnx")
        if (!vadFile.exists()) {
            Log.w(TAG, "Silero VAD 模型缺失")
            return null
        }
        return try {
            VadSegmenter(vadFile.absolutePath)
        } catch (t: Throwable) {
            Log.e(TAG, "VAD 初始化失败", t)
            null
        }
    }

    /** 装配翻译链（含术语表与熔断） */
    /**
     * 把 AppSettings 转成翻译层要的设置对象。
     *
     * 抽出来是为了让「测试翻译」能拿到**未包装的引擎列表**——
     * ResilientTranslator 只暴露结果不暴露每个引擎的耗时，
     * 而逐引擎测延迟必须直接持有 Translator 实例。
     */
    fun buildTranslationSettings(settings: AppSettings): TranslationSettings = TranslationSettings(
            sourceCode = settings.sourceLanguage,
            targetCode = settings.targetLanguage,
            preferOnline = settings.preferOnlineTranslation,
            offlineEnabled = settings.offlineTranslationEnabled,
            googleFreeEnabled = settings.googleTranslationEnabled,
            myMemoryEnabled = settings.myMemoryTranslationEnabled,
            llmConfig = if (settings.llmTranslationEnabled) {
                // 直接读用户填的值，不再从预设推导——
                // 预设只是"一键填入"，各家模型名变得太快，推导会把用户改过的值覆盖掉
                if (settings.llmBaseUrl.isNotBlank() &&
                    settings.llmApiKey.isNotBlank() &&
                    settings.llmModel.isNotBlank()
                ) {
                    LlmTranslator.LlmConfig(
                        baseUrl = settings.llmBaseUrl,
                        apiKey = settings.llmApiKey,
                        model = settings.llmModel,
                    )
                } else {
                    null
                }
            } else {
                null
            },
            aliyunConfig = if (settings.aliyunTranslationEnabled &&
                settings.aliyunAccessKeyId.isNotBlank() &&
                settings.aliyunAccessKeySecret.isNotBlank()
            ) {
                AliyunTranslator.AliyunConfig(
                    accessKeyId = settings.aliyunAccessKeyId,
                    accessKeySecret = settings.aliyunAccessKeySecret,
                )
            } else {
                null
            },
            volcengineConfig = if (settings.volcengineTranslationEnabled &&
                settings.volcengineAccessKeyId.isNotBlank() &&
                settings.volcengineSecretAccessKey.isNotBlank()
            ) {
                VolcengineTranslator.VolcengineConfig(
                    accessKeyId = settings.volcengineAccessKeyId,
                    secretAccessKey = settings.volcengineSecretAccessKey,
                )
            } else {
                null
            },
            azureConfig = if (settings.azureTranslationEnabled && settings.azureKey.isNotBlank()) {
                AzureTranslator.AzureConfig(
                    subscriptionKey = settings.azureKey,
                    region = settings.azureRegion,
                )
            } else {
                null
            },
    )

    fun buildTranslator(
        settings: AppSettings,
        glossary: List<GlossaryProtector.GlossaryTerm>,
    ): ResilientTranslator {
        // 额度门禁：两个全局开关任一关闭都直接放行（不跳过任何引擎）
        val gate = QuotaGate { engineId ->
            val quota = quotaRepository.currentConfig()
            if (!quota.switchEffective) {
                false
            } else {
                quotaRepository.isBlocked(engineId)
            }
        }

        return TranslatorFactory.build(
            settings = buildTranslationSettings(settings),
            glossaryTerms = glossary,
            usageSink = { event ->
                quotaRepository.record(
                    engineId = event.engineId,
                    chars = event.chars,
                    tokensIn = event.tokensIn,
                    tokensOut = event.tokensOut,
                    ok = event.ok,
                )
            },
            quotaGate = gate,
            probeAllowed = { engineId -> canProbe(engineId) },
        )
    }

    /**
     * 探测安全门：先看额度够不够，再决定要不要花那 2 个字符。
     *
     * 监测关掉时**不允许探测** —— 用户已经明确说"别管用量"，
     * 再偷偷花额度就自相矛盾了。
     */
    private fun canProbe(engineId: String): Boolean {
        val cfg = quotaRepository.currentConfig()
        if (!cfg.quotaMonitorEnabled) return false

        val usage = quotaRepository.snapshot().engine(engineId) ?: return false
        val quota = cfg.engine(engineId)
        val decision = ProbePolicy.decide(
            quotaChars = quota.monthlyQuotaChars,
            usedChars = usage.monthChars,
            allowNearLimit = quota.allowProbeNearLimit,
            probeMaxPercent = cfg.probeMaxPercent,
        )
        if (!decision.allowed) {
            Log.i(TAG, "$engineId 跳过探测：${decision.reason}（剩余 ${quota.monthlyQuotaChars - usage.monthChars} 字符）")
        }
        return decision.allowed
    }

    /** 从数据库读术语表并转成保护器条目 */
    suspend fun loadGlossary(): List<GlossaryProtector.GlossaryTerm> =
        try {
            database.glossaryDao().all().map(::toGlossaryTerm)
        } catch (t: Throwable) {
            Log.w(TAG, "读取术语表失败", t)
            emptyList()
        }

    private fun toGlossaryTerm(entity: GlossaryEntity) =
        GlossaryProtector.GlossaryTerm(
            source = entity.source,
            target = entity.target,
            priority = entity.priority,
            isRegex = entity.isRegex,
        )
}

/**
 * 进 App 后要弹的那条额度提示。
 *
 * [canEnableFreeFallback] 只在"一个免费引擎都没开、确实没退路"时为 true——
 * 有退路的时候不该建议用户去开免费引擎，那会显得系统在推销。
 */
data class QuotaNotice(
    val title: String,
    val body: String,
    val canEnableFreeFallback: Boolean,
)

/**
 * 真正配了 Key 的付费引擎。
 *
 * 只看凭据填没填，**不看开关**：关掉的引擎也应该显示用量，
 * 否则用户不知道自己关掉的那家到底用了多少。
 *
 * 与 `AppContainer.buildTranslationSettings` 的判定保持一致——
 * 那边用 `config.isUsable`（Key 非空）决定引擎进不进链，
 * 没进链的引擎不会有任何用量上报，所以面板里自然也不会出现。
 */
fun AppSettings.configuredPaidEngineIds(): Set<String> = buildSet {
    if (llmBaseUrl.isNotBlank() && llmApiKey.isNotBlank() && llmModel.isNotBlank()) {
        add(TranslatorIds.LLM)
    }
    if (azureKey.isNotBlank()) add(TranslatorIds.AZURE)
    if (aliyunAccessKeyId.isNotBlank() && aliyunAccessKeySecret.isNotBlank()) {
        add(TranslatorIds.ALIYUN)
    }
    if (volcengineAccessKeyId.isNotBlank() && volcengineSecretAccessKey.isNotBlank()) {
        add(TranslatorIds.VOLCENGINE)
    }
}

/** 配了 Key 且开关打开的付费引擎（"正在使用"的候选集） */fun AppSettings.enabledPaidEngineIds(): Set<String> = buildSet {
    if (llmTranslationEnabled && TranslatorIds.LLM in configuredPaidEngineIds()) {
        add(TranslatorIds.LLM)
    }
    if (azureTranslationEnabled && azureKey.isNotBlank()) add(TranslatorIds.AZURE)
    if (aliyunTranslationEnabled &&
        aliyunAccessKeyId.isNotBlank() && aliyunAccessKeySecret.isNotBlank()
    ) {
        add(TranslatorIds.ALIYUN)
    }
    if (volcengineTranslationEnabled &&
        volcengineAccessKeyId.isNotBlank() && volcengineSecretAccessKey.isNotBlank()
    ) {
        add(TranslatorIds.VOLCENGINE)
    }
}
