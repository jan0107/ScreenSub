// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.core.mt

/**
 * LLM 服务商预设。
 *
 * ## 预设只是"一键填入"，不是权威
 *
 * 各家模型名和地址变动很频繁（DeepSeek 就从 deepseek-chat 换成了
 * deepseek-flash / deepseek-v4-pro）。所以：
 *   - 预设只负责把地址和模型名填进输入框
 *   - 输入框始终可编辑，用户可以自己改
 *   - 真正生效的永远是**输入框里的值**，不是这里的常量
 *
 * 这样预设过期了也不会挡住用户，最多是初始值不对，改一下就行。
 */
object LlmPresets {

    data class Preset(
        val id: String,
        val label: String,
        val baseUrl: String,
        val model: String,
        val note: String,
        /**
         * 去哪拿 API Key。
         *
         * 新用户最先卡住的是"这个 Key 从哪来"，不是"填在哪个框"。
         * 每家都是独立站点，只能一个个维护；空串表示不提供入口（如自定义网关）。
         */
        val keyUrl: String = "",
        /**
         * 该服务商是否有**公开的余额查询接口**。
         *
         * 目前只有 DeepSeek / Kimi(Moonshot) / 硅基流动 三家有；
         * 通义千问、智谱 GLM、OpenAI 都没有可用的余额 API
         * （OpenAI 的 /v1/organization/costs 要组织管理员 Key，中转站更没有）。
         * 没有接口的只能按 token 估算花费，UI 上要如实说明，不能假装能查。
         */
        val hasBalanceApi: Boolean = false,
    )

    const val CUSTOM = "custom"

    val ALL = listOf(
        Preset(
            id = "deepseek",
            label = "DeepSeek",
            baseUrl = "https://api.deepseek.com",
            model = "deepseek-flash",
            note = "中文最好。也有 deepseek-v4-pro，模型名可直接改",
            keyUrl = "https://platform.deepseek.com/api_keys",
            hasBalanceApi = true,
        ),
        Preset(
            id = "qwen",
            label = "通义千问",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            model = "qwen-plus",
            note = "国内直连稳定，有新用户免费额度 · 无余额查询接口",
            keyUrl = "https://bailian.console.aliyun.com/",
        ),
        Preset(
            id = "moonshot",
            label = "Kimi",
            baseUrl = "https://api.moonshot.cn/v1",
            model = "moonshot-v1-8k",
            note = "月之暗面，长上下文 · 支持余额查询",
            keyUrl = "https://platform.moonshot.cn/console/api-keys",
            hasBalanceApi = true,
        ),
        Preset(
            id = "glm",
            label = "智谱 GLM",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            model = "glm-4-flash",
            note = "glm-4-flash 有免费额度 · 无余额查询接口",
            keyUrl = "https://open.bigmodel.cn/usercenter/apikeys",
        ),
        Preset(
            id = "siliconflow",
            label = "硅基流动",
            baseUrl = "https://api.siliconflow.cn/v1",
            model = "Qwen/Qwen2.5-7B-Instruct",
            note = "聚合多家开源模型 · 支持余额查询",
            keyUrl = "https://cloud.siliconflow.cn/account/ak",
            hasBalanceApi = true,
        ),
        Preset(
            id = "openai",
            label = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            model = "gpt-4o-mini",
            note = "质量好，但需要网络能通 · 无余额查询接口",
            keyUrl = "https://platform.openai.com/api-keys",
        ),
        Preset(
            id = CUSTOM,
            label = "自定义",
            baseUrl = "",
            model = "",
            note = "Ollama、vLLM 或公司内网网关，自己填地址和模型名",
        ),
    )

    fun of(id: String): Preset = ALL.firstOrNull { it.id == id } ?: ALL.first()

    val DEFAULT: String = ALL.first().id
}
