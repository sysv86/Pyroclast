package dev.vulkanturbo.core

import com.mojang.blaze3d.vertex.PoseStack
import dev.vulkanturbo.api.shader.TurboShadersBridge
import com.mojang.blaze3d.vertex.QuadInstance
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.renderer.WeatherEffectRenderer
import net.minecraft.client.renderer.feature.ShadowFeatureRenderer
import net.minecraft.client.renderer.state.gui.ScreenArea
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.resources.model.geometry.BakedQuad
import net.minecraft.util.ARGB
import net.minecraft.util.Mth
import net.minecraft.world.attribute.EnvironmentAttribute
import net.minecraft.world.attribute.EnvironmentAttributeLayer
import net.minecraft.world.attribute.SpatialAttributeInterpolator
import net.minecraft.world.phys.Vec3
import org.joml.Matrix3x2fc
import org.joml.Matrix4f
import org.joml.Matrix4fc
import org.joml.Quaternionf
import org.joml.Vector2f
import org.joml.Vector3f
import org.lwjgl.system.MemoryUtil
import java.lang.Float.floatToRawIntBits

/**
 * Duck interface mixed into `BufferBuilder` for single-reservation batch emission of
 * `BLOCK` (28B or extended `TurboShaders` stride), `ENTITY` (36B), `PARTICLE` (28B),
 * `POSITION_TEX_[LIGHTMAP_]COLOR` (24B/28B), and `POSITION_COLOR` (16B) quads.
 */
interface DirectEntityBufferBuilder {
    fun `vulkanturbo$getVertexSize`(): Int
    fun `vulkanturbo$reserveBlockVertices`(vertexCount: Int): Long
    fun `vulkanturbo$reserveEntityVertices`(vertexCount: Int): Long
    fun `vulkanturbo$reserveParticleQuad`(): Long
    fun `vulkanturbo$reserveGlyphQuad`(): Long
    fun `vulkanturbo$glyphHasLightmap`(): Boolean
    fun `vulkanturbo$reserveGuiColoredQuad`(): Long
}

/**
 * Allocation-free math and iteration helpers for entity, block-entity, item, glyph, GUI, weather,
 * shadow, and particle rendering.
 */
object EntityRenderOptimizer {
    private const val INV_16 = 0.0625f
    private const val SHADOW_FULL_BRIGHT = 15728880
    private const val SHADOW_PACKED_UP_NORMAL = 127 shl 8 // (0, +1, 0) in SNORM8x3
    private const val MAX_POOLED_POSES = 16384
    private const val ENTITY_VERTEX_STRIDE = 36L
    private const val BLOCK_VERTEX_STRIDE = 28L
    private const val PARTICLE_VERTEX_STRIDE = 28L
    private const val GLYPH_GUI_STRIDE = 24L
    private const val GLYPH_WORLD_STRIDE = 28L
    private const val GUI_COLORED_STRIDE = 16L

    @Volatile
    private var renderThread: Thread? = null

    private val rtVec3Pos = Vector3f()
    private val rtVec3Normal = Vector3f()
    private val rtVec3QuadNormal = Vector3f()
    private val rtVec3QuadPos = Vector3f()
    private val rtVec2 = Vector2f()
    private val rtQuat = Quaternionf()
    private val rtGuiPoseMat = Matrix4f()

    private val tlVec3Pos = ThreadLocal.withInitial(::Vector3f)
    private val tlVec3Normal = ThreadLocal.withInitial(::Vector3f)
    private val tlVec3QuadNormal = ThreadLocal.withInitial(::Vector3f)
    private val tlVec3QuadPos = ThreadLocal.withInitial(::Vector3f)
    private val tlVec2 = ThreadLocal.withInitial(::Vector2f)
    private val tlQuat = ThreadLocal.withInitial(::Quaternionf)
    private val tlGuiPoseMat = ThreadLocal.withInitial(::Matrix4f)

    private var posePool = arrayOfNulls<PoseStack.Pose>(256)
    private var posePoolCursor = 0

    @JvmStatic
    fun markRenderThread() {
        renderThread = Thread.currentThread()
    }

    private fun isOnRenderThread(): Boolean {
        val current = Thread.currentThread()
        val rt = renderThread
        if (rt === current) return true
        if (rt == null && current.name == "Render thread") {
            renderThread = current
            return true
        }
        return false
    }

    @JvmStatic
    fun scratchPos(): Vector3f = if (isOnRenderThread()) rtVec3Pos else tlVec3Pos.get()

    @JvmStatic
    fun scratchNormal(): Vector3f = if (isOnRenderThread()) rtVec3Normal else tlVec3Normal.get()

    @JvmStatic
    fun scratchQuadNormal(): Vector3f = if (isOnRenderThread()) rtVec3QuadNormal else tlVec3QuadNormal.get()

    @JvmStatic
    fun scratchQuadPos(): Vector3f = if (isOnRenderThread()) rtVec3QuadPos else tlVec3QuadPos.get()

    @JvmStatic
    fun scratchVec2(): Vector2f = if (isOnRenderThread()) rtVec2 else tlVec2.get()

    @JvmStatic
    fun scratchQuat(): Quaternionf = if (isOnRenderThread()) rtQuat else tlQuat.get()

    @JvmStatic
    fun scratchGuiPoseMatrix(pose: Matrix3x2fc): Matrix4f {
        val mat = if (isOnRenderThread()) rtGuiPoseMat else tlGuiPoseMat.get()
        return mat.identity().mul(pose)
    }

    @JvmStatic
    fun resetPosePool() {
        posePoolCursor = 0
    }

    @JvmStatic
    fun borrowCopiedPose(template: PoseStack.Pose): PoseStack.Pose {
        if (!isOnRenderThread()) {
            return template.copy()
        }
        val idx = posePoolCursor
        if (idx >= MAX_POOLED_POSES) {
            return template.copy()
        }
        posePoolCursor = idx + 1
        var pool = posePool
        if (idx >= pool.size) {
            pool = pool.copyOf(pool.size * 2)
            posePool = pool
        }
        var cached = pool[idx]
        if (cached == null) {
            cached = template.copy()
            pool[idx] = cached
            return cached
        }
        cached.pose().set(template.pose())
        cached.normal().set(template.normal())
        return cached
    }

    @JvmStatic
    fun buildChildrenArray(children: Map<String, ModelPart>): Array<ModelPart> =
        children.values.toTypedArray()

    @JvmStatic
    fun buildCubesArray(cubes: List<ModelPart.Cube>): Array<ModelPart.Cube> =
        cubes.toTypedArray()

    @JvmStatic
    fun renderChildren(
        children: Array<ModelPart>,
        poseStack: PoseStack,
        consumer: VertexConsumer,
        light: Int,
        overlay: Int,
        color: Int,
    ) {
        for (i in children.indices) {
            children[i].render(poseStack, consumer, light, overlay, color)
        }
    }

    @JvmStatic
    fun compileCubes(
        cubes: Array<ModelPart.Cube>,
        pose: PoseStack.Pose,
        consumer: VertexConsumer,
        light: Int,
        overlay: Int,
        color: Int,
    ) {
        for (i in cubes.indices) {
            compileCubeFast(cubes[i], pose, consumer, light, overlay, color)
        }
    }

    @JvmStatic
    fun translateAndRotateFast(part: ModelPart, poseStack: PoseStack) {
        val last = poseStack.last()
        val px = part.x
        val py = part.y
        val pz = part.z
        if (px != 0.0f || py != 0.0f || pz != 0.0f) {
            last.translate(px * INV_16, py * INV_16, pz * INV_16)
        }
        val rx = part.xRot
        val ry = part.yRot
        val rz = part.zRot
        if (rx != 0.0f || ry != 0.0f || rz != 0.0f) {
            last.pose().rotateZYX(rz, ry, rx)
            last.normal().rotateZYX(rz, ry, rx)
        }
        val sx = part.xScale
        val sy = part.yScale
        val sz = part.zScale
        if (sx != 1.0f || sy != 1.0f || sz != 1.0f) {
            last.scale(sx, sy, sz)
        }
    }

    @JvmStatic
    fun compileCubeFast(
        cube: ModelPart.Cube,
        pose: PoseStack.Pose,
        consumer: VertexConsumer,
        light: Int,
        overlay: Int,
        color: Int,
    ) {
        val mat = pose.pose()
        val m00 = mat.m00() * INV_16; val m10 = mat.m10() * INV_16; val m20 = mat.m20() * INV_16; val m30 = mat.m30()
        val m01 = mat.m01() * INV_16; val m11 = mat.m11() * INV_16; val m21 = mat.m21() * INV_16; val m31 = mat.m31()
        val m02 = mat.m02() * INV_16; val m12 = mat.m12() * INV_16; val m22 = mat.m22() * INV_16; val m32 = mat.m32()

        val normMat = pose.normal()
        val n00 = normMat.m00(); val n10 = normMat.m10(); val n20 = normMat.m20()
        val n01 = normMat.m01(); val n11 = normMat.m11(); val n21 = normMat.m21()
        val n02 = normMat.m02(); val n12 = normMat.m12(); val n22 = normMat.m22()

        val polygons = cube.polygons
        val polyCount = polygons.size
        if (polyCount == 0) return

        if (consumer is DirectEntityBufferBuilder) {
            var totalVerts = 0
            for (i in 0 until polyCount) {
                totalVerts += polygons[i].vertices().size
            }
            if (totalVerts > 0) {
                var addr = consumer.`vulkanturbo$reserveEntityVertices`(totalVerts)
                if (addr != -1L) {
                    val abgr = ARGB.toABGR(color)
                    val overlayAndLight = ChunkPipelineOptimizer.packLongLE(overlay, light)
                    for (i in 0 until polyCount) {
                        val polygon = polygons[i]
                        val pNorm = polygon.normal()
                        val pnx = pNorm.x()
                        val pny = pNorm.y()
                        val pnz = pNorm.z()
                        val nx = Math.fma(n00, pnx, Math.fma(n10, pny, n20 * pnz))
                        val ny = Math.fma(n01, pnx, Math.fma(n11, pny, n21 * pnz))
                        val nz = Math.fma(n02, pnx, Math.fma(n12, pny, n22 * pnz))
                        val packedNormal = packEntityNormal(nx, ny, nz)

                        val vertices = polygon.vertices()
                        for (j in vertices.indices) {
                            val vertex = vertices[j]
                            val vx = vertex.x()
                            val vy = vertex.y()
                            val vz = vertex.z()

                            val tx = Math.fma(m00, vx, Math.fma(m10, vy, Math.fma(m20, vz, m30)))
                            val ty = Math.fma(m01, vx, Math.fma(m11, vy, Math.fma(m21, vz, m31)))
                            val tz = Math.fma(m02, vx, Math.fma(m12, vy, Math.fma(m22, vz, m32)))

                            MemoryUtil.memPutLong(addr, ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(tx), floatToRawIntBits(ty)))
                            MemoryUtil.memPutLong(addr + 8L, ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(tz), abgr))
                            MemoryUtil.memPutLong(addr + 16L, ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(vertex.u()), floatToRawIntBits(vertex.v())))
                            MemoryUtil.memPutLong(addr + 24L, overlayAndLight)
                            MemoryUtil.memPutInt(addr + 32L, packedNormal)
                            addr += ENTITY_VERTEX_STRIDE
                        }
                    }
                    if (TurboShadersBridge.entityRayTracingActive) {
                        TurboShadersBridge.onEntityVerticesEmitted(addr - totalVerts.toLong() * ENTITY_VERTEX_STRIDE, totalVerts, 36)
                    }
                    return
                }
            }
        }

        for (i in 0 until polyCount) {
            val polygon = polygons[i]
            val pNorm = polygon.normal()
            val pnx = pNorm.x()
            val pny = pNorm.y()
            val pnz = pNorm.z()
            val nx = Math.fma(n00, pnx, Math.fma(n10, pny, n20 * pnz))
            val ny = Math.fma(n01, pnx, Math.fma(n11, pny, n21 * pnz))
            val nz = Math.fma(n02, pnx, Math.fma(n12, pny, n22 * pnz))

            val vertices = polygon.vertices()
            for (j in vertices.indices) {
                val vertex = vertices[j]
                val vx = vertex.x()
                val vy = vertex.y()
                val vz = vertex.z()

                val tx = m00 * vx + m10 * vy + m20 * vz + m30
                val ty = m01 * vx + m11 * vy + m21 * vz + m31
                val tz = m02 * vx + m12 * vy + m22 * vz + m32

                consumer.addVertex(tx, ty, tz, color, vertex.u(), vertex.v(), overlay, light, nx, ny, nz)
            }
        }
    }

    @JvmStatic
    fun writeBakedQuadEntity(
        basePtr: Long,
        pose: PoseStack.Pose,
        quad: BakedQuad,
        instance: QuadInstance,
    ) {
        val mat = pose.pose()
        val m00 = mat.m00(); val m10 = mat.m10(); val m20 = mat.m20(); val m30 = mat.m30()
        val m01 = mat.m01(); val m11 = mat.m11(); val m21 = mat.m21(); val m31 = mat.m31()
        val m02 = mat.m02(); val m12 = mat.m12(); val m22 = mat.m22(); val m32 = mat.m32()

        val normMat = pose.normal()
        val qNorm = quad.direction().unitVec3f
        val qnx = qNorm.x()
        val qny = qNorm.y()
        val qnz = qNorm.z()
        val nx = Math.fma(normMat.m00(), qnx, Math.fma(normMat.m10(), qny, normMat.m20() * qnz))
        val ny = Math.fma(normMat.m01(), qnx, Math.fma(normMat.m11(), qny, normMat.m21() * qnz))
        val nz = Math.fma(normMat.m02(), qnx, Math.fma(normMat.m12(), qny, normMat.m22() * qnz))
        val packedNormal = packEntityNormal(nx, ny, nz)

        val overlay = instance.overlayCoords()
        val emission = quad.materialInfo().lightEmission()

        for (v in 0 until 4) {
            val ptr = basePtr + v.toLong() * ENTITY_VERTEX_STRIDE
            val pos = quad.position(v)
            val px = pos.x()
            val py = pos.y()
            val pz = pos.z()
            val tx = Math.fma(m00, px, Math.fma(m10, py, Math.fma(m20, pz, m30)))
            val ty = Math.fma(m01, px, Math.fma(m11, py, Math.fma(m21, pz, m31)))
            val tz = Math.fma(m02, px, Math.fma(m12, py, Math.fma(m22, pz, m32)))

            val abgr = ChunkPipelineOptimizer.argbToAbgr(instance.getColor(v))
            val uv = java.lang.Long.rotateLeft(quad.packedUV(v), 32)
            val light = instance.getLightCoordsWithEmission(v, emission)

            MemoryUtil.memPutLong(ptr, ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(tx), floatToRawIntBits(ty)))
            MemoryUtil.memPutLong(ptr + 8L, ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(tz), abgr))
            MemoryUtil.memPutLong(ptr + 16L, uv)
            MemoryUtil.memPutLong(ptr + 24L, ChunkPipelineOptimizer.packLongLE(overlay, light))
            MemoryUtil.memPutInt(ptr + 32L, packedNormal)
        }
        if (TurboShadersBridge.entityRayTracingActive) {
            TurboShadersBridge.onEntityVerticesEmitted(basePtr, 4, 36)
        }
    }

    @JvmStatic
    fun writeBakedQuadBlock(
        basePtr: Long,
        pose: PoseStack.Pose,
        quad: BakedQuad,
        instance: QuadInstance,
    ) {
        val mat = pose.pose()
        val m00 = mat.m00(); val m10 = mat.m10(); val m20 = mat.m20(); val m30 = mat.m30()
        val m01 = mat.m01(); val m11 = mat.m11(); val m21 = mat.m21(); val m31 = mat.m31()
        val m02 = mat.m02(); val m12 = mat.m12(); val m22 = mat.m22(); val m32 = mat.m32()
        val emission = quad.materialInfo().lightEmission()

        for (v in 0 until 4) {
            val ptr = basePtr + v.toLong() * BLOCK_VERTEX_STRIDE
            val pos = quad.position(v)
            val px = pos.x()
            val py = pos.y()
            val pz = pos.z()
            val tx = Math.fma(m00, px, Math.fma(m10, py, Math.fma(m20, pz, m30)))
            val ty = Math.fma(m01, px, Math.fma(m11, py, Math.fma(m21, pz, m31)))
            val tz = Math.fma(m02, px, Math.fma(m12, py, Math.fma(m22, pz, m32)))

            val abgr = ChunkPipelineOptimizer.argbToAbgr(instance.getColor(v))
            val uv = java.lang.Long.rotateLeft(quad.packedUV(v), 32)
            val light = instance.getLightCoordsWithEmission(v, emission)

            MemoryUtil.memPutLong(ptr, ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(tx), floatToRawIntBits(ty)))
            MemoryUtil.memPutLong(ptr + 8L, ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(tz), abgr))
            MemoryUtil.memPutLong(ptr + 16L, uv)
            MemoryUtil.memPutInt(ptr + 24L, light)
        }
        if (TurboShadersBridge.entityRayTracingActive) {
            TurboShadersBridge.onEntityVerticesEmitted(basePtr, 4, 28)
        }
    }

    internal fun packEntityNormal(nx: Float, ny: Float, nz: Float): Int {
        val bx = ((if (nx < -1.0f) -1.0f else if (nx > 1.0f) 1.0f else nx) * 127.0f).toInt() and 0xFF
        val by = ((if (ny < -1.0f) -1.0f else if (ny > 1.0f) 1.0f else ny) * 127.0f).toInt() and 0xFF
        val bz = ((if (nz < -1.0f) -1.0f else if (nz > 1.0f) 1.0f else nz) * 127.0f).toInt() and 0xFF
        return bx or (by shl 8) or (bz shl 16)
    }

    /**
     * Batches all `ShadowPiece` quads of a `ShadowFeatureRenderer.Submit` into single 144-byte
     * (`4 * 36B`) off-heap reservations.
     */
    @JvmStatic
    fun prepareEntityShadowFast(submit: ShadowFeatureRenderer.Submit, builder: VertexConsumer) {
        val pieces = submit.pieces()
        val pieceCount = pieces.size
        if (pieceCount == 0) return

        val pose = submit.pose()
        val m00 = pose.m00(); val m10 = pose.m10(); val m20 = pose.m20(); val m30 = pose.m30()
        val m01 = pose.m01(); val m11 = pose.m11(); val m21 = pose.m21(); val m31 = pose.m31()
        val m02 = pose.m02(); val m12 = pose.m12(); val m22 = pose.m22(); val m32 = pose.m32()
        val invTwoRadius = -0.5f / submit.radius()
        val overlayAndLight = ChunkPipelineOptimizer.packLongLE(OverlayTexture.NO_OVERLAY, SHADOW_FULL_BRIGHT)

        val direct = builder as? DirectEntityBufferBuilder
        for (i in 0 until pieceCount) {
            val piece = pieces[i]
            val aabb = piece.shapeBelow().bounds()
            val x01 = piece.relativeX() + aabb.minX.toFloat()
            val x11 = piece.relativeX() + aabb.maxX.toFloat()
            val y01 = piece.relativeY() + aabb.minY.toFloat()
            val z01 = piece.relativeZ() + aabb.minZ.toFloat()
            val z11 = piece.relativeZ() + aabb.maxZ.toFloat()
            val u0 = Math.fma(x01, invTwoRadius, 0.5f)
            val u1 = Math.fma(x11, invTwoRadius, 0.5f)
            val v0 = Math.fma(z01, invTwoRadius, 0.5f)
            val v1 = Math.fma(z11, invTwoRadius, 0.5f)
            val color = ARGB.white(piece.alpha())

            if (direct != null) {
                val basePtr = direct.`vulkanturbo$reserveEntityVertices`(4)
                if (basePtr != -1L) {
                    val abgr = ChunkPipelineOptimizer.argbToAbgr(color)
                    val yx = m10 * y01 + m30
                    val yy = m11 * y01 + m31
                    val yz = m12 * y01 + m32

                    // v0: (x01, y01, z01), (u0, v0)
                    val tx0 = Math.fma(m00, x01, Math.fma(m20, z01, yx))
                    val ty0 = Math.fma(m01, x01, Math.fma(m21, z01, yy))
                    val tz0 = Math.fma(m02, x01, Math.fma(m22, z01, yz))
                    MemoryUtil.memPutLong(basePtr, ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(tx0), floatToRawIntBits(ty0)))
                    MemoryUtil.memPutLong(basePtr + 8L, ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(tz0), abgr))
                    MemoryUtil.memPutLong(basePtr + 16L, ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(u0), floatToRawIntBits(v0)))
                    MemoryUtil.memPutLong(basePtr + 24L, overlayAndLight)
                    MemoryUtil.memPutInt(basePtr + 32L, SHADOW_PACKED_UP_NORMAL)

                    // v1: (x01, y01, z11), (u0, v1)
                    val p1 = basePtr + ENTITY_VERTEX_STRIDE
                    val tx1 = Math.fma(m00, x01, Math.fma(m20, z11, yx))
                    val ty1 = Math.fma(m01, x01, Math.fma(m21, z11, yy))
                    val tz1 = Math.fma(m02, x01, Math.fma(m22, z11, yz))
                    MemoryUtil.memPutLong(p1, ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(tx1), floatToRawIntBits(ty1)))
                    MemoryUtil.memPutLong(p1 + 8L, ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(tz1), abgr))
                    MemoryUtil.memPutLong(p1 + 16L, ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(u0), floatToRawIntBits(v1)))
                    MemoryUtil.memPutLong(p1 + 24L, overlayAndLight)
                    MemoryUtil.memPutInt(p1 + 32L, SHADOW_PACKED_UP_NORMAL)

                    // v2: (x11, y01, z11), (u1, v1)
                    val p2 = basePtr + ENTITY_VERTEX_STRIDE * 2L
                    val tx2 = Math.fma(m00, x11, Math.fma(m20, z11, yx))
                    val ty2 = Math.fma(m01, x11, Math.fma(m21, z11, yy))
                    val tz2 = Math.fma(m02, x11, Math.fma(m22, z11, yz))
                    MemoryUtil.memPutLong(p2, ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(tx2), floatToRawIntBits(ty2)))
                    MemoryUtil.memPutLong(p2 + 8L, ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(tz2), abgr))
                    MemoryUtil.memPutLong(p2 + 16L, ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(u1), floatToRawIntBits(v1)))
                    MemoryUtil.memPutLong(p2 + 24L, overlayAndLight)
                    MemoryUtil.memPutInt(p2 + 32L, SHADOW_PACKED_UP_NORMAL)

                    // v3: (x11, y01, z01), (u1, v0)
                    val p3 = basePtr + ENTITY_VERTEX_STRIDE * 3L
                    val tx3 = Math.fma(m00, x11, Math.fma(m20, z01, yx))
                    val ty3 = Math.fma(m01, x11, Math.fma(m21, z01, yy))
                    val tz3 = Math.fma(m02, x11, Math.fma(m22, z01, yz))
                    MemoryUtil.memPutLong(p3, ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(tx3), floatToRawIntBits(ty3)))
                    MemoryUtil.memPutLong(p3 + 8L, ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(tz3), abgr))
                    MemoryUtil.memPutLong(p3 + 16L, ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(u1), floatToRawIntBits(v0)))
                    MemoryUtil.memPutLong(p3 + 24L, overlayAndLight)
                    MemoryUtil.memPutInt(p3 + 32L, SHADOW_PACKED_UP_NORMAL)
                    continue
                }
            }

            shadowVertexFast(pose, builder, color, x01, y01, z01, u0, v0)
            shadowVertexFast(pose, builder, color, x01, y01, z11, u0, v1)
            shadowVertexFast(pose, builder, color, x11, y01, z11, u1, v1)
            shadowVertexFast(pose, builder, color, x11, y01, z01, u1, v0)
        }
    }

    @JvmStatic
    fun shadowVertexFast(
        pose: Matrix4fc,
        consumer: VertexConsumer,
        color: Int,
        x: Float,
        y: Float,
        z: Float,
        u: Float,
        v: Float,
    ) {
        val tx = pose.m00() * x + pose.m10() * y + pose.m20() * z + pose.m30()
        val ty = pose.m01() * x + pose.m11() * y + pose.m21() * z + pose.m31()
        val tz = pose.m02() * x + pose.m12() * y + pose.m22() * z + pose.m32()
        consumer.addVertex(tx, ty, tz, color, u, v, OverlayTexture.NO_OVERLAY, SHADOW_FULL_BRIGHT, 0.0f, 1.0f, 0.0f)
    }

    @JvmStatic
    fun renderRotatedQuadFast(
        consumer: VertexConsumer,
        x: Float,
        y: Float,
        z: Float,
        qx: Float,
        qy: Float,
        qz: Float,
        qw: Float,
        scale: Float,
        u0: Float,
        u1: Float,
        v0: Float,
        v1: Float,
        color: Int,
        light: Int,
    ) {
        val xx = qx * qx
        val yy = qy * qy
        val zz = qz * qz
        val ww = qw * qw
        val normSq = xx + yy + zz + ww
        val s = if (normSq > 1e-12f) scale / normSq else scale
        val ux = ww + xx - yy - zz
        val uy = 2.0f * (qx * qy + qw * qz)
        val uz = 2.0f * (qx * qz - qw * qy)
        val vx = 2.0f * (qx * qy - qw * qz)
        val vy = ww - xx + yy - zz
        val vz = 2.0f * (qy * qz + qw * qx)

        val d0x = (ux - vx) * s
        val d0y = (uy - vy) * s
        val d0z = (uz - vz) * s
        val d1x = (ux + vx) * s
        val d1y = (uy + vy) * s
        val d1z = (uz + vz) * s

        if (consumer is DirectEntityBufferBuilder) {
            val basePtr = consumer.`vulkanturbo$reserveParticleQuad`()
            if (basePtr != -1L) {
                writeParticleQuadAtAddress(
                    basePtr,
                    x + d0x, y + d0y, z + d0z,
                    x + d1x, y + d1y, z + d1z,
                    x - d0x, y - d0y, z - d0z,
                    x - d1x, y - d1y, z - d1z,
                    u0, u1, v0, v1, color, light,
                )
                return
            }
        }

        consumer.addVertex(x + d0x, y + d0y, z + d0z).setUv(u1, v1).setColor(color).setLight(light)
        consumer.addVertex(x + d1x, y + d1y, z + d1z).setUv(u1, v0).setColor(color).setLight(light)
        consumer.addVertex(x - d0x, y - d0y, z - d0z).setUv(u0, v0).setColor(color).setLight(light)
        consumer.addVertex(x - d1x, y - d1y, z - d1z).setUv(u0, v1).setColor(color).setLight(light)
    }

    internal fun writeParticleQuadAtAddress(
        basePtr: Long,
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        x3: Float, y3: Float, z3: Float,
        u0: Float, u1: Float, v0: Float, v1: Float,
        color: Int,
        light: Int,
    ) {
        val abgr = ChunkPipelineOptimizer.argbToAbgr(color)
        val uv11 = ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(u1), floatToRawIntBits(v1))
        val uv10 = ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(u1), floatToRawIntBits(v0))
        val uv00 = ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(u0), floatToRawIntBits(v0))
        val uv01 = ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(u0), floatToRawIntBits(v1))
        val colorAndLight = ChunkPipelineOptimizer.packLongLE(abgr, light)

        MemoryUtil.memPutLong(basePtr, ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(x0), floatToRawIntBits(y0)))
        MemoryUtil.memPutFloat(basePtr + 8L, z0)
        MemoryUtil.memPutLong(basePtr + 12L, uv11)
        MemoryUtil.memPutLong(basePtr + 20L, colorAndLight)

        val p1 = basePtr + PARTICLE_VERTEX_STRIDE
        MemoryUtil.memPutLong(p1, ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(x1), floatToRawIntBits(y1)))
        MemoryUtil.memPutFloat(p1 + 8L, z1)
        MemoryUtil.memPutLong(p1 + 12L, uv10)
        MemoryUtil.memPutLong(p1 + 20L, colorAndLight)

        val p2 = basePtr + PARTICLE_VERTEX_STRIDE * 2L
        MemoryUtil.memPutLong(p2, ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(x2), floatToRawIntBits(y2)))
        MemoryUtil.memPutFloat(p2 + 8L, z2)
        MemoryUtil.memPutLong(p2 + 12L, uv00)
        MemoryUtil.memPutLong(p2 + 20L, colorAndLight)

        val p3 = basePtr + PARTICLE_VERTEX_STRIDE * 3L
        MemoryUtil.memPutLong(p3, ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(x3), floatToRawIntBits(y3)))
        MemoryUtil.memPutFloat(p3 + 8L, z3)
        MemoryUtil.memPutLong(p3 + 12L, uv01)
        MemoryUtil.memPutLong(p3 + 20L, colorAndLight)
    }

    @JvmStatic
    fun prepareWeatherColumnsFast(
        builder: VertexConsumer,
        columns: List<WeatherEffectRenderer.ColumnInstance>,
        cameraPos: Vec3,
        maxAlpha: Float,
        radius: Int,
        intensity: Float,
        columnSizeX: FloatArray,
        columnSizeZ: FloatArray,
    ) {
        val count = columns.size
        if (count == 0) return
        val camX = cameraPos.x
        val camY = cameraPos.y
        val camZ = cameraPos.z
        val camBlockX = Mth.floor(camX)
        val camBlockZ = Mth.floor(camZ)
        val radiusSq = (radius * radius).toFloat()
        val direct = builder as? DirectEntityBufferBuilder

        for (i in 0 until count) {
            val column = columns[i]
            val xRel = (column.x().toDouble() + 0.5 - camX).toFloat()
            val zRel = (column.z().toDouble() + 0.5 - camZ).toFloat()
            val distSq = Mth.lengthSquared(xRel, zRel)
            val alpha = Mth.lerp(Math.min(distSq / radiusSq, 1.0f), maxAlpha, 0.5f) * intensity
            val color = ARGB.white(alpha)

            val idx = (column.z() - camBlockZ + 16) * 32 + (column.x() - camBlockX + 16)
            val sx = columnSizeX[idx] * 0.5f
            val sz = columnSizeZ[idx] * 0.5f
            val x0 = xRel - sx
            val x1 = xRel + sx
            val y0 = (column.topY().toDouble() - camY).toFloat()
            val y1 = (column.bottomY().toDouble() - camY).toFloat()
            val z0 = zRel - sz
            val z1 = zRel + sz
            val u0 = column.uOffset()
            val u1 = 1.0f + column.uOffset()
            val v0 = column.bottomY().toFloat() * 0.25f + column.vOffset()
            val v1 = column.topY().toFloat() * 0.25f + column.vOffset()
            val light = column.lightCoords()

            if (direct != null) {
                val basePtr = direct.`vulkanturbo$reserveParticleQuad`()
                if (basePtr != -1L) {
                    writeWeatherQuadAtAddress(
                        basePtr,
                        x0, y0, z0, u0, v0,
                        x1, y0, z1, u1, v0,
                        x1, y1, z1, u1, v1,
                        x0, y1, z0, u0, v1,
                        color,
                        light,
                    )
                    continue
                }
            }

            builder.addVertex(x0, y0, z0).setUv(u0, v0).setColor(color).setLight(light)
            builder.addVertex(x1, y0, z1).setUv(u1, v0).setColor(color).setLight(light)
            builder.addVertex(x1, y1, z1).setUv(u1, v1).setColor(color).setLight(light)
            builder.addVertex(x0, y1, z0).setUv(u0, v1).setColor(color).setLight(light)
        }
    }

    internal fun writeWeatherQuadAtAddress(
        basePtr: Long,
        x0: Float, y0: Float, z0: Float, u0: Float, v0: Float,
        x1: Float, y1: Float, z1: Float, u1: Float, v1: Float,
        x2: Float, y2: Float, z2: Float, u2: Float, v2: Float,
        x3: Float, y3: Float, z3: Float, u3: Float, v3: Float,
        color: Int,
        light: Int,
    ) {
        val colorAndLight = ChunkPipelineOptimizer.packLongLE(ChunkPipelineOptimizer.argbToAbgr(color), light)

        MemoryUtil.memPutLong(basePtr, ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(x0), floatToRawIntBits(y0)))
        MemoryUtil.memPutFloat(basePtr + 8L, z0)
        MemoryUtil.memPutLong(basePtr + 12L, ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(u0), floatToRawIntBits(v0)))
        MemoryUtil.memPutLong(basePtr + 20L, colorAndLight)

        val p1 = basePtr + PARTICLE_VERTEX_STRIDE
        MemoryUtil.memPutLong(p1, ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(x1), floatToRawIntBits(y1)))
        MemoryUtil.memPutFloat(p1 + 8L, z1)
        MemoryUtil.memPutLong(p1 + 12L, ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(u1), floatToRawIntBits(v1)))
        MemoryUtil.memPutLong(p1 + 20L, colorAndLight)

        val p2 = basePtr + PARTICLE_VERTEX_STRIDE * 2L
        MemoryUtil.memPutLong(p2, ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(x2), floatToRawIntBits(y2)))
        MemoryUtil.memPutFloat(p2 + 8L, z2)
        MemoryUtil.memPutLong(p2 + 12L, ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(u2), floatToRawIntBits(v2)))
        MemoryUtil.memPutLong(p2 + 20L, colorAndLight)

        val p3 = basePtr + PARTICLE_VERTEX_STRIDE * 3L
        MemoryUtil.memPutLong(p3, ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(x3), floatToRawIntBits(y3)))
        MemoryUtil.memPutFloat(p3 + 8L, z3)
        MemoryUtil.memPutLong(p3 + 12L, ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(u3), floatToRawIntBits(v3)))
        MemoryUtil.memPutLong(p3 + 20L, colorAndLight)
    }

    @JvmStatic
    fun emitGlyphQuadFast(
        consumer: VertexConsumer,
        pose: Matrix4fc,
        lxTop: Float, y0: Float,
        lxBot: Float, y1: Float,
        rxBot: Float, y2: Float,
        rxTop: Float, y3: Float,
        z: Float,
        u0: Float, u1: Float,
        v0: Float, v1: Float,
        color: Int,
        light: Int,
    ) {
        if (consumer is DirectEntityBufferBuilder) {
            val basePtr = consumer.`vulkanturbo$reserveGlyphQuad`()
            if (basePtr != -1L) {
                val hasLightmap = consumer.`vulkanturbo$glyphHasLightmap`()
                val m00 = pose.m00(); val m10 = pose.m10(); val m20 = pose.m20(); val m30 = pose.m30()
                val m01 = pose.m01(); val m11 = pose.m11(); val m21 = pose.m21(); val m31 = pose.m31()
                val m02 = pose.m02(); val m12 = pose.m12(); val m22 = pose.m22(); val m32 = pose.m32()

                val zx = Math.fma(m20, z, m30)
                val zy = Math.fma(m21, z, m31)
                val zz = Math.fma(m22, z, m32)

                val tx0 = Math.fma(m00, lxTop, Math.fma(m10, y0, zx))
                val ty0 = Math.fma(m01, lxTop, Math.fma(m11, y0, zy))
                val tz0 = Math.fma(m02, lxTop, Math.fma(m12, y0, zz))

                val tx1 = Math.fma(m00, lxBot, Math.fma(m10, y1, zx))
                val ty1 = Math.fma(m01, lxBot, Math.fma(m11, y1, zy))
                val tz1 = Math.fma(m02, lxBot, Math.fma(m12, y1, zz))

                val tx2 = Math.fma(m00, rxBot, Math.fma(m10, y2, zx))
                val ty2 = Math.fma(m01, rxBot, Math.fma(m11, y2, zy))
                val tz2 = Math.fma(m02, rxBot, Math.fma(m12, y2, zz))

                val tx3 = Math.fma(m00, rxTop, Math.fma(m10, y3, zx))
                val ty3 = Math.fma(m01, rxTop, Math.fma(m11, y3, zy))
                val tz3 = Math.fma(m02, rxTop, Math.fma(m12, y3, zz))

                writeGlyphQuadAtAddress(
                    basePtr,
                    hasLightmap,
                    tx0, ty0, tz0,
                    tx1, ty1, tz1,
                    tx2, ty2, tz2,
                    tx3, ty3, tz3,
                    u0, u1, v0, v1,
                    color, light,
                )
                return
            }
        }

        consumer.addVertex(pose, lxTop, y0, z).setColor(color).setUv(u0, v0).setLight(light)
        consumer.addVertex(pose, lxBot, y1, z).setColor(color).setUv(u0, v1).setLight(light)
        consumer.addVertex(pose, rxBot, y2, z).setColor(color).setUv(u1, v1).setLight(light)
        consumer.addVertex(pose, rxTop, y3, z).setColor(color).setUv(u1, v0).setLight(light)
    }

    internal fun writeGlyphQuadAtAddress(
        basePtr: Long,
        hasLightmap: Boolean,
        tx0: Float, ty0: Float, tz0: Float,
        tx1: Float, ty1: Float, tz1: Float,
        tx2: Float, ty2: Float, tz2: Float,
        tx3: Float, ty3: Float, tz3: Float,
        u0: Float, u1: Float, v0: Float, v1: Float,
        color: Int,
        light: Int,
    ) {
        val abgr = ChunkPipelineOptimizer.argbToAbgr(color)
        val uv00 = ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(u0), floatToRawIntBits(v0))
        val uv01 = ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(u0), floatToRawIntBits(v1))
        val uv11 = ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(u1), floatToRawIntBits(v1))
        val uv10 = ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(u1), floatToRawIntBits(v0))

        if (hasLightmap) {
            val lightAndColor = ChunkPipelineOptimizer.packLongLE(light, abgr)

            MemoryUtil.memPutLong(basePtr, ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(tx0), floatToRawIntBits(ty0)))
            MemoryUtil.memPutFloat(basePtr + 8L, tz0)
            MemoryUtil.memPutLong(basePtr + 12L, uv00)
            MemoryUtil.memPutLong(basePtr + 20L, lightAndColor)

            val p1 = basePtr + GLYPH_WORLD_STRIDE
            MemoryUtil.memPutLong(p1, ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(tx1), floatToRawIntBits(ty1)))
            MemoryUtil.memPutFloat(p1 + 8L, tz1)
            MemoryUtil.memPutLong(p1 + 12L, uv01)
            MemoryUtil.memPutLong(p1 + 20L, lightAndColor)

            val p2 = basePtr + GLYPH_WORLD_STRIDE * 2L
            MemoryUtil.memPutLong(p2, ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(tx2), floatToRawIntBits(ty2)))
            MemoryUtil.memPutFloat(p2 + 8L, tz2)
            MemoryUtil.memPutLong(p2 + 12L, uv11)
            MemoryUtil.memPutLong(p2 + 20L, lightAndColor)

            val p3 = basePtr + GLYPH_WORLD_STRIDE * 3L
            MemoryUtil.memPutLong(p3, ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(tx3), floatToRawIntBits(ty3)))
            MemoryUtil.memPutFloat(p3 + 8L, tz3)
            MemoryUtil.memPutLong(p3 + 12L, uv10)
            MemoryUtil.memPutLong(p3 + 20L, lightAndColor)
        } else {
            MemoryUtil.memPutLong(basePtr, ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(tx0), floatToRawIntBits(ty0)))
            MemoryUtil.memPutFloat(basePtr + 8L, tz0)
            MemoryUtil.memPutLong(basePtr + 12L, uv00)
            MemoryUtil.memPutInt(basePtr + 20L, abgr)

            val p1 = basePtr + GLYPH_GUI_STRIDE
            MemoryUtil.memPutLong(p1, ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(tx1), floatToRawIntBits(ty1)))
            MemoryUtil.memPutFloat(p1 + 8L, tz1)
            MemoryUtil.memPutLong(p1 + 12L, uv01)
            MemoryUtil.memPutInt(p1 + 20L, abgr)

            val p2 = basePtr + GLYPH_GUI_STRIDE * 2L
            MemoryUtil.memPutLong(p2, ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(tx2), floatToRawIntBits(ty2)))
            MemoryUtil.memPutFloat(p2 + 8L, tz2)
            MemoryUtil.memPutLong(p2 + 12L, uv11)
            MemoryUtil.memPutInt(p2 + 20L, abgr)

            val p3 = basePtr + GLYPH_GUI_STRIDE * 3L
            MemoryUtil.memPutLong(p3, ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(tx3), floatToRawIntBits(ty3)))
            MemoryUtil.memPutFloat(p3 + 8L, tz3)
            MemoryUtil.memPutLong(p3 + 12L, uv10)
            MemoryUtil.memPutInt(p3 + 20L, abgr)
        }
    }

    @JvmStatic
    fun emitGuiBlitQuadFast(
        consumer: VertexConsumer,
        pose: Matrix3x2fc,
        x0: Int, y0: Int,
        x1: Int, y1: Int,
        u0: Float, u1: Float,
        v0: Float, v1: Float,
        color: Int,
    ) {
        val fx0 = x0.toFloat()
        val fy0 = y0.toFloat()
        val fx1 = x1.toFloat()
        val fy1 = y1.toFloat()
        val m00 = pose.m00(); val m10 = pose.m10(); val m20 = pose.m20()
        val m01 = pose.m01(); val m11 = pose.m11(); val m21 = pose.m21()

        val tx0 = Math.fma(m00, fx0, Math.fma(m10, fy0, m20))
        val ty0 = Math.fma(m01, fx0, Math.fma(m11, fy0, m21))
        val tx1 = Math.fma(m00, fx0, Math.fma(m10, fy1, m20))
        val ty1 = Math.fma(m01, fx0, Math.fma(m11, fy1, m21))
        val tx2 = Math.fma(m00, fx1, Math.fma(m10, fy1, m20))
        val ty2 = Math.fma(m01, fx1, Math.fma(m11, fy1, m21))
        val tx3 = Math.fma(m00, fx1, Math.fma(m10, fy0, m20))
        val ty3 = Math.fma(m01, fx1, Math.fma(m11, fy0, m21))

        if (consumer is DirectEntityBufferBuilder && !consumer.`vulkanturbo$glyphHasLightmap`()) {
            val basePtr = consumer.`vulkanturbo$reserveGlyphQuad`()
            if (basePtr != -1L) {
                writeGlyphQuadAtAddress(
                    basePtr,
                    false,
                    tx0, ty0, 0.0f,
                    tx1, ty1, 0.0f,
                    tx2, ty2, 0.0f,
                    tx3, ty3, 0.0f,
                    u0, u1, v0, v1,
                    color, 0,
                )
                return
            }
        }

        consumer.addVertex(tx0, ty0, 0.0f).setUv(u0, v0).setColor(color)
        consumer.addVertex(tx1, ty1, 0.0f).setUv(u0, v1).setColor(color)
        consumer.addVertex(tx2, ty2, 0.0f).setUv(u1, v1).setColor(color)
        consumer.addVertex(tx3, ty3, 0.0f).setUv(u1, v0).setColor(color)
    }

    @JvmStatic
    fun emitGuiColoredRectQuadFast(
        consumer: VertexConsumer,
        pose: Matrix3x2fc,
        x0: Int, y0: Int,
        x1: Int, y1: Int,
        col1: Int,
        col2: Int,
    ) {
        val fx0 = x0.toFloat()
        val fy0 = y0.toFloat()
        val fx1 = x1.toFloat()
        val fy1 = y1.toFloat()
        val m00 = pose.m00(); val m10 = pose.m10(); val m20 = pose.m20()
        val m01 = pose.m01(); val m11 = pose.m11(); val m21 = pose.m21()

        val tx0 = Math.fma(m00, fx0, Math.fma(m10, fy0, m20))
        val ty0 = Math.fma(m01, fx0, Math.fma(m11, fy0, m21))
        val tx1 = Math.fma(m00, fx0, Math.fma(m10, fy1, m20))
        val ty1 = Math.fma(m01, fx0, Math.fma(m11, fy1, m21))
        val tx2 = Math.fma(m00, fx1, Math.fma(m10, fy1, m20))
        val ty2 = Math.fma(m01, fx1, Math.fma(m11, fy1, m21))
        val tx3 = Math.fma(m00, fx1, Math.fma(m10, fy0, m20))
        val ty3 = Math.fma(m01, fx1, Math.fma(m11, fy0, m21))

        if (consumer is DirectEntityBufferBuilder) {
            val basePtr = consumer.`vulkanturbo$reserveGuiColoredQuad`()
            if (basePtr != -1L) {
                writeGuiColoredQuadAtAddress(basePtr, tx0, ty0, tx1, ty1, tx2, ty2, tx3, ty3, col1, col2)
                return
            }
        }

        consumer.addVertex(tx0, ty0, 0.0f).setColor(col1)
        consumer.addVertex(tx1, ty1, 0.0f).setColor(col2)
        consumer.addVertex(tx2, ty2, 0.0f).setColor(col2)
        consumer.addVertex(tx3, ty3, 0.0f).setColor(col1)
    }

    internal fun writeGuiColoredQuadAtAddress(
        basePtr: Long,
        tx0: Float, ty0: Float,
        tx1: Float, ty1: Float,
        tx2: Float, ty2: Float,
        tx3: Float, ty3: Float,
        col1: Int,
        col2: Int,
    ) {
        val zAndCol1 = ChunkPipelineOptimizer.packLongLE(0, ChunkPipelineOptimizer.argbToAbgr(col1))
        val zAndCol2 = ChunkPipelineOptimizer.packLongLE(0, ChunkPipelineOptimizer.argbToAbgr(col2))

        MemoryUtil.memPutLong(basePtr, ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(tx0), floatToRawIntBits(ty0)))
        MemoryUtil.memPutLong(basePtr + 8L, zAndCol1)

        val p1 = basePtr + GUI_COLORED_STRIDE
        MemoryUtil.memPutLong(p1, ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(tx1), floatToRawIntBits(ty1)))
        MemoryUtil.memPutLong(p1 + 8L, zAndCol2)

        val p2 = basePtr + GUI_COLORED_STRIDE * 2L
        MemoryUtil.memPutLong(p2, ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(tx2), floatToRawIntBits(ty2)))
        MemoryUtil.memPutLong(p2 + 8L, zAndCol2)

        val p3 = basePtr + GUI_COLORED_STRIDE * 3L
        MemoryUtil.memPutLong(p3, ChunkPipelineOptimizer.packLongLE(floatToRawIntBits(tx3), floatToRawIntBits(ty3)))
        MemoryUtil.memPutLong(p3 + 8L, zAndCol1)
    }

    @JvmStatic
    fun evaluatePositionalAttributesFast(
        attribute: EnvironmentAttribute<Any>,
        baseValue: Any,
        layers: Array<EnvironmentAttributeLayer<Any>>,
        cacheTickId: Int,
        pos: Vec3?,
        interpolator: SpatialAttributeInterpolator?,
    ): Any {
        var value = baseValue
        for (i in layers.indices) {
            when (val layer = layers[i]) {
                is EnvironmentAttributeLayer.Constant<Any> -> value = layer.applyConstant(value)
                is EnvironmentAttributeLayer.TimeBased<Any> -> value = layer.applyTimeBased(value, cacheTickId)
                is EnvironmentAttributeLayer.Positional<Any> -> value = layer.applyPositional(
                    value,
                    pos!!,
                    interpolator,
                )
            }
        }
        return attribute.sanitizeValue(value)
    }

    @JvmStatic
    fun guiHasIntersectionFast(rect: ScreenRectangle, list: List<ScreenArea>?): Boolean {
        if (list == null) return false
        val rLeft = rect.left()
        val rRight = rect.right()
        val rTop = rect.top()
        val rBottom = rect.bottom()
        for (i in 0 until list.size) {
            val bounds = list[i].bounds() ?: continue
            if (bounds.left() < rRight && bounds.right() > rLeft && bounds.top() < rBottom && bounds.bottom() > rTop) {
                return true
            }
        }
        return false
    }
}
