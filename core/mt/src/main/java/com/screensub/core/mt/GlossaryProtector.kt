// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.core.mt

/**
 * 术语保护器。
 *
 * 为什么要占位符而不是"翻译后再替换"：
 * 翻译引擎会把专有名词翻成任意五花八门的写法（"ルフィ" → "路飞"/"鲁夫"/"卢菲"），
 * 事后替换根本匹配不上。所以必须先把它从文本里摘出来，换成引擎看不懂的占位符，
 * 等翻译完再放回去。这样命中率是 100%，且不依赖任何引擎的能力。
 */
class GlossaryProtector(terms: List<GlossaryTerm>) {

    data class GlossaryTerm(
        /** 原文，例如 "ルフィ" 或 "Luffy" */
        val source: String,
        /** 目标译名，例如 "路飞" */
        val target: String,
        /** 越大越优先匹配（长词应设更高，避免被短词吃掉） */
        val priority: Int = 0,
        /** true 时 source 按正则处理（高级用法） */
        val isRegex: Boolean = false,
    )

    /** 按优先级降序 + 长度降序，保证长词先匹配 */
    private val sortedTerms: List<GlossaryTerm> = terms
        .filter { it.source.isNotBlank() && it.target.isNotBlank() }
        .sortedWith(
            compareByDescending<GlossaryTerm> { it.priority }
                .thenByDescending { it.source.length },
        )

    data class Protected(
        val text: String,
        /** 占位符 → 目标译名 */
        val tokens: Map<String, String>,
    ) {
        val isEmpty: Boolean get() = tokens.isEmpty()
    }

    fun protect(text: String): Protected {
        if (sortedTerms.isEmpty() || text.isBlank()) {
            return Protected(text, emptyMap())
        }

        val tokens = LinkedHashMap<String, String>()
        var working = text
        var index = 0

        for (term in sortedTerms) {
            val placeholder = buildPlaceholder(index)
            val replaced = if (term.isRegex) {
                replaceRegex(working, term.source, placeholder)
            } else {
                replaceLiteral(working, term.source, placeholder)
            }
            if (replaced != working) {
                tokens[placeholder] = term.target
                working = replaced
                index++
            }
        }

        return Protected(working, tokens)
    }

    fun restore(translated: String, tokens: Map<String, String>): String {
        if (tokens.isEmpty()) return translated

        var result = translated
        for ((placeholder, target) in tokens) {
            // 容错：部分引擎会在占位符里塞空格，或把 ⟦⟧ 改成 [] ()( )
            result = restoreOne(result, placeholder, target)
        }
        return result
    }

    private fun restoreOne(text: String, placeholder: String, target: String): String {
        val num = placeholder.filter { it.isDigit() }
        // 宽松匹配 ⟦T3⟧ / ⟦ T3 ⟧ / [T3] / (T3)
        val pattern = Regex("""[⟦\[（(]\s*T?\s*$num\s*[⟧\]）)]""")
        return pattern.replace(text, target)
    }

    private fun buildPlaceholder(index: Int): String = "⟦T$index⟧"

    private fun replaceLiteral(text: String, needle: String, placeholder: String): String =
        text.replace(needle, placeholder, ignoreCase = false)

    private fun replaceRegex(text: String, pattern: String, placeholder: String): String =
        try {
            Regex(pattern, RegexOption.IGNORE_CASE).replace(text, placeholder)
        } catch (_: Throwable) {
            text
        }

    companion object {
        val EMPTY = GlossaryProtector(emptyList())
    }
}
