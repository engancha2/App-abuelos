/**
 * Timbres de llamada para personas mayores, fabricados aquí mismo (sin archivos de terceros ni derechos).
 * Con la edad se pierden antes los agudos: frecuencias medias y graves, mucha energía y patrones reconocibles.
 * Cada timbre es un WAV de un ciclo (sonido + silencio) que el móvil repite mientras suena la llamada.
 */

const RATE = 22050;

/** "system" = el tono que ya tenga el móvil (Cerca no lo cambia). */
export const RINGTONES = [
  { id: 'system', name: 'El del móvil', description: 'El tono que ya tenía su teléfono.' },
  { id: 'campana', name: 'Teléfono antiguo', description: 'La campana de los teléfonos de toda la vida.' },
  { id: 'grave', name: 'Campana grave', description: 'Como el antiguo, pero más grave: se oye mejor si se oye poco.' },
  { id: 'doble', name: 'Doble timbre', description: 'Riiing-riiing, riiing-riiing. Muy reconocible.' },
  { id: 'trino', name: 'Teléfono de oficina', description: 'El trino de los teléfonos de los años 80.' },
  { id: 'estridente', name: 'Muy fuerte', description: 'Como una alarma. Para quien casi no oye o tiene el móvil lejos.' },
];
export const RINGTONE_IDS = RINGTONES.map((r) => r.id);

// ---------------------------------------------------------------- Síntesis

/** Golpes de martillo sobre una o dos campanas que se alternan, durante [seconds]. */
function bell(out, start, seconds, { freqs, strikesPerSecond, decay }) {
  const partials = [[1, 1], [2, 0.55], [2.76, 0.4], [3.9, 0.25], [5.4, 0.15]]; // parciales de campana (no armónicos)
  const strikes = Math.floor(seconds * strikesPerSecond);
  const tail = Math.floor(RATE * decay * 5);
  for (let s = 0; s < strikes; s++) {
    const f = freqs[s % freqs.length];
    const t0 = start + Math.floor((s / strikesPerSecond) * RATE);
    for (let i = 0; i < tail && t0 + i < out.length; i++) {
      const t = i / RATE;
      const env = Math.exp(-t / decay);
      let v = 0;
      for (const [k, a] of partials) v += a * Math.sin(2 * Math.PI * f * k * t);
      out[t0 + i] += env * v;
    }
  }
}

/** Tono electrónico (suma de frecuencias), un poco saturado para que suene más fuerte. */
function tone(out, start, seconds, freqs, { trill = 0 } = {}) {
  const n = Math.floor(seconds * RATE);
  const fade = Math.floor(0.01 * RATE); // sin chasquidos al empezar y al acabar
  for (let i = 0; i < n && start + i < out.length; i++) {
    const t = i / RATE;
    // Con trino, alterna entre las frecuencias muy deprisa en vez de sumarlas
    const active = trill ? [freqs[Math.floor(t * trill) % freqs.length]] : freqs;
    let v = 0;
    for (const f of active) v += Math.sin(2 * Math.PI * f * t);
    v /= active.length;
    const env = Math.min(1, i / fade, (n - i) / fade);
    out[start + i] += Math.tanh(2.2 * v) * env;
  }
}

/** Sirena que sube y baja (como una alarma), áspera a propósito. */
function siren(out, start, seconds, { low, high, sweepsPerSecond }) {
  const n = Math.floor(seconds * RATE);
  const fade = Math.floor(0.01 * RATE);
  let phase = 0;
  for (let i = 0; i < n && start + i < out.length; i++) {
    const t = i / RATE;
    const x = (t * sweepsPerSecond) % 1;
    const f = low + (high - low) * (x < 0.5 ? x * 2 : (1 - x) * 2);
    phase += (2 * Math.PI * f) / RATE;
    const env = Math.min(1, i / fade, (n - i) / fade);
    out[start + i] += Math.tanh(3 * Math.sin(phase)) * env;
  }
}

const sec = (s) => Math.floor(s * RATE);

const RECIPES = {
  // Campana clásica: dos campanas, unos 20 golpes por segundo. 2 s sonando, 2 s de silencio
  campana: () => {
    const out = new Float32Array(sec(4));
    bell(out, 0, 2, { freqs: [880, 1040], strikesPerSecond: 20, decay: 0.06 });
    return out;
  },
  // Más grave y más lenta: más fácil de oír con pérdida de audición
  grave: () => {
    const out = new Float32Array(sec(4));
    bell(out, 0, 2, { freqs: [520, 620], strikesPerSecond: 16, decay: 0.08 });
    return out;
  },
  // Riiing-riiing (400 + 450 Hz): 0,4 s, pausa 0,2 s, 0,4 s y 2 s de silencio
  doble: () => {
    const out = new Float32Array(sec(3));
    tone(out, 0, 0.4, [400, 450]);
    tone(out, sec(0.6), 0.4, [400, 450]);
    return out;
  },
  // Trino electrónico: alterna 650/800 Hz 16 veces por segundo. 1,2 s sonando, 2 s de silencio
  trino: () => {
    const out = new Float32Array(sec(3.2));
    tone(out, 0, 1.2, [650, 800], { trill: 16 });
    return out;
  },
  // Alarma: barrido 700–1500 Hz, 3 s sonando y 1 s de silencio
  estridente: () => {
    const out = new Float32Array(sec(4));
    siren(out, 0, 3, { low: 700, high: 1500, sweepsPerSecond: 4 });
    return out;
  },
};

/** Lo más fuerte posible sin distorsionar de más: al 97 % y con una saturación suave. */
function normalize(samples) {
  let peak = 0;
  for (const v of samples) peak = Math.max(peak, Math.abs(v));
  const gain = peak > 0 ? 1 / peak : 1;
  const k = Math.tanh(1.6);
  return samples.map((v) => (Math.tanh(1.6 * v * gain) / k) * 0.97);
}

function toWav(samples) {
  const data = Buffer.alloc(samples.length * 2);
  samples.forEach((v, i) => data.writeInt16LE(Math.round(Math.max(-1, Math.min(1, v)) * 32767), i * 2));
  const header = Buffer.alloc(44);
  header.write('RIFF', 0);
  header.writeUInt32LE(36 + data.length, 4);
  header.write('WAVE', 8);
  header.write('fmt ', 12);
  header.writeUInt32LE(16, 16);      // tamaño del bloque fmt
  header.writeUInt16LE(1, 20);       // PCM
  header.writeUInt16LE(1, 22);       // mono
  header.writeUInt32LE(RATE, 24);
  header.writeUInt32LE(RATE * 2, 28);
  header.writeUInt16LE(2, 32);
  header.writeUInt16LE(16, 34);
  header.write('data', 36);
  header.writeUInt32LE(data.length, 40);
  return Buffer.concat([header, data]);
}

const cache = new Map();

/** El WAV de un timbre (se fabrica una vez y se guarda). null si no existe o es "system". */
export function ringtoneWav(id) {
  if (!RECIPES[id]) return null;
  if (!cache.has(id)) cache.set(id, toWav(normalize(RECIPES[id]())));
  return cache.get(id);
}
