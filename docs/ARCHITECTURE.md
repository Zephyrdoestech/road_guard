# Architecture and API contract

## Android

`RoadGuardApp` initializes Room, memory-only Firestore caching, notification channels and recovery work. `RoadGuardViewModel` owns screen state and authenticated feeds. `TripService` owns active location/activity/sensor subscriptions and never restarts silently after process death. `SafetyEngine` and `ImpactDetector` are pure Kotlin components.

The foreground service starts only from Start Trip / Resume. No background-location permission, accessibility service, custom keyboard, contacts upload, call-log access, SMS permission, or boot auto-start is requested. Location is sampled about every five seconds. Sharing targets ten-second moving updates and thirty-second stopped updates; OS scheduling and connectivity can delay them.

Moving requires GPS speed >10 km/h for 10 seconds with vehicle recognition, or 15 seconds without it. Stopped requires valid speed <3 km/h for 120 seconds. Accuracy >40 m, unknown speed, old fixes, and sample gaps do not unlock Moving. The degraded flag is independent of phase. Role is chosen by the user, not inferred from sensors. Interrupted Moving sessions preserve input protection until resumed and stopped or explicitly ended.

Room stores sessions and a priority outbox: safety alerts and their resolutions first (in original order), then trip endings, messages, and location/events. Operation IDs are generated once and retained through retries. Online start requires server acceptance before the local foreground session begins. SOS is durably enqueued first and immediately attempts synchronization; WorkManager also retries with connectivity. A response loss cannot create duplicate server records. Permanent failures remain visible and can be retried/discarded. Do not discard a queued end-trip operation unless you intend to recover that cloud session manually.

Voice recognition and recording are foreground, user-initiated sessions. Leaving the foreground cancels active microphone use. A dictated message only sends after readback and a second recognition session accepting `send`, `send message`, or `ipadala`. Incomplete voice notes are removed when abandoned; recordings already queued survive until sent, discarded, or expired.

## Callable API

All ordinary client mutations call the regional callable `command`:

```json
{"action":"createAlert","operationId":"stable-client-uuid","payload":{"tripId":"trip-uuid","alertId":"alert-uuid","kind":"manual","occurredAt":1790000000000,"location":null}}
```

Firebase callable transport wraps this object in `data`. Auth requires a verified email. The server stores a hash of each operation with its result in the same transaction as the mutation. Reusing an operation ID with another payload is rejected. IDs are 8–128 alphanumeric/underscore/hyphen characters. Each account is limited to 90 new commands per minute.

| Action | Payload / behavior |
|---|---|
| `bootstrap` | `displayName`; creates/updates the caller's profile |
| `createInvite` | Returns code and 24-hour expiry; only a hash is stored in invitation records |
| `acceptInvite` | `code`; transaction consumes invite and creates both contact links |
| `revokeContact` | `uid`; removes both links, invalidating future shared reads |
| `startTrip` | `tripId`, `role`, `recipientUids`; up to ten accepted contacts, one active trip per owner |
| `location` | `tripId`, `phase`, `degraded`, `location`; ignores older samples and ended trips |
| `tripEvent` | `tripId`, `kind`, `occurredAt`; override/interrupted/resumed/impact-cancelled |
| `shareTrip` | `tripId`, `recipientUids`; replaces authorized trip participants |
| `endTrip` | `tripId`, `occurredAt`; preserves original end time and queues a notification |
| `sendMessage` | `messageId`, `receiverUid`, `kind`, `occurredAt`; text or private audio path/duration |
| `readMessage` | `messageId`; only the receiving accepted contact can mark read |
| `createAlert` | `alertId`, `tripId`, `kind`, `occurredAt`, optional location; delayed after 60 seconds |
| `acknowledgeAlert` | `alertId`; only a currently accepted participant can acknowledge |
| `resolveAlert` | `alertId`, `status` = resolved/cancelled; owner only |
| `registerDevice` / `removeDevice` | Per-install `deviceId`, with token for registration |

Locations contain `lat`, `lng`, `accuracy` (meters), and `capturedAt` (epoch milliseconds). Server `acceptedAt`/`updatedAt` are authoritative receipt times. `occurredAt` preserves offline event timing within the 30-day retention window. Invalid future dates are rejected. No location is represented as `null`, never as invented coordinates.

`deleteAccount` is a separate callable requiring authentication within five minutes. It writes a tombstone and recovery job, disables auth, removes owned/shared data and storage, and deletes the auth account. Daily cleanup retries unfinished deletions.

## Collections and privacy

- `users/{uid}`: profile; private contacts, devices, idempotency operations, rate limits in subcollections.
- `trips`: owner, role, participant IDs, phase, active flag and latest location.
- `locations`, `tripEvents`: owner-only history and event records.
- `messages`: sender, receiver, original/accepted/read times, text or private audio metadata.
- `alerts`: incident, current status, fixed initial recipients, last known location, acknowledgment map.
- `invites`, `notifications`, `deletionJobs`: server-only records.

Clients cannot directly write Firestore. Shared read authorization checks both record participation and the current trusted relationship. Queries are constrained by owner and participant or sender/receiver, rather than fetching a whole collection. Firestore uses memory-only cache; UI clears shared feeds on contact changes and listener authorization failures. A recipient can still retain data they already viewed.

Storage paths are `voice/{sender}/{receiver}/{messageId}.m4a`, private to accepted participants, create-only, `audio/mp4`, under 2 MB. File metadata is validated again before committing a message. This is metadata validation, not a media-content malware scanner. No download tokens or public URLs are generated by the app.

Push contains generic text and an opaque record ID. Notification triggers are at-least-once: a stable notification tag replaces repeated notifications where Android supports it. Push acceptance is never presented as recipient acknowledgment. Currently all notification taps open Home; the user then navigates to the relevant message or alert.

Cloud cleanup runs daily; the deletion SLA is 30 days plus at most one normal sweep interval. Clients hide expired records, and storage reads enforce age. Location/event reads also enforce expiry. Record access is always scoped to ownership/relationship even during the cleanup interval. Logs must not contain raw payloads, location samples, audio, tokens or message content.
