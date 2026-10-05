import {after, before, beforeEach, test} from 'node:test';
import {readFileSync} from 'node:fs';
import {initializeTestEnvironment, assertFails, assertSucceeds} from '@firebase/rules-unit-testing';
import {doc,setDoc,getDoc,getDocs,collection,query,where,Timestamp,deleteDoc} from 'firebase/firestore';
import {ref,uploadBytes,getBytes} from 'firebase/storage';
let env;
const expiry=Timestamp.fromMillis(Date.now()+86400000);
before(async()=>{ env=await initializeTestEnvironment({projectId:'demo-roadguard',firestore:{rules:readFileSync('../firestore.rules','utf8')},storage:{rules:readFileSync('../storage.rules','utf8')}}); });
after(async()=>{ await env?.cleanup(); });
beforeEach(async()=>{
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async context=>{
    const db=context.firestore();
    for(const uid of ['owner123','friend123','stranger123']) await setDoc(doc(db,'users',uid),{deleting:false});
    await setDoc(doc(db,'users/owner123/contacts/friend123'),{uid:'friend123'});
    await setDoc(doc(db,'users/friend123/contacts/owner123'),{uid:'owner123'});
    await setDoc(doc(db,'trips/trip12345'),{ownerUid:'owner123',participantUids:['owner123','friend123'],expiresAt:expiry});
    await setDoc(doc(db,'messages/message123'),{senderUid:'owner123',receiverUid:'friend123',memberUids:['owner123','friend123'],expiresAt:expiry});
  });
});
const context=uid=>env.authenticatedContext(uid,{email_verified:true});
test('only an owner or currently trusted participant can read a trip',async()=>{
  await assertSucceeds(getDoc(doc(context('owner123').firestore(),'trips/trip12345')));
  await assertSucceeds(getDoc(doc(context('friend123').firestore(),'trips/trip12345')));
  await assertFails(getDoc(doc(context('stranger123').firestore(),'trips/trip12345')));
});
test('queries used by Android are authorized without a broad data scan',async()=>{
  const db=context('friend123').firestore();
  await assertSucceeds(getDocs(query(collection(db,'trips'),where('ownerUid','==','owner123'),where('participantUids','array-contains','friend123'))));
  await assertSucceeds(getDocs(query(collection(db,'messages'),where('senderUid','==','owner123'),where('receiverUid','==','friend123'))));
  await assertFails(getDocs(collection(db,'trips')));
});
test('revocation prevents subsequent trip and message reads',async()=>{
  await env.withSecurityRulesDisabled(async c=>{ await deleteDoc(doc(c.firestore(),'users/owner123/contacts/friend123')); });
  await assertFails(getDoc(doc(context('friend123').firestore(),'trips/trip12345')));
  await assertFails(getDoc(doc(context('friend123').firestore(),'messages/message123')));
});
test('clients cannot forge writes, read device tokens, or reuse unverified accounts',async()=>{
  await assertFails(setDoc(doc(context('owner123').firestore(),'trips/trip12345'),{ownerUid:'owner123'}));
  await assertFails(getDoc(doc(context('friend123').firestore(),'users/owner123/devices/device123')));
  await assertFails(getDoc(doc(env.authenticatedContext('owner123',{email_verified:false}).firestore(),'trips/trip12345')));
  await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(),'trips/trip12345')));
});
test('deletion tombstones reject still-valid auth tokens',async()=>{
  await env.withSecurityRulesDisabled(c=>setDoc(doc(c.firestore(),'users/owner123'),{deleting:true}));
  await assertFails(getDoc(doc(context('owner123').firestore(),'trips/trip12345')));
});
test('audio uploads require ownership, an accepted recipient, and audio metadata',async()=>{
  const storage=context('owner123').storage();
  await assertSucceeds(uploadBytes(ref(storage,'voice/owner123/friend123/audio12345.m4a'),new Uint8Array([1,2,3]),{contentType:'audio/mp4'}));
  await assertFails(uploadBytes(ref(storage,'voice/owner123/stranger123/audio12346.m4a'),new Uint8Array([1]),{contentType:'audio/mp4'}));
  await assertFails(uploadBytes(ref(storage,'voice/owner123/friend123/audio12347.m4a'),new Uint8Array([1]),{contentType:'text/plain'}));
  await assertFails(getBytes(ref(context('stranger123').storage(),'voice/owner123/friend123/audio12345.m4a')));
});
