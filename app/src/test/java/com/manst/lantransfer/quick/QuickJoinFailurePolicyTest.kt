package com.manst.lantransfer.quick

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickJoinFailurePolicyTest {

    @Test
    fun allFailuresAreAuth_countsAsCodeFailure() {
        assertTrue(QuickJoinFailurePolicy.shouldCountAsCodeFailure(authenticationFailed = true, nonAuthFailureCount = 0))
    }

    @Test
    fun mixedAuthAndNetworkFailures_doesNotCount() {
        // 端口被占用/连接被拒等错误混入时，不能误判为接收码错误
        assertFalse(QuickJoinFailurePolicy.shouldCountAsCodeFailure(authenticationFailed = true, nonAuthFailureCount = 1))
    }

    @Test
    fun noAuthFailure_doesNotCount() {
        assertFalse(QuickJoinFailurePolicy.shouldCountAsCodeFailure(authenticationFailed = false, nonAuthFailureCount = 0))
    }
}
