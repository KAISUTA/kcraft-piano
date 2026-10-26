// The audio render clock continues while Minecraft is foreground. No hidden-page timers.
class PianoMidiClock extends AudioWorkletProcessor {
 process(){this.port.postMessage(currentTime);return true;}
}
registerProcessor('piano-midi-clock',PianoMidiClock);
