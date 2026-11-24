// Text cleanup shared by the fetchers (port of the Android app's net/Text.kt).

const SUP = { 0: '⁰', 1: '¹', 2: '²', 3: '³', 4: '⁴', 5: '⁵', 6: '⁶', 7: '⁷', 8: '⁸', 9: '⁹', '+': '⁺', '-': '⁻', '−': '⁻', '=': '⁼', '(': '⁽', ')': '⁾', n: 'ⁿ', i: 'ⁱ', '*': '*' };
const SUB = { 0: '₀', 1: '₁', 2: '₂', 3: '₃', 4: '₄', 5: '₅', 6: '₆', 7: '₇', 8: '₈', 9: '₉', '+': '₊', '-': '₋', '−': '₋', '=': '₌', '(': '₍', ')': '₎' };

const TEX = {
  alpha: 'α', beta: 'β', gamma: 'γ', delta: 'δ', epsilon: 'ε', varepsilon: 'ε', zeta: 'ζ', eta: 'η', theta: 'θ',
  kappa: 'κ', lambda: 'λ', mu: 'μ', nu: 'ν', xi: 'ξ', pi: 'π', rho: 'ρ', sigma: 'σ', tau: 'τ', phi: 'φ', varphi: 'φ',
  chi: 'χ', psi: 'ψ', omega: 'ω', Gamma: 'Γ', Delta: 'Δ', Theta: 'Θ', Lambda: 'Λ', Xi: 'Ξ', Pi: 'Π', Sigma: 'Σ',
  Phi: 'Φ', Psi: 'Ψ', Omega: 'Ω', hbar: 'ħ', ell: 'ℓ', pm: '±', mp: '∓', times: '×', cdot: '·', approx: '≈',
  sim: '∼', simeq: '≃', le: '≤', leq: '≤', ge: '≥', geq: '≥', neq: '≠', ne: '≠', infty: '∞', to: '→',
  rightarrow: '→', leftarrow: '←', leftrightarrow: '↔', partial: '∂', nabla: '∇', sqrt: '√', propto: '∝',
  langle: '⟨', rangle: '⟩', gg: '≫', ll: '≪', prime: '′', circ: '°', odot: '⊙', dagger: '†', uparrow: '↑',
  downarrow: '↓', quad: ' ', left: '', right: '', bar: '', overline: '', tilde: '', hat: '', mathrm: '', text: '',
};

/** Decodes HTML entities and strips tags, keeping <br>/<p> as line breaks. */
function htmlToText(html) {
  const doc = new DOMParser().parseFromString(`<body>${html}</body>`, 'text/html');
  doc.querySelectorAll('br').forEach((b) => b.replaceWith('\n'));
  doc.querySelectorAll('p').forEach((p) => p.append('\n'));
  return doc.body.textContent || '';
}

function mapScript(inner, table, mark) {
  const plain = htmlToText(inner).trim();
  if ([...plain].every((c) => c in table)) return [...plain].map((c) => table[c]).join('');
  return mark + plain;
}

function script(body, table, mark) {
  if ([...body].every((c) => c in table)) return [...body].map((c) => table[c]).join('');
  return body.length === 1 ? mark + body : `${mark}(${body})`;
}

/** Turns simple inline TeX such as $^{208}\mathrm{Pb}$ or $\alpha$ into readable Unicode. */
export function latexToUnicode(text) {
  const plain = text.replace(/\\(texttt|textit|textbf|textrm|textsc|emph|mathrm|mathit|mathbf)\s*\{([^{}$]*)\}/g, '$2');
  if (!plain.includes('$')) return plain;
  return plain.replace(/\$([^$]{1,400})\$/g, (_, inner) => {
    let t = inner.replace(/\\(mathrm|text|textrm|mathit|mathbf|rm|mathcal|boldsymbol|operatorname)\s*\{([^{}]*)\}/g, '$2');
    t = t.replace(/\\([A-Za-z]+)/g, (m, c) => (c in TEX ? TEX[c] : c));
    t = t.replace(/\^\{([^{}]*)\}/g, (m, b) => script(b, SUP, '^'));
    t = t.replace(/\^(.)/gu, (m, b) => script(b, SUP, '^'));
    t = t.replace(/_\{([^{}]*)\}/g, (m, b) => script(b, SUB, '_'));
    t = t.replace(/_(.)/gu, (m, b) => script(b, SUB, '_'));
    return t.replace(/[{}]/g, '').replace(/\\,/g, ' ').replace(/~/g, ' ');
  });
}

/** Converts HTML / JATS markup to readable plain text, keeping paragraph breaks. */
export function clean(raw) {
  if (!raw || !String(raw).trim()) return null;
  let s = String(raw)
    .replace(/<(jats:)?sup>([\s\S]*?)<\/(jats:)?sup>/g, (m, a, inner) => mapScript(inner, SUP, '^'))
    .replace(/<(jats:)?(sub|inf)>([\s\S]*?)<\/(jats:)?(sub|inf)>/g, (m, a, b, inner) => mapScript(inner, SUB, '_'))
    .replace(/<jats:title>[\s\S]*?<\/jats:title>/g, '')
    .replace(/<\/(jats:)?p>|<br\s*\/?>/gi, '\n')
    .replace(/<\/?jats:[^>]*>/g, '')
    .replace(/<math[\s\S]*?<\/math>/g, '');
  for (let pass = 0; pass < 2; pass++) {
    if (!/<[a-zA-Z/!]|&[a-zA-Z#0-9]+;/.test(s)) break;
    s = htmlToText(s.replace(/\n/g, '<br>'));
  }
  s = latexToUnicode(s)
    .replace(/[ \t\v\f\r ]+/g, ' ')
    .replace(/ *\n */g, '\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
  return s || null;
}

export function cleanTitle(raw) {
  return (clean(raw) || '').replace(/\s+/g, ' ').trim();
}

export function normalizeDoi(doi) {
  return doi.trim().replace(/^doi:/, '').replace(/^https?:\/\/(dx\.)?doi\.org\//i, '').replace(/[.,;)]+$/, '').toLowerCase();
}

export function keyFor(doi, url, title) {
  if (doi) return `doi:${normalizeDoi(doi)}`;
  if (url) return `url:${url.split('?dgcid=')[0].split('?af=')[0]}`;
  return `title:${title.toLowerCase().replace(/[^\p{L}\p{N}]/gu, '')}`;
}

export function foldName(name) {
  return name.normalize('NFD').replace(/\p{M}+/gu, '').toLowerCase().replace(/[^a-z ]/g, ' ').replace(/\s+/g, ' ').trim();
}

export function familyName(name) {
  const parts = foldName(name).split(' ');
  return parts[parts.length - 1] || '';
}

export function parseDate(raw) {
  if (!raw) return null;
  const t = Date.parse(raw);
  if (!Number.isNaN(t)) return t;
  const d = Date.parse(String(raw).slice(0, 10));
  return Number.isNaN(d) ? null : d;
}

export const yearOf = (ms) => new Date(ms).getUTCFullYear();

export function escapeHtml(s) {
  return String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

export function dateText(ms) {
  return new Date(ms).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' });
}

export function citation(a) {
  const names = a.authors ? a.authors.split('\n').filter(Boolean) : [];
  let s = names.length === 0 ? '' : names.length > 3 ? `${names[0]} et al.` : names.join(', ');
  if (s) s += ', ';
  s += `"${a.title}", ${a.journal}`;
  if (a.volume) s += ` ${a.volume}`;
  if (a.pages) s += `, ${a.pages}`;
  if (a.year) s += ` (${a.year})`;
  if (a.doi) s += `. https://doi.org/${a.doi}`;
  return s;
}

export function linkOf(a) {
  return a.doi ? `https://doi.org/${a.doi}` : a.url || null;
}
