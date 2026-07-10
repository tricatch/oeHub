function crc32(str) {
  if (!crc32._t) {
    crc32._t = new Uint32Array(256);
    for (let i = 0; i < 256; i++) {
      let c = i;
      for (let j = 0; j < 8; j++) c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
      crc32._t[i] = c;
    }
  }
  let crc = 0xFFFFFFFF;
  for (let i = 0; i < str.length; i++) crc = crc32._t[(crc ^ str.charCodeAt(i)) & 0xFF] ^ (crc >>> 8);
  return (crc ^ 0xFFFFFFFF) >>> 0;
}

function escHtml(s) {
  return String(s ?? '').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}

function fmtDate(d) {
  if (!d) return '';
  if (Array.isArray(d)) return `${d[0]}-${String(d[1]).padStart(2,'0')}-${String(d[2]).padStart(2,'0')}`;
  return String(d).slice(0, 10);
}

function fmtDateTime(d) {
  if (!d) return '';
  if (Array.isArray(d)) return `${d[0]}-${String(d[1]).padStart(2,'0')}-${String(d[2]).padStart(2,'0')} ${String(d[3]||0).padStart(2,'0')}:${String(d[4]||0).padStart(2,'0')}`;
  return String(d).slice(0, 16).replace('T', ' ');
}

function parseHostsToMap(content) {
  const map = {};
  content.split('\n').forEach(line => {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) return;
    const parts = trimmed.split(/\s+/);
    if (parts.length >= 2 && !map[parts[1]]) map[parts[1]] = parts[0];
  });
  return map;
}

function wrapDelayedUrl(url) {
  return `data:text/html,<script>setTimeout(()=>location.replace('${url}'),1000)</script>`;
}

function winArgQuote(p) {
  return `'${p.replace(/'/g, "''")}'`;
}

function macArgQuote(p) {
  return /[<>()']/.test(p) ? '"' + p.replace(/([\\$`"])/g, '\\$1') + '"' : p;
}

function resolveUDD(path, os) {
  const home = os === 'win' ? '%USERPROFILE%' : '$HOME';
  const p = path.replace(/\$\{USERHOME\}/g, home);
  return os === 'win' ? p.replace(/\//g, '\\') : p.replace(/\\/g, '/');
}

function copyToClipboard(text) {
  if (navigator.clipboard && navigator.clipboard.writeText) {
    return navigator.clipboard.writeText(text);
  }
  // navigator.clipboard is unavailable outside secure contexts (plain http on a non-localhost host).
  return new Promise((resolve, reject) => {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.focus();
    ta.select();
    try {
      const ok = document.execCommand('copy');
      document.body.removeChild(ta);
      if (ok) resolve(); else reject(new Error('execCommand copy failed'));
    } catch (err) {
      document.body.removeChild(ta);
      reject(err);
    }
  });
}

function toBase64Url(str) {
  return btoa(str).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function fromBase64Url(str) {
  return atob(str.replace(/-/g, '+').replace(/_/g, '/'));
}

function clearSearch() {
  document.getElementById('searchResults').innerHTML = '';
  document.getElementById('searchInput').value = '';
}

const OE_FEEDBACK_DURATION = 2500;

function showToast(msg) {
  const t = document.getElementById('toast');
  t.querySelector('.oe-toast-msg').textContent = msg;
  t.classList.add('show');
  setTimeout(() => t.classList.remove('show'), OE_FEEDBACK_DURATION);
}

function flashCopyFeedback(btn, label = '') {
  btn.innerHTML = `<span class="oe-icon oe-icon-clipboard-copy" style="width:13px;height:13px;color:#4a7a1e"></span>${label}`;
  setTimeout(() => {
    btn.innerHTML = `<span class="oe-icon oe-icon-copy" style="width:13px;height:13px"></span>${label}`;
  }, OE_FEEDBACK_DURATION);
}

function initGuideModal(modalId, checkboxId, storageKey) {
  const modal = document.getElementById(modalId);
  if (!modal) return;
  modal.addEventListener('show.bs.modal', () => {
    document.getElementById(checkboxId).checked = localStorage.getItem(storageKey) === 'true';
  });
  modal.addEventListener('hide.bs.modal', () => {
    if (document.getElementById(checkboxId).checked) {
      localStorage.setItem(storageKey, 'true');
    } else {
      localStorage.removeItem(storageKey);
    }
  });
  if (localStorage.getItem(storageKey) !== 'true') {
    bootstrap.Modal.getOrCreateInstance(modal).show();
  }
}

function initWidthToggle(appEl, btns, onChange) {
  if (!Array.isArray(btns)) btns = [btns];
  function applyExpanded(expanded, isInit) {
    appEl.classList.toggle('expanded', expanded);
    document.body.classList.toggle('oe-expanded', expanded);
    btns.forEach(btn => {
      btn.querySelectorAll('.icon-width-expand').forEach(el => el.style.display = expanded ? 'none' : '');
      btn.querySelectorAll('.icon-width-collapse').forEach(el => el.style.display = expanded ? '' : 'none');
      btn.title = expanded ? MSG['ui.collapse'] : MSG['ui.expand'];
    });
    localStorage.setItem('oeWidthExpanded', expanded);
    if (onChange) onChange(expanded, isInit);
  }
  applyExpanded(localStorage.getItem('oeWidthExpanded') === 'true', true);
  btns.forEach(btn => btn.addEventListener('click', () => applyExpanded(!appEl.classList.contains('expanded'), false)));
}
