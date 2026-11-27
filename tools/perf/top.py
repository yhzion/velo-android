#!/usr/bin/env python3
"""The slice names that took the most time on one thread of the app.

  uv run --with perfetto python tools/perf/top.py TRACE THREAD_NAME [LIMIT]

THREAD_NAME as the trace has it: the app's main thread is the package name cut to 15
characters (hub.yhzion.velo), the render threads velo-draw-<tag>, Compose's RenderThread.
"""
import os
import sys

from perfetto.trace_processor import TraceProcessor

trace, tname = sys.argv[1], sys.argv[2]
limit = int(sys.argv[3]) if len(sys.argv) > 3 else 25
PKG = os.environ.get("VELO_PKG", "io.github.yhzion.velo")
tp = TraceProcessor(trace=trace)
rows = tp.query(
    f"""select s.name, count(*) as n, sum(s.dur) as total, max(s.dur) as mx, min(s.depth) as d
        from slice s join thread_track tt on s.track_id = tt.id join thread t using(utid)
        join process p using(upid)
        where (p.name = '{PKG}' or p.name like '{PKG}:%') and t.name = '{tname}'
        group by s.name order by total desc limit {limit}"""
)
print(f"{trace} [{tname}]")
for r in rows:
    print(f"  {r.total / 1e6:9.1f}ms  n={r.n:<6} max={r.mx / 1e6:7.2f}ms  depth>={r.d}  {r.name[:90]}")
