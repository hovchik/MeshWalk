# Productivity & Usability Feature Suggestions

Suggested features to improve the day-to-day usability and productivity of MeshWalk,
organized by priority and effort level.

---

## High Priority — Core UX Improvements

### 1. Message Search
Search through chat history by keyword. Essential for finding information in
conversations, especially in disaster-response scenarios where specific instructions
or coordinates were shared earlier.

**Scope**: Add full-text search to Room database queries, search bar UI in chat list
and individual chat screens.

### 2. Read Receipts & Delivery Indicators
Show message status progression: Sent → Delivered → Read. The routing layer already
tracks `DeliveryStatus` (PENDING, SENT, DELIVERED, FAILED). Extend this to include
a READ status triggered when the recipient opens the conversation.

**Scope**: New ACK packet type for "read", update `DeliveryStatus` enum, add status
icons in chat bubbles (single check, double check, colored double check).

### 3. Quick Reply from Notification
Allow users to reply directly from the Android notification without opening the app.
Critical for rapid communication in the field.

**Scope**: Add `RemoteInput` to the foreground service notification, wire it into
`SendMessageUseCase`.

### 4. Unread Message Badges & Counts
Show unread counts on the conversation list and on the bottom navigation bar. Currently
there's no visual indicator of which conversations have new messages.

**Scope**: Track last-read message ID per conversation in Room, compute unread count
via DAO query, surface in `ChatListViewModel`.

### 5. Contact Nicknames & Favorites
Let users assign custom nicknames to peers (especially useful when peers use anonymous
identities) and mark frequent contacts as favorites for quick access.

**Scope**: New `nickname` and `isFavorite` columns on the peer entity, edit UI in peer
detail screen, sort favorites to top of peer/chat lists.

---

## Medium Priority — Communication Enhancements

### 6. Message Reactions (Emoji Responses)
Lightweight reactions (thumbs up, check mark, etc.) that require minimal bandwidth —
ideal for mesh networks where every byte counts. A reaction can be a small packet
(message ID + reaction code) instead of a full text message.

**Scope**: New `REACTION` packet type, reaction picker UI, reaction display under
chat bubbles.

### 7. Reply-to / Quoted Messages
Tap a message to reply with the original quoted inline. Helps maintain context in
group conversations where multiple threads overlap.

**Scope**: Add `replyToMessageId` field to the message model, quote bubble UI
component, scroll-to-original on tap.

### 8. Pinned Messages
Allow pinning important messages (meeting point coordinates, emergency instructions)
to the top of a chat. Especially valuable in disaster-response group chats.

**Scope**: `isPinned` flag on messages, pinned banner at top of chat screen, pin/unpin
action in message context menu.

### 9. Predefined Quick Messages
A configurable set of one-tap messages like "I'm OK", "Need help", "On my way",
"Send location". Reduces typing in urgent situations and saves bandwidth.

**Scope**: Quick-message bar above the keyboard, user-customizable presets stored in
DataStore preferences.

### 10. Typing Indicators
Show when a peer is actively composing a message. Use a lightweight ephemeral packet
that doesn't get stored or routed beyond direct connections (TTL=1) to minimize
network overhead.

**Scope**: New `TYPING` ephemeral packet type, typing indicator animation in chat UI,
auto-expire after 5 seconds of inactivity.

---

## Medium Priority — Network & Reliability

### 11. Peer Signal Strength Indicator
Show approximate connection quality (strong/medium/weak) for each connected peer
based on RSSI for BLE or connection latency for Nearby. Helps users understand
why messages might be slow.

**Scope**: Expose RSSI/latency metrics from transport layer, signal strength icon
in peer list and chat header.

### 12. Battery-Aware Mesh Mode
Offer low-power mode that reduces scan frequency, limits relay forwarding, and
disables BLE discovery when battery is below a configurable threshold. Preserves
device battery for essential communication.

**Scope**: `BatteryManager` listener, configurable threshold in settings, reduced
scan intervals in `TransportManager`.

### 13. Network Health Dashboard (Free Tier)
Move basic network stats (connected peers count, messages relayed, uptime) out from
behind the premium paywall. Keep advanced diagnostics (per-peer latency, route
tables, packet traces) as premium.

**Scope**: Split `DiagnosticsScreen` into free summary + premium detail views.

### 14. Auto-Retry Failed Messages
When a message fails, offer a one-tap retry button and optionally enable auto-retry
when the peer reconnects. The store-and-forward queue handles offline peers, but
explicit failures (encryption errors, transport drops) currently require manual resend.

**Scope**: Retry button on failed message bubbles, watch `TransportEvent.PeerConnected`
to auto-flush failed messages to reconnected peers.

---

## Lower Priority — Polish & Delight

### 15. Dark/Light Theme Toggle
The app uses Material3 dynamic theming but doesn't offer an explicit dark/light/system
toggle in settings. Add a three-way theme selector.

**Scope**: Theme preference in DataStore, theme selector in `SettingsScreen`, apply in
`MeshWalkTheme`.

### 16. Chat Wallpapers / Color Themes
Let users personalize individual chat backgrounds. Small touch that improves the
experience for daily-use scenarios beyond emergency communication.

**Scope**: Per-conversation color/wallpaper preference, background modifier on chat
screen composable.

### 17. Message Timestamps Toggle
Option to show full timestamps on every message vs. grouped date headers only.
Useful when reviewing message timelines for coordination.

**Scope**: Preference toggle, conditional timestamp rendering in chat bubble component.

### 18. Swipe Actions on Chat List
Swipe-to-archive, swipe-to-pin, swipe-to-mute on the conversation list for faster
chat management.

**Scope**: `SwipeToDismiss` composable wrapping chat list items, corresponding
repository actions.

### 19. Haptic Feedback for Key Actions
Subtle vibration on message sent, message received (when app is open), and peer
connected/disconnected. Provides tactile confirmation without needing to look at
the screen.

**Scope**: `HapticFeedback` integration in relevant composables and the foreground
service.

### 20. Accessibility Improvements
- Content descriptions on all icons and interactive elements
- Screen reader announcement for new messages
- High-contrast mode option
- Minimum touch target sizes (48dp per Material guidelines)
- Keyboard navigation support

**Scope**: Audit all composables for `contentDescription`, add `LiveRegion`
announcements, verify touch targets.

---

## Implementation Effort Matrix

| # | Feature                      | Effort   | Impact   | Dependencies        |
|---|------------------------------|----------|----------|---------------------|
| 1 | Message Search               | Medium   | High     | Room FTS            |
| 2 | Read Receipts                | Medium   | High     | New packet type     |
| 3 | Quick Reply from Notification| Low      | High     | RemoteInput API     |
| 4 | Unread Badges                | Low      | High     | DAO query           |
| 5 | Nicknames & Favorites        | Low      | Medium   | Schema migration    |
| 6 | Message Reactions            | Medium   | Medium   | New packet type     |
| 7 | Reply-to / Quoted Messages   | Medium   | Medium   | Schema migration    |
| 8 | Pinned Messages              | Low      | Medium   | Schema migration    |
| 9 | Quick Messages               | Low      | High     | DataStore           |
| 10| Typing Indicators            | Medium   | Low      | Ephemeral packets   |
| 11| Signal Strength              | Medium   | Medium   | Transport metrics   |
| 12| Battery-Aware Mode           | Medium   | High     | BatteryManager      |
| 13| Free Network Dashboard       | Low      | Medium   | UI refactor only    |
| 14| Auto-Retry Failed            | Low      | High     | Event listener      |
| 15| Theme Toggle                 | Low      | Low      | DataStore           |
| 16| Chat Wallpapers              | Low      | Low      | Per-chat preference |
| 17| Timestamp Toggle             | Low      | Low      | Preference          |
| 18| Swipe Actions                | Low      | Medium   | Compose gesture     |
| 19| Haptic Feedback              | Low      | Low      | HapticFeedback API  |
| 20| Accessibility                | Medium   | High     | Full audit          |

---

## Recommended Implementation Order

**Phase 1 — Quick wins with high impact** (1-2 weeks):
- #4 Unread Badges
- #3 Quick Reply from Notification
- #9 Predefined Quick Messages
- #14 Auto-Retry Failed Messages
- #15 Theme Toggle

**Phase 2 — Core communication features** (2-4 weeks):
- #1 Message Search
- #2 Read Receipts
- #5 Contact Nicknames & Favorites
- #7 Reply-to / Quoted Messages

**Phase 3 — Network intelligence & reliability** (2-3 weeks):
- #12 Battery-Aware Mode
- #11 Signal Strength Indicator
- #13 Free Network Dashboard

**Phase 4 — Polish & completeness** (2-3 weeks):
- #6 Message Reactions
- #8 Pinned Messages
- #18 Swipe Actions
- #20 Accessibility Improvements
- #10 Typing Indicators
- #16-17, #19 Minor polish items
