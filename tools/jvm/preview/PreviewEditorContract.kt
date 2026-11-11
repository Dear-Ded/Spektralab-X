package com.dearded.spektralab

import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PreviewEditorContract {
    private class Steps : CoroutineDispatcher() {
        val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun next() { check(tasks.isNotEmpty()) { "No scheduled continuation" }; tasks.removeFirst().run() }
        fun drain() { while (tasks.isNotEmpty()) next() }
    }
    private fun scenario(body: (PreviewHarness, Steps, Steps) -> Unit) {
        val main = Steps()
        val engine = Steps()
        EngineThread.dispatcher = engine
        NativeEngine.cacheTier = "preview"
        NativeEngine.fail = false
        NativeEngine.rejectParams = false
        DisplayColor.space = "sRGB"
        val scope = CoroutineScope(SupervisorJob() + main)
        try { body(PreviewHarness(scope), main, engine) }
        finally { scope.cancel(); main.drain(); engine.drain(); main.drain() }
    }

    @Test fun repackUsesActualNativeCacheTierAfterQueuedLiveCompletes() = scenario { vm, main, engine ->
        vm.viewModelScope.launch { vm.render("live") }
        main.next() // Native render queued, UI still says preview.
        vm.request(Edit(1)) // Look-only slider change queues a repack.
        vm.viewModelScope.launch { vm.repack() }
        vm.repacks.trySend(Unit)
        main.next() // Repack is queued behind the live render.
        engine.next() // Native retained display render is now live.
        engine.next() // Repack reads that live cache.
        main.drain()
        assertEquals(640, vm._ui.value.image!!.width)
        assertEquals("live", vm._ui.value.imageTier)
    }

    @Test fun recycledNativeHandleDoesNotPublishPreviousSource() = scenario { vm, main, engine ->
        vm.viewModelScope.launch { vm.render("preview") }
        main.next()
        engine.next()
        vm.replaceSameHandle() // Native allocator can return an old handle for a new source.
        main.drain()
        assertNull(vm._ui.value.image)
    }

    @Test fun sameEditLiveDoesNotDowngradePublishedPreview() = scenario { vm, main, engine ->
        vm.viewModelScope.launch { vm.render("preview") }
        main.next(); engine.next(); main.drain()
        vm.viewModelScope.launch { vm.render("live") }
        main.next(); engine.next(); main.drain()
        assertEquals("preview", vm._ui.value.imageTier)
        assertEquals(3400, vm._ui.value.image!!.width)
    }

    @Test fun previewCompletedAfterNewEditDoesNotPublishStaleParameters() = scenario { vm, main, engine ->
        val before = vm._ui.value.image
        vm.viewModelScope.launch { vm.render("preview") }
        main.next(); engine.next()
        vm.request(Edit(10)) // Cancellation is too late: native already returned.
        main.drain()
        assertSame(before, vm._ui.value.image)
    }

    @Test fun liveStillMakesProgressDuringContinuousDrag() = scenario { vm, main, engine ->
        vm.viewModelScope.launch { vm.render("live") }
        main.next(); engine.next()
        vm.request(Edit(10))
        main.drain()
        assertEquals("live", vm._ui.value.imageTier)
    }

    @Test fun bitmapKeepsTheProfileUsedByItsNativeSession() = scenario { vm, main, engine ->
        vm.sessionOutput = "Display P3"
        DisplayColor.space = "sRGB" // Window configuration changed after the session opened.
        vm.viewModelScope.launch { vm.render("preview") }
        main.next(); engine.next(); main.drain()
        assertEquals("Display P3", vm._ui.value.image!!.profile)
    }

    @Test fun rejectedParametersStillRestoreAcceptedEditAndShowFailure() = scenario { vm, main, engine ->
        vm.request(Edit(10))
        NativeEngine.rejectParams = true
        vm.viewModelScope.launch { vm.render("live") }
        main.next(); engine.next(); main.drain()
        assertEquals(Edit(), vm._ui.value.edit)
        assertEquals(Edit(), vm.wanted)
        assertTrue(vm._ui.value.message!!.contains("fake failure"))
    }

    @Test fun fullDetailDoesNotReplaceDisplayImageOrRepackCache() = scenario { vm, main, engine ->
        val display = vm._ui.value.image
        vm.inspect(null)
        main.next(); engine.next(); main.drain()
        assertSame(display, vm._ui.value.image)
        assertEquals(4096, vm._ui.value.detail!!.width)
        vm.viewModelScope.launch { vm.repack() }
        vm.repacks.trySend(Unit)
        main.next(); engine.next(); main.drain()
        assertEquals("preview", vm._ui.value.imageTier)
        assertEquals(3400, vm._ui.value.image!!.width)
        assertEquals(4096, vm._ui.value.detail!!.width)
    }

    @Test fun proofKeepsItsExplicitProfileSeparateFromDisplay() = scenario { vm, main, engine ->
        vm.sessionOutput = "Display P3"
        vm.inspect("sRGB")
        main.next(); engine.next(); main.drain()
        assertEquals("sRGB", vm._ui.value.detailTarget)
        assertEquals("sRGB", vm._ui.value.detail!!.profile)
        assertEquals("preview", vm._ui.value.imageTier)
    }

    @Test fun overrideWhileDetailCallbackIsQueuedRejectsTheOldDetail() = scenario { vm, main, engine ->
        vm.inspect(null)
        main.next(); engine.next()
        vm.previewOverride(Edit(10))
        main.drain()
        assertNull(vm._ui.value.detail)
        assertFalse(vm._ui.value.loadingDetail)
    }

    @Test fun oldSourceDetailIsRejectedEvenWhenHandleAndUiSourceAreUnchanged() = scenario { vm, main, engine ->
        vm.inspect(null)
        main.next(); engine.next()
        vm.replaceSameHandle()
        main.drain()
        assertNull(vm._ui.value.detail)
    }
}
