(function () {
  if (window.__sigBlobDownloadHook) return;
  var bridge = window.SigDownload;
  if (!bridge || typeof bridge.begin !== 'function') return;
  window.__sigBlobDownloadHook = true;

  var blobUrls = new Map();
  var createObjectURL = URL.createObjectURL.bind(URL);
  URL.createObjectURL = function (object) {
    var url = createObjectURL(object);
    try {
      if (object && typeof object.size === 'number' && typeof object.slice === 'function') {
        blobUrls.set(url, object);
      }
    } catch (error) { /* not a blob: leave it alone */ }
    return url;
  };
  var revokeObjectURL = URL.revokeObjectURL.bind(URL);
  URL.revokeObjectURL = function (url) {
    try { blobUrls.delete(url); } catch (error) { /* ignore */ }
    return revokeObjectURL(url);
  };

  function toBase64(bytes) {
    var text = '';
    for (var i = 0; i < bytes.length; i += 0x8000) {
      text += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    }
    return btoa(text);
  }

  function saveBlob(blob, name) {
    try {
      bridge.begin(name, blob.type || 'application/octet-stream');
    } catch (error) {
      return;
    }
    var chunk = 256 * 1024;
    var offset = 0;
    function next() {
      if (offset >= blob.size) {
        try { bridge.end(); } catch (error) { /* ignore */ }
        return;
      }
      var slice = blob.slice(offset, offset + chunk);
      offset += chunk;
      var reader = new FileReader();
      reader.onload = function () {
        try {
          bridge.chunk(toBase64(new Uint8Array(reader.result)));
          next();
        } catch (error) {
          try { bridge.fail(String(error && error.message || error)); } catch (ignored) { /* ignore */ }
        }
      };
      reader.onerror = function () {
        try { bridge.fail('leitura do arquivo falhou'); } catch (ignored) { /* ignore */ }
      };
      reader.readAsArrayBuffer(slice);
    }
    next();
  }

  var click = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    try {
      var href = this.href || '';
      if (href.indexOf('blob:') === 0 && this.hasAttribute('download') && blobUrls.has(href)) {
        var blob = blobUrls.get(href);
        saveBlob(blob, this.getAttribute('download') || 'download');
        return;
      }
    } catch (error) { /* fall through to the original click */ }
    return click.apply(this, arguments);
  };
})();
