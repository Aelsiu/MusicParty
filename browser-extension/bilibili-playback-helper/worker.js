// Fail closed on startup/reload: this diagnostic package owns no active rules.
// There are no page messages, credentials, media requests or header modifications.
async function clearRules() {
  const rules = await chrome.declarativeNetRequest.getSessionRules();
  if (rules.length) await chrome.declarativeNetRequest.updateSessionRules({
    removeRuleIds: rules.map(rule => rule.id),
  });
}
clearRules().catch(() => chrome.action.setBadgeText({text: 'ERR'}));
chrome.runtime.onInstalled.addListener(() => clearRules().catch(() => chrome.action.setBadgeText({text: 'ERR'})));
chrome.runtime.onStartup.addListener(() => clearRules().catch(() => chrome.action.setBadgeText({text: 'ERR'})));
