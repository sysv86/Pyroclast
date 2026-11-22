package dev.vulkanturbo.benchagent;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodTransform;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/**
 * Minimal JDK 25 instrumentation agent that injects frame-boundary hooks into Mojang's
 * {@code GameRenderer.renderLevel()} / {@code Minecraft.renderFrame(boolean)} and optional
 * {@code -Dvt.bench.vktrace=true} driver-boundary counters into LWJGL Vulkan wrappers.
 *
 * Every mod class is ignored untouched so third-party mods run completely unmodified and opaque.
 */
public final class BenchAgent {
    private static final boolean VKTRACE = Boolean.parseBoolean(System.getProperty("vt.bench.vktrace", "false"));
    private static final String GAME_RENDERER = "net/minecraft/client/renderer/GameRenderer";
    private static final String MINECRAFT = "net/minecraft/client/Minecraft";
    private static final String VK10 = "org/lwjgl/vulkan/VK10";
    private static final String VK13 = "org/lwjgl/vulkan/VK13";
    private static final String EXT_MULTI_DRAW = "org/lwjgl/vulkan/EXTMultiDraw";
    private static final String KHR_DYN_RENDER = "org/lwjgl/vulkan/KHRDynamicRendering";
    private static final String KHR_SYNC2 = "org/lwjgl/vulkan/KHRSynchronization2";
    private static final String KHR_SWAPCHAIN = "org/lwjgl/vulkan/KHRSwapchain";

    private static final ClassDesc SAMPLER_DESC = ClassDesc.of("dev.vulkanturbo.benchagent.BenchSampler");
    private static final MethodTypeDesc VOID_DESC = MethodTypeDesc.of(ConstantDescs.CD_void);
    private static final MethodTypeDesc INT_VOID_DESC = MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int);
    private static final MethodTypeDesc INT_INT_VOID_DESC = MethodTypeDesc.of(
        ConstantDescs.CD_void,
        ConstantDescs.CD_int,
        ConstantDescs.CD_int
    );

    private BenchAgent() {
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        inst.addTransformer(new MojangFrameTransformer(), false);
    }

    private static final class MojangFrameTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(
            Module module,
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer
        ) {
            if (!isTargetClass(className)) {
                return null;
            }
            ClassFile cf = ClassFile.of();
            var model = cf.parse(classfileBuffer);
            return cf.transformClass(model, (ClassTransform) (cb, ce) -> {
                if (ce instanceof MethodModel mm) {
                    CodeTransform ct = selectCodeTransform(className, mm);
                    if (ct != null) {
                        cb.transformMethod(mm, MethodTransform.transformingCode(ct));
                        return;
                    }
                }
                cb.with(ce);
            });
        }
    }

    private static boolean isTargetClass(String className) {
        if (GAME_RENDERER.equals(className) || MINECRAFT.equals(className)) {
            return true;
        }
        if (!VKTRACE) {
            return false;
        }
        return VK10.equals(className)
            || VK13.equals(className)
            || EXT_MULTI_DRAW.equals(className)
            || KHR_DYN_RENDER.equals(className)
            || KHR_SYNC2.equals(className)
            || KHR_SWAPCHAIN.equals(className);
    }

    private static CodeTransform selectCodeTransform(String className, MethodModel mm) {
        String name = mm.methodName().stringValue();
        String desc = mm.methodType().stringValue();

        if (GAME_RENDERER.equals(className)) {
            return ("renderLevel".equals(name) && "()V".equals(desc)) ? new SimpleEntryTransform("onRenderLevel") : null;
        }
        if (MINECRAFT.equals(className)) {
            return ("renderFrame".equals(name) && "(Z)V".equals(desc)) ? new SimpleEntryTransform("onRenderFrame") : null;
        }
        if (VK10.equals(className)) {
            return switch (name) {
                case "vkCmdDrawIndexed" -> "(Lorg/lwjgl/vulkan/VkCommandBuffer;IIIII)V".equals(desc)
                    ? new TwoIntEntryTransform("onDrawIndexed", 1, 2) : null;
                case "vkCmdDrawIndexedIndirect" -> "(Lorg/lwjgl/vulkan/VkCommandBuffer;JJII)V".equals(desc)
                    ? new OneIntEntryTransform("onDrawIndexedIndirect", 5) : null;
                case "vkCmdDraw" -> "(Lorg/lwjgl/vulkan/VkCommandBuffer;IIII)V".equals(desc)
                    ? new TwoIntEntryTransform("onDraw", 1, 2) : null;
                case "nvkCmdPipelineBarrier" -> new SimpleEntryTransform("onBarrier");
                case "vkCmdBindPipeline" -> new SimpleEntryTransform("onBindPipeline");
                case "nvkCmdBindDescriptorSets" -> new SimpleEntryTransform("onBindDescriptorSets");
                case "nvkCmdBindVertexBuffers" -> new SimpleEntryTransform("onBindVertexBuffers");
                case "vkCmdBindIndexBuffer" -> new SimpleEntryTransform("onBindIndexBuffer");
                case "nvkCmdPushConstants" -> new SimpleEntryTransform("onPushConstants");
                case "nvkQueueSubmit" -> new SimpleEntryTransform("onQueueSubmit");
                case "nvkWaitForFences" -> new TimedOpTransform(0);
                default -> null;
            };
        }
        if (VK13.equals(className)) {
            return switch (name) {
                case "nvkCmdBeginRendering" -> new SimpleEntryTransform("onBeginRendering");
                case "nvkCmdPipelineBarrier2" -> new SimpleEntryTransform("onBarrier");
                case "nvkQueueSubmit2" -> new SimpleEntryTransform("onQueueSubmit");
                default -> null;
            };
        }
        if (EXT_MULTI_DRAW.equals(className)) {
            if ("nvkCmdDrawMultiIndexedEXT".equals(name)
                && "(Lorg/lwjgl/vulkan/VkCommandBuffer;IJIIIJ)V".equals(desc)) {
                return new OneIntEntryTransform("onMultiDrawIndexed", 1);
            }
            return null;
        }
        if (KHR_DYN_RENDER.equals(className)) {
            return "nvkCmdBeginRenderingKHR".equals(name) ? new SimpleEntryTransform("onBeginRendering") : null;
        }
        if (KHR_SYNC2.equals(className)) {
            return switch (name) {
                case "nvkCmdPipelineBarrier2KHR" -> new SimpleEntryTransform("onBarrier");
                case "nvkQueueSubmit2KHR" -> new SimpleEntryTransform("onQueueSubmit");
                default -> null;
            };
        }
        if (KHR_SWAPCHAIN.equals(className)) {
            return switch (name) {
                case "nvkAcquireNextImageKHR" -> new TimedOpTransform(1);
                case "nvkQueuePresentKHR" -> new TimedOpTransform(2);
                default -> null;
            };
        }
        return null;
    }

    private static final class SimpleEntryTransform implements CodeTransform {
        private final String method;

        private SimpleEntryTransform(String method) {
            this.method = method;
        }

        @Override
        public void atStart(CodeBuilder builder) {
            builder.invokestatic(SAMPLER_DESC, method, VOID_DESC);
        }

        @Override
        public void accept(CodeBuilder builder, CodeElement element) {
            builder.with(element);
        }
    }

    private static final class OneIntEntryTransform implements CodeTransform {
        private final String method;
        private final int slot;

        private OneIntEntryTransform(String method, int slot) {
            this.method = method;
            this.slot = slot;
        }

        @Override
        public void atStart(CodeBuilder builder) {
            builder.iload(slot);
            builder.invokestatic(SAMPLER_DESC, method, INT_VOID_DESC);
        }

        @Override
        public void accept(CodeBuilder builder, CodeElement element) {
            builder.with(element);
        }
    }

    private static final class TwoIntEntryTransform implements CodeTransform {
        private final String method;
        private final int slotA;
        private final int slotB;

        private TwoIntEntryTransform(String method, int slotA, int slotB) {
            this.method = method;
            this.slotA = slotA;
            this.slotB = slotB;
        }

        @Override
        public void atStart(CodeBuilder builder) {
            builder.iload(slotA);
            builder.iload(slotB);
            builder.invokestatic(SAMPLER_DESC, method, INT_INT_VOID_DESC);
        }

        @Override
        public void accept(CodeBuilder builder, CodeElement element) {
            builder.with(element);
        }
    }

    private static final class TimedOpTransform implements CodeTransform {
        private final int opId;

        private TimedOpTransform(int opId) {
            this.opId = opId;
        }

        @Override
        public void atStart(CodeBuilder builder) {
            builder.loadConstant(opId);
            builder.invokestatic(SAMPLER_DESC, "enterOp", INT_VOID_DESC);
        }

        @Override
        public void accept(CodeBuilder builder, CodeElement element) {
            if (element instanceof ReturnInstruction) {
                builder.loadConstant(opId);
                builder.invokestatic(SAMPLER_DESC, "exitOp", INT_VOID_DESC);
            }
            builder.with(element);
        }
    }
}
