// Shared by the oeHosts editor (hosts.pebble) and its share/link page (hosts-share.pebble): wires the
// oelink install guide modal (_oelink-install-modal.pebble). Relies on oelinkClientOs() from
// oelink-cmd-modal.js and copyToClipboard()/showToast() from util.js.

/** Shows only the visitor's OS installer block (an unrecognized OS keeps both) and wires the copy buttons. */
function wireOelinkInstallModal() {
  const os = oelinkClientOs();
  document.querySelectorAll('#oelinkInstallModal [data-oelink-os]').forEach(block => {
    if (os && block.dataset.oelinkOs !== os) block.classList.add('d-none');
    block.querySelector('.oelink-install-copy').addEventListener('click', () => {
      copyToClipboard(block.querySelector('pre').textContent)
        .then(() => showToast(MSG['toast.oelink.install.copied']));
    });
  });
}
