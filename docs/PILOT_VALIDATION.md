# Pilot evaluation and release gates

## Status

This is an implementation and automated-validation report, **not a completed field trial**. No participants have been enrolled. Driving reliability, speech accuracy, impact sensitivity, battery consumption, and notification latency have no measured field results yet. Do not quote the concept note's 95%/80% targets as achieved.

Automated verification results and generated artifacts are recorded in `docs/VERIFICATION.md` after running checks. The release build keeps experimental impact detection disabled.

## Device test matrix

Cover Android 10, 12, 14, 15 and 16; at least one Samsung, one Xiaomi/Redmi and one stock/Pixel device. Include low-memory hardware, Bluetooth audio, battery saver, notification denial, approximate location, and weak mobile data.

| Journey | Acceptance evidence |
|---|---|
| Registration | Two verified users can sign in; unverified users cannot access protected feeds or call commands |
| Contacts | Single-use/expired codes fail; removal blocks subsequent data reads and audio retrieval |
| Start/stop | Foreground notification appears; End stops updates; one active trip per owner |
| Movement | Walking, motorcycle, car, bus, traffic stops and tunnels are labeled against observer ground truth |
| Protection | Every text field blocks software and physical keyboard entry in Moving; override expires at 60 seconds |
| Interruption | Kill/reboot leaves an interrupted state; no false active-monitoring claim; explicit Resume works |
| Sharing | New/removed recipients get the intended access; >60-second old position is marked stale |
| Manual SOS | Hold for two seconds; no-location and offline variants work; original event time survives retries |
| Acknowledgment | Sender sees server acceptance separately from contact acknowledgment; cancel/resolve updates same incident |
| Dictation | Readback and spoken confirmation required; denial/no-service/noise keeps message unsent |
| Voice note | <=60 seconds, interrupted recording cleans up, private playback, read state only after playback |
| Impact | Single spike rejected; multi-signal candidate starts countdown; cancellation prevents alert |
| Retention/deletion | Remove expired records/audio and abandoned uploads; failure resumes from deletion job |

## Measurement protocol

Use a consenting observer or recorded traces; drivers must not interact with test controls while moving. Never stage collisions. Label sensor replays as synthetic, and keep raw study traces separately with explicit consent and a retention policy.

- **Driving:** Record true/false moving classifications, activation delays, missed starts and false starts. Report precision, recall, sample size, device, transport mode and GPS conditions separately. Require both precision and recall >=95% on the agreed representative dataset before claiming the 95% objective.
- **Speech:** Compare reference transcripts with recognized text; report word error rate, language/accent, noise and device. A 95% word-accuracy target means WER <=5% on that declared dataset, not every utterance.
- **Impact:** Use phone drops, potholes, hard braking, rotation and synthetic multi-sensor traces. Publish false-candidate rates. No real-crash sensitivity claim is supported by synthetic tests. Do not enable the release flag until an independent validation review accepts the results.
- **Alerts:** Capture event creation, server acceptance, device notification appearance and human acknowledgment separately; report p50/p95 latency and failure rate. Exclude offline time only if explicitly reported separately.
- **Battery:** Compare paired one-hour baseline and active trips at matched brightness/radio/route. Report battery percentage per hour, temperature, device and OS.
- **Distraction:** The 80% six-month objective needs a longitudinal study with baseline and follow-up. RoadGuard typing events cannot establish total-phone texting reduction.

## Rollout gates

1. Pass automated build/unit/rule/callable/UI checks; no critical authorization, SOS-state or data-loss defects.
2. Complete internal two-phone tests, including offline replay and account switching. Verify actual Firebase rules, storage IAM, Maps restrictions, FCM delivery and deletion jobs.
3. Complete the device matrix and measured battery/latency report.
4. Enroll 20–50 consenting pilot users; track incidents, failures, revocations and support requests weekly.
5. Expand toward 500 users only after a documented review. Impact remains disabled if evidence is insufficient.
