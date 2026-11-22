package dev.vulkanturbo.core

import dev.vulkanturbo.VulkanTurbo
import org.lwjgl.system.MemoryUtil
import java.nio.ByteOrder

/**
 * Thread-safe normal packing and native memory writer for vertex buffers.
 *
 * Model and block rendering emits four vertices per quad that share an identical normal vector.
 * Per-instance normal caching lives on `BufferBuilderMixin` (since each `BufferBuilder` instance
 * is confined to a single thread, whereas multiple `BufferBuilder` instances run concurrently on
 * background chunk compile workers and the Render thread).
 *
 * Packs X/Y into a single 16-bit little-endian short (`memPutShort`) and Z into a single byte
 * (`memPutByte`), cutting native memory store calls from 3 to 2 per vertex without any shared
 * mutable static state.
 */
object NormalCache {
    private val NATIVE_LITTLE_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN

    @JvmStatic
    fun isEnabled(): Boolean = VulkanTurbo.normalCacheEnabled

    /**
     * Packs `(x, y, z)` in `[-1, 1]` into the low 24 bits of an `Int`:
     * bits `0..7 = bx`, bits `8..15 = by`, bits `16..23 = bz`.
     */
    @JvmStatic
    fun packNormal(x: Float, y: Float, z: Float): Int {
        val bx = packUnsigned(x)
        val by = packUnsigned(y)
        val bz = packUnsigned(z)
        return bx or (by shl 8) or (bz shl 16)
    }

    /**
     * Writes a pre-packed 24-bit normal at [address] using a 16-bit short store + 8-bit byte store
     * on little-endian architectures (or 3 byte stores on big-endian hosts).
     */
    @JvmStatic
    fun writePacked(address: Long, packedNormal: Int) {
        if (NATIVE_LITTLE_ENDIAN) {
            MemoryUtil.memPutShort(address, packedNormal.toShort())
            MemoryUtil.memPutByte(address + 2L, (packedNormal ushr 16).toByte())
        } else {
            MemoryUtil.memPutByte(address, packedNormal.toByte())
            MemoryUtil.memPutByte(address + 1L, (packedNormal ushr 8).toByte())
            MemoryUtil.memPutByte(address + 2L, (packedNormal ushr 16).toByte())
        }
    }

    /**
     * Stateless, thread-safe direct writer for [address].
     */
    @JvmStatic
    fun write(address: Long, x: Float, y: Float, z: Float) {
        writePacked(address, packNormal(x, y, z))
    }

    /**
     * Quantizes a single normal/tangent component in `[-1.0, 1.0]` to an unsigned 8-bit `SNORM` byte (`0..255`).
     */
    @JvmStatic
    fun packUnsigned(value: Float): Int {
        val clamped = if (value < -1.0f) -1.0f else if (value > 1.0f) 1.0f else value
        return (clamped * 127.0f).toInt() and 0xFF
    }
}
