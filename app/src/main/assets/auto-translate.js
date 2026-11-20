(function () {
  if (window.__nativeVietnameseTranslationInstalled) return;
  if (!window.NativeTranslation || !window.NativeTranslation.translateBatch) return;
  window.__nativeVietnameseTranslationInstalled = true;

  var nextId = 1;
  var pending = new Map();
  var queue = [];
  var scheduled = false;
  var sending = false;
  var activeIds = [];
  var observer = null;
  var originalText = new WeakMap();
  var translatedNodes = new WeakSet();
  var translatedValue = new WeakMap();
  var attemptedNodes = new WeakSet();
  var attributeState = new WeakMap();
  var ignoredTextTags = new Set([
    'script', 'style', 'noscript', 'textarea', 'option', 'code', 'pre', 'svg', 'math'
  ]);
  var ignoredAttributeTags = new Set(['script', 'style', 'noscript', 'svg', 'math', 'code', 'pre']);
  var translatableAttributes = ['placeholder', 'title', 'aria-label', 'alt'];

  function isIgnoredElement(element) {
    if (!element || element.closest('[contenteditable="true"],[aria-hidden="true"],[translate="no"]')) return true;
    for (var current = element; current && current !== document.documentElement; current = current.parentElement) {
      if (ignoredAttributeTags.has((current.tagName || '').toLowerCase())) return true;
      var style;
      try { style = window.getComputedStyle(current); } catch (_) { return true; }
      if (style.display === 'none' || style.visibility === 'hidden') return true;
    }
    return false;
  }

  function isIgnored(node) {
    if (!node || !node.parentElement || isIgnoredElement(node.parentElement)) return true;
    for (var current = node.parentElement; current && current !== document.documentElement; current = current.parentElement) {
      if (ignoredTextTags.has((current.tagName || '').toLowerCase())) return true;
    }
    return false;
  }

  function addNode(node) {
    if (!node || node.nodeType !== Node.TEXT_NODE || translatedNodes.has(node) || attemptedNodes.has(node) || isIgnored(node)) return;
    var value = node.nodeValue || '';
    if (!value.trim() || value.trim().length < 2) return;
    if (!originalText.has(node)) originalText.set(node, value);
    attemptedNodes.add(node);
    var id = String(nextId++);
    pending.set(id, { node: node, attribute: null });
    queue.push({ id: id, text: value });
    schedule();
  }

  function addAttributes(element) {
    if (!element || element.nodeType !== Node.ELEMENT_NODE || isIgnoredElement(element)) return;
    var states = attributeState.get(element);
    if (!states) {
      states = new Map();
      attributeState.set(element, states);
    }
    translatableAttributes.forEach(function (name) {
      if (!element.hasAttribute(name)) return;
      var value = element.getAttribute(name) || '';
      if (!value.trim() || value.trim().length < 2) return;
      var previous = states.get(name);
      if (previous && (previous.pending || previous.attempted) && value === previous.original) return;
      if (previous && value === previous.translated) return;
      var state = { original: value, translated: null, pending: true, attempted: true };
      states.set(name, state);
      var id = String(nextId++);
      pending.set(id, { node: element, attribute: name, state: state });
      queue.push({ id: id, text: value });
      schedule();
    });
  }

  function scan(root) {
    if (!root) return;
    if (root.nodeType === Node.TEXT_NODE) {
      addNode(root);
      return;
    }
    if (root.nodeType !== Node.ELEMENT_NODE && root !== document) return;
    if (root.nodeType === Node.ELEMENT_NODE) {
      addAttributes(root);
      if (ignoredTextTags.has((root.tagName || '').toLowerCase())) return;
    }
    var textWalker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    var node;
    while ((node = textWalker.nextNode())) addNode(node);
    var elementWalker = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT);
    var element;
    while ((element = elementWalker.nextNode())) addAttributes(element);
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
    activeIds = batch.map(function (item) { return item.id; });
    try {
      window.NativeTranslation.translateBatch(JSON.stringify(batch));
    } catch (error) {
      activeIds.forEach(function (id) { pending.delete(id); });
      activeIds = [];
      sending = false;
      if (queue.length) schedule();
    }
  }

  window.__applyNativeTranslations = function (results) {
    try {
      (results || []).forEach(function (item) {
        var id = String(item.id);
        var entry = pending.get(id);
        if (!entry || !entry.node || !entry.node.isConnected || typeof item.text !== 'string' || !item.text.trim()) return;
        if (entry.attribute) {
          var element = entry.node;
          if (isIgnoredElement(element) || element.getAttribute(entry.attribute) !== entry.state.original) return;
          entry.state.pending = false;
          entry.state.translated = item.text;
          element.setAttribute(entry.attribute, item.text);
        } else {
          var textNode = entry.node;
          if (isIgnored(textNode) || textNode.nodeValue !== originalText.get(textNode)) return;
          translatedNodes.add(textNode);
          translatedValue.set(textNode, item.text);
          textNode.nodeValue = item.text;
        }
      });
    } finally {
      activeIds.forEach(function (id) {
        var entry = pending.get(id);
        if (entry && entry.state) entry.state.pending = false;
        pending.delete(id);
      });
      activeIds = [];
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
          if (translatedNodes.has(target) && target.nodeValue === translatedValue.get(target)) return;
          translatedNodes.delete(target);
          translatedValue.delete(target);
          attemptedNodes.delete(target);
          originalText.delete(target);
          addNode(target);
        }
        if (mutation.type === 'attributes') {
          scan(mutation.target);
        }
        mutation.addedNodes && mutation.addedNodes.forEach(function (added) { scan(added); });
      });
    });
    observer.observe(document.body, {
      childList: true,
      subtree: true,
      characterData: true,
      attributes: true,
      attributeFilter: ['class', 'style', 'placeholder', 'title', 'aria-label', 'alt']
    });
  }

  if (document.body) installObserver();
  else document.addEventListener('DOMContentLoaded', installObserver, { once: true });

  var lastUrl = location.href;
  window.setInterval(function () {
    if (location.href !== lastUrl) {
      lastUrl = location.href;
      scan(document.body);
    }
  }, 1200);
})();
