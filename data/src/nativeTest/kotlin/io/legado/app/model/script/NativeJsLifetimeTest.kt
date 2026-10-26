@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.model.script

import io.legado.app.model.analyzeRule.AnalyzeRuleCore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import io.legado.app.help.JsExtensionsCommon
import io.legado.app.help.http.KmpResponseBuilder
import org.jsoup.Connection
import org.jsoup.internal.HttpResponse
import org.jsoup.internal.HttpConnectionRequest

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
    fun responseParseRegistersDomInOwningScope() {
        val response = HttpResponse(KmpResponseBuilder().build(), HttpConnectionRequest(),
            "<html><body><p>Hello scoped DOM</p></body></html>".encodeToByteArray())
        val java = object : JsExtensionsCommon {
            override fun getSource() = null
            override fun get(urlStr: String, headers: Map<String, String>): Connection.Response = response
        }
        val owner = NativeJsEngine.getRuntimeScope(JsBindings().apply { this["java"] = java }) as NativeJsScope
        val other = NativeJsEngine.createStandaloneScope() as NativeJsScope
        try {
            val initialHandles = owner.handles.size
            assertEquals("Hello scoped DOM", NativeJsEngine.eval(
                "var response = java.get('https://example.test', {}); var doc = response.parse(); doc.select('p').text();",
                owner, null))
            assertTrue(owner.handles.size > initialHandles)
            val handle = owner.handles.last()
            assertEquals("undefined", NativeJsEngine.eval("typeof __nativeDispatch($handle, 704, [])", other, null))
            assertEquals("Hello scoped DOM", NativeJsEngine.eval("doc.text()", owner, null))
        } finally {
            owner.close()
            other.close()
        }
        assertTrue(owner.handles.isEmpty())
        assertFailsWith<IllegalStateException> { NativeJsEngine.eval("doc.text()", owner, null) }
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
