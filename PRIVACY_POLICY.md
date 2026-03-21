# Privacy Policy for MeshWalk

**Last updated: March 21, 2026**

Hovhannes Grigoryan ("we", "us", or "our") built the MeshWalk app as a freemium application. This Privacy Policy explains how we collect, use, and protect your information when you use our app.

## 1. Information We Collect

### 1.1 Information You Provide

- **Display Name**: If you choose a named identity, we store the display name you enter. You may also use anonymous or temporary identities that do not require a display name.
- **Messages**: Text messages you send through the mesh network are stored locally on your device.

### 1.2 Information Generated Automatically

- **Cryptographic Keys**: The app generates encryption key pairs (ECDSA P-256 for signing, ECDH P-256 for key agreement) stored securely on your device using the Android Keystore.
- **Peer Discovery Data**: When communicating via the mesh network, the app processes nearby device identifiers, connection types, signal strength, and hop counts to facilitate message routing. This data is stored locally and is not transmitted to any remote server.
- **Routing Data**: The app maintains a local routing table to deliver messages across the mesh network.

### 1.3 Information We Do NOT Collect

- We do **not** collect your GPS location. Bluetooth and Wi-Fi permissions are used solely for mesh network discovery and communication, not for tracking your location.
- We do **not** collect contacts, photos, camera data, or any files from your device.
- We do **not** use analytics, crash reporting, or tracking libraries (no Firebase Analytics, Google Analytics, Crashlytics, or similar services).
- We do **not** collect advertising identifiers or serve ads.

## 2. How Your Data Is Stored

All data is stored **locally on your device only**:

| Data | Storage Method | Encryption |
|------|---------------|------------|
| Messages & conversations | Local Room database | On-device |
| Cryptographic keys | Android Keystore + DataStore | Hardware-backed when available |
| Peer & routing info | Local Room database | On-device |
| Group metadata | Local Room database | On-device |

**No data is transmitted to or stored on any external server for core app functionality.**

## 3. End-to-End Encryption

All messages are protected with end-to-end encryption:

- **AES-256-GCM** for message confidentiality and authentication
- **ECDH P-256** for secure key agreement between peers
- **ECDSA P-256** for message signing and identity verification

Only the intended recipient(s) can read your messages. Relay nodes that forward messages through the mesh network cannot decrypt their content.

## 4. Permissions

The app requests the following permissions, all of which are used exclusively for mesh network functionality:

| Permission | Purpose |
|-----------|---------|
| Bluetooth (Scan, Connect, Advertise) | Discover and connect to nearby mesh nodes via Bluetooth Low Energy |
| Location (Fine, Coarse) | Required by Android for Bluetooth Low Energy scanning — not used to track your location |
| Wi-Fi State (Access, Change) | Manage Wi-Fi Direct connections for mesh communication |
| Nearby Wi-Fi Devices (Android 13+) | Discover Wi-Fi Direct peers |
| Notifications (Android 13+) | Display foreground service notifications while the mesh is active |
| Foreground Service | Keep the mesh network running in the background |

All permissions are requested at runtime. If you deny a permission, the corresponding transport (Bluetooth or Wi-Fi Direct) will be unavailable, but the app will continue to function with the remaining transport.

## 5. Third-Party Services

### Google Play Billing

If you choose to purchase a premium subscription, the transaction is processed by Google Play. We do not collect or store your payment information. Google's privacy policy governs the processing of payment data: [https://policies.google.com/privacy](https://policies.google.com/privacy)

### Google Nearby Connections API

The app uses the Google Nearby Connections API for peer-to-peer device discovery and data transfer over Bluetooth and Wi-Fi Direct. This API operates locally between devices and does not transmit data to Google's servers for the purpose of mesh communication.

## 6. Data Retention

- **Messages**: Retained locally for 7 days (free tier) or 90 days (premium tier), after which they are automatically deleted.
- **Temporary Identities**: Automatically expire and are deleted after 24 hours.
- **All Other Data**: Retained on your device until you clear app data or uninstall the app.

You can delete all your data at any time by clearing the app's data in your device settings or by uninstalling the app.

## 7. Data Sharing

We do **not** share, sell, or transfer your personal data to any third parties. Since all data is stored locally on your device, we have no access to it.

The only exception is if you choose to use Google Play Billing for premium features, in which case Google processes the transaction according to their own privacy policy.

## 8. Children's Privacy

MeshWalk is not directed at children under the age of 13. We do not knowingly collect personal information from children under 13. If you believe a child under 13 has provided personal information through the app, please contact us so we can take appropriate action.

## 9. Security

We take the security of your data seriously:

- All messages are end-to-end encrypted with AES-256-GCM
- Cryptographic keys are protected by the Android Keystore (hardware-backed when available)
- No data leaves your device without your explicit action
- The app does not require internet access for core functionality

## 10. Changes to This Privacy Policy

We may update this Privacy Policy from time to time. We will notify you of any changes by posting the new Privacy Policy within the app and updating the "Last updated" date above. You are advised to review this Privacy Policy periodically for any changes.

## 11. Contact Us

If you have any questions or concerns about this Privacy Policy, please contact us at:

**Email**: hovchik@gmail.com

---

*This privacy policy is effective as of March 21, 2026.*
