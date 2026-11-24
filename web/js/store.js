// Everything the user keeps lives in this browser's IndexedDB: nothing is sent to any server.

const DB_NAME = 'paperfeed';
const DB_VERSION = 1;
export const READ_LATER_ID = 1;

let dbPromise = null;

function req(r) {
  return new Promise((resolve, reject) => {
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => reject(r.error);
  });
}

function done(tx) {
  return new Promise((resolve, reject) => {
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error);
  });
}

export const DEFAULT_SOURCES = [
  { name: 'Physical Review C', type: 'CROSSREF', query: '2469-9985' },
  { name: 'Nuclear Physics A', type: 'CROSSREF', query: '0375-9474' },
  { name: 'Physics Letters B', type: 'CROSSREF', query: '0370-2693' },
  { name: 'Chinese Physics C', type: 'CROSSREF', query: '1674-1137' },
  { name: 'Progress in Particle and Nuclear Physics', type: 'CROSSREF', query: '0146-6410' },
  { name: 'Annual Review of Nuclear and Particle Science', type: 'CROSSREF', query: '0163-8998' },
  { name: 'Reviews of Modern Physics', type: 'CROSSREF', query: '0034-6861' },
  { name: 'Nuclear Science and Engineering', type: 'CROSSREF', query: '0029-5639' },
  { name: 'Annals of Nuclear Energy', type: 'CROSSREF', query: '0306-4549' },
  { name: 'Journal of Nuclear Science and Technology', type: 'CROSSREF', query: '0022-3131' },
  { name: 'Journal of Physics G', type: 'CROSSREF', query: '0954-3899' },
  // Browsers can't read the arXiv API directly (no CORS), so arXiv comes through OpenAlex.
  { name: 'arXiv nuclear & high-energy', type: 'ARXIV', query: 'subfield:3106' },
];

export function open() {
  if (dbPromise) return dbPromise;
  dbPromise = new Promise((resolve, reject) => {
    const r = indexedDB.open(DB_NAME, DB_VERSION);
    r.onupgradeneeded = () => {
      const db = r.result;
      const articles = db.createObjectStore('articles', { keyPath: 'key' });
      articles.createIndex('published', 'published');
      articles.createIndex('sourceId', 'sourceId');
      const sources = db.createObjectStore('sources', { keyPath: 'id', autoIncrement: true });
      const lists = db.createObjectStore('lists', { keyPath: 'id', autoIncrement: true });
      const entries = db.createObjectStore('entries', { keyPath: ['listId', 'articleKey'] });
      entries.createIndex('articleKey', 'articleKey');
      entries.createIndex('listId', 'listId');
      const notes = db.createObjectStore('notes', { keyPath: 'id', autoIncrement: true });
      notes.createIndex('articleKey', 'articleKey');
      db.createObjectStore('vectors', { keyPath: 'key' });
      db.createObjectStore('kv');
      const now = Date.now();
      lists.add({ id: READ_LATER_ID, name: 'Read later', position: 0, createdAt: now });
      lists.add({ name: 'Important', position: 1, createdAt: now });
      DEFAULT_SOURCES.forEach((s) => sources.add({ ...s, enabled: true, lastFetched: 0, lastError: null }));
    };
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => reject(r.error);
  });
  return dbPromise;
}

async function store(name, mode = 'readonly') {
  const db = await open();
  return db.transaction(name, mode).objectStore(name);
}

export async function all(name) {
  return req((await store(name)).getAll());
}

export async function get(name, key) {
  return req((await store(name)).get(key));
}

export async function put(name, value) {
  return req((await store(name, 'readwrite')).put(value));
}

export async function add(name, value) {
  return req((await store(name, 'readwrite')).add(value));
}

export async function del(name, key) {
  return req((await store(name, 'readwrite')).delete(key));
}

export async function byIndex(name, index, value) {
  return req((await store(name)).index(index).getAll(value));
}

export async function clear(name) {
  return req((await store(name, 'readwrite')).clear());
}

/** Inserts articles that aren't stored yet; returns the new ones. */
export async function insertNew(articles) {
  if (!articles.length) return [];
  const have = new Set(await req((await store('articles')).getAllKeys()));
  const seen = new Set();
  const fresh = articles.filter((a) => !have.has(a.key) && !seen.has(a.key) && seen.add(a.key));
  if (fresh.length) await putMany('articles', fresh);
  return fresh;
}

export async function keys(name) {
  return req((await store(name)).getAllKeys());
}

export async function putMany(name, values) {
  const db = await open();
  const tx = db.transaction(name, 'readwrite');
  const st = tx.objectStore(name);
  values.forEach((v) => st.put(v));
  await done(tx);
}

export async function update(name, key, fn) {
  const cur = await get(name, key);
  if (!cur) return null;
  const next = fn(cur);
  await put(name, next);
  return next;
}

export async function kvGet(key, fallback) {
  const v = await get('kv', key);
  return v === undefined ? fallback : v;
}

export async function kvSet(key, value) {
  const db = await open();
  const tx = db.transaction('kv', 'readwrite');
  tx.objectStore('kv').put(value, key);
  await done(tx);
}

/** Deletes old feed items the user never kept (rated, in a list or with a note). */
export async function prune(before) {
  const [articles, entries, notes] = await Promise.all([all('articles'), all('entries'), all('notes')]);
  const kept = new Set([...entries.map((e) => e.articleKey), ...notes.map((n) => n.articleKey)]);
  const drop = articles.filter((a) => a.fetchedAt < before && !a.rating && !kept.has(a.key)).map((a) => a.key);
  if (!drop.length) return 0;
  const db = await open();
  const tx = db.transaction(['articles', 'vectors'], 'readwrite');
  drop.forEach((k) => { tx.objectStore('articles').delete(k); tx.objectStore('vectors').delete(k); });
  await done(tx);
  return drop.length;
}
