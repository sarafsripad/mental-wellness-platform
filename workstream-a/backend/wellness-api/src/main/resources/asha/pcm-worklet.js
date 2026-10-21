// Audio worklet for ASHA Care voice capture (ADR-017): converts the microphone's float samples to
// 16-bit PCM and posts them to the page. The AudioContext runs at 16 kHz, so no resampling is done here.
"use strict";

class PcmCapture extends AudioWorkletProcessor {
  process(inputs) {
    const channel = inputs[0] && inputs[0][0];
    if (channel) {
      const out = new Int16Array(channel.length);
      for (let i = 0; i < channel.length; i++) out[i] = Math.round(Math.max(-1, Math.min(1, channel[i])) * 32767);
      this.port.postMessage(out, [out.buffer]);
    }
    return true;
  }
}

registerProcessor("pcm-capture", PcmCapture);
