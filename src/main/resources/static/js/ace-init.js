/**
 * initAceEditor(id, mode [, opts]) → Ace editor instance
 *
 * opts:
 *   fontSize          – default '0.83rem'
 *   highlightActiveLine – default true
 *   readOnly          – default false
 *   spacing           – default true  (lineHeight / scrollMargin / padding)
 */
function initAceEditor(id, mode, opts) {
  opts = opts || {};
  var editor = ace.edit(id);
  editor.setTheme('ace/theme/textmate');
  editor.session.setMode(mode);
  editor.setOptions({
    fontFamily: "'Consolas', 'Monaco', 'Courier New', monospace",
    fontSize: opts.fontSize || '0.83rem',
    showGutter: true,
    showPrintMargin: false,
    highlightActiveLine: opts.highlightActiveLine !== false,
    readOnly: !!opts.readOnly,
    useSoftTabs: true,
    tabSize: 2,
  });
  if (opts.spacing !== false) {
    editor.container.style.lineHeight = '1.6';
    editor.renderer.updateFull(true);
    editor.renderer.setScrollMargin(8, 0, 0, 0);
    editor.renderer.setPadding(12);
  }
  return editor;
}
