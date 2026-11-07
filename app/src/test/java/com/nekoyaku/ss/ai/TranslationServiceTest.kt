package com.nekoyaku.ss.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TranslationServiceTest {
    private val service = TranslationService()

    @Test
    fun parseTranslationJson_acceptsWrappedJsonArray() {
        val response = """
            ```json
            [
              {"id":0,"translation":"第一段"},
              {"id":1,"translation":"第二段"}
            ]
            ```
        """.trimIndent()

        assertEquals(
            listOf("第一段", "第二段"),
            service.parseTranslationJson(response, listOf("하나", "둘")),
        )
    }

    @Test
    fun normalizePunctuation_convertsCjkAdjacentHalfWidth_keepsLatin() {
        // CJK 旁的半形 ,?! → 全形；拉丁詞間與數字時間的標點保留
        assertEquals(
            "背對大海，悄悄轉頭？好！那是回切（cutback, ok）10:30 出發：走",
            service.normalizePunctuation("背對大海,悄悄轉頭?好!那是回切（cutback, ok）10:30 出發:走"),
        )
    }

    @Test
    fun normalizePunctuation_convertsCurlyQuotesAndAsciiEllipsis() {
        // 彎雙引號→「」、ASCII 刪節號→……（單段重翻實錄：claude 吐 “ ” 與 ......）
        assertEquals("「因為我而哭？柯伊，真的嗎？」", service.normalizePunctuation("“因為我而哭?柯伊,真的嗎?”"))
        assertEquals("啊，不，不是那樣的……", service.normalizePunctuation("啊，不，不是那樣的......"))
        // 彎單引號只在 CJK 旁轉『』；英文縮寫撇號不動；小數點不動
        assertEquals("他說『好』。", service.normalizePunctuation("他說‘好’。"))
        assertEquals("don’t 是 3.5 倍", service.normalizePunctuation("don’t 是 3.5 倍"))
    }

    @Test
    fun toTraditional_convertsCommonSimplified_keepsAmbiguousAndPark() {
        // 常見無歧義簡體字→正體
        assertEquals("他說：這是我的錯，時間會證明", service.toTraditional("他说：这是我的错，时间会证明"))
        // 歧義字（后/里/发/只）刻意不收——寧漏勿錯；無歧義的 猫 照轉
        assertEquals("皇后在里面发呆，只有一只貓", service.toTraditional("皇后在里面发呆，只有一只猫"))
        // 韓姓 朴 絕不可轉樸（박PD＝朴PD）
        assertEquals("朴PD來了", service.toTraditional("朴PD來了"))
        // 已是正體＝原樣返回
        assertEquals("完全正體的句子", service.toTraditional("完全正體的句子"))
    }

    @Test
    fun parseTranslationJson_rejectsNonArrayResponse() {
        assertThrows(TranslationFormatException::class.java) {
            service.parseTranslationJson("抱歉，我無法提供指定格式。", listOf("하나"))
        }
    }

    @Test
    fun parseTranslationJson_rejectsMissingParagraph() {
        assertThrows(TranslationFormatException::class.java) {
            service.parseTranslationJson(
                """[{"id":0,"translation":"第一段"}]""",
                listOf("하나", "둘"),
            )
        }
    }

    /** 名詞表按批過濾（0.4.54）：原文出現才注入；助詞附著與全名部件比對都要命中。 */
    @Test
    fun relevantGlossary_matchesParticlesAndNameComponents() {
        val glossary = listOf(
            GlossaryTerm("레오나르도 블레인", "李奧納多．布萊恩", "人名", ""),
            GlossaryTerm("휴고", "修格", "人名", ""),
            GlossaryTerm("암실버", "暗銀", "組織", ""),
            GlossaryTerm("형", "哥", "稱呼", ""),
        )
        val text = "레오나르도가 검을 들었다.\n휴고는 조용히 바라보았다."
        val hit = TranslationService.relevantGlossary(glossary, text)
        // 全名詞條靠部件「레오나르도」命中；휴고 帶助詞 는 直接 substring 命中
        assertEquals(listOf("레오나르도 블레인", "휴고"), hit.map { it.term })
    }

    /** 單字詞條整詞比對，不因拆件門檻被丟。 */
    @Test
    fun relevantGlossary_keepsSingleCharTerm() {
        val glossary = listOf(GlossaryTerm("형", "哥", "稱呼", ""))
        assertEquals(1, TranslationService.relevantGlossary(glossary, "형, 이리 와봐.").size)
        assertEquals(0, TranslationService.relevantGlossary(glossary, "아무 관계 없는 문장.").size)
    }

    /**
     * 韓文人名去姓比對（0.5.9）：名詞表存全名、正文只喊「名」（이선/이선아）也要命中——
     * 否則該段丟失譯名支援、單段重翻照樣飄（實案：135 詞只帶到 1~2 個）。
     */
    @Test
    fun relevantGlossary_matchesGivenNameOnly() {
        val glossary = listOf(
            GlossaryTerm("정이선", "鄭利善", "人名", ""),
            GlossaryTerm("남궁민수", "南宮民秀", "人名", ""),
            GlossaryTerm("한지호", "韓智浩", "人名", ""),
        )
        val text = "\"이선아, 괜찮아?\" 민수가 물었다."
        assertEquals(
            listOf("정이선", "남궁민수"),
            TranslationService.relevantGlossary(glossary, text).map { it.term },
        )
    }

    /**
     * 連線層錯誤要能重試（0.5.15）：實案訊息不含 timeout 等關鍵字，只能靠型別判斷。
     * 舊的字串比對讓使用者手動按三次才成功。
     */
    @Test
    fun isTransientNetworkError_catchesConnectFailuresByType() {
        // 實案原文（不含 "timeout"／"timed out"）
        assertEquals(
            true,
            TranslationService.isTransientNetworkError(
                java.net.SocketTimeoutException("failed to connect to /192.0.2.10 (port 8080) after 20000ms"),
            ),
        )
        assertEquals(
            true,
            TranslationService.isTransientNetworkError(
                java.net.ConnectException("Failed to connect to api.openai.com/162.159.140.245:443"),
            ),
        )
        // 包在 cause 裡也要認得
        assertEquals(
            true,
            TranslationService.isTransientNetworkError(
                IllegalStateException("wrapped", java.net.UnknownHostException("api.openai.com")),
            ),
        )
        // 非連線問題不重試（模型拒答、金鑰錯誤等）
        assertEquals(false, TranslationService.isTransientNetworkError(IllegalStateException("翻譯 API 失敗 HTTP 401：unauthorized")))
    }

    /** API 層可重試碼綁 "http {code}" 前綴，避免 IP／port 數字誤判。 */
    @Test
    fun isRetryableApiError_matchesStatusPrefixOnly() {
        assertEquals(true, TranslationService.isRetryableApiError("翻譯 API 失敗 HTTP 429：rate limited"))
        assertEquals(true, TranslationService.isRetryableApiError("翻譯 API 失敗 HTTP 503：busy"))
        assertEquals(true, TranslationService.isRetryableApiError("quota exceeded"))
        assertEquals(false, TranslationService.isRetryableApiError("翻譯 API 失敗 HTTP 400：bad request"))
        // 舊版會被 port 裡的 "500" 誤判成可重試
        assertEquals(false, TranslationService.isRetryableApiError("Failed to connect to 10.0.0.5:50029"))
        // Gemini 模型名錯誤的 404 含 "generateContent"，舊版單找 "rate" 會誤判
        assertEquals(false, TranslationService.isRetryableApiError(
            "翻譯 API 失敗 HTTP 404：models/x is not found for API version v1beta, or is not supported for generateContent",
        ))
        // OpenAI 沒儲值也回 429，重試沒用
        assertEquals(false, TranslationService.isRetryableApiError(
            "翻譯 API 失敗 HTTP 429：{\"error\":{\"code\":\"insufficient_quota\"}}",
        ))
    }

    /** 譯注超過詞長上限也要送：換行併成空白、超長截斷，不整條丟。 */
    @Test
    fun systemGlossaryBlock_keepsLongNote() {
        val note45 = "兩人是同期入團的隊友，私下互稱名字，公開場合才用職稱稱呼對方，翻譯時要依場合切換。"
        val block = service.systemGlossaryBlock(listOf(GlossaryTerm("형", "哥", "稱呼", "$note45\n第二行")))
        assertEquals(true, "$note45 第二行" in block)
        val long = service.systemGlossaryBlock(listOf(GlossaryTerm("형", "哥", "稱呼", "註".repeat(300))))
        assertEquals(true, "註".repeat(TranslationService.MAX_GLOSSARY_NOTE_LENGTH) in long)
        assertEquals(false, "註".repeat(TranslationService.MAX_GLOSSARY_NOTE_LENGTH + 1) in long)
    }

    /** 0.5.54：Groq 429 訊息帶「Please try again in Xs」→ 照供應商指示等（＋緩衝、有上下限）。 */
    @Test
    fun retryAfterMs_parsesGroqStyleHint() {
        // Groq 實際訊息型：Limit 6000, Used 5432, Requested 4096. Please try again in 35.28s.
        assertEquals(36_030L, TranslationService.retryAfterMs("... Please try again in 35.28s. ..."))
        assertEquals(8_410L, TranslationService.retryAfterMs("Please try again in 7.66s"))
        // 整數秒與大小寫變體
        assertEquals(12_750L, TranslationService.retryAfterMs("please TRY AGAIN IN 12s"))
        // 超長／超短夾回範圍
        assertEquals(70_000L, TranslationService.retryAfterMs("try again in 3600s"))
        assertEquals(1_000L, TranslationService.retryAfterMs("try again in 0.1s"))
        // 沒有指示 → null（走既有盲退避）
        assertEquals(null, TranslationService.retryAfterMs("翻譯 API 失敗 HTTP 429：rate limited"))
        assertEquals(null, TranslationService.retryAfterMs(null))
    }

    /** 0.5.54：Groq 413（request_too_large）＝縮 max_tokens 才會過的錯，不能當一般限流盲重試。 */
    @Test
    fun isRequestTooLarge_matchesGroq413() {
        assertEquals(
            true,
            TranslationService.isRequestTooLarge(
                "翻譯 API 失敗 HTTP 413：{\"error\":{\"message\":\"Request too large for model" +
                    " `qwen/qwen3-32b` on tokens per minute (TPM): Limit 6000, Requested 9109\"," +
                    "\"code\":\"rate_limit_exceeded\"}}",
            ),
        )
        assertEquals(true, TranslationService.isRequestTooLarge("... too large for model `x` ..."))
        // 一般 429 限流不是「請求太大」
        assertEquals(false, TranslationService.isRequestTooLarge("翻譯 API 失敗 HTTP 429：rate limit reached"))
        assertEquals(false, TranslationService.isRequestTooLarge(null))
    }

    /**
     * 每批骨幹注入（0.5.11）：省主詞/純對話批次字面比對到 0 詞（實案：整章後段譯名飄），
     * 仍要帶全部人名/稱呼讓模型判斷誰是誰；設定/地名維持字面過濾。
     */
    @Test
    fun glossaryForBatch_alwaysIncludesNamesAndHonorifics() {
        val glossary = listOf(
            GlossaryTerm("정이선", "鄭利善", "人名", ""),
            GlossaryTerm("형", "哥", "稱呼", ""),
            GlossaryTerm("어비스", "深淵", "設定", ""),
            GlossaryTerm("서울역", "首爾站", "地名", ""),
        )
        // 批次內完全沒有任何詞條字面出現（省主詞的純對話）
        val hit = TranslationService.glossaryForBatch(glossary, "\"……괜찮아. 걱정하지 마.\"")
        assertEquals(listOf("정이선", "형"), hit.map { it.term })
        // 字面有出現的設定詞照常收，且排在前（相關優先）
        val hit2 = TranslationService.glossaryForBatch(glossary, "어비스가 열렸다.")
        assertEquals(listOf("어비스", "정이선", "형"), hit2.map { it.term })
    }

    /**
     * 名詞表放 system 尾端（0.5.56）：輸出必須**與輸入順序無關**——DB 回來的順序掛 updatedAt，
     * 碰過任何一筆整份就換位，前綴快取全滅；按內容排序後只有真的改了才變。
     */
    @Test
    fun systemGlossaryBlock_isOrderIndependentAndSorted() {
        val a = GlossaryTerm("정이선", "鄭利善", "人名", "")
        val b = GlossaryTerm("형", "哥", "稱呼", "年上對年下")
        val c = GlossaryTerm("어비스", "深淵", "設定", "")
        val one = service.systemGlossaryBlock(listOf(a, b, c))
        val two = service.systemGlossaryBlock(listOf(c, b, a))
        assertEquals(one, two)
        // type 相同時按 term；消毒不通過的詞（含換行）被丟掉；空 note 不輸出
        val three = service.systemGlossaryBlock(listOf(c, GlossaryTerm("나쁜\n詞", "壞", "設定"), GlossaryTerm("가온", "佳恩", "人名")))
        assertEquals(
            """[{"term":"가온","zh":"佳恩","type":"人名"},{"term":"어비스","zh":"深淵","type":"設定"}]""",
            three,
        )
    }

    @Test
    fun replaceLiteraryWords_swapsUnambiguousAdverbs() {
        assertEquals("他突然回頭，猛然抓住門把。", service.replaceLiteraryWords("他驀地回頭，猛地抓住門把。"))
        assertEquals("沒有文言腔的句子。", service.replaceLiteraryWords("沒有文言腔的句子。"))
    }

    /** 韓文 ASCII 引號預轉：成對直引號→「」『』、撇號不動、落單不動、彎引號 1:1。 */
    @Test
    fun cjkQuotes_convertsPairedKoreanQuotes() {
        assertEquals("「형, 이리 와봐.」 이선이 말했다.", service.cjkQuotes("\"형, 이리 와봐.\" 이선이 말했다."))
        assertEquals("『뭐지?』 그는 생각했다.", service.cjkQuotes("'뭐지?' 그는 생각했다."))
        // 關引號後接助詞
        assertEquals("『이선』이 누구야?", service.cjkQuotes("'이선'이 누구야?"))
        // 撇號（拉丁字母夾住）不動；落單直引號不動
        assertEquals("don't do that", service.cjkQuotes("don't do that"))
        assertEquals("5\" 모니터", service.cjkQuotes("5\" 모니터"))
        // 彎引號 1:1
        assertEquals("「안녕」 『속마음』", service.cjkQuotes("\u201C안녕\u201D \u2018속마음\u2019"))
        // 巢狀：雙引號內夾單引號
        assertEquals("「그가 『가자』라고 했어.」", service.cjkQuotes("\"그가 '가자'라고 했어.\""))
    }

    @Test
    fun `插圖翻譯三部分解析，包在 markdown 裡也抓得到`() {
        val t = service.parseIllustrationJson(
            "```json\n{\"repeated\":\"我是完美的老師！\",\"hidden\":\"생님 나는 한선…→…\",\"ending\":\"因為我不會違反規則!\"}\n```",
        )
        assertEquals("我是完美的老師！", t.repeated)
        assertEquals("因為我不會違反規則！", t.ending) // 半形驚嘆號正規化
        assertEquals(false, t.none)
    }

    @Test
    fun `插圖沒有文字回 none，格式錯或三欄皆空要丟例外`() {
        assertEquals(true, service.parseIllustrationJson("{\"none\":true}").none)
        assertThrows(TranslationFormatException::class.java) { service.parseIllustrationJson("圖裡寫著我是老師") }
        assertThrows(TranslationFormatException::class.java) {
            service.parseIllustrationJson("{\"repeated\":\"\",\"hidden\":\"\",\"ending\":\"\"}")
        }
    }
}
