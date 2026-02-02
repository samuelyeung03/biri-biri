#!/usr/bin/env bash
set -euo pipefail

# Capture `latency` tag from all connected adb devices.
# Output is written to <repo>/tools/latency_logs/<timestamp>/<serial>.log (by default)
#
# Usage:
#   ./tools/capture_latency_all_devices.sh [--clear]
#                                    [--dir tools/latency_logs]
#                                    [--buffers all|main,system,crash]
#                                    [--sanity-seconds 2]
#                                    [--no-filter]
#
# Stop with Ctrl+C; all background adb logcat processes will be killed.

# Always resolve paths relative to this script, not the caller's cwd.
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"

OUT_DIR="$SCRIPT_DIR/latency_logs"
RUN_DIR=""
CLEAR_FIRST=0
# Default to all buffers so tags emitted outside main (e.g., bluetooth) aren't missed.
BUFFERS="all"
SANITY_SECONDS=2
NO_FILTER=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dir)
      # Allow absolute paths, or relative paths (relative to caller) if user passes one.
      # If you want repo-relative, pass "--dir ./tools/latency_logs".
      OUT_DIR="$2"; shift 2 ;;
    --clear)
      CLEAR_FIRST=1; shift ;;
    --buffers)
      BUFFERS="$2"; shift 2 ;;
    --sanity-seconds)
      SANITY_SECONDS="$2"; shift 2 ;;
    --no-filter)
      NO_FILTER=1; shift ;;
    -h|--help)
      echo "Usage: $0 [--clear] [--dir tools/latency_logs] [--buffers all|main,system,crash] [--sanity-seconds 2] [--no-filter]"; exit 0 ;;
    *)
      echo "Unknown arg: $1"; exit 2 ;;
  esac
done

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not found in PATH" >&2
  exit 1
fi

# Create a per-run timestamp folder so repeated captures don't overwrite each other.
TS="$(date +%Y%m%d_%H%M%S)"
RUN_DIR="$OUT_DIR/$TS"
mkdir -p "$RUN_DIR"

echo "Output folder: $RUN_DIR"

echo "Finding devices..."
mapfile -t DEVICES < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')

if [[ ${#DEVICES[@]} -eq 0 ]]; then
  echo "No adb devices found." >&2
  exit 1
fi

echo "Devices: ${DEVICES[*]}"

# Build -b args.
# Special-case "all" because some adb versions accept -b all, others require multiple -b.
# We'll prefer -b all and fall back to main on failures (sanity will warn).

declare -a BUFFER_ARGS=()
if [[ "${BUFFERS//[[:space:]]/}" == "all" ]]; then
  BUFFER_ARGS=( -b all )
else
  IFS=',' read -ra _BUFS <<< "$BUFFERS"
  for b in "${_BUFS[@]}"; do
    b_trimmed="${b//[[:space:]]/}"
    [[ -z "$b_trimmed" ]] && continue
    BUFFER_ARGS+=( -b "$b_trimmed" )
  done
  if [[ ${#BUFFER_ARGS[@]} -eq 0 ]]; then
    BUFFER_ARGS=( -b main )
  fi
fi

# We'll launch each capture in its own process group so cleanup can be reliable.
# Store PGIDs (same as PID when started via setsid).
declare -a LOGCAT_PGIDS=()

cleanup() {
  echo "Stopping logcat capture..."
  for pgid in "${LOGCAT_PGIDS[@]:-}"; do
    [[ -z "${pgid:-}" ]] && continue
    # Kill the whole process group; ignore if already dead.
    if kill -0 "$pgid" >/dev/null 2>&1; then
      kill -TERM -- "-$pgid" >/dev/null 2>&1 || true
    fi
  done
  wait >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

for serial in "${DEVICES[@]}"; do
  if [[ $CLEAR_FIRST -eq 1 ]]; then
    echo "[$serial] clearing logcat buffer(s): $BUFFERS"
    adb -s "$serial" logcat "${BUFFER_ARGS[@]}" -c >/dev/null 2>&1 || true
    sleep 0.2
  fi

  if [[ "$SANITY_SECONDS" != "0" && $NO_FILTER -eq 0 ]]; then
    # Use the correct filter syntax: "latency:D" (no spaces) + "*:S".
    if ! timeout "${SANITY_SECONDS}s" adb -s "$serial" logcat -d "${BUFFER_ARGS[@]}" -v threadtime latency:D '*:S' >/dev/null 2>&1; then
      echo "[$serial] warning: sanity check didn't find any 'latency' logs (tag=latency, level>=D)." >&2
      echo "[$serial]          If you're sure logs exist, try --no-filter or --buffers all." >&2
    fi
  fi

  out_file="$RUN_DIR/${serial}.log"
  echo "[$serial] capturing -> $out_file"

  # Write a header immediately so it's obvious the file path is correct even if no logs arrive yet.
  {
    echo "# capture started: $(date -Is)"
    echo "# serial=$serial"
    echo "# buffers=$BUFFERS no_filter=$NO_FILTER"
  } >"$out_file"

  # Ensure we flush line-by-line so short captures aren't empty due to buffering.
  # If --no-filter is set, capture everything to help debug missing logs.
  if [[ $NO_FILTER -eq 1 ]]; then
    setsid bash -c "adb -s '$serial' logcat -v threadtime ${BUFFER_ARGS[*]} | stdbuf -oL cat" >>"$out_file" 2>&1 &
  else
    setsid bash -c "adb -s '$serial' logcat -v threadtime ${BUFFER_ARGS[*]} latency:D '*:S' | stdbuf -oL cat" >>"$out_file" 2>&1 &
  fi
  LOGCAT_PGIDS+=("$!")
done

echo "Capturing. Press Ctrl+C to stop."
wait

