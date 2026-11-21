import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import ts from 'typescript';

// Run the real provider with hook state and a controllable SDK; no microphone/network is used.
const source=ts.transpileModule(fs.readFileSync(new URL('./src/components/VoiceContext.tsx',import.meta.url),'utf8'),{
 compilerOptions:{target:ts.ScriptTarget.ES2020,module:ts.ModuleKind.CommonJS,jsx:ts.JsxEmit.ReactJSX},
}).outputText;
const deferred=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b;});return {promise,resolve,reject};};
const ticks=async()=>{for(let i=0;i<12;i++)await Promise.resolve();};
const channel={id:'one',name:'Room one'};

function harness(){
 const hooks=[];let cursor=0,queued=[],user={id:7,displayName:'A'},tokenGate=null,connectGate=null,micGate=null,publishFails=false,audioFails=false;
 const rooms=[],tracks=[],notifications=[];let captureRequests=0;
 const same=(a,b)=>a&&b&&a.length===b.length&&a.every((value,i)=>Object.is(value,b[i]));
 const react={
  useRef(value){const id=cursor++;return hooks[id]??=( {current:value} );},
  useState(value){const id=cursor++;if(!hooks[id])hooks[id]={value:typeof value==='function'?value():value};return [hooks[id].value,next=>{hooks[id].value=typeof next==='function'?next(hooks[id].value):next;}];},
  useCallback(fn,deps){return this.useMemo(()=>fn,deps);},
  useMemo(fn,deps){const id=cursor++;if(!hooks[id]||!same(hooks[id].deps,deps))hooks[id]={deps,value:fn()};return hooks[id].value;},
  useEffect(effect,deps){const id=cursor++;if(!hooks[id]||!same(hooks[id].deps,deps)){const previous=hooks[id];hooks[id]={deps,cleanup:previous?.cleanup};queued.push(()=>{previous?.cleanup?.();hooks[id].cleanup=effect();});}},
 };
 // Destructured hook calls do not carry `this`.
 react.useCallback=(fn,deps)=>react.useMemo(()=>fn,deps);
 const notify=(...args)=>notifications.push(args);
 class MockRoom{
  constructor(){
   rooms.push(this);this.listeners=new Map();this.remoteParticipants=new Map();this.canPlaybackAudio=true;this.audioContext={state:'running'};this.published=[];this.disconnects=0;
   this.localParticipant={setAttributes:async()=>{},publishTrack:async track=>{if(publishFails)throw Error('publish rejected');this.published.push(track);},getTrackPublication:()=>undefined,isMicrophoneEnabled:true};
  }
  on(event,listener){this.listeners.set(event,listener);return this;}
  emit(event,...args){this.listeners.get(event)?.(...args);}
  async connect(){if(connectGate)await connectGate.promise;}
  async disconnect(){this.disconnects++;this.emit('Disconnected');}
  async startAudio(){if(audioFails)throw Error('autoplay rejected');}
 }
 const makeTrack=()=>{const track={stopped:false,stop(){this.stopped=true;}};tracks.push(track);return track;};
 const modules={
  react,'react/jsx-runtime':{jsx:(type,props)=>({type,props})},
  'livekit-client':{Room:MockRoom,RoomEvent:new Proxy({},{get:(_t,name)=>name}),Track:{Kind:{Audio:'audio'},Source:{Microphone:'microphone'}},createLocalAudioTrack:async()=>{captureRequests++;return micGate?await micGate.promise:makeTrack();}},
  '../api/client':{api:{voiceToken:async()=>tokenGate?await tokenGate.promise:{wsUrl:'mock',token:'mock'}}},
  './VoiceContextBase':{VoiceContext:{Provider:'provider'}},
  './AuthContext':{useAuth:()=>({user})},
  '../utils/webrtc-check':{checkWebRtc:()=>({ok:true})},
  '../utils/audio-gain':{MAX_MIC_VOLUME:2,MAX_REMOTE_VOLUME:1.5,clampVolume:(value,max)=>Number.isFinite(value)?Math.max(0,Math.min(max,value)):1,createMicGainProcessor:()=>({setVolume(){}})},
  './Toast':{useToast:()=>({notify})},
 };
 const sandbox={exports:{},require:name=>{if(!(name in modules))throw Error('Unexpected import '+name);return modules[name];},
  window:{addEventListener(){},removeEventListener(){}},document:{querySelectorAll:()=>[],addEventListener(){},removeEventListener(){},body:{appendChild(){}}},console};
 vm.runInNewContext(source,sandbox,{filename:'VoiceContext.cjs'});
 const render=()=>{cursor=0;const view=sandbox.exports.VoiceProvider({children:null});const effects=queued;queued=[];effects.forEach(fn=>fn());return view.props.value;};
 return {render,rooms,tracks,notifications,makeTrack,get captureRequests(){return captureRequests;},
  set user(value){user=value;},set tokenGate(value){tokenGate=value;},set connectGate(value){connectGate=value;},set micGate(value){micGate=value;},set publishFails(value){publishFails=value;},set audioFails(value){audioFails=value;}};
}

// Leaving while fetching the credential must prevent construction of a room altogether.
{
 const h=harness(),voice=h.render(),gate=deferred();h.tokenGate=gate;
 const pending=voice.join(channel);await ticks();await voice.leave(true);
 gate.resolve({wsUrl:'mock',token:'mock'});await pending;
 assert.equal(h.rooms.length,0);assert.equal(h.render().state,null);
}
// Leaving an in-flight connect must disconnect it and never begin microphone capture.
{
 const h=harness(),voice=h.render(),gate=deferred();h.connectGate=gate;
 const pending=voice.join(channel);await ticks();assert.equal(h.rooms.length,1);
 await voice.leave(true);gate.resolve();await pending;
 assert(h.rooms[0].disconnects>0);assert.equal(h.rooms[0].published.length,0);assert.equal(h.tracks.length,0);
 assert.equal(h.render().state,null);
}
// Late getUserMedia completion after leave/logout must stop its new track, never publish it.
for(const logout of [false,true]){
 const h=harness(),voice=h.render(),gate=deferred();h.micGate=gate;
 const pending=voice.join(channel);await ticks();assert.equal(h.rooms.length,1);
 if(logout){h.user=null;h.render();}else await voice.leave(true);
 const track=h.makeTrack();gate.resolve(track);await pending;
 assert.equal(track.stopped,true);assert.equal(h.rooms[0].published.length,0);assert.equal(h.render().state,null);
}
// Cancelled Room events must not detach a newer Room or remove its state.
{
 const h=harness();let voice=h.render();await voice.join(channel);const old=h.rooms[0];
 await voice.leave(true);voice=h.render();await voice.join({id:'two',name:'Room two'});
 old.emit('Disconnected');assert.equal(h.render().state.roomId,'two');
}
// A publish rejection does not automatically stop a track that never belonged to the Room.
{
 const h=harness();let voice=h.render();h.publishFails=true;await voice.join(channel);
 assert.equal(h.tracks[0].stopped,true);voice=h.render();assert.equal(voice.state.micMuted,true);
 await voice.toggleMic();assert.equal(h.tracks[1].stopped,true,'Retry capture must also stop if publication fails');
 await voice.leave(true);
}
// Audio autoplay blocking affects listening, not whether the published microphone is live.
{
 const h=harness();let voice=h.render();h.publishFails=true;await voice.join(channel);voice=h.render();
 h.publishFails=false;const gate=deferred();h.micGate=gate;
 const first=voice.toggleMic(),second=voice.toggleMic();await ticks();
 assert.equal(h.captureRequests,2,'Double click during permission/publication must create only one retry track');
 gate.resolve(h.makeTrack());await Promise.all([first,second]);assert.equal(h.rooms[0].published.length,1);
 await voice.leave(true);
}
// The initial join already owns microphone capture; its toggle cannot start another one.
{
 const h=harness(),voice=h.render(),gate=deferred();h.micGate=gate;
 const join=voice.join(channel);await ticks();await h.render().toggleMic();
 assert.equal(h.captureRequests,1);gate.resolve(h.makeTrack());await join;await voice.leave(true);
}
{
 const h=harness();let voice=h.render();h.audioFails=true;await voice.join(channel);voice=h.render();
 assert.equal(voice.state.micMuted,false);assert.equal(voice.state.audioBlocked,true);assert.equal(h.tracks[0].stopped,false);
 await voice.leave(true);
}
// Apply per-member preferences on every subscription and deafen transition.
{
 const h=harness();let voice=h.render();await voice.join(channel);const room=h.rooms[0];voice=h.render();
 const remote=sid=>({sid,kind:'audio',volume:1,setVolume(value){this.volume=value;},attach(){return {autoplay:false,style:{},setAttribute(){},remove(){}};},detach(){}});
 const a=remote('a');room.emit('TrackSubscribed',a);voice.setMemberVolume('a',0);
 const b=remote('b');room.emit('TrackSubscribed',b);assert.equal(a.volume,0,'A new participant must not unmute a muted member');
 voice.setMemberVolume('b',1.5);voice.toggleDeafen();assert.equal(a.volume,0);assert.equal(b.volume,0);
 voice.setMemberVolume('b',.5);assert.equal(b.volume,0,'Slider changes cannot override global deafen');
 voice.toggleDeafen();assert.equal(a.volume,0);assert.equal(b.volume,.5,'Undeafen restores preferences, not 100%');
 await voice.leave(true);
}
// A late playback rejection from a departed room must not block the active room.
{
 const h=harness();let voice=h.render();await voice.join(channel);
 const old=h.rooms[0], playback=deferred();
 old.startAudio=()=>playback.promise;
 old.emit('TrackSubscribed',{sid:'old-track',kind:'audio',setVolume(){},attach(){return{style:{},setAttribute(){},remove(){}};}});
 voice=h.render();await voice.join({id:'two',name:'Room two'});
 assert.equal(h.render().state.roomId,'two');
 assert.equal(h.render().state.audioBlocked,false);
 playback.reject(Error('old room playback was blocked'));await ticks();
 assert.equal(h.render().state.audioBlocked,false,'Old track playback rejection must not affect the active room');
 await voice.leave(true);
}
// Completing manual playback for a departed room cannot dismiss the active room's prompt.
{
 const h=harness();let voice=h.render();await voice.join(channel);
 const old=h.rooms[0], playback=deferred();
 old.startAudio=()=>playback.promise;
 const pending=h.render().resumeAudio();
 h.audioFails=true;voice=h.render();await voice.join({id:'two',name:'Room two'});
 assert.equal(h.render().state.roomId,'two');
 assert.equal(h.render().state.audioBlocked,true);
 playback.resolve();await pending;
 assert.equal(h.render().state.audioBlocked,true,'Old room playback success must not clear the active room prompt');
 await voice.leave(true);
}
console.log('VOICE_LIFECYCLE_PASS: leave during token/connect/mic, logout, late event isolation, member preferences and global deafen');
