// Copy/download handlers for the one-time recovery-code display (register.pebble, setup.pebble,
// recover.pebble via templates/_recovery-code-modal.pebble, and app-layout.pebble's own
// reissue-recovery modal, which keeps its own element ids but reuses this same logic) - CLAUDE.md
// "Scripts used in two or more places" rule.
const OE_RECOVERY_CODE_MODAL = (function () {
  'use strict';

  function bind(opts) {
    var copyBtn = document.getElementById(opts.copyBtnId);
    var downloadBtn = document.getElementById(opts.downloadBtnId);
    var getCode = opts.getCode;

    copyBtn.addEventListener('click', function () {
      copyToClipboard(getCode()).then(function () { showToast(MSG['toast.copied']); });
    });

    downloadBtn.addEventListener('click', function () {
      var blob = new Blob([getCode() + '\n'], { type: 'text/plain' });
      var url = URL.createObjectURL(blob);
      var a = document.createElement('a');
      a.href = url;
      a.download = 'oeHub-recovery-code.txt';
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
    });
  }

  return { bind: bind };
})();
