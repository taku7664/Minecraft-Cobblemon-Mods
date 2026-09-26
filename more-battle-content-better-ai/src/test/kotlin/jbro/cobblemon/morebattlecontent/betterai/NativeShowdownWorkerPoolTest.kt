package jbro.cobblemon.morebattlecontent.betterai

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleDefinition
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleFrame
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBranchWorker
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeRulesGeneration
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownRuntime
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownServerLifecycle
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownWorkerPool
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class NativeShowdownWorkerPoolTest {
    @Test
    fun `pool bounds concurrency and retires only after borrowed work returns`(@TempDir directory: Path) {
        val rules = rules(directory)
        val engineRoot = rules.engineRoot
        val worker = FakeWorker(rules.fingerprint, "g1")
        val pool = NativeShowdownWorkerPool.create(rules, listOf(worker))
        val borrowed = CountDownLatch(1)
        val release = CountDownLatch(1)
        val caller = Executors.newSingleThreadExecutor()
        try {
            val active = caller.submit<String> {
                pool.withWorker(System.nanoTime() + TimeUnit.SECONDS.toNanos(5)) {
                    borrowed.countDown()
                    release.await()
                    (it as FakeWorker).label
                }
            }
            assertTrue(borrowed.await(5, TimeUnit.SECONDS))

            assertNull(
                pool.withWorker(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(25)) { "unexpected" },
            )
            pool.retire()
            assertFalse(worker.closed.get(), "A borrowed worker must not close underneath its branch")

            release.countDown()
            assertEquals("g1", active.get(5, TimeUnit.SECONDS))
            assertTrue(worker.closed.get())
            assertTrue(Files.exists(engineRoot), "Retiring a worker must not delete the original Showdown tree")
        } finally {
            release.countDown()
            caller.shutdownNow()
            pool.retire()
        }
    }

    @Test
    fun `refresh invalidates stale generation until replacement is complete`(@TempDir directory: Path) {
        val executor = Executors.newSingleThreadExecutor()
        val releaseSecond = CountDownLatch(1)
        val builds = AtomicInteger()
        val workers = mutableListOf<FakeWorker>()
        val runtime = NativeShowdownRuntime(executor, poolFactory = {
            val index = builds.incrementAndGet()
            if (index == 2) releaseSecond.await()
            val rules = rules(directory.resolve("g$index"))
            val worker = FakeWorker(rules.fingerprint, "g$index").also(workers::add)
            NativeShowdownWorkerPool.create(rules, listOf(worker))
        })
        try {
            assertTrue(runtime.refresh().get(5, TimeUnit.SECONDS))
            assertEquals("g1", runtime.withWorker(deadline()) { (it as FakeWorker).label })

            val refreshing = runtime.refresh()
            assertNull(runtime.withWorker(deadline()) { (it as FakeWorker).label })
            releaseSecond.countDown()
            assertTrue(refreshing.get(5, TimeUnit.SECONDS))
            assertEquals("g2", runtime.withWorker(deadline()) { (it as FakeWorker).label })
            assertTrue(workers.first().closed.get(), "Stale worker must be retired off the caller thread")
        } finally {
            releaseSecond.countDown()
            runtime.close()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `newest refresh wins when builds complete out of order`(@TempDir directory: Path) {
        val executor = Executors.newFixedThreadPool(2)
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val builds = AtomicInteger()
        val workers = mutableListOf<FakeWorker>()
        val runtime = NativeShowdownRuntime(executor, poolFactory = {
            val index = builds.incrementAndGet()
            if (index == 1) {
                firstStarted.countDown()
                releaseFirst.await()
            }
            val rules = rules(directory.resolve("g$index"))
            val worker = FakeWorker(rules.fingerprint, "g$index").also { synchronized(workers) { workers += it } }
            NativeShowdownWorkerPool.create(rules, listOf(worker))
        })
        try {
            val oldRefresh = runtime.refresh()
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
            val newestRefresh = runtime.refresh()
            assertTrue(newestRefresh.get(5, TimeUnit.SECONDS))
            assertEquals("g2", runtime.withWorker(deadline()) { (it as FakeWorker).label })

            releaseFirst.countDown()
            assertFalse(oldRefresh.get(5, TimeUnit.SECONDS))
            assertEquals("g2", runtime.withWorker(deadline()) { (it as FakeWorker).label })
            assertTrue(workers.single { it.label == "g1" }.closed.get())
        } finally {
            releaseFirst.countDown()
            runtime.close()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `invalidation removes active generation without blocking caller`(@TempDir directory: Path) {
        val executor = Executors.newSingleThreadExecutor()
        lateinit var worker: FakeWorker
        val runtime = NativeShowdownRuntime(executor, poolFactory = {
            val rules = rules(directory)
            worker = FakeWorker(rules.fingerprint, "active")
            NativeShowdownWorkerPool.create(rules, listOf(worker))
        })
        try {
            assertTrue(runtime.refresh().get(5, TimeUnit.SECONDS))
            runtime.invalidate()
            assertNull(runtime.withWorker(deadline()) { (it as FakeWorker).label })
            executor.submit {}.get(5, TimeUnit.SECONDS)
            assertTrue(worker.closed.get())
        } finally {
            runtime.close()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `close during activation window cannot resurrect completed generation`(@TempDir directory: Path) {
        val executor = Executors.newSingleThreadExecutor()
        val activationWindow = CountDownLatch(1)
        val releaseActivation = CountDownLatch(1)
        lateinit var worker: FakeWorker
        val runtime = NativeShowdownRuntime(
            executor = executor,
            poolFactory = {
                val rules = rules(directory)
                worker = FakeWorker(rules.fingerprint, "late")
                NativeShowdownWorkerPool.create(rules, listOf(worker))
            },
            beforeActivation = {
                activationWindow.countDown()
                releaseActivation.await()
            },
        )
        try {
            val refresh = runtime.refresh()
            assertTrue(activationWindow.await(5, TimeUnit.SECONDS))
            runtime.close()
            assertNull(runtime.withWorker(deadline()) { (it as FakeWorker).label })
            releaseActivation.countDown()
            assertFalse(refresh.get(5, TimeUnit.SECONDS))
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            assertTrue(worker.closed.get())
        } finally {
            releaseActivation.countDown()
            runtime.close()
        }
    }

    @Test
    fun `server lifecycle creates a fresh runtime after stop and restart`(@TempDir directory: Path) {
        val executors = mutableListOf<java.util.concurrent.ExecutorService>()
        val workers = mutableListOf<FakeWorker>()
        val starts = AtomicInteger()
        val lifecycle = NativeShowdownServerLifecycle {
            val index = starts.incrementAndGet()
            val executor = Executors.newSingleThreadExecutor().also(executors::add)
            NativeShowdownRuntime(executor, poolFactory = {
                val rules = rules(directory.resolve("server$index"))
                val worker = FakeWorker(rules.fingerprint, "server$index").also(workers::add)
                NativeShowdownWorkerPool.create(rules, listOf(worker))
            })
        }
        try {
            assertTrue(lifecycle.start().get(5, TimeUnit.SECONDS))
            assertEquals("server1", lifecycle.withWorker(deadline()) { (it as FakeWorker).label })
            lifecycle.stop()
            assertTrue(executors[0].awaitTermination(5, TimeUnit.SECONDS))
            assertTrue(workers[0].closed.get())

            assertTrue(lifecycle.start().get(5, TimeUnit.SECONDS))
            assertEquals("server2", lifecycle.withWorker(deadline()) { (it as FakeWorker).label })
        } finally {
            lifecycle.stop()
            executors.forEach { assertTrue(it.awaitTermination(5, TimeUnit.SECONDS)) }
        }
    }

    private fun rules(directory: Path): NativeRulesGeneration {
        Files.createDirectories(directory.resolve("sim"))
        Files.writeString(directory.resolve("index.js"), "globalThis.test = true;")
        Files.writeString(directory.resolve("sim/battle.js"), "module.exports = {};")
        return NativeRulesGeneration.capture(directory)
    }

    private fun deadline(): Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)

    private class FakeWorker(
        override val rulesFingerprint: String,
        val label: String,
    ) : NativeBranchWorker {
        val closed = AtomicBoolean(false)

        override fun createBattle(definition: NativeBattleDefinition): NativeBattleFrame =
            error("Not needed by the pool lifecycle test")

        override fun rebindMoves(
            snapshotJson: String,
            rebindings: List<jbro.cobblemon.morebattlecontent.betterai.simulation.NativeMoveSetRebinding>,
        ): NativeBattleFrame = error("Not needed by the pool lifecycle test")

        override fun branch(snapshotJson: String, p1Choice: String, p2Choice: String): NativeBattleFrame =
            error("Not needed by the pool lifecycle test")

        override fun close() {
            check(closed.compareAndSet(false, true)) { "Worker closed twice" }
        }
    }
}
