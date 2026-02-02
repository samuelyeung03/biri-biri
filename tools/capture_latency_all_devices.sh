#!/usr/bin/env bash
set -euo pipefail

# Capture only the `latency` tag from all connected adb devices.
# Output is written to ./latency_logs/<serial>.log
#
# Usage:
#   ./tools/capture_latency_all_devices.sh [--clear] [--dir latency_logs]
#
# Stop with Ctrl+C; all background adb logcat processes will be killed.

OUT_DIR="latency_logs"
CLEAR_FIRST=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dir)
      OUT_DIR="$2"; shift 2 ;;
    --clear)
      CLEAR_FIRST=1; shift ;;
    -h|--help)
      echo "Usage: $0 [--clear] [--dir latency_logs]"; exit 0 ;;
    *)
      echo "Unknown arg: $1"; exit 2 ;;
  esac
done

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not found in PATH" >&2
  exit 1
fi

mkdir -p "$OUT_DIR"

echo "Finding devices..."
mapfile -t DEVICES < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')

if [[ ${#DEVICES[@]} -eq 0 ]]; then
  echo "No adb devices found." >&2
  exit 1
fi

echo "Devices: ${DEVICES[*]}"

cleanup() {
  echo "Stopping logcat capture..."
  jobs -p | xargs -r kill || true
}
trap cleanup EXIT INT TERM

for serial in "${DEVICES[@]}"; do
  if [[ $CLEAR_FIRST -eq 1 ]]; then
    echo "[$serial] clearing logcat buffer"
    adb -s "$serial" logcat -c || true
  fi

  out_file="$OUT_DIR/${serial}.log"
  echo "[$serial] capturing -> $out_file"

  # Use -v threadtime for consistent prefix, but parser only needs that 'latency' appears.
  adb -s "$serial" logcat -v threadtime -s latency >"$out_file" &
done

echo "Capturing. Press Ctrl+C to stop."

# Wait forever until user interrupts.
wait

