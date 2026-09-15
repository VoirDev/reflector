@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.voir.reflector.sample.ledger.server

import dev.voir.reflector.sync.protocol.BatchSeq
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.protocol.events.SyncEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a connected client is told, and about which scope.
 *
 * The socket is the only path by which a client learns anything before its next request, so both of
 * these matter: a notification that reaches the wrong scope is a leak, and a revocation that reaches
 * nobody leaves the data on a device until it happens to ask again.
 */
class ScopeEventsTest {
    private val events = ScopeEvents()
    private val mine = ScopeId("user-1")
    private val theirs = ScopeId("user-2")

    @Test
    fun `a revocation reaches the socket of that scope and no other`() =
        runTest {
            // Collected on an unconfined dispatcher, and started before anything is published: the
            // flow is hot, and a socket that subscribes late has missed the event, here as in life.
            val mineSaw = mutableListOf<SyncEvent>()
            val theirsSaw = mutableListOf<SyncEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { events.events(mine).toList(mineSaw) }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { events.events(theirs).toList(theirsSaw) }

            events.revoke(mine)
            events.publish(theirs, LEDGER, BatchSeq("7"))
            testScheduler.advanceUntilIdle()

            assertEquals(listOf<SyncEvent>(SyncEvent.Revoked), mineSaw)
            assertEquals(listOf<SyncEvent>(SyncEvent.Invalidate(LEDGER, BatchSeq("7"))), theirsSaw)
        }
}
