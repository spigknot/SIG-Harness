(function () {
  if (window.__sigReloadHook) return;
  window.__sigReloadHook = true;

  var ICON = '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M20 11a8 8 0 1 0-2.3 5.7"/><path d="M20 4v7h-7"/></svg>';
  var button = null;

  function installStyle() {
    if (document.getElementById('sig-reload-style')) return;
    var style = document.createElement('style');
    style.id = 'sig-reload-style';
    style.textContent =
      '#sig-reload-button{width:28px;height:28px;border-radius:999px;border:none;background:transparent;' +
      'color:var(--dsw-alias-label-tertiary);display:grid;place-items:center;padding:0;margin:0 2px;' +
      'cursor:pointer;flex:none;transition:background-color .1s,color .1s}' +
      '#sig-reload-button:hover{background:var(--dsw-alias-interactive-bg-hover-solid);color:var(--dsw-alias-label-primary)}';
    document.head.appendChild(style);
  }

  function ensure() {
    installStyle();
    if (button !== null) return;
    button = document.createElement('button');
    button.type = 'button';
    button.id = 'sig-reload-button';
    button.title = 'Recarregar a interface';
    button.setAttribute('aria-label', 'Recarregar a interface');
    button.innerHTML = ICON;
    button.addEventListener('click', function (event) {
      event.preventDefault();
      event.stopPropagation();
      window.location.reload();
    });
  }

  function seat() {
    return document.querySelector(
      'button[title="Choose the agent preset for your new task"],' +
      'button[title="选择新任务使用的 Agent 预设"]'
    );
  }

  function anchor() {
    var actions = document.querySelector('[class*="headerActions"]');
    if (actions !== null && actions.offsetParent !== null) {
      return { parent: actions, before: actions.firstChild === button ? button.nextSibling : actions.firstChild };
    }
    var preset = seat();
    if (preset !== null && preset.parentNode !== null && preset.offsetParent !== null) {
      return { parent: preset.parentNode, before: preset };
    }
    var row = document.querySelector('[class*="heroWorkspaceRow"]');
    if (row !== null && row.offsetParent !== null) {
      return { parent: row, before: row.firstChild === button ? button.nextSibling : row.firstChild };
    }
    return null;
  }

  function mount() {
    ensure();
    var target = anchor();
    if (target === null || target.before === null || target.before === button) {
      if (button.parentNode !== null) button.parentNode.removeChild(button);
      return;
    }
    if (button.parentNode !== target.parent || button.nextSibling !== target.before) {
      target.parent.insertBefore(button, target.before);
    }
  }

  mount();
  window.addEventListener('resize', mount);
  window.addEventListener('orientationchange', function () { setTimeout(mount, 250); });
  setInterval(mount, 700);
  var observer = new MutationObserver(function () { mount(); });
  try { observer.observe(document.body, { childList: true, subtree: true }); } catch (error) { /* ignore */ }
})();
