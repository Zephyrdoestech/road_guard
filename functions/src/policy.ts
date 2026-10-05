export const DAY = 86_400_000;
export const RETENTION = 30 * DAY;
export function validId(value: unknown): value is string {
  return typeof value === "string" && /^[a-zA-Z0-9_-]{8,128}$/.test(value);
}
export function validLocation(value: unknown): value is {lat: number; lng: number; accuracy: number; capturedAt: number} {
  if (!value || typeof value !== "object") return false;
  const p = value as Record<string, unknown>;
  return typeof p.lat === "number" && Number.isFinite(p.lat) && Math.abs(p.lat) <= 90 &&
    typeof p.lng === "number" && Number.isFinite(p.lng) && Math.abs(p.lng) <= 180 &&
    typeof p.accuracy === "number" && Number.isFinite(p.accuracy) && p.accuracy >= 0 && p.accuracy <= 100_000 &&
    typeof p.capturedAt === "number" && Number.isSafeInteger(p.capturedAt);
}
export function safeEventTime(value: unknown, now: number): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value > now + 60_000 || value < now - RETENTION)
    throw new Error("Event time is outside the retention window.");
  return Math.min(value, now);
}
export function isDelayed(createdAt: number, acceptedAt: number): boolean { return acceptedAt - createdAt > 60_000; }
export function canReadTrip(uid: string, trip: {ownerUid: string; participantUids: string[]}, trusted: boolean): boolean {
  return trip.ownerUid === uid || (trip.participantUids.includes(uid) && trusted);
}
