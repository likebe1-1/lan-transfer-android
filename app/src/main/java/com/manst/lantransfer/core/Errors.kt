package com.manst.lantransfer.core

/** 本应用统一异常父类，message 可直接展示给用户。 */
open class LanTransferException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 连接/网络层错误。 */
class ConnectionException(message: String, cause: Throwable? = null) : LanTransferException(message, cause)

/** 认证失败。 */
class AuthenticationException(
    message: String = "认证失败，请检查用户名或密码",
    cause: Throwable? = null
) : LanTransferException(message, cause)

/** 远端路径/文件系统错误。 */
class RemotePathException(message: String, cause: Throwable? = null) : LanTransferException(message, cause)

/** 传输被用户取消。 */
class TransferCancelledException : LanTransferException("传输已取消")
