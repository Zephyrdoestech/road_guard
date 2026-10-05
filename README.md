# RoadGuard

Native Android safety pilot by WeCare. Kotlin / Jetpack Compose with Firebase Authentication, Firestore, Cloud Functions, Storage and FCM. Android 10+, targeting Android 16 (API 36).

The original concept note and supplied logo remain in the repository root. This implementation supports **personal phones**: typing protection applies inside RoadGuard only. It does not disable other apps or replace emergency services.

## Build the Android app

Prerequisites: JDK 17 or 21, Android SDK platform 36 and build tools 36.0.0. Open this directory in Android Studio, or create an ignored `local.properties` with your `sdk.dir`.

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The debug-signed APK is `app/build/outputs/apk/debug/app-debug.apk`. Without Firebase configuration it opens the branded onboarding screen with an explicit setup notice; account actions are disabled. No sample contacts, fake location, simulated delivery, or embedded production keys are used.

## Connect a development backend

1. Create **separate** Firebase development and pilot projects. Enable Email/Password Authentication, Firestore, Cloud Storage, Cloud Functions and Cloud Messaging. Functions, scheduled cleanup and Storage require an appropriate billing plan.
2. Register Android package `com.wecare.roadguard`. Download the project's `google-services.json` to `app/google-services.json` (ignored by Git). The Google Services plugin is applied only when this file exists.
3. Create Firestore and Storage in a suitable region. Functions use `asia-southeast1`; keep the Android `FUNCTIONS_REGION` constant and backend region consistent.
4. Enable Maps SDK for Android in the project's Google Cloud console. Restrict its key by package and signing-certificate SHA-1. Set `MAPS_API_KEY=your-key` in your **user-level** `~/.gradle/gradle.properties`, or pass `-PMAPS_API_KEY=...` locally. Set API quotas and billing alerts. Never commit credentials.
5. Install Node.js 22 and install/deploy the backend:

```powershell
npm --prefix functions ci
npm --prefix functions test
.\functions\node_modules\.bin\firebase.cmd login
.\functions\node_modules\.bin\firebase.cmd deploy --project YOUR_DEVELOPMENT_PROJECT --only firestore,storage,functions
```

6. Accept Firebase's prompt to enable Storage rules' cross-service Firestore access. Wait for Firestore indexes to finish building. Configure Authentication email templates and a real support contact.
7. Rebuild, install on two test phones, register two accounts and verify their email addresses. Create/accept a circle invitation, select the contact, and start a trip from the visible app. The receiving phone must also allow notifications.

There is deliberately no automatic cloud deployment or project selection. The repository has not been connected to a real Firebase project.

## Implemented behavior

- Verified email/password registration, sign-in, password reset, profile setup and reauthenticated account deletion.
- Single-use, 24-hour hashed invitations; mutual contact acceptance and immediate server-side revocation.
- User-started foreground trip monitoring, driver/passenger role, selected recipients, location readiness and persistent notification.
- GPS plus activity context; moving/stopped hysteresis; degraded state; safe interruption/resume; logged 60-second typing override.
- Disabled Compose text fields globally while driving, plus a send-side guard against sending typed text during protection.
- Selected-contact live location, stale timestamps, accuracy, optional Google map, start/end notifications, and in-trip recipient editing.
- Two-second manual SOS, offline outbox, delayed-alert labeling, distinct server acceptance and contact acknowledgment, cancellation/resolution.
- Dictated messages with TTS readback and spoken confirmation, voice notes up to 60 seconds, authenticated private playback, sent/read states, and system dialer handoff.
- Room persistence, ordered operation queue, WorkManager recovery, transactional server idempotency and permanent-failure drafts.
- Experimental multi-sensor suspected-impact candidates with a 30-second audible countdown. Available only in internal debug builds; release builds keep it off until `IMPACT_VALIDATED` is deliberately changed after validation.
- Daily retention cleanup (30 days, plus up to one sweep interval), orphan audio cleanup, deletion-job recovery, private device tokens and generic push content.

See [architecture and API contract](docs/ARCHITECTURE.md), [pilot validation](docs/PILOT_VALIDATION.md), and [release operations](docs/RELEASE.md).

## Verification

```powershell
# Unit tests and APK/lint
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug

# Compose input-protection test, with an Android emulator/device connected
.\gradlew.bat :app:connectedDebugAndroidTest

# TypeScript build and policy tests
npm --prefix functions test

# Real Firebase emulator authorization and callable-operation integration tests
.\functions\node_modules\.bin\firebase.cmd emulators:exec --project demo-roadguard --only auth,functions,firestore,storage "npm --prefix functions run test:emulators"
```

Emulator tests use a `demo-` project with synthetic identities and no real notifications. They do not establish field detection accuracy, push latency, crash detection, or battery performance.

### Run the Android app against local emulators

Remove any `app/google-services.json` while building this opt-in local test configuration. The app rejects combining a real project configuration with emulator mode. FCM registration is skipped because Firebase provides no FCM emulator.

```powershell
# Terminal 1
.\functions\node_modules\.bin\firebase.cmd emulators:start --project demo-roadguard --only auth,functions,firestore,storage

# Terminal 2: synthetic local test accounts only
$env:FIREBASE_AUTH_EMULATOR_HOST='127.0.0.1:9099'
$env:FIRESTORE_EMULATOR_HOST='127.0.0.1:8080'
node functions/test/seed.emulator.mjs
.\gradlew.bat :app:installDebug -PUSE_FIREBASE_EMULATORS=true
```

Use an Android Studio emulator (the app connects through `10.0.2.2`). Sign in as `driver@roadguard.test` or `family@roadguard.test`, password `RoadGuard-test-123`. These fixture credentials apply only to the local demo project. The authenticated UI labels this as an emulator build. Omit the Gradle property to return to the normal configuration.
