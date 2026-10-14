// Shared by the oeHosts editor (hosts.pebble) and its public share page (hosts-share.pebble):
// shows the direct Chrome launch command for one OS, plus a variant that first force-closes the
// Chrome it would collide with. Markup lives in _chrome-cmd-modal.pebble.
// Relies on copyToClipboard() and flashCopyFeedback() from util.js.

/**
 * @param os          'win' or 'mac'
 * @param launchCmd   output of buildChromeCommandLine
 * @param userDataDir the resolved --user-data-dir when that option is on, otherwise ''
 */
function openChromeCmdModal(os, launchCmd, userDataDir) {
  const modal = document.getElementById('chromeCmdModal');
  document.getElementById('chromeCmdOs').textContent =
    MSG[os === 'win' ? 'modal.chrome.cmd.os.win' : 'modal.chrome.cmd.os.mac'];
  document.getElementById('chromeCmdLaunch').textContent = launchCmd;
  document.getElementById('chromeCmdKillLaunch').textContent = buildChromeKillLaunchCommand(os, launchCmd, userDataDir);
  document.getElementById('chromeCmdKillWarn').textContent =
    MSG[userDataDir ? 'modal.chrome.cmd.kill.warn.profile' : 'modal.chrome.cmd.kill.warn.all'];
  bootstrap.Modal.getOrCreateInstance(modal).show();
}

document.addEventListener('DOMContentLoaded', () => {
  document.querySelectorAll('#chromeCmdModal [data-copy-from]').forEach(btn => {
    btn.addEventListener('click', () => {
      const text = document.getElementById(btn.dataset.copyFrom).textContent;
      copyToClipboard(text).then(() => flashCopyFeedback(btn, MSG['modal.chrome.cmd.copy']));
    });
  });
});
