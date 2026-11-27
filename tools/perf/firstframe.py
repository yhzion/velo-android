#!/usr/bin/env python3
"""What each render thread did for the glyphs of its window: a render thread starts whenever a
pane gets a window (a tab shown, an overview card, the app back on the screen), with an empty
glyph atlas. For each one, the frames that filled it (Renderer.drawMissing): how long they
took, the glyphs the rasterizer drew for them and the glyphs another pane had drawn already:
the cache is looked in for every missing glyph (Renderer.findGlyph), and drawn when it lacks it.

  uv run --with perfetto python tools/perf/firstframe.py TRACE [TRACE...]
"""
import os
import sys

from perfetto.trace_processor import TraceProcessor

PKG = os.environ.get("VELO_PKG", "io.github.yhzion.velo")


def analyze(path):
    tp = TraceProcessor(trace=path)
    q = lambda sql: list(tp.query(sql))
    t0 = q("select start_ts from trace_bounds")[0].start_ts
    fills = q(
        f"""select t.name as tname, t.tid, d.ts, d.dur,
                   (select count(*) from slice s where s.track_id = d.track_id and s.ts >= d.ts
                      and s.ts < d.ts + d.dur and s.name = 'Renderer.rasterizeGlyph') as drawn,
                   (select ifnull(sum(s.dur), 0) from slice s where s.track_id = d.track_id and s.ts >= d.ts
                      and s.ts < d.ts + d.dur and s.name = 'Renderer.rasterizeGlyph') as drawn_ns,
                   (select count(*) from slice s where s.track_id = d.track_id and s.ts >= d.ts
                      and s.ts < d.ts + d.dur and s.name = 'Renderer.findGlyph') as looked
            from slice d join thread_track tt on d.track_id = tt.id join thread t using(utid)
            join process p using(upid)
            where (p.name = '{PKG}' or substr(p.name, 1, length('{PKG}') + 1) = '{PKG}:') and d.name = 'Renderer.drawMissing' order by d.ts"""
    )
    print(f"=== {os.path.basename(path)}")
    big = []
    for f in fills:
        # Every glyph drawn was looked for first; without a cache (an older build) none was.
        found = f.looked - f.drawn
        print(
            f"  {(f.ts - t0) / 1e6:8.1f} ms  {f.tname:<16} tid={f.tid:<6} fill {f.dur / 1e6:6.1f} ms  "
            f"drawn {f.drawn:<4} ({f.drawn_ns / 1e6:6.1f} ms)  from the cache {max(0, found)}"
        )
        if f.drawn + max(0, found) >= 50:
            big.append(f.dur / 1e6)
    total = sum(f.dur for f in fills) / 1e6
    print(f"  fills {len(fills)}, {total:.1f} ms in all; drawn {sum(f.drawn for f in fills)}, "
          f"from the cache {sum(max(0, f.looked - f.drawn) for f in fills)}")
    if big:
        big.sort()
        print(f"  fills of 50 glyphs or more: {len(big)}, median {big[len(big) // 2]:.1f} ms, longest {big[-1]:.1f} ms")
    tp.close()


if len(sys.argv) < 2:
    raise SystemExit(__doc__)
for p in sys.argv[1:]:
    analyze(p)
