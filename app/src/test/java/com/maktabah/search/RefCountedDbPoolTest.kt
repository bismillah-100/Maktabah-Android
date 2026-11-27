package com.maktabah.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class RefCountedDbPoolTest {
    private class FakeHandle(val id: String) {
        var closed: Boolean = false
    }

    @Test
    fun acquire_reusesActiveHandleAndClosesAfterEvictOnceReleased() {
        val pool = RefCountedDbPool<FakeHandle>(maxEntries = 2) { it.closed = true }
        var opens = 0

        val first = pool.acquire("a.sqlite") { opens++; FakeHandle("a") }!!
        val second = pool.acquire("a.sqlite") { opens++; FakeHandle("a2") }!!

        assertEquals(1, opens)
        assertSame(first, second)

        pool.evict("a.sqlite")
        assertEquals(false, first.handle.closed)

        pool.release(first)
        assertEquals(false, first.handle.closed)

        pool.release(second)
        assertEquals(true, first.handle.closed)

        val reopened = pool.acquire("a.sqlite") { opens++; FakeHandle("a3") }!!
        assertEquals(2, opens)
        assertNotSame(first, reopened)
        pool.release(reopened)
    }

    @Test
    fun acquire_lruEvictionDefersCloseForInFlightHandleAndClosesIdleHandleImmediately() {
        val pool = RefCountedDbPool<FakeHandle>(maxEntries = 2) { it.closed = true }

        val a = pool.acquire("a.sqlite") { FakeHandle("a") }!!
        val b = pool.acquire("b.sqlite") { FakeHandle("b") }!!
        pool.release(b) // b is idle; a is still active (LRU)

        val c = pool.acquire("c.sqlite") { FakeHandle("c") }!!
        assertEquals(true, a.markedForClose)
        assertEquals(false, a.handle.closed)
        assertEquals(false, b.handle.closed)

        pool.release(a)
        assertEquals(true, a.handle.closed)

        val d = pool.acquire("d.sqlite") { FakeHandle("d") }!!
        assertEquals(true, b.handle.closed)

        pool.evictAll()
        assertEquals(false, c.handle.closed)
        assertEquals(false, d.handle.closed)

        pool.release(c)
        pool.release(d)
        assertEquals(true, c.handle.closed)
        assertEquals(true, d.handle.closed)
    }

    @Test
    fun acquire_returnsNullWhenOpenerFailsWithoutStoringEntry() {
        val pool = RefCountedDbPool<FakeHandle>(maxEntries = 2) { it.closed = true }

        assertNull(pool.acquire("missing.sqlite") { null })
        val opened = pool.acquire("missing.sqlite") { FakeHandle("ok") }!!
        assertEquals("ok", opened.handle.id)
        pool.release(opened)
        pool.evictAll()
        assertEquals(true, opened.handle.closed)
    }
}
