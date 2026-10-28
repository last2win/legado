@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.model.script

import io.legado.app.model.analyzeRule.AnalyzeRuleCore
import kotlin.test.Test
import kotlin.test.BeforeTest
import io.legado.app.help.image.ImageOps
import io.legado.app.help.image.ImageRef
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
    @Test fun sparseAndNestedResultsCannotExpandPastHostBudgets() {
        val scope = NativeJsEngine.createStandaloneScope()
        try {
            for (script in listOf(
                "new Array(1000000000)",
                "var a = new Array(60000).fill(1); [a, a]",
                "var s = 'x'.repeat(1024 * 1024); [s, s, s, s, s]",
                "new Uint8Array(17 * 1024 * 1024)",
                "Array(1000).fill({value: 'x'.repeat(8192)})"
            )) {
                val failure = assertFailsWith<IllegalArgumentException> { NativeJsEngine.eval(script, scope, null) }
                assertTrue(failure.message.orEmpty().contains("JS conversion"), failure.message)
            }
            assertEquals(listOf(1, "中文", null, listOf(2)), NativeJsEngine.eval("[1, '中文', null, [2]]", scope, null))
            assertEquals("a\u0000b", NativeJsEngine.eval("'a\\u0000b'", scope, null))
        } finally { scope.close() }
    }

    @Test fun bridgeArgumentsShareOneConversionBudget() {
        val java = object : JsExtensionsCommon { override fun getSource() = null }
        val scope = NativeJsEngine.getRuntimeScope(JsBindings().apply { this["java"] = java })
        try {
            for (arg in listOf("new Array(1000000000)", "[Array(60000).fill(1), Array(60000).fill(2)]")) {
                val failure = assertFailsWith<NativeScriptException> { NativeJsEngine.eval("java.base64Encode($arg)", scope, null) }
                assertTrue(failure.message.orEmpty().contains("JS conversion"), failure.message)
            }
            assertEquals("YWJj", NativeJsEngine.eval("java.base64Encode('abc')", scope, null))
        } finally { scope.close() }
    }

    @Test
    fun pureJavaScriptLoopIsInterruptedAndScopeRemainsUsable() {
        val scope = NativeJsEngine.createStandaloneScope() as NativeJsScope
        try {
            scope.budget.timeoutMillis = 50
            assertFailsWith<NativeScriptException> { NativeJsEngine.eval("while (true) {}", scope, null) }
            scope.budget.timeoutMillis = 30_000
            assertEquals("alive", NativeJsEngine.eval("'alive'", scope, null))
        } finally { scope.close() }
    }

    @Test
    fun runtimeRejectsAllocationBeyondItsHeapBudget() {
        val scope = NativeJsEngine.createStandaloneScope()
        try {
            val failure = assertFailsWith<NativeScriptException> {
                NativeJsEngine.eval("new ArrayBuffer(80 * 1024 * 1024)", scope, null)
            }
            assertTrue(failure.message.orEmpty().contains("memory", ignoreCase = true), failure.message)
        } finally { scope.close() }
    }

    @BeforeTest
    fun initializeHostBindings() {
        // No image operations are exercised here; keep the real QuickJS/runtime/DOM bridge.
        if (JsBindingInjector.imageOpsOrNull == null) JsBindingInjector.registerImageOps(object : ImageOps {
            override fun decode(bytes: ByteArray): ImageRef = error("Image API not used in this test")
            override fun decode(base64: String): ImageRef = error("Image API not used in this test")
            override fun encode(img: ImageRef, format: String, quality: Int): ByteArray = error("Image API not used in this test")
            override fun split(img: ImageRef, rows: Int, cols: Int): List<ImageRef> = error("Image API not used in this test")
            override fun stitch(imgs: List<ImageRef>, direction: String): ImageRef = error("Image API not used in this test")
            override fun crop(img: ImageRef, x: Int, y: Int, w: Int, h: Int): ImageRef = error("Image API not used in this test")
            override fun rotate(img: ImageRef, deg: Int): ImageRef = error("Image API not used in this test")
            override fun flip(img: ImageRef, direction: String): ImageRef = error("Image API not used in this test")
            override fun size(img: ImageRef): Map<String, Int> = error("Image API not used in this test")
        })
    }

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
                "var response = java.get('https://example.test', {}); var doc = response.parse(); doc.select('p')[0].text();",
                owner, null))
            assertTrue(owner.handles.size > initialHandles)
            val handle = owner.handles.last()
            // Both tagged integer literals and float handles must resolve correctly.
            assertEquals("Hello scoped DOM", NativeJsEngine.eval("__nativeDispatch($handle, 3201, [])", owner, null))
            assertEquals("undefined", NativeJsEngine.eval("typeof __nativeDispatch($handle + 0.5, 3201, [])", owner, null))
            assertEquals("undefined", NativeJsEngine.eval("typeof __nativeDispatch(NaN, 3201, [])", owner, null))
            assertEquals("undefined", NativeJsEngine.eval("typeof __nativeDispatch($handle, 3201, [])", other, null))
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
