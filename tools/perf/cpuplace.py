#!/usr/bin/env python3
"""What one glyph took to draw, by the kind of core and its clock at the time.

  uv run --with perfetto python tools/perf/cpuplace.py TRACE [TRACE...]

PERF_BIG_FROM is the first big core (6 on the LG Velvet's Snapdragon 765G).
"""
import os
import sys, bisect
from perfetto.trace_processor import TraceProcessor
BIG_FROM = int(os.environ.get("PERF_BIG_FROM", "6"))
for path in sys.argv[1:]:
    tp = TraceProcessor(trace=path)
    rs = list(tp.query("""select s.ts, s.dur, tt.utid from slice s join thread_track tt on s.track_id = tt.id
        join thread t using(utid) where s.name = 'Renderer.rasterizeGlyph' and t.name like 'velo-draw%'"""))
    if not rs: continue
    utids = ",".join(str(u) for u in {r.utid for r in rs})
    sch = list(tp.query(f"select ts, dur, cpu, utid from sched_slice where utid in ({utids}) order by ts"))
    freq = {}
    for f in tp.query("""select c.ts, c.value, t.cpu from counter c join cpu_counter_track t on c.track_id = t.id
                         where t.name = 'cpufreq' order by c.ts"""):
        freq.setdefault(f.cpu, ([], []))
        freq[f.cpu][0].append(f.ts); freq[f.cpu][1].append(f.value)
    starts = [s.ts for s in sch]
    agg = {}
    for r in rs:
        i = bisect.bisect_right(starts, r.ts) - 1
        if i < 0: continue
        s = sch[i]
        if not (s.ts <= r.ts < s.ts + s.dur): continue
        cpu = s.cpu
        fl = freq.get(cpu)
        mhz = 0
        if fl:
            j = bisect.bisect_right(fl[0], r.ts) - 1
            if j >= 0: mhz = int(fl[1][j] / 1000)
        kind = 'big' if cpu >= BIG_FROM else 'little'
        band = f"{kind}@{(mhz // 300) * 300}+MHz"
        a = agg.setdefault(band, [0, 0]); a[0] += 1; a[1] += r.dur
    print(path)
    for k, (n, d) in sorted(agg.items()):
        print(f"  {k:<18} n={n:<5} avg={d / n / 1e6:5.2f}ms")
