#!/usr/bin/env python3
"""Monitor contention on the render threads (the glyph rasterizer is one lock for all of
them), and their builds over 10 ms.

  uv run --with perfetto python tools/perf/contention.py TRACE
"""
import os
import sys
from perfetto.trace_processor import TraceProcessor
PKG = os.environ.get("VELO_PKG", "io.github.yhzion.velo")
tp = TraceProcessor(trace=sys.argv[1])
q = f"""select t.name as tname, s.name, s.ts, s.dur from slice s join thread_track tt on s.track_id = tt.id
       join thread t using(utid) join process p using(upid)
       where p.name like '{PKG}%' and t.name like 'velo-draw%'
         and (s.name like '%ontention%' or s.name = 'Renderer.buildFrame') order by s.ts"""
rows = list(tp.query(q))
t0 = rows[0].ts if rows else 0
cont = {}
for r in rows:
    if r.name == 'Renderer.buildFrame':
        if r.dur > 10e6: print(f"buildFrame {r.tname:<16} start={(r.ts-t0)/1e6:8.1f}ms dur={r.dur/1e6:6.1f}ms")
    else:
        k = (r.tname, r.name.split(' owner')[0][:70])
        c = cont.setdefault(k, [0, 0]); c[0] += 1; c[1] += r.dur
for (t, n), (c, d) in sorted(cont.items(), key=lambda x: -x[1][1])[:8]:
    print(f"contention {t:<16} n={c:<4} total={d/1e6:7.1f}ms  {n}")
