(() => {
  if (window.__hypurrInstalled) return;
  window.__hypurrInstalled = true;
  const sel = (el) => {
    if (el.id) return '#' + CSS.escape(el.id);
    const parts = [];
    let n = el;
    while (n && n.nodeType === 1 && parts.length < 4) {
      const t = n.getAttribute('data-testid');
      if (t) { parts.unshift('[data-testid="' + t + '"]'); break; }
      let s = n.tagName.toLowerCase();
      const p = n.parentElement;
      if (p) {
        const same = Array.from(p.children).filter((c) => c.tagName === n.tagName);
        if (same.length > 1) s += ':nth-of-type(' + (same.indexOf(n) + 1) + ')';
      }
      parts.unshift(s);
      n = p;
    }
    return parts.join(' > ');
  };
  document.addEventListener('click', (e) => {
    const t = e.target;
    const el = (t.closest && t.closest('button,a,[role=button],input,select,textarea,summary,label,[onclick],[role=tab],[role=link],[tabindex]:not([tabindex="-1"])')) || t;
    window.__hypurrLast = el;
    window.__hypurrBefore = document.body ? document.body.innerText : '';
    const label = (el.getAttribute('aria-label') || el.innerText || el.value || el.title || '').trim().replace(/\s+/g, ' ').slice(0, 80);
    try {
      window.__hypurrClick(JSON.stringify({ label, tag: el.tagName.toLowerCase(), role: el.getAttribute('role'), selector: sel(el), page: location.pathname }));
    } catch (_) {}
  }, true);
})();
