package dev.vulkanturbo.mixin.client;

import dev.vulkanturbo.gui.VulkanTurboSettingsScreen;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a "VulkanTurbo Settings..." entry button to Minecraft's Video Settings list using
 * `OptionsList.addBig`, stacking cleanly alongside any other mods' buttons without overlap.
 */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin extends OptionsSubScreen {
    protected VideoSettingsScreenMixin(Screen lastScreen, Options options, Component title) {
        super(lastScreen, options, title);
    }

    @Inject(method = "addOptions", at = @At("HEAD"))
    private void vulkanturbo$addSettingsButton(CallbackInfo ci) {
        if (this.list != null) {
            this.list.addBig(VulkanTurboSettingsScreen.createSettingsButton(this, this.options));
        }
    }
}
