const RULE_ID_BASE = 1000;
const RESOURCE_TYPES = [
  'main_frame', 'sub_frame', 'xmlhttprequest', 'script', 'stylesheet',
  'image', 'font', 'object', 'media', 'websocket', 'other'
];

function toRequestDomain(domain) {
  const d = domain.trim();
  return d.startsWith('*.') ? d.slice(2) : d;
}

async function doRebuildRules() {
  const { oid, domains, enabled } = await chrome.storage.local.get(['oid', 'domains', 'enabled']);
  const existing = await chrome.declarativeNetRequest.getDynamicRules();
  const removeRuleIds = existing.map(r => r.id);

  const addRules = enabled === false ? [] : (domains || [])
    .map(toRequestDomain)
    .filter(d => d.length > 0)
    .map((domain, i) => ({
      id: RULE_ID_BASE + i,
      priority: 1,
      action: {
        type: 'modifyHeaders',
        requestHeaders: [{ header: 'X-OeHub-Oid', operation: 'set', value: oid || '' }]
      },
      condition: {
        requestDomains: [domain],
        resourceTypes: RESOURCE_TYPES
      }
    }));

  await chrome.declarativeNetRequest.updateDynamicRules({ removeRuleIds, addRules });
}

// onInstalled and storage.onChanged can both fire from the same storage write
// (see onInstalled below), so concurrent calls must be serialized — otherwise
// two overlapping calls can both read the dynamic rules before either writes,
// and then both try to add the same rule id ("does not have a unique ID").
let rebuildChain = Promise.resolve();
function rebuildRules() {
  rebuildChain = rebuildChain.then(doRebuildRules).catch(err => console.error('rebuildRules failed', err));
  return rebuildChain;
}

chrome.runtime.onInstalled.addListener(async () => {
  const current = await chrome.storage.local.get(['oid', 'domains']);
  if (current.oid === undefined && current.domains === undefined) {
    const res = await fetch(chrome.runtime.getURL('default-config.json'));
    const defaults = await res.json();
    await chrome.storage.local.set({ oid: defaults.oid || '', domains: defaults.domains || [] });
  }
  await rebuildRules();
});

chrome.storage.onChanged.addListener((changes, area) => {
  if (area === 'local' && (changes.oid || changes.domains || changes.enabled)) {
    rebuildRules();
  }
});
