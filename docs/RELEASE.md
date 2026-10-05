# Release and operations

## Environments and signing

Use separate Firebase projects, restricted Maps keys and budgets for development and pilot. The supplied APK is debug-signed for internal installation only. For distribution create a private Android signing key, configure Android Studio's signed bundle flow (or CI secrets), and register its certificate fingerprint for Maps. Never put keystores/passwords in source control. Revalidate Play's target-SDK and permission requirements before submission.

Production-like builds must use `release`, where `INTERNAL_BUILD=false` and `IMPACT_VALIDATED=false`. Enabling impact for external users requires a deliberate reviewed source change with the validation report attached. There is no remote switch that silently enables it.

## Operational checks

- Alert on failed Functions invocations, exhausted quotas, retention job failures and repeated deletion jobs.
- Inspect Firestore indexes before onboarding testers; test all queries with deployed rules.
- Validate contact revocation on active listeners, not only one-time reads.
- Configure budgets/alerts for Firestore operations, Functions, audio storage/egress and Maps. A moving one-hour trip targets about 360 location writes plus associated operation records. Profile actual reads for active family viewers before increasing pilot size.
- Check scheduled cleanup daily and verify expired audio and deletion jobs disappear.
- FCM may be delayed or suppressed by Android. Measure real device delivery; no automatic SMS or emergency-service fallback is provided. The dialer is a user-operated fallback.
- Keep raw diagnostic payload logging disabled. Use aggregate operation/error counts and opaque IDs for support.
- Before public distribution, add abuse controls beyond the per-user command limit (for example Firebase App Check and sign-up rate controls) and complete the Play Data Safety disclosures from actual deployed behavior.

## Scope and budget

The concept note allocates PHP 300,000 development, PHP 80,000 testing, PHP 120,000 marketing, PHP 50,000 contingency. These are planning allocations, not validated supplier quotes. Cloud, Maps, device access and ongoing support need a separate operating budget based on measured pilot usage.

Deferred: fleet admin/licensing, managed-device restrictions, iOS, SMS, third-party chat, all-day automatic trip start, automated call answering, wake-word recognition and emergency dispatch. No 12–14 week field program can be replaced by a build passing locally.

## Recovery

- Queued records retain their stable operation IDs. Retry them without editing identifiers.
- Permanent failures remain in the app; check access, account state and the error before retrying.
- End Trip requires eventual server synchronization. Do not discard its operation casually. If local storage was cleared while a cloud trip remained active, recover/end that owned trip before starting another (the app's recovery controls surface it).
- If deletion fails, the account is tombstoned and disabled. The scheduled job retries cleanup; support should inspect the job rather than recreating the account.
- Restoring an interrupted local trip requires the visible Resume control. Monitoring must never be represented as running solely because a cloud trip is active.
