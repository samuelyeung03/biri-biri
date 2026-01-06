#!/usr/bin/env python3
import re
import sys
from datetime import datetime
import statistics

# latency_test.py
# Usage: python3 latency_test.py <deviceA_log> <deviceB_log>
# Parses lines that include the 'latency' tag and extracts timestamp, message, and seq.
# Matches packets by seq and computes per-step statistics using log timestamps only.

EVENT_KEYWORDS_OUT = [
    ("capture_start", "Start capture"),
    ("capture_end", "Captured audio frame"),
    ("encoding_start", "Encoding started"),
    ("encoding_end", "Encoding finished"),
    ("sending_start", "Calling meshService.sendVoice"),
    ("sending_end", "meshService.sendVoice returned"),
]

EVENT_KEYWORDS_IN = [
    ("receive_start", "handleIncomingAudio"),
    ("decoding_start", "Decoding started"),
    ("decoding_end", "Decoded voice frame"),
    ("enqueue_time", "Enqueued packet"),
    ("dequeue_time", "Dequeued packet"),
    ("play_start", "Playback started"),
    ("play_end", "Played PCM"),
    ("voice_ack", "Received VOICE_ACK"),
]

TS_LEN = 18  # 'MM-DD HH:MM:SS.mmm' length (Android logcat)


def parse_timestamp(ts_str):
    # Try with year prefix (common logs) or without year (Android logcat)
    now = datetime.now()
    formats = ['%Y-%m-%d %H:%M:%S.%f', '%m-%d %H:%M:%S.%f']
    for fmt in formats:
        try:
            dt = datetime.strptime(ts_str, fmt)
            if fmt == '%m-%d %H:%M:%S.%f':
                # assign current year
                dt = dt.replace(year=now.year)
            return dt
        except Exception:
            continue
    return None


def parse_log(path):
    # Returns two dicts: outgoing[seq] = {event: timestamp}, incoming[seq] = {event: timestamp}
    outgoing = {}
    incoming = {}
    try:
        with open(path, 'r', encoding='utf-8', errors='ignore') as f:
            for line in f:
                if 'latency' not in line and 'latency' not in line.lower():
                    continue

                if len(line) < TS_LEN:
                    continue

                ts_str = line[:TS_LEN]
                ts = parse_timestamp(ts_str)
                if ts is None:
                    # skip lines with unparseable timestamp
                    continue

                rest = line[TS_LEN:]
                # try to extract the message after the log level marker (e.g., ' D  ')
                if ' D  ' in rest:
                    msg = rest.split(' D  ', 1)[1].strip()
                else:
                    # fallback: take everything after the tag name 'latency'
                    parts = rest.split('latency')
                    msg = parts[-1].strip() if parts else rest.strip()

                # extract seq
                seq_m = re.search(r'seq=(\d+)', msg)
                if seq_m:
                    seq = int(seq_m.group(1))
                else:
                    # Some events like VOICE_ACK may still include seq but not follow same pattern; try other patterns
                    seq_m2 = re.search(r'seq\s*[:=]\s*(\d+)', msg)
                    if seq_m2:
                        seq = int(seq_m2.group(1))
                    else:
                        # If no seq, skip (we need per-packet correlation)
                        continue

                # classify event
                marked = False
                for key, kw in EVENT_KEYWORDS_OUT:
                    if kw in msg:
                        outgoing.setdefault(seq, {})[key] = ts
                        marked = True
                        break

                if marked:
                    continue

                for key, kw in EVENT_KEYWORDS_IN:
                    if kw in msg:
                        incoming.setdefault(seq, {})[key] = ts
                        marked = True
                        break

                # also consider "Encoded voice frame" as outgoing marker
                if not marked and 'Encoded voice frame' in msg:
                    outgoing.setdefault(seq, {})['encoded'] = ts
                    marked = True

    except FileNotFoundError:
        print(f"File not found: {path}")
    except Exception as e:
        print(f"Error reading {path}: {e}")

    return outgoing, incoming


def analyze_flow(name_from, name_to, out_map, in_map):
    print(f"\n=== Flow: {name_from} -> {name_to} ===")
    total_matches = 0

    cap_durs = []
    enc_durs = []
    send_api = []
    net_lat = []
    dec_durs = []
    jb = []
    play_durs = []
    e2e = []
    rtts = []

    seqs = sorted(out_map.keys())
    for seq in seqs:
        o = out_map.get(seq, {})
        i = in_map.get(seq)
        # capture duration
        if 'capture_start' in o and 'capture_end' in o:
            cap_durs.append((o['capture_end'] - o['capture_start']).total_seconds() * 1000)
        # encode
        if 'encoding_start' in o and 'encoding_end' in o:
            enc_durs.append((o['encoding_end'] - o['encoding_start']).total_seconds() * 1000)
        # sending API
        if 'sending_start' in o and 'sending_end' in o:
            send_api.append((o['sending_end'] - o['sending_start']).total_seconds() * 1000)

        if i:
            total_matches += 1
            # decoding
            if 'decoding_start' in i and 'decoding_end' in i:
                dec_durs.append((i['decoding_end'] - i['decoding_start']).total_seconds() * 1000)
            elif 'receive_start' in i and 'decoding_end' in i:
                dec_durs.append((i['decoding_end'] - i['receive_start']).total_seconds() * 1000)
            # jitter buffer residency
            if 'enqueue_time' in i and 'dequeue_time' in i:
                jb.append((i['dequeue_time'] - i['enqueue_time']).total_seconds() * 1000)
            # playback
            if 'play_start' in i and 'play_end' in i:
                play_durs.append((i['play_end'] - i['play_start']).total_seconds() * 1000)
            # network latency estimate: receive_start - sending_end
            if 'receive_start' in i and 'sending_end' in o:
                net = (i['receive_start'] - o['sending_end']).total_seconds() * 1000
                net_lat.append(net)
            # e2e: play_end - capture_end (requires clock sync)
            if 'play_end' in i and 'capture_end' in o:
                total = (i['play_end'] - o['capture_end']).total_seconds() * 1000
                e2e.append(total)
            # RTT: ack time - sending_start
            if 'voice_ack' in i and 'sending_start' in o:
                rtts.append((i['voice_ack'] - o['sending_start']).total_seconds() * 1000)

    def stats(arr):
        if not arr:
            return None
        return {
            'count': len(arr),
            'avg': statistics.mean(arr),
            'med': statistics.median(arr),
            'min': min(arr),
            'max': max(arr),
        }

    print(f"Matched packets: {total_matches} / Sent: {len(seqs)}")
    def print_stat(name, arr):
        s = stats(arr)
        if s is None:
            print(f"{name}: No data")
        else:
            print(f"{name}: count={s['count']}, avg={s['avg']:.2f}ms, med={s['med']:.2f}ms, min={s['min']:.2f}ms, max={s['max']:.2f}ms")

    print_stat('Capture duration', cap_durs)
    print_stat('Encoding latency', enc_durs)
    print_stat('Sending API overhead', send_api)
    print_stat('Network latency (recvStart - sendEnd) [requires synced clocks]', net_lat)
    print_stat('Decoding latency', dec_durs)
    print_stat('Jitter buffer residency (dequeue - enqueue)', jb)
    print_stat('Playback duration', play_durs)
    print_stat('Total E2E (captureEnd -> playEnd) [requires synced clocks]', e2e)
    print_stat('RTT (sendStart -> VOICE_ACK)', rtts)


if __name__ == '__main__':
    if len(sys.argv) < 3:
        print('Usage: python3 latency_test.py <deviceA_log> <deviceB_log>')
        sys.exit(1)

    a, b = sys.argv[1], sys.argv[2]
    print(f"Parsing logs: A={a}, B={b}")
    outA, inA = parse_log(a)
    outB, inB = parse_log(b)

    # Flow A -> B: outgoing in A compared with incoming in B
    if outA and inB:
        analyze_flow('DeviceA', 'DeviceB', outA, inB)
    else:
        print('\nNo complete A->B data to analyze')

    # Flow B -> A
    if outB and inA:
        analyze_flow('DeviceB', 'DeviceA', outB, inA)
    else:
        print('\nNo complete B->A data to analyze')
