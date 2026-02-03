import subprocess
import time
import sys
import re

# ==============================================================================
# CONFIGURATION
# ==============================================================================
APP_KEYWORD = "bitchat"           # Partial name is fine
TARGET_TIMEZONE = "Asia/Hong_Kong"
LATENCY_SAMPLES = 100

# ==============================================================================
# CORE FUNCTIONS
# ==============================================================================

def run_adb_cmd(serial, cmd):
    """Runs an ADB command and returns output + return code."""
    full_cmd = ["adb", "-s", serial, "shell"] + cmd.split()
    try:
        # We join the command list for better handling of quotes if needed,
        # but standard list is safer for subprocess.
        result = subprocess.run(full_cmd, capture_output=True, text=True)
        return result.stdout.strip(), result.returncode
    except Exception as e:
        return str(e), 1

def get_devices():
    out = subprocess.check_output(["adb", "devices"]).decode("utf-8")
    lines = out.strip().split("\n")[1:]
    return [line.split()[0] for line in lines if "device" in line]

def measure_latency(serial):
    total_time = 0
    print(f"  > Measuring USB latency...", end="", flush=True)
    for _ in range(LATENCY_SAMPLES):
        start = time.time()
        run_adb_cmd(serial, "echo 1")
        end = time.time()
        total_time += (end - start)
    avg_rtt = total_time / LATENCY_SAMPLES
    print(f" Avg RTT: {avg_rtt*1000:.2f}ms")
    return avg_rtt

def restart_target_app(serial, keyword):
    print(f"  > Searching for app containing '{keyword}'...")

    # 1. Find Full Package Name
    out, _ = run_adb_cmd(serial, f"pm list packages | grep {keyword}")
    if not out:
        print(f"  [!] App not found. Skipping restart.")
        return

    # Take the first match (e.g., package:org.permissionlesstech.bitchat)
    full_pkg = out.splitlines()[0].replace("package:", "").strip()
    print(f"    Target Package: {full_pkg}")

    # 2. Kill App
    print(f"    Killing app...")
    run_adb_cmd(serial, f"am force-stop {full_pkg}")

    # 3. Find Main Activity (The "Right" Way)
    # We ask Android: "What opens when I click the icon?"
    print(f"    Resolving Main Activity...")
    out, rc = run_adb_cmd(serial, f"cmd package resolve-activity --brief {full_pkg}")

    # Output looks like:
    # org.permissionlesstech.bitchat
    #   org.permissionlesstech.bitchat/.MainActivity

    main_activity = None
    if rc == 0 and "/" in out:
        for line in out.splitlines():
            if "/" in line:
                main_activity = line.strip()
                break

    # 4. Launch App
    if main_activity:
        print(f"    Launching: {main_activity}")
        out, rc = run_adb_cmd(serial, f"am start -n {main_activity}")
        if "Error" in out:
             print(f"    [!] Launch Error: {out}")
        else:
             print(f"  [✓] App Launched Successfully.")
    else:
        # Fallback to Monkey if resolve-activity failed (e.g. older Android)
        print(f"    [!] Could not resolve activity. Trying fallback (Monkey)...")
        run_adb_cmd(serial, f"monkey -p {full_pkg} -c android.intent.category.LAUNCHER 1")

def sync_device(serial):
    print(f"--------------------------------------------------")
    print(f"Target: {serial}")

    # 1. Root
    subprocess.run(["adb", "-s", serial, "root"], stdout=subprocess.DEVNULL)

    # 2. Timezone
    run_adb_cmd(serial, "settings put global auto_time_zone 0")
    run_adb_cmd(serial, f"cmd alarm set-timezone {TARGET_TIMEZONE}")

    # 3. Time Sync
    avg_rtt = measure_latency(serial)
    one_way_delay = avg_rtt / 2

    run_adb_cmd(serial, "settings put global auto_time 0")
    now = time.time()
    target_time = now + one_way_delay

    out, rc = run_adb_cmd(serial, f"su 0 date -u @{target_time:.3f}")

    if rc == 0:
        print(f"  [✓] Time Synced.")
    else:
        print(f"  [X] Time Sync Failed: {out}")

    # 4. RESTART APP
    restart_target_app(serial, APP_KEYWORD)

# ==============================================================================
if __name__ == "__main__":
    devices = get_devices()
    if not devices:
        print("No devices found.")
    for d in devices:
        sync_device(d)
    print("--------------------------------------------------")
    print("Done.")