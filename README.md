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

**Practical design:** use BLE mesh for **signaling**, and carry the actual A/V media over an **IP-capable bearer** when possible (e.g., Wi‑Fi Direct, local Wi‑Fi LAN, or internet). This keeps the “off-grid discovery” property while making video/voice feasible.

## Proposed architecture

### 1) Mesh Signaling (BLE)
Use the existing bitchat mesh protocol to exchange:
- call invites (`CALL_OFFER`)
- call accept/decline (`CALL_ANSWER`)
- session parameters (codec preferences, network candidates, fingerprints/keys)
- call control messages (mute, hangup, renegotiation)

### 2) Media Transport (recommended)
Use a standard RTC stack (typically **WebRTC**) for:
- audio/video capture
- jitter buffering, AEC/NS/AGC
- congestion control
- NAT traversal (when internet is present)

Bearer options:
- **Wi‑Fi Direct** (best “no AP” local option on Android)
- **Local Wi‑Fi LAN**
- **Internet** (optional)

### 3) (Optional/Experimental) Voice-over-Mesh mode
If you want “voice over BLE mesh” as a research feature:
- expect **very low bitrate**, **high latency**, and **dropouts**, especially multi-hop
- requires aggressive codec settings and strong buffering
- video is not realistic over BLE mesh

## Current status

- Messaging: inherited from bitchat-android (BLE mesh)
- Calling: **not implemented yet** (this repo is being repurposed)

## Roadmap (minimal milestones)

1. **Call signaling over mesh**
   - Add new packet types for call offer/answer/hangup
   - UI: call screen + incoming call notifications
2. **WebRTC media over IP bearer**
   - Wire BLE-mesh signaling to WebRTC SDP/ICE exchange
   - Support voice first, then video
3. **Connectivity strategy**
   - Prefer Wi‑Fi Direct when available
   - Fallback to LAN/internet when permitted
   - Keep mesh-only mode for chat + signaling
4. **Security**
   - Authenticate call signaling with existing identity keys
   - Use end-to-end encryption properties from WebRTC (DTLS-SRTP) and bind identity to signaling

## License

This project inherits the public domain licensing from the upstream project. See [LICENSE](LICENSE.md).

## Upstream reference

This is based on the Android port of the original iOS app:
- iOS: https://github.com/jackjackbits/bitchat
- Android upstream: https://github.com/permissionlesstech/bitchat-android
