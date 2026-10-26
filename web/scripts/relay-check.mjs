import WebSocket from 'ws';
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {randomBytes} from 'node:crypto';
import assert from 'node:assert/strict';
const base='https://kcraft-piano.kaisuta-works.workers.dev',wss=base.replace('https','wss'),room=randomBytes(32).toString('base64url');
async function rejected(path,options,expected){await new Promise((resolve,reject)=>{const ws=new WebSocket(wss+path,options);ws.on('unexpected-response',(req,res)=>{try{assert.equal(res.statusCode,expected);res.resume();req.destroy();resolve();}catch(e){req.destroy();reject(e);}});ws.on('open',()=>{ws.close();reject(Error('Connection should have been rejected'));});ws.on('error',()=>{});});}
await rejected('/bridge/'+room,{},401);
await rejected('/play/'+room,{origin:'https://example.invalid'},403);
await rejected('/play/'+room,{origin:base},409);
const secret=(await readFile('../.local/bridge-secret.txt','utf8')).trim();
const bridge=new WebSocket(`${wss}/bridge/${room}`,{headers:{Authorization:`Bearer ${secret}`}});
await new Promise((r,j)=>{bridge.once('open',r);bridge.once('error',j);});
const play=new WebSocket(`${wss}/play/${room}`,{origin:base});await new Promise((r,j)=>{play.once('open',r);play.once('error',j);});
await rejected('/play/'+room,{origin:base},409);
const panic=new Promise(resolve=>bridge.on('message',(data,binary)=>{if(binary&&data.length===16&&data[0]===2)resolve(true);}));
const badClose=new Promise(resolve=>play.on('close',code=>resolve(code)));play.send(Buffer.alloc(15));
assert.equal(await badClose,1008);await Promise.race([panic,new Promise((_,j)=>setTimeout(()=>j(Error('Missing all-notes-off after invalid input')),5000))]);bridge.close();
const result={unauthorizedBridge:401,crossOrigin:403,inactiveRoom:409,secondPerformer:409,invalidPacketClose:1008,disconnectPanic:true};
await mkdir('test-output',{recursive:true});await writeFile('test-output/relay.json',JSON.stringify(result,null,2));console.log(JSON.stringify(result));
