'use strict';

const $ = (s, el = document) => el.querySelector(s);

const ICONS = {
  folder: '<path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/>',
  file: '<path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5"/>',
  image: '<rect x="3" y="4" width="18" height="16" rx="2"/><circle cx="9" cy="10" r="1.5"/><path d="m21 16-5-5L5 20"/>',
  film: '<rect x="3" y="4" width="18" height="16" rx="2"/><path d="m10 9 5 3-5 3z"/>',
  music: '<path d="M9 18V5l11-2v13"/><circle cx="6.5" cy="18" r="2.5"/><circle cx="17.5" cy="16" r="2.5"/>',
  play: '<path d="M8 5v14l11-7z"/>',
  search: '<circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/>',
  upload: '<path d="M12 16V4"/><path d="m7 9 5-5 5 5"/><path d="M4 16v3a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-3"/>',
  download: '<path d="M12 4v12"/><path d="m7 11 5 5 5-5"/><path d="M4 20h16"/>',
  folderPlus: '<path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><path d="M12 10v6M9 13h6"/>',
  grid: '<rect x="4" y="4" width="6.5" height="6.5" rx="1"/><rect x="13.5" y="4" width="6.5" height="6.5" rx="1"/><rect x="4" y="13.5" width="6.5" height="6.5" rx="1"/><rect x="13.5" y="13.5" width="6.5" height="6.5" rx="1"/>',
  list: '<path d="M8 6h12M8 12h12M8 18h12M4 6h.01M4 12h.01M4 18h.01"/>',
  x: '<path d="M6 6l12 12M18 6 6 18"/>',
  check: '<path d="m5 12 5 5 9-10"/>',
  left: '<path d="m15 5-7 7 7 7"/>',
  right: '<path d="m9 5 7 7-7 7"/>',
  maximize: '<path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5"/>',
  minimize: '<path d="M9 4v5H4M15 4v5h5M9 20v-5H4M15 20v-5h5"/>',
  logout: '<path d="M10 4H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h4"/><path d="M15 8l4 4-4 4M19 12H9"/>',
  home: '<path d="m4 11 8-7 8 7v8a1 1 0 0 1-1 1h-4v-6h-6v6H5a1 1 0 0 1-1-1z"/>',
};
const icon = n => `<svg viewBox="0 0 24 24" aria-hidden="true">${ICONS[n]}</svg>`;
document.querySelectorAll('i[data-icon]').forEach(i => { i.innerHTML = icon(i.dataset.icon); });

// ---- preferences ---------------------------------------------------------

const prefs = { view: 'grid', sort: 'name', tile: 180, tq: 'src' };
try { Object.assign(prefs, JSON.parse(localStorage.getItem('shelf') || '{}')); } catch {}
const savePrefs = () => { try { localStorage.setItem('shelf', JSON.stringify(prefs)); } catch {} };

// ---- state ---------------------------------------------------------------

const state = {
  path: '/',
  entries: [],     // raw listing of the current folder
  view: [],        // what is on screen, sorted (folder or search results)
  search: null,    // { q, entries, more } while searching
  sel: new Set(),  // selected full paths
  anchor: -1,      // last clicked index, for shift-range
  clip: null,      // { paths, copy }
  readonly: false,
};
const listCache = new Map();  // path -> entries
const scrollPos = new Map();  // path -> scrollY
let loadSeq = 0;

const itemsEl = $('#items');

// ---- helpers -------------------------------------------------------------

const esc = s => String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const enc = p => p.split('/').map(encodeURIComponent).join('/');
const join = (dir, name) => (dir === '/' ? '' : dir) + '/' + name;
const parentOf = p => p.slice(0, p.lastIndexOf('/')) || '/';
const baseOf = p => p.slice(p.lastIndexOf('/') + 1);
const pathOf = e => e.p || join(state.path, e.n);
const fileURL = p => '/files' + enc(p);
const thumbURL = (e, p, large) => `/api/thumb?p=${encodeURIComponent(p)}&v=${e.m}-${e.s}${large ? '&s=l' : ''}`;
const extOf = n => { const i = n.lastIndexOf('.'); return i > 0 ? n.slice(i + 1).toLowerCase() : ''; };

const DIRECT = new Set(['svg', 'ico']);                       // shown as-is, no server thumbnail
const NATIVE = new Set(['jpg', 'jpeg', 'png', 'gif', 'webp', 'avif', 'bmp', 'svg', 'ico']); // browsers can display these
const hasThumb = e => (e.k === 'i' && !DIRECT.has(extOf(e.n))) || e.k === 'v';

function fmtSize(n) {
  if (n < 1000) return n + ' B';
  const u = ['kB', 'MB', 'GB', 'TB'];
  let i = -1;
  do { n /= 1000; i++; } while (n >= 1000 && i < u.length - 1);
  return (n < 10 ? n.toFixed(1) : Math.round(n)) + ' ' + u[i];
}
const dateFmt = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' });
const fmtDate = ms => dateFmt.format(new Date(ms));

function toast(msg, isErr) {
  const t = document.createElement('div');
  t.className = 'toast' + (isErr ? ' err' : '');
  t.textContent = msg;
  $('#toasts').append(t);
  setTimeout(() => t.remove(), isErr ? 6000 : 3000);
}
const fail = e => { if (e && e.name !== 'AbortError' && !e.silent) toast(e.message || String(e), true); };

async function api(method, url, body, signal) {
  const opt = { method, signal, headers: { 'X-Shelf': '1' } };
  if (body !== undefined) { opt.body = JSON.stringify(body); opt.headers['Content-Type'] = 'application/json'; }
  const res = await fetch(url, opt);
  if (res.status === 401 && !url.startsWith('/api/login')) {
    showLogin();
    throw Object.assign(new Error('Login required'), { silent: true });
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || res.statusText || 'Request failed');
  return data;
}

// ---- dialogs -------------------------------------------------------------

function ask({ title, text = '', value = null, ok = 'OK', danger = false }) {
  const dlg = $('#ask'), input = $('#askInput'), okBtn = $('#askOk');
  $('#askTitle').textContent = title;
  $('#askText').textContent = text;
  input.hidden = value === null;
  input.required = value !== null;
  input.value = value || '';
  okBtn.textContent = ok;
  okBtn.className = danger ? 'danger' : 'primary';
  dlg.returnValue = 'cancel';
  dlg.showModal();
  if (value !== null) {
    input.focus();
    const dot = value.lastIndexOf('.');
    input.setSelectionRange(0, dot > 0 ? dot : value.length);
  } else okBtn.focus();
  return new Promise(resolve => {
    dlg.addEventListener('close', () => {
      if (dlg.returnValue !== 'ok') resolve(null);
      else resolve(value === null ? true : input.value.trim());
    }, { once: true });
  });
}

function showLogin() {
  const dlg = $('#login');
  if (!dlg.open) { dlg.showModal(); $('#loginUser').focus(); }
}
$('#login').addEventListener('cancel', e => e.preventDefault());
$('#loginForm').addEventListener('submit', async e => {
  e.preventDefault();
  $('#loginErr').textContent = '';
  try {
    await api('POST', '/api/login', { user: $('#loginUser').value, password: $('#loginPass').value });
    $('#login').close();
    $('#loginPass').value = '';
    $('#logoutBtn').hidden = false;
    load(state.path);
  } catch (err) { $('#loginErr').textContent = err.message; }
});
$('#logoutBtn').addEventListener('click', async () => {
  await api('POST', '/api/logout').catch(fail);
  listCache.clear();
  itemsEl.replaceChildren();
  showLogin();
});

// ---- navigation ----------------------------------------------------------

function pathFromHash() {
  let p = location.hash.slice(1);
  try { p = decodeURIComponent(p); } catch {}
  p = '/' + p.split('/').filter(Boolean).join('/');
  return p;
}

function navigate(p) {
  if (p === state.path && !state.search) return;
  scrollPos.set(state.path, scrollY);
  history.pushState(null, '', '#' + enc(p));
  scrollPos.delete(p);
  load(p);
}

addEventListener('popstate', () => {
  if (lb.open) { closeLightbox(true); }
  const p = pathFromHash();
  if (p !== state.path) { scrollPos.set(state.path, scrollY); load(p); }
});

async function fetchList(p) {
  const d = await api('GET', '/api/ls?p=' + encodeURIComponent(p));
  listCache.set(p, d.entries);
  return d.entries;
}

// load shows a folder. A cached listing is painted instantly and then
// refreshed in the background, so going back and forth never waits.
async function load(p, keepSel) {
  const seq = ++loadSeq;
  const changed = p !== state.path;
  state.path = p;
  if (changed || !keepSel) clearSel(true);
  if (changed) { state.search = null; $('#q').value = ''; }
  renderCrumbs();
  const cached = listCache.get(p);
  if (cached) {
    state.entries = cached;
    if (!state.search) { render(); if (changed) scrollTo(0, scrollPos.get(p) || 0); }
  } else if (changed) {
    itemsEl.replaceChildren();
    $('#status').textContent = '';
  }
  try {
    const fresh = await fetchList(p);
    if (seq !== loadSeq) return;
    if (!cached || JSON.stringify(fresh) !== JSON.stringify(cached)) {
      state.entries = fresh;
      if (!state.search) { render(); if (!cached && changed) scrollTo(0, scrollPos.get(p) || 0); }
    }
  } catch (e) {
    if (seq !== loadSeq) return;
    if (!cached && !e.silent) {
      itemsEl.innerHTML = `<div class="empty">${esc(e.message)}</div>`;
    }
    fail(e);
  }
}

function refresh() {
  listCache.clear();
  if (state.search) runSearch();
  return load(state.path, true);
}

function renderCrumbs() {
  const parts = state.path.split('/').filter(Boolean);
  let acc = '';
  let html = `<a href="#/" data-p="/" title="Home">${icon('home')}</a>`;
  for (const part of parts) {
    acc += '/' + part;
    html += `<span class="sep">/</span><a href="#${esc(enc(acc))}" data-p="${esc(acc)}">${esc(part)}</a>`;
  }
  const c = $('#crumbs');
  c.innerHTML = html;
  c.scrollLeft = c.scrollWidth;
  document.title = (parts.length ? parts[parts.length - 1] + ' · ' : '') + 'Shelf';
}
$('#crumbs').addEventListener('click', e => {
  const a = e.target.closest('a');
  if (!a || e.ctrlKey || e.metaKey || e.shiftKey) return;
  e.preventDefault();
  if (state.search) { state.search = null; $('#q').value = ''; if (a.dataset.p === state.path) render(); }
  navigate(a.dataset.p);
});

// ---- rendering -----------------------------------------------------------

const collator = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' });
function sorted(list) {
  const by = {
    name: (a, b) => collator.compare(a.n, b.n),
    date: (a, b) => b.m - a.m || collator.compare(a.n, b.n),
    size: (a, b) => b.s - a.s || collator.compare(a.n, b.n),
  }[prefs.sort] || ((a, b) => collator.compare(a.n, b.n));
  return [...list].sort((a, b) => (b.d ? 1 : 0) - (a.d ? 1 : 0) || (a.d && prefs.sort === 'size' ? collator.compare(a.n, b.n) : by(a, b)));
}

function itemHTML(e, i) {
  const p = pathOf(e);
  const ext = extOf(e.n);
  let inner, href, attrs = '';
  if (e.d) {
    inner = icon('folder');
    href = '#' + enc(p);
  } else {
    href = fileURL(p);
    attrs = ' target="_blank" rel="noopener"';
    const ic = e.k === 'i' ? 'image' : e.k === 'v' ? 'film' : e.k === 'a' ? 'music' : 'file';
    inner = icon(ic);
    if (!e.k && ext && ext.length <= 5) inner += `<span class="ext">${esc(ext)}</span>`;
    if (hasThumb(e)) inner += `<img alt="" decoding="async" data-src="${esc(thumbURL(e, p))}">`;
    else if (e.k === 'i') inner += `<img alt="" decoding="async" data-src="${esc(href)}">`;
    if (e.k === 'v') inner += `<span class="badge">${icon('play')}</span>`;
  }
  const sub = e.p ? `<div class="sub">${esc(parentOf(e.p))}</div>` : '';
  return `<a class="item${e.d ? ' dir' : ''}${state.sel.has(p) ? ' sel' : ''}" data-i="${i}" href="${esc(href)}"${attrs} title="${esc(e.n)}" draggable="false">` +
    `<span class="thumb">${inner}</span>` +
    `<span class="label"><div class="name">${esc(e.n)}</div>${sub}</span>` +
    `<span class="size">${e.d ? '' : fmtSize(e.s)}</span><span class="date">${fmtDate(e.m)}</span>` +
    `<span class="check" role="checkbox" aria-label="Select">${icon('check')}</span></a>`;
}

// Thumbnails load only near the viewport, and a request that is still pending
// when its tile scrolls away is cancelled so fast scrolling never queues up.
const thumbIO = new IntersectionObserver(entries => {
  for (const en of entries) {
    const img = en.target;
    if (en.isIntersecting) {
      if (!img.getAttribute('src')) img.src = img.dataset.src;
    } else if (img.getAttribute('src') && !img.complete) {
      img.removeAttribute('src');
    }
  }
}, { rootMargin: '500px 0px' });

itemsEl.addEventListener('load', e => {
  if (e.target.tagName === 'IMG') { e.target.classList.add('ok'); e.target.parentNode.classList.add('has-img'); thumbIO.unobserve(e.target); }
}, true);
itemsEl.addEventListener('error', e => {
  if (e.target.tagName === 'IMG' && e.target.getAttribute('src')) { thumbIO.unobserve(e.target); e.target.remove(); }
}, true);

let renderSeq = 0;
function render() {
  const seq = ++renderSeq;
  const src = state.search ? state.search.entries : state.entries;
  const view = state.view = sorted(src);
  thumbIO.disconnect();
  itemsEl.className = prefs.view;
  document.body.classList.toggle('list-view', prefs.view === 'list');
  itemsEl.classList.toggle('has-sel', state.sel.size > 0);

  const notice = $('#notice');
  notice.hidden = !state.search;
  if (state.search) {
    notice.textContent = `${view.length}${state.search.more ? '+' : ''} result${view.length === 1 ? '' : 's'} for “${state.search.q}” in ${state.path === '/' ? 'all files' : baseOf(state.path)}`;
  }

  if (!view.length) {
    itemsEl.innerHTML = `<div class="empty">${state.search ? 'Nothing found.' : state.readonly ? 'This folder is empty.' : 'This folder is empty. Drop files here to upload.'}</div>`;
    $('#status').textContent = '';
    return;
  }

  // Paint the first screenful immediately, the rest in idle slices.
  const CHUNK = 400;
  let i = 0;
  const paint = () => {
    if (seq !== renderSeq) return;
    let html = '';
    const end = Math.min(view.length, i + CHUNK);
    for (; i < end; i++) html += itemHTML(view[i], i);
    const first = itemsEl.children.length;
    if (first === 0 || itemsEl.firstElementChild.classList.contains('empty')) itemsEl.innerHTML = html;
    else itemsEl.insertAdjacentHTML('beforeend', html);
    for (const img of itemsEl.querySelectorAll('img[data-src]:not([data-o])')) { img.dataset.o = 1; thumbIO.observe(img); }
    if (i < view.length) setTimeout(paint, 0);
  };
  itemsEl.replaceChildren();
  paint();

  const dirs = view.filter(e => e.d).length, files = view.length - dirs;
  const bytes = view.reduce((n, e) => n + (e.d ? 0 : e.s), 0);
  const parts = [];
  if (dirs) parts.push(`${dirs} folder${dirs === 1 ? '' : 's'}`);
  if (files) parts.push(`${files} file${files === 1 ? '' : 's'} · ${fmtSize(bytes)}`);
  $('#status').textContent = parts.join(', ');
}

// ---- item interaction ----------------------------------------------------

// Long-press selects on touch screens.
let pressT, pressed = false, pressAt = null, lastPointer = 'mouse';
itemsEl.addEventListener('pointerdown', ev => {
  lastPointer = ev.pointerType;
  pressed = false;
  clearTimeout(pressT);
  const a = ev.target.closest('.item');
  if (!a || ev.pointerType === 'mouse') return;
  pressAt = { x: ev.clientX, y: ev.clientY };
  pressT = setTimeout(() => {
    pressed = true;
    toggleSel(+a.dataset.i);
    if (navigator.vibrate) navigator.vibrate(10);
  }, 450);
});
itemsEl.addEventListener('pointermove', ev => {
  if (pressAt && Math.hypot(ev.clientX - pressAt.x, ev.clientY - pressAt.y) > 10) clearTimeout(pressT);
});
for (const t of ['pointerup', 'pointercancel', 'pointerleave']) itemsEl.addEventListener(t, () => clearTimeout(pressT));
addEventListener('scroll', () => clearTimeout(pressT), { passive: true });
itemsEl.addEventListener('contextmenu', ev => { if (lastPointer !== 'mouse' && ev.target.closest('.item')) ev.preventDefault(); });

itemsEl.addEventListener('click', ev => {
  const a = ev.target.closest('.item');
  if (!a) return;
  if (pressed) { pressed = false; ev.preventDefault(); return; }
  const i = +a.dataset.i, e = state.view[i];
  if (ev.target.closest('.check') || ev.ctrlKey || ev.metaKey) { ev.preventDefault(); toggleSel(i); return; }
  if (ev.shiftKey) { ev.preventDefault(); rangeSel(i); return; }
  if (e.d) { ev.preventDefault(); navigate(pathOf(e)); }
  else if (e.k) { ev.preventDefault(); openLightbox(e); }
});

// Warm the listing cache when the pointer rests on a folder.
let hoverT;
itemsEl.addEventListener('pointerover', ev => {
  const a = ev.target.closest('.item.dir');
  clearTimeout(hoverT);
  if (!a) return;
  const p = pathOf(state.view[+a.dataset.i]);
  if (!listCache.has(p)) hoverT = setTimeout(() => fetchList(p).catch(() => {}), 80);
});

function markSel() {
  for (const a of itemsEl.children) {
    const e = state.view[+a.dataset.i];
    if (e) a.classList.toggle('sel', state.sel.has(pathOf(e)));
  }
  itemsEl.classList.toggle('has-sel', state.sel.size > 0);
  renderBar();
}
function toggleSel(i) {
  const p = pathOf(state.view[i]);
  state.sel.has(p) ? state.sel.delete(p) : state.sel.add(p);
  state.anchor = i;
  markSel();
}
function rangeSel(i) {
  const from = state.anchor < 0 ? i : state.anchor;
  for (let k = Math.min(from, i); k <= Math.max(from, i); k++) state.sel.add(pathOf(state.view[k]));
  markSel();
}
function clearSel(quiet) {
  state.sel.clear();
  state.anchor = -1;
  if (!quiet) markSel(); else renderBar();
}

// ---- action bar ----------------------------------------------------------

function renderBar() {
  const bar = $('#bar');
  const n = state.sel.size;
  let html = '';
  if (n) {
    html += `<span class="count">${n} selected</span>`;
    html += `<button data-act="download">${icon('download')}Download</button>`;
    if (!state.readonly) {
      if (n === 1) html += '<button data-act="rename">Rename</button>';
      html += '<button data-act="cut">Move</button><button data-act="copy">Copy</button>';
      html += '<button data-act="delete" class="danger">Delete</button>';
    }
    html += `<button data-act="clear" class="icon" title="Clear selection" aria-label="Clear selection">${icon('x')}</button>`;
  } else if (state.clip) {
    const c = state.clip.paths.length;
    html += `<span class="count">${state.clip.copy ? 'Copying' : 'Moving'} ${c} item${c === 1 ? '' : 's'}</span>`;
    html += `<button data-act="paste" class="primary">${state.clip.copy ? 'Copy' : 'Move'} here</button>`;
    html += `<button data-act="unclip" class="icon" title="Cancel" aria-label="Cancel">${icon('x')}</button>`;
  }
  bar.innerHTML = html;
  bar.hidden = !html;
}

const actions = {
  clear: () => clearSel(),
  unclip: () => { state.clip = null; renderBar(); },
  cut: () => { state.clip = { paths: [...state.sel], copy: false }; clearSel(); toast('Open the destination folder, then choose “Move here”.'); },
  copy: () => { state.clip = { paths: [...state.sel], copy: true }; clearSel(); toast('Open the destination folder, then choose “Copy here”.'); },
  download() {
    const paths = [...state.sel];
    const one = paths.length === 1 && state.view.find(e => pathOf(e) === paths[0]);
    const url = one && !one.d ? fileURL(paths[0]) + '?dl' : '/api/zip?' + paths.map(p => 'p=' + encodeURIComponent(p)).join('&');
    const a = document.createElement('a');
    a.href = url; a.download = '';
    document.body.append(a); a.click(); a.remove();
  },
  async rename() {
    const p = [...state.sel][0];
    const name = await ask({ title: 'Rename', value: baseOf(p), ok: 'Rename' });
    if (!name || name === baseOf(p)) return;
    await api('POST', '/api/rename', { path: p, name });
    clearSel(true);
    await refresh();
  },
  async delete() {
    const paths = [...state.sel];
    const what = paths.length === 1 ? `“${baseOf(paths[0])}”` : `${paths.length} items`;
    if (!await ask({ title: `Delete ${what}?`, text: 'This permanently removes them from disk, including everything inside folders.', ok: 'Delete', danger: true })) return;
    await api('POST', '/api/delete', { paths });
    clearSel(true);
    await refresh();
  },
  async paste() {
    const { paths, copy } = state.clip;
    await api('POST', '/api/transfer', { from: paths, to: state.path, copy });
    state.clip = null;
    renderBar();
    await refresh();
  },
};
$('#bar').addEventListener('click', e => {
  const b = e.target.closest('button[data-act]');
  if (b) Promise.resolve(actions[b.dataset.act]()).catch(fail);
});

// ---- toolbar -------------------------------------------------------------

function applyPrefs() {
  document.documentElement.style.setProperty('--tile', prefs.tile + 'px');
  $('#tile').value = prefs.tile;
  $('#sort').value = prefs.sort;
  $('#viewBtn').innerHTML = icon(prefs.view === 'grid' ? 'list' : 'grid');
}
$('#tile').addEventListener('input', e => { prefs.tile = +e.target.value; applyPrefs(); savePrefs(); });
$('#sort').addEventListener('change', e => { prefs.sort = e.target.value; savePrefs(); render(); });
$('#viewBtn').addEventListener('click', () => { prefs.view = prefs.view === 'grid' ? 'list' : 'grid'; applyPrefs(); savePrefs(); render(); });

$('#mkdirBtn').addEventListener('click', async () => {
  const name = await ask({ title: 'New folder', value: '', ok: 'Create' });
  if (!name) return;
  try { await api('POST', '/api/mkdir', { path: state.path, name }); await refresh(); } catch (e) { fail(e); }
});

// ---- search --------------------------------------------------------------

let searchT, searchAbort;
async function runSearch() {
  const q = $('#q').value.trim();
  if (searchAbort) searchAbort.abort();
  if (!q) {
    if (state.search) { state.search = null; render(); }
    return;
  }
  searchAbort = new AbortController();
  const at = state.path;
  try {
    const d = await api('GET', `/api/search?p=${encodeURIComponent(at)}&q=${encodeURIComponent(q)}`, undefined, searchAbort.signal);
    if (at !== state.path) return;
    state.search = { q, entries: d.entries, more: d.more };
    clearSel(true);
    render();
  } catch (e) { fail(e); }
}
$('#q').addEventListener('input', () => { clearTimeout(searchT); searchT = setTimeout(runSearch, 180); });

// ---- uploads -------------------------------------------------------------

const up = { queue: [], active: new Set(), total: 0, done: 0, failed: 0, bytes: 0, sent: 0, dirs: new Set() };

function addUploads(list, dir) {
  if (!list.length) return;
  if (state.readonly) { toast('This server is read-only.', true); return; }
  for (const it of list) {
    up.queue.push({ ...it, dir, loaded: 0 });
    up.total++;
    up.bytes += it.file.size;
  }
  up.dirs.add(dir);
  pumpUploads();
}

function pumpUploads() {
  while (up.active.size < 3 && up.queue.length) startUpload(up.queue.shift());
  const busy = up.active.size > 0;
  $('#uploads').hidden = !busy;
  if (busy) {
    let sent = up.sent;
    for (const j of up.active) sent += j.loaded;
    const pct = up.bytes ? Math.min(100, Math.round(sent / up.bytes * 100)) : 0;
    $('#upText').textContent = `Uploading ${Math.min(up.done + up.failed + 1, up.total)} of ${up.total} · ${pct}%`;
    $('#upFill').style.width = pct + '%';
    return;
  }
  if (up.total) {
    const ok = up.done;
    if (ok) toast(`Uploaded ${ok} file${ok === 1 ? '' : 's'}.`);
    const touched = up.dirs.has(state.path);
    Object.assign(up, { total: 0, done: 0, failed: 0, bytes: 0, sent: 0 });
    up.dirs.clear();
    listCache.clear();
    if (touched) refresh();
  }
}

function startUpload(job) {
  const xhr = job.xhr = new XMLHttpRequest();
  up.active.add(job);
  xhr.open('PUT', `/api/upload?p=${encodeURIComponent(job.dir)}&name=${encodeURIComponent(job.rel)}`);
  xhr.setRequestHeader('X-Shelf', '1');
  xhr.upload.onprogress = e => { job.loaded = e.loaded; pumpUploads(); };
  xhr.onloadend = () => {
    up.active.delete(job);
    up.sent += job.file.size;
    if (xhr.status >= 200 && xhr.status < 300) up.done++;
    else {
      up.failed++;
      if (!job.cancelled) {
        let msg = xhr.status ? `upload failed (HTTP ${xhr.status})` : 'upload failed, the connection was cut or the file could not be read';
        try { msg = JSON.parse(xhr.responseText).error || msg; } catch {}
        toast(`${job.rel}: ${msg}`, true);
      }
    }
    pumpUploads();
  };
  xhr.send(job.file);
}

$('#upCancel').addEventListener('click', () => {
  up.failed += up.queue.length;
  up.queue.length = 0;
  for (const j of up.active) { j.cancelled = true; j.xhr.abort(); }
});
$('#uploadBtn').addEventListener('click', () => $('#filePick').click());
$('#filePick').addEventListener('change', e => {
  addUploads([...e.target.files].map(file => ({ file, rel: file.name })), state.path);
  e.target.value = '';
});

// Dropped folders are walked so their structure is kept.
async function walkEntry(entry, prefix, out) {
  if (entry.isFile) {
    const file = await new Promise((res, rej) => entry.file(res, rej));
    out.push({ file, rel: prefix + entry.name });
  } else if (entry.isDirectory) {
    const reader = entry.createReader();
    for (;;) {
      const batch = await new Promise((res, rej) => reader.readEntries(res, rej));
      if (!batch.length) break;
      for (const child of batch) await walkEntry(child, prefix + entry.name + '/', out);
    }
  }
}

let dragDepth = 0;
function hasFiles(e) {
  const t = e.dataTransfer && e.dataTransfer.types;
  if (!t) return false;
  for (let i = 0; i < t.length; i++) if (t[i] === 'Files' || t[i] === 'application/x-moz-file') return true;
  return false;
}
const showDrop = on => { $('#drop').hidden = !on; };
// Both dragenter and dragover have to be cancelled, or some browsers refuse the drop.
addEventListener('dragenter', e => {
  if (!hasFiles(e)) return;
  e.preventDefault();
  dragDepth++;
  if (!state.readonly) showDrop(true);
});
addEventListener('dragover', e => {
  if (!hasFiles(e)) return;
  e.preventDefault();
  e.dataTransfer.dropEffect = state.readonly ? 'none' : 'copy';
  if (!state.readonly) showDrop(true);
});
addEventListener('dragleave', e => {
  if (!hasFiles(e)) return;
  if (--dragDepth <= 0) { dragDepth = 0; showDrop(false); }
});
addEventListener('dragend', () => { dragDepth = 0; showDrop(false); });
addEventListener('drop', e => {
  if (!hasFiles(e)) return;
  e.preventDefault();   // otherwise the browser opens the file instead
  dragDepth = 0;
  showDrop(false);
  if (state.readonly) { toast('This server is read-only.', true); return; }
  if (lb.open) closeLightbox();
  const dir = state.path;
  const dt = e.dataTransfer;

  // Everything is collected right now: the browser empties the drop data as
  // soon as this handler returns. Plain files are taken as File objects, the
  // dependable route; only folders need the entry API.
  const files = [], folders = [];
  const items = dt.items ? Array.from(dt.items) : [];
  for (const it of items) {
    if (it.kind !== 'file') continue;
    let entry = null;
    try { entry = it.webkitGetAsEntry ? it.webkitGetAsEntry() : null; } catch {}
    if (entry && entry.isDirectory) { folders.push(entry); continue; }
    const file = it.getAsFile();
    if (file) files.push({ file, rel: file.name });
  }
  if (!files.length && !folders.length) {
    for (const file of Array.from(dt.files || [])) files.push({ file, rel: file.name });
  }
  if (!files.length && !folders.length) { toast('The browser did not hand over any files from that drop.', true); return; }

  addUploads(files, dir);
  (async () => {
    for (const folder of folders) {
      const out = [];
      try { await walkEntry(folder, '', out); }
      catch (err) { toast(`Could not read folder “${folder.name}”: ${err && err.message || err}`, true); }
      addUploads(out, dir);
    }
  })();
});

// ---- lightbox ------------------------------------------------------------

const lbEl = $('#lb'), stage = $('#lbStage'), modeSel = $('#lbMode');
const lb = { open: false, list: [], i: 0, pushed: false, idleT: 0, token: 0, hls: null, el: null, entry: null, info: null, mode: null };
const ASSET_V = document.currentScript ? document.currentScript.dataset.v : '';
const fsElement = () => document.fullscreenElement || document.webkitFullscreenElement;
const canFS = !!(lbEl.requestFullscreen || lbEl.webkitRequestFullscreen);

function openLightbox(entry) {
  lb.list = state.view.filter(e => e.k);
  lb.i = Math.max(0, lb.list.indexOf(entry));
  lb.open = true;
  ptrs.clear();
  gest = null;
  stage.style.transform = '';
  lbEl.hidden = false;
  document.body.style.overflow = 'hidden';
  history.pushState({ lb: true }, '');   // so Back closes the viewer
  lb.pushed = true;
  lbEl.focus();
  showMedia();
  wake();
}

function closeLightbox(fromPop) {
  if (!lb.open) return;
  lb.open = false;
  lb.token++;
  stopMedia();
  stage.replaceChildren();
  if (fsElement()) (document.exitFullscreen || document.webkitExitFullscreen).call(document);
  lbEl.hidden = true;
  document.body.style.overflow = '';
  clearTimeout(lb.idleT);
  if (lb.pushed && !fromPop) history.back();
  lb.pushed = false;
  // Bring the last viewed item into view in the grid.
  const e = lb.list[lb.i];
  const idx = state.view.indexOf(e);
  const el = idx >= 0 && itemsEl.children[idx];
  if (el) el.scrollIntoView({ block: 'nearest' });
}

function stopMedia() {
  if (lb.hls) { lb.hls.destroy(); lb.hls = null; }
  for (const m of stage.querySelectorAll('video, audio')) {
    m.onerror = m.onloadedmetadata = null;
    m.pause();
    m.removeAttribute('src');
    m.load();   // drops the connection instead of buffering in the background
  }
  lb.el = null;
}

const fullURL = (e, p) => (e.k !== 'i' || NATIVE.has(extOf(e.n))) ? fileURL(p) : thumbURL(e, p, true);

function unplayable() {
  const e = lb.list[lb.i];
  if (!e) return;
  stopMedia();
  modeSel.hidden = true;
  const d = document.createElement('div');
  d.className = 'msg';
  d.innerHTML = `<div class="big">${icon(e.k === 'i' ? 'image' : e.k === 'a' ? 'music' : 'film')}</div><p>This file cannot be shown here.</p><p><a href="${esc(fileURL(pathOf(e)))}?dl">Download it</a></p>`;
  stage.replaceChildren(d);
}

// ---- playback: original file or on-the-fly transcode ----------------------

const infoCache = new Map();
function mediaInfo(e, p) {
  const k = `${p}|${e.m}|${e.s}`;
  if (!infoCache.has(k)) infoCache.set(k, api('GET', '/api/media/info?p=' + encodeURIComponent(p)).catch(() => null));
  return infoCache.get(k);
}

let hlsLoading;
const loadHls = () => hlsLoading || (hlsLoading = new Promise(res => {
  const s = document.createElement('script');
  s.src = '/_/hls.min.js?v=' + ASSET_V;
  s.onload = s.onerror = () => res();
  document.head.append(s);
}));

const tester = document.createElement('video');
const plays = t => tester.canPlayType(t) !== '';
const WEB_AUDIO = new Set(['aac', 'mp3', 'opus', 'vorbis', 'flac', 'pcm_s16le', 'pcm_s24le', 'pcm_f32le', 'pcm_u8']);
const WEB_BOX = new Set(['mp4', 'm4v', 'mov', 'webm', 'mkv', 'ogv', 'mp3', 'm4a', 'm4b', 'aac', 'flac', 'ogg', 'oga', 'opus', 'wav']);

// canDirect guesses whether this browser can play the file untouched. A wrong
// "yes" is harmless: a playback error falls back to transcoding.
function canDirect(e, info) {
  if (!WEB_BOX.has(extOf(e.n))) return false;
  if (!info) return true;
  if (info.audio && !WEB_AUDIO.has(info.audio)) return false;
  if (e.k !== 'v') return true;
  switch (info.video) {
    case 'h264': return /^yuvj?420p$/.test(info.pixfmt || 'yuv420p');
    case 'hevc': return plays(/10/.test(info.pixfmt || '') ? 'video/mp4; codecs="hvc1.2.4.L153.B0"' : 'video/mp4; codecs="hvc1.1.6.L153.B0"');
    case 'av1': return plays('video/mp4; codecs="av01.0.08M.08"');
    case 'vp9': return plays('video/webm; codecs="vp9"');
    case 'vp8': return plays('video/webm; codecs="vp8"');
    default: return false;
  }
}

function buildModes(e, info) {
  const opts = [['direct', 'Original']];
  if (e.k === 'a') opts.push(['src', 'Transcode']);
  else {
    opts.push(['src', 'Transcode · full size']);
    for (const h of [1080, 720, 480]) if (!info || !info.height || info.height > h) opts.push([String(h), `Transcode · ${h}p`]);
  }
  modeSel.innerHTML = opts.map(([v, t]) => `<option value="${v}">${t}</option>`).join('');
  modeSel.hidden = false;
}

function transcodeMode(e) {
  const want = e.k === 'a' ? 'src' : (prefs.tq || 'src');
  return [...modeSel.options].some(o => o.value === want) ? want : 'src';
}

async function startPlayback(mode, at) {
  const e = lb.entry, el = lb.el, token = lb.token;
  if (!el) return;
  const p = pathOf(e);
  if (lb.hls) { lb.hls.destroy(); lb.hls = null; }
  lb.mode = mode;
  modeSel.value = mode;
  el.onerror = el.onloadedmetadata = null;
  const seek = () => { if (at > 0.5) { try { el.currentTime = at; } catch {} } };
  const fallback = () => {
    if (token !== lb.token || lb.mode !== 'direct') return;
    toast('The original cannot be played here, transcoding instead.');
    startPlayback(transcodeMode(e), el.currentTime || at || 0);
  };

  if (mode === 'direct') {
    el.onerror = fallback;
    el.onloadedmetadata = () => {
      // Sound but no picture means the video codec is not supported.
      if (e.k === 'v' && lb.info && lb.info.video && !el.videoWidth) return fallback();
      seek();
    };
    el.src = fileURL(p);
  } else {
    const url = `/api/hls/index.m3u8?p=${encodeURIComponent(p)}&q=${mode}&v=${e.m}-${e.s}`;
    const nativeHls = plays('application/vnd.apple.mpegurl');
    const apple = nativeHls && /Apple/.test(navigator.vendor);   // Safari's own player is the best one there
    if (!apple) await loadHls();
    if (token !== lb.token) return;
    if (!apple && window.Hls && window.Hls.isSupported()) {
      const Hls = window.Hls;
      const slow = { maxTimeToFirstByteMs: 90000, maxLoadTimeMs: 180000,
        timeoutRetry: { maxNumRetry: 2, retryDelayMs: 0, maxRetryDelayMs: 0 },
        errorRetry: { maxNumRetry: 3, retryDelayMs: 1000, maxRetryDelayMs: 8000 } };
      const h = lb.hls = new Hls({ startPosition: at > 0.5 ? at : -1, maxBufferLength: 30, maxMaxBufferLength: 60, fragLoadPolicy: { default: slow } });
      let recovered = false;
      h.on(Hls.Events.ERROR, (_, d) => {
        if (!d.fatal || token !== lb.token) return;
        if (d.type === Hls.ErrorTypes.MEDIA_ERROR && !recovered) { recovered = true; h.recoverMediaError(); }
        else unplayable();
      });
      h.loadSource(url);
      h.attachMedia(el);
    } else if (nativeHls) {
      el.onerror = () => { if (token === lb.token) unplayable(); };
      el.onloadedmetadata = seek;
      el.src = url;
    } else {
      return unplayable();
    }
  }
  const pr = el.play();
  if (pr && pr.catch) pr.catch(() => {});
}

modeSel.addEventListener('change', () => {
  if (!lb.el) return;
  if (modeSel.value !== 'direct' && lb.entry.k === 'v') { prefs.tq = modeSel.value; savePrefs(); }
  startPlayback(modeSel.value, lb.el.currentTime || 0);
});

function showMedia() {
  const e = lb.list[lb.i];
  if (!e) return closeLightbox();
  const p = pathOf(e), token = ++lb.token;
  stopMedia();
  resetZoom();
  lb.entry = e;
  lb.info = null;
  modeSel.hidden = true;
  $('#lbName').textContent = e.n;
  $('#lbCount').textContent = `${lb.i + 1} / ${lb.list.length}`;
  $('#lbDl').href = fileURL(p) + '?dl';
  $('#lbPrev').style.visibility = lb.i > 0 ? '' : 'hidden';
  $('#lbNext').style.visibility = lb.i < lb.list.length - 1 ? '' : 'hidden';

  if (e.k === 'i') {
    const img = new Image();
    img.className = 'lb-media';
    img.alt = e.n;
    img.draggable = false;
    const full = fullURL(e, p);
    if (hasThumb(e)) {
      // The grid thumbnail is already cached: show it at once, then swap in
      // the full image when it has decoded.
      img.src = thumbURL(e, p);
      const hi = new Image();
      hi.src = full;
      hi.decode().then(() => { if (token === lb.token) img.src = full; }, () => {});
    } else {
      img.src = full;
    }
    img.onerror = () => { if (token === lb.token) unplayable(); };
    stage.replaceChildren(img);
  } else {
    let el;
    if (e.k === 'v') {
      el = document.createElement('video');
      el.className = 'lb-media';
      el.playsInline = true;
      el.poster = thumbURL(e, p);
      // Use our own fullscreen so next/previous keep working inside it.
      if (canFS) el.setAttribute('controlslist', 'nofullscreen');
      stage.replaceChildren(el);
      if (navigator.maxTouchPoints > 0) {
        // Phones give touches on a video to its built-in player, so swipes
        // never reach us. This layer covers everything but the control strip.
        const layer = document.createElement('div');
        layer.className = 'lb-touch';
        layer.addEventListener('click', () => { el.paused ? el.play().catch(() => {}) : el.pause(); });
        stage.append(layer);
      }
    } else {
      el = document.createElement('audio');
      const wrap = document.createElement('div');
      wrap.className = 'big';
      wrap.innerHTML = icon('music');
      stage.replaceChildren(wrap, el);
    }
    el.controls = true;
    el.autoplay = true;
    el.preload = 'metadata';
    lb.el = el;
    buildModes(e, null);
    mediaInfo(e, p).then(info => {
      if (token !== lb.token) return;
      lb.info = info;
      buildModes(e, info);
      startPlayback(canDirect(e, info) ? 'direct' : transcodeMode(e), 0);
    });
  }

  // Preload the neighbours so stepping through pictures is instant.
  for (const n of [lb.list[lb.i + 1], lb.list[lb.i - 1]]) {
    if (n && n.k === 'i') new Image().src = fullURL(n, pathOf(n));
  }
}

function step(d) {
  const n = lb.i + d;
  if (n < 0 || n >= lb.list.length) return false;
  lb.i = n;
  showMedia();
  return true;
}

function toggleFS() {
  if (fsElement()) return (document.exitFullscreen || document.webkitExitFullscreen).call(document);
  const rq = lbEl.requestFullscreen || lbEl.webkitRequestFullscreen;
  if (rq) {
    const r = rq.call(lbEl);
    if (r && r.catch) r.catch(() => {});
    return;
  }
  // iPhone Safari only allows fullscreen on the video element itself.
  const v = $('video', stage);
  if (v && v.webkitEnterFullscreen) v.webkitEnterFullscreen();
  else toast('Fullscreen is not available in this browser.');
}
for (const ev of ['fullscreenchange', 'webkitfullscreenchange']) {
  document.addEventListener(ev, () => { $('#lbFs').innerHTML = icon(fsElement() ? 'minimize' : 'maximize'); });
}

function wake() {
  lbEl.classList.remove('idle');
  clearTimeout(lb.idleT);
  lb.idleT = setTimeout(() => { if (lb.open && document.activeElement !== modeSel) lbEl.classList.add('idle'); }, 2500);
}
for (const ev of ['mousemove', 'pointerdown', 'keydown']) lbEl.addEventListener(ev, wake);

$('#lbClose').addEventListener('click', () => closeLightbox());
$('#lbFs').addEventListener('click', toggleFS);
$('#lbPrev').addEventListener('click', () => step(-1));
$('#lbNext').addEventListener('click', () => step(1));
stage.addEventListener('click', e => { if (e.target === stage) closeLightbox(); });

// ---- viewer gestures: swipe, pinch, pan, double-tap, wheel ----------------

const zoom = { s: 1, x: 0, y: 0 };
const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));
function applyZoom() {
  const img = $('img.lb-media', stage);
  if (!img) return;
  img.style.transform = zoom.s > 1 ? `translate(${zoom.x}px, ${zoom.y}px) scale(${zoom.s})` : '';
  img.style.cursor = zoom.s > 1 ? 'grab' : '';
}
function clampPan() {
  const r = stage.getBoundingClientRect();
  const mx = (zoom.s - 1) * r.width / 2, my = (zoom.s - 1) * r.height / 2;
  zoom.x = clamp(zoom.x, -mx, mx);
  zoom.y = clamp(zoom.y, -my, my);
}
function zoomAt(cx, cy, ns) {
  const r = stage.getBoundingClientRect();
  ns = clamp(ns, 1, 8);
  const px = cx - r.left - r.width / 2, py = cy - r.top - r.height / 2;
  zoom.x = px - (px - zoom.x) * ns / zoom.s;
  zoom.y = py - (py - zoom.y) * ns / zoom.s;
  zoom.s = ns;
  clampPan();
  applyZoom();
}
function resetZoom() { zoom.s = 1; zoom.x = zoom.y = 0; applyZoom(); }

// One gesture engine fed from two sources: touch events for fingers and
// pointer events for mouse and pen. Fingers use touch events on purpose:
// they keep flowing for the whole gesture on every phone browser, whereas
// the browser may cancel a pointer stream to start its own pan or zoom.
const ptrs = new Map();   // id -> { x, y }
let gest = null, lastTap = null, lastTouchAt = 0;
const HAS_TOUCH = 'ontouchstart' in window;
const spread = () => { const [a, b] = [...ptrs.values()]; return Math.hypot(a.x - b.x, a.y - b.y) || 1; };
// Touches on the player's own control strip belong to the player.
function onControls(target, y) {
  const m = target.closest && target.closest('video, audio');
  if (!m) return false;
  return m.tagName === 'AUDIO' || y > m.getBoundingClientRect().bottom - 90;
}

function gestureDown(id, x, y, type, target) {
  ptrs.set(id, { x, y });
  if (ptrs.size === 1) {
    gest = { id, x, y, zx: zoom.x, zy: zoom.y, type,
      img: !!target.closest('img.lb-media'), video: !!target.closest('video, .lb-touch'),
      wasIdle: lbEl.classList.contains('idle'), moved: false };
  } else if (ptrs.size === 2) {
    stage.style.transform = '';
    gest = { pinch: true, d: spread(), s: zoom.s };
  }
}

// Returns true while the gesture is ours, so the caller can stop the
// browser from scrolling or navigating with the same movement.
function gestureMove(id, x, y) {
  if (!ptrs.has(id)) return false;
  ptrs.set(id, { x, y });
  if (!gest || gest.over) return true;
  if (gest.pinch) {
    if (ptrs.size >= 2 && $('img.lb-media', stage)) {
      const [a, b] = [...ptrs.values()];
      zoomAt((a.x + b.x) / 2, (a.y + b.y) / 2, gest.s * spread() / gest.d);
    }
    return true;
  }
  const dx = x - gest.x, dy = y - gest.y;
  if (Math.hypot(dx, dy) > 8) gest.moved = true;
  if (zoom.s === 1 && gest.type !== 'mouse' && (gest.drag || (Math.abs(dx) > 10 && Math.abs(dx) > Math.abs(dy)))) {
    gest.drag = true;   // the stage follows the finger
    stage.style.transition = 'none';
    stage.style.transform = `translateX(${dx}px)`;
  }
  if (zoom.s > 1 && gest.img) { zoom.x = gest.zx + dx; zoom.y = gest.zy + dy; clampPan(); applyZoom(); }
  return true;
}

function gestureUp(id, x, y, cancelled) {
  if (!ptrs.delete(id)) return;
  const g = gest;
  if (ptrs.size) { if (g) g.over = true; return; }   // one finger of a pinch lifted
  gest = null;
  const settle = () => {   // glide back to rest
    if (!stage.style.transform) return;
    stage.style.transition = 'transform .16s ease-out';
    stage.style.transform = '';
  };
  if (!g || g.pinch || g.over || !lb.open) return settle();
  if (zoom.s > 1 || g.type === 'mouse') return settle();
  const dx = x - g.x, dy = y - g.y;
  // A swipe counts even if the browser cut the gesture short.
  if (Math.abs(dx) > 45 && Math.abs(dx) > 1.2 * Math.abs(dy)) {
    const dir = dx < 0 ? 1 : -1;
    if (!step(dir)) return settle();
    // The next item slides in from the side the finger came from.
    stage.style.transition = 'none';
    stage.style.transform = '';
    if (stage.animate) stage.animate([{ transform: `translateX(${dir * 35}%)`, opacity: 0.3 }, { transform: 'none', opacity: 1 }], { duration: 170, easing: 'ease-out' });
    return;
  }
  settle();
  if (cancelled) return;
  if (dy > 110 && Math.abs(dy) > 2 * Math.abs(dx) && !fsElement()) return closeLightbox();
  if (g.moved) return;
  // Taps: a second one close by is a double-tap.
  const now = Date.now();
  if (lastTap && now - lastTap.t < 320 && Math.hypot(x - lastTap.x, y - lastTap.y) < 40) {
    lastTap = null;
    if (g.img) zoom.s > 1 ? resetZoom() : zoomAt(x, y, 2.5);
    else if (g.video) toggleFS();
    return;
  }
  lastTap = { t: now, x, y };
  if (g.img && !g.wasIdle) { clearTimeout(lb.idleT); lbEl.classList.add('idle'); }   // tap hides the bars, next tap shows them
}

// Fingers.
lbEl.addEventListener('touchstart', e => {
  lastTouchAt = Date.now();
  if (!e.target.closest('#lbStage')) return;
  for (const t of e.changedTouches) {
    if (onControls(e.target, t.clientY)) continue;
    gestureDown('t' + t.identifier, t.clientX, t.clientY, 'touch', e.target);
  }
}, { passive: true });
lbEl.addEventListener('touchmove', e => {
  let ours = false;
  for (const t of e.changedTouches) ours = gestureMove('t' + t.identifier, t.clientX, t.clientY) || ours;
  if (ours && e.cancelable) e.preventDefault();   // keep the browser from panning or going back
}, { passive: false });
for (const type of ['touchend', 'touchcancel']) {
  // On the window: the element under the finger may be gone by now.
  addEventListener(type, e => {
    lastTouchAt = Date.now();
    for (const t of e.changedTouches) gestureUp('t' + t.identifier, t.clientX, t.clientY, type === 'touchcancel');
  }, { passive: true, capture: true });
}

// Mouse and pen (and touch on the rare browser without touch events).
const fromFinger = e => e.pointerType === 'touch' && HAS_TOUCH;
stage.addEventListener('pointerdown', e => {
  if (fromFinger(e) || e.button > 0 || onControls(e.target, e.clientY)) return;
  gestureDown('p' + e.pointerId, e.clientX, e.clientY, e.pointerType, e.target);
});
addEventListener('pointermove', e => { if (!fromFinger(e)) gestureMove('p' + e.pointerId, e.clientX, e.clientY); });
addEventListener('pointerup', e => { if (!fromFinger(e)) gestureUp('p' + e.pointerId, e.clientX, e.clientY, false); });
addEventListener('pointercancel', e => { if (!fromFinger(e)) gestureUp('p' + e.pointerId, e.clientX, e.clientY, true); });

stage.addEventListener('dblclick', e => {
  if (Date.now() - lastTouchAt < 1000) return;   // double-taps are handled above
  if (e.target.closest('img.lb-media')) zoom.s > 1 ? resetZoom() : zoomAt(e.clientX, e.clientY, 2.5);
  else if (e.target.closest('video, .lb-touch') && !onControls(e.target, e.clientY)) toggleFS();
});
stage.addEventListener('wheel', e => {
  if (!e.target.closest('img.lb-media')) return;
  e.preventDefault();
  zoomAt(e.clientX, e.clientY, zoom.s * (e.deltaY < 0 ? 1.25 : 0.8));
}, { passive: false });
addEventListener('resize', () => { if (lb.open) { clampPan(); applyZoom(); } });

// ---- keyboard ------------------------------------------------------------

addEventListener('keydown', e => {
  if (document.querySelector('dialog[open]')) return;
  if (lb.open) {
    const v = $('video, audio', stage);
    switch (e.key) {
      case 'ArrowLeft': case 'PageUp': step(-1); break;
      case 'ArrowRight': case 'PageDown': step(1); break;
      case 'Home': lb.i = 0; showMedia(); break;
      case 'End': lb.i = lb.list.length - 1; showMedia(); break;
      case 'Escape': if (!fsElement()) closeLightbox(); break;
      case 'f': case 'F': toggleFS(); break;
      case ' ': case 'k':
        if (v && document.activeElement !== v) v.paused ? v.play() : v.pause();
        else return;
        break;
      case 'm': if (v) v.muted = !v.muted; break;
      default: return;
    }
    e.preventDefault();
    return;
  }
  const typing = /^(INPUT|TEXTAREA|SELECT)$/.test(e.target.tagName);
  if (e.key === 'Escape') {
    if (typing && e.target.id === 'q') { e.target.value = ''; e.target.blur(); runSearch(); }
    else if (state.sel.size) clearSel();
    return;
  }
  if (typing) return;
  if (e.key === '/') { e.preventDefault(); $('#q').focus(); }
  else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'a') {
    e.preventDefault();
    for (const en of state.view) state.sel.add(pathOf(en));
    markSel();
  } else if (e.key === 'Delete' && state.sel.size && !state.readonly) {
    actions.delete().catch(fail);
  } else if (e.key === 'Backspace' && state.path !== '/') {
    navigate(parentOf(state.path));
  }
});

// ---- start ---------------------------------------------------------------

(async function init() {
  applyPrefs();
  state.path = pathFromHash();
  renderCrumbs();
  try {
    const me = await api('GET', '/api/me');
    state.readonly = me.readonly;
    document.body.classList.toggle('readonly', me.readonly);
    $('#logoutBtn').hidden = !(me.login && me.authed);
    if (!me.authed) return showLogin();
  } catch (e) { fail(e); }
  const p = state.path;
  state.path = null;   // force the first load to count as a navigation
  load(p);
})();
