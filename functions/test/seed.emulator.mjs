// Explicit development fixture only. Never runs during application startup or production deployment.
import {initializeApp} from 'firebase-admin/app';
import {getAuth} from 'firebase-admin/auth';
import {getFirestore} from 'firebase-admin/firestore';
if(!process.env.FIREBASE_AUTH_EMULATOR_HOST || !process.env.FIRESTORE_EMULATOR_HOST) throw new Error('Local emulator hosts are required.');
const app=initializeApp({projectId:'demo-roadguard'});
const auth=getAuth(app),db=getFirestore(app);
for(const [uid,email,displayName] of [['test-driver-123','driver@roadguard.test','Test Driver'],['test-family-123','family@roadguard.test','Test Family']]) {
  await auth.createUser({uid,email,password:'RoadGuard-test-123',emailVerified:true,displayName}).catch(e=>{if(e.code!=='auth/uid-already-exists')throw e;});
  await db.doc(`users/${uid}`).set({displayName,email,deleting:false,createdAt:Date.now()});
}
await db.doc('users/test-driver-123/contacts/test-family-123').set({uid:'test-family-123',displayName:'Test Family',acceptedAt:Date.now()});
await db.doc('users/test-family-123/contacts/test-driver-123').set({uid:'test-driver-123',displayName:'Test Driver',acceptedAt:Date.now()});
console.log('Synthetic local accounts ready: driver@roadguard.test and family@roadguard.test; password RoadGuard-test-123.');
