#!/usr/bin/env python3
"""Parse structured `latency` logcat lines and compute per-stage latency stats.

Expected input lines contain key=value pairs produced by com.bitchat.android.util.LatencyLog,
for example:
  D/latency: ev=cam_frame t=123 fid=7 w=640 h=480 rid=...

This script is intentionally tolerant: it ignores non-latency lines and lines missing fields.

Usage:
  python3 tools/parse_latency_logcat.py deviceA.log [deviceB.log ...]

Output:
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
    ms = [ns_to_ms(v) for v in vals]
    return {
        "count": len(ms),
        "avg_ms": statistics.mean(ms),
        "p50_ms": statistics.median(ms),
        "min_ms": min(ms),
        "max_ms": max(ms),
    }


def print_stats(name: str, values_ns: List[int]) -> None:
    s = stats_ms(values_ns)
    if not s:
        print(f"  {name}: no data")
        return
    print(
        f"  {name}: count={s['count']} avg={s['avg_ms']:.2f}ms p50={s['p50_ms']:.2f}ms "
        f"min={s['min_ms']:.2f}ms max={s['max_ms']:.2f}ms"
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


def main(argv: List[str]) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("logs", nargs="+", help="logcat dump files")
    args = ap.parse_args(argv)

    for p in args.logs:
        fp = Path(p)
        if not fp.exists():
            print(f"Missing file: {fp}")
            continue

        events = parse_latency_lines(fp)
        print(f"\n=== {fp} ===")
        print(f"events: {len(events)}")

        # Event counts
        counts: Dict[str, int] = {}
        for e in events:
            counts[e.ev] = counts.get(e.ev, 0) + 1
        top = sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))
        print("Top events:")
        for ev, c in top[:20]:
            print(f"  {ev}: {c}")

        analyze_sender_by_fid(events)
        analyze_receiver_by_seq(events)
        analyze_fragments_by_id(events)

    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))

