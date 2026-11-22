#!/usr/bin/env python3
"""
Clean-room A/B benchmark runner comparing isolated Minecraft 26.3 Vulkan runs:
  - sodium (Sodium without VulkanTurbo)
  - vulkanturbo (VulkanTurbo without Sodium)
  - optional: vanilla (no optimization mods)

Measures identically via a minimal JDK 25 -javaagent hooked strictly into Mojang's
GameRenderer.renderLevel(), without inspecting, unpacking, or depending on third-party mod code.
"""

from __future__ import annotations

import argparse
import glob
import json
import os
import re
import shutil
import statistics
import subprocess
import sys
import tempfile
import time
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parent.parent
EXE_SUFFIX = ".exe" if os.name == "nt" else ""


def _find_jdk_bin_dir() -> Path | None:
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidate = Path(java_home) / "bin"
        if (candidate / f"java{EXE_SUFFIX}").exists() and (candidate / f"javac{EXE_SUFFIX}").exists():
            return candidate
    gradle_jdks = Path.home() / ".gradle" / "jdks"
    if gradle_jdks.is_dir():
        for jdk_dir in sorted(gradle_jdks.glob("*25*"), reverse=True):
            for candidate in (jdk_dir / "bin", jdk_dir / "Contents" / "Home" / "bin"):
                if (candidate / f"java{EXE_SUFFIX}").exists() and (candidate / f"javac{EXE_SUFFIX}").exists():
                    return candidate
    return None


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="VulkanTurbo vs Sodium isolated Vulkan benchmark")
    p.add_argument("--world", default="BenchMobCrowd", help="Save directory name in run/saves/ (default: BenchMobCrowd)")
    p.add_argument("--modes", default="sodium,vulkanturbo", help="Comma-separated modes among: vanilla,vulkanturbo,sodium")
    p.add_argument("--pairs", type=int, default=3, help="Number of alternating rounds per mode (default: 3)")
    p.add_argument("--warmup", type=int, default=3600, help="Warmup world frames before sampling (default: 3600)")
    p.add_argument("--samples", type=int, default=5400, help="Sampled world frames per run (default: 5400)")
    p.add_argument("--render-distance", type=int, default=32, help="Render distance chunks (default: 32)")
    p.add_argument("--fullscreen", choices=["true", "false"], default="true", help="Fullscreen option (default: true)")
    p.add_argument("--improved-transparency", choices=["true", "false"], default="false", help="Improved/OIT transparency (default: false)")
    p.add_argument("--sodium-mods", default=os.environ.get("VT_SODIUM_MODS", str(Path(tempfile.gettempdir()) / "mc-sodium-26.3" / "mods")),
                   help="Directory holding opaque external sodium*.jar (+ optional fabric-api*.jar)")
    p.add_argument("--work-dir", default=str(Path(tempfile.gettempdir()) / "vt-bench-work"), help="Temporary isolated game dirs root")
    p.add_argument("--out-dir", default=str(Path(tempfile.gettempdir()) / "vt-bench-results"), help="Output directory for JSON/Markdown reports")
    p.add_argument("--skip-build", action="store_true", help="Skip './gradlew --no-daemon jar'")
    p.add_argument("--vktrace", action="store_true", help="Enable black-box LWJGL Vulkan driver-call telemetry")
    p.add_argument("--timeout-sec", type=int, default=240, help="Per-run timeout in seconds (default: 240)")
    return p.parse_args()


def ensure_tools() -> tuple[Path, Path, Path]:
    jdk_bin = _find_jdk_bin_dir()
    java = (jdk_bin / f"java{EXE_SUFFIX}") if jdk_bin else Path(shutil.which("java") or "")
    javac = (jdk_bin / f"javac{EXE_SUFFIX}") if jdk_bin else Path(shutil.which("javac") or "")
    jar_tool = (jdk_bin / f"jar{EXE_SUFFIX}") if jdk_bin else Path(shutil.which("jar") or "")
    for label, p in [("java", java), ("javac", javac), ("jar", jar_tool)]:
        if not p or not p.exists():
            raise SystemExit(f"Missing required JDK 25 tool: {label}")
    return java, javac, jar_tool


def build_agent_jar(javac: Path, jar_tool: Path, dest_dir: Path) -> Path:
    classes_dir = dest_dir / "classes"
    shutil.rmtree(classes_dir, ignore_errors=True)
    classes_dir.mkdir(parents=True, exist_ok=True)
    agent_sources = sorted((REPO_ROOT / "bench/agent").glob("*.java"))
    subprocess.run(
        [str(javac), "--release", "25", "-d", str(classes_dir), *[str(s) for s in agent_sources]],
        check=True,
    )
    sampler_jar = dest_dir / "bench-sampler.jar"
    subprocess.run(
        [str(jar_tool), "cf", str(sampler_jar), "-C", str(classes_dir), "dev/vulkanturbo/benchagent/BenchSampler.class"],
        check=True,
    )
    manifest = dest_dir / "MANIFEST.MF"
    manifest.write_text(
        "Manifest-Version: 1.0\n"
        "Premain-Class: dev.vulkanturbo.benchagent.BenchAgent\n"
        "Boot-Class-Path: bench-sampler.jar\n",
        encoding="utf-8",
    )
    agent_classes = sorted((classes_dir / "dev/vulkanturbo/benchagent").glob("BenchAgent*.class"))
    agent_jar = dest_dir / "bench-agent.jar"
    jar_cmd = [str(jar_tool), "cfm", str(agent_jar), str(manifest)]
    for cls in agent_classes:
        jar_cmd.extend(["-C", str(classes_dir), f"dev/vulkanturbo/benchagent/{cls.name}"])
    subprocess.run(jar_cmd, check=True)
    return agent_jar


def find_vulkanturbo_jar() -> Path:
    libs = sorted((REPO_ROOT / "build/libs").glob("vulkanturbo-*.jar"))
    jars = [p for p in libs if not p.name.endswith("-sources.jar") and not p.name.endswith("-dev.jar")]
    if not jars:
        raise SystemExit("No built vulkanturbo-*.jar found in build/libs/. Run without --skip-build.")
    return jars[-1]


def build_filtered_classpath(dest_file: Path, java: Path) -> Path:
    loom_arg = REPO_ROOT / "build/loom-cache/argFiles/runClient"
    cached_arg = REPO_ROOT / ".gradle/loom-cache/bench-runClient.args"
    fallback_arg = Path(os.environ.get("VT_BENCH_FALLBACK_ARGS", str(Path(tempfile.gettempdir()) / "mc-sodium-26.3" / "args.txt")))
    if loom_arg.exists():
        cached_arg.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(loom_arg, cached_arg)
        arg_file = loom_arg
    elif cached_arg.exists():
        arg_file = cached_arg
    elif fallback_arg.exists():
        cached_arg.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(fallback_arg, cached_arg)
        arg_file = cached_arg
    else:
        raise SystemExit(
            f"Missing Loom argFile: {loom_arg}. Start './gradlew runClient' once so Loom generates it."
        )
    raw = arg_file.read_text(encoding="utf-8")
    tokens = raw.splitlines()
    cp_value = None
    for i, tok in enumerate(tokens):
        if tok == "-classpath" and i + 1 < len(tokens):
            cp_value = tokens[i + 1]
            break
    if not cp_value:
        raise SystemExit(f"Could not parse -classpath from {arg_file}")
    build_prefix = str((REPO_ROOT / "build").resolve()) + os.sep
    entries = [e for e in cp_value.split(os.pathsep) if e and not e.startswith(build_prefix)]
    dest_file.write_text(f"-classpath\n{os.pathsep.join(entries)}\n", encoding="utf-8")
    return dest_file


def normalize_options(src: Path, dst: Path, rd: int, fullscreen: str, improved_transparency: str) -> None:
    overrides = {
        "preferredGraphicsBackend": '"vulkan"',
        "fullscreen": fullscreen,
        "enableVsync": "false",
        "maxFps": "260",
        "inactivityFpsLimit": '"minimized"',
        "pauseOnLostFocus": "false",
        "renderDistance": str(rd),
        "improvedTransparency": improved_transparency,
    }
    seen = set()
    out_lines = []
    for line in src.read_text(encoding="utf-8").splitlines():
        if ":" in line:
            key = line.split(":", 1)[0]
            if key in overrides:
                out_lines.append(f"{key}:{overrides[key]}")
                seen.add(key)
                continue
        out_lines.append(line)
    for k, v in overrides.items():
        if k not in seen:
            out_lines.append(f"{k}:{v}")
    dst.write_text("\n".join(out_lines) + "\n", encoding="utf-8")


def prepare_run_dir(
    run_dir: Path,
    mode: str,
    world_src: Path,
    world_name: str,
    vt_jar: Path | None,
    sodium_mods_dir: Path | None,
    args: argparse.Namespace,
) -> None:
    shutil.rmtree(run_dir, ignore_errors=True)
    run_dir.mkdir(parents=True, exist_ok=True)
    mods_dir = run_dir / "mods"
    mods_dir.mkdir()
    config_dir = run_dir / "config"
    config_dir.mkdir()
    saves_dir = run_dir / "saves"
    saves_dir.mkdir()

    shutil.copytree(world_src, saves_dir / world_name)
    normalize_options(
        REPO_ROOT / "run/options.txt",
        run_dir / "options.txt",
        args.render_distance,
        args.fullscreen,
        args.improved_transparency,
    )

    if mode == "vulkanturbo":
        assert vt_jar is not None
        shutil.copy2(vt_jar, mods_dir / vt_jar.name)
        cfg_src = REPO_ROOT / "run/config/vulkanturbo.json"
        cfg = json.loads(cfg_src.read_text(encoding="utf-8")) if cfg_src.exists() else {}
        cfg["enabled"] = True
        cfg["benchmark"] = False
        (config_dir / "vulkanturbo.json").write_text(json.dumps(cfg, indent=2) + "\n", encoding="utf-8")
    elif mode == "sodium":
        assert sodium_mods_dir is not None
        jars = sorted(sodium_mods_dir.glob("*.jar"))
        if not jars:
            raise SystemExit(f"No .jar files found in --sodium-mods {sodium_mods_dir}")
        for jar in jars:
            if "vulkanturbo" in jar.name.lower():
                raise SystemExit(f"Refusing vulkanturbo jar inside sodium mods dir: {jar}")
            shutil.copy2(jar, mods_dir / jar.name)
    elif mode == "vanilla":
        pass
    else:
        raise SystemExit(f"Unknown mode: {mode}")


def verify_run_log(log_path: Path, mode: str) -> dict:
    if not log_path.exists():
        raise RuntimeError(f"Missing log file: {log_path}")
    text = log_path.read_text(encoding="utf-8", errors="replace")

    if "Using graphics backend Vulkan" not in text:
        raise RuntimeError(f"[{mode}] Vulkan backend was NOT selected in {log_path}")

    gpu_match = re.search(r"Using graphics device: (.+)", text)
    backend_match = re.search(r"Using graphics backend (.+)", text)

    # Check FabricLoader mod list lines only (not file contents of mods).
    mods_block_match = re.search(r"Loading \d+ mods:\n((?:\t.*\n)+)", text)
    mods_block = mods_block_match.group(1) if mods_block_match else text
    has_vt = bool(re.search(r"\t-\s+vulkanturbo\b", mods_block))
    has_sodium = bool(re.search(r"\t-\s+sodium\b", mods_block))

    if mode == "sodium" and (not has_sodium or has_vt):
        raise RuntimeError(f"Mod isolation failed for sodium run: has_sodium={has_sodium}, has_vulkanturbo={has_vt}")
    if mode == "vulkanturbo" and (not has_vt or has_sodium):
        raise RuntimeError(f"Mod isolation failed for vulkanturbo run: has_vulkanturbo={has_vt}, has_sodium={has_sodium}")
    if mode == "vanilla" and (has_vt or has_sodium):
        raise RuntimeError(f"Mod isolation failed for vanilla run: has_vulkanturbo={has_vt}, has_sodium={has_sodium}")

    return {
        "backend": backend_match.group(1).strip() if backend_match else "Vulkan",
        "device": gpu_match.group(1).strip() if gpu_match else "unknown",
        "hasSodium": has_sodium,
        "hasVulkanTurbo": has_vt,
    }


def run_single(
    java: Path,
    args_file: Path,
    launch_cfg: Path,
    agent_jar: Path,
    run_dir: Path,
    out_json: Path,
    label: str,
    mode: str,
    args: argparse.Namespace,
) -> dict:
    cmd = [
        str(java),
        f"@{args_file}",
        "-Xmx4G",
        "-XX:StackShadowPages=32",
        f"-javaagent:{agent_jar}",
        f"-Dvt.bench.warmup={args.warmup}",
        f"-Dvt.bench.samples={args.samples}",
        f"-Dvt.bench.vktrace={'true' if args.vktrace else 'false'}",
        "-Dvt.bench.autoExit=true",
        f"-Dvt.bench.label={label}",
        f"-Dvt.bench.out={out_json}",
        f"-Dfabric.dli.config={launch_cfg}",
        "-Dfabric.dli.env=client",
        "-Dfabric.dli.main=net.fabricmc.loader.impl.launch.knot.KnotClient",
        f"-Dfabric.modsFolder={run_dir / 'mods'}",
        "net.fabricmc.devlaunchinjector.Main",
        "--gameDir",
        str(run_dir),
        "--quickPlaySingleplayer",
        args.world,
    ]
    t0 = time.monotonic()
    proc = subprocess.run(
        cmd,
        cwd=str(run_dir),
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        timeout=args.timeout_sec,
    )
    wall_s = time.monotonic() - t0
    if proc.returncode != 0:
        tail = "\n".join(proc.stdout.splitlines()[-40:])
        raise RuntimeError(f"Run {label} failed (rc={proc.returncode}):\n{tail}")
    if not out_json.exists():
        tail = "\n".join(proc.stdout.splitlines()[-40:])
        raise RuntimeError(f"Run {label} produced no JSON at {out_json}:\n{tail}")

    data = json.loads(out_json.read_text(encoding="utf-8"))
    log_info = verify_run_log(run_dir / "logs/latest.log", mode)
    data.update(log_info)
    data["mode"] = mode
    data["wallSeconds"] = round(wall_s, 2)
    return data


def pct_delta(new_val: float, base_val: float) -> float:
    return ((new_val - base_val) / base_val) * 100.0


def summarize(results: list[dict], modes: list[str], args: argparse.Namespace) -> dict:
    by_mode: dict[str, dict] = {}
    metrics = ["avgMs", "avgFps", "p50Ms", "p95Ms", "p99Ms", "onePercentLowFps", "maxMs"]
    for m in modes:
        runs = [r for r in results if r["mode"] == m]
        stats = {"count": len(runs), "runs": runs}
        for key in metrics:
            vals = [r[key] for r in runs]
            stats[key] = {
                "median": statistics.median(vals),
                "mean": statistics.mean(vals),
                "min": min(vals),
                "max": max(vals),
            }
        by_mode[m] = stats
    return {
        "config": {
            "world": args.world,
            "renderDistance": args.render_distance,
            "fullscreen": args.fullscreen == "true",
            "improvedTransparency": args.improved_transparency == "true",
            "warmupFrames": args.warmup,
            "sampleFrames": args.samples,
            "pairs": args.pairs,
            "modes": modes,
        },
        "summary": by_mode,
        "runs": results,
    }


def format_markdown(report: dict) -> str:
    cfg = report["config"]
    summary = report["summary"]
    modes = cfg["modes"]
    lines = [
        "# Isolated Vulkan Benchmark Comparison",
        "",
        f"- **World:** `{cfg['world']}` (fresh identical copy per run)",
        f"- **Settings:** RD `{cfg['renderDistance']}`, fullscreen `{cfg['fullscreen']}`, "
        f"improvedTransparency `{cfg['improvedTransparency']}`, vsync `false`, maxFps `260` (unlimited), backend `Vulkan`",
        f"- **Sampling:** `{cfg['warmupFrames']}` warmup frames + `{cfg['sampleFrames']}` sampled frames × `{cfg['pairs']}` alternating rounds",
        "",
        "## Medians across rounds",
        "",
        "| Mode | Avg ms | Avg FPS | p50 ms | p95 ms | p99 ms | 1% Low FPS | Max ms |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for m in modes:
        s = summary[m]
        lines.append(
            f"| `{m}` | {s['avgMs']['median']:.3f} | {s['avgFps']['median']:.1f} | "
            f"{s['p50Ms']['median']:.3f} | {s['p95Ms']['median']:.3f} | {s['p99Ms']['median']:.3f} | "
            f"{s['onePercentLowFps']['median']:.1f} | {s['maxMs']['median']:.3f} |"
        )

    if "vulkanturbo" in summary and "sodium" in summary:
        vt = summary["vulkanturbo"]
        so = summary["sodium"]
        lines.extend([
            "",
            "## VulkanTurbo (no Sodium) vs Sodium (no VulkanTurbo)",
            "",
            f"- **Avg FPS:** `VulkanTurbo` {vt['avgFps']['median']:.1f} vs `Sodium` {so['avgFps']['median']:.1f} "
            f"(`{pct_delta(vt['avgFps']['median'], so['avgFps']['median']):+.2f}%`)",
            f"- **Avg frame time:** `{vt['avgMs']['median']:.3f} ms` vs `{so['avgMs']['median']:.3f} ms` "
            f"(`{pct_delta(vt['avgMs']['median'], so['avgMs']['median']):+.2f}%`)",
            f"- **p95 frame time:** `{vt['p95Ms']['median']:.3f} ms` vs `{so['p95Ms']['median']:.3f} ms` "
            f"(`{pct_delta(vt['p95Ms']['median'], so['p95Ms']['median']):+.2f}%`)",
            f"- **p99 frame time:** `{vt['p99Ms']['median']:.3f} ms` vs `{so['p99Ms']['median']:.3f} ms` "
            f"(`{pct_delta(vt['p99Ms']['median'], so['p99Ms']['median']):+.2f}%`)",
            f"- **1% Low FPS:** `{vt['onePercentLowFps']['median']:.1f}` vs `{so['onePercentLowFps']['median']:.1f}` "
            f"(`{pct_delta(vt['onePercentLowFps']['median'], so['onePercentLowFps']['median']):+.2f}%`)",
        ])

    lines.extend(["", "## Per-run samples", ""])
    for r in report["runs"]:
        lines.append(
            f"- `{r['label']}` ({r['backend']}, {r['device']}): "
            f"avg `{r['avgMs']:.3f} ms` (`{r['avgFps']:.1f} fps`), "
            f"p95 `{r['p95Ms']:.3f} ms`, p99 `{r['p99Ms']:.3f} ms`, "
            f"1% low `{r['onePercentLowFps']:.1f} fps`, wall `{r['wallSeconds']}s`"
        )
    return "\n".join(lines) + "\n"


def main() -> None:
    args = parse_args()
    modes = [m.strip() for m in args.modes.split(",") if m.strip()]
    for m in modes:
        if m not in {"vanilla", "vulkanturbo", "sodium"}:
            raise SystemExit(f"Unsupported mode: {m}")

    world_src = REPO_ROOT / "run/saves" / args.world
    if not world_src.exists():
        raise SystemExit(f"World not found: {world_src}")

    sodium_dir = Path(args.sodium_mods) if "sodium" in modes else None
    if sodium_dir is not None and not sodium_dir.exists():
        raise SystemExit(f"Sodium mods dir not found: {sodium_dir}")

    java, javac, jar_tool = ensure_tools()

    if not args.skip_build and "vulkanturbo" in modes:
        print("[bench] Building VulkanTurbo jar via ./gradlew --no-daemon jar ...", flush=True)
        env = os.environ.copy()
        env["JAVA_HOME"] = str(java.parent.parent)
        subprocess.run([str(REPO_ROOT / "gradlew"), "--no-daemon", "jar"], cwd=str(REPO_ROOT), env=env, check=True)

    vt_jar = find_vulkanturbo_jar() if "vulkanturbo" in modes else None

    work_root = Path(args.work_dir)
    out_root = Path(args.out_dir)
    shutil.rmtree(work_root, ignore_errors=True)
    work_root.mkdir(parents=True, exist_ok=True)
    out_root.mkdir(parents=True, exist_ok=True)

    agent_jar = build_agent_jar(javac, jar_tool, work_root / "agent")
    args_file = build_filtered_classpath(work_root / "args.txt", java)
    launch_cfg = work_root / "launch.cfg"
    shutil.copy2(REPO_ROOT / ".gradle/loom-cache/launch.cfg", launch_cfg)

    results: list[dict] = []
    for rnd in range(args.pairs):
        order = modes if rnd % 2 == 0 else list(reversed(modes))
        for mode in order:
            label = f"{mode}-r{rnd + 1}"
            run_dir = work_root / label
            out_json = out_root / f"{label}.json"
            print(f"[bench] Preparing {label} ...", flush=True)
            prepare_run_dir(run_dir, mode, world_src, args.world, vt_jar, sodium_dir, args)
            print(f"[bench] Running {label} (Vulkan, world={args.world}) ...", flush=True)
            res = run_single(java, args_file, launch_cfg, agent_jar, run_dir, out_json, label, mode, args)
            results.append(res)
            print(
                f"[bench] Done {label}: {res['avgMs']:.3f} ms ({res['avgFps']:.1f} fps) | "
                f"p95 {res['p95Ms']:.3f} | p99 {res['p99Ms']:.3f} | 1%low {res['onePercentLowFps']:.1f} fps",
                flush=True,
            )

    report = summarize(results, modes, args)
    summary_json = out_root / "summary.json"
    summary_md = out_root / "summary.md"
    summary_json.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    md_text = format_markdown(report)
    summary_md.write_text(md_text, encoding="utf-8")
    print("\n" + md_text, flush=True)
    print(f"[bench] Saved reports: {summary_json} and {summary_md}", flush=True)


if __name__ == "__main__":
    main()
