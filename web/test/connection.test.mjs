import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';

// Exercise the real connection handler with a clock and a silent/open relay.
const source=readFileSync(new URL('../src/app.js',import.meta.url),'utf8');
function setup(){
 const nodes=new Map(),sockets=[];let now=0,tick;
 class Socket{constructor(){this.readyState=1;sockets.push(this);}close(code,reason){this.readyState=3;this.closed={code,reason};}}
 const context=vm.createContext({WebSocket:Socket,performance:{now:()=>now},location:{protocol:'https:',host:'piano.test'},selectedPiano:{id:'test'},shuttingDown:false,socket:null,heartbeat:null,retryTimer:null,connected:false,reconnecting:false,rtts:[],serverTimes:[],DataView,
 $:id=>{if(!nodes.has(id))nodes.set(id,{textContent:'',className:''});return nodes.get(id);},setInterval:fn=>{tick=fn;return 1;},clearInterval(){},clearTimeout(){},panic(){},transmit(){},percentile:a=>a[0]});
 vm.runInContext(source.slice(source.indexOf('function connect(){'),source.indexOf('\nvoid loadPianos().catch')),context);
 context.connect();sockets[0].onopen();
 return{context,nodes,sockets,advance:ms=>{now+=ms;tick();},ack:ws=>{const b=new ArrayBuffer(24);new DataView(b).setFloat64(8,now,true);ws.onmessage({data:b});}};
}
test('an open relay without a Minecraft reply is not connected and times out',()=>{
 const s=setup();assert.equal(s.context.connected,false);assert.equal(s.nodes.get('connection').textContent,'Minecraft 応答確認中');s.advance(8001);assert.equal(s.sockets[0].closed.reason,'Minecraft response timeout');
});
test('only a Minecraft acknowledgement confirms connection; missing later replies reconnect',()=>{
 const s=setup();s.ack(s.sockets[0]);assert.equal(s.context.connected,true);assert.equal(s.nodes.get('connection').textContent,'Minecraft 接続済み');s.advance(6000);assert.equal(s.sockets[0].closed,undefined);s.advance(2001);assert.equal(s.context.connected,false);assert.ok(s.sockets[0].closed);
});
test('late replies from a replaced connection cannot mark the new connection healthy',()=>{
 const s=setup();s.context.connect();s.sockets[1].onopen();s.ack(s.sockets[0]);assert.equal(s.context.connected,false);s.ack(s.sockets[1]);assert.equal(s.context.connected,true);
});
