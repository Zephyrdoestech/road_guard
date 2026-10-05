import {test, before, after} from 'node:test';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {initializeApp,deleteApp} from 'firebase-admin/app';
import {getAuth} from 'firebase-admin/auth';
import {getFirestore,Timestamp} from 'firebase-admin/firestore';
let app, db;
before(()=>{ app=initializeApp({projectId:'demo-roadguard',storageBucket:'demo-roadguard.appspot.com'},'commands'); db=getFirestore(app); });
after(async()=>{await deleteApp(app);});
async function account() {
  const email=`${randomUUID()}@roadguard.test`, password='Pilot-password-123';
  const user=await getAuth(app).createUser({email,password,emailVerified:true});
  const response=await fetch('http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=fake-key',{
    method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({email,password,returnSecureToken:true})});
  const result=await response.json(); assert.ok(result.idToken);
  const account={uid:user.uid,token:result.idToken}; await command(account,'bootstrap',{displayName:'Pilot user'}); return account;
}
async function command(account,action,payload={},operationId=randomUUID()) {
  const response=await fetch('http://127.0.0.1:5001/demo-roadguard/asia-southeast1/command',{
    method:'POST',headers:{'Content-Type':'application/json',Authorization:`Bearer ${account.token}`},body:JSON.stringify({data:{action,payload,operationId}})});
  const json=await response.json(); if(json.error) throw new Error(json.error.status+': '+json.error.message); return json.result;
}
test('invites, trips, delayed alerts, retry deduplication and revocation work end to end',async()=>{
  const owner=await account(), friend=await account(), stranger=await account();
  const invite=await command(owner,'createInvite');
  await command(friend,'acceptInvite',{code:invite.code});
  await assert.rejects(command(stranger,'acceptInvite',{code:invite.code}));
  const tripId=randomUUID();
  await command(owner,'startTrip',{tripId,role:'Driver',recipientUids:[friend.uid]});
  await assert.rejects(command(stranger,'endTrip',{tripId,occurredAt:Date.now()}));
  const operationId=randomUUID(), alertId=randomUUID();
  const payload={tripId,alertId,kind:'manual',occurredAt:Date.now()-120000,location:null};
  const first=await command(owner,'createAlert',payload,operationId);
  assert.deepEqual(await command(owner,'createAlert',payload,operationId),first);
  assert.equal((await db.doc(`alerts/${alertId}`).get()).get('delayed'),true);
  assert.equal((await db.collection('alerts').where('tripId','==',tripId).get()).size,1);
  await assert.rejects(command(owner,'createAlert',{...payload,kind:'suspected-impact'},operationId));
  await command(friend,'acknowledgeAlert',{alertId});
  await assert.rejects(command(stranger,'acknowledgeAlert',{alertId}));
  await command(owner,'revokeContact',{uid:friend.uid});
  await assert.rejects(command(friend,'acknowledgeAlert',{alertId}));
  await assert.rejects(command(owner,'sendMessage',{messageId:randomUUID(),receiverUid:friend.uid,kind:'text',text:'Hello',occurredAt:Date.now()}));
  await command(owner,'resolveAlert',{alertId,status:'resolved'});
  await command(owner,'endTrip',{tripId,occurredAt:Date.now()});
  const late=await command(owner,'location',{tripId,phase:'Moving',location:{lat:0,lng:0,accuracy:5,capturedAt:Date.now()}});
  assert.equal(late.ignored,true);
});
test('expired invitations cannot be accepted',async()=>{
  const owner=await account(), friend=await account();
  const invite=await command(owner,'createInvite');
  const docs=await db.collection('invites').where('ownerUid','==',owner.uid).get();
  await docs.docs[0].ref.update({expiresAt:Timestamp.fromMillis(Date.now()-1)});
  await assert.rejects(command(friend,'acceptInvite',{code:invite.code}));
});
test('recently authenticated account deletion removes owned data and reciprocal access',async()=>{
  const owner=await account(), friend=await account();
  const invite=await command(owner,'createInvite'); await command(friend,'acceptInvite',{code:invite.code});
  const messageId=randomUUID();
  await command(owner,'sendMessage',{messageId,receiverUid:friend.uid,kind:'text',text:'Synthetic deletion test',occurredAt:Date.now()});
  const response=await fetch('http://127.0.0.1:5001/demo-roadguard/asia-southeast1/deleteAccount',{
    method:'POST',headers:{'Content-Type':'application/json',Authorization:`Bearer ${owner.token}`},body:JSON.stringify({data:{}})});
  const result=await response.json(); assert.ok(result.result?.ok,JSON.stringify(result));
  assert.equal((await db.doc(`users/${owner.uid}`).get()).exists,false);
  assert.equal((await db.doc(`users/${friend.uid}/contacts/${owner.uid}`).get()).exists,false);
  assert.equal((await db.doc(`messages/${messageId}`).get()).exists,false);
  await assert.rejects(getAuth(app).getUser(owner.uid));
});
