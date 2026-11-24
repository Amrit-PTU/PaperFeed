// Meaning-search model in a background worker: bge-small-en-v1.5 (int8 ONNX), the same file and
// tokenizer rules as the Android app and Gurbani Ask. Runs entirely in this browser.
import * as ort from '../vendor/ort.wasm.min.mjs';

const base = new URL('../', import.meta.url);
ort.env.wasm.wasmPaths = new URL('vendor/', base).href;
ort.env.wasm.numThreads = self.crossOriginIsolated ? Math.min(4, navigator.hardwareConcurrency || 1) : 1;

const QUERY_PREFIX = 'Represent this sentence for searching relevant passages: ';
const MODEL_CACHE = 'pf-model-v1';
let ready = null;
let session = null;
let tok = null;

/** Model files are big (34 MB): keep them in the Cache API so they download once. */
async function cachedFetch(path, onProgress) {
  const url = new URL(path, base).href;
  const cache = await caches.open(MODEL_CACHE);
  const hit = await cache.match(url);
  if (hit) return hit;
  const r = await fetch(url);
  if (!r.ok) throw new Error(`Couldn't download ${path} (HTTP ${r.status})`);
  const total = Number(r.headers.get('content-length')) || 0;
  const reader = r.body.getReader();
  const parts = [];
  let got = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    parts.push(value);
    got += value.length;
    if (onProgress && total) onProgress(got / total);
  }
  const blob = new Blob(parts);
  await cache.put(url, new Response(blob, { headers: { 'content-type': r.headers.get('content-type') || 'application/octet-stream' } }));
  return new Response(blob);
}

function load() {
  if (ready) return ready;
  ready = (async () => {
    const vocab = (await (await cachedFetch('model/bge_vocab.txt')).text()).split('\n');
    if (vocab[vocab.length - 1] === '') vocab.pop();
    tok = new WordPiece(vocab);
    const bytes = new Uint8Array(await (await cachedFetch('model/bge_small.onnx', (p) => postMessage({ type: 'progress', value: p }))).arrayBuffer());
    session = await ort.InferenceSession.create(bytes, { executionProviders: ['wasm'], graphOptimizationLevel: 'all' });
    postMessage({ type: 'ready' });
  })();
  ready.catch(() => { ready = null; });
  return ready;
}

async function embed(text, query, maxTokens) {
  const ids = tok.encode((query ? QUERY_PREFIX : '') + text, maxTokens);
  const n = ids.length;
  const feeds = {
    input_ids: new ort.Tensor('int64', BigInt64Array.from(ids, BigInt), [1, n]),
    attention_mask: new ort.Tensor('int64', new BigInt64Array(n).fill(1n), [1, n]),
  };
  if (session.inputNames.includes('token_type_ids')) feeds.token_type_ids = new ort.Tensor('int64', new BigInt64Array(n), [1, n]);
  const out = await session.run(feeds);
  const hidden = out[session.outputNames[0]].data; // [1, n, 384]: CLS pooling = first 384
  const v = new Float32Array(384);
  let norm = 0;
  for (let i = 0; i < 384; i++) { v[i] = hidden[i]; norm += v[i] * v[i]; }
  norm = Math.sqrt(norm);
  for (let i = 0; i < 384; i++) v[i] /= norm;
  return v;
}

self.onmessage = async (e) => {
  const { id, texts, query, maxTokens = 160 } = e.data;
  try {
    await load();
    if (!texts) { postMessage({ id, vectors: [] }); return; }
    const vectors = [];
    for (const t of texts) vectors.push(await embed(t, query, maxTokens));
    postMessage({ id, vectors }, vectors.map((v) => v.buffer));
  } catch (err) {
    postMessage({ id, error: String(err && err.message ? err.message : err) });
  }
};

/** BERT uncased WordPiece tokenizer (same rules as the model's tokenizer.json). */
class WordPiece {
  constructor(lines) {
    this.vocab = new Map(lines.map((t, i) => [t, i]));
    this.unk = this.vocab.get('[UNK]');
    this.cls = this.vocab.get('[CLS]');
    this.sep = this.vocab.get('[SEP]');
  }

  encode(text, maxLen) {
    const out = [this.cls];
    for (const w of this.basic(text)) {
      out.push(...this.piece(w));
      if (out.length >= maxLen - 1) break;
    }
    const ids = out.slice(0, maxLen - 1);
    ids.push(this.sep);
    return ids;
  }

  static isPunct(c) {
    const cp = c.codePointAt(0);
    if ((cp >= 33 && cp <= 47) || (cp >= 58 && cp <= 64) || (cp >= 91 && cp <= 96) || (cp >= 123 && cp <= 126)) return true;
    return /[\p{Pc}\p{Pd}\p{Ps}\p{Pe}\p{Pi}\p{Pf}\p{Po}]/u.test(c);
  }

  static isCjk(cp) {
    return (cp >= 0x4e00 && cp <= 0x9fff) || (cp >= 0x3400 && cp <= 0x4dbf) || (cp >= 0x20000 && cp <= 0x2a6df) ||
      (cp >= 0x2a700 && cp <= 0x2b73f) || (cp >= 0x2b740 && cp <= 0x2b81f) || (cp >= 0x2b820 && cp <= 0x2ceaf) ||
      (cp >= 0xf900 && cp <= 0xfaff) || (cp >= 0x2f800 && cp <= 0x2fa1f);
  }

  /** Clean, lowercase, strip accents, split on spaces and punctuation. */
  basic(text) {
    let sb = '';
    for (const c of text) {
      const cp = c.codePointAt(0);
      if (cp === 0 || cp === 0xfffd) continue;
      if (/\s/u.test(c) || /\p{Zs}/u.test(c)) sb += ' ';
      else if (/[\p{Cc}\p{Cf}]/u.test(c)) continue;
      else if (WordPiece.isCjk(cp)) sb += ` ${c} `;
      else sb += c;
    }
    const lowered = sb.toLowerCase().normalize('NFD').replace(/\p{Mn}/gu, '');
    const words = [];
    for (const t of lowered.split(' ')) {
      if (!t) continue;
      let cur = '';
      for (const c of t) {
        if (WordPiece.isPunct(c)) {
          if (cur) { words.push(cur); cur = ''; }
          words.push(c);
        } else cur += c;
      }
      if (cur) words.push(cur);
    }
    return words;
  }

  piece(word) {
    const chars = [...word];
    if (chars.length > 100) return [this.unk];
    const out = [];
    let start = 0;
    while (start < chars.length) {
      let end = chars.length;
      let found;
      while (start < end) {
        const sub = (start > 0 ? '##' : '') + chars.slice(start, end).join('');
        if (this.vocab.has(sub)) { found = this.vocab.get(sub); break; }
        end--;
      }
      if (found === undefined) return [this.unk];
      out.push(found);
      start = end;
    }
    return out;
  }
}
