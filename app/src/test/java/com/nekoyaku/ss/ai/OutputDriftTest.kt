package com.nekoyaku.ss.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** 0.5.89：模型把輸入欄位名 text 抄到輸出、名詞陣列中途壞掉——都要能收。 */
class OutputDriftTest {
    private val service = TranslationService()

    @Test
    fun textKeyAcceptedAsTranslationWhenDiffersFromOriginal() {
        val resp = """[{"id":0,"translation":"第一段"},{"id":1,"text":"第二段"}]"""
        assertEquals(listOf("第一段", "第二段"), service.parseTranslationJson(resp, listOf("하나", "둘")))
    }

    @Test
    fun textKeyEchoingOriginalIsRejected() {
        val resp = """[{"id":0,"translation":"第一段"},{"id":1,"text":"둘"}]"""
        assertThrows(TranslationFormatException::class.java) {
            service.parseTranslationJson(resp, listOf("하나", "둘"))
        }
    }

    @Test
    fun glossaryArrayBrokenMidway_salvagesGoodObjects() {
        val src = "청명이 화산의 매화검법을 펼쳤다. 윤종이 보았다."
        val resp = """[{"term":"청명","zh":"青明","type":"人名"},{"term":"매화검법","zh":"梅花"劍法","type":"武功"},{"term":"윤종","zh":"尹宗","type":"人名"}]"""
        val out = service.parseGlossaryJson(resp, src)
        assertEquals(listOf("청명", "윤종"), out.map { it.term })
    }
}
