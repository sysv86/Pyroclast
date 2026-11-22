## Summary

<!-- Describe what this PR changes, why, and which packages are affected. -->

Closes #

## Change Type

- [ ] Bug fix
- [ ] Performance optimization (include benchmark data below)
- [ ] Shader Bridge SPI (`TurboShadersBridge`) change
- [ ] Documentation / CI / tooling

## Verification

Check only the commands actually executed in this session:

- [ ] `./gradlew test`
- [ ] `./gradlew build`
- [ ] `python3 -m py_compile bench/compare.py` (if benchmark harness changed)

<!-- For performance changes, include hardware, scene, baseline, and `bench/compare.py` or JFR output. -->

## Documentation & Contract Sync

- [ ] Updated affected developer docs under `docs/` (if vertex formats, alignment, SPI hooks, or build flags changed).
- [ ] Preserved `mixinPriority = 900`, `TurboShadersBridge.active` zero-overhead fast path, and `20B`/`28B`/`40B` vertex alignment invariants.

## AI Agent & Clean-Room Compliance

- [ ] If an AI coding agent was used, it followed [`AGENTS.md`](AGENTS.md) (English-only concise comments, OS-agnostic paths, no stubs).
- [ ] **No forbidden copying**: No code, shaders, or data structures were copied or ported from viral/copyleft or non-compete/proprietary mods (including Sodium under PolyForm Shield 1.0.0, Iris/VulkanMod/Embeddium under LGPL-3.0, or OptiFine).
- [ ] **Permissive reuse & credits**: If any compatible permissive (MIT / Apache-2.0 / BSD) reference was used with zero license risk, full attribution was added to notices and file headers.
- [ ] No hardcoded personal paths (e.g. `/home/<user>`, `/Users/<user>`, `C:\Users\...`) or decompiled Mojang code were introduced.
