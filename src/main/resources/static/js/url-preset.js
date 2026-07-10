function initUrlPresetTable(opts) {
  const apiBase        = opts.apiBase;
  const deleteTooltip  = opts.deleteTooltipKey;
  const savedToastKey  = opts.savedToastKey;
  const deletedToastKey = opts.deletedToastKey;
  const onDirtyChange   = typeof opts.onDirtyChange === 'function' ? opts.onDirtyChange : () => {};
  const listApiBase     = opts.listApiBase || apiBase;
  const showGlobal      = !!opts.listApiBase;

  const tbody    = document.getElementById('urlPresetBody');
  const emptyRow = document.getElementById('urlEmptyRow');
  let presets = [];

  function escHtml(s) {
    return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
  }

  function renderRows() {
    tbody.querySelectorAll('tr.url-row').forEach(r => r.remove());
    const globalList = showGlobal ? presets.filter(p => !p.mine) : [];
    const mineList    = showGlobal ? presets.filter(p => p.mine) : presets;
    if (globalList.length === 0 && mineList.length === 0) { emptyRow.style.display = ''; return; }
    emptyRow.style.display = 'none';

    globalList.forEach(p => {
      const tr = document.createElement('tr');
      tr.className = 'url-row url-row-readonly';
      tr.innerHTML = `
        <td class="url-cell-name"><span class="url-view-name">${escHtml(p.urlName)}</span><span class="oe-icon oe-icon-admin url-admin-icon" style="width:12px;height:12px"></span></td>
        <td class="url-cell-value"><span class="url-view-value" title="${escHtml(p.urlValue)}">${escHtml(p.urlValue)}</span></td>
        <td></td>`;
      tbody.insertBefore(tr, emptyRow);
    });

    mineList.forEach(p => {
      const tr = document.createElement('tr');
      tr.className = 'url-row';
      tr.dataset.urlId = p.urlId;
      tr.innerHTML = `
        <td class="url-cell-name">
          <span class="url-view-name">${escHtml(p.urlName)}</span>
          <input type="text" class="form-control form-control-sm url-edit-name d-none" value="${escHtml(p.urlName)}">
        </td>
        <td class="url-cell-value">
          <span class="url-view-value" title="${escHtml(p.urlValue)}">${escHtml(p.urlValue)}</span>
          <input type="text" class="form-control form-control-sm url-edit-value d-none" value="${escHtml(p.urlValue)}">
        </td>
        <td class="text-end text-nowrap">
          <button class="btn btn-sm btn-oe-icon url-btn-edit" title="${escHtml(MSG['tooltip.url.edit'])}">
            <span class="oe-icon oe-icon-edit" style="width:13px;height:13px"></span>
          </button>
          <button class="btn btn-sm btn-oe-icon url-btn-delete" title="${escHtml(MSG[deleteTooltip])}">
            <span class="oe-icon oe-icon-delete" style="width:13px;height:13px"></span>
          </button>
          <button class="btn btn-sm btn-oe-icon url-btn-save d-none" title="${escHtml(MSG['btn.save'])}">
            <span class="oe-icon oe-icon-save" style="width:13px;height:13px"></span>
          </button>
          <button class="btn btn-sm btn-oe-icon url-btn-cancel d-none" title="${escHtml(MSG['btn.cancel'])}">
            <span class="oe-icon oe-icon-cancel" style="width:13px;height:13px"></span>
          </button>
        </td>`;
      tr.querySelector('.url-btn-edit').addEventListener('click', () => startEdit(tr));
      tr.querySelector('.url-btn-save').addEventListener('click', () => saveEdit(tr, p));
      tr.querySelector('.url-btn-cancel').addEventListener('click', () => cancelEdit(tr));
      tr.querySelector('.url-btn-delete').addEventListener('click', () => deletePreset(p.urlId));
      tr.draggable = true;
      tr.addEventListener('dragstart', e => { e.dataTransfer.setData('text/plain', p.urlId); });
      tr.addEventListener('dragover', e => { e.preventDefault(); tr.classList.add('drag-over'); });
      tr.addEventListener('dragleave', () => tr.classList.remove('drag-over'));
      tr.addEventListener('drop', e => {
        e.preventDefault();
        tr.classList.remove('drag-over');
        const srcId = e.dataTransfer.getData('text/plain');
        reorder(srcId, p.urlId);
      });
      tbody.insertBefore(tr, emptyRow);
    });
  }

  async function reorder(srcId, targetId) {
    if (srcId === targetId) return;
    const srcIdx = presets.findIndex(x => x.urlId === srcId);
    const tgtIdx = presets.findIndex(x => x.urlId === targetId);
    if (srcIdx === -1 || tgtIdx === -1) return;
    const [moved] = presets.splice(srcIdx, 1);
    presets.splice(tgtIdx, 0, moved);
    renderRows();
    const mineIds = (showGlobal ? presets.filter(p => p.mine) : presets).map(p => p.urlId);
    await fetch(apiBase + '/order', {
      method: 'PUT', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify(mineIds)
    });
  }

  function startEdit(tr) {
    tr.querySelector('.url-view-name').classList.add('d-none');
    tr.querySelector('.url-view-value').classList.add('d-none');
    tr.querySelector('.url-edit-name').classList.remove('d-none');
    tr.querySelector('.url-edit-value').classList.remove('d-none');
    tr.querySelector('.url-btn-edit').classList.add('d-none');
    tr.querySelector('.url-btn-delete').classList.add('d-none');
    tr.querySelector('.url-btn-save').classList.remove('d-none');
    tr.querySelector('.url-btn-cancel').classList.remove('d-none');
    onDirtyChange(true);
  }

  function cancelEdit(tr) {
    tr.querySelector('.url-view-name').classList.remove('d-none');
    tr.querySelector('.url-view-value').classList.remove('d-none');
    tr.querySelector('.url-edit-name').classList.add('d-none');
    tr.querySelector('.url-edit-value').classList.add('d-none');
    tr.querySelector('.url-btn-edit').classList.remove('d-none');
    tr.querySelector('.url-btn-delete').classList.remove('d-none');
    tr.querySelector('.url-btn-save').classList.add('d-none');
    tr.querySelector('.url-btn-cancel').classList.add('d-none');
    onDirtyChange(false);
  }

  async function saveEdit(tr, p) {
    const urlName  = tr.querySelector('.url-edit-name').value.trim();
    const urlValue = tr.querySelector('.url-edit-value').value.trim();
    if (!urlName || !urlValue) return;
    const res = await fetch(apiBase + '/' + p.urlId, {
      method: 'PATCH', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({urlName, urlValue})
    });
    if (res.ok) {
      const updated = await res.json();
      const idx = presets.findIndex(x => x.urlId === p.urlId);
      if (idx >= 0) presets[idx] = updated;
      renderRows();
      showToast(MSG[savedToastKey]);
      onDirtyChange(false);
    }
  }

  async function deletePreset(urlId) {
    const res = await fetch(apiBase + '/' + urlId, {method: 'DELETE'});
    if (res.ok || res.status === 204) {
      presets = presets.filter(p => p.urlId !== urlId);
      renderRows();
      showToast(MSG[deletedToastKey]);
    }
  }

  document.getElementById('btnAddUrlPreset').addEventListener('click', () => {
    const tr = document.createElement('tr');
    tr.className = 'url-row url-row-new';
    tr.innerHTML = `
      <td><input type="text" class="form-control form-control-sm url-new-name" placeholder="${escHtml(MSG['settings.url.name.placeholder'])}"></td>
      <td><input type="text" class="form-control form-control-sm url-new-value" placeholder="${escHtml(MSG['settings.url.value.placeholder'])}"></td>
      <td class="text-end text-nowrap">
        <button class="btn btn-sm btn-oe-icon url-btn-create" title="${escHtml(MSG['btn.save'])}">
          <span class="oe-icon oe-icon-save" style="width:13px;height:13px"></span>
        </button>
        <button class="btn btn-sm btn-oe-icon url-btn-new-cancel" title="${escHtml(MSG['btn.cancel'])}">
          <span class="oe-icon oe-icon-cancel" style="width:13px;height:13px"></span>
        </button>
      </td>`;
    tr.querySelector('.url-btn-create').addEventListener('click', async () => {
      const urlName  = tr.querySelector('.url-new-name').value.trim();
      const urlValue = tr.querySelector('.url-new-value').value.trim();
      if (!urlName || !urlValue) return;
      const res = await fetch(apiBase, {
        method: 'POST', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({urlName, urlValue})
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
    tr.querySelector('.url-btn-new-cancel').addEventListener('click', () => { tr.remove(); onDirtyChange(false); });
    emptyRow.style.display = 'none';
    const firstMineRow = showGlobal ? tbody.querySelector('tr.url-row:not(.url-row-readonly)') : null;
    tbody.insertBefore(tr, firstMineRow || emptyRow);
    tr.querySelector('.url-new-name').focus();
    onDirtyChange(true);
  });

  (async () => {
    const res = await fetch(listApiBase);
    if (res.ok) { presets = await res.json(); renderRows(); }
  })();
}
