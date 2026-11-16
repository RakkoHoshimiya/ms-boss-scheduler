import { JSDOM } from 'jsdom';
import fs from 'node:fs';
import assert from 'node:assert/strict';
const dom=new JSDOM('<div id="root"></div>',{url:'http://localhost/#/notes',pretendToBeVisual:true});
const w=dom.window;w.__GMSR_SKIP_VOICE__=true;
for(const k of ['window','document','localStorage','location','history','Node','Element','HTMLElement','MutationObserver','Event'])globalThis[k]=k==='window'?w:w[k];
Object.defineProperty(globalThis,'navigator',{value:w.navigator,configurable:true});
globalThis.getComputedStyle=w.getComputedStyle.bind(w);globalThis.requestAnimationFrame=cb=>setTimeout(cb,0);globalThis.cancelAnimationFrame=clearTimeout;
globalThis.matchMedia=()=>({matches:false,addListener(){},removeListener(){},addEventListener(){},removeEventListener(){}});
localStorage.setItem('gmsr.token','test');

globalThis.confirm=()=>true;
let drafts=[];let draftTrash=[];let trash=[];let notes=[];let stored=[];let stages=0;let writes=0;let failUpload=false;let delayedDraftSave=null;let rejectSourceDraft=false;let forks=0;
globalThis.fetch=async(url,opts={})=>{
 let body={};
 if(url==='/api/auth/me')body={id:7,username:'test',displayName:'测试用户',role:'VIEWER'};
 else if(url==='/api/notes/drafts')body=drafts;
 else if(url==='/api/notes/drafts/trash')body=draftTrash;
 else if(url==='/api/notes/trash')body=trash;
 else if(url==='/api/notes/drafts/fork' && opts.method==='POST'){
  forks++;const update=JSON.parse(opts.body);const copies=stored.filter(f=>update.attachmentIds.includes(f.id)).map(f=>({...f,id:'copy'+f.id}));
  let content=update.content;for(const f of copies)content=content.replaceAll('[[image:'+f.id.slice(4)+']]','[[image:'+f.id+']]');
  stored.push(...copies);body={...update,id:crypto.randomUUID(),version:0,noteId:null,sourceVersion:null,content,attachments:copies,updatedAt:new Date().toISOString()};drafts.push(body);
 }
 else if(url.startsWith('/api/notes/drafts/') && opts.method==='PUT') {
  if(rejectSourceDraft)return new Response(JSON.stringify({message:'来源攻略已删除或版本冲突',code:'CONCURRENT_UPDATE'}),{status:409});
  if(delayedDraftSave)await delayedDraftSave;
  const update=JSON.parse(opts.body);const old=drafts.find(d=>d.id===update.id);if(old)assert.equal(update.version,old.version);
  body={...update,version:old?old.version+1:0,updatedAt:new Date().toISOString(),attachments:stored.filter(f=>update.attachmentIds.includes(f.id))};drafts=[...drafts.filter(d=>d.id!==body.id),body];
 } else if(url.startsWith('/api/notes/drafts/') && opts.method==='DELETE') {
  const id=url.split('/').pop();draftTrash.push({...drafts.find(d=>d.id===id),deletedAt:new Date().toISOString()});drafts=drafts.filter(d=>d.id!==id);return new Response(null,{status:204});
 } else if(url.endsWith('/restore') && url.startsWith('/api/notes/drafts/')) {
  const id=url.split('/').at(-2);body={...draftTrash.find(d=>d.id===id),deletedAt:null};draftTrash=draftTrash.filter(d=>d.id!==id);drafts.push(body);
 } else if(url==='/api/notes/1/restore') {body={...trash[0],deletedAt:null};notes=[body];trash=[];}
 else if(url==='/api/notes/1' && opts.method==='DELETE') {trash=[{...notes[0],deletedAt:new Date().toISOString()}];notes=[];return new Response(null,{status:204});}
 else if(url==='/api/notes/attachments' && opts.method==='POST') {
  if(failUpload)return new Response(JSON.stringify({message:'test upload failure'}),{status:503});
  stages++;body={id:'image'+stages,name:'image'+stages+'.png',size:128,mediaType:'image/png'};stored.push(body);
 } else if(url.startsWith('/api/notes/attachments/')) return new Response('image bytes',{status:200});
 else if(url==='/api/notes/1/pin') {
  const update=JSON.parse(opts.body);assert.deepEqual(Object.keys(update).sort(),['pinned','version']);assert.equal(update.version,notes[0].version);notes=[{...notes[0],pinned:update.pinned,version:notes[0].version+1}];body=notes[0];
 } else if((url==='/api/notes' && opts.method==='POST') || (url==='/api/notes/1' && opts.method==='PUT')) {
  writes++;const update=JSON.parse(opts.body);assert(drafts.some(d=>d.id===update.draftId && d.version===update.draftVersion));drafts=drafts.filter(d=>d.id!==update.draftId);if(opts.method==='PUT')assert.equal(update.version,notes[0].version);body={...update,id:1,version:notes.length?notes[0].version+1:0,updatedAt:new Date().toISOString()};notes=[body];
 } else if(url==='/api/notes')body=notes;
 else if(url==='/api/notes/1')body={note:notes[0],attachments:stored.filter(f=>notes[0].attachmentIds.includes(f.id))};
 else if(url==='/api/client-info')body={mobile:false};
 return new Response(JSON.stringify(body),{status:200,headers:{'Content-Type':'application/json'}});
};
w.fetch=globalThis.fetch;
const assets=new URL('./dist/assets/',import.meta.url);
const bundle=fs.readdirSync(assets).find(x=>/^index-.*\.js$/.test(x));await import(new URL(bundle,assets));
const wait=()=>new Promise(r=>setTimeout(r,130));await wait();await wait();
const button=text=>[...document.querySelectorAll('.modal button'),...document.querySelectorAll('button')].find(b=>b.textContent===text);
const input=(el,value)=>{const proto=el.tagName==='TEXTAREA'?w.HTMLTextAreaElement.prototype:w.HTMLInputElement.prototype;Object.getOwnPropertyDescriptor(proto,'value').set.call(el,value);el.dispatchEvent(new w.Event('input',{bubbles:true}));};
const attach=async(label)=>{const element=document.querySelector('input[aria-label="'+label+'"]');Object.defineProperty(element,'files',{value:[new File(['image'],'a.png',{type:'image/png'})],configurable:true});element.dispatchEvent(new w.Event('change',{bubbles:true}));await wait();await wait();};
button('＋ 发布便签').click();await wait();
assert(document.querySelector('input[aria-label="插入正文图片"]'));
input(document.querySelector('.modal input[type="text"]'),'图文攻略');
const rich=document.querySelector('.note-rich-editor');
assert.equal(document.querySelectorAll('[contenteditable="true"]').length,1);
assert.equal(document.querySelectorAll('textarea').length,0);
rich.textContent='beforeafter';rich.dispatchEvent(new w.Event('input',{bubbles:true}));await wait();
const range=w.document.createRange();range.setStart(rich.firstChild,6);range.collapse(true);w.getSelection().removeAllRanges();w.getSelection().addRange(range);rich.dispatchEvent(new w.MouseEvent('mouseup',{bubbles:true}));
await attach('插入正文图片');
assert.equal(writes,0,'Uploading must not publish a note');
assert.equal(rich.querySelectorAll('img').length,1);
assert(rich.querySelector('img').src.startsWith('blob:'));
assert.equal(rich.firstChild.textContent,'before');
assert.equal(rich.lastChild.textContent,'after');
assert.equal(document.querySelectorAll('.note-inline-editor,.note-asset-heading,textarea').length,0);
await attach('添加底部附件');assert.equal(stages,2);assert.equal(rich.querySelectorAll('img').length,1);
failUpload=true;await attach('添加底部附件');assert(document.querySelector('[role="alert"]').textContent.includes('上传未完成'));assert.equal(writes,0);failUpload=false;
button('发布便签').click();await wait();await wait();
assert.equal(writes,1);assert.equal(notes[0].content,'before[[image:image1]]\nafter');assert.deepEqual(notes[0].attachmentIds,['image1','image2']);
assert.equal(document.querySelectorAll('.note-content img').length,1);assert.equal(document.querySelectorAll('.note-footer-attachments img').length,1);
assert.equal(document.querySelector('.note-content img').alt,'image1.png');assert.equal(document.querySelector('.note-footer-attachments img').alt,'image2.png');
const beforePin=notes[0].content;button('置顶').click();await wait();assert.equal(notes[0].content,beforePin);assert.equal(notes[0].version,1);
button('编辑').click();await wait();const edited=document.querySelector('.note-rich-editor');edited.querySelector('img').remove();edited.dispatchEvent(new w.Event('input',{bubbles:true}));await wait();button('保存修改').click();await wait();await wait();
assert.equal(document.querySelectorAll('.note-content img').length,0);assert.equal(document.querySelectorAll('.note-footer-attachments img').length,1);
assert.equal(notes[0].content,'before\nafter');assert.deepEqual(notes[0].attachmentIds,['image2']);
button('编辑').click();await wait();input(document.querySelector('.modal input[type="text"]'),'未保存修改');await wait();button('保存草稿并关闭').click();await wait();assert.equal(notes[0].title,'图文攻略');
assert.equal(drafts[0].title,'未保存修改');assert.equal(notes[0].title,'图文攻略');
// Leaving the route without closing the editor simulates losing its component on refresh.
const route = async hash => { w.location.hash=hash; await wait(); await wait(); };
const draftCard = name => [...document.querySelectorAll('article.note-card')].find(el=>el.querySelector('h3')?.textContent===name);
button('＋ 发布便签').click();await wait();input(document.querySelector('.modal input[type="text"]'),'刷新恢复测试');
const recoveryEditor=document.querySelector('.note-rich-editor');recoveryEditor.textContent='刷新后保留正文';recoveryEditor.dispatchEvent(new w.Event('input',{bubbles:true}));await wait();await attach('插入正文图片');
await new Promise(r=>setTimeout(r,1100));assert(drafts.some(d=>d.title==='刷新恢复测试'));
await route('#/changelog');await route('#/notes');
draftCard('刷新恢复测试').querySelector('button').click();await wait();
assert.equal(document.querySelector('.modal input[type="text"]').value,'刷新恢复测试');assert(document.querySelector('.note-rich-editor').textContent.includes('刷新后保留正文'));assert.equal(document.querySelectorAll('.note-rich-editor img').length,1);
button('删除草稿').click();await wait();await wait();assert(!drafts.some(d=>d.title==='刷新恢复测试'));assert(draftTrash.some(d=>d.title==='刷新恢复测试'));
[...document.querySelectorAll('button')].find(b=>b.textContent.startsWith('回收站（')).click();await wait();button('恢复草稿').click();await wait();await wait();assert(draftCard('刷新恢复测试'));
// Published content is hidden immediately, then recoverable with its attachments.
document.querySelector('button.note-card').click();await wait();await wait();assert(button('删除'),document.body.textContent);button('删除').click();await wait();await wait();assert.equal(notes.length,0);assert.equal(trash.length,1);
button('恢复攻略').click();await wait();await wait();assert.equal(notes[0].title,'图文攻略');assert.equal(trash.length,0);
// The old save reaches the server after a newer edit and the editor has unmounted.
let finishOldSave;delayedDraftSave=new Promise(resolve=>{finishOldSave=resolve;});
button('＋ 发布便签').click();await wait();input(document.querySelector('.modal input[type="text"]'),'慢请求恢复测试');
const slowEditor=document.querySelector('.note-rich-editor');slowEditor.textContent='旧快照A';slowEditor.dispatchEvent(new w.Event('input',{bubbles:true}));
await new Promise(resolve=>setTimeout(resolve,1100));
slowEditor.textContent='请求期间继续编辑的B';slowEditor.dispatchEvent(new w.Event('input',{bubbles:true}));await wait();
await route('#/changelog');finishOldSave();delayedDraftSave=null;await wait();
assert.equal(drafts.find(d=>d.title==='慢请求恢复测试').content,'旧快照A');
await route('#/notes');draftCard('慢请求恢复测试').querySelector('button').click();await wait();
assert.equal(document.querySelector('.note-rich-editor').textContent,'请求期间继续编辑的B','A slow old save must not replace newer unsent editing on restore');
// Fork the complete current document even when the original draft can no longer be saved.
const oldDraft=drafts.find(d=>d.title==='慢请求恢复测试');const oldContent=oldDraft.content;const beforeForkWrites=writes;
await attach('插入正文图片');input(document.querySelector('.modal input[type="text"]'),'另存新攻略测试');await wait();rejectSourceDraft=true;
button('另存为新攻略').click();await wait();await wait();
const copied=drafts.find(d=>d.title==='另存新攻略测试');assert.equal(forks,1);assert(copied);assert.notEqual(copied.id,oldDraft.id);
assert.equal(copied.noteId,null);assert.equal(copied.sourceVersion,null);assert.equal(oldDraft.content,oldContent);
assert(copied.content.startsWith('请求期间继续编辑的B'));assert(copied.content.includes('[[image:copy'));
assert(document.querySelector('.note-rich-editor img').dataset.noteImage.startsWith('copy'));assert.equal(writes,beforeForkWrites,'Forking must leave publishing for an explicit confirmation');
console.log('NOTE_RECOVERY_PASS: autosave, route/refresh recovery with image, private drafts, deletion and restore');
console.log('NOTE_DELAYED_SAVE_PASS: unsent edits survive a late old save across route unmount and recovery');
console.log('NOTE_FORK_PASS: unsaved document and image are forked without flushing the broken source or publishing automatically');
console.log('NOTE_COMPOSE_PASS: one document, visible cursor image, footer attachment, failure preservation, image deletion, publish and cancel');
dom.window.close();process.exit(0);
