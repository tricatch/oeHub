function applyI18n() {
  document.querySelectorAll('[data-i18n]').forEach(el => {
    const msg = chrome.i18n.getMessage(el.dataset.i18n);
    if (msg) el.textContent = msg;
  });
  document.querySelectorAll('[data-i18n-placeholder]').forEach(el => {
    const msg = chrome.i18n.getMessage(el.dataset.i18nPlaceholder);
    if (msg) el.placeholder = msg;
  });
  document.querySelectorAll('[data-i18n-aria-label]').forEach(el => {
    const msg = chrome.i18n.getMessage(el.dataset.i18nAriaLabel);
    if (msg) el.setAttribute('aria-label', msg);
  });
}

function setFieldsDisabled(disabled) {
  document.getElementById('oid').disabled = disabled;
  document.getElementById('domains').disabled = disabled;
  document.getElementById('save').disabled = disabled;
  document.getElementById('oidPreview').classList.toggle('disabled', disabled);
}

function updateOidPreview() {
  const oid = document.getElementById('oid').value.trim();
  document.getElementById('oidPreview').textContent = `X-OeHub-Oid: ${oid}`;
}

document.getElementById('oid').addEventListener('input', updateOidPreview);

async function load() {
  applyI18n();
  const { oid, domains, enabled } = await chrome.storage.local.get(['oid', 'domains', 'enabled']);
  document.getElementById('oid').value = oid || '';
  document.getElementById('domains').value = (domains || []).join('\n');
  const isEnabled = enabled !== false;
  document.getElementById('enabled').checked = isEnabled;
  setFieldsDisabled(!isEnabled);
  updateOidPreview();
}

document.getElementById('enabled').addEventListener('change', async (e) => {
  setFieldsDisabled(!e.target.checked);
  await chrome.storage.local.set({ enabled: e.target.checked });
});

document.getElementById('save').addEventListener('click', async () => {
  const oid = document.getElementById('oid').value.trim();
  const domains = document.getElementById('domains').value
    .split('\n')
    .map(s => s.trim())
    .filter(s => s.length > 0);

  await chrome.storage.local.set({ oid, domains });

  const status = document.getElementById('status');
  status.textContent = chrome.i18n.getMessage('savedStatus');
  setTimeout(() => { status.textContent = ''; }, 1500);
});

load();
