package com.manst.lantransfer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 桌面快捷方式 action → 应用内路由的纯映射测试（启动链路用模拟器手工验证）。 */
class RoutesShortcutTest {

    @Test
    fun shortcutActions_mapToExpectedRoutes() {
        assertEquals(Routes.QUICK_SEND, Routes.routeForShortcutAction(Routes.ACTION_QUICK_SEND))
        assertEquals(Routes.QUICK_RECEIVE, Routes.routeForShortcutAction(Routes.ACTION_QUICK_RECEIVE))
        assertEquals(Routes.SERVER, Routes.routeForShortcutAction(Routes.ACTION_LOCAL_SERVER))
    }

    @Test
    fun unknownShortcutAction_returnsNull() {
        assertNull(Routes.routeForShortcutAction(null))
        assertNull(Routes.routeForShortcutAction(""))
        assertNull(Routes.routeForShortcutAction("com.manst.lantransfer.action.UNKNOWN"))
        assertNull(Routes.routeForShortcutAction("android.intent.action.MAIN"))
    }

    @Test
    fun shortcutActions_areDistinct() {
        val actions = listOf(Routes.ACTION_QUICK_SEND, Routes.ACTION_QUICK_RECEIVE, Routes.ACTION_LOCAL_SERVER)
        assertEquals(actions.size, actions.toSet().size)
        val routes = actions.mapNotNull { Routes.routeForShortcutAction(it) }
        assertEquals(actions.size, routes.size)
        assertEquals(routes.size, routes.toSet().size)
    }
}
