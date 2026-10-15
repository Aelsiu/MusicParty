const state = document.getElementById('state');
try {
  const rules = await chrome.declarativeNetRequest.getSessionRules();
  state.textContent = rules.length ? '异常：仍有会话规则，请停用扩展。' : '已关闭：当前无会话规则。';
} catch { state.textContent = '无法核对规则；不能确认已关闭，请停用扩展。'; }
