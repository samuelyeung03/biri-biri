# Latency log capture + parsing

This repo emits structured logcat markers with tag `latency` (see `LatencyLog`).

## 1) Capture from all connected devices

Capture only `latency` lines from **every** connected adb device:

```bash
./tools/capture_latency_all_devices.sh --clear
```

Outputs:
- `./tools/latency_logs/<timestamp>/<serial>.log`

Stop capturing:
- Press `Ctrl+C`

## 2) Parse captured logs

Parse a whole capture run folder (recommended):

```bash
python3 ./tools/parse_latency_logcat.py --dir ./tools/latency_logs/<timestamp>
```

Or parse one or multiple log files:

```bash
python3 ./tools/parse_latency_logcat.py ./tools/latency_logs/<timestamp>/*.log
```

The parser reports (per file):
- inferred device role: `sender`, `receiver`, `relay`, `both`, or `unknown`
- event counts
- sender-side pipeline breakdown (grouped by `fid`): `cam_frame -> enc_out -> video_payload -> mesh_send_call`
- receiver-side pipeline breakdown (grouped by `seq`): `rx_video -> dec_in -> render_cb`
- fragmentation/reassembly breakdown (grouped by `fragId`) when available

## Notes

- All deltas computed by the parser are **within the same device**, using monotonic timestamps (`elapsedRealtimeNanos`).
- Cross-device latency requires time alignment; this tool intentionally does not attempt it.

