package io.legado.app.model.script

import io.legado.app.model.analyzeRule.AnalyzeRuleCore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Run on macOS with :data:iosSimulatorArm64Test; exercises the actual QuickJS bridge. */
class NativeJsLifetimeTest {
    @Test
    fun scriptCannotCloseHostRuntime() {
        val rule = AnalyzeRuleCore()
        val scope = NativeJsEngine.getRuntimeScope(JsBindings().apply { this["java"] = rule })
        try {
            assertEquals("undefined", NativeJsEngine.eval("typeof java.close", scope, null))
            assertFailsWith<NativeScriptException> { NativeJsEngine.eval("java.close()", scope, null) }
            assertEquals("alive", NativeJsEngine.eval("'alive'", scope, null))
        } finally {
            scope.close()
            scope.close()
            rule.close()
        }
        assertFailsWith<IllegalStateException> { NativeJsEngine.eval("1", scope, null) }
    }

    @Test
    fun closeDuringActiveUseWaitsForCleanup() {
        val scope = NativeJsEngine.createStandaloneScope() as NativeJsScope
        val ctx = scope.ctx
        scope.withUse {
            scope.close()
            assertEquals(ctx, scope.ctx)
        }
        assertEquals(null, scope.ctx)
        assertEquals(null, scope.rt)
        scope.close()
    }
}
