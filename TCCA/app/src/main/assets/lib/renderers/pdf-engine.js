// PDF rendering engine for Cast Control Sources — thin wrapper around
// pdfjs-dist, shared (via relative import) by controller.html (page-count +
// thumbnail generation for the Source Controls panel) and presenter.html
// (full-resolution page render into the shared #media-frame-box). Both call
// sites load this same module so there is exactly one PDF parsing/caching
// implementation rather than two.
import * as pdfjsLib from '../pdfjs/pdf.min.mjs';

pdfjsLib.GlobalWorkerOptions.workerSrc = new URL('../pdfjs/pdf.worker.min.mjs', import.meta.url).href;

// Cache the parsed PDFDocumentProxy by dataUrl (string identity) so paging
// through a source never re-parses the underlying file — only a genuinely
// different dataUrl (a different/replaced source) triggers a fresh parse.
var _docCache = new Map(); // dataUrl -> Promise<PDFDocumentProxy>

function _dataUrlToBytes(dataUrl) {
  var comma = dataUrl.indexOf(',');
  var base64 = comma >= 0 ? dataUrl.slice(comma + 1) : dataUrl;
  var binary = atob(base64);
  var bytes = new Uint8Array(binary.length);
  for (var i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

// Returns a Promise<PDFDocumentProxy>. Rejects (with a real pdf.js error) on
// an invalid/corrupted/unsupported PDF — callers surface that as a toast
// rather than silently failing.
export function loadPdf(dataUrl) {
  if (!dataUrl) return Promise.reject(new Error('No PDF file'));
  var cached = _docCache.get(dataUrl);
  if (cached) return cached;
  var task;
  try {
    task = pdfjsLib.getDocument({ data: _dataUrlToBytes(dataUrl) });
  } catch (e) {
    return Promise.reject(e);
  }
  var promise = task.promise.catch(function (err) {
    _docCache.delete(dataUrl);
    throw err;
  });
  _docCache.set(dataUrl, promise);
  return promise;
}

export function clearPdf(dataUrl) {
  _docCache.delete(dataUrl);
}

// Renders pageNum (1-based) into canvas at the page's own native aspect
// ratio (never pre-fit/padded to the box) — canvas.width/height (the pixel
// buffer) are set to a resolution matching the frame box's LARGER dimension
// at device pixel ratio, so CSS object-fit (contain/cover/fill, driven by
// Display Mode) can fit this bitmap into the frame box crisply under any of
// the three modes, exactly like it already does for #media/#video's own
// intrinsic size — the canvas element's CSS box itself is always 100%/100%
// of the frame (see #pdf-canvas in presenter.html's <style>).
export async function renderPageToCanvas(doc, pageNum, canvas, opts) {
  opts = opts || {};
  var boxW = Math.max(1, opts.maxW || canvas.clientWidth || 800);
  var boxH = Math.max(1, opts.maxH || canvas.clientHeight || 600);
  var dpr = opts.dpr || Math.min(2, window.devicePixelRatio || 1);
  var page = await doc.getPage(Math.max(1, Math.min(doc.numPages, pageNum)));
  var base = page.getViewport({ scale: 1 });
  var longEdgeTarget = Math.max(boxW, boxH) * dpr;
  var scale = longEdgeTarget / Math.max(base.width, base.height);
  if (!isFinite(scale) || scale <= 0) scale = dpr;
  var viewport = page.getViewport({ scale: scale });
  canvas.width = Math.max(1, Math.round(viewport.width));
  canvas.height = Math.max(1, Math.round(viewport.height));
  var ctx = canvas.getContext('2d');
  await page.render({ canvasContext: ctx, viewport: viewport }).promise;
  return { width: base.width, height: base.height };
}

// Small offscreen render → data URL, for the horizontal page-thumbnail strip.
export async function renderPageThumbDataUrl(doc, pageNum, maxDim) {
  var page = await doc.getPage(Math.max(1, Math.min(doc.numPages, pageNum)));
  var base = page.getViewport({ scale: 1 });
  var scale = (maxDim || 96) / Math.max(base.width, base.height);
  var viewport = page.getViewport({ scale: scale });
  var canvas = document.createElement('canvas');
  canvas.width = Math.max(1, Math.round(viewport.width));
  canvas.height = Math.max(1, Math.round(viewport.height));
  var ctx = canvas.getContext('2d');
  await page.render({ canvasContext: ctx, viewport: viewport }).promise;
  return canvas.toDataURL('image/png');
}
