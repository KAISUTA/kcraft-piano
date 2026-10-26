// Little-endian: type,u8 note,u8 velocity,u8 channel,u32 sequence,f64 browser performance.now().
// 0=note, 1=sustain, 2=all-notes-off, 3=ping; 16 bytes, no JSON or polling in the note path.
export function validPacket(data) {
  if (!(data instanceof ArrayBuffer) || data.byteLength !== 16) return false;
  const d = new DataView(data), type = d.getUint8(0);
  return type <= 3 && d.getUint8(2) <= 127 && d.getUint8(3) <= 15 &&
    (type !== 0 || (d.getUint8(1) >= 21 && d.getUint8(1) <= 108)) && Number.isFinite(d.getFloat64(8, true));
}
