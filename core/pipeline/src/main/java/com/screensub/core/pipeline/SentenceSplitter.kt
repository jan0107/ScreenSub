// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.core.pipeline

/**
 * 把一段识别文本按**标点**切成句子。
 *
 * ## 为什么需要它
 *
 * VAD 是声学的：它只知道"有没有声音"，不知道句子在哪结束。连续说话时没有停顿，
 * 只能按最长时长硬切，于是经常出现「一句话被切成两条字幕」或者「两三句挤在一行」。
 *
 * 而 ASR 模型（X-ASR 等）输出的文本**自带标点**，句子边界本来就写在字面上。
 * 拿这个信息做切分，才是"切在真正的句子交界"，代价几乎为零。
 *
 * ## 语言无关
 *
 * 中英日韩俄混在一起也能切：`。！？…` 是 CJK 终止符，`.!?` 是拉丁终止符。
 * 不依赖空格（中日文没有词间空格）。
 *
 * ## 几个必须处理的例外
 *
 * - **小数点**：`3.14` 里的点不是句号
 * - **英文缩写**：`Mr.` `Dr.` `U.S.` `e.g.` 后面不该断
 * - **连续点**：`...` 省略号只在最后一个点断开
 * - **过短片段**：`Yes.` `Okay.` 这种 3~4 个字符的句子单独成条会闪得厉害，并入前一句
 */
object SentenceSplitter {

    /** 短于这个长度的片段并入前一句（避免 "Yes." "Okay." 单独占一屏） */
    private const val MIN_MERGE_CHARS = 12

    /**
     * 单条字幕的字数上限。
     *
     * 48 → 96 → **160**。每放宽一次，切换掉的都是"读半句"的问题：
     * 48 时一句话被切成 3 段，切点落在固定搭配中间（`subscribe` / `button`）；
     * 160 能容下参考译文里最长那句（155 字符）整句翻译。
     * 再长就交给显示层（4 行 + 自动缩字号），版面比语义好让步。
     */
    private const val MAX_PIECE_CHARS = 160

    /** 可以在这些符号处二次切分（逗号类，切完语义还站得住） */
    private const val SOFT_BREAKS = ",;，；、—:："

    // ---------------- 无标点语言（俄语 GigaAM 等） ----------------

    /**
     * 俄语 GigaAM 是**字符级 CTC**，词表只有 33 个字母 + 空格，
     * 它输出的文本**一个标点都没有**（`похитительница детей во что у вас в голове`）。
     *
     * 这类文本交给翻译引擎，模型只能自己猜句子在哪结束，实测会翻成
     * 「绑架儿童的人，你到底在想什么，然后我是他的母亲，这不知何故改变了这个案子」
     * —— 把三四句对白糊成一句。
     *
     * 所以标点缺失时退一步：按**连接词**和**词数**切，让每片短到翻译模型能自己断句。
     */
    private const val NOPUNCT_MIN_WORDS = 6
    private const val NOPUNCT_MAX_WORDS = 14

    /** 俄语里常见的"新分句起头"，在这些词前面切通常不会断掉语义 */
    private val CLAUSE_STARTERS = setOf(
        "и", "а", "но", "что", "чтобы", "потому", "поэтому", "если",
        "когда", "так", "потом", "же", "или", "хотя", "пока",
    )

    /** 中日文没有空格，无标点时只能按字数切（40 字约等于一句） */
    private const val NOPUNCT_CJK_CHARS = 40

    /** 常见英文缩写，后面跟的点不是句末 */
    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc",
        "inc", "ltd", "co", "dept", "eg", "ie", "no", "fig", "approx",
        "univ", "gov", "sen", "rep", "gen", "col", "capt", "lt", "sgt",
        "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec",
    )

    /** CJK 终止符：见到就断，不需要看后面 */
    private const val CJK_ENDERS = "。！？…．"

    /** 拉丁终止符：`.` 需要额外判断 */
    private const val LATIN_HARD_ENDERS = "!?"

    /**
     * 切分。
     *
     * @return 至少 1 条；没有标点时原样返回整段（此时切分毫无意义，不如不切）
     */
    fun split(raw: String): List<String> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()
        if (text.length <= MIN_MERGE_CHARS) return listOf(text)

        val pieces = ArrayList<String>(4)
        val buf = StringBuilder()

        for (i in text.indices) {
            val c = text[i]
            buf.append(c)

            val next = text.getOrNull(i + 1)
            val isBoundary = when {
                c in CJK_ENDERS -> true
                c in LATIN_HARD_ENDERS -> true
                c == '.' -> isSentenceDot(text, i, next)
                else -> false
            }

            if (isBoundary) {
                val piece = buf.toString().trim()
                if (piece.isNotEmpty()) pieces.add(piece)
                buf.setLength(0)
            }
        }

        val tail = buf.toString().trim()
        if (tail.isNotEmpty()) pieces.add(tail)

        // 标点切完之后，超长的句子再按逗号拆一次：一条字幕必须能完整显示
        val result = mergeShortPieces(pieces).flatMap { splitLongPiece(it) }

        // 整段**一个句末标点都没有** → 模型不产标点（俄语 GigaAM 就是这样），
        // 上面的切分等于什么都没做。改按连接词/词数切，否则翻译引擎会自己乱断句。
        return if (result.size <= 1 && !isComplete(text)) splitWithoutPunctuation(text) else result
    }

    /**
     * 这段文本是否以一个**完整的句子**结束（最后一个字符是句末标点）。
     *
     * 用途：判断这段音频是不是在句子中间被切断了。ASR 模型（X-ASR 等）
     * 输出的标点很可靠，"有没有句号"基本等价于"这句话说完了没有"。
     *
     * 没说完的尾巴不能单独上屏——否则就会出现「…订阅按钮和小」这种断尾译文。
     */
    fun isComplete(text: String): Boolean {
        val last = text.trimEnd().lastOrNull() ?: return false
        return last in CJK_ENDERS || last in LATIN_HARD_ENDERS || last == '.'
    }

    /** 拼接两段文本：中英之间补空格，中日韩之间不补 */
    fun join(a: String, b: String): String = joinSmart(a, b)

    /**
     * `.` 到底是不是句末。
     *
     * 三种情况不算：数字中间（3.14）、缩写后面（Mr.）、紧跟字母（说明是域名/缩写内部）。
     */
    private fun isSentenceDot(text: String, index: Int, next: Char?): Boolean {
        val prev = text.getOrNull(index - 1)

        // 3.14 / 1.5%
        if (prev != null && prev.isDigit() && next != null && next.isDigit()) return false

        // Mr. / Dr. / e.g.
        var j = index - 1
        val word = StringBuilder()
        while (j >= 0 && (text[j].isLetter() || text[j] == '.')) {
            if (text[j] != '.') word.append(text[j])
            j--
        }
        val token = word.reverse().toString().lowercase()
        if (token.isNotEmpty() && token in ABBREVIATIONS) return false

        // 后面紧跟字母或数字（没有空格）→ 不是句末，多半是 "U.S.A" 这类
        if (next != null && (next.isLetterOrDigit())) return false

        return true
    }

    /**
     * 超长句二次切分。
     *
     * 规则：优先在**最靠近中点**的逗号类符号处断开（保持两边语义平衡）；
     * 没有逗号就按词数对半拆；中文无逗号且无空格时无法再切，原样返回。
     *
     * 递归处理右半段，因为一句话可能出现三次以上的逗号。
     */
    private fun splitLongPiece(text: String): List<String> {
        if (text.length <= MAX_PIECE_CHARS) return listOf(text)

        val mid = text.length / 2
        var cut = -1
        for (i in text.indices) {
            if (text[i] !in SOFT_BREAKS) continue
            if (cut == -1 || kotlin.math.abs(i - mid) < kotlin.math.abs(cut - mid)) cut = i
        }

        if (cut > 0) {
            val left = text.substring(0, cut + 1).trim()
            val right = text.substring(cut + 1).trim()
            // 两边都得有像样的内容，否则这次切分只是把标点挪了个位置
            if (left.length >= MIN_MERGE_CHARS && right.length >= MIN_MERGE_CHARS) {
                return listOf(left) + splitLongPiece(right)
            }
        }

        // **没有逗号就原样返回**，不再按空格对半拆。
        //
        // 按空格拆一定会落在词与词的任意边界上，实测切出 "…click the subscribe" +
        // "button below and the small bell icon"，两句各自读得通、连起来别扭。
        // 长句宁可让显示层多给两行，也不能让人读半句。
        return listOf(text)
    }

    /**
     * 无标点文本的兜底切分。
     *
     * 优先在**连接词前**切（「и」「а」「но」这类通常是新分句的开头），
     * 没有连接词就按词数切；中文日文没有空格，只能按字数切。
     *
     * 只在整段没有任何句末标点时调用 —— 有标点的语言（中英韩）走正常路径。
     */
    private fun splitWithoutPunctuation(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.length <= MAX_PIECE_CHARS) return listOf(trimmed)

        val words = trimmed.split(" ").filter { it.isNotBlank() }

        // 没有空格（中日文）：按字数切
        if (words.size < 4) {
            val out = ArrayList<String>(4)
            var i = 0
            while (i < trimmed.length) {
                val end = (i + NOPUNCT_CJK_CHARS).coerceAtMost(trimmed.length)
                out.add(trimmed.substring(i, end).trim())
                i = end
            }
            return out.filter { it.isNotEmpty() }
        }

        if (words.size < NOPUNCT_MIN_WORDS * 2) return listOf(trimmed)

        val out = ArrayList<String>(4)
        var current = ArrayList<String>()
        for (w in words) {
            val starter = w.lowercase().trim(',', '.', '—') in CLAUSE_STARTERS
            val full = current.size >= NOPUNCT_MAX_WORDS
            // 到上限，或者在连接词前、且已经攒够一句话 → 断
            if (current.isNotEmpty() && (full || (starter && current.size >= NOPUNCT_MIN_WORDS))) {
                out.add(current.joinToString(" "))
                current = ArrayList()
            }
            current.add(w)
        }
        if (current.isNotEmpty()) out.add(current.joinToString(" "))

        // 太短的尾巴并回前一片，避免一条字幕只有两三个词
        if (out.size > 1 && out.last().length < MIN_MERGE_CHARS) {
            val tail = out.removeAt(out.lastIndex)
            out[out.lastIndex] = joinSmart(out.last(), tail)
        }
        return out
    }

    /**
     * 过短的句子并入相邻句子。
     *
     * 不并入会怎样：ASR 偶尔冒出 "Yeah." "Right." 这种独立短句，
     * 一条字幕只显示一个字，屏幕疯狂闪。字幕宁可少一条，也别抖。
     */
    private fun mergeShortPieces(pieces: List<String>): List<String> {
        if (pieces.size <= 1) return pieces

        val merged = ArrayList<String>(pieces.size)
        for (piece in pieces) {
            if (merged.isNotEmpty() && piece.length < MIN_MERGE_CHARS) {
                val last = merged.removeAt(merged.lastIndex)
                merged.add(joinSmart(last, piece))
            } else {
                merged.add(piece)
            }
        }
        return merged
    }

    /**
     * 拼接两条文本：中英之间补空格，中日韩之间不补。
     *
     * 不处理的话会出现「hello 世界」和「hello世界」两种结果，
     * 前者是对英文的尊重，后者是中文的习惯，按边界字符类型决定。
     */
    private fun joinSmart(a: String, b: String): String {
        val left = a.lastOrNull() ?: return b
        val right = b.firstOrNull() ?: return a
        val needSpace = !isCjk(left) && !isCjk(right)
        return if (needSpace) "$a $b" else "$a$b"
    }

    private fun isCjk(c: Char): Boolean {
        val code = c.code
        return code in 0x4E00..0x9FFF ||      // 汉字
            code in 0x3040..0x30FF ||          // 日文假名
            code in 0xAC00..0xD7AF ||          // 韩文
            code in 0x3000..0x303F            // 中日标点
    }
}
