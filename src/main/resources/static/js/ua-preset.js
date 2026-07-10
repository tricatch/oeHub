function initUaPresetTable(opts) {
  const apiBase        = opts.apiBase;
  const deleteTooltip  = opts.deleteTooltipKey;
  const savedToastKey  = opts.savedToastKey;
  const deletedToastKey = opts.deletedToastKey;
  const onDirtyChange   = typeof opts.onDirtyChange === 'function' ? opts.onDirtyChange : () => {};
  const listApiBase     = opts.listApiBase || apiBase;
  const showGlobal      = !!opts.listApiBase;

  const tbody    = document.getElementById('uaPresetBody');
  const emptyRow = document.getElementById('uaEmptyRow');
  let presets = [];

  function escHtml(s) {
    return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
  }

  function renderRows() {
    tbody.querySelectorAll('tr.ua-row').forEach(r => r.remove());
    const globalList = showGlobal ? presets.filter(p => !p.mine) : [];
    const mineList    = showGlobal ? presets.filter(p => p.mine) : presets;
    if (globalList.length === 0 && mineList.length === 0) { emptyRow.style.display = ''; return; }
    emptyRow.style.display = 'none';

    globalList.forEach(p => {
      const tr = document.createElement('tr');
      tr.className = 'ua-row ua-row-readonly';
      tr.innerHTML = `
        <td class="ua-cell-name"><span class="ua-view-name">${escHtml(p.uaName)}</span><span class="oe-icon oe-icon-admin ua-admin-icon" style="width:12px;height:12px"></span></td>
        <td class="ua-cell-value"><span class="ua-view-value" title="${escHtml(p.uaValue)}">${escHtml(p.uaValue)}</span></td>
        <td></td>`;
      tbody.insertBefore(tr, emptyRow);
    });

    mineList.forEach(p => {
      const tr = document.createElement('tr');
      tr.className = 'ua-row';
      tr.dataset.uaId = p.uaId;
      tr.innerHTML = `
        <td class="ua-cell-name">
          <span class="ua-view-name">${escHtml(p.uaName)}</span>
          <input type="text" class="form-control form-control-sm ua-edit-name d-none" value="${escHtml(p.uaName)}">
        </td>
        <td class="ua-cell-value">
          <span class="ua-view-value" title="${escHtml(p.uaValue)}">${escHtml(p.uaValue)}</span>
          <input type="text" class="form-control form-control-sm ua-edit-value d-none" value="${escHtml(p.uaValue)}">
        </td>
        <td class="text-end text-nowrap">
          <button class="btn btn-sm btn-oe-icon ua-btn-edit" title="${escHtml(MSG['tooltip.ua.edit'])}">
            <span class="oe-icon oe-icon-edit" style="width:13px;height:13px"></span>
          </button>
          <button class="btn btn-sm btn-oe-icon ua-btn-delete" title="${escHtml(MSG[deleteTooltip])}">
            <span class="oe-icon oe-icon-delete" style="width:13px;height:13px"></span>
          </button>
          <button class="btn btn-sm btn-oe-icon ua-btn-save d-none" title="${escHtml(MSG['btn.save'])}">
            <span class="oe-icon oe-icon-save" style="width:13px;height:13px"></span>
          </button>
          <button class="btn btn-sm btn-oe-icon ua-btn-cancel d-none" title="${escHtml(MSG['btn.cancel'])}">
            <span class="oe-icon oe-icon-cancel" style="width:13px;height:13px"></span>
          </button>
        </td>`;
      tr.querySelector('.ua-btn-edit').addEventListener('click', () => startEdit(tr));
      tr.querySelector('.ua-btn-save').addEventListener('click', () => saveEdit(tr, p));
      tr.querySelector('.ua-btn-cancel').addEventListener('click', () => cancelEdit(tr));
      tr.querySelector('.ua-btn-delete').addEventListener('click', () => deletePreset(p.uaId));
      tr.draggable = true;
      tr.addEventListener('dragstart', e => { e.dataTransfer.setData('text/plain', p.uaId); });
      tr.addEventListener('dragover', e => { e.preventDefault(); tr.classList.add('drag-over'); });
      tr.addEventListener('dragleave', () => tr.classList.remove('drag-over'));
      tr.addEventListener('drop', e => {
        e.preventDefault();
        tr.classList.remove('drag-over');
        const srcId = e.dataTransfer.getData('text/plain');
        reorder(srcId, p.uaId);
      });
      tbody.insertBefore(tr, emptyRow);
    });
  }

  async function reorder(srcId, targetId) {
    if (srcId === targetId) return;
    const srcIdx = presets.findIndex(x => x.uaId === srcId);
    const tgtIdx = presets.findIndex(x => x.uaId === targetId);
    if (srcIdx === -1 || tgtIdx === -1) return;
    const [moved] = presets.splice(srcIdx, 1);
    presets.splice(tgtIdx, 0, moved);
    renderRows();
    const mineIds = (showGlobal ? presets.filter(p => p.mine) : presets).map(p => p.uaId);
    await fetch(apiBase + '/order', {
      method: 'PUT', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify(mineIds)
    });
  }

  function startEdit(tr) {
    tr.querySelector('.ua-view-name').classList.add('d-none');
    tr.querySelector('.ua-view-value').classList.add('d-none');
    tr.querySelector('.ua-edit-name').classList.remove('d-none');
    tr.querySelector('.ua-edit-value').classList.remove('d-none');
    tr.querySelector('.ua-btn-edit').classList.add('d-none');
    tr.querySelector('.ua-btn-delete').classList.add('d-none');
    tr.querySelector('.ua-btn-save').classList.remove('d-none');
    tr.querySelector('.ua-btn-cancel').classList.remove('d-none');
    onDirtyChange(true);
  }

  function cancelEdit(tr) {
    tr.querySelector('.ua-view-name').classList.remove('d-none');
    tr.querySelector('.ua-view-value').classList.remove('d-none');
    tr.querySelector('.ua-edit-name').classList.add('d-none');
    tr.querySelector('.ua-edit-value').classList.add('d-none');
    tr.querySelector('.ua-btn-edit').classList.remove('d-none');
    tr.querySelector('.ua-btn-delete').classList.remove('d-none');
    tr.querySelector('.ua-btn-save').classList.add('d-none');
    tr.querySelector('.ua-btn-cancel').classList.add('d-none');
    onDirtyChange(false);
  }

  async function saveEdit(tr, p) {
    const uaName  = tr.querySelector('.ua-edit-name').value.trim();
    const uaValue = tr.querySelector('.ua-edit-value').value.trim();
    if (!uaName || !uaValue) return;
    const res = await fetch(apiBase + '/' + p.uaId, {
      method: 'PATCH', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({uaName, uaValue})
    });
    if (res.ok) {
      const updated = await res.json();
      const idx = presets.findIndex(x => x.uaId === p.uaId);
      if (idx >= 0) presets[idx] = updated;
      renderRows();
      showToast(MSG[savedToastKey]);
      onDirtyChange(false);
    }
  }

  async function deletePreset(uaId) {
    const res = await fetch(apiBase + '/' + uaId, {method: 'DELETE'});
    if (res.ok || res.status === 204) {
      presets = presets.filter(p => p.uaId !== uaId);
      renderRows();
      showToast(MSG[deletedToastKey]);
    }
  }

  document.getElementById('btnAddUaPreset').addEventListener('click', () => {
    const tr = document.createElement('tr');
    tr.className = 'ua-row ua-row-new';
    tr.innerHTML = `
      <td><input type="text" class="form-control form-control-sm ua-new-name" placeholder="${escHtml(MSG['settings.ua.name.placeholder'])}"></td>
      <td><input type="text" class="form-control form-control-sm ua-new-value" placeholder="${escHtml(MSG['settings.ua.value.placeholder'])}"></td>
      <td class="text-end text-nowrap">
        <button class="btn btn-sm btn-oe-icon ua-btn-create" title="${escHtml(MSG['btn.save'])}">
          <span class="oe-icon oe-icon-save" style="width:13px;height:13px"></span>
        </button>
        <button class="btn btn-sm btn-oe-icon ua-btn-new-cancel" title="${escHtml(MSG['btn.cancel'])}">
          <span class="oe-icon oe-icon-cancel" style="width:13px;height:13px"></span>
        </button>
      </td>`;
    tr.querySelector('.ua-btn-create').addEventListener('click', async () => {
      const uaName  = tr.querySelector('.ua-new-name').value.trim();
      const uaValue = tr.querySelector('.ua-new-value').value.trim();
      if (!uaName || !uaValue) return;
      const res = await fetch(apiBase, {
        method: 'POST', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({uaName, uaValue})
      });
      if (res.ok || res.status === 201) {
        const created = await res.json();
        presets.push(created);
        tr.remove();
        renderRows();
        showToast(MSG[savedToastKey]);
        onDirtyChange(false);
      }
    });
    tr.querySelector('.ua-btn-new-cancel').addEventListener('click', () => { tr.remove(); onDirtyChange(false); });
    emptyRow.style.display = 'none';
    const firstMineRow = showGlobal ? tbody.querySelector('tr.ua-row:not(.ua-row-readonly)') : null;
    tbody.insertBefore(tr, firstMineRow || emptyRow);
    tr.querySelector('.ua-new-name').focus();
    onDirtyChange(true);
  });

  (async () => {
    const res = await fetch(listApiBase);
    if (res.ok) { presets = await res.json(); renderRows(); }
  })();
}
