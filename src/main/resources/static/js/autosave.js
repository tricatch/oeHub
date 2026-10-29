// Debounced auto-save for the panel editors (oeHosts, oeProxy). The page owns the editor and the
// actual save request; this owns when to save and what the user sees about it:
//  - one save in flight at a time, so a slow request can never land after a newer one;
//  - a failed save is retried with backoff instead of being dropped until the next keystroke;
//  - an edit still waiting to be saved survives switching to another item and back;
//  - Ctrl+S / Cmd+S saves right away instead of opening the browser's "save page" dialog;
//  - leaving the page with an unsaved edit asks first (an async encrypt-then-send can't be
//    relied on to finish while the page unloads);
//  - the editor container gets oe-autosave-dirty / oe-autosave-failed (outline, oe-panel.css)
//    and the status element says what is going on.
//
// opts.save(id, content) returns a Promise of:
//   {saved: true}                    saved
//   {saved: true, notice: '...'}     saved, but show this notice as an error for a moment
//   {saved: false, message: '...'}   not saved and retrying won't help (e.g. invalid content, 4xx);
//                                    stays unsaved until the content changes
// and throws on a failure worth retrying (network, 5xx, session keys not available yet).
const OE_AUTOSAVE = (() => {
  const DEBOUNCE_MS = 2000;
  const RETRY_MIN_MS = 2000;
  const RETRY_MAX_MS = 30000;
  const SAVED_SHOW_MS = 2000;
  const NOTICE_SHOW_MS = 4000;

  function create({editorEl, statusEl, save}) {
    const savedCrc = new Map(); // id -> crc of the content the server is known to hold
    const pending = new Map();  // id -> latest edit not yet confirmed saved
    const failed = new Set();   // ids whose last attempt failed and are being retried
    const blocked = new Map();  // id -> {crc, message} for content save() refused outright
    let activeId = null;
    let savingId = null;
    let running = false;
    let debounceTimer = null;
    let retryTimer = null;
    let retryDelay = RETRY_MIN_MS;
    let flash = null;           // {id, text, error} shown briefly after a save
    let flashTimer = null;

    function isDirty(id) {
      return pending.has(id) && crc32(pending.get(id)) !== savedCrc.get(id);
    }

    function nextToSave() {
      for (const [id, content] of pending) {
        const crc = crc32(content);
        if (crc === savedCrc.get(id)) { pending.delete(id); continue; }
        const b = blocked.get(id);
        if (b && b.crc === crc) continue;
        return [id, content];
      }
      return null;
    }

    async function run() {
      if (running) return; // the loop below picks up whatever is queued once the current save ends
      running = true;
      clearTimeout(retryTimer);
      try {
        for (let next = nextToSave(); next; next = nextToSave()) {
          const [id, content] = next;
          savingId = id;
          render();
          let result;
          try {
            result = await save(id, content);
          } catch (e) {
            if (pending.has(id)) failed.add(id);
            retryTimer = setTimeout(run, retryDelay);
            retryDelay = Math.min(retryDelay * 2, RETRY_MAX_MS);
            break;
          }
          failed.delete(id);
          retryDelay = RETRY_MIN_MS;
          if (result && result.saved === false) {
            blocked.set(id, {crc: crc32(content), message: result.message});
            continue;
          }
          blocked.delete(id);
          savedCrc.set(id, crc32(content));
          if (pending.get(id) === content) pending.delete(id);
          showFlash(id, result && result.notice ? result.notice : MSG['status.saved'], !!(result && result.notice));
        }
      } finally {
        savingId = null;
        running = false;
        render();
      }
    }

    function showFlash(id, text, error) {
      clearTimeout(flashTimer);
      flash = {id, text, error};
      flashTimer = setTimeout(() => { flash = null; render(); }, error ? NOTICE_SHOW_MS : SAVED_SHOW_MS);
    }

    function render() {
      const id = activeId;
      const dirty = id !== null && isDirty(id);
      const b = dirty ? blocked.get(id) : null;
      const isBlocked = !!b && b.crc === crc32(pending.get(id));
      const isFailed = dirty && failed.has(id);
      let text = '';
      let error = false;
      if (isFailed) { text = MSG['status.save.retrying']; error = true; }
      else if (isBlocked) { text = b.message; error = true; }
      else if (dirty) { text = MSG[savingId === id ? 'status.saving' : 'status.unsaved']; }
      else if (flash && flash.id === id) { text = flash.text; error = flash.error; }
      editorEl.classList.toggle('oe-autosave-dirty', dirty && !isFailed);
      editorEl.classList.toggle('oe-autosave-failed', isFailed);
      statusEl.textContent = text;
      statusEl.classList.toggle('is-error', error);
    }

    // Saves whatever is queued now instead of waiting for the debounce.
    function flush() {
      clearTimeout(debounceTimer);
      run();
    }

    window.addEventListener('beforeunload', e => {
      flush();
      if ([...pending.keys()].some(isDirty)) {
        e.preventDefault();
        e.returnValue = '';
      }
    });
    // Capture phase, so it is handled the same whether or not focus is inside Ace.
    document.addEventListener('keydown', e => {
      if (!(e.ctrlKey || e.metaKey) || e.altKey || e.shiftKey || e.key.toLowerCase() !== 's') return;
      e.preventDefault();
      if (activeId === null) return;
      const nothingToSave = !isDirty(activeId);
      flush();
      // Nothing was waiting: still answer the keypress so it doesn't look ignored.
      if (nothingToSave && savingId === null) { showFlash(activeId, MSG['status.saved'], false); render(); }
    }, true);
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'hidden') flush();
    });

    return {
      // Call before putting an item's content into the editor; id is null when nothing editable
      // is shown (read-only item, search result, nothing selected). Returns the content to
      // display: the unsaved edit if this item still has one queued, else serverContent.
      activate(id, serverContent) {
        flush();
        activeId = id;
        if (id !== null && !pending.has(id)) savedCrc.set(id, crc32(serverContent));
        render();
        return id !== null && pending.has(id) ? pending.get(id) : serverContent;
      },
      // Call on every edit of the active item.
      changed(content) {
        if (activeId === null) return;
        pending.set(activeId, content);
        render();
        clearTimeout(debounceTimer);
        debounceTimer = setTimeout(run, DEBOUNCE_MS);
      },
      // Call when an item is deleted, so nothing keeps trying to save it.
      discard(id) {
        pending.delete(id);
        failed.delete(id);
        blocked.delete(id);
        savedCrc.delete(id);
        if (activeId === id) activeId = null;
        render();
      },
      flush
    };
  }

  return {create};
})();
