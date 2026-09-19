// Shared Chrome launch-flag building logic for the oeHosts editor (hosts.pebble) and its public
// share page (hosts-share.pebble) - keeps the flag order/masking rules in exactly one place since
// both pages must stay in sync.

function splitExtraArgsLines(text) {
  return text.split('\n').map(l => l.trim()).filter(l => l && !l.startsWith('//'));
}

function chromeOelinkValue(uddEnabled, userDataDir) {
  return (uddEnabled && userDataDir) ? crc32(userDataDir) : 0;
}

/**
 * Builds Chrome's launch flags in the order shared by every "real" launcher (the oelink scheme
 * string and the direct win/mac command). Returns the flags as an array, sorted except for the
 * leading --oelink and trailing URL entries the caller adds itself.
 */
function buildChromeArgParts(opts) {
  const parts = [];
  if (opts.devTools) parts.push('--auto-open-devtools-for-tabs');
  if (opts.extraArgsEnabled) splitExtraArgsLines(opts.extraArgsVal).forEach(l => parts.push(l));
  if (opts.hrrEnabled && opts.rulesStr) parts.push(`--host-resolver-rules="${opts.rulesStr}"`);
  if (opts.proxyServerEnabled && opts.proxyServerValue) parts.push(`--proxy-server="${opts.proxyServerValue}"`);
  if (opts.incognito) parts.push('--incognito');
  if (opts.uddEnabled && opts.userDataDir) parts.push(`--user-data-dir="${opts.userDataDir}"`);
  if (opts.uaEnabled && opts.userAgent) parts.push(`--user-agent="${opts.userAgent}"`);
  parts.sort();
  return parts;
}

/**
 * Builds the flag-per-line preview list shown in the UI - unlike buildChromeArgParts, this masks
 * --host-resolver-rules to a fixed placeholder since the resolved mapping can be long.
 */
function buildChromeArgLines(opts) {
  const lines = [];
  if (opts.devTools) lines.push('--auto-open-devtools-for-tabs');
  if (opts.extraArgsEnabled) splitExtraArgsLines(opts.extraArgsVal).forEach(l => lines.push(l));
  if (opts.hrrEnabled && opts.hasRules) lines.push('--host-resolver-rules "MAP ..."');
  if (opts.proxyServerEnabled && opts.proxyServerValue) lines.push(`--proxy-server="${opts.proxyServerValue}"`);
  if (opts.incognito) lines.push('--incognito');
  if (opts.uddEnabled && opts.userDataDir) lines.push(`--user-data-dir="${opts.userDataDir}"`);
  if (opts.uaEnabled && opts.userAgent) lines.push(`--user-agent="${opts.userAgent}"`);
  lines.sort();
  const oelinkValue = chromeOelinkValue(opts.uddEnabled, opts.userDataDir);
  if (opts.oelinkEnabled) lines.unshift(`--oelink=${oelinkValue}`);
  if (opts.urlEnabled && opts.url) lines.push('  ' + opts.url);
  return lines;
}

/** Renders the win/mac direct-launch command line from an already-built flag list. */
function buildChromeCommandLine(os, parts) {
  if (os === 'win') {
    const argList = parts.map(winArgQuote).join(', ');
    return parts.length > 0
      ? `Start-Process "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe" -ArgumentList ${argList}`
      : 'Start-Process "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe"';
  }
  return parts.length > 0
    ? 'open -na "Google Chrome" --args ' + parts.map(macArgQuote).join(' ')
    : 'open -na "Google Chrome"';
}
