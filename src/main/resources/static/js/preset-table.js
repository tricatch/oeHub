// Shared by ua-preset and url-preset tables (settings.pebble, my-setting.pebble, setup.pebble) —
// the two only ever differed by field/id/class naming, driven here entirely by `prefix`.
// Relies on escHtml() from util.js, which every page loading this file already includes.
function initPresetTable(prefix, opts) {
  const idKey    = prefix + 'Id';
  const nameKey  = prefix + 'Name';
  const valueKey = prefix + 'Value';

  const apiBase         = opts.apiBase;
  const deleteTooltip    = opts.deleteTooltipKey;
  const savedToastKey    = opts.savedToastKey;
  const deletedToastKey  = opts.deletedToastKey;
  const onDirtyChange    = typeof opts.onDirtyChange === 'function' ? opts.onDirtyChange : () => {};
  const listApiBase      = opts.listApiBase || apiBase;
  const showGlobal       = !!opts.listApiBase;

  const tbody    = document.getElementById(prefix + 'PresetBody');
  const emptyRow = document.getElementById(prefix + 'EmptyRow');
  let presets = [];

  function renderRows() {
    tbody.querySelectorAll('tr.' + prefix + '-row').forEach(r => r.remove());
    const globalList = showGlobal ? presets.filter(p => !p.mine) : [];
    const mineList    = showGlobal ? presets.filter(p => p.mine) : presets;
    if (globalList.length === 0 && mineList.length === 0) { emptyRow.style.display = ''; return; }
    emptyRow.style.display = 'none';

    globalList.forEach(p => {
      const tr = document.createElement('tr');
      tr.className = prefix + '-row ' + prefix + '-row-readonly';
      tr.innerHTML = `
        <td class="${prefix}-cell-name"><span class="${prefix}-view-name">${escHtml(p[nameKey])}</span><span class="oe-icon oe-icon-admin ${prefix}-admin-icon" style="width:12px;height:12px"></span></td>
        <td class="${prefix}-cell-value"><span class="${prefix}-view-value" title="${escHtml(p[valueKey])}">${escHtml(p[valueKey])}</span></td>
        <td></td>`;
      tbody.insertBefore(tr, emptyRow);
    });

    mineList.forEach(p => {
      const tr = document.createElement('tr');
      tr.className = prefix + '-row';
      tr.dataset[idKey] = p[idKey];
      tr.innerHTML = `
        <td class="${prefix}-cell-name">
          <span class="${prefix}-view-name">${escHtml(p[nameKey])}</span>
          <input type="text" class="form-control form-control-sm ${prefix}-edit-name d-none" value="${escHtml(p[nameKey])}">
        </td>
        <td class="${prefix}-cell-value">
          <span class="${prefix}-view-value" title="${escHtml(p[valueKey])}">${escHtml(p[valueKey])}</span>
          <input type="text" class="form-control form-control-sm ${prefix}-edit-value d-none" value="${escHtml(p[valueKey])}">
        </td>
        <td class="text-end text-nowrap">
          <button class="btn btn-sm btn-oe-icon ${prefix}-btn-edit" title="${escHtml(MSG['tooltip.' + prefix + '.edit'])}">
            <span class="oe-icon oe-icon-edit" style="width:13px;height:13px"></span>
          </button>
          <button class="btn btn-sm btn-oe-icon ${prefix}-btn-delete" title="${escHtml(MSG[deleteTooltip])}">
            <span class="oe-icon oe-icon-delete" style="width:13px;height:13px"></span>
          </button>
          <button class="btn btn-sm btn-oe-icon ${prefix}-btn-save d-none" title="${escHtml(MSG['btn.save'])}">
            <span class="oe-icon oe-icon-save" style="width:13px;height:13px"></span>
          </button>
          <button class="btn btn-sm btn-oe-icon ${prefix}-btn-cancel d-none" title="${escHtml(MSG['btn.cancel'])}">
            <span class="oe-icon oe-icon-cancel" style="width:13px;height:13px"></span>
          </button>
        </td>`;
      tr.querySelector('.' + prefix + '-btn-edit').addEventListener('click', () => startEdit(tr));
      tr.querySelector('.' + prefix + '-btn-save').addEventListener('click', () => saveEdit(tr, p));
      tr.querySelector('.' + prefix + '-btn-cancel').addEventListener('click', () => cancelEdit(tr));
      tr.querySelector('.' + prefix + '-btn-delete').addEventListener('click', () => deletePreset(p[idKey]));
      tr.draggable = true;
      tr.addEventListener('dragstart', e => { e.dataTransfer.setData('text/plain', p[idKey]); });
      tr.addEventListener('dragover', e => { e.preventDefault(); tr.classList.add('drag-over'); });
      tr.addEventListener('dragleave', () => tr.classList.remove('drag-over'));
      tr.addEventListener('drop', e => {
        e.preventDefault();
        tr.classList.remove('drag-over');
        const srcId = e.dataTransfer.getData('text/plain');
        reorder(srcId, p[idKey]);
      });
      tbody.insertBefore(tr, emptyRow);
    });
  }

  async function reorder(srcId, targetId) {
    if (srcId === targetId) return;
    const srcIdx = presets.findIndex(x => x[idKey] === srcId);
    const tgtIdx = presets.findIndex(x => x[idKey] === targetId);
    if (srcIdx === -1 || tgtIdx === -1) return;
    const [moved] = presets.splice(srcIdx, 1);
    presets.splice(tgtIdx, 0, moved);
    renderRows();
    const mineIds = (showGlobal ? presets.filter(p => p.mine) : presets).map(p => p[idKey]);
    try {
      const res = await fetch(apiBase + '/order', {
        method: 'PUT', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify(mineIds)
      });
      if (!res.ok) showToast(MSG['toast.save.failed']);
    } catch (e) {
      showToast(MSG['toast.save.failed']);
    }
  }

  function startEdit(tr) {
    tr.querySelector('.' + prefix + '-view-name').classList.add('d-none');
    tr.querySelector('.' + prefix + '-view-value').classList.add('d-none');
    tr.querySelector('.' + prefix + '-edit-name').classList.remove('d-none');
    tr.querySelector('.' + prefix + '-edit-value').classList.remove('d-none');
    tr.querySelector('.' + prefix + '-btn-edit').classList.add('d-none');
    tr.querySelector('.' + prefix + '-btn-delete').classList.add('d-none');
    tr.querySelector('.' + prefix + '-btn-save').classList.remove('d-none');
    tr.querySelector('.' + prefix + '-btn-cancel').classList.remove('d-none');
    onDirtyChange(true);
  }

  function cancelEdit(tr) {
    tr.querySelector('.' + prefix + '-view-name').classList.remove('d-none');
    tr.querySelector('.' + prefix + '-view-value').classList.remove('d-none');
    tr.querySelector('.' + prefix + '-edit-name').classList.add('d-none');
    tr.querySelector('.' + prefix + '-edit-value').classList.add('d-none');
    tr.querySelector('.' + prefix + '-btn-edit').classList.remove('d-none');
    tr.querySelector('.' + prefix + '-btn-delete').classList.remove('d-none');
    tr.querySelector('.' + prefix + '-btn-save').classList.add('d-none');
    tr.querySelector('.' + prefix + '-btn-cancel').classList.add('d-none');
    onDirtyChange(false);
  }

  async function saveEdit(tr, p) {
    const name  = tr.querySelector('.' + prefix + '-edit-name').value.trim();
    const value = tr.querySelector('.' + prefix + '-edit-value').value.trim();
    if (!name || !value) return;
    try {
      const res = await fetch(apiBase + '/' + p[idKey], {
        method: 'PATCH', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({[nameKey]: name, [valueKey]: value})
      });
      if (res.ok) {
        const updated = await res.json();
        const idx = presets.findIndex(x => x[idKey] === p[idKey]);
        if (idx >= 0) presets[idx] = updated;
        renderRows();
        showToast(MSG[savedToastKey]);
        onDirtyChange(false);
      } else {
        showToast(MSG['toast.save.failed']);
      }
    } catch (e) {
      showToast(MSG['toast.save.failed']);
    }
  }

  async function deletePreset(id) {
    try {
      const res = await fetch(apiBase + '/' + id, {method: 'DELETE'});
      if (res.ok || res.status === 204) {
        presets = presets.filter(p => p[idKey] !== id);
        renderRows();
        showToast(MSG[deletedToastKey]);
      } else {
        showToast(MSG['toast.save.failed']);
      }
    } catch (e) {
      showToast(MSG['toast.save.failed']);
    }
  }

  const Prefix = prefix.charAt(0).toUpperCase() + prefix.slice(1);
  document.getElementById('btnAdd' + Prefix + 'Preset').addEventListener('click', () => {
    const tr = document.createElement('tr');
    tr.className = prefix + '-row ' + prefix + '-row-new';
    tr.innerHTML = `
      <td><input type="text" class="form-control form-control-sm ${prefix}-new-name" placeholder="${escHtml(MSG['settings.' + prefix + '.name.placeholder'])}"></td>
      <td><input type="text" class="form-control form-control-sm ${prefix}-new-value" placeholder="${escHtml(MSG['settings.' + prefix + '.value.placeholder'])}"></td>
      <td class="text-end text-nowrap">
        <button class="btn btn-sm btn-oe-icon ${prefix}-btn-create" title="${escHtml(MSG['btn.save'])}">
          <span class="oe-icon oe-icon-save" style="width:13px;height:13px"></span>
        </button>
        <button class="btn btn-sm btn-oe-icon ${prefix}-btn-new-cancel" title="${escHtml(MSG['btn.cancel'])}">
          <span class="oe-icon oe-icon-cancel" style="width:13px;height:13px"></span>
        </button>
      </td>`;
    tr.querySelector('.' + prefix + '-btn-create').addEventListener('click', async () => {
      const name  = tr.querySelector('.' + prefix + '-new-name').value.trim();
      const value = tr.querySelector('.' + prefix + '-new-value').value.trim();
      if (!name || !value) return;
      try {
        const res = await fetch(apiBase, {
          method: 'POST', headers: {'Content-Type': 'application/json'},
          body: JSON.stringify({[nameKey]: name, [valueKey]: value})
        });
        if (res.ok || res.status === 201) {
          const created = await res.json();
          presets.push(created);
          tr.remove();
          renderRows();
          showToast(MSG[savedToastKey]);
          onDirtyChange(false);
        } else {
          showToast(MSG['toast.save.failed']);
        }
      } catch (e) {
        showToast(MSG['toast.save.failed']);
      }
    });
    tr.querySelector('.' + prefix + '-btn-new-cancel').addEventListener('click', () => { tr.remove(); onDirtyChange(false); });
    emptyRow.style.display = 'none';
    const firstMineRow = showGlobal ? tbody.querySelector('tr.' + prefix + '-row:not(.' + prefix + '-row-readonly)') : null;
    tbody.insertBefore(tr, firstMineRow || emptyRow);
    tr.querySelector('.' + prefix + '-new-name').focus();
    onDirtyChange(true);
  });

  (async () => {
    try {
      const res = await fetch(listApiBase);
      if (res.ok) { presets = await res.json(); renderRows(); }
      else showToast(MSG['toast.load.failed']);
    } catch (e) {
      showToast(MSG['toast.load.failed']);
    }
  })();
}

function initUaPresetTable(opts) { return initPresetTable('ua', opts); }
function initUrlPresetTable(opts) { return initPresetTable('url', opts); }
