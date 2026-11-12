// SteamShelf web: the little behaviour htmx doesn't cover. Everything uses event delegation, so it keeps
// working when htmx swaps parts of the page.
(() => {
  'use strict';

  const $ = (selector, root = document) => root.querySelector(selector);

  // Steam artwork: try the next candidate URL when an image is missing (newer games use hashed paths).
  document.addEventListener('error', (event) => {
    const img = event.target;
    if (!(img instanceof HTMLImageElement) || img.dataset.fallback === undefined) return;
    const [next, ...rest] = img.dataset.fallback.split(' ').filter(Boolean);
    if (next) {
      img.dataset.fallback = rest.join(' ');
      img.src = next;
    } else {
      delete img.dataset.fallback;
      img.classList.add('missing');
    }
  }, true);

  // ---- Toasts ----

  function toast(message, kind = 'info') {
    const box = $('#toasts');
    if (!box || !message) return;
    const el = document.createElement('div');
    el.className = `toast ${kind}`;
    el.textContent = message;
    box.append(el);
    setTimeout(() => el.remove(), kind === 'error' ? 8000 : 4500);
  }

  // Server responses can ask for a toast or to close the dialog through the HX-Trigger header.
  document.body.addEventListener('toast', (e) => toast(e.detail.value));
  document.body.addEventListener('clear-search', () => {
    const q = $('#q');
    if (q) q.value = '';
  });
  document.body.addEventListener('dialog-close', () => document.querySelectorAll('dialog[open]').forEach((d) => d.close()));
  document.body.addEventListener('htmx:responseError', (e) => {
    if (e.detail.xhr.status === 401) return; // htmx follows HX-Redirect to the sign-in page
    toast(`Something went wrong (HTTP ${e.detail.xhr.status}).`, 'error');
  });
  document.body.addEventListener('htmx:sendError', () => toast("Couldn't reach SteamShelf. Check your connection.", 'error'));

  // ---- Filters sheet (small screens) ----

  function setFiltersOpen(open) {
    document.body.classList.toggle('filters-open', open);
    document.querySelectorAll('[data-filters-open]').forEach((b) => b.setAttribute('aria-expanded', String(open)));
    if (open) $('#filters .filters-head button')?.focus();
  }

  document.addEventListener('click', (e) => {
    const target = e.target instanceof Element ? e.target : null;
    if (!target) return;
    if (target.closest('[data-filters-open]')) setFiltersOpen(true);
    else if (target.closest('[data-filters-close]')) setFiltersOpen(false);

    const more = target.closest('[data-tags-more]');
    if (more) {
      const list = $('#tag-list');
      const collapsed = list.classList.toggle('collapsed');
      more.textContent = collapsed ? more.dataset.more : more.dataset.less;
      more.setAttribute('aria-expanded', String(!collapsed));
    }

    const opener = target.closest('[data-dialog-open]');
    if (opener) {
      const dialog = document.getElementById(opener.dataset.dialogOpen);
      if (dialog) {
        dialog.showModal();
        // The dialog runs its automatic search each time it opens, like the app.
        const results = dialog.querySelector('[hx-trigger="dialog-open"]');
        if (results) htmx.trigger(results, 'dialog-open');
      }
    }
    if (target.closest('[data-dialog-close]')) target.closest('dialog')?.close();

    // "Library" goes back in history when we came from it, so the list keeps its filters and scroll position.
    const back = target.closest('a[data-back]');
    if (back && document.referrer) {
      const from = new URL(document.referrer);
      if (from.origin === location.origin && from.pathname === '/' && history.length > 1) {
        e.preventDefault();
        history.back();
      }
    }
  });

  // Clicking the dialog backdrop closes it.
  document.addEventListener('click', (e) => {
    if (e.target instanceof HTMLDialogElement && e.target.open) {
      const r = e.target.getBoundingClientRect();
      const inside = e.clientX >= r.left && e.clientX <= r.right && e.clientY >= r.top && e.clientY <= r.bottom;
      if (!inside) e.target.close();
    }
  });

  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && document.body.classList.contains('filters-open')) setFiltersOpen(false);
    // "/" jumps to the search box, as on many sites.
    if (e.key === '/' && !e.ctrlKey && !e.metaKey && !e.altKey) {
      const active = document.activeElement;
      const typing = active && (active.tagName === 'INPUT' || active.tagName === 'TEXTAREA' || active.tagName === 'SELECT' || active.isContentEditable);
      const search = $('#q');
      if (!typing && search) {
        e.preventDefault();
        search.focus();
        search.select();
      }
    }
  });

  // Tag search inside the filters: hides tags that don't match (client-side; nothing is submitted).
  document.addEventListener('input', (e) => {
    if (!(e.target instanceof HTMLInputElement) || e.target.id !== 'tag-search') return;
    const list = $('#tag-list');
    if (!list) return;
    const needle = e.target.value.trim().toLowerCase();
    list.classList.toggle('searching', needle.length > 0);
    list.querySelectorAll('.chip').forEach((chip) => {
      chip.classList.toggle('no-match', needle.length > 0 && !chip.dataset.name.includes(needle));
    });
  });

  // A bare "/" opens the saved view; record which one it was, so Back returns to it rather than to
  // whatever the saved view is by then.
  const library = $('#library');
  if (library?.dataset.url && location.pathname === '/' && !location.search) {
    history.replaceState(history.state, '', library.dataset.url);
  }

  // ---- Live sync status (server-sent events) ----

  const syncBar = () => $('#sync');
  let source = null;
  let last = null;
  let shownErrorId = Number(syncBar()?.dataset.errorId || 0);

  function renderSync(s) {
    const bar = syncBar();
    if (!bar) return;
    bar.hidden = !s.running && !bar.querySelector('.refresh-list');
    bar.dataset.running = String(s.running);
    $('.sync-label', bar).textContent = s.label;
    const track = $('.bar', bar);
    track.classList.toggle('indeterminate', !(s.total > 0));
    $('span', track).style.width = s.total > 0 ? `${Math.round((s.done * 100) / s.total)}%` : '0%';
    const syncNow = $('#sync-now');
    if (syncNow) syncNow.disabled = s.running;
    const status = $('#settings-sync-status');
    if (status && s.running) status.textContent = `${s.label}…`;
  }

  /** New data arrived (a sync phase finished): refresh the list when that won't disturb the user. */
  function libraryChanged() {
    const form = $('#library');
    if (!form) return;
    const busy = document.body.classList.contains('filters-open') || document.querySelector('dialog[open]');
    if (window.scrollY < 600 && !busy) {
      // The address bar holds the canonical view, so the URL alone describes what to fetch.
      htmx.ajax('GET', location.pathname + location.search, {
        target: '#results', select: '#results', swap: 'outerHTML',
        headers: { 'X-SteamShelf-Refresh': '1' },
      });
      return;
    }
    const bar = syncBar();
    if (bar && !bar.querySelector('.refresh-list')) {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'link-btn refresh-list';
      button.textContent = 'New data — refresh the list';
      button.addEventListener('click', () => location.reload());
      bar.append(button);
      bar.hidden = false;
    }
  }

  function onSync(event) {
    const s = JSON.parse(event.data);
    renderSync(s);
    if (last && last.running && (!s.running || last.phase !== s.phase)) libraryChanged();
    if (s.error && s.errorId > shownErrorId) toast(s.error, 'error');
    shownErrorId = Math.max(shownErrorId, s.errorId);
    // The settings page shows when the library last synced; redraw it unless the user is typing there.
    const typingInSettings = document.activeElement?.closest?.('#settings form');
    if (last && last.running && !s.running && $('#settings-sync-status') && !typingInSettings) {
      htmx.ajax('GET', '/settings', { target: '#settings', select: '#settings', swap: 'outerHTML' });
    }
    last = s;
  }

  function connect() {
    if (!syncBar() || source) return;
    source = new EventSource('/events');
    source.addEventListener('sync', onSync);
  }

  function disconnect() {
    source?.close();
    source = null;
  }

  // Closed while the page is hidden in the back/forward cache, reopened when it comes back.
  window.addEventListener('pagehide', disconnect);
  window.addEventListener('pageshow', connect);
  connect();
})();
