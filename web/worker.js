import { DurableObject } from 'cloudflare:workers';
import { validPacket } from './protocol.js';

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === '/health') return Response.json({ service: 'KCraftPiano', protocol: 1 });
    const match = /^\/(bridge|play)\/([A-Za-z0-9_-]{43})$/.exec(url.pathname);
    if (!match) return env.ASSETS.fetch(request);
    if (request.headers.get('Upgrade')?.toLowerCase() !== 'websocket') return new Response('WebSocket required', { status: 426 });
    if (match[1] === 'bridge') {
      if (!env.BRIDGE_SECRET || request.headers.get('Authorization') !== `Bearer ${env.BRIDGE_SECRET}`) return new Response('Unauthorized', { status: 401 });
    } else if (request.headers.get('Origin') !== url.origin) return new Response('Origin rejected', { status: 403 });
    const headers = new Headers(request.headers);
    headers.delete('Authorization');
    headers.set('X-Piano-Role', match[1]);
    const room = env.ROOMS.get(env.ROOMS.idFromName(match[2]), { locationHint: 'apac' });
    return room.fetch(new Request(request, { headers }));
  }
};

export class PianoRoom extends DurableObject {
  async fetch(request) {
    const role = request.headers.get('X-Piano-Role');
    const bridges = this.ctx.getWebSockets('bridge');
    if (role === 'play' && !bridges.length) return new Response('Minecraft側で /piano connect を実行してください', { status: 409 });
    if (role === 'play' && this.ctx.getWebSockets('play').length) return new Response('この接続は使用中です', { status: 409 });
    if (role === 'bridge') for (const ws of bridges) ws.close(1000, 'Reconnected');
    const pair = new WebSocketPair(), [client, server] = Object.values(pair);
    this.ctx.acceptWebSocket(server, [role]);
    server.serializeAttachment({ role, start: Date.now(), count: 0 });
    server.send(JSON.stringify({ type: 'ready', role }));
    return new Response(null, { status: 101, webSocket: client });
  }
  webSocketMessage(ws, data) {
    const state = ws.deserializeAttachment();
    if (Date.now() - state.start > 60 * 60 * 1000) { ws.close(1000, 'Session expired'); return; }
    if (typeof data === 'string') {
      if (data === 'ping') ws.send('pong');
      else ws.close(1008, 'Binary packets only');
      return;
    }
    if (state.role === 'play') {
      if (!validPacket(data)) { ws.close(1008, 'Invalid MIDI packet'); return; }
      // Bound messages per socket, while leaving enough headroom for dense chords and MIDI.
      const now = Date.now();
      if (now - (state.window ?? 0) > 1000) { state.window = now; state.count = 0; }
      if (++state.count > 1000) { ws.close(1008, 'Rate limit'); return; }
      ws.serializeAttachment(state);
    } else if (!(data instanceof ArrayBuffer) || data.byteLength !== 24) { ws.close(1008, 'Invalid acknowledgement'); return; }
    for (const peer of this.ctx.getWebSockets(state.role === 'play' ? 'bridge' : 'play')) peer.send(data);
  }
  webSocketClose(ws, code, reason, wasClean) {
    const state = ws.deserializeAttachment();
    // Release held notes when the browser disappears. Never leave a sustain pedal stuck.
    if (state.role === 'play') {
      const panic = new Uint8Array(16); panic[0] = 2;
      for (const bridge of this.ctx.getWebSockets('bridge')) bridge.send(panic);
    } else for (const peer of this.ctx.getWebSockets('play')) peer.close(1012, 'Minecraft disconnected');
    ws.close([1005,1006,1015].includes(code) ? 1000 : code, reason);
  }
  webSocketError(ws) { ws.close(1011, 'Connection error'); }
}
