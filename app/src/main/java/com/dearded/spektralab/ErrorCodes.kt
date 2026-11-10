package com.dearded.spektralab

/**
 * 用户可见的错误编码，每个失败都带 E-xxxx 编码方便截图反馈。
 * 编码含义与用户该怎么办详见 docs/ERRORS.md。
 */
object ErrorCodes {
    // 引擎与 GPU (E-1xxx)
    const val ENGINE_INIT_FAILED = "E-1001"
    const val GPU_DEVICE_LOST = "E-1002"
    const val ENGINE_EXCEPTION = "E-1003"

    // 内存与资源 (E-2xxx)
    const val OUT_OF_MEMORY = "E-2001"
    const val OUT_OF_MEMORY_NATIVE = "E-2002"
    const val OUT_OF_MEMORY_GPU = "E-2003"

    // 源文件与解码 (E-3xxx)
    const val SOURCE_OPEN_FAILED = "E-3001"
    const val SOURCE_DECODE_FAILED = "E-3002"
    const val SOURCE_UNSUPPORTED = "E-3003"
    const val SOURCE_ACCESS_DENIED = "E-3004"

    // 导出与保存 (E-4xxx)
    const val EXPORT_BLANK_FRAME = "E-4001"
    const val EXPORT_SAVE_FAILED = "E-4002"
    const val EXPORT_ENCODE_FAILED = "E-4003"

    // 系统与权限 (E-5xxx)
    const val SYSTEM_INTERRUPTED = "E-5001"
    const val PERMISSION_DENIED = "E-5002"
    const val STORAGE_FULL = "E-5003"

    /** 为异常生成带编码的消息。*/
    fun format(code: String, message: String): String = "[$code] $message"

    /** 从异常消息中提取错误编码，未找到返回 null。*/
    fun extract(message: String?): String? {
        message ?: return null
        return Regex("""\[([E]-\d{4})\]""").find(message)?.groupValues?.get(1)
    }
}
