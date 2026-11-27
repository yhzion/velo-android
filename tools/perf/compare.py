#!/usr/bin/env python3
"""One line per trace, for A/B tables: the terminal surfaces' swaps and the frames they skipped,
CPU time of the render threads, the main thread, Compose's RenderThread and SurfaceFlinger,
Compose recomposition, main-thread frames over 16.7 ms, app frames that missed their
deadline out of all, the frames SurfaceFlinger composed on the GPU (`gpu`), and the gaps of more
than 25 ms between two frames of the app's window on the screen while it moves (`gaps`: a
skipped vsync at 60 Hz; gaps of 150 ms or more are rests, not counted).

  uv run --with perfetto python tools/perf/compare.py TRACE [TRACE...]
"""
import os
import sys

from perfetto.trace_processor import TraceProcessor

if len(sys.argv) < 2:
    raise SystemExit(__doc__)
PKG = os.environ.get("VELO_PKG", "io.github.yhzion.velo")
print(f"{'trace':<28} {'swaps':>5} {'same':>5} {'draw':>6} {'main':>6} {'rthr':>6} {'sf':>6} {'recomp':>7} {'df>16':>5} {'missed':>9} {'gpu':>4} {'gaps':>8}")
for path in sys.argv[1:]:
    tp = TraceProcessor(trace=path)
    q = lambda s: list(tp.query(s))
    one = lambda s: q(s)[0].v or 0
    up = ",".join(str(r.upid) for r in q(f"select upid from process where (name = '{PKG}' or substr(name, 1, length('{PKG}') + 1) = '{PKG}:')"))
    pid = q(f"select pid from process where (name = '{PKG}' or substr(name, 1, length('{PKG}') + 1) = '{PKG}:') order by upid")[0].pid
    on = f"from slice s join thread_track tt on s.track_id = tt.id join thread t using(utid) where t.upid in ({up})"
    presents = one(f"select count(*) v {on} and s.name = 'Renderer.present'")
    same = one(f"select count(*) v {on} and s.name = 'Renderer.unchanged'")
    cpu = lambda cond: one(f"select sum(ss.dur) v from sched_slice ss join thread t using(utid) where t.upid in ({up}) and {cond}") / 1e6
    sf = one("""select sum(ss.dur) v from sched_slice ss join thread t using(utid) join process p using(upid)
                where p.name like '%surfaceflinger%'""") / 1e6
    recomp = one(f"select sum(s.dur) v {on} and t.tid = {pid} and s.name = 'Recomposer:recompose'") / 1e6
    long_frames = one(f"""select count(*) v {on} and t.tid = {pid} and s.depth = 0
                          and s.name like 'Choreographer#doFrame%' and s.dur > 16666667""")
    missed = one(f"select count(*) v from actual_frame_timeline_slice where upid in ({up}) and jank_type like '%App Deadline Missed%'")
    frames = one(f"select count(*) v from actual_frame_timeline_slice where upid in ({up})")
    gpu = one("""select count(*) v from slice s join thread_track tt on s.track_id = tt.id join thread t using(utid)
                 join process p using(upid) where p.name like '%surfaceflinger%' and s.name = 'REThreaded::drawLayers'""")
    # The window's frames as they reached the screen; the terminal surfaces are layers of their own.
    shown = sorted(r.t for r in q(f"""select ts + dur t from actual_frame_timeline_slice where upid in ({up})
                                     and layer_name not like '%SurfaceView%' and present_type != 'Dropped Frame'"""))
    moving = [(b - a) / 1e6 for a, b in zip(shown, shown[1:]) if b - a < 150e6]
    gaps = sum(1 for g in moving if g > 25)
    draw = cpu("t.name like 'velo-draw%'")
    main = cpu(f"t.tid = {pid}")
    rthr = cpu("t.name = 'RenderThread'")
    print(f"{os.path.basename(path)[:28]:<28} {presents - same:5d} {same:5d} {draw:6.0f} {main:6.0f} {rthr:6.0f} "
          f"{sf:6.0f} {recomp:7.0f} {long_frames:5d} {missed:4d}/{frames:<4} {gpu:4d} {gaps:3d}/{len(moving):<4}")
    tp.close()
