// Shared by the oeHosts (hosts.pebble) and oeProxy (proxy.pebble) sidebars: the item list as a
// one-level tree. Folders and the items outside them are interleaved in one saved order (a
// folder's items follow it), which is also the server's list order (LIST_ORDER in the mappers)
// and so the merge order of the selected items. The page keeps its item array and what clicking,
// toggling and renaming an item does; this renders the tree and owns folders and drag & drop.
// Relies on escHtml() and showToast() from util.js.

/**
 * @param opts.container     the list element
 * @param opts.apiBase       '/api/hosts' or '/api/proxy/vhosts'
 * @param opts.idKey         the item id field: 'hostsId' or 'vhostId'
 * @param opts.nameKey       the item name field: 'hostsProfile' or 'vhostProfile'
 * @param opts.getItems      () => the page's item array
 * @param opts.setItems      (items) => replaces the page's item array (same objects, new order)
 * @param opts.rerender      () => the page's full sidebar render, which calls render() below
 * @param opts.isActive      (item) => whether it is the one open in the editor
 * @param opts.publicLinkTitle (item) => tooltip when the item has a live public link, else null
 * @param opts.onActivate    (item) => open it in the editor
 * @param opts.onToggle      (item) => Promise; turn it on/off (updates item.selected)
 * @param opts.onRename      (nameEl, item) => start inline rename; omitted for shared rows
 * @param opts.canRename     (item) => whether the name can be renamed here
 */
function createListFolders(opts) {
  const { container, apiBase, idKey, nameKey } = opts;
  let folders = [];
  let drag = null; // {type: 'item' | 'folder', id}
  container.classList.add('oe-tree');

  async function load() {
    const res = await fetch(apiBase + '/folders');
    folders = res.ok ? await res.json() : [];
  }

  // ── Tree model ─────────────────────────────────────────────
  // [{type: 'folder', folder, children: [item]}, {type: 'item', item}] in display order, built
  // from the saved sortOrder values. An item whose folder is gone shows at the root.
  function buildTree() {
    const items = opts.getItems();
    const byId = new Map(folders.map(f => [f.folderId, {type: 'folder', folder: f, children: []}]));
    const root = [];
    folders.forEach((f, i) => root.push({entry: byId.get(f.folderId), key: f.sortOrder, tie: 1, idx: i}));
    items.forEach((it, i) => {
      const group = it.folderId && byId.get(it.folderId);
      if (group) group.children.push({it, i});
      else root.push({entry: {type: 'item', item: it}, key: it.sortOrder, tie: 0, idx: i});
    });
    // Same tie-break as the server: on equal sortOrder an item sorts before a folder.
    root.sort((a, b) => a.key - b.key || a.tie - b.tie || a.idx - b.idx);
    byId.forEach(g => {
      g.children = g.children.sort((a, b) => a.it.sortOrder - b.it.sortOrder || a.i - b.i).map(c => c.it);
    });
    return root.map(r => r.entry);
  }

  // Renumbers everything in display order, mirrors it into the page's item array, re-renders and
  // saves the whole tree in one request.
  async function commit(tree) {
    let n = 0;
    const flat = [];
    const payload = [];
    tree.forEach(e => {
      if (e.type === 'folder') {
        e.folder.sortOrder = n++;
        payload.push({type: 'folder', id: e.folder.folderId});
        e.children.forEach(it => {
          it.folderId = e.folder.folderId;
          it.sortOrder = n++;
          flat.push(it);
          payload.push({type: 'item', id: it[idKey], folderId: e.folder.folderId});
        });
      } else {
        e.item.folderId = null;
        e.item.sortOrder = n++;
        flat.push(e.item);
        payload.push({type: 'item', id: e.item[idKey], folderId: null});
      }
    });
    folders = tree.filter(e => e.type === 'folder').map(e => e.folder);
    opts.setItems(flat);
    opts.rerender();
    const res = await fetch(apiBase + '/tree-order', {
      method: 'PUT', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify(payload)
    });
    if (!res.ok) saveFailed();
  }

  // ── Render ─────────────────────────────────────────────────
  function render() {
    const tree = buildTree();
    opts.setItems(tree.flatMap(e => e.type === 'folder' ? e.children : [e.item]));
    container.innerHTML = '';
    tree.forEach(e => {
      if (e.type === 'item') {
        container.appendChild(itemRow(e.item, false));
        return;
      }
      const group = document.createElement('div');
      group.className = 'oe-tree-group';
      group.appendChild(folderRow(e.folder, e.children));
      if (!e.folder.collapsed && e.children.length > 0) {
        const kids = document.createElement('div');
        kids.className = 'oe-tree-children';
        e.children.forEach(it => kids.appendChild(itemRow(it, true)));
        group.appendChild(kids);
      }
      container.appendChild(group);
    });
  }

  function itemRow(it, inFolder) {
    const el = document.createElement('div');
    el.className = 'oe-list-item' + (inFolder ? ' in-folder' : '') + (opts.isActive(it) ? ' active' : '');
    el.dataset[idKey] = it[idKey];
    el.draggable = true;
    const scopeIcon = 'oe-icon-scope-' + (['private', 'collabo', 'managed'].includes(it.shareScope) ? it.shareScope : 'default');
    const linkTitle = opts.publicLinkTitle(it);
    const name = it[nameKey];
    el.innerHTML = `
      ${linkTitle
        ? `<span class="oe-list-drag" title="${escHtml(linkTitle)}"><span class="oe-icon oe-icon-public-link-issued"></span></span>`
        : `<span class="oe-list-drag"><span class="oe-icon ${scopeIcon}"></span></span>`}
      <span class="oe-list-name">${escHtml(name)}</span>
      <button type="button" class="oe-tree-switch" aria-pressed="${it.selected}"
              aria-label="${escHtml(MSG[it.selected ? 'aria.list.switch.off' : 'aria.list.switch.on'].replace('{0}', name))}">
        <span class="oe-tree-switch-track"><span class="oe-tree-switch-knob"></span></span>
      </button>`;

    el.addEventListener('click', e => {
      if (e.target.closest('.oe-tree-switch')) return;
      opts.onActivate(it);
    });
    el.querySelector('.oe-tree-switch').addEventListener('click', async e => {
      e.stopPropagation();
      await opts.onToggle(it);
      opts.rerender(); // the switch and the folder's badge/icon all follow item.selected
    });
    if (opts.canRename(it)) {
      el.querySelector('.oe-list-name').addEventListener('dblclick', e => opts.onRename(e.target, it));
    }
    wireDrop(el, {type: 'item', item: it, inFolder});
    el.addEventListener('dragstart', e => {
      drag = {type: 'item', id: it[idKey]};
      e.dataTransfer.setData('text/plain', it[idKey]);
    });
    el.addEventListener('dragend', endDrag);
    return el;
  }

  function folderRow(f, children) {
    const on = children.filter(it => it.selected).length;
    const el = document.createElement('div');
    el.className = 'oe-list-folder' + (f.collapsed ? ' collapsed' : '') + (on > 0 ? ' has-on' : '');
    el.dataset.folderId = f.folderId;
    el.draggable = true;
    el.setAttribute('role', 'button');
    el.tabIndex = 0;
    el.setAttribute('aria-expanded', String(!f.collapsed));
    el.innerHTML = `
      <span class="oe-icon ${f.collapsed ? 'oe-icon-folder' : 'oe-icon-folder-open'} oe-list-folder-icon"></span>
      <span class="oe-list-folder-name">${escHtml(f.folderName)}</span>
      <span class="oe-list-folder-count">${on}/${children.length}</span>
      <button type="button" class="oe-list-folder-del" title="${escHtml(MSG['tooltip.delete.folder'])}">
        <span class="oe-icon oe-icon-delete"></span>
      </button>`;
    const nameEl = el.querySelector('.oe-list-folder-name');

    // A click toggles the folder, a double-click on the name renames it. Toggling re-renders the
    // list, which would swallow the double-click, so the single click waits until it is clearly
    // not the first half of one.
    let clickTimer = null;
    el.addEventListener('click', e => {
      if (nameEl.isContentEditable || e.target.closest('.oe-list-folder-del')) return;
      clearTimeout(clickTimer);
      clickTimer = setTimeout(() => toggleCollapsed(f), 250);
    });
    el.addEventListener('keydown', e => {
      if (nameEl.isContentEditable || e.target !== el) return;
      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggleCollapsed(f); }
    });
    nameEl.addEventListener('dblclick', e => {
      e.stopPropagation();
      clearTimeout(clickTimer);
      startRename(nameEl, f);
    });
    el.querySelector('.oe-list-folder-del').addEventListener('click', e => {
      e.stopPropagation();
      deleteFolder(f);
    });
    wireDrop(el, {type: 'folder', folder: f});
    el.addEventListener('dragstart', e => {
      drag = {type: 'folder', id: f.folderId};
      e.dataTransfer.setData('text/plain', f.folderId);
    });
    el.addEventListener('dragend', endDrag);
    return el;
  }

  // ── Drag & drop ────────────────────────────────────────────
  // Over an item: the upper half drops before it, the lower half after it (an item joins that
  // item's folder or the root). Over a folder: an item goes into it unless it is near the top
  // edge (then before the folder); a folder goes before/after it by half. A folder dropped on
  // another folder's item lands next to that folder. Below the last row: the end of the root.
  function dropPosition(e, el, target) {
    const r = el.getBoundingClientRect();
    const y = (e.clientY - r.top) / r.height;
    if (target.type === 'folder' && drag.type === 'item') return y < 0.3 ? 'before' : 'into';
    return y < 0.5 ? 'before' : 'after';
  }

  const DROP_CLASSES = ['drop-before', 'drop-after', 'drop-into'];
  function clearDropMarks() {
    container.querySelectorAll('.drop-before, .drop-after, .drop-into').forEach(el => el.classList.remove(...DROP_CLASSES));
  }
  function endDrag() { drag = null; clearDropMarks(); }

  function wireDrop(el, target) {
    el.addEventListener('dragover', e => {
      if (!drag) return;
      e.preventDefault();
      e.stopPropagation();
      clearDropMarks();
      el.classList.add('drop-' + dropPosition(e, el, target));
    });
    el.addEventListener('dragleave', () => el.classList.remove(...DROP_CLASSES));
    el.addEventListener('drop', e => {
      if (!drag) return;
      e.preventDefault();
      e.stopPropagation();
      const pos = dropPosition(e, el, target);
      clearDropMarks();
      move(drag, target, pos);
    });
  }

  container.addEventListener('dragover', e => {
    if (!drag || !belowLastRow(e)) return;
    e.preventDefault();
    clearDropMarks();
  });
  container.addEventListener('drop', e => {
    if (!drag || !belowLastRow(e)) return;
    e.preventDefault();
    move(drag, {type: 'end'}, 'after');
  });
  function belowLastRow(e) {
    const last = container.lastElementChild;
    return !last || e.clientY > last.getBoundingClientRect().bottom;
  }

  function move(src, target, pos) {
    const tree = buildTree();
    // Take the dragged entry out of the tree.
    let moving = null;
    if (src.type === 'folder') {
      const i = tree.findIndex(e => e.type === 'folder' && e.folder.folderId === src.id);
      if (i === -1) return;
      if (target.type === 'folder' && target.folder.folderId === src.id) return;
      if (target.type === 'item' && target.item.folderId === src.id) return; // onto its own item
      moving = tree.splice(i, 1)[0];
    } else {
      if (target.type === 'item' && target.item[idKey] === src.id) return;
      for (let i = 0; i < tree.length && !moving; i++) {
        const e = tree[i];
        if (e.type === 'item' && e.item[idKey] === src.id) moving = tree.splice(i, 1)[0].item;
        else if (e.type === 'folder') {
          const j = e.children.findIndex(it => it[idKey] === src.id);
          if (j !== -1) moving = e.children.splice(j, 1)[0];
        }
      }
      if (!moving) return;
    }
    const rootEntry = src.type === 'folder' ? moving : {type: 'item', item: moving};
    const rootIndexOf = pred => tree.findIndex(pred);

    if (target.type === 'end') {
      tree.push(rootEntry);
    } else if (target.type === 'folder') {
      const i = rootIndexOf(e => e.type === 'folder' && e.folder.folderId === target.folder.folderId);
      if (pos === 'into') {
        tree[i].children.push(moving);
        if (tree[i].folder.collapsed) { tree[i].folder.collapsed = false; saveCollapsed(tree[i].folder); }
      } else {
        tree.splice(pos === 'before' ? i : i + 1, 0, rootEntry);
      }
    } else {
      const parent = tree.find(e => e.type === 'folder' && e.children.includes(target.item));
      if (parent && src.type === 'item') {
        const j = parent.children.indexOf(target.item);
        parent.children.splice(pos === 'before' ? j : j + 1, 0, moving);
      } else if (parent) {
        // A folder can't go inside a folder: put it next to the folder holding the target.
        const i = tree.indexOf(parent);
        tree.splice(pos === 'before' ? i : i + 1, 0, rootEntry);
      } else {
        const i = rootIndexOf(e => e.type === 'item' && e.item === target.item);
        tree.splice(pos === 'before' ? i : i + 1, 0, rootEntry);
      }
    }
    commit(tree);
  }

  // ── Folder actions ─────────────────────────────────────────
  function saveFailed() { showToast(MSG['status.save.failed']); }

  async function saveCollapsed(f) {
    const res = await fetch(apiBase + '/folders/' + f.folderId, {
      method: 'PATCH', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({collapsed: f.collapsed})
    });
    if (!res.ok) saveFailed();
  }

  function toggleCollapsed(f) {
    f.collapsed = !f.collapsed;
    opts.rerender();
    container.querySelector(`.oe-list-folder[data-folder-id="${f.folderId}"]`)?.focus();
    saveCollapsed(f);
  }

  async function deleteFolder(f) {
    if (!confirm(MSG['confirm.delete.folder'])) return;
    const res = await fetch(apiBase + '/folders/' + f.folderId, {method: 'DELETE'});
    if (!res.ok) { saveFailed(); return; }
    // The server moved the folder's items to the root (FK ON DELETE SET NULL); keep them where
    // the folder was and save that.
    const tree = buildTree();
    const i = tree.findIndex(e => e.type === 'folder' && e.folder.folderId === f.folderId);
    if (i === -1) return;
    const freed = tree[i].children.map(it => ({type: 'item', item: it}));
    tree.splice(i, 1, ...freed);
    commit(tree);
  }

  async function createFolder() {
    const res = await fetch(apiBase + '/folders', {method: 'POST'});
    if (!res.ok) { saveFailed(); return; }
    const f = await res.json();
    folders.push(f);
    opts.rerender();
    const nameEl = container.querySelector(`.oe-list-folder[data-folder-id="${f.folderId}"] .oe-list-folder-name`);
    if (nameEl) startRename(nameEl, f);
  }

  // Inline rename, same interaction as an item's: Enter/blur saves, Escape cancels.
  function startRename(el, f) {
    el.contentEditable = 'true';
    el.classList.add('editing');
    el.focus();
    const range = document.createRange();
    range.selectNodeContents(el);
    window.getSelection().removeAllRanges();
    window.getSelection().addRange(range);

    let cancelled = false;
    const finish = () => { el.contentEditable = 'false'; el.classList.remove('editing'); };
    const commitName = async () => {
      if (cancelled) return;
      finish();
      const newName = el.textContent.trim();
      if (!newName || newName === f.folderName) { el.textContent = f.folderName; return; }
      const res = await fetch(apiBase + '/folders/' + f.folderId, {
        method: 'PATCH', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({name: newName})
      });
      if (res.ok) {
        f.folderName = (await res.json()).folderName;
        el.textContent = f.folderName;
      } else {
        el.textContent = f.folderName;
        showToast(MSG[res.status === 409 ? 'toast.folder.name.taken' : 'status.save.failed']);
      }
    };
    const onKeydown = e => {
      e.stopPropagation(); // Enter/Space here are typing, not the folder row's toggle keys
      if (e.key === 'Enter') { e.preventDefault(); el.removeEventListener('keydown', onKeydown); el.blur(); }
      if (e.key === 'Escape') {
        cancelled = true;
        el.removeEventListener('keydown', onKeydown);
        el.textContent = f.folderName;
        finish();
        el.blur();
      }
    };
    el.addEventListener('blur', commitName, {once: true});
    el.addEventListener('keydown', onKeydown);
  }

  return { load, render, createFolder };
}
