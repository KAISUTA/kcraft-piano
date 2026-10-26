import { DurableObject } from 'cloudflare:workers';
import { validPacket } from './protocol.js';
const encoder=new TextEncoder();
const digest=async text=>Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',encoder.encode(text))),b=>b.toString(16).padStart(2,'0')).join('');
async function sign(value,secret){const key=await crypto.subtle.importKey('raw',encoder.encode(secret),{name:'HMAC',hash:'SHA-256'},false,['sign']);return Array.from(new Uint8Array(await crypto.subtle.sign('HMAC',key,encoder.encode(value))),b=>b.toString(16).padStart(2,'0')).join('');}
async function authorized(request,env){const cookie=request.headers.get('Cookie')?.match(/(?:^|;\s*)piano_access=(\d+)\.([a-f0-9]{64})(?:;|$)/);return !!cookie&&Number(cookie[1])>Date.now()&&cookie[2]===await sign(cookie[1],env.BRIDGE_SECRET);}
export default {
 async fetch(request,env){
  const url=new URL(request.url);
  if(url.pathname==='/health')return Response.json({service:'KCraftPiano',protocol:1,connection:'fixed-password'});
  const room=()=>env.ROOMS.get(env.ROOMS.idFromName('fixed-pianos-v1'),{locationHint:'apac'});
  if(url.pathname==='/api/login'){
   if(request.method!=='POST'||request.headers.get('Origin')!==url.origin)return new Response('Forbidden',{status:403});
   if(Number(request.headers.get('Content-Length')||0)>1024)return new Response('Too large',{status:413});
   return room().fetch(request);
  }
  if(url.pathname==='/api/pianos'){
   if(!await authorized(request,env))return new Response('パスワードを入力してください',{status:401});
   return room().fetch(request);
  }
  const match=/^\/(bridge|play)\/([A-Za-z0-9_-]{43})$/.exec(url.pathname);
  if(!match)return env.ASSETS.fetch(request);
  if(request.headers.get('Upgrade')?.toLowerCase()!=='websocket')return new Response('WebSocket required',{status:426});
  if(match[1]==='bridge'){
   if(!env.BRIDGE_SECRET||request.headers.get('Authorization')!==`Bearer ${env.BRIDGE_SECRET}`)return new Response('Unauthorized',{status:401});
  }else{
   if(request.headers.get('Origin')!==url.origin)return new Response('Origin rejected',{status:403});
   if(!await authorized(request,env))return new Response('Unauthorized',{status:401});
  }
  const headers=new Headers(request.headers);headers.delete('Authorization');headers.set('X-Piano-Role',match[1]);headers.set('X-Piano-Id',match[2]);headers.set('X-Piano-Name',encodeURIComponent((url.searchParams.get('name')||'piano').slice(0,48)));
  return room().fetch(new Request(request,{headers}));
 }
};
export class PianoRoom extends DurableObject {
 constructor(ctx,env){super(ctx,env);this.env=env;this.attempts=new Map();}
 async fetch(request){
  const url=new URL(request.url);
  if(url.pathname==='/api/login'){
   const ip=request.headers.get('CF-Connecting-IP')||'unknown',now=Date.now();let rate=this.attempts.get(ip);if(!rate||now-rate.start>60000){rate={start:now,count:0};this.attempts.set(ip,rate);}if(++rate.count>10)return new Response('少し待ってから再試行してください',{status:429});
   if(this.attempts.size>1000)for(const[k,v]of this.attempts)if(now-v.start>60000)this.attempts.delete(k);
   let body;try{const text=await request.text();if(text.length>1024)throw Error();body=JSON.parse(text);}catch{return new Response('Invalid request',{status:400});}
   if(typeof body.password!=='string'||!this.env.PLAY_PASSWORD_HASH||await digest(body.password)!==this.env.PLAY_PASSWORD_HASH)return new Response('パスワードが違います',{status:401});
   const expiry=String(now+30*86400000),signature=await sign(expiry,this.env.BRIDGE_SECRET);
   return Response.json({ok:true},{headers:{'Set-Cookie':`piano_access=${expiry}.${signature}; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=2592000`,'Cache-Control':'no-store'}});
  }
  if(url.pathname==='/api/pianos')return Response.json(this.ctx.getWebSockets('bridge').filter(ws=>ws.readyState===1).map(ws=>{const s=ws.deserializeAttachment();return{id:s.id,name:s.name};}).filter((s,i,a)=>a.findIndex(t=>t.id===s.id)===i),{headers:{'Cache-Control':'no-store'}});
  const role=request.headers.get('X-Piano-Role'),id=request.headers.get('X-Piano-Id');
  const bridges=this.ctx.getWebSockets('bridge:'+id).filter(ws=>ws.readyState===1);
  if(role==='play'&&!bridges.length)return new Response('ピアノが未接続です。少し待って再接続してください',{status:409});
  // A fixed URL can be reopened: newest authenticated connection takes control.
  const previous=this.ctx.getWebSockets(role+':'+id);
  if(role==='play'&&previous.length){const panic=new Uint8Array(16);panic[0]=2;for(const b of bridges)b.send(panic);}
  for(const ws of previous)ws.close(1000,'Reconnected');
  const pair=new WebSocketPair(),[client,server]=Object.values(pair);this.ctx.acceptWebSocket(server,[role,role+':'+id]);
  server.serializeAttachment({role,id,name:decodeURIComponent(request.headers.get('X-Piano-Name')||'piano'),count:0});server.send(JSON.stringify({type:'ready',role}));return new Response(null,{status:101,webSocket:client});
 }
 webSocketMessage(ws,data){
  const state=ws.deserializeAttachment();if(ws.readyState!==1)return;
  if(typeof data==='string'){if(data==='ping')ws.send('pong');else ws.close(1008,'Binary packets only');return;}
  if(state.role==='play'){
   if(!validPacket(data)){ws.close(1008,'Invalid MIDI packet');return;}
   const now=Date.now();if(now-(state.window||0)>1000){state.window=now;state.count=0;}if(++state.count>1000){ws.close(1008,'Rate limit');return;}ws.serializeAttachment(state);
  }else if(!(data instanceof ArrayBuffer)||data.byteLength!==24){ws.close(1008,'Invalid acknowledgement');return;}
  for(const peer of this.ctx.getWebSockets((state.role==='play'?'bridge:':'play:')+state.id))if(peer.readyState===1)peer.send(data);
 }
 webSocketClose(ws,code,reason){
  const state=ws.deserializeAttachment();
  // Replaced connections may finish closing after the new controller connected.
  const others=this.ctx.getWebSockets(state.role+':'+state.id).filter(p=>p!==ws&&p.readyState===1);
  if(!others.length){if(state.role==='play'){const panic=new Uint8Array(16);panic[0]=2;for(const bridge of this.ctx.getWebSockets('bridge:'+state.id))if(bridge.readyState===1)bridge.send(panic);}else for(const peer of this.ctx.getWebSockets('play:'+state.id))peer.close(1012,'Minecraft reconnecting');}
  ws.close([1005,1006,1015].includes(code)?1000:code,reason);
 }
 webSocketError(ws){ws.close(1011,'Connection error');}
}
