/*
 * lowlatency-cast.js — front-end for the DLNA/UPnP "Direct OBS Stream" path.
 * Independent of the mirror-casting code: it only talks to window.LowLatencyCast
 * (LowLatencyCastBridge.java) and window.Android.clearPresenter() (existing), and only
 * touches the elements listed below.
 *
 * Expected markup (any place in controller.html — bind is by id):
 *   <input  id="obs-stream-url"   type="url" value="http://10.114.182">
 *   <button id="obs-stream-start" type="button">🎥 Switch TV to Live OBS</button>
 *   <button id="obs-stream-stop"  type="button">📄 Switch TV back to Verses/Lyrics</button>
 *   <span   id="direct-obs-status"></span>
 *
 * The presenter-side half (hiding its HTML layer while the TV plays the stream) lives in
 * presenter.html's own onDirectStreamStatus(); the bridge notifies both pages.
 */
(function () {
  'use strict';

  var bridge = window.LowLatencyCast;          // undefined in a desktop browser / old build
  var $ = function (id) { return document.getElementById(id); };

  function setStatus(text, state) {
    var el = $('direct-obs-status');
    if (!el) return;
    el.textContent = text;
    el.dataset.state = state || '';            // style with [data-state="error"] etc.
  }

  function setBusy(busy) {
    var b = $('obs-stream-start');
    if (b) b.disabled = busy;
  }

  // Java → JS. The bridge calls this for every state change.
  window.onDirectStreamStatus = function (s) {
    var state = (s && s.state) || '';
    setBusy(state === 'discovering' || state === 'connecting');
    setStatus((s && s.message) || '', state);
  };

  function start() {
    if (!bridge) { setStatus('Direct streaming is only available inside the app', 'error'); return; }
    var input = $('obs-stream-url');
    var url = (input ? input.value : '').trim();
    if (!url) { setStatus('Enter the OBS stream URL first', 'error'); return; }
    setBusy(true);
    setStatus('Starting…', 'connecting');
    // Returns false when the URL was rejected up-front; the reason is delivered via onDirectStreamStatus.
    if (!bridge.startDirectObsStream(url)) setBusy(false);
  }

  function stop() {
    if (bridge) bridge.stopDirectObsStream();
  }

  // Stop the hardware stream, then hand the display back to the normal presenter.
  function backToPresentation() {
    stop();
    if (window.Android) window.Android.clearPresenter();
  }

  function init() {
    // The OBS URL is saved/restored by controller.html (cast_obs_url) — not here.
    var startBtn = $('obs-stream-start'), stopBtn = $('obs-stream-stop');
    if (startBtn) startBtn.addEventListener('click', start);
    if (stopBtn) stopBtn.addEventListener('click', backToPresentation);
    if (!bridge) setStatus('Direct streaming unavailable', 'error');
  }

  // Public handle for other scripts / the console: DirectObsStream.start(...) etc.
  window.DirectObsStream = {
    start: function (url) { return bridge ? bridge.startDirectObsStream(String(url)) : false; },
    stop: stop,
    rescan: function () { if (bridge) bridge.discoverDirectRenderers(); },
    renderers: function () { return bridge ? JSON.parse(bridge.getDirectRenderers()) : []; },
    select: function (nameOrId) { if (bridge) bridge.selectDirectRenderer(String(nameOrId)); }
  };

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();
