// PaperFeed for the web: follows journals and arXiv, keeps lists, ratings and notes in this
// browser, and searches papers by meaning with an on-device model.
import * as db from './store.js';
import * as net from './net.js';
import * as smart from './search.js';
import { icon } from './icons.js';
import { escapeHtml as h, dateText, citation, linkOf, familyName, foldName } from './text.js';

const DEFAULT_SETTINGS = {
  mode: 'system', hue: 265, scale: 1, layout: 'cards', preview: 2,
  email: '', openAlexKey: '', notify: false, refreshMinutes: 60,
};
const HUES = [265, 220, 190, 150, 110, 70, 40, 15, 340, 300];

let settings = { ...DEFAULT_SETTINGS };
const main = document.querySelector('main');
const transient = new Map(); // search/author results not stored yet
const scrollPos = new Map();
let currentPath = null;
let navDepth = 0;
let refreshing = false;

// ---------------- Small helpers ----------------

function snack(msg, ms = 3500) {
  document.querySelector('.snackbar')?.remove();
  const el = document.createElement('div');
  el.className = 'snackbar';
  el.textContent = msg;
  document.body.append(el);
  setTimeout(() => el.remove(), ms);
}

function go(hash) { navDepth++; location.hash = hash; }
function back() { if (navDepth > 0) { navDepth--; history.back(); } else location.hash = '#/'; }

async function pool(items, n, fn) {
  const queue = [...items];
  await Promise.all(Array.from({ length: Math.min(n, queue.length) }, async () => {
    while (queue.length) await fn(queue.shift());
  }));
}

function sheet(html, { onClose } = {}) {
  const d = document.createElement('dialog');
  d.className = 'sheet';
  d.innerHTML = `<div class="handle"></div>${html}`;
  document.body.append(d);
  d.addEventListener('click', (e) => { if (e.target === d) d.close(); });
  d.addEventListener('close', () => { d.remove(); onClose?.(); });
  d.showModal();
  return d;
}

function ask({ title, value = '', placeholder = '', confirm = 'Save' }) {
  return new Promise((resolve) => {
    let result = null;
    const d = sheet(`<h3>${h(title)}</h3><div class="body" style="padding:0 16px"><input value="${h(value)}" placeholder="${h(placeholder)}"></div>
      <div class="actions"><button class="btn text" data-x>Cancel</button><button class="btn filled" data-ok>${h(confirm)}</button></div>`,
    { onClose: () => resolve(result) });
    const input = d.querySelector('input');
    const ok = () => { result = input.value.trim() || null; d.close(); };
    d.querySelector('[data-ok]').onclick = ok;
    d.querySelector('[data-x]').onclick = () => d.close();
    input.addEventListener('keydown', (e) => { if (e.key === 'Enter') ok(); });
    setTimeout(() => input.focus(), 50);
  });
}

function confirmBox(title, text, confirm = 'Delete') {
  return new Promise((resolve) => {
    let result = false;
    const d = sheet(`<h3>${h(title)}</h3><div class="hint" style="padding:0 24px">${h(text)}</div>
      <div class="actions"><button class="btn text" data-x>Cancel</button><button class="btn filled" data-ok>${h(confirm)}</button></div>`,
    { onClose: () => resolve(result) });
    d.querySelector('[data-ok]').onclick = () => { result = true; d.close(); };
    d.querySelector('[data-x]').onclick = () => d.close();
  });
}

async function copy(text, what = 'Copied') {
  try { await navigator.clipboard.writeText(text); snack(what); } catch { snack('Copy failed'); }
}

// ---------------- Settings & theme ----------------

async function loadSettings() {
  settings = { ...DEFAULT_SETTINGS, ...(await db.kvGet('settings', {})) };
  net.configure({ email: settings.email, openAlexKey: settings.openAlexKey });
  applyTheme();
}

async function saveSettings(patch) {
  settings = { ...settings, ...patch };
  await db.kvSet('settings', settings);
  net.configure({ email: settings.email, openAlexKey: settings.openAlexKey });
  applyTheme();
}

const darkQuery = matchMedia('(prefers-color-scheme: dark)');
function applyTheme() {
  const dark = settings.mode === 'dark' || (settings.mode === 'system' && darkQuery.matches);
  const root = document.documentElement;
  root.dataset.theme = dark ? 'dark' : 'light';
  root.style.setProperty('--hue', settings.hue);
  root.style.setProperty('--scale', settings.scale);
  try { localStorage.setItem('pf-theme', JSON.stringify({ mode: settings.mode, hue: settings.hue })); } catch { /* private mode */ }
  const bg = getComputedStyle(document.body).backgroundColor;
  document.querySelector('meta[name="theme-color"]').setAttribute('content', bg);
}
darkQuery.addEventListener('change', applyTheme);

// ---------------- Paper cards ----------------

function authorLinks(a, max = 2) {
  const names = a.authors ? a.authors.split('\n').filter(Boolean) : [];
  const shown = names.slice(0, max).map((n) => `<a href="#/author/${encodeURIComponent(n)}" data-author>${h(n)}</a>`).join(', ');
  return shown + (names.length > max ? ' et al.' : '');
}

function card(a) {
  const stars = a.rating ? `<span class="stars-mini">${'★'.repeat(a.rating)}</span>` : '';
  const abs = settings.preview > 0 && a.abstract
    ? `<div class="abs" style="-webkit-line-clamp:${settings.preview}">${h(a.abstract.replace(/\n/g, ' '))}</div>` : '';
  return `<div class="card${a.read ? ' read' : ''}" data-open="${h(a.key)}" tabindex="0">
    <div class="t">${h(a.title)}</div>${abs}
    <div class="meta"><span class="who"><span class="names">${authorLinks(a)}</span>${a.year ? `<span class="yr">${a.authors ? ' · ' : ''}${a.year}</span>` : ''}</span>${stars}<span class="jr">${h(a.journal)}</span></div>
  </div>`;
}

/** Opens a paper (storing a search result first so lists, ratings and notes work on it). */
async function openArticle(key) {
  const t = transient.get(key);
  if (t) await db.insertNew([t]);
  go(`#/article/${encodeURIComponent(key)}`);
}

document.addEventListener('click', (e) => {
  if (e.target.closest('[data-author]')) return; // author links navigate by themselves
  const c = e.target.closest('[data-open]');
  if (c) { e.preventDefault(); openArticle(c.dataset.open); }
});
document.addEventListener('keydown', (e) => {
  const c = e.target.closest?.('[data-open]');
  if (c && e.key === 'Enter') openArticle(c.dataset.open);
});

// ---------------- Feed: refresh & going back in time ----------------

const exhausted = new Set();
let loadingOlder = false;

async function feedList(sourceFilter) {
  const [articles, sources] = await Promise.all([db.all('articles'), db.all('sources')]);
  const enabled = new Set(sources.filter((s) => s.enabled && (sourceFilter == null || s.id === sourceFilter)).map((s) => s.id));
  const list = articles.filter((a) => enabled.has(a.sourceId));
  // Show only articles newer than the date every source is loaded down to, so the merged
  // feed stays in strict date order with no gaps between journals.
  const oldest = new Map();
  list.forEach((a) => { if (!oldest.has(a.sourceId) || a.published < oldest.get(a.sourceId)) oldest.set(a.sourceId, a.published); });
  let mark = 0;
  oldest.forEach((t, id) => { if (!exhausted.has(id)) mark = Math.max(mark, t); });
  return { items: list.filter((a) => a.published >= mark).sort((a, b) => b.published - a.published || b.fetchedAt - a.fetchedAt), oldest, mark };
}

async function loadOlder(sourceFilter) {
  if (loadingOlder) return 0;
  loadingOlder = true;
  try {
    const { oldest, mark } = await feedList(sourceFilter);
    const sources = (await db.all('sources')).filter((s) => s.enabled && (sourceFilter == null || s.id === sourceFilter) && !exhausted.has(s.id));
    const targets = sources.filter((s) => (oldest.get(s.id) ?? Date.now()) >= mark - 86400000);
    if (!targets.length) return -1;
    let added = 0;
    await pool(targets, 4, async (s) => {
      let before = oldest.get(s.id) ?? Date.now();
      for (let attempt = 0; attempt < 4; attempt++) {
        let page;
        try { page = await net.fetchOlder(s, before); } catch { exhausted.add(s.id); return; }
        if (!page.length) { exhausted.add(s.id); return; }
        const fresh = await db.insertNew(page);
        added += fresh.length;
        if (fresh.length) { fillAbstracts(fresh); return; }
        before = Math.min(before, ...page.map((a) => a.published)) - 86400000;
      }
      exhausted.add(s.id);
    });
    return added;
  } finally {
    loadingOlder = false;
  }
}

async function fillAbstracts(articles) {
  const now = Date.now();
  const found = await net.lookupAbstracts(articles);
  for (const a of articles) {
    const abs = found.get(a.key);
    await db.update('articles', a.key, (x) => (abs ? { ...x, abstract: abs } : { ...x, abstractTries: (x.abstractTries || 0) + 1, abstractAt: now }));
  }
  return found.size;
}

async function refreshAll(only = null) {
  if (refreshing) return;
  refreshing = true;
  document.querySelectorAll('[data-act="refresh"]').forEach((b) => b.classList.add('on'));
  if (currentPath === '' || currentPath === null) document.getElementById('feedprog')?.removeAttribute('hidden');
  let fresh = [];
  let errors = 0;
  try {
    const sources = (await db.all('sources')).filter((s) => s.enabled && (only == null || s.id === only));
    await pool(sources, 4, async (s) => {
      try {
        const items = await net.fetchSource(s);
        const f = await db.insertNew(items);
        fresh = fresh.concat(f);
        await db.put('sources', { ...s, lastFetched: Date.now(), lastError: null });
      } catch (e) {
        errors++;
        await db.put('sources', { ...s, lastError: e.message || String(e) });
      }
    });
    // Abstracts that journals leave out (Elsevier, T&F) come from OpenAlex, which lags a few days.
    const now = Date.now();
    const retry = (await db.all('articles')).filter((a) => !a.abstract && a.doi && (a.abstractTries || 0) < 5 &&
      now - (a.abstractAt || 0) > 6 * 3600000 && !fresh.includes(a)).slice(0, 100);
    await fillAbstracts([...fresh.filter((a) => !a.abstract), ...retry]);
    await db.kvSet('lastRefresh', Date.now());
    snack((fresh.length ? `${fresh.length} new paper${fresh.length === 1 ? '' : 's'}` : 'No new papers') +
      (errors ? ` · ${errors} source${errors === 1 ? '' : 's'} failed` : ''));
    if (fresh.length) notify(fresh);
  } finally {
    refreshing = false;
    document.querySelectorAll('[data-act="refresh"]').forEach((b) => b.classList.remove('on'));
    document.getElementById('feedprog')?.setAttribute('hidden', '');
    if (currentPath === '') renderFeed();
    smart.index();
  }
}

async function notify(fresh) {
  if (!settings.notify || !('Notification' in window) || Notification.permission !== 'granted' || document.visibilityState === 'visible') return;
  const body = fresh.length === 1 ? fresh[0].title : `${fresh.length} new papers · ${fresh[0].title}`;
  try {
    const reg = await navigator.serviceWorker?.getRegistration();
    if (reg) reg.showNotification('PaperFeed', { body, icon: 'icons/icon-192.png', tag: 'new-papers' });
    else new Notification('PaperFeed', { body, icon: 'icons/icon-192.png' });
  } catch { /* not allowed here */ }
}

// ---------------- Home ----------------

let homeFilter = null;
let homeShown = 60;
let homeEnd = false;

async function renderHome() {
  const sources = (await db.all('sources')).filter((s) => s.enabled);
  main.innerHTML = `
    <div class="top">
      <div class="searchbar" data-go="#/search" role="button" tabindex="0">
        <span class="icon-btn">${icon('search')}</span><span class="label">Search papers</span>
        <button class="icon-btn" data-act="refresh" title="Refresh">${icon('refresh')}</button>
        <button class="icon-btn" data-act="view" title="Text size and layout">${icon('textFields')}</button>
      </div>
    </div>
    ${sources.length > 1 ? `<div class="chips">
      <button class="chip${homeFilter == null ? ' on' : ''}" data-src="">All</button>
      ${sources.map((s) => `<button class="chip${homeFilter === s.id ? ' on' : ''}" data-src="${s.id}">${h(s.name)}</button>`).join('')}
    </div>` : ''}
    <div id="feedprog" class="progress" ${refreshing ? '' : 'hidden'}></div>
    <div id="feed"></div>`;
  main.querySelector('.searchbar').addEventListener('click', (e) => { if (!e.target.closest('button')) go('#/search'); });
  main.querySelector('[data-act="refresh"]').onclick = () => { homeEnd = false; refreshAll(); };
  main.querySelector('[data-act="view"]').onclick = viewSheet;
  main.querySelectorAll('[data-src]').forEach((b) => {
    b.onclick = () => {
      homeFilter = b.dataset.src ? Number(b.dataset.src) : null;
      homeShown = 60; homeEnd = false;
      main.querySelectorAll('[data-src]').forEach((x) => x.classList.toggle('on', x === b));
      window.scrollTo(0, 0);
      renderFeed();
    };
  });
  const last = await db.kvGet('lastRefresh', 0);
  if (Date.now() - last > 30 * 60000 && !refreshing) refreshAll();
  await renderFeed();
}

let feedObserver = null;
async function renderFeed() {
  const box = document.getElementById('feed');
  if (!box) return;
  const { items } = await feedList(homeFilter);
  if (!items.length) {
    box.innerHTML = `<div class="empty">${refreshing ? 'Fetching the latest papers…' : 'No papers yet. Tap refresh.'}</div>`;
    return;
  }
  box.className = `feed ${settings.layout === 'list' ? 'list' : ''}`;
  box.innerHTML = items.slice(0, homeShown).map(card).join('') +
    `<div class="footer" id="feedend">${homeEnd ? 'No older papers available' : `<span class="spinner"></span> Loading papers before ${dateText(items[Math.min(homeShown, items.length) - 1].published)}…`}</div>`;
  restoreScroll();
  feedObserver?.disconnect();
  feedObserver = new IntersectionObserver(async (entries) => {
    if (!entries[0].isIntersecting || homeEnd) return;
    feedObserver.disconnect();
    if (homeShown < items.length) {
      homeShown += 60;
    } else {
      const added = await loadOlder(homeFilter);
      if (added < 0) homeEnd = true;
      homeShown += 60;
    }
    if (currentPath === '') renderFeed();
  }, { rootMargin: '600px' });
  const end = document.getElementById('feedend');
  if (end) feedObserver.observe(end);
}

function viewSheet() {
  const d = sheet(`<h3>Text and layout</h3><div class="body">
    <div class="field"><label>Text size</label><input type="range" min="0.85" max="1.4" step="0.05" value="${settings.scale}" data-k="scale"></div>
    <div class="field"><label>Layout</label><div class="seg" data-seg="layout">
      <button data-v="cards" class="${settings.layout === 'cards' ? 'on' : ''}">Cards</button><button data-v="list" class="${settings.layout === 'list' ? 'on' : ''}">List</button></div></div>
    <div class="field"><label>Abstract preview</label><div class="seg" data-seg="preview">
      ${[0, 2, 4].map((n) => `<button data-v="${n}" class="${settings.preview === n ? 'on' : ''}">${n ? `${n} lines` : 'None'}</button>`).join('')}</div></div>
  </div><div class="actions"><button class="btn filled" data-x>Done</button></div>`, { onClose: () => { if (currentPath === '') renderFeed(); } });
  d.querySelector('[data-k="scale"]').oninput = (e) => saveSettings({ scale: Number(e.target.value) });
  d.querySelectorAll('[data-seg]').forEach((seg) => seg.querySelectorAll('button').forEach((b) => {
    b.onclick = () => {
      const k = seg.dataset.seg;
      saveSettings({ [k]: k === 'preview' ? Number(b.dataset.v) : b.dataset.v });
      seg.querySelectorAll('button').forEach((x) => x.classList.toggle('on', x === b));
    };
  }));
  d.querySelector('[data-x]').onclick = () => d.close();
}

// ---------------- Search ----------------

const MODES = [
  { id: 'meaning', label: 'Meaning', icon: 'sparkle', hint: 'Topic or keywords' },
  { id: 'words', label: 'Exact words', icon: 'textFields', hint: 'Words in the title or abstract' },
  { id: 'author', label: 'Author', icon: 'person', hint: 'Author name, e.g. L. M. Robledo' },
];
const S = { query: '', mode: 'meaning', newest: false, results: [], cursor: {}, qv: null, searched: null, loading: false, status: null, error: null, token: 0 };

function renderSearch() {
  const mode = MODES.find((m) => m.id === S.mode);
  main.innerHTML = `
    <div class="top">
      <div class="searchbar">
        <button class="icon-btn" data-act="back" title="Back">${icon('back')}</button>
        <input type="search" enterkeyhint="search" autocomplete="off" placeholder="${h(mode.hint)}" value="${h(S.query)}">
        <button class="icon-btn" data-act="clear" title="Clear" ${S.query ? '' : 'hidden'}>${icon('close')}</button>
      </div>
      <div class="chips" style="padding-left:0;padding-right:0">
        ${MODES.map((m) => `<button class="chip${S.mode === m.id ? ' on' : ''}" data-mode="${m.id}">${icon(m.icon)}${m.label}</button>`).join('')}
        <span class="chip-sep"></span>
        <button class="chip${!S.newest ? ' on' : ''}" data-newest="0">Best match</button>
        <button class="chip${S.newest ? ' on' : ''}" data-newest="1">Newest</button>
      </div>
    </div>
    <div id="sprog" class="progress" hidden></div>
    <div class="hint" id="shint"></div>
    <div id="sres"></div>`;
  const input = main.querySelector('input');
  input.oninput = () => { S.query = input.value; main.querySelector('[data-act="clear"]').hidden = !S.query; };
  input.onkeydown = (e) => { if (e.key === 'Enter') { input.blur(); runSearch(true); } };
  main.querySelector('[data-act="back"]').onclick = back;
  main.querySelector('[data-act="clear"]').onclick = () => { S.query = ''; input.value = ''; input.focus(); };
  main.querySelectorAll('[data-mode]').forEach((b) => { b.onclick = () => { S.mode = b.dataset.mode; renderSearch(); if (S.searched) runSearch(true); }; });
  main.querySelectorAll('[data-newest]').forEach((b) => { b.onclick = () => { S.newest = b.dataset.newest === '1'; renderSearch(); if (S.searched) runSearch(true); }; });
  if (!S.query) setTimeout(() => input.focus(), 50);
  if (S.mode === 'meaning') smart.warmUp();
  renderSearchResults();
  restoreScroll();
}

function searchHint() {
  const dl = smart.status;
  if (S.mode === 'meaning' && !dl.modelReady && dl.modelProgress > 0 && dl.modelProgress < 1) {
    return `Downloading the search model (34 MB, only the first time): ${Math.round(dl.modelProgress * 100)}%`;
  }
  if (S.status) return S.status;
  if (S.mode !== 'meaning') return 'Searches every year of the journals in your sources, plus arXiv.';
  const st = smart.status;
  if (st.error) return `Meaning search unavailable: ${st.error}`;
  if (st.pending > 0 && st.indexing) return `Finds papers on your topic, even without the exact words. Indexing ${st.pending} papers in this browser…`;
  return 'Finds papers on your topic, even without the exact words. Runs in this browser.';
}

function renderSearchResults() {
  const box = document.getElementById('sres');
  if (!box) return;
  document.getElementById('shint').textContent = searchHint();
  document.getElementById('sprog').hidden = !(S.loading && !S.results.length);
  const finished = S.cursor.crossrefDone && S.cursor.oaDone;
  if (!S.searched) { box.innerHTML = '<div class="empty">Type a topic, keywords or an author and press search.</div>'; return; }
  if (!S.results.length) {
    box.innerHTML = S.loading ? '' : S.error
      ? `<div class="empty">Search failed: ${h(S.error)}<br><button class="btn text" data-act="retry">Retry</button></div>`
      : `<div class="empty">No papers found for “${h(S.searched)}”.</div>`;
    box.querySelector('[data-act="retry"]')?.addEventListener('click', () => runSearch(true));
    return;
  }
  box.className = `feed ${settings.layout === 'list' ? 'list' : ''}`;
  box.innerHTML = S.results.map((r) => card(r.article)).join('') + `<div class="footer" id="send">${
    S.loading ? `<span class="spinner"></span> ${h(S.status || '')}`
      : S.error ? '<button class="btn text" data-act="more">Couldn\'t load more · Retry</button>'
        : finished ? 'End of results' : '<button class="btn text" data-act="more">Load more</button>'}</div>`;
  box.querySelector('[data-act="more"]')?.addEventListener('click', () => runSearch(false));
  const end = document.getElementById('send');
  const obs = new IntersectionObserver((en) => {
    if (en[0].isIntersecting && !S.loading && !S.error && !finished) { obs.disconnect(); runSearch(false); }
  }, { rootMargin: '400px' });
  if (!S.loading) obs.observe(end);
}

smart.onStatus(() => { if (currentPath === 'search') { const el = document.getElementById('shint'); if (el) el.textContent = searchHint(); } });

async function runSearch(reset) {
  const q = S.query.trim();
  if (q.length < 2) return;
  const finished = S.cursor.crossrefDone && S.cursor.oaDone;
  if (!reset && (S.loading || finished)) return;
  const token = ++S.token;
  const alive = () => token === S.token;
  S.loading = true; S.error = null;
  const start = reset ? {} : S.cursor;
  if (reset) { S.results = []; S.searched = q; S.qv = null; S.cursor = {}; window.scrollTo(0, 0); }
  const show = () => { if (alive() && currentPath === 'search') renderSearchResults(); };
  show();
  const sources = await db.all('sources');
  const keep = (list) => list.forEach((r) => { if (!r.inLibrary) transient.set(r.article.key, r.article); });
  try {
    if (S.mode === 'meaning') {
      if (reset) { smart.enable(); db.kvSet('meaning', true); setTimeout(() => smart.index(), 8000); }
      if (!S.qv) { S.status = 'Understanding your search…'; show(); S.qv = await smart.queryVector(q); }
      if (!alive()) return;
      if (reset) { S.results = order(await smart.searchLibrary(q, S.qv)); show(); }
      S.status = 'Searching journals and arXiv…'; show();
      const { items, cursor } = await net.searchArchive(q, 'any', false, sources, start);
      if (!alive()) return;
      S.cursor = cursor;
      const seen = new Set(S.results.map((r) => r.article.key));
      S.status = `Ranking ${items.length} papers by meaning…`; show();
      const online = await smart.rankOnline(q, S.qv, items.filter((a) => !seen.has(a.key)));
      if (!alive()) return;
      keep(online);
      S.results = reset ? order([...S.results, ...online].sort((a, b) => b.score - a.score)) : [...S.results, ...order(online)];
    } else {
      S.status = null;
      const { items, cursor } = await net.searchArchive(q, S.mode === 'author' ? 'author' : 'any', S.newest, sources, start);
      if (!alive()) return;
      S.cursor = cursor;
      const seen = new Set(S.results.map((r) => r.article.key));
      const add = items.filter((a) => !seen.has(a.key)).map((a) => ({ article: a, score: 0, inLibrary: false }));
      keep(add);
      S.results = [...S.results, ...add];
    }
  } catch (e) {
    if (alive()) S.error = e.message || 'Search failed';
  } finally {
    if (alive()) { S.loading = false; S.status = null; show(); }
  }
}

function order(list) { return S.newest ? [...list].sort((a, b) => b.article.published - a.article.published) : list; }

// ---------------- Article ----------------

async function getArticle(key) { return (await db.get('articles', key)) || transient.get(key) || null; }

async function renderArticle(key) {
  const a = await getArticle(key);
  if (!a) { main.innerHTML = `<div class="top"><div class="appbar"><button class="icon-btn" data-act="back">${icon('back')}</button></div></div><div class="empty">Paper not found.</div>`; main.querySelector('[data-act="back"]').onclick = back; return; }
  if (!a.read) await db.update('articles', key, (x) => ({ ...x, read: true }));
  const inReadLater = !!(await db.get('entries', [db.READ_LATER_ID, key]));
  const notes = (await db.byIndex('notes', 'articleKey', key)).sort((x, y) => y.updatedAt - x.updatedAt);
  const link = linkOf(a);
  const allAuthors = a.authors ? a.authors.split('\n').filter(Boolean) : [];
  main.innerHTML = `
    <div class="top"><div class="appbar">
      <button class="icon-btn" data-act="back" title="Back">${icon('back')}</button><h1></h1>
      ${link ? `<a class="icon-btn" href="${h(link)}" target="_blank" rel="noopener" title="Open at the publisher">${icon('open')}</a>` : ''}
      <button class="icon-btn" data-act="share" title="Share">${icon('share')}</button>
    </div></div>
    <div class="article">
      <div class="src">${h(a.journal)}${a.volume ? ` ${h(a.volume)}` : ''}${a.pages ? `, ${h(a.pages)}` : ''} · ${dateText(a.published)}</div>
      <h2>${h(a.title)}</h2>
      <div class="authors">${allAuthors.map((n) => `<a href="#/author/${encodeURIComponent(n)}">${h(n)}</a>`).join(', ')}</div>
      ${a.abstract ? `<div class="abstract">${h(a.abstract)}</div>`
        : '<div class="row-btns" style="padding-left:0"><button class="btn tonal" data-act="fetchabs">Find abstract</button></div>'}
      <div class="facts">${a.doi ? `DOI <a href="https://doi.org/${h(a.doi)}" target="_blank" rel="noopener">${h(a.doi)}</a>` : ''}</div>
    </div>
    <div class="actionbar">
      ${[1, 2, 3].map((i) => `<button class="icon-btn star-btn${i <= (a.rating || 0) ? ' on' : ''}" data-star="${i}" title="${i} star">${icon(i <= (a.rating || 0) ? 'star' : 'starBorder')}</button>`).join('')}
      <span class="grow"></span>
      <button class="icon-btn${inReadLater ? ' on' : ''}" data-act="later" title="Read later">${icon(inReadLater ? 'bookmark' : 'bookmarkBorder')}</button>
      <button class="icon-btn" data-act="lists" title="Save to lists">${icon('playlistAdd')}</button>
      <button class="icon-btn" data-act="cite" title="Copy citation">${icon('copy')}</button>
    </div>
    <div class="row-btns">
      ${a.doi ? '<button class="btn outlined" data-act="bibtex">Copy BibTeX</button>' : ''}
      <button class="btn outlined" data-act="addnote">${icon('note')}Add note</button>
    </div>
    ${notes.length ? '<div class="section-h">NOTES</div>' : ''}
    <div id="notes">${notes.map(noteHtml).join('')}</div>`;
  const $ = (s) => main.querySelector(s);
  $('[data-act="back"]').onclick = back;
  main.querySelectorAll('[data-star]').forEach((b) => {
    b.onclick = async () => {
      const i = Number(b.dataset.star);
      await db.update('articles', key, (x) => ({ ...x, rating: x.rating === i ? 0 : i }));
      renderArticle(key);
    };
  });
  $('[data-act="later"]').onclick = async () => {
    await toggleEntry(db.READ_LATER_ID, key);
    renderArticle(key);
  };
  $('[data-act="lists"]').onclick = () => listsSheet(key, () => renderArticle(key));
  $('[data-act="cite"]').onclick = () => copy(citation(a), 'Citation copied');
  $('[data-act="share"]').onclick = async () => {
    const data = { title: a.title, text: citation(a), url: link || undefined };
    if (navigator.share) { try { await navigator.share(data); } catch { /* cancelled */ } } else copy(citation(a), 'Citation copied');
  };
  $('[data-act="bibtex"]')?.addEventListener('click', async () => {
    try { await copy(await net.bibtex(a.doi), 'BibTeX copied'); } catch { snack('BibTeX not available for this DOI'); }
  });
  $('[data-act="fetchabs"]')?.addEventListener('click', async (e) => {
    e.target.disabled = true; e.target.textContent = 'Looking…';
    const abs = await net.findAbstract(a);
    if (abs) { await db.update('articles', key, (x) => ({ ...x, abstract: abs })); smart.index(); renderArticle(key); } else {
      snack('No abstract found. Opening the publisher page.');
      e.target.disabled = false; e.target.textContent = 'Find abstract';
      if (link) window.open(link, '_blank', 'noopener');
    }
  });
  $('[data-act="addnote"]').onclick = () => noteEditor(key, null, () => renderArticle(key));
  main.querySelectorAll('[data-note-edit]').forEach((b) => {
    b.onclick = async () => noteEditor(key, await db.get('notes', Number(b.dataset.noteEdit)), () => renderArticle(key));
  });
  main.querySelectorAll('[data-note-del]').forEach((b) => {
    b.onclick = async () => { if (await confirmBox('Delete note?', 'This can\'t be undone.')) { await db.del('notes', Number(b.dataset.noteDel)); renderArticle(key); } };
  });
  restoreScroll();
}

function noteHtml(n) {
  return `<div class="note"><div class="when"><span>${dateText(n.updatedAt)}</span>
    <button class="icon-btn" data-note-edit="${n.id}" title="Edit">${icon('edit')}</button>
    <button class="icon-btn" data-note-del="${n.id}" title="Delete">${icon('delete')}</button></div>${h(n.text)}</div>`;
}

function noteEditor(key, note, after) {
  const d = sheet(`<h3>${note ? 'Edit note' : 'New note'}</h3><div class="body" style="padding:0 16px">
    <textarea class="editor" rows="8" placeholder="Ideas, questions, what to check…">${h(note?.text || '')}</textarea></div>
    <div class="actions"><button class="btn text" data-x>Cancel</button><button class="btn filled" data-ok>Save</button></div>`);
  const ta = d.querySelector('textarea');
  setTimeout(() => ta.focus(), 50);
  d.querySelector('[data-x]').onclick = () => d.close();
  d.querySelector('[data-ok]').onclick = async () => {
    const text = ta.value.trim();
    if (text) {
      const now = Date.now();
      if (note) await db.put('notes', { ...note, text, updatedAt: now });
      else await db.add('notes', { articleKey: key, text, createdAt: now, updatedAt: now });
    }
    d.close();
    after();
  };
}

async function toggleEntry(listId, key) {
  const id = [listId, key];
  if (await db.get('entries', id)) await db.del('entries', id);
  else await db.put('entries', { listId, articleKey: key, addedAt: Date.now() });
}

async function listsSheet(key, after) {
  const lists = (await db.all('lists')).sort((a, b) => a.position - b.position);
  const inLists = new Set((await db.byIndex('entries', 'articleKey', key)).map((e) => e.listId));
  const d = sheet(`<h3>Save to lists</h3><div class="body">
    ${lists.map((l) => `<label class="check-row"><input type="checkbox" data-list="${l.id}" ${inLists.has(l.id) ? 'checked' : ''}>${h(l.name)}</label>`).join('')}
    <div class="field" style="display:flex;gap:8px;align-items:center"><input placeholder="New list" data-new><button class="icon-btn" data-add title="Create list">${icon('add')}</button></div>
  </div><div class="actions"><button class="btn filled" data-x>Done</button></div>`, { onClose: after });
  d.querySelectorAll('[data-list]').forEach((c) => { c.onchange = () => toggleEntry(Number(c.dataset.list), key); });
  const create = async () => {
    const name = d.querySelector('[data-new]').value.trim();
    if (!name) return;
    const id = await db.add('lists', { name, position: lists.length, createdAt: Date.now() });
    await db.put('entries', { listId: id, articleKey: key, addedAt: Date.now() });
    d.close();
    listsSheet(key, after);
  };
  d.querySelector('[data-add]').onclick = create;
  d.querySelector('[data-new]').onkeydown = (e) => { if (e.key === 'Enter') create(); };
  d.querySelector('[data-x]').onclick = () => d.close();
}

// ---------------- Author ----------------

const A = { name: null, items: [], cursor: null, done: false, loading: false, error: null };

async function renderAuthor(name) {
  if (A.name !== name) {
    Object.assign(A, { name, items: [], cursor: null, done: false, loading: false, error: null });
    const fam = familyName(name);
    const local = (await db.all('articles')).filter((a) => a.authors && a.authors.split('\n').some((n) => foldName(n) === foldName(name) || (familyName(n) === fam && n[0] === name[0])));
    A.items = local.sort((x, y) => y.published - x.published);
  }
  main.innerHTML = `<div class="top"><div class="appbar"><button class="icon-btn" data-act="back">${icon('back')}</button><h1>${h(name)}</h1></div></div>
    <div class="hint">Papers in your feeds plus the author's OpenAlex profile, newest first.</div><div id="ares"></div>`;
  main.querySelector('[data-act="back"]').onclick = back;
  drawAuthor();
  restoreScroll();
  if (!A.cursor && !A.done && !A.loading) moreAuthor();
}

function drawAuthor() {
  const box = document.getElementById('ares');
  if (!box || currentPath !== 'author') return;
  box.className = `feed ${settings.layout === 'list' ? 'list' : ''}`;
  box.innerHTML = A.items.map(card).join('') + `<div class="footer" id="aend">${A.loading ? '<span class="spinner"></span>'
    : A.error ? '<button class="btn text" data-act="more">Couldn\'t load · Retry</button>' : A.done ? (A.items.length ? 'End of list' : 'No papers found') : ''}</div>`;
  box.querySelector('[data-act="more"]')?.addEventListener('click', moreAuthor);
  const obs = new IntersectionObserver((en) => { if (en[0].isIntersecting && !A.loading && !A.done && !A.error) { obs.disconnect(); moreAuthor(); } }, { rootMargin: '400px' });
  obs.observe(document.getElementById('aend'));
}

async function moreAuthor() {
  const name = A.name;
  A.loading = true; A.error = null; drawAuthor();
  try {
    const { items, next } = await net.authorWorks(name, A.cursor);
    if (A.name !== name) return;
    const seen = new Set(A.items.map((a) => a.key));
    const add = items.filter((a) => !seen.has(a.key));
    add.forEach((a) => transient.set(a.key, a));
    A.items = [...A.items, ...add].sort((x, y) => y.published - x.published);
    A.cursor = next;
    A.done = !next || !items.length;
  } catch (e) {
    A.error = e.message;
  } finally {
    A.loading = false;
    drawAuthor();
  }
}

// ---------------- Lists & notes ----------------

async function renderLists() {
  const [lists, entries, notes] = await Promise.all([db.all('lists'), db.all('entries'), db.all('notes')]);
  lists.sort((a, b) => a.position - b.position);
  const count = (id) => entries.filter((e) => e.listId === id).length;
  main.innerHTML = `<div class="top"><div class="appbar"><h1 style="padding-left:12px">Lists</h1></div></div>
    <div class="group">
      ${lists.map((l) => `<a class="rowitem" href="#/list/${l.id}">${icon(l.id === db.READ_LATER_ID ? 'bookmark' : 'list')}<span class="grow">${h(l.name)}</span>${count(l.id) ? `<span class="badge">${count(l.id)}</span>` : ''}</a>`).join('')}
      <a class="rowitem" href="#/notes">${icon('note')}<span class="grow">Notes</span>${notes.length ? `<span class="badge">${notes.length}</span>` : ''}</a>
    </div>
    <button class="fab" data-act="new">${icon('add')}New list</button>`;
  main.querySelector('[data-act="new"]').onclick = async () => {
    const name = await ask({ title: 'New list', placeholder: 'List name', confirm: 'Create' });
    if (name) { await db.add('lists', { name, position: lists.length, createdAt: Date.now() }); renderLists(); }
  };
}

let listSort = 'date';
async function renderList(id) {
  const list = await db.get('lists', id);
  if (!list) { location.hash = '#/lists'; return; }
  const entries = await db.byIndex('entries', 'listId', id);
  const added = new Map(entries.map((e) => [e.articleKey, e.addedAt]));
  const items = (await Promise.all(entries.map((e) => db.get('articles', e.articleKey)))).filter(Boolean);
  items.sort(listSort === 'rating' ? (a, b) => (b.rating || 0) - (a.rating || 0) || b.published - a.published
    : listSort === 'added' ? (a, b) => added.get(b.key) - added.get(a.key) : (a, b) => b.published - a.published);
  main.innerHTML = `<div class="top"><div class="appbar"><button class="icon-btn" data-act="back">${icon('back')}</button><h1>${h(list.name)}</h1>
      <button class="icon-btn" data-act="rename" title="Rename">${icon('edit')}</button>
      ${id !== db.READ_LATER_ID ? `<button class="icon-btn" data-act="delete" title="Delete list">${icon('delete')}</button>` : ''}</div>
    <div class="chips" style="padding-left:0">${[['date', 'Newest'], ['added', 'Recently added'], ['rating', 'Rating']].map(([k, l]) => `<button class="chip${listSort === k ? ' on' : ''}" data-sort="${k}">${l}</button>`).join('')}</div></div>
    <div class="feed ${settings.layout === 'list' ? 'list' : ''}">${items.length ? items.map(card).join('') : '<div class="empty">No papers in this list yet.</div>'}</div>`;
  main.querySelector('[data-act="back"]').onclick = back;
  main.querySelectorAll('[data-sort]').forEach((b) => { b.onclick = () => { listSort = b.dataset.sort; renderList(id); }; });
  main.querySelector('[data-act="rename"]').onclick = async () => {
    const name = await ask({ title: 'Rename list', value: list.name });
    if (name) { await db.put('lists', { ...list, name }); renderList(id); }
  };
  main.querySelector('[data-act="delete"]')?.addEventListener('click', async () => {
    if (!(await confirmBox(`Delete “${list.name}”?`, 'The papers stay in the app; only the list goes.'))) return;
    await db.del('lists', id);
    await Promise.all(entries.map((e) => db.del('entries', [id, e.articleKey])));
    location.hash = '#/lists';
  });
  restoreScroll();
}

async function renderNotes() {
  const notes = (await db.all('notes')).sort((a, b) => b.updatedAt - a.updatedAt);
  const papers = new Map();
  for (const n of notes) if (!papers.has(n.articleKey)) papers.set(n.articleKey, await db.get('articles', n.articleKey));
  main.innerHTML = `<div class="top"><div class="appbar"><button class="icon-btn" data-act="back">${icon('back')}</button><h1>Notes</h1></div></div>
    ${notes.length ? notes.map((n) => {
    const a = papers.get(n.articleKey);
    return `<div class="note" data-open="${h(n.articleKey)}" style="cursor:pointer"><div class="when"><span>${dateText(n.updatedAt)}</span></div>${h(n.text)}
        ${a ? `<div class="sub" style="margin-top:8px;font-size:.8125rem;color:var(--primary)">${h(a.title)}${a.year ? ` · ${a.year}` : ''}</div>` : ''}</div>`;
  }).join('') : '<div class="empty">No notes yet. Open a paper and tap “Add note”.</div>'}`;
  main.querySelector('[data-act="back"]').onclick = back;
}

// ---------------- Sources ----------------

const TYPES = {
  CROSSREF: { label: 'Journal (ISSN, via Crossref)', hint: 'ISSN, e.g. 2469-9985' },
  ARXIV: { label: 'arXiv (via OpenAlex)', hint: 'Keywords, or subfield:3106 for nuclear & HEP' },
  OPENALEX: { label: 'OpenAlex keyword search', hint: 'Keywords, e.g. neutron star mergers' },
};

async function renderSources() {
  const sources = await db.all('sources');
  main.innerHTML = `<div class="top"><div class="appbar"><h1 style="padding-left:12px">Sources</h1>
      <button class="icon-btn" data-act="refresh" title="Refresh all">${icon('refresh')}</button></div></div>
    <div class="group">${sources.map((s) => `<div class="rowitem">
      <span class="grow"><div>${h(s.name)}</div><div class="sub${s.lastError ? ' err' : ''}">${h(s.lastError ? `Error: ${s.lastError}` : `${TYPES[s.type]?.label || s.type} · ${s.query}`)}</div></span>
      <button class="icon-btn" data-del="${s.id}" title="Remove">${icon('delete')}</button>
      <label class="switch"><input type="checkbox" data-en="${s.id}" ${s.enabled ? 'checked' : ''}><span></span></label></div>`).join('')}</div>
    <div class="row-btns"><button class="btn text" data-act="defaults">Restore default sources</button></div>
    <div class="hint">Browsers can't read arXiv or journal RSS feeds directly, so the web app uses Crossref and OpenAlex. They carry the same papers; arXiv preprints appear 1–2 days later than on arXiv itself.</div>
    <button class="fab" data-act="add">${icon('add')}Add source</button>`;
  main.querySelector('[data-act="refresh"]').onclick = () => refreshAll();
  main.querySelectorAll('[data-en]').forEach((c) => {
    c.onchange = async () => { await db.update('sources', Number(c.dataset.en), (s) => ({ ...s, enabled: c.checked })); exhausted.clear(); };
  });
  main.querySelectorAll('[data-del]').forEach((b) => {
    b.onclick = async () => {
      const s = sources.find((x) => x.id === Number(b.dataset.del));
      if (!(await confirmBox(`Remove ${s.name}?`, 'Its papers that you haven\'t rated, listed or noted are removed too.', 'Remove'))) return;
      await db.del('sources', s.id);
      const [articles, entries, notes] = await Promise.all([db.all('articles'), db.all('entries'), db.all('notes')]);
      const kept = new Set([...entries.map((e) => e.articleKey), ...notes.map((n) => n.articleKey)]);
      const drop = articles.filter((a) => a.sourceId === s.id && !a.rating && !kept.has(a.key));
      await Promise.all(drop.flatMap((a) => [db.del('articles', a.key), db.del('vectors', a.key)]));
      smart.forget(drop.map((a) => a.key));
      renderSources();
    };
  });
  main.querySelector('[data-act="defaults"]').onclick = async () => {
    const have = new Set(sources.map((s) => `${s.type}|${s.query}`));
    const add = db.DEFAULT_SOURCES.filter((s) => !have.has(`${s.type}|${s.query}`));
    for (const s of add) await db.add('sources', { ...s, enabled: true, lastFetched: 0, lastError: null });
    snack(add.length ? `Added ${add.length} source${add.length === 1 ? '' : 's'}` : 'All default sources are already there');
    renderSources();
    if (add.length) refreshAll();
  };
  main.querySelector('[data-act="add"]').onclick = addSourceSheet;
}

function addSourceSheet() {
  const d = sheet(`<h3>Add source</h3><div class="body">
    <div class="field"><label>Type</label><select data-type>${Object.entries(TYPES).map(([k, t]) => `<option value="${k}">${t.label}</option>`).join('')}</select></div>
    <div class="field"><label data-qlabel>${TYPES.CROSSREF.hint}</label><input data-q></div>
    <div class="field"><label>Name (optional)</label><input data-name placeholder="Looked up from the ISSN if empty"></div>
  </div><div class="actions"><button class="btn text" data-x>Cancel</button><button class="btn filled" data-ok>Add</button></div>`);
  const type = d.querySelector('[data-type]');
  type.onchange = () => { d.querySelector('[data-qlabel]').textContent = TYPES[type.value].hint; };
  d.querySelector('[data-x]').onclick = () => d.close();
  d.querySelector('[data-ok]').onclick = async (e) => {
    const query = d.querySelector('[data-q]').value.trim();
    if (!query) return;
    let name = d.querySelector('[data-name]').value.trim();
    e.target.disabled = true;
    if (!name && type.value === 'CROSSREF') {
      try { name = await net.journalName(query); } catch { snack('No journal found for that ISSN'); e.target.disabled = false; return; }
    }
    const id = await db.add('sources', { name: name || query, type: type.value, query, enabled: true, lastFetched: 0, lastError: null });
    d.close();
    renderSources();
    refreshAll(id);
  };
}

// ---------------- Settings & backup ----------------

async function renderSettings() {
  const vecCount = (await db.keys('vectors')).length;
  const notifState = 'Notification' in window ? Notification.permission : 'unsupported';
  main.innerHTML = `<div class="top"><div class="appbar"><h1 style="padding-left:12px">Settings</h1></div></div>
    <div class="group-h">Appearance</div>
    <div class="group">
      <div class="field"><label>Theme</label><div class="seg" data-seg="mode">
        ${[['system', 'System'], ['light', 'Light'], ['dark', 'Dark']].map(([k, l]) => `<button data-v="${k}" class="${settings.mode === k ? 'on' : ''}">${l}</button>`).join('')}</div></div>
      <div class="field"><label>Colour</label></div>
      <div class="swatches">${HUES.map((hu) => `<button class="swatch${settings.hue === hu ? ' on' : ''}" style="--h:${hu}" data-hue="${hu}" title="Colour">${settings.hue === hu ? icon('check') : ''}</button>`).join('')}</div>
      <button class="rowitem" data-act="view">${icon('textFields')}<span class="grow">Text size and layout</span></button>
    </div>
    <div class="group-h">Updates</div>
    <div class="group">
      <div class="field"><label>Refresh while the app is open</label><select data-k="refreshMinutes">
        ${[[0, 'Only when I tap refresh'], [30, 'Every 30 minutes'], [60, 'Every hour'], [180, 'Every 3 hours']].map(([v, l]) => `<option value="${v}" ${settings.refreshMinutes === v ? 'selected' : ''}>${l}</option>`).join('')}</select></div>
      <label class="rowitem"><span class="grow"><div>Notify about new papers</div><div class="sub">${notifState === 'unsupported' ? 'Not supported here. On iPhone, add PaperFeed to the Home Screen first.' : 'Shown when a refresh finds new papers while the app is in the background.'}</div></span>
        <span class="switch"><input type="checkbox" data-act="notify" ${settings.notify && notifState === 'granted' ? 'checked' : ''} ${notifState === 'unsupported' ? 'disabled' : ''}><span></span></span></label>
    </div>
    <div class="group-h">Meaning search</div>
    <div class="group">
      <div class="rowitem"><span class="grow"><div>Runs in this browser</div><div class="sub" id="mstat">${vecCount} papers indexed${smart.status.pending ? ` · ${smart.status.pending} waiting` : ''}. The model (34 MB) downloads once and works offline.</div></span></div>
    </div>
    <div class="group-h">Services</div>
    <div class="group">
      <div class="field"><label>Contact email for Crossref / OpenAlex (optional, faster service)</label><input type="email" data-k="email" value="${h(settings.email)}" placeholder="you@example.com"></div>
      <div class="field"><label>OpenAlex API key (optional, free at openalex.org)</label><input data-k="openAlexKey" value="${h(settings.openAlexKey)}" placeholder="Raises the daily request limit"></div>
      <div class="hint">Stored only in this browser. Requests go straight from your device to Crossref and OpenAlex.</div>
    </div>
    <div class="group-h">Backup</div>
    <div class="group">
      <button class="rowitem" data-act="export">${icon('download')}<span class="grow"><div>Export backup</div><div class="sub">Lists, ratings, notes and sources as a JSON file (the Android app's format)</div></span></button>
      <button class="rowitem" data-act="import">${icon('upload')}<span class="grow"><div>Import backup</div><div class="sub">From this web app or the PaperFeed Android app</div></span></button>
      <input type="file" accept="application/json,.json" hidden data-file>
    </div>
    <div class="hint" style="padding-bottom:24px">PaperFeed web · data stays on this device · <a href="https://github.com/Amrit-PTU/PaperFeed" target="_blank" rel="noopener">source code</a></div>`;
  main.querySelectorAll('[data-seg="mode"] button').forEach((b) => { b.onclick = async () => { await saveSettings({ mode: b.dataset.v }); renderSettings(); }; });
  main.querySelectorAll('[data-hue]').forEach((b) => { b.onclick = async () => { await saveSettings({ hue: Number(b.dataset.hue) }); renderSettings(); }; });
  main.querySelector('[data-act="view"]').onclick = viewSheet;
  main.querySelectorAll('[data-k]').forEach((el) => {
    el.onchange = async () => {
      const v = el.dataset.k === 'refreshMinutes' ? Number(el.value) : el.value.trim();
      await saveSettings({ [el.dataset.k]: v });
      if (el.dataset.k === 'refreshMinutes') scheduleRefresh();
    };
  });
  main.querySelector('[data-act="notify"]').onchange = async (e) => {
    if (e.target.checked) {
      const p = await Notification.requestPermission();
      if (p !== 'granted') { e.target.checked = false; snack('Notifications are blocked for this site'); return; }
    }
    await saveSettings({ notify: e.target.checked });
  };
  main.querySelector('[data-act="export"]').onclick = exportBackup;
  const file = main.querySelector('[data-file]');
  main.querySelector('[data-act="import"]').onclick = () => file.click();
  file.onchange = async () => {
    const f = file.files[0];
    if (!f) return;
    try { await importBackup(JSON.parse(await f.text())); } catch (err) { snack(`Import failed: ${err.message}`); }
    file.value = '';
  };
}

// Android sources that browsers can't read directly, mapped to their Crossref/OpenAlex equivalent.
const RSS_TO_ISSN = {
  'feeds.aps.org/rss/recent/prc.xml': '2469-9985', 'feeds.aps.org/rss/recent/rmp.xml': '0034-6861',
  'feeds.aps.org/rss/recent/prl.xml': '0031-9007', 'iopscience.iop.org/journal/rss/1674-1137': '1674-1137',
  'iopscience.iop.org/journal/rss/0954-3899': '0954-3899',
};
const ANDROID_ARXIV = 'cat:nucl-th OR cat:nucl-ex';

function toWebSource(s) {
  if (s.type === 'CROSSREF' || s.type === 'OPENALEX') return { name: s.name, type: s.type, query: s.query };
  if (s.type === 'RSS') {
    const k = Object.keys(RSS_TO_ISSN).find((u) => s.query.includes(u));
    return k ? { name: s.name, type: 'CROSSREF', query: RSS_TO_ISSN[k] } : null;
  }
  if (s.type === 'ARXIV') {
    if (/nucl-|hep-/.test(s.query)) return { name: s.name, type: 'ARXIV', query: 'subfield:3106' };
    const words = s.query.replace(/\b(cat|ti|au|abs|all):\S+/g, ' ').replace(/\b(AND|OR|ANDNOT)\b/g, ' ').trim();
    return { name: s.name, type: 'ARXIV', query: words || 'subfield:3106' };
  }
  return null; // Google Scholar can't be read from a browser
}

function toAndroidSource(s) {
  if (s.type === 'ARXIV') return { ...s, query: s.query === 'subfield:3106' ? ANDROID_ARXIV : `all:${s.query}` };
  return s;
}

async function exportBackup() {
  const [articles, lists, entries, sources, notes] = await Promise.all([db.all('articles'), db.all('lists'), db.all('entries'), db.all('sources'), db.all('notes')]);
  const kept = new Set([...entries.map((e) => e.articleKey), ...notes.map((n) => n.articleKey)]);
  const data = {
    app: 'PaperFeed', version: 1, createdAt: Date.now(), from: 'web',
    articles: articles.filter((a) => a.rating > 0 || kept.has(a.key)).map((a) => ({
      key: a.key, title: a.title, authors: a.authors, journal: a.journal, sourceId: a.sourceId, doi: a.doi, url: a.url,
      abstract: a.abstract, published: a.published, year: a.year, volume: a.volume, issue: a.issue, pages: a.pages,
      fetchedAt: a.fetchedAt, rating: a.rating || 0, read: !!a.read,
    })),
    lists: lists.map((l) => ({ id: l.id, name: l.name, position: l.position, notificationAction: false, createdAt: l.createdAt })),
    entries,
    sources: sources.map((s) => { const x = toAndroidSource(s); return { id: x.id, name: x.name, type: x.type, query: x.query, enabled: x.enabled, notify: true }; }),
    notes,
    settings: { themeId: 'dynamic', contactEmail: settings.email, feedStyle: settings.layout === 'list' ? 'LIST' : 'CARDS', previewLines: settings.preview },
  };
  const blob = new Blob([JSON.stringify(data, null, 1)], { type: 'application/json' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = 'PaperFeed-backup.json';
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 5000);
}

async function importBackup(o) {
  if (o.app !== 'PaperFeed') throw new Error('Not a PaperFeed backup');
  // Sources: add the ones we don't have, remembering old id -> new id for the articles.
  const existing = await db.all('sources');
  const idMap = new Map();
  for (const s of o.sources || []) {
    const w = toWebSource(s);
    if (!w) continue;
    const same = existing.find((e) => e.type === w.type && e.query === w.query);
    if (same) { idMap.set(s.id, same.id); continue; }
    const id = await db.add('sources', { ...w, enabled: s.enabled !== false, lastFetched: 0, lastError: null });
    existing.push({ ...w, id });
    idMap.set(s.id, id);
  }
  // Lists: merge by name.
  const lists = await db.all('lists');
  const listMap = new Map();
  for (const l of o.lists || []) {
    const same = l.id === db.READ_LATER_ID ? lists.find((x) => x.id === db.READ_LATER_ID) : lists.find((x) => x.name === l.name);
    if (same) { listMap.set(l.id, same.id); continue; }
    const id = await db.add('lists', { name: l.name, position: lists.length, createdAt: l.createdAt || Date.now() });
    lists.push({ id, name: l.name });
    listMap.set(l.id, id);
  }
  const articles = (o.articles || []).map((a) => ({ ...a, sourceId: idMap.get(a.sourceId) ?? -1, rating: a.rating || 0, read: !!a.read, abstractTries: a.abstract ? 0 : 5 }));
  for (const a of articles) {
    const cur = await db.get('articles', a.key);
    await db.put('articles', cur ? { ...cur, rating: Math.max(cur.rating || 0, a.rating), abstract: cur.abstract || a.abstract } : a);
  }
  await db.putMany('entries', (o.entries || []).filter((e) => listMap.has(e.listId)).map((e) => ({ listId: listMap.get(e.listId), articleKey: e.articleKey, addedAt: e.addedAt || Date.now() })));
  const notes = await db.all('notes');
  for (const n of o.notes || []) {
    if (notes.some((x) => x.articleKey === n.articleKey && x.text === n.text)) continue;
    await db.add('notes', { articleKey: n.articleKey, text: n.text, createdAt: n.createdAt || Date.now(), updatedAt: n.updatedAt || Date.now() });
  }
  if (o.settings?.contactEmail && !settings.email) await saveSettings({ email: o.settings.contactEmail });
  snack(`Imported ${articles.length} papers, ${(o.entries || []).length} list entries and ${(o.notes || []).length} notes`);
  exhausted.clear();
  smart.index();
  render();
}

// ---------------- Router ----------------

function saveScroll() { if (currentPath != null) scrollPos.set(location.hash || '#/', window.scrollY); }
function restoreScroll() {
  const y = scrollPos.get(location.hash || '#/') || 0;
  requestAnimationFrame(() => window.scrollTo(0, y));
}

function setNav(path) {
  const tab = ['', 'search', 'article', 'author'].includes(path) ? '' : ['lists', 'list', 'notes'].includes(path) ? 'lists' : path;
  document.querySelectorAll('.navbar a, .rail a').forEach((a) => a.classList.toggle('on', a.dataset.tab === tab));
}

async function render() {
  const [, path = '', ...rest] = (location.hash || '#/').split('/');
  const arg = rest.length ? decodeURIComponent(rest.join('/')) : null;
  currentPath = path;
  setNav(path);
  feedObserver?.disconnect();
  switch (path) {
    case '': return renderHome();
    case 'search': return renderSearch();
    case 'article': return renderArticle(arg);
    case 'author': return renderAuthor(arg);
    case 'lists': return renderLists();
    case 'list': return renderList(Number(arg));
    case 'notes': return renderNotes();
    case 'sources': return renderSources();
    case 'settings': return renderSettings();
    default: location.hash = '#/';
  }
}

let lastHash = location.hash;
window.addEventListener('hashchange', () => {
  scrollPos.set(lastHash || '#/', window.scrollY);
  lastHash = location.hash;
  render();
});
window.addEventListener('beforeunload', saveScroll);

function buildNav() {
  const items = [['', 'home', 'Home'], ['lists', 'list', 'Lists'], ['sources', 'rss', 'Sources'], ['settings', 'settings', 'Settings']];
  const html = items.map(([t, i, l]) => `<a href="#/${t}" data-tab="${t}"><span class="pill">${icon(i)}</span>${l}</a>`).join('');
  document.querySelector('.navbar').innerHTML = html;
  document.querySelector('.rail').innerHTML = html;
  // Tabs reset the in-app back stack, like a bottom bar should.
  document.querySelectorAll('.navbar a, .rail a').forEach((a) => a.addEventListener('click', () => { navDepth = 0; }));
}

let refreshTimer = null;
function scheduleRefresh() {
  clearInterval(refreshTimer);
  if (settings.refreshMinutes > 0) refreshTimer = setInterval(() => refreshAll(), settings.refreshMinutes * 60000);
}

async function start() {
  await db.open();
  await loadSettings();
  if (await db.kvGet('meaning', false)) smart.enable();
  buildNav();
  render();
  scheduleRefresh();
  navigator.storage?.persist?.();
  if ('serviceWorker' in navigator && location.protocol !== 'file:') navigator.serviceWorker.register('sw.js').catch(() => {});
  // Old feed items nobody kept are cleared after four months.
  db.prune(Date.now() - 120 * 86400000).then((n) => { if (n) smart.refreshPendingCount(); });
  setTimeout(() => smart.index(), 4000);
}

start();
