package dev.vulkanturbo.mixin.client;

import dev.vulkanturbo.diagnostics.VulkanDiagnosticLog;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.CrashReport;
import net.minecraft.ReportType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Injects Pyroclast (and any registered shader extension) diagnostic details into vanilla
 * Minecraft {@link CrashReport} output and mirrors crash logs into
 * {@code <gameDir>/pyroclast/crash-logs/}.
 */
@Mixin(CrashReport.class)
public class CrashReportMixin {
    @Inject(method = "getDetails(Ljava/lang/StringBuilder;)V", at = @At("TAIL"), require = 0)
    private void vulkanturbo$appendPyroclastDiagnostics(StringBuilder details, CallbackInfo ci) {
        try {
            if (details.indexOf("-- Pyroclast (VulkanTurbo) Diagnostics --") >= 0) {
                return;
            }
            String section = VulkanDiagnosticLog.buildCrashDiagnosticSection();
            if (section != null && !section.isEmpty()) {
                details.append("\n\n").append(section).append('\n');
            }
        } catch (Throwable ignored) {
            // Never interfere with vanilla crash report formatting.
        }
    }

    @Inject(
        method = "saveToFile(Ljava/nio/file/Path;Lnet/minecraft/ReportType;Ljava/util/List;)Z",
        at = @At("HEAD"),
        require = 0
    )
    private void vulkanturbo$dumpPyroclastCrashLog(
        Path path,
        ReportType type,
        List<String> extraComments,
        CallbackInfoReturnable<Boolean> cir
    ) {
        try {
            CrashReport self = (CrashReport) (Object) this;
            String title = self.getTitle();
            Throwable cause = self.getException();
            String reportDetails = null;
            try {
                reportDetails = self.getDetails();
            } catch (Throwable ignored) {
            }
            String reason = (title != null && !title.isEmpty())
                ? "Minecraft CrashReport: " + title
                : "Minecraft CrashReport";
            VulkanDiagnosticLog.dumpCrashLog(reason, reportDetails, cause);
        } catch (Throwable ignored) {
            // Never interfere with vanilla crash report saving.
        }
    }
}
