// PPTX rendering engine for Cast Control Sources — thin wrapper around
// @aiden0z/pptx-renderer's standalone browser build, shared by controller.html
// (slide-count + thumbnail generation for the Source Controls panel) and
// presenter.html (full slide render into the shared #media-frame-box).
import { PptxViewer, parseZip, buildPresentation, RECOMMENDED_ZIP_LIMITS } from '../pptx-renderer/aiden0z-pptx-renderer.browser.es.js';

// One entry per distinct dataUrl: { presentation, thumbViewer, thumbHost,
// thumbHandles:Map<index,SlideHandle> }. presentation is the parsed model
// (shared, cheap) — thumbViewer is a hidden, never-rendered-into-its-own-
// container PptxViewer that owns presentationData for
// renderThumbnailToContainer() calls. Live/Preview rendering (presenter.html)
// keeps its OWN viewer bound to the real visible container — see
// loadForDisplay()/renderSlideInto() below — so a thumbnail request from
// controller.html never competes with what's on air.
var _deckCache = new Map(); // dataUrl -> Promise<DeckEntry>

function _dataUrlToArrayBuffer(dataUrl) {
  var comma = dataUrl.indexOf(',');
  var base64 = comma >= 0 ? dataUrl.slice(comma + 1) : dataUrl;
  var binary = atob(base64);
  var bytes = new Uint8Array(binary.length);
  for (var i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes.buffer;
}

function _hiddenHost() {
  var el = document.createElement('div');
  el.style.cssText = 'position:fixed;left:-9999px;top:-9999px;width:960px;height:540px;overflow:hidden;pointer-events:none;';
  document.body.appendChild(el);
  return el;
}

// Parses (but does not render) a deck — gives slideCount/slideWidth/
// slideHeight immediately, and is the model used for on-demand thumbnails.
// Rejects on an invalid/corrupted/unsupported PPTX.
export function loadPptxModel(dataUrl) {
  if (!dataUrl) return Promise.reject(new Error('No PowerPoint file'));
  var cached = _deckCache.get(dataUrl);
  if (cached) return cached;
  var promise = (async function () {
    var buffer = _dataUrlToArrayBuffer(dataUrl);
    var files = await parseZip(buffer, RECOMMENDED_ZIP_LIMITS);
    var presentation = buildPresentation(files);
    var thumbHost = _hiddenHost();
    var thumbViewer = new PptxViewer(thumbHost, { fitMode: 'none' });
    thumbViewer.load(presentation);
    return { presentation: presentation, thumbViewer: thumbViewer, thumbHost: thumbHost, thumbHandles: new Map() };
  })();
  promise.catch(function () { _deckCache.delete(dataUrl); });
  _deckCache.set(dataUrl, promise);
  return promise;
}

// Disposes every currently-rendered thumbnail SlideHandle for a deck without
// dropping the parsed model itself — called before re-populating the
// thumbnail strip (controller.html rebuilds that DOM via innerHTML on every
// Source Controls re-render, e.g. after each page/slide navigation), so
// handles never keep pointing at now-detached elements / leaking blob URLs.
export async function disposeThumbs(dataUrl) {
  var entry = await (_deckCache.get(dataUrl) || Promise.resolve(null));
  if (!entry) return;
  entry.thumbHandles.forEach(function (h) { try { h.dispose(); } catch (e) {} });
  entry.thumbHandles.clear();
}

export function clearPptx(dataUrl) {
  var entry = _deckCache.get(dataUrl);
  _deckCache.delete(dataUrl);
  if (entry && entry.then) {
    entry.then(function (e) { _disposeEntry(e); }).catch(function () {});
  }
}

function _disposeEntry(entry) {
  if (!entry) return;
  entry.thumbHandles.forEach(function (h) { try { h.dispose(); } catch (e) {} });
  entry.thumbHandles.clear();
  try { entry.thumbViewer.destroy(); } catch (e) {}
  if (entry.thumbHost && entry.thumbHost.parentNode) entry.thumbHost.parentNode.removeChild(entry.thumbHost);
}

// Renders slideIndex's (0-based) thumbnail into containerEl (an already-
// visible strip button). Reuses/disposes the previous handle for the same
// index on this deck so repeated calls (panel re-render) don't leak blob URLs.
export async function renderThumbInto(dataUrl, slideIndex, containerEl, width) {
  var entry = await loadPptxModel(dataUrl);
  var prev = entry.thumbHandles.get(slideIndex);
  if (prev) { try { prev.dispose(); } catch (e) {} entry.thumbHandles.delete(slideIndex); }
  var handle = entry.thumbViewer.renderThumbnailToContainer(slideIndex, containerEl, { width: width || 96 });
  if (handle) {
    entry.thumbHandles.set(slideIndex, handle);
    if (handle.ready) await handle.ready;
  }
  return handle;
}

// ---- Full-resolution single-slide display (presenter.html) ----
// One live viewer per distinct dataUrl+containerEl pair (in practice one per
// presenter.html instance — Preview/Live/the real cast display each run
// their own copy of this module). Caching by dataUrl means switching slides
// on the SAME deck never re-parses the zip/XML.
var _displayViewer = null; // { dataUrl, viewer }

export async function renderSlideInto(dataUrl, slideIndex, containerEl) {
  if (!_displayViewer || _displayViewer.dataUrl !== dataUrl || _displayViewer.viewer.container !== containerEl) {
    if (_displayViewer) { try { _displayViewer.viewer.destroy(); } catch (e) {} }
    var buffer = _dataUrlToArrayBuffer(dataUrl);
    var viewer = new PptxViewer(containerEl, { fitMode: 'none' });
    await viewer.open(buffer, { renderMode: 'slide' });
    _displayViewer = { dataUrl: dataUrl, viewer: viewer };
  }
  var viewer = _displayViewer.viewer;
  if (viewer.currentSlideIndex !== slideIndex) await viewer.renderSlide(slideIndex);
  return { slideWidth: viewer.slideWidth, slideHeight: viewer.slideHeight, slideCount: viewer.slideCount };
}
