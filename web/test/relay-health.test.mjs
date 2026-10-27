import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
import {validPacket} from '../protocol.js';
const source=readFileSync(new URL('../worker.js',import.meta.url),'utf8');
function setup(age){
 let now=100000;
 const bridge={readyState:1,state:{role:'bridge',id:'piano',name:'test',lastSeen:now-age},deserializeAttachment(){return this.state;},serializeAttachment(s){this.state=s;},close(code,reason){this.closed={code,reason};this.readyState=2;},send(data){this.sent=data;}};
 const context=vm.createContext({DurableObject:class{},Date:{now:()=>now},URL,Response,Request,TextEncoder,validPacket,ArrayBuffer});
 vm.runInContext(source.slice(source.indexOf('export class PianoRoom')).replace('export class','class')+'\nglobalThis.Room=PianoRoom;',context);
 const room=new context.Room();room.ctx={getWebSockets:()=>[bridge]};return{room,bridge};
}
test('dead Minecraft bridges disappear from the piano list and reject new controllers',async()=>{
 const {room,bridge}=setup(20001);const response=await room.fetch(new Request('https://piano.test/api/pianos'));assert.deepEqual(await response.json(),[]);assert.equal(bridge.closed.reason,'Minecraft heartbeat timeout');
 const request=new Request('https://piano.test/play/piano',{headers:{'X-Piano-Role':'play','X-Piano-Id':'piano'}});assert.equal((await room.fetch(request)).status,409);
});
test('a Java heartbeat keeps a quiet piano registered without any playing',async()=>{
 const {room,bridge}=setup(19000);room.webSocketMessage(bridge,'ping');assert.equal(bridge.sent,'pong');assert.equal(bridge.state.lastSeen,100000);assert.equal((await(await room.fetch(new Request('https://piano.test/api/pianos'))).json())[0].name,'test');
});
