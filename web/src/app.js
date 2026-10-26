import { Midi } from '@tonejs/midi';
const $ = id => document.getElementById(id);
const status = text => { $('status').textContent = text; };
let audio, master, ready=false, loading=false, midiAccess, midiInput;
let octave=0, pedal=false, socket, sequence=0, connected=false, heartbeat;
const buffers=new Map(), voices=new Map(), inputs=new Map(), activeNotes=new Set(), pointers=new Map();
const noteNames=['C','C♯','D','D♯','E','F','F♯','G','G♯','A','A♯','B'];
const label=n=>noteNames[n%12]+(Math.floor(n/12)-1);
const map={KeyA:60,KeyW:61,KeyS:62,KeyE:63,KeyD:64,KeyF:65,KeyT:66,KeyG:67,KeyY:68,KeyH:69,KeyU:70,KeyJ:71,KeyK:72,KeyO:73,KeyL:74,KeyP:75,Semicolon:76};
const physical=new Map(), elements=new Map();
let white=0;
for(let n=21;n<=108;n++) {
  const black=[1,3,6,8,10].includes(n%12), key=document.createElement('button');
  key.type='button';key.className=`key ${black?'black':'white'}`;key.dataset.note=n;key.setAttribute('aria-label',label(n));
  key.style.left=black?`calc((${white} - .305) * var(--key-width))`:`calc(${white} * var(--key-width))`;
  key.innerHTML=`<span class="shortcut"></span><span class="note">${n%12===0||n===21||n===108?label(n):''}</span>`;
  $('keyboard').append(key);elements.set(n,key);if(!black)white++;
}
function updateLabels(){for(const el of elements.values())el.querySelector('.shortcut').textContent='';for(const [code,n] of Object.entries(map)){elements.get(n+octave*12)?.querySelector('.shortcut')?.replaceChildren(code.replace('Key','').replace('Semicolon',';'));}$('octave-label').textContent=`中央 C${4+octave}`;}
updateLabels();
function centerKeyboard(){$('keyboard-scroll').scrollLeft=(23+octave*7)*parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--key-width'))-$('keyboard-scroll').clientWidth/2;}
requestAnimationFrame(centerKeyboard);window.addEventListener('resize',centerKeyboard);
// Rendering is outside the audio/MIDI hot path.
let drawPending=false;
function redraw(){if(drawPending)return;drawPending=true;requestAnimationFrame(()=>{drawPending=false;for(const [n,el]of elements)el.classList.toggle('active',activeNotes.has(n));$('note-display').textContent=[...activeNotes].sort((a,b)=>a-b).map(label).join(' · ')||'—';$('pedal-led').classList.toggle('on',pedal);});}
async function enableAudio(){
  if(ready){await audio.resume();return true;}if(loading)return false;loading=true;$('start').disabled=true;
  try{
    audio ||= new AudioContext({latencyHint:'interactive'});await audio.resume();
    master ||= audio.createGain();master.gain.value=Number($('volume').value)/100*.42;master.connect(audio.destination);
    let next=21,done=0;
    await Promise.all(Array.from({length:8},async()=>{while(next<=108){let n=next++;let response=await fetch(`/samples/${n}.ogg`);if(!response.ok)throw Error(`音源 ${n} の取得に失敗`);const b=await audio.decodeAudioData(await response.arrayBuffer());buffers.set(n,b);done++;if(done%8===0)status(`音源を準備中 ${done}/88`);}}));
    ready=true;$('start').textContent='音声 有効';$('start').disabled=false;status('演奏できます。PCキーボード・タッチ、またはMIDI入力を使ってください。');
    const estimate=(audio.baseLatency||0)+(audio.outputLatency||0);$('audio-latency').textContent=estimate?(estimate*1000).toFixed(1):'非対応';
    return true;
  }catch(e){status(`音源を準備できませんでした。再試行してください。${e.message}`);$('start').disabled=false;return false;}finally{loading=false;}
}
$('start').onclick=enableAudio;
function soundOn(n,velocity){
  const buffer=buffers.get(n);if(!ready||!buffer||audio.state!=='running')return;
  // Audio first: start() immediately, no network round trip, timer, promise or animation frame.
  const source=audio.createBufferSource(), gain=audio.createGain();source.buffer=buffer;gain.gain.value=(velocity/127)**1.3;
  source.connect(gain).connect(master);source.start();
  const old=voices.get(n);if(old)release(old,.012);
  const voice={source,gain,released:false};voices.set(n,voice);
  source.onended=()=>{source.disconnect();gain.disconnect();if(voices.get(n)===voice)voices.delete(n);};
}
function release(v,time=.065){if(v.released)return;v.released=true;v.gain.gain.cancelScheduledValues(audio.currentTime);v.gain.gain.setTargetAtTime(0,audio.currentTime,time/4);v.source.stop(audio.currentTime+time);}
function transmit(type,n=0,vel=0,ch=0){
  if(!connected||socket?.readyState!==WebSocket.OPEN)return;
  if(socket.bufferedAmount>16384){socket.close(1000,'通信が混雑しています');return;}
  const packet=new ArrayBuffer(16),d=new DataView(packet);d.setUint8(0,type);d.setUint8(1,n);d.setUint8(2,vel);d.setUint8(3,ch);d.setUint32(4,++sequence,true);d.setFloat64(8,performance.now(),true);socket.send(packet);
}
function noteOn(n,vel=100,source='keyboard',ch=0){
  if(!ready||n<21||n>108||vel<=0)return;
  let sources=inputs.get(n);if(!sources){sources=new Set();inputs.set(n,sources);}if(sources.has(source))return;
  sources.add(source);soundOn(n,vel);transmit(0,n,vel,ch);activeNotes.add(n);redraw();
}
function noteOff(n,source='keyboard',ch=0){const sources=inputs.get(n);if(!sources||!sources.delete(source))return;if(sources.size)return;inputs.delete(n);if(!pedal){const v=voices.get(n);if(v)release(v);}transmit(0,n,0,ch);activeNotes.delete(n);redraw();}
const pedalSources=new Set();
function sustain(down,source='keyboard'){if(down)pedalSources.add(source);else pedalSources.delete(source);const next=pedalSources.size>0;if(next===pedal)return;pedal=next;if(!pedal)for(const[n,v]of voices)if(!inputs.has(n))release(v);transmit(1,0,pedal?127:0);redraw();}
function panic(){for(const v of voices.values())release(v,.02);voices.clear();inputs.clear();activeNotes.clear();physical.clear();pointers.clear();pedalSources.clear();pedal=false;transmit(2);redraw();}
$('panic').onclick=()=>{stopFile();panic();};
$('volume').oninput=()=>{const v=Number($('volume').value);$('volume-value').textContent=`${v}%`;if(master)master.gain.setTargetAtTime(v/100*.42,audio.currentTime,.01);};
function typing(e){return /INPUT|SELECT|TEXTAREA/.test(e.target.tagName)||e.target.isContentEditable;}
window.addEventListener('keydown',e=>{if(typing(e)||e.metaKey||e.ctrlKey||e.altKey)return;if(e.code==='Space'){e.preventDefault();if(!e.repeat)sustain(true);return;}if(!(e.code in map))return;e.preventDefault();if(e.repeat||physical.has(e.code))return;if(!ready){status('先に「音を有効にする」を押してください。');return;}if(audio.state!=='running')void audio.resume();const n=map[e.code]+octave*12;physical.set(e.code,n);noteOn(n,100,e.code);});
window.addEventListener('keyup',e=>{if(e.code==='Space')sustain(false);const n=physical.get(e.code);if(n!==undefined){noteOff(n,e.code);physical.delete(e.code);}});
function pointerNote(e){const target=document.elementFromPoint(e.clientX,e.clientY)?.closest('[data-note]');return target?Number(target.dataset.note):null;}
$('keyboard').addEventListener('pointerdown',e=>{e.preventDefault();if(!ready){status('先に「音を有効にする」を押してください。');return;}const n=pointerNote(e);if(n===null)return;$('keyboard').setPointerCapture(e.pointerId);pointers.set(e.pointerId,n);noteOn(n,100,`pointer${e.pointerId}`);});
$('keyboard').addEventListener('pointermove',e=>{if(!pointers.has(e.pointerId))return;const old=pointers.get(e.pointerId),n=pointerNote(e);if(old===n)return;noteOff(old,`pointer${e.pointerId}`);pointers.set(e.pointerId,n);if(n!==null)noteOn(n,100,`pointer${e.pointerId}`);});
for(const event of ['pointerup','pointercancel','lostpointercapture'])$('keyboard').addEventListener(event,e=>{if(pointers.has(e.pointerId)){noteOff(pointers.get(e.pointerId),`pointer${e.pointerId}`);pointers.delete(e.pointerId);}});
function changeOctave(by){panic();octave=Math.max(-3,Math.min(2,octave+by));updateLabels();}
$('octave-down').onclick=()=>changeOctave(-1);$('octave-up').onclick=()=>changeOctave(1);
function releaseLocalControls(){for(const[code,n]of physical)noteOff(n,code);physical.clear();for(const[id,n]of pointers)noteOff(n,`pointer${id}`);pointers.clear();sustain(false,'keyboard');}
window.addEventListener('blur',releaseLocalControls);document.addEventListener('visibilitychange',()=>{if(document.hidden)releaseLocalControls();if(ready&&audio.state!=='running')void audio.resume().catch(()=>status('音声が休止中です。「音を有効にする」で再開してください。'));});
let midiCount=0;
function midiMessage(e){const[statusByte,n,v=0]=e.data,ch=statusByte&15,type=statusByte&240,source=`midi${ch}:${n}`;if(type===144&&v>0){if(inputs.get(n)?.has(source)){soundOn(n,v);transmit(0,n,v,ch);}else noteOn(n,v,source,ch);midiCount++;$('midi-note').textContent=label(n);$('midi-velocity').textContent=v;$('midi-count').textContent=midiCount;}else if(type===128||type===144)noteOff(n,source,ch);else if(type===176&&n===64)sustain(v>=64,`midi${ch}`);else if(type===176&&(n===120||n===123))panic();}
function bindMidi(){const next=midiAccess?.inputs.get($('midi').value);if(next===midiInput)return;if(midiInput)midiInput.onmidimessage=null;for(const[n,sources]of inputs)for(const source of[...sources])if(source.startsWith('midi'))noteOff(n,source);for(const source of[...pedalSources])if(source.startsWith('midi'))sustain(false,source);midiInput=next;if(midiInput){midiInput.onmidimessage=midiMessage;void midiInput.open?.();$('midi-state').textContent='接続済み';$('midi-device').textContent=midiInput.name;$('midi-enable').textContent='MIDI接続済み';}else{$('midi-state').textContent='未接続';$('midi-device').textContent='USB接続と機器の電源を確認してください。';$('midi-enable').textContent='MIDIキーボードを接続';}}
function refreshMidi(){const selected=$('midi').value;$('midi').replaceChildren(new Option('MIDI機器を選択',''));for(const input of midiAccess.inputs.values())if(input.state==='connected')$('midi').add(new Option(input.name,input.id));if(selected&&[...$('midi').options].some(o=>o.value===selected))$('midi').value=selected;else $('midi').selectedIndex=$('midi').options.length>1?1:0;bindMidi();}
$('midi').onchange=bindMidi;
$('midi-enable').onclick=async()=>{if(!navigator.requestMIDIAccess){status('このブラウザはWeb MIDIに対応していません。Chrome / Edgeを使ってください。');return;}try{if(!await enableAudio())return;midiAccess=await navigator.requestMIDIAccess({sysex:false});midiAccess.onstatechange=refreshMidi;refreshMidi();status(midiInput?'MIDI入力を接続しました。':'MIDI機器が見つかりません。USB接続を確認してください。');}catch(e){status('MIDIへのアクセスが許可されませんでした。ブラウザの権限設定を確認してください。');}};
let fileClockModule;
let fileEvents=[],duration=0,fileStart=0,filePosition=0,fileTimer=null,eventIndex=0;
const time=s=>`${Math.floor(s/60)}:${String(Math.floor(s%60)).padStart(2,'0')}`;
$('file').onchange=async()=>{const file=$('file').files[0];if(!file)return;stopFile();if(file.size>10*1024*1024){status('MIDIファイルは10MBまでです。');return;}try{const midi=new Midi(await file.arrayBuffer());fileEvents=[];midi.tracks.forEach((track,i)=>{for(const note of track.notes){if(note.midi<21||note.midi>108)continue;fileEvents.push({t:note.time,on:true,n:note.midi,v:Math.max(1,Math.round(note.velocity*127)),source:`file${i}:${note.midi}`,ch:track.channel});fileEvents.push({t:note.time+note.duration,on:false,n:note.midi,source:`file${i}:${note.midi}`,ch:track.channel});}for(const cc of track.controlChanges[64]||[])fileEvents.push({t:cc.time,pedal:cc.value>=.5,source:`filepedal${i}`});});if(fileEvents.length>200000)throw Error('ノート数が多すぎます');fileEvents.sort((a,b)=>a.t-b.t||Number(a.on)-Number(b.on));duration=midi.duration;filePosition=0;$('file-name').textContent=file.name;$('play').disabled=!fileEvents.length;$('stop').disabled=false;$('progress').disabled=false;updateTime();status('MIDIファイルを読み込みました。');}catch(e){fileEvents=[];$('play').disabled=true;$('stop').disabled=true;$('progress').disabled=true;status('MIDIファイルを読み込めませんでした。'+e.message);}};
function updateTime(){$('time').textContent=`${time(filePosition)} / ${time(duration)}`;$('progress').value=duration?Math.round(filePosition/duration*1000):0;}
function stopFile(){if(fileTimer){fileTimer.port.onmessage=null;fileTimer.disconnect();fileTimer=null;}$('play').textContent='再生';for(const[n,sources]of inputs)for(const source of[...sources])if(source.startsWith('file'))noteOff(n,source);for(const source of[...pedalSources])if(source.startsWith('file'))sustain(false,source);}
$('play').onclick=async()=>{if(fileTimer){stopFile();return;}if(!await enableAudio())return;if(filePosition>=duration)filePosition=0;eventIndex=fileEvents.findIndex(e=>e.t>=filePosition);if(eventIndex<0)eventIndex=fileEvents.length;await (fileClockModule ||= audio.audioWorklet.addModule('/midi-clock.js'));fileStart=audio.currentTime-filePosition;$('play').textContent='一時停止';fileTimer=new AudioWorkletNode(audio,'piano-midi-clock');fileTimer.connect(audio.destination);fileTimer.port.onmessage=()=>{filePosition=audio.currentTime-fileStart;while(eventIndex<fileEvents.length&&fileEvents[eventIndex].t<=filePosition){const e=fileEvents[eventIndex++];if('pedal'in e)sustain(e.pedal,e.source);else if(e.on)noteOn(e.n,e.v,e.source,e.ch);else noteOff(e.n,e.source,e.ch);}updateTime();if(filePosition>=duration){stopFile();filePosition=duration;updateTime();}};};
$('stop').onclick=()=>{stopFile();filePosition=0;updateTime();};$('progress').oninput=()=>{stopFile();filePosition=duration*Number($('progress').value)/1000;updateTime();};
const rtts=[],serverTimes=[];
function percentile(arr,p){const sorted=[...arr].sort((a,b)=>a-b);return sorted[Math.floor((sorted.length-1)*p)];}
const requestedPiano=new URLSearchParams(location.hash.slice(1)).get('piano');
let pianoList=[], selectedPiano=null, retryTimer, reconnecting=false, shuttingDown=false;
$('login-form').onsubmit=async e=>{e.preventDefault();const button=e.submitter;button.disabled=true;try{const response=await fetch('/api/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({password:$('password').value})});if(!response.ok)throw Error(await response.text());$('password').value='';await loadPianos();}catch(error){$('login-status').textContent=error.message;}finally{button.disabled=false;}};
async function loadPianos(){
 const response=await fetch('/api/pianos',{cache:'no-store'});
 if(response.status===401){$('login-form').hidden=false;$('piano-controls').hidden=true;$('login-status').textContent='固定パスワードを入力してください。';return false;}
 if(!response.ok)throw Error('ピアノ一覧を取得できませんでした');
 pianoList=await response.json();$('login-form').hidden=true;$('piano-controls').hidden=false;
 const previous=selectedPiano?.id||localStorage.getItem('piano-id');$('piano-select').replaceChildren(...pianoList.map(p=>new Option(p.name,p.id)));
 selectedPiano=pianoList.find(p=>p.id===previous)||pianoList.find(p=>p.name===requestedPiano)||pianoList[0];
 if(!selectedPiano){$('login-status').textContent='Minecraft側のピアノ接続を待っています。';if(!shuttingDown)retryTimer=setTimeout(()=>void loadPianos().catch(()=>{retryTimer=setTimeout(connect,3000);}),3000);return false;}
 $('piano-select').value=selectedPiano.id;localStorage.setItem('piano-id',selectedPiano.id);$('login-status').textContent='認証済み。ページを開き直しても同じピアノへ接続できます。';connect();return true;
}
$('piano-select').onchange=()=>{selectedPiano=pianoList.find(p=>p.id===$('piano-select').value);if(selectedPiano){localStorage.setItem('piano-id',selectedPiano.id);connect();}};
$('reconnect').onclick=()=>{clearTimeout(retryTimer);void loadPianos().catch(e=>$('login-status').textContent=e.message);};
function connect(){
 if(!selectedPiano||shuttingDown)return;
 clearTimeout(retryTimer);const old=socket;if(old){old.onclose=null;old.onerror=null;panic();old.close(1000,'Switching piano');}clearInterval(heartbeat);connected=false;
 const current=new WebSocket(`${location.protocol==='https:'?'wss:':'ws:'}//${location.host}/play/${selectedPiano.id}`);socket=current;current.binaryType='arraybuffer';$('connection').textContent='Minecraft 接続中';
 current.onopen=()=>{if(socket!==current)return;connected=true;reconnecting=false;$('connection').textContent='Minecraft 接続済み';$('connection-dot').className='on';heartbeat=setInterval(()=>transmit(3),2000);transmit(3);};
 current.onmessage=e=>{if(typeof e.data==='string')return;if(e.data.byteLength!==24)return;const d=new DataView(e.data);rtts.push(performance.now()-d.getFloat64(8,true));serverTimes.push(d.getFloat64(16,true));if(rtts.length>100){rtts.shift();serverTimes.shift();}$('rtt').textContent=percentile(rtts,.5).toFixed(1);$('server-latency').textContent=percentile(serverTimes,.5).toFixed(1);$('measurement-state').textContent=`直近${rtts.length}件 · 中央値`;};
 current.onclose=e=>{if(socket!==current)return;connected=false;clearInterval(heartbeat);panic();$('connection-dot').className='';$('connection').textContent='Minecraft 再接続中';if(e.reason==='Reconnected'){status('別のページから同じピアノへ接続されました。「再接続」で操作を戻せます。');return;}if(!shuttingDown)retryTimer=setTimeout(()=>void loadPianos().catch(()=>{retryTimer=setTimeout(connect,3000);}),2000);};
 current.onerror=()=>{$('login-status').textContent='Minecraftへ再接続しています。固定URLを開いたままで待てます。';};
}
void loadPianos().catch(()=>{$('login-status').textContent='接続できません。「再接続」で再試行できます。';});
window.addEventListener('pagehide',()=>{shuttingDown=true;clearTimeout(retryTimer);panic();socket?.close(1000,'Page closed');});
window.addEventListener('pageshow',e=>{if(e.persisted){shuttingDown=false;void loadPianos();}});
