import mc from 'minecraft-protocol';
import WebSocket from 'ws';
import {writeFile,mkdir,readFile} from 'node:fs/promises';
import assert from 'node:assert/strict';
import net from 'node:net';
const base='https://kcraft-piano.kaisuta-works.workers.dev';
const wait=ms=>new Promise(r=>setTimeout(r,ms));
async function rcon(command){
  const password=(await readFile('../.local/test-rcon.txt','utf8')).trim();
  return new Promise((resolve,reject)=>{const s=net.connect(35690,'127.0.0.1');let buffer=Buffer.alloc(0),authed=false;
    function send(id,type,text){const t=Buffer.from(text),b=Buffer.alloc(t.length+14);b.writeInt32LE(t.length+10,0);b.writeInt32LE(id,4);b.writeInt32LE(type,8);t.copy(b,12);s.write(b);}
    s.on('connect',()=>send(1,3,password));s.on('error',reject);s.on('data',data=>{buffer=Buffer.concat([buffer,data]);while(buffer.length>=4&&buffer.length>=buffer.readInt32LE(0)+4){const size=buffer.readInt32LE(0),p=buffer.subarray(4,size+4);buffer=buffer.subarray(size+4);if(!authed&&p.readInt32LE(0)===1){authed=true;send(2,2,command);}else if(p.readInt32LE(0)===2){s.end();resolve(p.subarray(8,-2).toString());}}});s.setTimeout(5000,()=>{s.destroy();reject(Error('RCON timeout'));});});
}
// minecraft-data lists 26.2 but has no 26.2 schema yet. 26.1's packet schema
// can read the tested chat/model/sound packets; handshake uses the actual 776 protocol.
const options={host:'127.0.0.1',port:35689,username:'PianoTest',auth:'offline',version:'26.1',connect(c){options.protocolVersion=776;c.setSocket(net.connect(35689,'127.0.0.1'));}};
const client=mc.createClient(options);
const packets=new Map();let token,position;let performerNotes=0,performerStops=0;
client.on('packet',(data,meta)=>{
  packets.set(meta.name,(packets.get(meta.name)||0)+1);
  if(JSON.stringify(data)?.includes('kcraft:p')){if(meta.name==='sound_effect')performerNotes++;if(meta.name==='stop_sound')performerStops++;}
  if(meta.name==='position'){position=data;if(data.teleportId!==undefined)client.write('teleport_confirm',{teleportId:data.teleportId});}
  if(meta.name==='update_health'&&data.health<=0)client.write('client_command',{actionId:0});
  if(meta.name==='system_chat'){
    const text=JSON.stringify(data);const match=text.match(/session=([A-Za-z0-9_-]{43})/);if(match)token=match[1];
    console.log('Game message:',text.replace(/session=[A-Za-z0-9_-]+/g,'session=[redacted]').slice(0,450));
  }
});
client.on('error',e=>console.error('MC error',e.message));
client.on('kick_disconnect',data=>console.log('Disconnected',JSON.stringify(data)));
await new Promise((resolve,reject)=>{client.once('login',resolve);setTimeout(()=>reject(Error('Minecraft login timeout')),20000).unref();});
await rcon('gamemode creative PianoTest');client.write('client_command',{actionId:0});
await wait(2500);
const observerOptions={host:'127.0.0.1',port:35689,username:'PianoListen',auth:'offline',version:'26.1',connect(c){observerOptions.protocolVersion=776;c.setSocket(net.connect(35689,'127.0.0.1'));}};
const observer=mc.createClient(observerOptions);let sounds=0,customNotes=0,customStops=0;const soundNames=new Set();
observer.on('packet',(p,m)=>{if(m.name==='position')observer.write('teleport_confirm',{teleportId:p.teleportId});if(m.name==='update_health'&&p.health<=0)observer.write('client_command',{actionId:0});if(/sound/.test(m.name)){sounds++;soundNames.add(m.name);if(JSON.stringify(p).includes('kcraft:p')){if(m.name==='stop_sound')customStops++;else customNotes++;}}});
observer.on('error',e=>console.log('Observer error',e.message));
await new Promise((r,j)=>{observer.once('login',r);setTimeout(()=>j(Error('Observer timeout')),20000).unref();});
await rcon('gamemode creative PianoListen');observer.write('client_command',{actionId:0});await wait(1000);
if(process.env.PIANO_LATE_LOAD==='1'){console.log('Testing scoped PlugMan reload with both clients online:',await rcon('plugman reload KCraftPiano'));await wait(2000);console.log('Runtime status:',await rcon('pianoadmin status'));}
console.log('Minecraft joined. chat=',typeof client.chat,'position=',position&&{x:position.x,y:position.y,z:position.z});
const pianoName=process.env.PIANO_TEST_CORNER==='1'?'corner':'verify';
if(process.env.PIANO_TEST_CORNER==='1'){await rcon('tp PianoTest 15.9 -60 12 0 0');await wait(500);}
client.chat('/piano create '+pianoName);await wait(2500);
const password=(await readFile('../.local/fixed-password.txt','utf8')).trim();
const login=await fetch(base+'/api/login',{method:'POST',headers:{Origin:base,'Content-Type':'application/json'},body:JSON.stringify({password})});assert.equal(login.status,200);
const cookie=login.headers.get('set-cookie').split(';')[0];
let selected;for(let i=0;i<20&&!selected;i++){const list=await(await fetch(base+'/api/pianos',{headers:{Cookie:cookie}})).json();selected=list.find(p=>p.name===pianoName);if(!selected)await wait(500);}assert.ok(selected,'Piano must register its permanent bridge automatically');
await mkdir('../.local',{recursive:true});await writeFile('../.local/test-session.json',JSON.stringify({id:selected.id,name:pianoName,base}));
const socket=new WebSocket(`${base.replace('https','wss')}/play/${selected.id}`,{origin:base,headers:{Cookie:cookie}});
await new Promise((resolve,reject)=>{socket.once('open',resolve);socket.once('error',reject);});
const times=[],delays=[],receivedSequences=new Set();let acks=0,sent=0;socket.on('message',(data,binary)=>{if(!binary)return;if(data.length!==24)return;times.push(performance.now()-data.readDoubleLE(8));delays.push(data.readDoubleLE(16));receivedSequences.add(data.readUInt32LE(4));acks++;});
socket.on('close',(code,reason)=>console.log('Relay closed',code,reason.toString()));
function send(type,n=0,v=0){const b=Buffer.alloc(16);b[0]=type;b[1]=n;b[2]=v;b.writeUInt32LE(++sent,4);b.writeDoubleLE(performance.now(),8);socket.send(b);}
// Deterministic 100-packet test: dense chords + note offs + pedal and ping.
for(let i=0;i<10;i++){for(const n of[60,64,67,72])send(0,n,100);await wait(40);for(const n of[60,64,67,72])send(0,n,0);send(1,0,i%2?0:127);send(3);await wait(70);}
await wait(1000);send(2);for(let i=0;i<100&&acks<101;i++)await wait(50);
if(acks!==101)console.log('Missing sequences',Array.from({length:sent},(_,i)=>i+1).filter(x=>!receivedSequences.has(x)));
assert.equal(acks,101,'Every valid message must be acknowledged without queue loss');
const stats=a=>{const s=[...a].sort((a,b)=>a-b);return{count:s.length,min:s[0],p50:s[Math.floor(s.length*.5)],p95:s[Math.floor(s.length*.95)],max:s.at(-1)};};
const result={timestamp:new Date().toISOString(),minecraft:'Folia 26.2',acks,roundTripMs:stats(times),serverDispatchMs:stats(delays),packets:Object.fromEntries([...packets].filter(([k])=>/sound|entity|metadata/.test(k)))};
result.observerSoundPackets=sounds;result.observerSoundPacketNames=[...soundNames];result.customPianoNoteOns=customNotes;result.customPianoStops=customStops;assert.ok(sounds>=80,'Nearby listener must receive both sound and stop packets');
assert.equal(customNotes,40,'Listener must receive all 40 custom piano note-on packets');
if(process.env.PIANO_EXPECT_SELF_AUDIO==='1'){assert.equal(performerNotes,40,'Performer must hear all 40 notes in Minecraft');assert.equal(performerStops,40,'Performer must receive all 40 note stops');result.performerNoteOns=performerNotes;result.performerStops=performerStops;}
await wait(1500);const before=acks;
for(let i=0;i<250;i++){send(0,21+i%88,100);send(0,21+i%88,0);}
for(let i=0;i<100&&acks<before+500;i++)await wait(50);
assert.equal(acks-before,500,'500-message burst must not lose note on/off or acknowledgement packets');result.burstAcknowledged=500;
console.log(JSON.stringify(result,null,2));await mkdir('test-output',{recursive:true});await writeFile('test-output/integration.json',JSON.stringify(result,null,2));
// Keep the Minecraft owner online while a browser verifies the same session.
socket.close();await wait(700);
console.log('Browser session ready; holding test connections for the configured interval.');await wait(Number(process.env.PIANO_KEEPALIVE_MS||120000));client.end();observer.end();
