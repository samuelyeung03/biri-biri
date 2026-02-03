#!/usr/bin/env python3
"""Parse structured `latency` logcat lines and compute per-stage latency stats.

Expected input lines contain key=value pairs produced by com.bitchat.android.util.LatencyLog,
for example:
  D/latency: ev=cam_frame t=123 fid=7 w=640 h=480 rid=...

This script is intentionally tolerant: it ignores non-latency lines and lines missing fields.

Usage:
  python3 tools/parse_latency_logcat.py deviceA.log [deviceB.log ...]
  python3 tools/parse_latency_logcat.py --dir tools/latency_logs/20260202_135901

Output:
  - Per-file inferred role (sender/receiver/relay)
  - Per-file event counts
  - Sender-side (fid) pipeline breakdown (cam_frame -> enc_out -> mesh_send_call)
  - Receiver-side (seq) pipeline breakdown (rx_video -> dec_in -> render_cb)
  - Fragmentation/reassembly breakdown (fragId) when present

Notes:
  - LatencyLog uses elapsedRealtimeNanos() so deltas within the same device are reliable.
  - Cross-device deltas require clock sync; this tool does not attempt to sync devices.
"""

from __future__ import annotations

import argparse
import statistics
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Tuple


def _to_int(v: Optional[str]) -> Optional[int]:
    if v is None:
        return None
    try:
        return int(v)
    except Exception:
        return None


def _to_float(v: Optional[str]) -> Optional[float]:
    if v is None:
        return None
    try:
        return float(v)
    except Exception:
        return None


@dataclass
class Event:
    ev: str
    t: int
    fields: Dict[str, str]

    def get(self, k: str) -> Optional[str]:
        return self.fields.get(k)

    def geti(self, k: str) -> Optional[int]:
        return _to_int(self.get(k))


def parse_latency_lines(fp: Path) -> List[Event]:
    events: List[Event] = []

    for raw in fp.read_text(encoding="utf-8", errors="ignore").splitlines():
        # Accept either "D/latency: ..." or plain "ev=..." lines
        if "latency" not in raw:
            continue

        # Try to isolate the key=value segment
        if "ev=" not in raw or " t=" not in raw:
            continue

        segment = raw
        if "latency:" in raw:
            segment = raw.split("latency:", 1)[1].strip()
        else:
            # some formats: "latency  ev=..."
            idx = raw.find("ev=")
            segment = raw[idx:].strip() if idx >= 0 else raw

        parts = [p for p in segment.split(" ") if p]
        kv: Dict[str, str] = {}
        for p in parts:
            if "=" not in p:
                continue
            k, v = p.split("=", 1)
            kv[k] = v

        ev = kv.get("ev")
        t = _to_int(kv.get("t"))
        if ev is None or t is None:
            continue

        # Remove ev/t from fields
        kv.pop("ev", None)
        kv.pop("t", None)

        events.append(Event(ev=ev, t=t, fields=kv))

    return events


def ns_to_ms(ns: int) -> float:
    return ns / 1_000_000.0


def stats_ms(values_ns: Iterable[int]) -> Optional[Dict[str, Any]]:
    vals = list(values_ns)
    if not vals:
        return None
    ms = sorted(ns_to_ms(v) for v in vals)

    def pct(p: float) -> float:
        if not ms:
            return 0.0
        if len(ms) == 1:
            return ms[0]
        # nearest-rank percentile
        k = int(round((p / 100.0) * (len(ms) - 1)))
        k = max(0, min(len(ms) - 1, k))
        return ms[k]

    return {
        "count": len(ms),
        "avg_ms": statistics.mean(ms),
        "p50_ms": pct(50),
        "p90_ms": pct(90),
        "p99_ms": pct(99),
        "min_ms": ms[0],
        "max_ms": ms[-1],
    }


def print_stats(name: str, values_ns: List[int]) -> None:
    s = stats_ms(values_ns)
    if not s:
        print(f"  {name}: no data")
        return
    print(
        f"  {name}: count={s['count']} avg={s['avg_ms']:.2f}ms "
        f"p50={s['p50_ms']:.2f}ms p90={s['p90_ms']:.2f}ms p99={s['p99_ms']:.2f}ms "
        f"min={s['min_ms']:.2f}ms max={s['max_ms']:.2f}ms"
    )


def _print_top_events(counts: Dict[str, int], limit: int = 15) -> None:
    if not counts:
        return
    top = sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))[:limit]
    print(f"Top events (by count, top {len(top)}):")
    for ev, c in top:
        print(f"  {ev}: {c}")


def _group_delays_by_key(
    events: List[Event],
    ev_start: str,
    ev_end: str,
    make_key,
) -> Tuple[List[int], Dict[str, List[int]]]:
    """Generic matcher to compute delays between two event types.

    Returns:
      - all_delays list
      - per_group mapping (string key -> delays)

    Matching strategy:
      - Extract a tuple key from each event via make_key(e)
      - Use earliest timestamp per key for start/end
      - Delay = end - start where end >= start
    """

    start_t: Dict[tuple, int] = {}
    end_t: Dict[tuple, int] = {}

    for e in events:
        if e.ev not in (ev_start, ev_end):
            continue
        k = make_key(e)
        if k is None:
            continue
        if e.ev == ev_start:
            prev = start_t.get(k)
            if prev is None or e.t < prev:
                start_t[k] = e.t
        else:
            prev = end_t.get(k)
            if prev is None or e.t < prev:
                end_t[k] = e.t

    all_delays: List[int] = []
    per: Dict[str, List[int]] = {}

    for k, t0 in start_t.items():
        t1 = end_t.get(k)
        if t1 is None:
            continue
        d = t1 - t0
        if d < 0:
            continue
        all_delays.append(d)

        group_label = str(k)
        per.setdefault(group_label, []).append(d)

    return all_delays, per


def _print_group_table(
    title: str,
    per_group: Dict[str, List[int]],
    limit: int = 10,
    sort_by: str = "p90",
) -> None:
    if not per_group:
        return

    rows: List[Tuple[float, str, Dict[str, Any]]] = []
    for k, delays in per_group.items():
        s = stats_ms(delays)
        if not s:
            continue
        score = {
            "avg": s["avg_ms"],
            "p50": s["p50_ms"],
            "p90": s["p90_ms"],
            "p99": s["p99_ms"],
            "max": s["max_ms"],
        }.get(sort_by, s["p90_ms"])
        rows.append((float(score), k, s))

    rows.sort(key=lambda r: (-r[0], r[1]))
    rows = rows[:limit]

    print(title)
    print("  key | count | avg | p50 | p90 | p99 | max (ms)")
    for _, k, s in rows:
        print(
            f"  {k} | {s['count']} | {s['avg_ms']:.2f} | {s['p50_ms']:.2f} | {s['p90_ms']:.2f} | {s['p99_ms']:.2f} | {s['max_ms']:.2f}"
        )


def first_by_ev(events: List[Event], ev: str) -> Optional[Event]:
    for e in events:
        if e.ev == ev:
            return e
    return None


def build_index(events: List[Event], key: str) -> Dict[str, List[Event]]:
    idx: Dict[str, List[Event]] = {}
    for e in events:
        v = e.get(key)
        if v is None:
            continue
        idx.setdefault(v, []).append(e)
    return idx


def nearest_event_after(events: List[Event], ev: str, t0: int) -> Optional[Event]:
    # Events are already in file order; not always sorted. We'll just scan.
    best: Optional[Event] = None
    for e in events:
        if e.ev != ev:
            continue
        if e.t < t0:
            continue
        if best is None or e.t < best.t:
            best = e
    return best


def analyze_sender_by_fid(events: List[Event]) -> None:
    # We expect fid present for sender camera/encoder pipeline.
    by_fid = build_index(events, "fid")

    cam_to_enc = []
    enc_to_payload = []
    payload_to_mesh_call = []

    for fid, evs in by_fid.items():
        # Small per-fid extraction
        cam = first_by_ev(evs, "cam_frame")
        enc_out = first_by_ev(evs, "enc_out")
        payload = first_by_ev(evs, "video_payload")
        mesh_call = first_by_ev(evs, "mesh_send_call")

        if cam and enc_out:
            cam_to_enc.append(enc_out.t - cam.t)
        if enc_out and payload:
            enc_to_payload.append(payload.t - enc_out.t)
        if payload and mesh_call:
            payload_to_mesh_call.append(mesh_call.t - payload.t)

    print("Sender pipeline (group by fid):")
    print_stats("cam_frame -> enc_out", cam_to_enc)
    print_stats("enc_out -> video_payload", enc_to_payload)
    print_stats("video_payload -> mesh_send_call", payload_to_mesh_call)


def analyze_receiver_by_seq(events: List[Event]) -> None:
    by_seq = build_index(events, "seq")

    rx_to_dec = []
    dec_to_render = []

    for seq, evs in by_seq.items():
        rx = first_by_ev(evs, "rx_video")
        dec = first_by_ev(evs, "dec_in")
        ren = first_by_ev(evs, "render_cb")

        if rx and dec:
            rx_to_dec.append(dec.t - rx.t)
        if dec and ren:
            dec_to_render.append(ren.t - dec.t)

    print("Receiver pipeline (group by seq):")
    print_stats("rx_video -> dec_in", rx_to_dec)
    print_stats("dec_in -> render_cb", dec_to_render)


def analyze_fragments_by_id(events: List[Event]) -> None:
    by_frag = build_index(events, "fragId")

    create_to_split = []
    split_to_first_emit = []
    first_reasm_add_to_done = []

    for fragId, evs in by_frag.items():
        create = first_by_ev(evs, "frag_create")
        split = first_by_ev(evs, "frag_split")
        emit = first_by_ev(evs, "frag_emit")
        add = first_by_ev(evs, "reasm_add")
        done = first_by_ev(evs, "reasm_done")

        if create and split:
            create_to_split.append(split.t - create.t)
        if split and emit:
            split_to_first_emit.append(emit.t - split.t)
        if add and done:
            first_reasm_add_to_done.append(done.t - add.t)

    # Only print if there is at least some fragment data.
    if not (create_to_split or split_to_first_emit or first_reasm_add_to_done):
        return

    print("Fragmentation/Reassembly (group by fragId):")
    print_stats("frag_create -> frag_split", create_to_split)
    print_stats("frag_split -> frag_emit(first)", split_to_first_emit)
    print_stats("reasm_add(first) -> reasm_done", first_reasm_add_to_done)


def analyze_fragment_queue_wait(events: List[Event]) -> None:
    """Estimate sender-side queueing/backpressure before a fragment is sent over BLE.

    We correlate per fragment using (fragId, idx):
      - frag_emit: when the fragment becomes available to send
      - ble_tx_call: when we actually invoke the BLE transmit call for that fragment

    queue_delay = t(ble_tx_call) - t(frag_emit)

    This is a proxy for time spent waiting in a sending buffer/queue.
    We report a single aggregate stat (not per-idx) as requested.
    """

    def key_emit_tx(e: Event) -> Optional[tuple]:
        frag_id = e.get("fragId")
        idx = e.geti("idx")
        if frag_id is None or idx is None:
            return None
        return (frag_id, idx)

    emit_t: Dict[tuple, int] = {}
    tx_t: Dict[tuple, int] = {}

    for e in events:
        if e.ev not in ("frag_emit", "ble_tx_call"):
            continue
        k = key_emit_tx(e)
        if k is None:
            continue
        if e.ev == "frag_emit":
            prev = emit_t.get(k)
            if prev is None or e.t < prev:
                emit_t[k] = e.t
        else:
            prev = tx_t.get(k)
            if prev is None or e.t < prev:
                tx_t[k] = e.t

    delays: List[int] = []
    per_addr: Dict[str, List[int]] = {}

    # For per-addr breakdown, we need addr; it exists on ble_tx_call, so we join via key.
    tx_addr: Dict[tuple, str] = {}
    for e in events:
        if e.ev != "ble_tx_call":
            continue
        k = key_emit_tx(e)
        if k is None:
            continue
        addr = e.get("addr")
        if addr is not None:
            tx_addr[k] = addr

    for k, t_emit in emit_t.items():
        t_tx = tx_t.get(k)
        if t_tx is None:
            continue
        d = t_tx - t_emit
        if d < 0:
            continue
        delays.append(d)
        addr = tx_addr.get(k, "(unknown)")
        per_addr.setdefault(addr, []).append(d)

    if not delays:
        return

    print("Fragment send queue (frag_emit -> ble_tx_call, matched by fragId+idx):")
    print_stats("emit -> ble_tx_call", delays)
    _print_group_table("Fragment send queue by addr (top p90):", {k: v for k, v in per_addr.items() if v}, limit=8, sort_by="p90")


def analyze_scheduler_queue_delay(events: List[Event]) -> None:
    """Compute how long packets/fragments waited inside BluetoothBroadcasterScheduler.

    We match (addr, priority, transferId, fragId, idx) when available.
    For non-fragment packets, we fall back to (addr, priority, transferId).

    Metric: t(sched_deq) - t(sched_enq)
    """

    def _match_key(e: Event) -> Optional[tuple]:
        addr = e.get("addr")
        prio = e.get("priority")
        transfer_id = e.get("transferId")
        if addr is None or prio is None:
            return None

        frag_id = e.get("fragId")
        idx = e.geti("idx")
        if frag_id is not None and idx is not None:
            return (addr, prio, transfer_id, frag_id, idx)

        return (addr, prio, transfer_id)

    enq: Dict[tuple, int] = {}
    deq: Dict[tuple, int] = {}

    for e in events:
        if e.ev not in ("sched_enq", "sched_deq"):
            continue
        k = _match_key(e)
        if k is None:
            continue
        if e.ev == "sched_enq":
            prev = enq.get(k)
            if prev is None or e.t < prev:
                enq[k] = e.t
        else:
            prev = deq.get(k)
            if prev is None or e.t < prev:
                deq[k] = e.t

    delays: List[int] = []
    per_addr: Dict[str, List[int]] = {}
    per_prio: Dict[str, List[int]] = {}

    for k, t_enq in enq.items():
        t_deq = deq.get(k)
        if t_deq is None:
            continue
        d = t_deq - t_enq
        if d < 0:
            continue
        delays.append(d)
        addr = k[0] if len(k) >= 1 else "(unknown)"
        prio = k[1] if len(k) >= 2 else "(unknown)"
        per_addr.setdefault(str(addr), []).append(d)
        per_prio.setdefault(str(prio), []).append(d)

    if not delays:
        return

    print("Scheduler queue (sched_enq -> sched_deq):")
    print_stats("enq -> deq", delays)
    _print_group_table("Scheduler queue by priority (top p90):", per_prio, limit=6, sort_by="p90")
    _print_group_table("Scheduler queue by addr (top p90):", per_addr, limit=8, sort_by="p90")


def build_event_counts(events: List[Event]) -> Dict[str, int]:
    counts: Dict[str, int] = {}
    for e in events:
        counts[e.ev] = counts.get(e.ev, 0) + 1
    return counts


def infer_role(counts: Dict[str, int]) -> str:
    """Infer a device role from which events exist.

    Heuristic approach:
      - sender: camera/encoder/send pipeline events seen (cam_frame/enc_out/video_payload/mesh_send_call)
      - receiver: receive/decode/render pipeline events seen (rx_video/dec_in/render_cb)
      - relay: has rx activities + forwarding, but lacks camera+render ends

    If ambiguous, returns 'both' (sender+receiver) or 'unknown'.
    """

    sender_markers = {"cam_frame", "enc_out", "video_payload", "mesh_send_call", "tx_video"}
    receiver_markers = {"rx_video", "dec_in", "render_cb"}

    # Relay/forwarding markers (best-effort; tolerate mismatch across versions)
    relay_markers = {
        "relay_rx",
        "relay_tx",
        "mesh_relay",
        "mesh_forward",
        "forward_video",
        "route_forward",
        "sfwd",
    }

    sender_score = sum(counts.get(k, 0) for k in sender_markers)
    receiver_score = sum(counts.get(k, 0) for k in receiver_markers)
    relay_score = sum(counts.get(k, 0) for k in relay_markers)

    has_sender = sender_score > 0
    has_receiver = receiver_score > 0

    if has_sender and has_receiver:
        return "both"

    if has_sender:
        return "sender"

    if has_receiver:
        # If it receives and also forwards (or has explicit relay markers), call it relay.
        # If it only receives, call it receiver.
        if relay_score > 0 and not has_sender:
            return "relay"
        return "receiver"

    # Fallback: if we see fragmentation/reassembly only, could be relay/receiver.
    if any(k.startswith("reasm_") for k in counts.keys()) and any(k.startswith("frag_") for k in counts.keys()):
        return "relay"

    return "unknown"


def iter_log_files(dir_path: Path, recursive: bool = False) -> List[Path]:
    if not dir_path.exists() or not dir_path.is_dir():
        return []
    pattern = "**/*.log" if recursive else "*.log"
    files = [p for p in dir_path.glob(pattern) if p.is_file()]
    return sorted(files)


def summarize_file(fp: Path) -> None:
    events = parse_latency_lines(fp)
    counts = build_event_counts(events)
    role = infer_role(counts)

    print(f"\n=== {fp} ===")
    print(f"role: {role}")
    print(f"events: {len(events)}")
    _print_top_events(counts, limit=15)

    # Core analyses
    analyze_sender_by_fid(events)
    analyze_receiver_by_seq(events)
    analyze_fragments_by_id(events)
    analyze_scheduler_queue_delay(events)
    analyze_fragment_queue_wait(events)


def main(argv: List[str]) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("logs", nargs="*", help="logcat dump files")
    ap.add_argument(
        "--dir",
        type=str,
        default=None,
        help="Scan a folder for *.log files (optionally a timestamp run folder).",
    )
    ap.add_argument(
        "--recursive",
        action="store_true",
        help="When used with --dir, scan recursively for *.log.",
    )
    args = ap.parse_args(argv)

    files: List[Path] = []

    if args.dir:
        files.extend(iter_log_files(Path(args.dir), recursive=args.recursive))

    if args.logs:
        files.extend([Path(p) for p in args.logs])

    # De-dup while preserving order
    seen: set[Path] = set()
    uniq: List[Path] = []
    for f in files:
        f_abs = f.resolve()
        if f_abs in seen:
            continue
        seen.add(f_abs)
        uniq.append(f)

    if not uniq:
        ap.error("No log files provided. Pass log paths or --dir <folder>.")

    for fp in uniq:
        if not fp.exists():
            print(f"Missing file: {fp}")
            continue
        summarize_file(fp)

    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
