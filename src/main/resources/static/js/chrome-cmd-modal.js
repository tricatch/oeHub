// Shared by the oeHosts editor (hosts.pebble) and its public share page (hosts-share.pebble):
// shows the direct Chrome launch command for one OS, wrapped in a script that first closes the
// Chrome it would collide with. Markup lives in _chrome-cmd-modal.pebble.
// Relies on copyToClipboard() and showToast() from util.js.

/**
 * @param os          'win' or 'mac'
 * @param launchCmd   output of buildChromeCommandLine
 * @param userDataDir the resolved --user-data-dir when that option is on, otherwise ''
 */
function openChromeCmdModal(os, launchCmd, userDataDir) {
  const modal = document.getElementById('chromeCmdModal');
  document.getElementById('chromeCmdOs').textContent =
    MSG[os === 'win' ? 'modal.chrome.cmd.os.win' : 'modal.chrome.cmd.os.mac'];
  document.getElementById('chromeCmdKillLaunch').textContent = buildChromeKillLaunchCommand(os, launchCmd, userDataDir);
  document.getElementById('chromeCmdKillWarn').textContent =
    MSG[userDataDir ? 'modal.chrome.cmd.kill.warn.profile' : 'modal.chrome.cmd.kill.warn.all'];
  bootstrap.Modal.getOrCreateInstance(modal).show();
}

// Copying is the only thing left to do in the modal, so it closes right after. The share page's
// layout has no toast, hence the guard.
document.addEventListener('DOMContentLoaded', () => {
  const modal = document.getElementById('chromeCmdModal');
  if (!modal) return;
  document.getElementById('chromeCmdCopyBtn').addEventListener('click', () => {
    copyToClipboard(document.getElementById('chromeCmdKillLaunch').textContent).then(() => {
      bootstrap.Modal.getOrCreateInstance(modal).hide();
      if (document.getElementById('toast')) showToast(MSG['toast.chrome.cmd.copied']);
    });
  });
});
