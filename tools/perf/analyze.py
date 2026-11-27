#!/usr/bin/env python3
"""Summarise Velo Perfetto traces: the core's sections, glyph misses per frame, the frames
the terminal surfaces get, state deliveries, UI frames, and CPU time per thread.

  uv run --with perfetto python tools/perf/analyze.py TRACE [TRACE...]
"""
import os
import sys

from perfetto.trace_processor import TraceProcessor

PKG = os.environ.get("VELO_PKG", "io.github.yhzion.velo")
VSYNC = 16_666_667


def pct(values, p):
    if not values:
        return 0.0
    v = sorted(values)
    return v[min(len(v) - 1, max(0, round(p / 100 * (len(v) - 1))))]


def ms(ns):
    return ns / 1e6


def row(label, durs, extra=""):
    if not durs:
        print(f"  {label:<34} n=0")
        return
    print(
        f"  {label:<34} n={len(durs):<5} sum={ms(sum(durs)):8.1f}ms  p50={ms(pct(durs, 50)):6.2f}  "
        f"p95={ms(pct(durs, 95)):6.2f}  max={ms(max(durs)):7.2f}ms {extra}"
    )


def analyze(path):
    tp = TraceProcessor(trace=path)
    q = lambda sql: list(tp.query(sql))
    procs = q(f"select upid, pid from process where name like '{PKG}%' order by upid")
    if not procs:
        print(f"{path}: no {PKG} process")
        return
    upids = ",".join(str(p.upid) for p in procs)
    main_pid = procs[0].pid
    bounds = q("select start_ts, end_ts from trace_bounds")[0]
    print(f"\n=== {path}  ({ms(bounds.end_ts - bounds.start_ts) / 1000:.1f}s, pid {main_pid})")

    slices = q(
        f"""select s.id, s.ts, s.dur, s.name, s.depth, s.parent_id, t.name as tname, t.tid
            from slice s join thread_track tt on s.track_id = tt.id join thread t using(utid)
            where t.upid in ({upids}) and s.dur >= 0"""
    )
    by = {}
    for s in slices:
        by.setdefault(s.name, []).append(s)

    print(" core sections (Zig, ATrace):")
    for name in ("Renderer.buildFrame", "Renderer.present", "Renderer.drawMissing", "Renderer.unchanged",
                 "Renderer.rasterizeGlyph", "Renderer.findGlyph", "DataStream.feed", "Core.stateSnapshot",
                 "Core.publishState"):
        row(name, [s.dur for s in by.get(name, [])])

    # A present with a Renderer.unchanged inside drew nothing: the window kept its frame.
    skipped = {s.parent_id for s in by.get("Renderer.unchanged", [])}
    drawn = [s for s in by.get("Renderer.present", []) if s.id not in skipped]
    row("present that drew (swapped)", [s.dur for s in drawn])

    # Glyph misses inside each frame: in buildFrame, with the pane lock held, before the misses
    # were drawn after it (Renderer.drawMissing inside Renderer.present); in present after.
    frame_slice = "Renderer.present" if by.get("Renderer.drawMissing") else "Renderer.buildFrame"
    per = q(
        f"""select b.id, b.dur, t.name as tname,
                   (select count(*) from slice r where r.track_id = b.track_id and r.ts >= b.ts
                      and r.ts < b.ts + b.dur and r.name = 'Renderer.rasterizeGlyph') as misses,
                   (select ifnull(sum(r.dur), 0) from slice r where r.track_id = b.track_id and r.ts >= b.ts
                      and r.ts < b.ts + b.dur and r.name = 'Renderer.rasterizeGlyph') as rdur
            from slice b join thread_track tt on b.track_id = tt.id join thread t using(utid)
            where t.upid in ({upids}) and b.name = '{frame_slice}'"""
    )
    with_miss = [p for p in per if p.misses > 0]
    if per:
        print(
            f"  {frame_slice} with misses: {len(with_miss)}/{len(per)}; misses total={sum(p.misses for p in per)}; "
            f"rasterize share of those frames={100 * sum(p.rdur for p in with_miss) / max(1, sum(p.dur for p in with_miss)):.0f}%"
        )
        for p in sorted(per, key=lambda p: -p.dur)[:5]:
            print(f"    worst {frame_slice} {ms(p.dur):7.2f}ms  misses={p.misses:<4} rasterize={ms(p.rdur):7.2f}ms  [{p.tname}]")
    builds = [s.dur for s in by.get("Renderer.buildFrame", [])]
    print(f"  buildFrame > 16.7ms (pane lock held a whole vsync): {sum(1 for d in builds if d > VSYNC)}")

    # Swap intervals per draw thread: the pacing of the terminal surfaces.
    swaps = {}
    for s in drawn:
        swaps.setdefault(s.tname, []).append(s.ts)
    for tname, ts in sorted(swaps.items()):
        gaps = [b - a for a, b in zip(ts, ts[1:])]
        if gaps:
            print(
                f"  swap gaps [{tname}] n={len(gaps)} p50={ms(pct(gaps, 50)):.1f} p95={ms(pct(gaps, 95)):.1f} "
                f"max={ms(max(gaps)):.1f}ms  >33ms={sum(1 for g in gaps if g > 2 * VSYNC)}"
            )

    # State deliveries to the app: how far apart they come.
    pubs = sorted(s.ts for s in by.get("Core.publishState", []))
    gaps = [b - a for a, b in zip(pubs, pubs[1:])]
    if gaps:
        print(
            f"  state gaps n={len(gaps)} p10={ms(pct(gaps, 10)):.1f} p50={ms(pct(gaps, 50)):.1f} "
            f"p90={ms(pct(gaps, 90)):.1f}ms  <16.7ms={sum(1 for g in gaps if g < VSYNC)}"
        )

    print(" UI (Kotlin/Compose):")
    doframe = [s for s in slices if s.name.startswith("Choreographer#doFrame") and s.tid == main_pid and s.depth == 0]
    row("Choreographer#doFrame (main)", [s.dur for s in doframe], f">16.7ms={sum(1 for s in doframe if s.dur > VSYNC)}")
    for name in ("Recomposer:recompose", "Compose:recompose", "traversal", "animation", "input"):
        row(name, [s.dur for s in by.get(name, []) if s.tid == main_pid])
    row("RenderThread DrawFrame", [s.dur for s in slices if s.name.startswith("DrawFrame") or s.name == "DrawFrames"])

    jank = q(
        f"""select jank_type, count(*) as n from actual_frame_timeline_slice
            where upid in ({upids}) group by jank_type order by n desc"""
    )
    if jank:
        print("  frame timeline (app):", ", ".join(f"{j.jank_type}={j.n}" for j in jank))

    print(" CPU running time by thread (top 12):")
    for c in q(
        f"""select t.name as tname, t.tid, sum(ss.dur) as run
            from sched_slice ss join thread t using(utid)
            where t.upid in ({upids}) group by t.utid order by run desc limit 12"""
    ):
        print(f"  {c.tname or '?':<24} tid={c.tid:<6} {ms(c.run):8.1f}ms")
    sf = q(
        """select sum(ss.dur) as run from sched_slice ss join thread t using(utid) join process p using(upid)
           where p.name like '%surfaceflinger%'"""
    )
    if sf and sf[0].run:
        print(f"  {'surfaceflinger (all threads)':<31} {ms(sf[0].run):8.1f}ms")
    tp.close()


if len(sys.argv) < 2:
    raise SystemExit(__doc__)
for p in sys.argv[1:]:
    analyze(p)
