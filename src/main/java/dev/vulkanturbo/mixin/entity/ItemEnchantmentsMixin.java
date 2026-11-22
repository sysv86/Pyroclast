package dev.vulkanturbo.mixin.entity;

import dev.vulkanturbo.VulkanTurbo;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.Holder;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collections;
import java.util.Set;

/**
 * Returns `Collections.emptySet()` from `ItemEnchantments.entrySet()` when empty, avoiding
 * `Collections$UnmodifiableSet` and fastutil `EntryIterator` allocations on unenchanted items.
 */
@Mixin(ItemEnchantments.class)
public abstract class ItemEnchantmentsMixin {
    @Shadow
    @Final
    private Object2IntOpenHashMap<Holder<Enchantment>> enchantments;

    @Inject(method = "entrySet", at = @At("HEAD"), cancellable = true)
    private void vulkanturbo$fastEmptyEntrySet(
        CallbackInfoReturnable<Set<Object2IntMap.Entry<Holder<Enchantment>>>> cir
    ) {
        if (VulkanTurbo.fastEntityRenderEnabled && this.enchantments.isEmpty()) {
            cir.setReturnValue(Collections.emptySet());
        }
    }
}
