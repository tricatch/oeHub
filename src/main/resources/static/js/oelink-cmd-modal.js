// Shared by the oeHosts editor (hosts.pebble) and its public share page (hosts-share.pebble):
// shows the oelink:// link wrapped in a command for the visitor's OS, so it can be pasted into
// PowerShell or Terminal (where a bare URL does nothing). Markup lives in _oelink-cmd-modal.pebble.
// Relies on copyToClipboard() and showToast() from util.js.

/** 'win', 'mac', or null for an OS the oelink handler does not support. */
function oelinkClientOs() {
  const ua = navigator.userAgent;
  return ua.includes('Win') ? 'win' : ua.includes('Mac') ? 'mac' : null;
}

/**
 * The payload is base64url ([A-Za-z0-9_-]), so double quotes are safe in both shells. On Windows
 * Start-Process hands the URL to the registered protocol handler; on macOS `open` does the same.
 */
function buildOelinkCommand(os, schemeUrl) {
  if (os === 'win') return `Start-Process "${schemeUrl}"`;
  if (os === 'mac') return `open "${schemeUrl}"`;
  return schemeUrl;
}

function openOelinkCmdModal(schemeUrl) {
  const modal = document.getElementById('oelinkCmdModal');
  const os = oelinkClientOs();
  document.getElementById('oelinkCmdOs').textContent =
    os ? MSG[os === 'win' ? 'hosts.install.os.win' : 'hosts.install.os.mac'] : '';
  document.getElementById('oelinkCmdScript').textContent = buildOelinkCommand(os, schemeUrl);
  bootstrap.Modal.getOrCreateInstance(modal).show();
}

// Same as the Chrome command modal: copying is the only thing left to do, so it closes right after.
// The share page's layout has no toast, hence the guard.
document.addEventListener('DOMContentLoaded', () => {
  const modal = document.getElementById('oelinkCmdModal');
  if (!modal) return;
  document.getElementById('oelinkCmdCopyBtn').addEventListener('click', () => {
    copyToClipboard(document.getElementById('oelinkCmdScript').textContent).then(() => {
      bootstrap.Modal.getOrCreateInstance(modal).hide();
      if (document.getElementById('toast')) showToast(MSG['toast.chrome.cmd.copied']);
    });
  });
});
