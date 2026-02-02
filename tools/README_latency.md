# Latency log capture + parsing

This repo emits structured logcat markers with tag `latency` (see `LatencyLog`).

## 1) Capture from all connected devices

Capture only `latency` lines from **every** connected adb device:

```bash
./tools/capture_latency_all_devices.sh --clear
```

Outputs:
- `./latency_logs/<serial>.log`

Stop capturing:
- Press `Ctrl+C`

## 2) Parse captured logs

Parse one or multiple log files:

```bash
python3 ./tools/parse_latency_logcat.py ./latency_logs/*.log
```

The parser reports (per file):
- event counts
- sender-side pipeline breakdown (grouped by `fid`): `cam_frame -> enc_out -> video_payload -> mesh_send_call`
- receiver-side pipeline breakdown (grouped by `seq`): `rx_video -> dec_in -> render_cb`
- fragmentation/reassembly breakdown (grouped by `fragId`) when available

## Notes

- All deltas computed by the parser are **within the same device**, using monotonic timestamps (`elapsedRealtimeNanos`).
- Cross-device latency requires time alignment; this tool intentionally does not attempt it.

