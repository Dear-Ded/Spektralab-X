package com.dearded.spektralab

import org.junit.Assert.*
import org.junit.Test

class ErrorCodesTest {
    @Test
    fun `format creates correct message`() {
        val msg = ErrorCodes.format(ErrorCodes.ENGINE_INIT_FAILED, "引擎启动失败")
        assertEquals("[E-1001] 引擎启动失败", msg)
    }

    @Test
    fun `extract finds error code`() {
        val code = ErrorCodes.extract("[E-2001] 内存不足：无法分配缓冲区")
        assertEquals("E-2001", code)
    }

    @Test
    fun `extract returns null when no code present`() {
        val code = ErrorCodes.extract("普通错误消息")
        assertNull(code)
    }

    @Test
    fun `extract handles null message`() {
        val code = ErrorCodes.extract(null)
        assertNull(code)
    }

    @Test
    fun `exception messages include error codes`() {
        val ex = EngineException("测试失败", ErrorCodes.ENGINE_EXCEPTION)
        assertTrue(ex.message!!.contains("E-1003"))
        assertTrue(ex.message!!.contains("测试失败"))
    }
}
