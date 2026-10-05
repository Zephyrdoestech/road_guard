import {test} from 'node:test';
import assert from 'node:assert/strict';
import {validId, validLocation, safeEventTime, isDelayed, canReadTrip, RETENTION} from '../lib/policy.js';
test('reject path injection and malformed ids', () => {
  assert.equal(validId('../users/admin'), false); assert.equal(validId('event_123456'), true);
});
test('validate locations without treating 0,0 as missing', () => {
  assert.equal(validLocation({lat:0,lng:0,accuracy:5,capturedAt:100}),true);
  assert.equal(validLocation({lat:91,lng:0,accuracy:5,capturedAt:100}),false);
  assert.equal(validLocation({lat:NaN,lng:0,accuracy:5,capturedAt:100}),false);
});
test('preserve offline event times and label delayed delivery', () => {
  const now=RETENTION*2; assert.equal(safeEventTime(now-120000,now),now-120000);
  assert.equal(isDelayed(now-120000,now),true);
  assert.throws(()=>safeEventTime(now-RETENTION-1,now));
  assert.throws(()=>safeEventTime(now+120000,now));
});
test('revocation removes access even for an existing participant', () => {
  const t={ownerUid:'owner',participantUids:['friend']};
  assert.equal(canReadTrip('friend',t,true),true); assert.equal(canReadTrip('friend',t,false),false);
  assert.equal(canReadTrip('stranger',t,true),false); assert.equal(canReadTrip('owner',t,false),true);
});
