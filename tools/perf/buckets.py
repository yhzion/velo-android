#!/usr/bin/env python3
"""The render threads' work per 500 ms: frames built, glyph misses and the time they took,
frames handed to the window and frames that drew nothing new.

  uv run --with perfetto python tools/perf/buckets.py TRACE
"""
import sys

from perfetto.trace_processor import TraceProcessor

tp = TraceProcessor(trace=sys.argv[1])
rows = tp.query(
    """select s.name, s.ts, s.dur, t.name as tname
       from slice s join thread_track tt on s.track_id = tt.id join thread t using(utid)
       where s.name in ('Renderer.buildFrame', 'Renderer.rasterizeGlyph', 'Renderer.present', 'Renderer.unchanged')
       order by s.ts"""
)
rows = list(rows)
if not rows:
    raise SystemExit("no renderer slices")
t0 = rows[0].ts
b = {}
threads = set()
for r in rows:
    k = (r.ts - t0) // 500_000_000
    e = b.setdefault(k, {"frames": 0, "miss": 0, "rast": 0, "present": 0, "same": 0})
    threads.add(r.tname)
    if r.name == "Renderer.buildFrame":
        e["frames"] += 1
    elif r.name == "Renderer.rasterizeGlyph":
        e["miss"] += 1
        e["rast"] += r.dur
    elif r.name == "Renderer.unchanged":
        e["same"] += 1
    else:
        e["present"] += 1
print("threads:", sorted(threads))
print(" t(s)  frames  misses  rasterize(ms)  presents  unchanged")
for k in sorted(b):
    e = b[k]
    print(f"{k * 0.5:5.1f}  {e['frames']:6}  {e['miss']:6}  {e['rast'] / 1e6:12.1f}  {e['present']:8}  {e['same']:9}")
