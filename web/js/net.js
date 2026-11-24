// Paper sources for the browser: Crossref (journals, all years) and OpenAlex (arXiv, keyword
// searches, authors, abstracts). Both allow requests straight from a web page.
import { clean, cleanTitle, normalizeDoi, keyFor, parseDate, yearOf, familyName } from './text.js';

const ARXIV_SOURCE = 'S4306400194';
const CROSSREF_SELECT = 'DOI,title,author,created,published,issued,container-title,abstract,volume,issue,page,URL,type,ISSN';
const OA_SELECT = 'doi,display_name,publication_date,authorships,primary_location,abstract_inverted_index,biblio,id';
const JUNK = /^(editorial board|issue editorial masthead|contents|table of contents|graphical abstract|copyright|cover|front matter|back matter|masthead|information for authors)/i;

const config = { email: '', openAlexKey: '' };
export function configure(c) { Object.assign(config, c); }

const enc = encodeURIComponent;
const mailto = () => (config.email.includes('@') ? `&mailto=${enc(config.email)}` : '');
const oaAuth = () => mailto() + (config.openAlexKey ? `&api_key=${enc(config.openAlexKey)}` : '');
const day = (ms) => new Date(ms).toISOString().slice(0, 10);

async function getJson(url, { timeout = 20000, accept } = {}) {
  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), timeout);
  try {
    const r = await fetch(url, { signal: ctrl.signal, headers: accept ? { Accept: accept } : undefined });
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    return accept && !accept.includes('json') ? r.text() : r.json();
  } catch (e) {
    if (e.name === 'AbortError') throw new Error('Timed out');
    throw e;
  } finally {
    clearTimeout(t);
  }
}

// ---------------- Crossref ----------------

function datePartsToMs(o) {
  const p = o?.['date-parts']?.[0];
  if (!p || !p[0]) return null;
  return Date.UTC(p[0], Math.min(Math.max(p[1] || 1, 1), 12) - 1, Math.min(Math.max(p[2] || 1, 1), 28));
}

export function crossrefItem(o, sourceId, fallbackJournal) {
  const title = cleanTitle(o.title?.[0]);
  if (!title || JUNK.test(title)) return null;
  const doi = o.DOI ? normalizeDoi(o.DOI) : null;
  const authors = (o.author || [])
    .map((a) => [a.given, a.family].filter(Boolean).join(' ') || a.name)
    .filter(Boolean);
  const created = parseDate(o.created?.['date-time']);
  const issued = datePartsToMs(o.published || o.issued);
  // New papers appear online before their issue date; old ones were registered long after.
  const published = Math.min(...[created, issued].filter((x) => x != null), Date.now());
  return {
    key: keyFor(doi, o.URL, title),
    title,
    authors: authors.join('\n'),
    journal: o['container-title']?.[0] || fallbackJournal,
    sourceId,
    doi,
    url: o.URL || null,
    abstract: clean(o.abstract),
    published,
    year: yearOf(published),
    volume: o.volume || null,
    issue: o.issue || null,
    pages: o.page || null,
    fetchedAt: Date.now(),
    rating: 0,
    read: false,
  };
}

// After a Crossref failure, go straight to OpenAlex for 10 minutes instead of waiting each time.
let crossrefDownUntil = 0;
function crossrefCall(url) {
  if (Date.now() < crossrefDownUntil) return Promise.reject(new Error('Crossref unavailable'));
  return getJson(url, { timeout: 15000 }).catch((e) => { crossrefDownUntil = Date.now() + 600000; throw e; });
}

async function crossrefJournal(src, before) {
  const filter = `type:journal-article${before ? `,until-created-date:${day(before)}` : ''}`;
  const url = `https://api.crossref.org/journals/${enc(src.query.trim())}/works?sort=created&order=desc&rows=40` +
    `&filter=${filter}&select=${CROSSREF_SELECT}${mailto()}`;
  try {
    const data = await crossrefCall(url);
    return data.message.items.map((o) => crossrefItem(o, src.id, src.name)).filter(Boolean);
  } catch (e) {
    // Crossref is sometimes slow or down; OpenAlex indexes the same journals by ISSN.
    const f = [`primary_location.source.issn:${src.query.trim()}`];
    if (before) f.push(`to_publication_date:${day(before)}`);
    const { items } = await oaWorks(`filter=${f.join(',')}&sort=publication_date:desc&per-page=40`, src.id);
    if (!items.length && !before) throw e;
    return items.map((a) => ({ ...a, journal: a.journal === 'OpenAlex' ? src.name : a.journal }));
  }
}

export async function journalName(issn) {
  const data = await getJson(`https://api.crossref.org/journals/${enc(issn.trim())}?${mailto().slice(1)}`);
  return data.message?.title || null;
}

// ---------------- OpenAlex ----------------

export function invertedAbstract(index) {
  if (!index) return null;
  const words = [];
  for (const [w, positions] of Object.entries(index)) positions.forEach((p) => { words[p] = w; });
  const text = words.filter((w) => w !== undefined).join(' ');
  return text ? clean(text) : null;
}

function oaItem(o, sourceId) {
  const title = cleanTitle(o.display_name);
  if (!title) return null;
  const doi = o.doi ? normalizeDoi(o.doi) : null;
  const authors = (o.authorships || []).map((a) => a.author?.display_name).filter(Boolean);
  const published = parseDate(o.publication_date) || Date.now();
  const loc = o.primary_location;
  const landing = loc?.landing_page_url || null;
  const b = o.biblio || {};
  return {
    key: keyFor(doi, landing || o.id, title),
    title,
    authors: authors.join('\n'),
    journal: loc?.source?.id?.endsWith(ARXIV_SOURCE) ? 'arXiv' : loc?.source?.display_name || 'OpenAlex',
    sourceId,
    doi,
    url: landing || o.id,
    abstract: invertedAbstract(o.abstract_inverted_index),
    published,
    year: yearOf(published),
    volume: b.volume || null,
    issue: b.issue || null,
    pages: b.first_page || null,
    fetchedAt: Date.now(),
    rating: 0,
    read: false,
  };
}

async function oaWorks(params, sourceId) {
  const data = await getJson(`https://api.openalex.org/works?${params}&select=${OA_SELECT}${oaAuth()}`);
  return { items: (data.results || []).map((o) => oaItem(o, sourceId)).filter(Boolean), next: data.meta?.next_cursor || null };
}

/** OpenAlex filter + search for an arXiv source: "subfield:3106", "topic:T10123" or keywords. */
function arxivParams(query) {
  const q = query.trim();
  const m = q.match(/^(subfield|topic|field):(\S+)$/);
  const filter = [`primary_location.source.id:${ARXIV_SOURCE}`];
  if (m) filter.push(`primary_topic.${m[1]}.id:${m[2]}`);
  return { filter, search: m ? '' : q };
}

function sourceParams(src, before) {
  if (src.type === 'ARXIV') {
    const { filter, search } = arxivParams(src.query);
    if (before) filter.push(`to_publication_date:${day(before)}`);
    return `filter=${filter.join(',')}${search ? `&search=${enc(search)}` : ''}`;
  }
  const filter = before ? `&filter=to_publication_date:${day(before)}` : '';
  return `search=${enc(src.query.trim())}${filter}`;
}

// ---------------- Feeds ----------------

/** Newest articles of a source. */
export async function fetchSource(src) {
  if (src.type === 'CROSSREF') return crossrefJournal(src);
  return (await oaWorks(`${sourceParams(src)}&sort=publication_date:desc&per-page=40`, src.id)).items;
}

/** One page of a source's articles released on or before [before]. */
export async function fetchOlder(src, before) {
  if (src.type === 'CROSSREF') return crossrefJournal(src, before);
  return (await oaWorks(`${sourceParams(src, before)}&sort=publication_date:desc&per-page=40`, src.id)).items;
}

/** Fills missing abstracts from OpenAlex, 50 DOIs per request. Returns key -> abstract. */
export async function lookupAbstracts(articles) {
  const out = new Map();
  const withDoi = articles.filter((a) => a.doi && !a.abstract);
  for (let i = 0; i < withDoi.length; i += 50) {
    const chunk = withDoi.slice(i, i + 50);
    try {
      const data = await getJson(`https://api.openalex.org/works?filter=doi:${chunk.map((a) => enc(a.doi)).join('|')}` +
        `&per-page=50&select=doi,abstract_inverted_index${oaAuth()}`);
      for (const r of data.results || []) {
        const doi = r.doi && normalizeDoi(r.doi);
        const abs = invertedAbstract(r.abstract_inverted_index);
        const a = chunk.find((x) => x.doi === doi);
        if (a && abs) out.set(a.key, abs);
      }
    } catch { /* try again on a later refresh */ }
  }
  return out;
}

/** Best-effort lookup of one missing abstract. */
export async function findAbstract(a) {
  if (!a.doi) return null;
  try {
    const o = await getJson(`https://api.openalex.org/works/doi:${a.doi}?select=abstract_inverted_index${oaAuth()}`);
    const abs = invertedAbstract(o.abstract_inverted_index);
    if (abs) return abs;
  } catch { /* next */ }
  try {
    const o = await getJson(`https://api.crossref.org/works/${enc(a.doi)}?${mailto().slice(1)}`);
    return clean(o.message?.abstract);
  } catch {
    return null;
  }
}

export async function bibtex(doi) {
  return (await getJson(`https://api.crossref.org/works/${enc(doi)}/transform/application/x-bibtex`, { accept: 'application/x-bibtex' })).trim();
}

// ---------------- Search across the archives ----------------

/**
 * Searches every journal source (Crossref, all years) and the arXiv sources (OpenAlex).
 * field: 'any' | 'author'. Pass the returned cursor back for the next page.
 */
export async function searchArchive(query, field, newest, sources, cursor = {}) {
  const enabled = sources.filter((s) => s.enabled);
  const issns = enabled.filter((s) => s.type === 'CROSSREF').map((s) => s.query.trim());
  const byIssn = new Map(enabled.filter((s) => s.type === 'CROSSREF').map((s) => [s.query.trim(), s]));
  const arxiv = enabled.find((s) => s.type === 'ARXIV');

  const journalsOpenAlex = async () => {
    const filter = [`primary_location.source.issn:${issns.join('|')}`];
    let params;
    if (field === 'author') { filter.push(`raw_author_name.search:${enc(query)}`); params = `filter=${filter.join(',')}`; }
    else params = `filter=${filter.join(',')}&search=${enc(query)}`;
    if (newest) params += '&sort=publication_date:desc';
    const r = await oaWorks(`${params}&per-page=30&cursor=${enc(cursor.crossref || '*')}`, -1);
    return { items: r.items, next: r.items.length ? r.next : null, via: 'openalex' };
  };

  const journals = (async () => {
    if (cursor.crossrefDone || !issns.length) return { items: [], next: null };
    if (cursor.jvia === 'openalex') return journalsOpenAlex();
    try {
      return await journalsCrossref();
    } catch (e) {
      if (cursor.crossref) throw e; // mid-way through Crossref pages: don't mix services
      return journalsOpenAlex();
    }
  })();

  async function journalsCrossref() {
    const param = field === 'author' ? 'query.author' : 'query.bibliographic';
    const filter = [...issns.map((i) => `issn:${i}`), 'type:journal-article'].join(',');
    const offset = newest ? Number(cursor.crossref || 0) : 0;
    const url = `https://api.crossref.org/works?${param}=${enc(query)}&filter=${filter}&rows=30&select=${CROSSREF_SELECT}` +
      (newest ? `&sort=published&order=desc&offset=${offset}` : `&cursor=${enc(cursor.crossref || '*')}`) + mailto();
    const msg = (await crossrefCall(url)).message;
    const items = msg.items.map((o) => {
      const src = (o.ISSN || []).map((i) => byIssn.get(i)).find(Boolean);
      return crossrefItem(o, -1, src?.name || '');
    }).filter(Boolean);
    const next = newest ? (msg.items.length === 30 && offset + 30 < 10000 ? String(offset + 30) : null)
      : (msg.items.length ? msg['next-cursor'] : null);
    return { items, next, via: 'crossref' };
  }

  const preprints = (async () => {
    if (cursor.oaDone || !arxiv) return { items: [], next: null };
    const filter = [`primary_location.source.id:${ARXIV_SOURCE}`];
    let params;
    if (field === 'author') {
      filter.push(`raw_author_name.search:${enc(query)}`);
      params = `filter=${filter.join(',')}`;
    } else {
      params = `filter=${filter.join(',')}&search=${enc(query)}`;
    }
    params += newest ? '&sort=publication_date:desc' : '';
    try {
      return await oaWorks(`${params}&per-page=30&cursor=${enc(cursor.oa || '*')}`, -1);
    } catch {
      return { items: [], next: cursor.oa || '*', failed: true };
    }
  })();

  const [j, p] = await Promise.all([journals, preprints]);
  const merged = [...j.items, ...p.items];
  const unique = merged.filter((a, i) => merged.findIndex((b) => b.key === a.key) === i);
  if (newest) unique.sort((a, b) => b.published - a.published);
  return {
    items: unique,
    cursor: {
      crossref: j.next, crossrefDone: !j.next, jvia: j.via || cursor.jvia,
      oa: p.next, oaDone: !arxiv || (!p.failed && (!p.next || !p.items.length)),
    },
  };
}

// ---------------- Authors ----------------

const authorFilters = new Map();

async function authorFilter(name) {
  if (authorFilters.has(name)) return authorFilters.get(name);
  const fam = familyName(name);
  let filter = `raw_author_name.search:${enc(`"${name}"`)}`;
  try {
    const data = await getJson(`https://api.openalex.org/authors?search=${enc(name)}&per-page=10&select=id,display_name,works_count${oaAuth()}`);
    const best = (data.results || []).filter((a) => familyName(a.display_name) === fam)
      .sort((a, b) => b.works_count - a.works_count)[0];
    if (best) filter = `author.id:${best.id.split('/').pop()}`;
  } catch { /* byline match */ }
  authorFilters.set(name, filter);
  return filter;
}

/** One page of an author's works, newest first. */
export async function authorWorks(name, cursor) {
  const f = await authorFilter(name);
  return oaWorks(`filter=${f}&sort=publication_date:desc&per-page=50&cursor=${enc(cursor || '*')}`, -1);
}
