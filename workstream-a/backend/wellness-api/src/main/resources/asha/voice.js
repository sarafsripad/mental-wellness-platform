// ASHA Care voice capture (ADR-017). Produces 16 kHz mono 16-bit PCM WAV, the contract's
// WAV_PCM_S16LE format. Voice features are biomarker candidates, not diagnoses: nothing here, and
// nothing in the app, ever shows the worker a result.
//
// The microphone is used only when the server's settings allow it (privacy-lead approval). Otherwise
// the app records a generated synthetic voice, so no real voice is ever labelled SYNTHETIC.
"use strict";

const Voice = (() => {
  const RATE = 16000;
  const MIN_SECONDS = 3;
  const MAX_SECONDS = 180;

  function wav(pcm) {
    const buf = new ArrayBuffer(44 + pcm.byteLength);
    const v = new DataView(buf);
    const ascii = (o, s) => [...s].forEach((c, i) => v.setUint8(o + i, c.charCodeAt(0)));
    ascii(0, "RIFF"); v.setUint32(4, 36 + pcm.byteLength, true); ascii(8, "WAVE");
    ascii(12, "fmt "); v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true);
    v.setUint32(24, RATE, true); v.setUint32(28, RATE * 2, true); v.setUint16(32, 2, true); v.setUint16(34, 16, true);
    ascii(36, "data"); v.setUint32(40, pcm.byteLength, true);
    new Int16Array(buf, 44).set(pcm);
    return new Uint8Array(buf);
  }

  async function sha256(bytes) {
    const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
    return Array.from(digest, (b) => b.toString(16).padStart(2, "0")).join("");
  }

  /** A synthetic voiced signal (same generator as the dev console), different for every recording. */
  function synthetic(seconds) {
    const n = Math.floor(RATE * seconds);
    let seed = crypto.getRandomValues(new Uint32Array(1))[0];
    const rand = () => {
      seed = (seed + 0x6d2b79f5) | 0;
      let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
      t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
    const gauss = () => Math.sqrt(-2 * Math.log(rand() || 1e-12)) * Math.cos(2 * Math.PI * rand());
    const pcm = new Int16Array(n);
    const base = 120 + rand() * 60;
    let phase = 0;
    for (let i = 0; i < n; i++) {
      const t = i / RATE;
      const within = t % 0.34;
      const envelope = within < 0.22 ? Math.sin(Math.PI * within / 0.22) ** 2 : 0;
      phase += 2 * Math.PI * (base + 25 * Math.sin(2 * Math.PI * 0.35 * t) + (rand() * 3 - 1.5)) / RATE;
      let voiced = 0;
      for (let k = 1; k <= 5; k++) voiced += Math.sin(k * phase) / k;
      const x = 0.5 * envelope * voiced / 1.6 + gauss() * 0.004;
      pcm[i] = Math.round(Math.max(-1, Math.min(1, x)) * 32767);
    }
    return pcm;
  }

  /** Starts the microphone. Returns stop(), which resolves to the PCM samples and releases the device. */
  async function microphone() {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: false,
      noiseSuppression: false, autoGainControl: false } });
    const ctx = new AudioContext({ sampleRate: RATE });
    await ctx.audioWorklet.addModule("pcm-worklet.js");
    const source = ctx.createMediaStreamSource(stream);
    const node = new AudioWorkletNode(ctx, "pcm-capture");
    const chunks = [];
    let total = 0;
    node.port.onmessage = (e) => {
      if (total < RATE * MAX_SECONDS) { chunks.push(e.data); total += e.data.length; }
    };
    source.connect(node);
    return async function stop() {
      source.disconnect();
      node.disconnect();
      for (const track of stream.getTracks()) track.stop();
      await ctx.close();
      const pcm = new Int16Array(Math.min(total, RATE * MAX_SECONDS));
      let at = 0;
      for (const c of chunks) {
        const part = c.subarray(0, Math.max(0, pcm.length - at));
        pcm.set(part, at);
        at += part.length;
      }
      return pcm;
    };
  }

  /** Wraps samples as a recording ready for the outbox, or throws if it is too short or too long. */
  async function recording(pcm) {
    const seconds = pcm.length / RATE;
    if (seconds < MIN_SECONDS) throw new Error(`The recording must be at least ${MIN_SECONDS} seconds long.`);
    if (seconds > MAX_SECONDS) throw new Error(`The recording must be at most ${MAX_SECONDS} seconds long.`);
    const bytes = wav(pcm);
    return { bytes, seconds: Math.round(seconds * 100) / 100, sha256: await sha256(bytes) };
  }

  return { synthetic, microphone, recording, RATE, MIN_SECONDS, MAX_SECONDS };
})();
