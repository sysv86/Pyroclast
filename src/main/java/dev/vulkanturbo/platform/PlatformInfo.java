package dev.vulkanturbo.platform;

/**
 * Environment facts, collected once at startup.
 *
 * Pure Java by design: avoids Minecraft, Fabric, and Kotlin dependencies so it remains safe to
 * call from mixin bodies and unit tests.
 */
public final class PlatformInfo {
    private PlatformInfo() {
    }

    public static String osName() {
        return System.getProperty("os.name", "unknown");
    }

    public static String osArch() {
        return System.getProperty("os.arch", "unknown");
    }

    public static String jvmVersion() {
        return System.getProperty("java.vm.version", "unknown");
    }

    /**
     * Wayland, X11, or "unknown". Minecraft 26.3 moved from GLFW to SDL, so the windowing backend is
     * now relevant when diagnosing presentation bugs.
     */
    public static String sessionType() {
        String session = System.getenv("XDG_SESSION_TYPE");
        if (session == null || session.isBlank()) {
            return "unknown";
        }
        return session.toLowerCase(java.util.Locale.ROOT);
    }

    public static boolean isLinux() {
        return osName().toLowerCase(java.util.Locale.ROOT).contains("linux");
    }
}
