package com.manst.lantransfer.quick

/**
 * 判断一次「快速传文件连接失败」是否应计为「接收码错误」。
 *
 * 自动回退会依次尝试 FTPS/SFTP/SCP；只有**全部失败都是认证错误**时，
 * 才说明大概率是接收码错误。若同时出现连接被拒/端口占用/超时等非认证错误，
 * 更可能是目标不是 LanLink 服务或端口冲突，不应把用户锁 10 分钟。
 */
object QuickJoinFailurePolicy {
    fun shouldCountAsCodeFailure(authenticationFailed: Boolean, nonAuthFailureCount: Int): Boolean =
        authenticationFailed && nonAuthFailureCount == 0
}
