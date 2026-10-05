import {initializeApp} from "firebase-admin/app";
import {getAuth} from "firebase-admin/auth";
import {getFirestore, Timestamp, Transaction, DocumentReference, FieldValue} from "firebase-admin/firestore";
import {getStorage} from "firebase-admin/storage";
import {getMessaging} from "firebase-admin/messaging";
import {onCall, HttpsError} from "firebase-functions/v2/https";
import {onDocumentCreated} from "firebase-functions/v2/firestore";
import {onSchedule} from "firebase-functions/v2/scheduler";
import {createHash, randomBytes} from "node:crypto";
import {DAY, RETENTION, validId, validLocation, safeEventTime, isDelayed} from "./policy.js";

initializeApp();
const db = getFirestore();
const region = "asia-southeast1";
type Data = Record<string, any>;
function fail(message: string): never { throw new HttpsError("invalid-argument", message); }
function id(value: unknown): string { if (!validId(value)) fail("Invalid identifier."); return value; }
function text(value: unknown, max = 200): string {
  if (typeof value !== "string" || !value.trim() || value.length > max) fail("Invalid text length.");
  return value.trim();
}
function requireOwner(value: Data | undefined, uid: string): asserts value is Data {
  if (!value || value.ownerUid !== uid) throw new HttpsError("permission-denied", "Owner access required.");
}
function eventTime(value: unknown, now: number): number {
  try { return safeEventTime(value, now); } catch { return fail("Event time is outside the retention window."); }
}
const contactRef = (uid: string, other: string) => db.doc(`users/${uid}/contacts/${other}`);
async function requireContacts(tx: Transaction, uid: string, values: unknown): Promise<string[]> {
  if (!Array.isArray(values) || values.length > 10) fail("Select up to ten trusted contacts.");
  const ids = [...new Set(values.map(id))].filter(v => v !== uid);
  for (const other of ids) {
    const [contact, profile] = await Promise.all([tx.get(contactRef(uid, other)), tx.get(db.doc(`users/${other}`))]);
    if (!contact.exists || !profile.exists || profile.get("deleting"))
      throw new HttpsError("permission-denied", "Contact is no longer available.");
  }
  return ids;
}
function notify(tx: Transaction, op: string, ownerUid: string, recipientUids: string[], title: string, referenceId: string, now: number) {
  tx.create(db.doc(`notifications/${op}`), {ownerUid, recipientUids, title, referenceId, createdAt: now, expiresAt: Timestamp.fromMillis(now + DAY)});
}

/** All client mutations are server-authorized and deduplicated in the same transaction. */
export const command = onCall({region, maxInstances: 10}, async request => {
  if (!request.auth || request.auth.token.email_verified !== true)
    throw new HttpsError("unauthenticated", "Verify your email and sign in again.");
  const uid = request.auth.uid;
  const input = request.data as Data;
  if (!input || typeof input.action !== "string") fail("Missing action.");
  const action = input.action;
  const operationId = id(input.operationId);
  const p: Data = input.payload ?? {};
  const now = Date.now();
  const opRef = db.doc(`users/${uid}/operations/${operationId}`);
  const userRef = db.doc(`users/${uid}`);
  // Object identity protects against an operation id accidentally reused with different content.
  const fingerprint = createHash("sha256").update(JSON.stringify({action, p})).digest("hex");
  return db.runTransaction(async tx => {
    const [previous, profile] = await Promise.all([tx.get(opRef), tx.get(userRef)]);
    if (profile.get("deleting")) throw new HttpsError("failed-precondition", "Account deletion is in progress.");
    if (previous.exists) {
      if (previous.get("fingerprint") !== fingerprint) throw new HttpsError("already-exists", "Operation id was reused.");
      return previous.get("result");
    }
    if (action !== "bootstrap" && !profile.exists) throw new HttpsError("failed-precondition", "Complete your profile first.");
    const rateRef = db.doc(`users/${uid}/limits/commands`);
    const rate = (await tx.get(rateRef)).data();
    const count = rate && now - rate.windowAt < 60_000 ? rate.count + 1 : 1;
    if (count > 90) throw new HttpsError("resource-exhausted", "Please wait before trying again.");
    let result: Data = {ok: true};
    switch (action) {
      case "bootstrap": {
        tx.set(userRef, {displayName: text(p.displayName ?? profile.get("displayName") ?? "RoadGuard member", 60),
          email: request.auth!.token.email ?? "", deleting: false, createdAt: profile.get("createdAt") ?? now}, {merge: true});
        break;
      }
      case "createInvite": {
        const code = randomBytes(6).toString("hex").toUpperCase();
        const hash = createHash("sha256").update(code).digest("hex");
        tx.create(db.doc(`invites/${hash}`), {ownerUid: uid, displayName: profile.get("displayName"),
          expiresAt: Timestamp.fromMillis(now + DAY), used: false});
        result = {code, expiresAt: now + DAY}; break;
      }
      case "acceptInvite": {
        const code = text(p.code, 20).toUpperCase();
        const hash = createHash("sha256").update(code).digest("hex");
        const ref = db.doc(`invites/${hash}`);
        const invite = (await tx.get(ref)).data();
        if (!invite || invite.used || invite.expiresAt.toMillis() <= now || invite.ownerUid === uid) fail("Invitation is invalid or expired.");
        const owner = await tx.get(db.doc(`users/${invite.ownerUid}`));
        if (!owner.exists || owner.get("deleting")) fail("Invitation is no longer available.");
        tx.set(contactRef(uid, invite.ownerUid), {uid: invite.ownerUid, displayName: owner.get("displayName"), acceptedAt: now});
        tx.set(contactRef(invite.ownerUid, uid), {uid, displayName: profile.get("displayName"), acceptedAt: now});
        tx.update(ref, {used: true}); break;
      }
      case "revokeContact": {
        const other = id(p.uid);
        const shared: Array<{ref: DocumentReference; remove: string}> = [];
        for (const collection of ["trips", "alerts"]) {
          for (const [owner, recipient] of [[uid, other], [other, uid]]) {
            const records = await tx.get(db.collection(collection).where("ownerUid", "==", owner).where("participantUids", "array-contains", recipient));
            records.docs.forEach(record => shared.push({ref: record.ref, remove: recipient}));
          }
        }
        for (const record of shared) tx.update(record.ref, {participantUids: FieldValue.arrayRemove(record.remove)});
        tx.delete(contactRef(uid, other)); tx.delete(contactRef(other, uid)); break;
      }
      case "startTrip": {
        const tripId = id(p.tripId);
        if (!["Driver", "Passenger"].includes(p.role)) fail("Select a travel role.");
        const recipients = await requireContacts(tx, uid, p.recipientUids);
        const tripRef = db.doc(`trips/${tripId}`);
        const existing = await tx.get(tripRef);
        const active = await tx.get(db.collection("trips").where("ownerUid", "==", uid).where("active", "==", true));
        if (existing.exists || !active.empty) throw new HttpsError("failed-precondition", "End or resume your current trip first.");
        tx.create(tripRef, {ownerUid: uid, ownerName: profile.get("displayName"), role: p.role,
          participantUids: [uid, ...recipients], active: true, phase: "Monitoring", degraded: false, createdAt: now,
          updatedAt: now, latestLocation: null, expiresAt: Timestamp.fromMillis(now + RETENTION)});
        notify(tx, `${uid}_${operationId}`, uid, recipients, "A shared trip has started", tripId, now);
        result = {tripId}; break;
      }
      case "location": {
        const ref = db.doc(`trips/${id(p.tripId)}`);
        const trip = (await tx.get(ref)).data(); requireOwner(trip, uid);
        if (!trip.active) { result = {ignored: true}; break; }
        if (!validLocation(p.location)) fail("Invalid location.");
        eventTime(p.location.capturedAt, now);
        if (!["Monitoring", "Moving", "Stopped"].includes(p.phase)) fail("Invalid trip state.");
        if (trip.latestLocation && trip.latestLocation.capturedAt >= p.location.capturedAt) { result = {ignored: true}; break; }
        const sample = {...p.location, ownerUid: uid, tripId: p.tripId, expiresAt: Timestamp.fromMillis(now + RETENTION)};
        tx.create(db.doc(`locations/${uid}_${operationId}`), sample);
        tx.update(ref, {latestLocation: p.location, updatedAt: now, phase: p.phase, degraded: p.degraded === true}); break;
      }
      case "tripEvent": {
        const ref = db.doc(`trips/${id(p.tripId)}`);
        const trip = (await tx.get(ref)).data(); requireOwner(trip, uid);
        if (!["override", "interrupted", "resumed", "impact-cancelled"].includes(p.kind)) fail("Invalid event.");
        tx.create(db.doc(`tripEvents/${uid}_${operationId}`), {ownerUid: uid, tripId: p.tripId, kind: p.kind,
          occurredAt: eventTime(p.occurredAt, now), acceptedAt: now, expiresAt: Timestamp.fromMillis(now + RETENTION)}); break;
      }
      case "endTrip": {
        const ref = db.doc(`trips/${id(p.tripId)}`);
        const trip = (await tx.get(ref)).data(); requireOwner(trip, uid);
        if (trip.active) {
          tx.update(ref, {active: false, phase: "Ended", endedAt: eventTime(p.occurredAt, now), updatedAt: now});
          notify(tx, `${uid}_${operationId}`, uid, trip.participantUids.filter((v: string) => v !== uid), "A shared trip has ended", p.tripId, now);
        }
        break;
      }
      case "shareTrip": {
        const ref = db.doc(`trips/${id(p.tripId)}`);
        const trip = (await tx.get(ref)).data(); requireOwner(trip, uid);
        const recipients = await requireContacts(tx, uid, p.recipientUids);
        tx.update(ref, {participantUids: [uid, ...recipients]}); break;
      }
      case "sendMessage": {
        const receiverUid = id(p.receiverUid);
        await requireContacts(tx, uid, [receiverUid]);
        const messageId = id(p.messageId);
        const sentAt = eventTime(p.occurredAt, now);
        const message: Data = {senderUid: uid, receiverUid, memberUids: [uid, receiverUid], kind: p.kind,
          occurredAt: sentAt, acceptedAt: now, readAt: null, expiresAt: Timestamp.fromMillis(now + RETENTION)};
        if (p.kind === "text") message.text = text(p.text, 2000);
        else if (p.kind === "voice") {
          const audioPath = `voice/${uid}/${receiverUid}/${messageId}.m4a`;
          if (p.audioPath !== audioPath || typeof p.durationMs !== "number" || p.durationMs < 1 || p.durationMs > 60_000) fail("Invalid voice note.");
          const [metadata] = await getStorage().bucket().file(audioPath).getMetadata();
          if (metadata.contentType !== "audio/mp4" || Number(metadata.size) > 2_000_000) fail("Invalid audio file.");
          message.audioPath = audioPath; message.durationMs = p.durationMs;
        } else fail("Unsupported message type.");
        tx.create(db.doc(`messages/${messageId}`), message);
        notify(tx, `${uid}_${operationId}`, uid, [receiverUid], "New RoadGuard message", messageId, now); break;
      }
      case "readMessage": {
        const ref = db.doc(`messages/${id(p.messageId)}`);
        const message = (await tx.get(ref)).data();
        if (!message || message.receiverUid !== uid) throw new HttpsError("permission-denied", "Recipient access required.");
        await requireContacts(tx, uid, [message.senderUid]);
        tx.update(ref, {readAt: now}); break;
      }
      case "createAlert": {
        const ref = db.doc(`trips/${id(p.tripId)}`);
        const trip = (await tx.get(ref)).data(); requireOwner(trip, uid);
        const recipients: string[] = [];
        // Revoked contacts never receive a later queued alert.
        for (const other of trip.participantUids as string[]) {
          if (other !== uid && (await tx.get(contactRef(uid, other))).exists) recipients.push(other);
        }
        if (!["manual", "suspected-impact"].includes(p.kind)) fail("Invalid alert kind.");
        const occurredAt = eventTime(p.occurredAt, now);
        if (p.location != null && !validLocation(p.location)) fail("Invalid location.");
        tx.create(db.doc(`alerts/${id(p.alertId)}`), {ownerUid: uid, ownerName: trip.ownerName, tripId: p.tripId,
          participantUids: [uid, ...recipients], kind: p.kind, status: "open", occurredAt, acceptedAt: now,
          delayed: isDelayed(occurredAt, now), location: p.location ?? null, acknowledgments: {}, expiresAt: Timestamp.fromMillis(now + RETENTION)});
        notify(tx, `${uid}_${operationId}`, uid, recipients, isDelayed(occurredAt, now) ? "Delayed RoadGuard safety alert" : "RoadGuard safety alert", p.alertId, now);
        result = {alertId: p.alertId, acceptedAt: now, recipientCount: recipients.length}; break;
      }
      case "acknowledgeAlert":
      case "resolveAlert": {
        const ref = db.doc(`alerts/${id(p.alertId)}`);
        const alert = (await tx.get(ref)).data();
        if (!alert) throw new HttpsError("not-found", "Alert unavailable.");
        if (action === "resolveAlert") {
          requireOwner(alert, uid);
          if (!["resolved", "cancelled"].includes(p.status)) fail("Invalid resolution.");
          tx.update(ref, {status: p.status, resolvedAt: now});
        } else {
          if (!alert.participantUids.includes(uid) || alert.ownerUid === uid) throw new HttpsError("permission-denied", "Shared contact access required.");
          await requireContacts(tx, uid, [alert.ownerUid]);
          tx.update(ref, {[`acknowledgments.${uid}`]: now});
        }
        break;
      }
      case "registerDevice": {
        tx.set(db.doc(`users/${uid}/devices/${id(p.deviceId)}`), {token: text(p.token, 4096), updatedAt: now}); break;
      }
      case "removeDevice": { tx.delete(db.doc(`users/${uid}/devices/${id(p.deviceId)}`)); break; }
      default: fail("Unknown operation.");
    }
    tx.set(rateRef, {count, windowAt: count === 1 ? now : rate!.windowAt});
    tx.create(opRef, {fingerprint, result, expiresAt: Timestamp.fromMillis(now + RETENTION)});
    return result;
  });
});

export const deliverNotification = onDocumentCreated({document: "notifications/{notificationId}", region, retry: true}, async event => {
  const data = event.data?.data(); if (!data) return;
  for (const uid of data.recipientUids as string[]) {
    if (!(await contactRef(data.ownerUid, uid).get()).exists) continue;
    const devices = await db.collection(`users/${uid}/devices`).get();
    for (const device of devices.docs) {
      try {
        await getMessaging().send({token: device.get("token"), notification: {title: data.title, body: "Open RoadGuard to view the update."},
          data: {referenceId: data.referenceId}, android: {priority: "high", notification: {channelId: "safety", tag: event.params.notificationId}}});
      } catch (error: any) {
        if (["messaging/registration-token-not-registered", "messaging/invalid-registration-token"].includes(error.code)) await device.ref.delete();
        else throw new Error("Notification transport failed; retry required.");
      }
    }
  }
});

async function deleteQuery(query: FirebaseFirestore.Query): Promise<void> {
  let page;
  do {
    page = await query.limit(200).get();
    if (page.empty) break;
    const batch = db.batch(); page.docs.forEach(d => batch.delete(d.ref)); await batch.commit();
  } while (page.size === 200);
}

async function purgeUser(uid: string): Promise<void> {
  // Auth is disabled first; the profile tombstone also denies still-valid ID tokens in rules.
  await getAuth().updateUser(uid, {disabled: true}).catch(e => { if (e.code !== "auth/user-not-found") throw e; });
  const contacts = await db.collection(`users/${uid}/contacts`).get();
  for (const contact of contacts.docs) await contactRef(contact.id, uid).delete();
  for (const collection of ["trips", "locations", "tripEvents", "alerts", "invites", "notifications"])
    await deleteQuery(db.collection(collection).where("ownerUid", "==", uid));
  for (const field of ["senderUid", "receiverUid"]) {
    const messages = await db.collection("messages").where(field, "==", uid).get();
    for (const message of messages.docs) {
      if (message.get("audioPath")) await getStorage().bucket().file(message.get("audioPath")).delete({ignoreNotFound: true});
      await message.ref.delete();
    }
  }
  for (const collection of ["trips", "alerts"]) {
    const shared = await db.collection(collection).where("participantUids", "array-contains", uid).get();
    for (const doc of shared.docs) await doc.ref.update({participantUids: FieldValue.arrayRemove(uid), [`acknowledgments.${uid}`]: FieldValue.delete()});
  }
  await getStorage().bucket().deleteFiles({prefix: `voice/${uid}/`});
  await db.recursiveDelete(db.doc(`users/${uid}`));
  await getAuth().deleteUser(uid).catch(e => { if (e.code !== "auth/user-not-found") throw e; });
}

export const deleteAccount = onCall({region, timeoutSeconds: 540}, async request => {
  if (!request.auth || Date.now()/1000 - Number(request.auth.token.auth_time) > 300)
    throw new HttpsError("unauthenticated", "Sign in again before deleting your account.");
  const uid = request.auth.uid;
  await db.doc(`users/${uid}`).set({deleting: true}, {merge: true});
  await db.doc(`deletionJobs/${uid}`).set({createdAt: Date.now()});
  await purgeUser(uid);
  await db.doc(`deletionJobs/${uid}`).delete();
  return {ok: true};
});

export const retentionSweep = onSchedule({schedule: "every 24 hours", region, timeoutSeconds: 540}, async () => {
  const now = Timestamp.now();
  const messages = await db.collection("messages").where("expiresAt", "<=", now).get();
  for (const message of messages.docs) {
    if (message.get("audioPath")) await getStorage().bucket().file(message.get("audioPath")).delete({ignoreNotFound: true});
    await message.ref.delete();
  }
  for (const collection of ["trips", "locations", "tripEvents", "alerts", "invites", "notifications"])
    await deleteQuery(db.collection(collection).where("expiresAt", "<=", now));
  await deleteQuery(db.collectionGroup("operations").where("expiresAt", "<=", now));
  // Remove abandoned uploads too (no message record was committed).
  for await (const file of getStorage().bucket().getFilesStream({prefix: "voice/"})) {
    const [metadata] = await file.getMetadata();
    if (Date.parse(metadata.timeCreated ?? "") < Date.now() - RETENTION) await file.delete({ignoreNotFound: true});
  }
  const jobs = await db.collection("deletionJobs").get();
  for (const job of jobs.docs) { await purgeUser(job.id); await job.ref.delete(); }
});
