import { test } from 'node:test';
import assert from 'node:assert/strict';
import { validPacket } from '../protocol.js';
function packet(type=0,note=60,velocity=100){const b=new ArrayBuffer(16),v=new DataView(b);v.setUint8(0,type);v.setUint8(1,note);v.setUint8(2,velocity);v.setFloat64(8,123.5,true);return b;}
test('88 key boundaries and note-off are accepted',()=>{for(let n=21;n<=108;n++){assert.ok(validPacket(packet(0,n,0)));assert.ok(validPacket(packet(0,n,127)));}});
test('reject malformed packets without forwarding',()=>{for(const b of [new ArrayBuffer(15),new ArrayBuffer(17),'note',packet(4),packet(0,20),packet(0,109),packet(0,60,128)])assert.equal(validPacket(b),false);const b=packet();new DataView(b).setFloat64(8,NaN,true);assert.equal(validPacket(b),false);});
test('pedal, panic and timing probes are supported',()=>{for(const type of [1,2,3])assert.ok(validPacket(packet(type,0,0)));});
