import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import ts from 'typescript';

// Execute the shipped client, resolving its one runtime dependency without a bundler or server.
const transpile = text => ts.transpileModule(text,{compilerOptions:{target:ts.ScriptTarget.ES2020,module:ts.ModuleKind.ESNext}}).outputText;
const dataUrl = text => `data:text/javascript;base64,${Buffer.from(text).toString('base64')}`;
const cacheUrl=dataUrl(transpile(await fs.readFile(new URL('./src/features/note-blob-cache.ts',import.meta.url),'utf8')));
const clientText=transpile(await fs.readFile(new URL('./src/api/client.ts',import.meta.url),'utf8')).replace("'../features/note-blob-cache'",JSON.stringify(cacheUrl));
const values=new Map();
globalThis.localStorage={getItem:key=>values.get(key)??null,setItem:(key,value)=>values.set(key,value),removeItem:key=>values.delete(key)};
const client=await import(dataUrl(clientText));
const {api,notesApi,setTokens,clearTokens,getToken,getRefreshToken,setUnauthorizedHandler}=client;
const deferred=()=>{let resolve;return{promise:new Promise(done=>{resolve=done;}),resolve:value=>resolve(value)};};
const json=(body,status=200)=>new Response(JSON.stringify(body),{status,headers:{'Content-Type':'application/json'}});
const stale=error=>error.code==='SESSION_CHANGED';
let unauthorized=0;setUnauthorizedHandler(()=>unauthorized++);
const tick=()=>new Promise(resolve=>setTimeout(resolve,0));

// Storage reads from separate tabs can interleave even within a synchronous caller.
setTokens('account-a','refresh-a');
const ordinaryGetItem=localStorage.getItem;let tokenReads=0,captureFetches=0;
localStorage.getItem=key=>{
 const value=ordinaryGetItem(key);
 if(key==='gmsr.token' && ++tokenReads===1)localStorage.setItem(key,'account-b');
 return value;
};
globalThis.fetch=async()=>{captureFetches++;return json({id:1});};
try {await assert.rejects(api.me(),stale);assert.equal(captureFetches,0,'Do not send an A token under B\'s request generation');}
finally {localStorage.getItem=ordinaryGetItem;}

// Many expired requests from one account consume exactly one refresh token.
setTokens('expired-shared','refresh-shared');const shared=deferred();let sharedRefreshes=0;
globalThis.fetch=async(url,options)=>{
 if(url==='/api/auth/refresh'){sharedRefreshes++;return shared.promise;}
 return options.headers.get('gmsr-token')==='expired-shared'?json({},401):json({id:1});
};
const concurrent=Array.from({length:6},()=>api.me());await tick();assert.equal(sharedRefreshes,1);
shared.resolve(json({token:'renewed-shared',refreshToken:'renewed-refresh'}));await Promise.all(concurrent);

// A's in-flight refresh must never replace B's access/refresh tokens or replay A's mutation as B.
for(const status of [200,401]){
 setTokens('account-a','refresh-a');const refresh=deferred();let refreshStarted=false,writes=0;
 globalThis.fetch=async url=>{
  if(url==='/api/auth/refresh'){refreshStarted=true;return refresh.promise;}
  writes++;return json({},401);
 };
 const save=api.updateDisplayName('A 的新名字');const rejected=assert.rejects(save,stale);
 while(!refreshStarted)await tick();
 clearTokens();setTokens('account-b','refresh-b');refresh.resolve(json({token:'late-a',refreshToken:'late-refresh-a'},status));
 await rejected;assert.equal(getToken(),'account-b');assert.equal(getRefreshToken(),'refresh-b');assert.equal(writes,1);assert.equal(unauthorized,0);
}

// A late 401 or a delayed success body from an old session cannot affect the current one.
for(const status of [200,401]){
 setTokens('account-a','refresh-a');const response=deferred();globalThis.fetch=()=>response.promise;
 const old=api.me();const rejected=assert.rejects(old,stale);setTokens('account-b','refresh-b');response.resolve(json({id:1},status));await rejected;
 assert.equal(getToken(),'account-b');assert.equal(unauthorized,0);
}
setTokens('account-a','refresh-a');const body=deferred();let reading=false;
globalThis.fetch=async()=>({ok:true,status:200,text:()=>{reading=true;return body.promise;}});
const oldBody=api.me();const rejectedBody=assert.rejects(oldBody,stale);while(!reading)await tick();
setTokens('account-b','refresh-b');body.resolve('{"id":1}');await rejectedBody;

// Requests that arrive late after the same session's successful refresh reuse that new token.
setTokens('expired-a','refresh-a');const late=deferred();let refreshes=0;
globalThis.fetch=async(url,options)=>{
 if(url==='/api/auth/refresh'){refreshes++;return json({token:'current-a',refreshToken:'next-a'});}
 if(url==='/api/auth/display-name' && options.headers.get('gmsr-token')==='expired-a')return late.promise;
 if(options.headers.get('gmsr-token')==='expired-a')return json({},401);
 return json({id:1});
};
const lateRequest=api.updateDisplayName('same account');await api.me();await tick();
late.resolve(json({},401));await lateRequest;assert.equal(refreshes,1);assert.equal(getToken(),'current-a');

// An absent refresh token cannot leave a permanently cached failed refresh promise.
setTokens('no-refresh',null);globalThis.fetch=async()=>json({},401);
await assert.rejects(api.me(),error=>error.code==='UNAUTHORIZED');assert.equal(getToken(),null);
setTokens('expired-again','available-refresh');
globalThis.fetch=async(url,options)=>url==='/api/auth/refresh'?json({token:'valid-again',refreshToken:'valid-refresh'}):options.headers.get('gmsr-token')==='expired-again'?json({},401):json({id:2});
await api.me();assert.equal(getToken(),'valid-again');

// Cached private images must be refetched after another tab changes the stored account.
setTokens('account-a','refresh-a');let images=0;
globalThis.fetch=async()=>{images++;return new Response('image',{headers:{'Content-Type':'image/png'}});};
await notesApi.preview('private-image');await notesApi.preview('private-image');assert.equal(images,1);
localStorage.setItem('gmsr.token','account-b-in-another-tab');await notesApi.preview('private-image');assert.equal(images,2);
const cachedImage=notesApi.preview('private-image');const rejectedImage=assert.rejects(cachedImage,stale);
setTokens('account-c','refresh-c');await rejectedImage;
console.log('AUTH_SESSION_PASS: stale refresh/401/success isolation, no cross-account mutation replay, delayed 401 reuse, missing-refresh recovery and cross-tab image isolation');
