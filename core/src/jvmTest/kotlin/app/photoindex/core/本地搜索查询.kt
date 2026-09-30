package app.photoindex.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class 本地搜索查询 {
    @Test
    fun 没有空格时整段作为一个片段且全角会先规范化() {
        val plan = planSearch("　ＣＡＦＥ小红　", SearchMatchMode.ANY, DEFAULT_SYNONYM_TABLE)

        assertEquals(SearchMatchMode.ANY, plan.mode)
        assertEquals(listOf("cafe小红"), plan.segments.map { it.text })
        assertEquals(listOf("cafe小红"), plan.segments.single().alternatives)
    }

    @Test
    fun 按空格拆段且同义词只做相等扩展() {
        val plan = planSearch("小红书 周末 小红", SearchMatchMode.ALL, DEFAULT_SYNONYM_TABLE)

        assertEquals(listOf("小红书", "周末", "小红"), plan.segments.map { it.text })
        assertEquals(listOf("小红书", "红书"), plan.segments[0].alternatives)
        assertEquals(listOf("周末"), plan.segments[1].alternatives)
        assertEquals(listOf("小红"), plan.segments[2].alternatives)
        assertFalse(plan.segments[2].alternatives.contains("小红书"))
    }

    @Test
    fun 摘录包含查询片段而不是另一句() {
        val text = "这是一句没有命中的风景说明。周末去了小红书，点了一杯咖啡，正文在后半句里继续写了很久才结束"
        val excerpt = searchExcerpt(text, listOf("小红"))

        assertTrue(excerpt.contains("小红"), "摘录应包含查询片段，实际为 $excerpt")
        assertFalse(excerpt.contains("风景说明"), "摘录应落在命中的那一句，实际为 $excerpt")
    }

    @Test
    fun 查询片段里的引号和运算符只按字面匹配() {
        assertEquals("\"小红书\"", ftsPhraseLiteral("小红书"))
        assertEquals("\"说\"\"好\"\"\"", ftsPhraseLiteral("说\"好\""))
        assertEquals(
            "searchText : \"a OR b\"",
            ftsDisjunction("searchText", listOf("a OR b")),
        )
    }
}
