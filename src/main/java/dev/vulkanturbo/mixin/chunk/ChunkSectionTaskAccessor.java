package dev.vulkanturbo.mixin.chunk;

import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.atomic.AtomicBoolean;

/** Exposes `SectionTask.isCancelled` cleanly for zero-allocation queue polling. */
@Mixin(SectionRenderDispatcher.RenderSection.SectionTask.class)
public interface ChunkSectionTaskAccessor {
    @Accessor("isCancelled")
    AtomicBoolean vulkanturbo$isCancelled();
}
