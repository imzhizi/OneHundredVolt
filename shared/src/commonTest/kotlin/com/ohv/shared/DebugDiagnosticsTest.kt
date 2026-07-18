package com.ohv.shared

import com.ohv.shared.diagnostics.DebugDiagnostics
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class DebugDiagnosticsTest {
    @Test
    fun `diagnostics redact sensitive details and keep bounded text`() {
        DebugDiagnostics.clear()
        DebugDiagnostics.log(
            category = "test",
            message = "request finished",
            details = mapOf("token" to "should-not-appear", "status" to "200")
        )

        assertEquals(1, DebugDiagnostics.count())
        val exported = DebugDiagnostics.exportText()
        assertContains(exported, "token=<redacted>")
        assertContains(exported, "status=200")
        kotlin.test.assertFalse(exported.contains("should-not-appear"))
        DebugDiagnostics.clear()
    }
}
