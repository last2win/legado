package io.legado.app.model.fileBook

import io.legado.app.model.script.JsConversionBudget
import org.junit.Assert.*
import org.junit.Test

class JsConversionBudgetTest {
    @Test fun lengthsAreCheckedBeforeNarrowingAndAllocation() {
        for (size in listOf(-1L, 100_001L, 1_000_000_000L, Long.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { JsConversionBudget().elements(size) }
        }
        assertEquals(3, JsConversionBudget().elements(3))
    }
    @Test fun siblingCollectionsShareTheTotalElementLimit() {
        val budget = JsConversionBudget()
        budget.elements(60_000)
        assertThrows(IllegalArgumentException::class.java) { budget.elements(60_000) }
    }
    @Test fun stringsBuffersAndObjectsShareTheByteAndNodeLimits() {
        val budget = JsConversionBudget()
        budget.bytes(JsConversionBudget.MAX_BYTES - 10)
        assertThrows(IllegalArgumentException::class.java) { budget.bytes(11) }
        val nodes = JsConversionBudget()
        repeat(100_000) { nodes.node(0) }
        assertThrows(IllegalArgumentException::class.java) { nodes.node(0) }
        assertThrows(IllegalArgumentException::class.java) { JsConversionBudget().node(65) }
    }
    @Test fun everyAllocationBoundaryChecksCancellation() {
        val budget = JsConversionBudget { throw kotlinx.coroutines.CancellationException("cancelled") }
        assertThrows(kotlinx.coroutines.CancellationException::class.java) { budget.elements(1) }
        assertThrows(kotlinx.coroutines.CancellationException::class.java) { budget.bytes(1) }
        assertThrows(kotlinx.coroutines.CancellationException::class.java) { budget.node(0) }
    }
}
