> [!WARNING]
> This software has not received external security review and may contain vulnerabilities and may not necessarily meet its stated security goals. Do not use it for sensitive use cases, and do not rely on its security until it has been reviewed.

# biri-biri Bluetooth In Real-time Interaction

This project is a modification of **bitchat-android** toward a **real-time communication (RTC)** app.

## Goal

- **Voice & video calls** with **peer discovery and call signaling over Bluetooth LE mesh**
- **No servers required** for rendezvous/signaling in local/off-grid scenarios

## Important constraint (Bluetooth mesh vs real-time media)

Bluetooth LE mesh is well-suited for:
- discovery, presence, and small message relay (signaling)
- store-and-forward messaging

Bluetooth LE mesh is generally *not* suitable for:
- low-latency, high-throughput media transport (voice/video)
- stable continuous streams across multiple hops

## Proposed architecture

This project targets a **Bluetooth LE mesh-native RTC** stack: discovery, call control, and **media frames** are all carried over the mesh.

### 1) Mesh control/signaling (BLE)
Use the existing bitchat / BitChat mesh protocol for small control messages:
- peer discovery / presence
- call invite/accept/hangup (see `RTCSync`)
- session parameters (codec, bitrate, video mode)
- in-call control events (mute/unmute, camera toggle)

### 2) Mesh media transport (BLE)
Audio/video are transmitted as **packetized frames over the mesh**, optimized for BLE constraints:
- **Audio**: Opus frames at low bitrate with buffering to smooth multi-hop jitter
- **Video (experimental)**: low resolution/bitrate, tolerant of loss and delay

Practical constraints:
- expect higher latency and lower throughput than IP-based RTC
- multi-hop reliability is probabilistic; performance varies with topology and interference
- video will be significantly more constrained than audio

## Current status

### What works today (inherited from bitchat-android)
- **BLE mesh chat/messaging**: peer discovery, presence (ANNOUNCE), and message relay.
- **Mesh reliability tooling**: features like local neighbor sync (`REQUEST_SYNC`) and connection hardening/device monitoring exist in this repo.

### What’s implemented already (this repo)
- A **BLE-mesh RTC package** exists under `app/src/main/java/com/bitchat/android/rtc`:
  - `RTCConnectionManager` + `RTCSync` (invite/accept/hangup control)
  - Opus-based **voice streaming** over the mesh
  - basic **video streaming** plumbing (encode/decode + camera integration)

### What’s evolving / not finished yet
- **Call UX + state model**: incoming/outgoing call flows and polish are still in progress.
- **QoS/tuning** for mesh media: buffering/jitter handling, bitrate adaptation, and better behavior under packet loss.
- **Interoperability/compat**: keeping wire formats stable as RTC messages evolve.

## Roadmap (minimal milestones)

1. **Rate control**
   - adaptive audio/video bitrate based on observed loss/jitter
   - frame pacing and backpressure (don’t enqueue faster than the mesh can deliver)
   - optional drop/skip strategies (e.g., drop stale video frames first)
2. **Reduce latency**
   - tune buffering/jitter strategy for mesh hops
   - reduce fragmentation overhead where possible
3. **Routing / reduce unnecessary transfer**
   - improve targeted delivery to avoid broad rebroadcast
   - leverage/extend source-based routing to cut duplicate relays
   - smarter relay policies (TTL, dedupe, per-peer rate limits)

## License

This project inherits the public domain licensing from the upstream project. See [LICENSE](LICENSE.md).

## Upstream reference

This is based on the Android port of the original iOS app:
- iOS: https://github.com/jackjackbits/bitchat
- Android upstream: https://github.com/permissionlesstech/bitchat-android
