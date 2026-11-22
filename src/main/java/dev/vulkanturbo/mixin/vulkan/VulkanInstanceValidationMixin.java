package dev.vulkanturbo.mixin.vulkan;

import com.mojang.renderpearl.backend.vulkan.VulkanInstance;
import dev.vulkanturbo.VulkanTurbo;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.EXTValidationFeatures;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkValidationFeaturesEXT;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Enables `VK_LAYER_KHRONOS_validation` and `VK_EXT_validation_features` (`Synchronization Validation`)
 * in development profiles when `-Dvt.vulkan.validation=true` or `VT_VULKAN_VALIDATION=1` is active.
 */
@Mixin(VulkanInstance.class)
public abstract class VulkanInstanceValidationMixin {
    @Shadow
    @Final
    private Set<String> enabledExtensions;

    @Shadow
    protected abstract Set<String> getSupportedInstanceExtensions();

    @Unique
    private static boolean vulkanturbo$isValidationRequested() {
        return Boolean.parseBoolean(System.getProperty("vt.vulkan.validation", "false"))
            || "1".equals(System.getenv("VT_VULKAN_VALIDATION"))
            || "true".equalsIgnoreCase(System.getenv("VT_VULKAN_VALIDATION"));
    }

    @ModifyVariable(
        method = "<init>",
        at = @At("HEAD"),
        ordinal = 1,
        argsOnly = true
    )
    private static boolean vulkanturbo$forceValidationInDevProfile(boolean validation) {
        if (vulkanturbo$isValidationRequested()) {
            return true;
        }
        return validation;
    }

    @Unique
    private PointerBuffer vulkanturbo$valExtBuf;

    @Unique
    private List<ByteBuffer> vulkanturbo$valExtNames;

    @Unique
    private IntBuffer vulkanturbo$valEnables;

    @Unique
    private VkValidationFeaturesEXT vulkanturbo$valFeatures;

    @ModifyArg(
        method = "<init>",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK12;vkCreateInstance(Lorg/lwjgl/vulkan/VkInstanceCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Lorg/lwjgl/PointerBuffer;)I"
        ),
        index = 0
    )
    private VkInstanceCreateInfo vulkanturbo$enableSyncValidationFeatures(VkInstanceCreateInfo instanceInfo) {
        if (!vulkanturbo$isValidationRequested()) {
            return instanceInfo;
        }
        Set<String> available = this.getSupportedInstanceExtensions();
        if (available.contains(EXTValidationFeatures.VK_EXT_VALIDATION_FEATURES_EXTENSION_NAME)) {
            this.enabledExtensions.add(EXTValidationFeatures.VK_EXT_VALIDATION_FEATURES_EXTENSION_NAME);
            PointerBuffer extBuf = MemoryUtil.memCallocPointer(this.enabledExtensions.size());
            List<ByteBuffer> nameBufs = new ArrayList<>(this.enabledExtensions.size());
            for (String name : this.enabledExtensions) {
                ByteBuffer utf8 = MemoryUtil.memUTF8(name);
                nameBufs.add(utf8);
                extBuf.put(utf8);
            }
            extBuf.flip();
            instanceInfo.ppEnabledExtensionNames(extBuf);

            IntBuffer enables = MemoryUtil.memAllocInt(1);
            enables.put(0, EXTValidationFeatures.VK_VALIDATION_FEATURE_ENABLE_SYNCHRONIZATION_VALIDATION_EXT);
            VkValidationFeaturesEXT valFeatures = VkValidationFeaturesEXT.calloc()
                .sType$Default()
                .pEnabledValidationFeatures(enables)
                .pNext(instanceInfo.pNext());
            instanceInfo.pNext(valFeatures.address());

            this.vulkanturbo$valExtBuf = extBuf;
            this.vulkanturbo$valExtNames = nameBufs;
            this.vulkanturbo$valEnables = enables;
            this.vulkanturbo$valFeatures = valFeatures;

            VulkanTurbo.INSTANCE.getLog().info(
                "VulkanTurbo: Enabled VK_LAYER_KHRONOS_validation + VK_VALIDATION_FEATURE_ENABLE_SYNCHRONIZATION_VALIDATION_EXT"
            );
        }
        return instanceInfo;
    }

    @Inject(
        method = "<init>",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK12;vkCreateInstance(Lorg/lwjgl/vulkan/VkInstanceCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Lorg/lwjgl/PointerBuffer;)I",
            shift = At.Shift.AFTER
        )
    )
    private void vulkanturbo$cleanupSyncValidationAllocations(CallbackInfo ci) {
        if (this.vulkanturbo$valFeatures != null) {
            this.vulkanturbo$valFeatures.free();
            this.vulkanturbo$valFeatures = null;
        }
        if (this.vulkanturbo$valEnables != null) {
            MemoryUtil.memFree(this.vulkanturbo$valEnables);
            this.vulkanturbo$valEnables = null;
        }
        if (this.vulkanturbo$valExtBuf != null) {
            MemoryUtil.memFree(this.vulkanturbo$valExtBuf);
            this.vulkanturbo$valExtBuf = null;
        }
        if (this.vulkanturbo$valExtNames != null) {
            for (ByteBuffer buf : this.vulkanturbo$valExtNames) {
                MemoryUtil.memFree(buf);
            }
            this.vulkanturbo$valExtNames = null;
        }
    }
}
