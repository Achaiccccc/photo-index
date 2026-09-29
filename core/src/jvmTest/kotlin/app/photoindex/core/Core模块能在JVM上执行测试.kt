package app.photoindex.core

import kotlin.test.Test
import kotlin.test.assertEquals

class Core模块能在JVM上执行测试 {
    @Test
    fun core模块能在JVM上加载并带上统一包名() {
        assertEquals("app.photoindex", Core.PACKAGE_NAME)
        assertEquals("app.photoindex.core.Core", Core::class.qualifiedName)
    }
}
