package com.ohv.shared

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 共享层 smoke test：占位用，确保 commonTest 编译/运行链路通。
 * 真正的业务测试在 v1.6 Batch 1 中追加（DatabaseServiceTest 等）。
 */
class SmokeTest {

    @Test
    fun `kotlin test infra works`() {
        assertEquals(1 + 1, 2)
    }
}