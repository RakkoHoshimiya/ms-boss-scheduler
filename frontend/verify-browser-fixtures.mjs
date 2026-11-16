/** Deterministic anonymous API fixtures for local browser-layout checks. */
const boss={id:1,groupId:1,groupName:'测试分组',name:'测试BOSS',tier:'N',tiers:['N'],tierLabel:'普通',capacity:6,enabled:true,sortOrder:0};
const run={id:1,bossId:1,bossName:boss.name,groupId:1,groupName:boss.groupName,tier:'N',tierLabel:'普通',runIndex:1,capacity:6,status:'PLANNED',scheduled:'周五 21:00',memberCount:0,powerTotal:0,members:[]};
export function browserFixtureFetch(backend=process.env.GMSR_BACKEND){
 const realFetch=globalThis.fetch;
 return async(input,init)=>{
  const path=typeof input==='string'?input:input.url;
  if(backend)return realFetch(new URL(path,backend),init);
  const body=path==='/api/client-info'?{device:'mobile',isMobile:true,touch:true,userAgent:'local mobile fixture'}:
   path==='/api/runs'?[run]:path==='/api/bosses'?[boss]:path==='/api/status-reset'?{nextResetAt:'2026-11-19T00:00:00Z'}:undefined;
  if(body===undefined)throw Error('Unexpected local fixture request '+path);
  return new Response(JSON.stringify(body),{headers:{'Content-Type':'application/json'}});
 };
}
