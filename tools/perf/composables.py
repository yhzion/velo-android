#!/usr/bin/env python3
"""The composables composed on the app's main thread in a composition-traced run
(tools/perf/compose-tracing.patch): how often, inclusive and self time, by name.

  uv run --with perfetto python tools/perf/composables.py TRACE [LIMIT]

A name is the composable and where it is ("Workspace (Workspace.kt:61)"). Compare its count
with Core.publishState's to see what each state delivery composes again.
"""
import os
import sys

from perfetto.trace_processor import TraceProcessor

if len(sys.argv) < 2:
    raise SystemExit(__doc__)
PKG = os.environ.get("VELO_PKG", "io.github.yhzion.velo")
tp = TraceProcessor(trace=sys.argv[1])
limit = int(sys.argv[2]) if len(sys.argv) > 2 else 40
rows = list(tp.query(f"""
  with s as (select s.id, s.dur, s.name, s.depth from slice s
             join thread_track tt on s.track_id = tt.id join thread t using(utid) join process p using(upid)
             where p.name like '{PKG}%' and t.tid = p.pid and s.name like '%.kt:%')
  select s.name, count(*) n, sum(s.dur) incl,
         sum(s.dur - ifnull((select sum(c.dur) from slice c where c.parent_id = s.id), 0)) self,
         min(s.depth) d
  from s group by s.name order by incl desc"""))
states = list(tp.query("select count(*) n from slice where name = 'Core.publishState'"))[0].n
print(f"{len(rows)} composables, {states} state deliveries")
for r in rows[:limit]:
    print(f"{r.incl / 1e6:8.1f}ms self={r.self / 1e6:7.1f}ms n={r.n:<5} {r.name[:110]}")
