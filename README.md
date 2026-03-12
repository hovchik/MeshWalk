# MeshWalk

**Offline mesh communication for Android** — chat without internet using nearby phones as relay stations.

## Overview

MeshWalk turns Android phones into nodes in a local mesh network. Messages hop between devices using Bluetooth and Wi-Fi Direct, enabling communication even without cellular or internet connectivity.

### Key Features

- **Offline mesh networking** — No internet required
- **Multi-hop message delivery** — Messages travel through intermediate relay nodes
- **End-to-end encryption** — AES-256-GCM + ECDH P-256 key agreement
- **Named, anonymous, and temporary identities**
- **1:1 chat, group chat, and broadcast messaging**
- **Network graph visualization** — See the mesh topology in real-time
- **Store-and-forward** — Messages queue for offline recipients
- **Battery-aware scanning** — Aggressive, balanced, and battery saver modes
- **Premium billing abstraction** — Feature gating with graceful free-tier fallback

---

## Setup & Building

### Requirements

- Android Studio Ladybug (2024.2+) or later
- JDK 17+
- Android SDK 35
- Google Play Services (for Nearby Connections API)
- Minimum 2 physical Android devices (API 29+) for testing

### Quick Start

1. Clone or unzip the project
2. Open in Android Studio
3. Generate the Gradle wrapper (not included in zip):
   ```bash
   gradle wrapper --gradle-version 8.9
   ```
4. Sync Gradle
5. Add a launcher icon:
   - Right-click `app/src/main/res` → New → Image Asset
   - Name it `ic_launcher`
6. Build and run:
   ```bash
   ./gradlew assembleDebug
   ```
7. Install on **2+ physical devices** to test mesh communication

### API Keys & External Services

MeshWalk is designed to work **fully offline** with no external API keys required for core functionality. All encryption, routing, and transport operates locally.

| Service | Key Required? | Notes |
|---------|:---:|-------|
| Google Nearby Connections | No | Bundled with Google Play Services |
| Android Keystore | No | Hardware-backed, on-device |
| Google Play Billing | Optional | Only for premium features; falls back to free tier |

**If adding Google Play Billing** in production:
- Add `com.android.vending.BILLING` permission to `AndroidManifest.xml`
- Add `play-services-billing:7.0.0` dependency
- Implement `BillingProvider` interface in `com.meshwalk.app.billing`
- Replace `FreeTierBillingProvider` with your `GooglePlayBillingProvider`

---

## Permissions

All permissions are requested at runtime. The app degrades gracefully if individual permissions are denied.

| Permission | Required For | Denial Behavior |
|-----------|-------------|-----------------|
| `BLUETOOTH_SCAN` | Discover nearby BLE devices | BLE discovery disabled |
| `BLUETOOTH_CONNECT` | Establish Bluetooth connections | BT transport unavailable |
| `BLUETOOTH_ADVERTISE` | Make this node discoverable | Node invisible to BLE |
| `ACCESS_FINE_LOCATION` | BLE scanning (Android requirement) | BLE discovery disabled |
| `NEARBY_WIFI_DEVICES` | Wi-Fi Direct discovery (API 33+) | WiFi transport unavailable |
| `POST_NOTIFICATIONS` | Foreground service notification (API 33+) | Silent service |
| `FOREGROUND_SERVICE` | Keep mesh running in background | Service cannot start |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Android 14+ service type declaration | Required on API 34+ |

---

## Architecture

```
┌─────────────────────────────────────────┐
│                   UI                     │
│  (Jetpack Compose + Material3 + MVVM)   │
├─────────────────────────────────────────┤
│              Domain Layer                │
│  (Use Cases + Repository Interfaces)     │
├────────┬──────────┬──────────┬──────────┤
│ Crypto │ Routing  │Transport │  Data    │
│        │          │          │          │
│ Keys   │ Engine   │ Nearby   │ Room DB  │
│ Session│ Table    │ BLE      │ DataStore│
│Envelope│ Dedup    │ Manager  │ Mappers  │
│ Group  │ Queue    │          │          │
├────────┴──────────┴──────────┴──────────┤
│     Billing │ Mesh Foreground Service    │
└─────────────────────────────────────────┘
```

### Package Structure

| Package | Responsibility |
|---------|---------------|
| `ui.*` | Compose screens, ViewModels (MVVM), Material3 theme |
| `domain.model` | Core business models (MeshPacket, MeshMessage, NodeIdentity, etc.) |
| `domain.repository` | Repository interfaces (dependency inversion) |
| `domain.usecase` | Business logic and port interfaces |
| `data.local` | Room database, DAOs, entities, type converters |
| `data.repository` | Repository implementations |
| `data.mapper` | Entity ↔ domain model mappers with safe JSON/enum parsing |
| `crypto.keys` | ECDH/ECDSA key generation, Android Keystore, key storage |
| `crypto.session` | Session establishment, symmetric key ratcheting |
| `crypto.envelope` | AES-256-GCM encryption/decryption, ECDSA signing |
| `crypto.group` | Sender Keys model for group encryption |
| `transport.api` | Transport abstraction, events, node advertisement |
| `transport.nearby` | Google Nearby Connections P2P_CLUSTER implementation |
| `transport.ble` | BLE advertising + scanning (discovery only) |
| `transport.manager` | Multi-transport coordinator, packet serialization |
| `routing.engine` | Hybrid flood + distance-vector routing, ACKs |
| `routing.table` | ConcurrentHashMap routing table, best-route selection |
| `routing.dedup` | 5-minute packet deduplication cache |
| `routing.queue` | Store-and-forward offline queue (24h expiry) |
| `billing` | Premium feature abstraction, free-tier fallback |
| `mesh.service` | Android foreground service |
| `mesh.outbox` | Use-case → routing bridge (encrypt + send) |
| `di` | Hilt DI modules |

---

## Security Architecture

### Encryption Model

- **Identity keys**: ECDSA (P-256) signing + ECDH (P-256) key agreement per node
- **Session keys**: Derived via ECDH → HKDF → AES-256 symmetric keys
- **Message encryption**: AES-256-GCM with 12-byte nonce and authenticated additional data
- **Group encryption**: Sender Keys model (each member has a chain key; one encrypt, all decrypt)
- **Local storage**: Keys stored as Base64 in DataStore (Android Keystore for production hardening)
- **Integrity**: ECDSA signatures on message envelopes
- **Replay protection**: Message ID deduplication + timestamp validation

### Threat Model

| Threat | Mitigation |
|--------|-----------|
| Relay reading messages | E2E encryption — relays see only encrypted blobs |
| Message tampering | ECDSA signatures + GCM authentication tags |
| Replay attacks | Packet deduplicator + unique message IDs |
| Identity spoofing | Contact verification via safety number fingerprints |
| Local device theft | Android Keystore hardware-backed key protection |
| Traffic analysis | Partially mitigated; relay nodes can observe patterns |
| Malformed data from peers | Safe JSON/enum parsing with Timber logging, no crashes |

---

## Billing & Premium Features

MeshWalk uses a provider-agnostic billing abstraction (`BillingProvider` interface) so the app compiles and runs without Google Play Billing SDK. The default `FreeTierBillingProvider` activates automatically when no billing client is available.

| Feature | Free | Premium |
|---------|:----:|:-------:|
| 1:1 Chat | ✓ | ✓ |
| Group size | 5 max | 50 max |
| Store-and-forward queue | 100 | 1000 |
| Message retention | 7 days | 90 days |
| Priority relay routing | — | ✓ |
| Diagnostics screen | — | ✓ |
| Multiple identities | — | ✓ |

Check premium status via `FeatureGate`:
```kotlin
@Inject lateinit var featureGate: FeatureGate

if (members.size < featureGate.maxGroupSize) { /* allow add */ }
if (featureGate.canAccessDiagnostics) { /* show diagnostics */ }
```

---

## Testing

### Unit Tests

```bash
./gradlew test
```

Included test suites:

| Suite | Tests | Covers |
|-------|:-----:|--------|
| `AesGcmTest` | 6 | AES-256-GCM round-trip, wrong key, wrong AAD, tampered ciphertext, empty/large payloads |
| `PacketDeduplicatorTest` | 6 | Dedup detection, expiry, cache eviction, concurrent access |
| `OfflineQueueTest` | 7 | Enqueue/dequeue, expiry, size limits, destination filtering |
| `NodeAdvertisementTest` | 4 | Binary serialization round-trip, edge cases |
| `DomainModelTest` | 5+ | MeshPacket relay/flags, NodeIdentity fingerprint |

### Instrumented Tests (requires device)

```bash
./gradlew connectedCheck
```

### Manual Testing Checklist

1. **Pair test**: Install on 2 devices, verify peer discovery within ~30 seconds
2. **Message delivery**: Send a message, verify delivery status transitions (PENDING → SENT → DELIVERED)
3. **Relay test**: Place device C between A and B (out of direct range), verify multi-hop delivery
4. **Offline queue**: Send message while recipient is offline, bring online, verify delivery
5. **Identity types**: Create Named, Anonymous, and Temporary identities; verify expiry
6. **Group chat**: Create group with 3+ devices, verify fan-out delivery
7. **Network graph**: Open Network tab, verify topology visualization updates in real-time
8. **Battery modes**: Switch between Aggressive/Balanced/Battery Saver, verify scan intervals change

### Adding Tests

- Unit tests go in `app/src/test/java/com/meshwalk/app/`
- Instrumented tests go in `app/src/androidTest/java/com/meshwalk/app/`
- Use `kotlinx-coroutines-test` for testing coroutine-based code
- Room DAOs can be tested with `room-testing` in-memory database

---

## App Screens

| Screen | Description |
|--------|-------------|
| **Onboarding** | 3-page intro explaining mesh concepts |
| **Identity Setup** | Create named/anonymous/temporary identity with error handling |
| **Chats** | List of 1:1 conversations with unread badges |
| **Chat Detail** | Message bubbles, delivery status icons, encryption indicator, error snackbar |
| **Groups** | Group creation with member selection, error state handling |
| **Peers** | Discovered nearby nodes with connection type and start-chat button |
| **Network Map** | Interactive Canvas visualization of mesh topology with pinch-to-zoom |
| **Settings** | Scan mode, relay config, appearance, identity display |
| **Diagnostics** | Routing table, queue stats, real-time event log (premium) |

---

## Known Limitations

1. **Google Play Services required** for Nearby Connections (primary transport)
2. **Background restrictions**: Android aggressively kills background services; foreground notification required
3. **No true Curve25519**: Android Keystore doesn't universally support it; using P-256 as practical alternative
4. **Wi-Fi Direct**: Only one group per device at a time
5. **BLE payload size**: Limited to ~20-512 bytes; not suitable for message content
6. **Range**: BT ~10-30m, WiFi ~50-200m
7. **Max concurrent connections**: ~5-8 (hardware dependent)
8. **Forward secrecy degraded** in store-and-forward scenarios
9. **No emulator testing**: Requires real hardware with Bluetooth/Wi-Fi
10. **Billing**: Free-tier only until Google Play Billing provider is implemented

---

## Future Improvements

- [ ] libsodium-jni for true Curve25519/Ed25519
- [ ] Full Double Ratchet protocol for forward secrecy
- [ ] Wi-Fi Aware transport for compatible devices
- [ ] Google Play Billing provider implementation
- [ ] File/image sharing
- [ ] Voice messages
- [ ] QR code contact exchange
- [ ] Message expiration (disappearing messages)
- [ ] Encrypted local backup/export
- [ ] Mesh simulation mode for testing
- [ ] Android Wear companion for notifications

---

## License

MIT
