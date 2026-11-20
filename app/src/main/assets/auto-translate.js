(function () {
  if (window.__nativeVietnameseTranslationInstalled) return;
  if (!window.NativeTranslation || !window.NativeTranslation.translateBatch) return;
  window.__nativeVietnameseTranslationInstalled = true;

  var nextId = 1;
  var pending = new Map();
  var queue = [];
  var scheduled = false;
  var sending = false;
  var observer = null;
  var originalText = new WeakMap();
  var translatedNodes = new WeakSet();
  var ignoredTags = new Set([
    'script', 'style', 'noscript', 'textarea', 'option', 'code', 'pre',
    'input', 'select', 'button', 'svg', 'math'
  ]);

  function isIgnored(node) {
    var element = node && node.parentElement;
    if (!element) return true;
    if (element.closest('[contenteditable="true"],[aria-hidden="true"],[translate="no"]')) return true;
    for (var current = element; current && current !== document.documentElement; current = current.parentElement) {
      if (ignoredTags.has((current.tagName || '').toLowerCase())) return true;
      var style;
      try { style = window.getComputedStyle(current); } catch (_) { return true; }
      if (style.display === 'none' || style.visibility === 'hidden') return true;
    }
    return false;
  }

  function addNode(node) {
    if (!node || node.nodeType !== Node.TEXT_NODE || translatedNodes.has(node) || isIgnored(node)) return;
    var value = node.nodeValue || '';
    if (!value.trim() || value.trim().length < 2) return;
    if (!originalText.has(node)) originalText.set(node, value);
    var id = String(nextId++);
    pending.set(id, node);
    queue.push({ id: id, text: value });
    schedule();
  }

  function scan(root) {
    if (!root) return;
    if (root.nodeType === Node.TEXT_NODE) {
      addNode(root);
      return;
    }
    if (root.nodeType !== Node.ELEMENT_NODE && root !== document) return;
    if (root.nodeType === Node.ELEMENT_NODE && ignoredTags.has((root.tagName || '').toLowerCase())) return;
    var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    var node;
    while ((node = walker.nextNode())) addNode(node);
  }

  function schedule() {
    if (scheduled) return;
    scheduled = true;
    window.setTimeout(function () {
      scheduled = false;
      sendNext();
    }, 180);
  }

  function sendNext() {
    if (sending || queue.length === 0) return;
    sending = true;
    var batch = queue.splice(0, 20);
    try {
      window.NativeTranslation.translateBatch(JSON.stringify(batch));
    } catch (error) {
      batch.forEach(function (item) { pending.delete(item.id); });
      sending = false;
      if (queue.length) schedule();
    }
  }

  window.__applyNativeTranslations = function (results) {
    try {
      (results || []).forEach(function (item) {
        var node = pending.get(String(item.id));
        pending.delete(String(item.id));
        if (!node || !node.isConnected || typeof item.text !== 'string' || !item.text.trim()) return;
        if (isIgnored(node)) return;
        if (node.nodeValue !== originalText.get(node)) return;
        translatedNodes.add(node);
        node.nodeValue = item.text;
      });
    } finally {
      sending = false;
      if (queue.length) schedule();
    }
  };

  function installObserver() {
    if (!document.body || observer) return;
    scan(document.body);
    observer = new MutationObserver(function (mutations) {
      mutations.forEach(function (mutation) {
        if (mutation.type === 'characterData') {
          var target = mutation.target;
          if (translatedNodes.has(target)) return;
          originalText.delete(target);
          addNode(target);
        }
        mutation.addedNodes && mutation.addedNodes.forEach(function (node) { scan(node); });
      });
    });
    observer.observe(document.body, { childList: true, subtree: true, characterData: true });
  }

  if (document.body) installObserver();
  else document.addEventListener('DOMContentLoaded', installObserver, { once: true });

  // Re-scan after route changes in single-page applications.
  var lastUrl = location.href;
  window.setInterval(function () {
    if (location.href !== lastUrl) {
      lastUrl = location.href;
      scan(document.body);
    }
  }, 1200);
})();