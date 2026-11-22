package dev.vulkanturbo.core

import com.google.common.collect.Queues
import dev.vulkanturbo.VulkanTurbo
import dev.vulkanturbo.diagnostics.VulkanDiagnosticLog
import it.unimi.dsi.fastutil.longs.Long2ObjectMap
import it.unimi.dsi.fastutil.longs.LongArrayList
import it.unimi.dsi.fastutil.longs.LongList
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import it.unimi.dsi.fastutil.longs.LongSet
import it.unimi.dsi.fastutil.longs.LongSets
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet
import net.minecraft.client.renderer.Octree
import net.minecraft.client.renderer.SectionOcclusionGraph
import net.minecraft.client.renderer.ViewArea
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher
import net.minecraft.client.renderer.culling.Frustum
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.core.BlockPos
import net.minecraft.util.Util
import net.minecraft.world.phys.Vec3
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.util.Queue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

/**
 * Immutable camera snapshot captured on the render thread before dispatching an asynchronous
 * occlusion update so background workers never observe frame-to-frame mutations of
 * `CameraRenderState`.
 */
data class OcclusionCameraSnapshot(
    val blockPos: BlockPos,
    val pos: Vec3,
    val smartCull: Boolean,
    val offsetFrustum: Frustum?,
)

/**
 * Abstraction over `SectionOcclusionGraph`'s package-private `GraphState`, `GraphStorage`, and
 * `SectionToNodeMap` internals so the async coordinator can be tested on the JVM without a live
 * Minecraft renderer.
 */
interface OcclusionGraphBridge<State : Any, Storage : Any, NodeMap : Any> {
    fun createGraphState(viewArea: ViewArea): State
    fun getStorage(state: State): Storage
    fun getSectionTree(storage: Storage): Octree
    fun getSectionToNodeMap(storage: Storage): NodeMap
    fun getSectionsWaiting(storage: Storage): Long2ObjectMap<LongList>
    fun getNodeFromMap(map: NodeMap, section: SectionRenderDispatcher.RenderSection): SectionOcclusionGraph.Node?
    fun putNodeInMap(map: NodeMap, section: SectionRenderDispatcher.RenderSection, node: SectionOcclusionGraph.Node)
    fun getNodeSection(node: SectionOcclusionGraph.Node): SectionRenderDispatcher.RenderSection
    fun getRenderSection(viewArea: ViewArea, sectionNode: Long): SectionRenderDispatcher.RenderSection?
    fun initializeQueueForFullUpdate(
        graph: SectionOcclusionGraph,
        camBlockPos: BlockPos,
        queue: Queue<SectionOcclusionGraph.Node>,
    )

    fun runUpdates(
        graph: SectionOcclusionGraph,
        storage: Storage,
        camPos: Vec3,
        queue: Queue<SectionOcclusionGraph.Node>,
        smartCull: Boolean,
        onNewSection: Consumer<SectionRenderDispatcher.RenderSection>,
        emptySections: LongOpenHashSet,
        loadedChunks: LongOpenHashSet,
    )
}

/**
 * One-time `MethodHandle` bridge to `SectionOcclusionGraph`'s package-private state types.
 */
object SectionOcclusionReflection : OcclusionGraphBridge<Any, Any, Any> {
    private class Handles {
        val graphStateCtor: MethodHandle
        val graphStateStorage: MethodHandle
        val graphStateQueue: MethodHandle
        val storageSectionTree: MethodHandle
        val storageSectionToNodeMap: MethodHandle
        val storageWaitingMap: MethodHandle
        val mapGet: MethodHandle
        val mapPut: MethodHandle
        val nodeSection: MethodHandle
        val viewAreaGetSection: MethodHandle
        val initFullQueue: MethodHandle
        val runUpdatesMethod: MethodHandle

        init {
            val lookup = MethodHandles.lookup()
            val graphClass = SectionOcclusionGraph::class.java
            val stateClass = Class.forName("net.minecraft.client.renderer.SectionOcclusionGraph\$GraphState")
            val storageClass = Class.forName("net.minecraft.client.renderer.SectionOcclusionGraph\$GraphStorage")
            val mapClass = Class.forName("net.minecraft.client.renderer.SectionOcclusionGraph\$SectionToNodeMap")
            val nodeClass = SectionOcclusionGraph.Node::class.java

            val ctor = stateClass.getDeclaredConstructor(ViewArea::class.java).apply { isAccessible = true }
            graphStateCtor = lookup.unreflectConstructor(ctor)

            val storageAcc = stateClass.getDeclaredMethod("storage").apply { isAccessible = true }
            graphStateStorage = lookup.unreflect(storageAcc)

            val queueAcc = stateClass.getDeclaredMethod("sectionsToPropagateFrom").apply { isAccessible = true }
            graphStateQueue = lookup.unreflect(queueAcc)

            val treeField = storageClass.getDeclaredField("sectionTree").apply { isAccessible = true }
            storageSectionTree = lookup.unreflectGetter(treeField)

            val mapField = storageClass.getDeclaredField("sectionToNodeMap").apply { isAccessible = true }
            storageSectionToNodeMap = lookup.unreflectGetter(mapField)

            val waitField = storageClass.getDeclaredField("sectionsWaitingForChunkLoads").apply { isAccessible = true }
            storageWaitingMap = lookup.unreflectGetter(waitField)

            val getM = mapClass.getDeclaredMethod("get", SectionRenderDispatcher.RenderSection::class.java)
                .apply { isAccessible = true }
            mapGet = lookup.unreflect(getM)

            val putM = mapClass.getDeclaredMethod(
                "put",
                SectionRenderDispatcher.RenderSection::class.java,
                nodeClass,
            ).apply { isAccessible = true }
            mapPut = lookup.unreflect(putM)

            val secField = nodeClass.getDeclaredField("section").apply { isAccessible = true }
            nodeSection = lookup.unreflectGetter(secField)

            val getSecM = ViewArea::class.java.getDeclaredMethod("getRenderSection", Long::class.javaPrimitiveType)
                .apply { isAccessible = true }
            viewAreaGetSection = lookup.unreflect(getSecM)

            val initM = graphClass.getDeclaredMethod(
                "initializeQueueForFullUpdate",
                BlockPos::class.java,
                Queue::class.java,
            ).apply { isAccessible = true }
            initFullQueue = lookup.unreflect(initM)

            val runM = graphClass.getDeclaredMethod(
                "runUpdates",
                storageClass,
                Vec3::class.java,
                Queue::class.java,
                Boolean::class.javaPrimitiveType,
                Consumer::class.java,
                LongOpenHashSet::class.java,
                LongOpenHashSet::class.java,
            ).apply { isAccessible = true }
            runUpdatesMethod = lookup.unreflect(runM)
        }
    }

    private val handles: Handles by lazy(LazyThreadSafetyMode.PUBLICATION) { Handles() }

    override fun createGraphState(viewArea: ViewArea): Any = handles.graphStateCtor.invoke(viewArea)

    override fun getStorage(state: Any): Any = handles.graphStateStorage.invoke(state)

    @Suppress("UNCHECKED_CAST")
    fun getSectionsToPropagateFrom(state: Any): Queue<SectionRenderDispatcher.RenderSection> =
        handles.graphStateQueue.invoke(state) as Queue<SectionRenderDispatcher.RenderSection>

    override fun getSectionTree(storage: Any): Octree = handles.storageSectionTree.invoke(storage) as Octree

    override fun getSectionToNodeMap(storage: Any): Any = handles.storageSectionToNodeMap.invoke(storage)

    @Suppress("UNCHECKED_CAST")
    override fun getSectionsWaiting(storage: Any): Long2ObjectMap<LongList> =
        handles.storageWaitingMap.invoke(storage) as Long2ObjectMap<LongList>

    override fun getNodeFromMap(map: Any, section: SectionRenderDispatcher.RenderSection): SectionOcclusionGraph.Node? =
        handles.mapGet.invoke(map, section) as SectionOcclusionGraph.Node?

    override fun putNodeInMap(map: Any, section: SectionRenderDispatcher.RenderSection, node: SectionOcclusionGraph.Node) {
        handles.mapPut.invoke(map, section, node)
    }

    override fun getNodeSection(node: SectionOcclusionGraph.Node): SectionRenderDispatcher.RenderSection =
        handles.nodeSection.invoke(node) as SectionRenderDispatcher.RenderSection

    override fun getRenderSection(viewArea: ViewArea, sectionNode: Long): SectionRenderDispatcher.RenderSection? =
        handles.viewAreaGetSection.invoke(viewArea, sectionNode) as SectionRenderDispatcher.RenderSection?

    override fun initializeQueueForFullUpdate(
        graph: SectionOcclusionGraph,
        camBlockPos: BlockPos,
        queue: Queue<SectionOcclusionGraph.Node>,
    ) {
        handles.initFullQueue.invoke(graph, camBlockPos, queue)
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
        handles.runUpdatesMethod.invoke(graph, storage, camPos, queue, smartCull, onNewSection, emptySections, loadedChunks)
    }
}

/**
 * Coordinates asynchronous full and partial section occlusion graph updates off the render thread.
 *
 * Design invariants:
 * - Only one serialized worker task runs against [workerGraphState] at any time (`workerScheduled`
 *   guarded by `jobLock`), eliminating concurrent writes to `SectionToNodeMap` and `sectionsWaitingForChunkLoads`.
 * - The render thread's `Octree` (`currentGraph.get().storage.sectionTree`) is NEVER mutated by the
 *   worker thread. Full updates build a dedicated published `Octree` on the worker before atomic swap;
 *   partial updates return only newly uncovered `RenderSection`s, which are inserted into the render
 *   `Octree` during `drainCompleted` on the render thread.
 * - `waitAndReset()` increments [epoch] and awaits any in-flight worker future so stale results are
 *   never published across world reloads or render distance changes.
 */
class AsyncOcclusionCoordinator @JvmOverloads constructor(
    private val executor: Executor = Util.backgroundExecutor(),
    private val bridge: OcclusionGraphBridge<Any, Any, Any> = SectionOcclusionReflection,
) {
    private class FullJob(
        val epoch: Long,
        val owner: SectionOcclusionGraph,
        val viewArea: ViewArea,
        val camera: OcclusionCameraSnapshot,
        val emptySections: LongOpenHashSet,
        val loadedChunks: LongOpenHashSet,
    )

    private class PartialJob(
        val epoch: Long,
        val owner: SectionOcclusionGraph,
        val viewArea: ViewArea,
        val camera: OcclusionCameraSnapshot,
        val emptySections: LongOpenHashSet,
        val loadedChunks: LongOpenHashSet,
        val loadedExpectedChunks: LongArray,
    )

    private class CompletedFull(
        val epoch: Long,
        val graphGen: Long,
        val publishedGraph: Any,
        val expectedChunks: LongSet,
    )

    private class CompletedPartial(
        val epoch: Long,
        val graphGen: Long,
        val addedSections: List<SectionRenderDispatcher.RenderSection>,
        val expectedChunks: LongSet,
        val anyInFrustum: Boolean,
    )

    private val jobLock = Any()
    private val epoch = AtomicLong(1L)

    @Volatile
    private var activeFuture: CompletableFuture<Void>? = null

    // Guarded by jobLock
    private var workerScheduled = false
    private var pendingFull: FullJob? = null
    private var hasPendingPartial = false
    private var pendingPartialOwner: SectionOcclusionGraph? = null
    private var pendingPartialViewArea: ViewArea? = null
    private var pendingPartialCamera: OcclusionCameraSnapshot? = null
    private var pendingPartialEmpty: LongOpenHashSet? = null
    private var pendingPartialLoaded: LongOpenHashSet? = null
    private val pendingExpectedChunks = LongArrayList()

    // Lock-free MPMC queue fed by schedulePropagationFrom on any thread and drained by the worker.
    private val pendingPropagations = ConcurrentLinkedQueue<SectionRenderDispatcher.RenderSection>()

    // Worker-thread-only graph state (except volatile read for F3 debug overlay).
    @Volatile
    private var workerGraphState: Any? = null
    private var workerGraphGen = 0L

    // Worker -> render-thread handoff queues.
    private val completedFull = AtomicReference<CompletedFull?>(null)
    private val completedPartials = ConcurrentLinkedQueue<CompletedPartial>()

    // Render-thread-only state.
    private var activeGraphGen = 0L
    private var publishedExpectedChunks: LongSet = LongSets.EMPTY_SET
    private var cachedEmptySnapshot: LongOpenHashSet? = null
    private var cachedLoadedSnapshot: LongOpenHashSet? = null
    private var emptySnapshotDirty = true
    private var loadedSnapshotDirty = true

    fun markEmptyDirty() {
        emptySnapshotDirty = true
    }

    fun markLoadedDirty() {
        loadedSnapshotDirty = true
    }

    fun enqueuePropagation(section: SectionRenderDispatcher.RenderSection) {
        pendingPropagations.add(section)
    }

    fun expectedChunksSnapshot(): LongSet = publishedExpectedChunks

    fun getWorkerNode(section: SectionRenderDispatcher.RenderSection): SectionOcclusionGraph.Node? {
        val state = workerGraphState ?: return null
        val storage = bridge.getStorage(state)
        val map = bridge.getSectionToNodeMap(storage)
        return bridge.getNodeFromMap(map, section)
    }

    private fun snapshotEmpty(emptySections: LongOpenHashSet): LongOpenHashSet {
        var snap = cachedEmptySnapshot
        if (emptySnapshotDirty || snap == null) {
            snap = emptySections.clone()
            cachedEmptySnapshot = snap
            emptySnapshotDirty = false
        }
        return snap
    }

    private fun snapshotLoaded(loadedChunks: LongOpenHashSet): LongOpenHashSet {
        var snap = cachedLoadedSnapshot
        if (loadedSnapshotDirty || snap == null) {
            snap = loadedChunks.clone()
            cachedLoadedSnapshot = snap
            loadedSnapshotDirty = false
        }
        return snap
    }

    private fun snapshotCamera(cameraState: CameraRenderState): OcclusionCameraSnapshot {
        val frustum: Frustum? = cameraState.cullFrustum
        val offset = if (frustum != null) {
            Frustum(frustum).offsetToFullyIncludeCameraCube(8)
        } else {
            null
        }
        return OcclusionCameraSnapshot(
            blockPos = cameraState.blockPos.immutable(),
            pos = cameraState.pos,
            smartCull = cameraState.smartCull,
            offsetFrustum = offset,
        )
    }

    fun enqueueFull(
        owner: SectionOcclusionGraph,
        viewArea: ViewArea?,
        cameraState: CameraRenderState,
        emptySections: LongOpenHashSet,
        loadedChunks: LongOpenHashSet,
    ) {
        if (viewArea == null) return
        val camSnap = snapshotCamera(cameraState)
        val emptySnap = snapshotEmpty(emptySections)
        val loadedSnap = snapshotLoaded(loadedChunks)
        val job = FullJob(epoch.get(), owner, viewArea, camSnap, emptySnap, loadedSnap)

        val shouldLaunch: Boolean
        synchronized(jobLock) {
            pendingFull = job
            hasPendingPartial = false
            pendingExpectedChunks.clear()
            shouldLaunch = !workerScheduled
            if (shouldLaunch) {
                workerScheduled = true
            }
        }
        if (shouldLaunch) {
            launchWorker()
        }
    }

    fun enqueuePartial(
        owner: SectionOcclusionGraph,
        viewArea: ViewArea?,
        cameraState: CameraRenderState,
        loadedExpectedChunks: LongSet,
        emptySections: LongOpenHashSet,
        loadedChunks: LongOpenHashSet,
        currentGraphState: Any?,
    ) {
        if (viewArea == null) return
        if (currentGraphState != null && bridge === SectionOcclusionReflection) {
            val existingQueue = SectionOcclusionReflection.getSectionsToPropagateFrom(currentGraphState)
            while (true) {
                val sec = existingQueue.poll() ?: break
                pendingPropagations.add(sec)
            }
        }
        if (loadedExpectedChunks.isEmpty() && pendingPropagations.isEmpty()) {
            return
        }

        val camSnap = snapshotCamera(cameraState)
        val emptySnap = snapshotEmpty(emptySections)
        val loadedSnap = snapshotLoaded(loadedChunks)
        val curEpoch = epoch.get()

        val shouldLaunch: Boolean
        synchronized(jobLock) {
            if (curEpoch != epoch.get()) return
            pendingPartialOwner = owner
            pendingPartialViewArea = viewArea
            pendingPartialCamera = camSnap
            pendingPartialEmpty = emptySnap
            pendingPartialLoaded = loadedSnap
            if (!loadedExpectedChunks.isEmpty()) {
                val iter = loadedExpectedChunks.longIterator()
                while (iter.hasNext()) {
                    pendingExpectedChunks.add(iter.nextLong())
                }
            }
            hasPendingPartial = true
            shouldLaunch = !workerScheduled
            if (shouldLaunch) {
                workerScheduled = true
            }
        }
        if (shouldLaunch) {
            launchWorker()
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun drainCompleted(
        currentGraphRef: AtomicReference<*>,
        needsFrustumUpdate: AtomicBoolean,
    ) {
        val full = completedFull.getAndSet(null)
        if (full != null && full.epoch == epoch.get()) {
            activeGraphGen = full.graphGen
            (currentGraphRef as AtomicReference<Any>).set(full.publishedGraph)
            publishedExpectedChunks = full.expectedChunks
            needsFrustumUpdate.set(true)
            ChunkRenderPrepOptimizer.bumpVisibleSectionsEpoch()
        }

        val currentState = currentGraphRef.get()
        val renderOctree = if (currentState != null) {
            bridge.getSectionTree(bridge.getStorage(currentState))
        } else {
            null
        }

        var frustumDirty = false
        var anyAdded = false
        while (true) {
            val partial = completedPartials.poll() ?: break
            if (partial.epoch != epoch.get() || partial.graphGen != activeGraphGen) {
                continue
            }
            if (renderOctree != null) {
                val added = partial.addedSections
                for (i in 0 until added.size) {
                    if (renderOctree.add(added[i])) {
                        anyAdded = true
                    }
                }
            }
            publishedExpectedChunks = partial.expectedChunks
            if (partial.anyInFrustum) {
                frustumDirty = true
            }
        }
        if (frustumDirty) {
            needsFrustumUpdate.set(true)
        }
        if (anyAdded) {
            ChunkRenderPrepOptimizer.bumpVisibleSectionsEpoch()
        }
    }

    fun waitAndReset() {
        epoch.incrementAndGet()
        val future = activeFuture
        if (future != null) {
            try {
                future.get()
            } catch (e: Exception) {
                VulkanDiagnosticLog.recordWarning(source = "AsyncOcclusion", summary = "Async section occlusion update failed during reset", throwable = e)
            }
            activeFuture = null
        }
        synchronized(jobLock) {
            pendingFull = null
            hasPendingPartial = false
            pendingPartialOwner = null
            pendingPartialViewArea = null
            pendingPartialCamera = null
            pendingPartialEmpty = null
            pendingPartialLoaded = null
            pendingExpectedChunks.clear()
        }
        pendingPropagations.clear()
        completedFull.set(null)
        completedPartials.clear()
        workerGraphState = null
        workerGraphGen = 0L
        activeGraphGen = 0L
        publishedExpectedChunks = LongSets.EMPTY_SET
        cachedEmptySnapshot = null
        cachedLoadedSnapshot = null
        emptySnapshotDirty = true
        loadedSnapshotDirty = true
    }

    private fun launchWorker() {
        synchronized(jobLock) {
            activeFuture = CompletableFuture.runAsync(::runWorkerLoop, executor)
        }
    }

    internal fun awaitIdleForTesting() {
        activeFuture?.get(5, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun runWorkerLoop() {
        try {
            while (true) {
                var fullJob: FullJob? = null
                var partialJob: PartialJob? = null
                synchronized(jobLock) {
                    if (pendingFull != null) {
                        fullJob = pendingFull
                        pendingFull = null
                        hasPendingPartial = false
                        pendingExpectedChunks.clear()
                    } else if (hasPendingPartial) {
                        val owner = pendingPartialOwner
                        val va = pendingPartialViewArea
                        val cam = pendingPartialCamera
                        val emp = pendingPartialEmpty
                        val lod = pendingPartialLoaded
                        val expected = pendingExpectedChunks.toLongArray()
                        pendingExpectedChunks.clear()
                        hasPendingPartial = false
                        if (owner != null && va != null && cam != null && emp != null && lod != null) {
                            partialJob = PartialJob(epoch.get(), owner, va, cam, emp, lod, expected)
                        }
                    } else {
                        workerScheduled = false
                        return
                    }
                }

                try {
                    if (fullJob != null) {
                        executeFullJob(fullJob)
                    } else if (partialJob != null) {
                        executePartialJob(partialJob)
                    }
                } catch (t: Throwable) {
                    VulkanDiagnosticLog.recordWarning(source = "AsyncOcclusion", summary = "Async occlusion worker job failed cleanly: $t", throwable = t)
                }
            }
        } finally {
            synchronized(jobLock) {
                workerScheduled = false
            }
        }
    }

    private fun executeFullJob(job: FullJob) {
        if (job.epoch != epoch.get()) return
        val newWorkerGraph = bridge.createGraphState(job.viewArea)
        val publishedGraph = bridge.createGraphState(job.viewArea)
        val workerStorage = bridge.getStorage(newWorkerGraph)
        val nodeMap = bridge.getSectionToNodeMap(workerStorage)

        val queue: Queue<SectionOcclusionGraph.Node> = Queues.newArrayDeque()
        bridge.initializeQueueForFullUpdate(job.owner, job.camera.blockPos, queue)
        for (node in queue) {
            bridge.putNodeInMap(nodeMap, bridge.getNodeSection(node), node)
        }

        val discovered = ArrayList<SectionRenderDispatcher.RenderSection>(1024)
        bridge.runUpdates(
            job.owner,
            workerStorage,
            job.camera.pos,
            queue,
            job.camera.smartCull,
            Consumer { sec -> discovered.add(sec) },
            job.emptySections,
            job.loadedChunks,
        )

        val publishedOctree = bridge.getSectionTree(bridge.getStorage(publishedGraph))
        for (i in 0 until discovered.size) {
            publishedOctree.add(discovered[i])
        }

        val waitingKeys = bridge.getSectionsWaiting(workerStorage).keys
        val expectedSnapshot: LongSet = if (waitingKeys.isEmpty()) {
            LongSets.EMPTY_SET
        } else {
            LongOpenHashSet(waitingKeys)
        }

        if (job.epoch != epoch.get()) return
        workerGraphState = newWorkerGraph
        workerGraphGen++
        completedPartials.clear()
        completedFull.set(CompletedFull(job.epoch, workerGraphGen, publishedGraph, expectedSnapshot))
    }

    private fun executePartialJob(job: PartialJob) {
        if (job.epoch != epoch.get()) return
        val state = workerGraphState ?: return
        val storage = bridge.getStorage(state)
        val waitingMap = bridge.getSectionsWaiting(storage)
        val nodeMap = bridge.getSectionToNodeMap(storage)
        val queue: Queue<SectionOcclusionGraph.Node> = Queues.newArrayDeque()
        val seen = ReferenceOpenHashSet<SectionRenderDispatcher.RenderSection>()

        val loadedExpected = job.loadedExpectedChunks
        var waitingChanged = false
        for (i in 0 until loadedExpected.size) {
            val waitingSections = waitingMap.remove(loadedExpected[i])
            if (waitingSections != null) {
                waitingChanged = true
                val iter = waitingSections.longIterator()
                while (iter.hasNext()) {
                    val sec = bridge.getRenderSection(job.viewArea, iter.nextLong()) ?: continue
                    if (seen.add(sec)) {
                        val node = bridge.getNodeFromMap(nodeMap, sec)
                        if (node != null && bridge.getNodeSection(node) === sec) {
                            queue.add(node)
                        }
                    }
                }
            }
        }

        while (true) {
            val sec = pendingPropagations.poll() ?: break
            if (seen.add(sec)) {
                val node = bridge.getNodeFromMap(nodeMap, sec)
                if (node != null && bridge.getNodeSection(node) === sec) {
                    queue.add(node)
                }
            }
        }

        if (queue.isEmpty()) {
            if (waitingChanged && job.epoch == epoch.get()) {
                val expectedSnapshot: LongSet = if (waitingMap.isEmpty()) {
                    LongSets.EMPTY_SET
                } else {
                    LongOpenHashSet(waitingMap.keys)
                }
                completedPartials.add(
                    CompletedPartial(job.epoch, workerGraphGen, emptyList(), expectedSnapshot, false),
                )
            }
            return
        }

        val addedSections = ArrayList<SectionRenderDispatcher.RenderSection>(32)
        val frustum = job.camera.offsetFrustum
        var anyInFrustum = false
        bridge.runUpdates(
            job.owner,
            storage,
            job.camera.pos,
            queue,
            job.camera.smartCull,
            Consumer { sec ->
                addedSections.add(sec)
                if (!anyInFrustum && (frustum == null || frustum.isVisible(sec.boundingBox))) {
                    anyInFrustum = true
                }
            },
            job.emptySections,
            job.loadedChunks,
        )

        val expectedSnapshot: LongSet = if (waitingMap.isEmpty()) {
            LongSets.EMPTY_SET
        } else {
            LongOpenHashSet(waitingMap.keys)
        }
        if (job.epoch != epoch.get()) return
        completedPartials.add(
            CompletedPartial(job.epoch, workerGraphGen, addedSections, expectedSnapshot, anyInFrustum),
        )
    }
}
