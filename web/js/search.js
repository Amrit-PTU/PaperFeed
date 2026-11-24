// Meaning search over papers (same method as the Android app's SmartSearch.kt): every stored
// paper gets a vector in the background; a search is embedded once and compared with the
// library and with fresh online results, ranked together by one score.
import * as db from './store.js';

const LIBRARY_MIN = 0.62;
const ONLINE_MIN = 0.5;
const STOP = new Set(['the', 'and', 'for', 'with', 'from', 'into', 'that', 'this', 'are', 'via', 'using', 'about']);

let worker = null;
let nextId = 1;
const pending = new Map();
const library = new Map(); // key -> Float32Array
const remote = new Map(); // key -> { withAbstract, vec }
let loaded = null;
let indexing = false;
const listeners = new Set();
export const status = { modelProgress: 0, modelReady: false, pending: 0, indexing: false, error: null };

export function onStatus(fn) { listeners.add(fn); return () => listeners.delete(fn); }
function emit() { listeners.forEach((fn) => fn(status)); }

function getWorker() {
  if (worker) return worker;
  worker = new Worker(new URL('./embed-worker.js', import.meta.url), { type: 'module' });
  worker.onmessage = (e) => {
    const m = e.data;
    if (m.type === 'progress') { status.modelProgress = m.value; emit(); return; }
    if (m.type === 'ready') { status.modelReady = true; status.error = null; emit(); return; }
    const p = pending.get(m.id);
    if (!p) return;
    pending.delete(m.id);
    if (m.error) { status.error = m.error; emit(); p.reject(new Error(m.error)); } else p.resolve(m.vectors);
  };
  worker.onerror = (e) => { status.error = e.message || 'The search model failed to start'; emit(); };
  return worker;
}

function embedMany(texts, query = false) {
  return new Promise((resolve, reject) => {
    const id = nextId++;
    pending.set(id, { resolve, reject });
    getWorker().postMessage({ id, texts, query });
  });
}

export const paperText = (a) => (a.abstract ? `${a.title}. ${a.abstract.slice(0, 1200)}` : a.title);

function cosine(a, b) {
  let s = 0;
  for (let i = 0; i < a.length; i++) s += a[i] * b[i];
  return s;
}

function terms(q) {
  return [...new Set(q.toLowerCase().split(/[^\p{L}\p{N}]+/u).filter((t) => t.length >= 3 && !STOP.has(t)))];
}

function keywordFraction(a, ts) {
  if (!ts.length) return 0;
  const text = `${a.title} ${a.abstract || ''}`.toLowerCase();
  return ts.filter((t) => text.includes(t)).length / ts.length;
}

function score(c, a, ts) {
  if (!ts.length) return c;
  const title = a.title.toLowerCase();
  const titleFrac = ts.filter((t) => title.includes(t)).length / ts.length;
  return c + 0.05 * keywordFraction(a, ts) + 0.03 * titleFrac;
}

async function ensureLoaded() {
  if (!loaded) {
    loaded = db.all('vectors').then((rows) => { rows.forEach((r) => library.set(r.key, r.vec)); });
  }
  return loaded;
}

/** Papers that need a (new) vector, newest first. */
async function needing() {
  await ensureLoaded();
  const [articles, vecs] = await Promise.all([db.all('articles'), db.all('vectors')]);
  const withAbs = new Map(vecs.map((v) => [v.key, v.withAbstract]));
  return articles
    .filter((a) => !withAbs.has(a.key) || (!withAbs.get(a.key) && a.abstract))
    .sort((a, b) => b.published - a.published);
}

let enabled = false;
/** Indexing starts only once meaning search has been used, so the model isn't downloaded for nothing. */
export function enable() { enabled = true; }

/** Embeds stored papers that have no vector yet. Safe to call often. */
export async function index() {
  if (indexing || !enabled) return;
  indexing = true;
  status.indexing = true;
  try {
    const todo = await needing();
    status.pending = todo.length;
    emit();
    for (let i = 0; i < todo.length; i += 8) {
      const batch = todo.slice(i, i + 8);
      const need = batch.filter((a) => { const r = remote.get(a.key); return !(r && r.withAbstract === !!a.abstract); });
      const vecs = need.length ? await embedMany(need.map(paperText)) : [];
      const rows = batch.map((a) => {
        const j = need.indexOf(a);
        const vec = j >= 0 ? vecs[j] : remote.get(a.key).vec;
        library.set(a.key, vec);
        return { key: a.key, vec, withAbstract: !!a.abstract };
      });
      await db.putMany('vectors', rows);
      status.pending = Math.max(0, todo.length - i - batch.length);
      emit();
    }
  } catch (e) {
    status.error = e.message;
  } finally {
    indexing = false;
    status.indexing = false;
    emit();
  }
}

export async function refreshPendingCount() {
  status.pending = (await needing()).length;
  emit();
}

export async function queryVector(q) {
  return (await embedMany([q], true))[0];
}

/** Stored papers closest in meaning to the search. */
export async function searchLibrary(q, qv, limit = 60) {
  await ensureLoaded();
  const nearest = [];
  for (const [key, vec] of library) nearest.push([key, cosine(qv, vec)]);
  nearest.sort((a, b) => b[1] - a[1]);
  const top = nearest.slice(0, limit * 3);
  const ts = terms(q);
  const hits = [];
  for (const [key, c] of top) {
    const a = await db.get('articles', key);
    if (!a) continue;
    if (c >= LIBRARY_MIN || keywordFraction(a, ts) === 1) hits.push({ article: a, score: score(c, a, ts), inLibrary: true });
  }
  return hits.sort((x, y) => y.score - x.score).slice(0, limit);
}

/** Ranks online results (already keyword-matched by the server) by meaning. */
export async function rankOnline(q, qv, articles) {
  await ensureLoaded();
  const ts = terms(q);
  const need = articles.filter((a) => {
    if (library.has(a.key)) return false;
    const r = remote.get(a.key);
    return !(r && r.withAbstract === !!a.abstract);
  });
  if (need.length) {
    const vecs = await embedMany(need.map(paperText));
    need.forEach((a, i) => remote.set(a.key, { withAbstract: !!a.abstract, vec: vecs[i] }));
  }
  return articles.map((a) => {
    const v = library.get(a.key) || remote.get(a.key).vec;
    const c = cosine(qv, v);
    return c < ONLINE_MIN ? null : { article: a, score: score(c, a, ts), inLibrary: false };
  }).filter(Boolean).sort((x, y) => y.score - x.score);
}

/** Starts loading the model early (e.g. when the search page opens). */
export function warmUp() {
  embedMany(null).catch(() => {});
}

export function forget(keys) { keys.forEach((k) => library.delete(k)); }
