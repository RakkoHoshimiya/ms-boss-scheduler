import assert from 'node:assert/strict';
import { createNoteBlobCache } from './src/features/note-blob-cache.ts';
import { JSDOM } from 'jsdom';
import fs from 'node:fs';

// Exercise transfer coalescing, progress, bounded eviction, retries and session invalidation.
let calls=0, finish, report;
const cache=createNoteBlobCache(6,5);
const loader=(_signal,progress)=>{calls++;report=progress;return new Promise(resolve=>{finish=resolve;});};
const seen=[];
const first=cache.get('a',loader,p=>seen.push(p.loaded));
const second=cache.get('a',loader);await Promise.resolve();assert.equal(calls,1);
report({loaded:2,total:4});finish(new Blob(['1234']));
assert.equal(await first.promise,await second.promise);assert(seen.includes(2));
first.unsubscribe();second.unsubscribe();
await cache.get('a',loader).promise;assert.equal(calls,1);
await cache.get('b',async()=>new Blob(['5678'])).promise;
await cache.get('a',async()=>{calls++;return new Blob(['12']);}).promise;assert.equal(calls,2);
await new Promise(resolve=>setTimeout(resolve,10));
await cache.get('a',async()=>{calls++;return new Blob(['12']);}).promise;assert.equal(calls,3);
await assert.rejects(cache.get('failure',async()=>{throw Error('offline');}).promise);
await cache.get('failure',async()=>new Blob(['ok'])).promise;
let interrupted;
const pending=cache.get('pending',signal=>new Promise((_resolve,reject)=>{interrupted=signal;signal.addEventListener('abort',()=>reject(Error('ended')));}));
await Promise.resolve();cache.clear();assert(interrupted.aborted);await assert.rejects(pending.promise);
console.log('NOTE_IMAGE_CACHE_PASS: shared transfers, progress, reuse, LRU limit, expiry, retries and session clear');

const dom=new JSDOM('<div id="root"></div>',{url:'http://localhost/#/notes',pretendToBeVisual:true});
const w=dom.window;w.__GMSR_SKIP_VOICE__=true;
for(const k of ['window','document','localStorage','location','history','Node','Element','HTMLElement','MutationObserver','Event'])globalThis[k]=k==='window'?w:w[k];
Object.defineProperty(globalThis,'navigator',{value:w.navigator,configurable:true});
globalThis.getComputedStyle=w.getComputedStyle.bind(w);globalThis.requestAnimationFrame=cb=>setTimeout(cb,0);globalThis.cancelAnimationFrame=clearTimeout;
globalThis.matchMedia=()=>({matches:false,addListener(){},removeListener(){},addEventListener(){},removeEventListener(){}});
localStorage.setItem('gmsr.token','test');
const note={id:1,version:0,title:'图片预览测试',content:'before[[image:image1]]after',pinned:false,updatedAt:new Date().toISOString()};
const file={id:'image1',name:'guide.png',size:3356784,mediaType:'image/png'};
let previews=0,originals=0,previewStream,originalStream,failPreview=true,expireAccess=false,refreshes=0;
const stream=assign=>new Response(new ReadableStream({start:assign}),{headers:{'Content-Type':'image/jpeg','Content-Length':'8'}});
globalThis.fetch=async url=>{
 let body={};
 if(url==='/api/auth/me')body={id:7,username:'test',displayName:'测试用户',role:'VIEWER'};
 else if(url==='/api/auth/login')body={token:'next-session',refreshToken:'next-refresh',user:{id:8,username:'next',displayName:'另一用户',role:'VIEWER'}};
 else if(url==='/api/auth/refresh'){refreshes++;body={token:'refreshed-session',refreshToken:'refreshed-refresh'};}
 else if(['/api/notes/drafts','/api/notes/trash','/api/notes/drafts/trash','/api/runs','/api/bosses','/api/boss-groups','/api/me/runs','/api/me/characters'].includes(url))body=[];
 else if(url==='/api/notes')body=[note];
 else if(url==='/api/notes/1')body={note,attachments:[file]};
 else if(url==='/api/notes/attachments/image1/preview'){
  previews++;if(failPreview){failPreview=false;return new Response(JSON.stringify({message:'预览暂时不可用'}),{status:503});}
  if(expireAccess){expireAccess=false;return new Response('{}',{status:401});}
  return stream(controller=>{previewStream=controller;controller.enqueue(new Uint8Array([1,2,3,4]));});
 } else if(url==='/api/notes/attachments/image1'){
  originals++;return stream(controller=>{originalStream=controller;controller.enqueue(new Uint8Array([1,2,3,4]));});
 } else if(url==='/api/client-info')body={mobile:false};
 return new Response(JSON.stringify(body),{headers:{'Content-Type':'application/json'}});
};w.fetch=globalThis.fetch;
const assets=new URL('./dist/assets/',import.meta.url);
const bundle=fs.readdirSync(assets).find(name=>/^index-.*\.js$/.test(name));await import(new URL(bundle,assets));
const wait=()=>new Promise(resolve=>setTimeout(resolve,100));await wait();await wait();
const button=text=>[...document.querySelectorAll('button')].find(el=>el.textContent===text);
const open=()=>document.querySelector('button.note-card').click();
open();await wait();await wait();
assert(document.querySelector('[role="alert"]').textContent.includes('预览暂时不可用'));
button('重试图片').click();await wait();
assert.equal(document.querySelector('progress').value,50);assert.equal(originals,0);
previewStream.enqueue(new Uint8Array([5,6,7,8]));previewStream.close();await wait();
const previewUrl=document.querySelector('.note-content img').src;assert(previewUrl.startsWith('blob:'));
button('查看原图').click();await wait();
assert.equal(document.querySelector('.note-content img').src,previewUrl,'Keep preview visible during original transfer');
assert.equal(document.querySelector('progress').value,50);
originalStream.enqueue(new Uint8Array([5,6,7,8]));originalStream.close();await wait();
assert.notEqual(document.querySelector('.note-content img').src,previewUrl);
assert([...document.querySelectorAll('a')].some(el=>el.textContent==='打开原图 ↗'));
button('关闭').click();await wait();open();await wait();await wait();
assert.equal(previews,2,'Reopening uses the completed preview cache after initial failure + retry');
button('查看原图').click();await wait();assert.equal(originals,1,'Reopening original reuses its cached bytes');
button('关闭').click();await wait();
button('退出').click();await wait();assert.equal(localStorage.getItem('gmsr.token'),null);
w.location.hash='#/login';await wait();
for(const [selector,value] of [['input[type="text"]','next'],['input[type="password"]','test-password']]){
 const el=document.querySelector(selector);Object.getOwnPropertyDescriptor(w.HTMLInputElement.prototype,'value').set.call(el,value);el.dispatchEvent(new w.Event('input',{bubbles:true}));
}
document.querySelector('form').dispatchEvent(new w.Event('submit',{bubbles:true,cancelable:true}));await wait();
w.location.hash='#/notes';await wait();await wait();expireAccess=true;open();await wait();await wait();
assert.equal(previews,4,'New session must refetch its preview, including one auth refresh retry');
assert.equal(refreshes,1);previewStream.enqueue(new Uint8Array([5,6,7,8]));previewStream.close();await wait();
assert(document.querySelector('.note-content img'),'Token rotation must not cancel the image being retried');
console.log('NOTE_IMAGE_UI_PASS: retry, streamed percentage, preview-first, explicit original, visible preview during download, reopen cache, account isolation and auth refresh');
dom.window.close();process.exit(0);
