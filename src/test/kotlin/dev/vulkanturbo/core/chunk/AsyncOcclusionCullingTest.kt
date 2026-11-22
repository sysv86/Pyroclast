package dev.vulkanturbo.core.chunk

import dev.vulkanturbo.core.AsyncOcclusionCoordinator
import dev.vulkanturbo.core.OcclusionGraphBridge
import it.unimi.dsi.fastutil.longs.Long2ObjectMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongArrayList
import it.unimi.dsi.fastutil.longs.LongList
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.client.renderer.Octree
import net.minecraft.client.renderer.SectionOcclusionGraph
import net.minecraft.client.renderer.ViewArea
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher
import net.minecraft.client.renderer.culling.Frustum
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import sun.misc.Unsafe
import java.util.Queue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class AsyncOcclusionCullingTest {
    private class FakeStorage {
        val tree = Octree(SectionPos.of(0, 0, 0), 4, 4096, 0)
        val map = IdentityHashMapNodeMap()
        val waiting: Long2ObjectMap<LongList> = Long2ObjectOpenHashMap()
    }

    private class IdentityHashMapNodeMap {
        val entries = java.util.IdentityHashMap<SectionRenderDispatcher.RenderSection, SectionOcclusionGraph.Node>()
    }

    private class FakeState(val storage: FakeStorage)

    private class FakeBridge(
        private val onRunUpdates: (
            storage: FakeStorage,
            queue: Queue<SectionOcclusionGraph.Node>,
            onNewSection: Consumer<SectionRenderDispatcher.RenderSection>,
        ) -> Unit,
    ) : OcclusionGraphBridge<Any, Any, Any> {
        val workerThreadNames = mutableListOf<String>()

        override fun createGraphState(viewArea: ViewArea): Any = FakeState(FakeStorage())
        override fun getStorage(state: Any): Any = (state as FakeState).storage
        override fun getSectionTree(storage: Any): Octree = (storage as FakeStorage).tree
        override fun getSectionToNodeMap(storage: Any): Any = (storage as FakeStorage).map
        override fun getSectionsWaiting(storage: Any): Long2ObjectMap<LongList> = (storage as FakeStorage).waiting

        override fun getNodeFromMap(map: Any, section: SectionRenderDispatcher.RenderSection): SectionOcclusionGraph.Node? =
            (map as IdentityHashMapNodeMap).entries[section]

        override fun putNodeInMap(
            map: Any,
            section: SectionRenderDispatcher.RenderSection,
            node: SectionOcclusionGraph.Node,
        ) {
            (map as IdentityHashMapNodeMap).entries[section] = node
        }

        override fun getNodeSection(node: SectionOcclusionGraph.Node): SectionRenderDispatcher.RenderSection =
            nodeSectionField.get(node) as SectionRenderDispatcher.RenderSection

        override fun getRenderSection(viewArea: ViewArea, sectionNode: Long): SectionRenderDispatcher.RenderSection? = null

        override fun initializeQueueForFullUpdate(
            graph: SectionOcclusionGraph,
            camBlockPos: BlockPos,
            queue: Queue<SectionOcclusionGraph.Node>,
        ) {
        }

        override fun runUpdates(
            graph: SectionOcclusionGraph,
            storage: Any,
            camPos: Vec3,
            queue: Queue<SectionOcclusionGraph.Node>,
            smartCull: Boolean,
            onNewSection: Consumer<SectionRenderDispatcher.RenderSection>,
            emptySections: LongOpenHashSet,
            loadedChunks: LongOpenHashSet,
        ) {
            synchronized(workerThreadNames) {
                workerThreadNames.add(Thread.currentThread().name)
            }
            onRunUpdates(storage as FakeStorage, queue, onNewSection)
        }
    }

    @Test
    fun `async coordinator runs full and partial updates off calling thread and hands off safely`() {
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "vt-test-occ-worker") }
        try {
            val sectionA = createSection(0, SectionPos.asLong(0, 4, 0))
            val sectionB = createSection(1, SectionPos.asLong(1, 0, 1))
            val nodeA = createNode(sectionA)

            var callIndex = 0

            val bridge = FakeBridge { storage, queue, consumer ->
                callIndex++
                if (callIndex == 1) {
                    storage.map.entries[sectionA] = nodeA
                    storage.waiting.put(1234L, LongArrayList.of(5678L))
                } else {
                    // Partial update receives sectionA via queue and uncovers sectionB.
                    assertFalse(queue.isEmpty())
                    storage.waiting.remove(1234L)
                    consumer.accept(sectionB)
                }
            }

            val coordinator = AsyncOcclusionCoordinator(executor, bridge)
            val owner = allocateUnchecked(SectionOcclusionGraph::class.java)
            val viewArea = allocateUnchecked(ViewArea::class.java)
            val camera = CameraRenderState().apply {
                blockPos = BlockPos(10, 64, 20)
                pos = Vec3(10.5, 64.5, 20.5)
                smartCull = true
                cullFrustum = Frustum(Matrix4f().ortho(-256f, 256f, -256f, 256f, -256f, 256f), Matrix4f())
            }
            val currentGraph = AtomicReference<Any>(FakeState(FakeStorage()))
            val initialGraph = currentGraph.get()
            val needsFrustum = AtomicBoolean(false)

            coordinator.enqueueFull(owner, viewArea, camera, LongOpenHashSet(), LongOpenHashSet())
            coordinator.awaitIdleForTesting()

            // Drain full update on calling thread.
            coordinator.drainCompleted(currentGraph, needsFrustum)
            assertNotSame(initialGraph, currentGraph.get())
            assertTrue(needsFrustum.getAndSet(false))
            assertTrue(coordinator.expectedChunksSnapshot().contains(1234L))

            // Enqueue partial propagation and verify it executes on worker thread.
            coordinator.enqueuePropagation(sectionA)
            coordinator.enqueuePartial(
                owner,
                viewArea,
                camera,
                LongOpenHashSet(),
                LongOpenHashSet(),
                LongOpenHashSet(),
                null,
            )
            coordinator.awaitIdleForTesting()

            coordinator.drainCompleted(currentGraph, needsFrustum)
            assertTrue(needsFrustum.get())
            assertFalse(coordinator.expectedChunksSnapshot().contains(1234L))
            assertEquals(listOf("vt-test-occ-worker", "vt-test-occ-worker"), bridge.workerThreadNames)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `waitAndReset discards stale in-flight results from prior epoch`() {
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "vt-test-occ-reset") }
        try {
            val started = CountDownLatch(1)
            val allowFinish = CountDownLatch(1)
            val bridge = FakeBridge { _, _, _ ->
                started.countDown()
                allowFinish.await(5, TimeUnit.SECONDS)
            }
            val coordinator = AsyncOcclusionCoordinator(executor, bridge)
            val owner = allocateUnchecked(SectionOcclusionGraph::class.java)
            val viewArea = allocateUnchecked(ViewArea::class.java)
            val camera = CameraRenderState().apply {
                blockPos = BlockPos.ZERO
                pos = Vec3.ZERO
                smartCull = true
            }
            val currentGraph = AtomicReference<Any>(FakeState(FakeStorage()))
            val initialGraph = currentGraph.get()
            val needsFrustum = AtomicBoolean(false)

            coordinator.enqueueFull(owner, viewArea, camera, LongOpenHashSet(), LongOpenHashSet())
            assertTrue(started.await(5, TimeUnit.SECONDS))

            // Release worker right as waitAndReset runs; reset must prevent stale graph swap.
            allowFinish.countDown()
            coordinator.waitAndReset()
            coordinator.drainCompleted(currentGraph, needsFrustum)
            assertEquals(initialGraph, currentGraph.get())
            assertFalse(needsFrustum.get())
            assertNotNull(coordinator.expectedChunksSnapshot())
        } finally {
            executor.shutdownNow()
        }
    }

    companion object {
        private val unsafe: Unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply {
            isAccessible = true
        }.get(null) as Unsafe

        private val nodeSectionField = SectionOcclusionGraph.Node::class.java.getDeclaredField("section").apply {
            isAccessible = true
        }

        private val nodeCtor = SectionOcclusionGraph.Node::class.java.getDeclaredConstructor(
            SectionRenderDispatcher.RenderSection::class.java,
            net.minecraft.core.Direction::class.java,
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }

        private val bbField = SectionRenderDispatcher.RenderSection::class.java.getDeclaredField("bb").apply {
            isAccessible = true
        }

        private val sectionNodeField = SectionRenderDispatcher.RenderSection::class.java.getDeclaredField("sectionNode").apply {
            isAccessible = true
        }

        private val renderOriginField = SectionRenderDispatcher.RenderSection::class.java.getDeclaredField("renderOrigin").apply {
            isAccessible = true
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T : Any> allocateUnchecked(clazz: Class<T>): T = unsafe.allocateInstance(clazz) as T

        private fun createSection(index: Int, sectionNode: Long): SectionRenderDispatcher.RenderSection {
            val sec = allocateUnchecked(SectionRenderDispatcher.RenderSection::class.java)
            val x = SectionPos.sectionToBlockCoord(SectionPos.x(sectionNode)).toDouble()
            val y = SectionPos.sectionToBlockCoord(SectionPos.y(sectionNode)).toDouble()
            val z = SectionPos.sectionToBlockCoord(SectionPos.z(sectionNode)).toDouble()
            bbField.set(sec, AABB(x, y, z, x + 16.0, y + 16.0, z + 16.0))
            sectionNodeField.setLong(sec, sectionNode)
            renderOriginField.set(sec, BlockPos.MutableBlockPos(x.toInt(), y.toInt(), z.toInt()))
            return sec
        }

        private fun createNode(section: SectionRenderDispatcher.RenderSection): SectionOcclusionGraph.Node =
            nodeCtor.newInstance(section, null, 0)
    }
}
