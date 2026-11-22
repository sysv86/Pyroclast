# AGENTS.md

Operating rules for AI agents and human contributors working in **Pyroclast** (`VulkanTurbo` / `TEPHRA`). Read this file in full before modifying code, shaders, or build files.

## 1. Mission & Scope

**Pyroclast** (`VulkanTurbo`) is an independent, clean-room Vulkan performance engine for **Minecraft: Java Edition 26.3+** (Fabric, RenderPearl Vulkan backend `com.mojang.renderpearl.backend.vulkan.*`), licensed under **Apache-2.0** ([`LICENSE`](LICENSE)).

It is **not** a fork, port, wrapper, or reimplementation of any existing mod. Every line in this repository is written from first principles against Mojang's shipped API surface, the Vulkan specification, and reproducible profiling.

## 2. Clean-Room Policy & Competitor Restrictions

Copying, transcribing, paraphrasing, or decompiling another Minecraft rendering or optimization mod is **strictly forbidden**.

| Project | License | Why it cannot be copied |
| :--- | :--- | :--- |
| **Sodium** (`CaffeineMC/sodium`) | **PolyForm Shield 1.0.0** (`LicenseRef-Polyform-Shield-1.0.0`) | Non-FOSS, non-compete source-available license. Explicitly prohibits using any portion of its source code or binaries in a competing Minecraft rendering/performance product, and is incompatible with Apache-2.0. |
| **Iris** (`IrisShaders/Iris`) | **LGPL-3.0** (`glsl-transformer` is **AGPL-3.0**) | Copyleft / strong-copyleft dependencies are incompatible with Apache-2.0. |
| **VulkanMod** / **Embeddium** | **LGPL-3.0** | Copyleft license; incompatible with Apache-2.0. |
| **OptiFine** / **Nvidium** | Proprietary / source-available | Closed or non-permissive terms; reverse-engineering and copying are prohibited. |

Rules:
- Never copy, port, or "rewrite from memory" code, shaders, data structures, or algorithms from any mod above or any blog/gist/AI snippet derived from them.
- Never decompile competitor JARs or commit decompiled output anywhere in the repository.
- Never depend on, link against, or shade competitor artifacts or third-party config libraries (e.g. Cloth Config, Sodium's config API).
- If a problem appears to require reading a competitor's implementation, **stop and report it** instead.

## 3. Mojang Code: Inspection Allowed, Shipping Forbidden

Minecraft 26.3 ships unobfuscated JARs.
- **Allowed**: `javap -p` / `unzip -l` signature checks on the locally installed client JAR, reading `com.mojang.renderpearl.api.*` and `com.mojang.blaze3d.*` contracts, JFR/GPU captures.
- **Forbidden**: pasting or committing decompiled Mojang method bodies, or bundling `com.mojang.*` / `net.minecraft.*` classes in build artifacts.

## 4. Repository Map & Documentation

Read the relevant developer doc before editing a subsystem, and update it in the same PR whenever a contract changes:

- [`docs/optimizations.md`](docs/optimizations.md) — Vulkan layout tracking, scoped sync barriers, buffer view cache, pipeline cache, 4×4×4 region MDI, 20B/28B/40B vertex formats, async occlusion culling, and entity/UI fast paths.
- [`docs/shader-bridge.md`](docs/shader-bridge.md) — `TurboShadersBridge` SPI, `TurboShadersExtension` hooks, and pass ordering invariants.
- [`docs/configuration.md`](docs/configuration.md) — `vulkanturbo.json` options, vendor auto-tuning, `FrameProfiler`, diagnostic logs, and JVM/Gradle flags.

## 5. Architecture & Technical Invariants

- **Kotlin (`src/main/kotlin/dev/vulkanturbo/`)** is the default for core optimizers, state, config, GUI, and `api/shader/TurboShadersBridge`.
- **Java (`src/main/java/dev/vulkanturbo/mixin/`)** is reserved for thin Spongepowered Mixin classes and `PlatformInfo.java` (pure JDK boundary). Delegate logic into Kotlin objects.
- **Mixin priority**: Keep `mixinPriority = 900` in `vulkanturbo.mixins.json` so `Hyaloclast` (`1100`) layers cleanly on top via `TurboShadersBridge`.
- **Shader bridge fast path**: Keep `TurboShadersBridge.active` as a plain `@JvmField` boolean guard with zero virtual dispatch or allocation when inactive. Preserve pass hook ordering: `onBeginRegionTerrainPass` must run **before** `renderPass.setPipeline(...)`, and `onAfterRegionPipelineBound` must run **after**.
- **Vertex alignment**: Maintain `280` (`lcm(lcm(140, 40), vanillaAlign, activeStride)`) so `UberGpuBuffer` byte offsets divide evenly by `20B`, `28B`, and `40B` vertex strides.
- **Kotlin toolchain parity**: `kotlin("jvm")` in `build.gradle.kts` must match the suffix of `fabric_kotlin_version` in `gradle.properties`.
- **OS-agnostic paths**: Never hardcode `/home/<user>`, `/Users/<user>`, `C:\Users\...`, or `/tmp/...` in code, scripts, or tests. Always use `Path.of`, `System.getProperty("user.home")`, `tempfile.gettempdir()`, or environment variables.
- **No stubs or speculative abstractions**: Never ship placeholder methods, fake pass-throughs, or unused config keys.

## 6. Language & Code Comment Policy

- **English only**: All code, identifiers, comments, KDoc/Javadoc, commit messages, PRs, issues, and documentation must be written strictly in English. (Only `src/main/resources/assets/vulkanturbo/lang/*.json` localization files may contain other languages.)
- **High-signal comments only**: Keep comments short, current, and technical. Explain *why*, Vulkan synchronization/layout invariants, memory alignment (`20B`/`28B`/`40B` vertex formats), or mixin target contracts.
- **Forbidden comments**: Zero-information filler (`// increment counter`), outdated changelog narration (`// Fixed: ...`), TODOs for already-implemented code, or non-English comments.

## 7. Build & Verification

- **Toolchain**: JDK 25, Gradle 9.8.1, Fabric Loader `0.20.1+`, Minecraft `26.3`, Kotlin `2.4.21`.
- **Required checks before commit/PR**:
  ```sh
  ./gradlew test
  ./gradlew build
  ```
- **Performance claims**: Must include hardware, scene, baseline, and measurement method (`bench/compare.py`, `FrameProfiler`, or JFR). Never claim a test passed unless it was actually executed in the current session.
