/* ZeroBezel sync.js — injected into every WebView via evaluateJavascript */
(function () {
    // Guard: do not inject twice on the same page
    if (window.__zbSyncLoaded) return;
    window.__zbSyncLoaded = true;

    var isRemoteUpdate = false;
    var scrollTicking  = false;

    /* ── Throttled scroll sender ─────────────────────────────────────────── */
    window.addEventListener('scroll', function () {
        if (isRemoteUpdate || scrollTicking) return;
        scrollTicking = true;
        requestAnimationFrame(function () {
            var docEl = document.documentElement;
            var maxX  = Math.max(1, docEl.scrollWidth  - window.innerWidth);
            var maxY  = Math.max(1, docEl.scrollHeight - window.innerHeight);
            var normX = Math.min(Math.max(window.scrollX / maxX, 0), 1);
            var normY = Math.min(Math.max(window.scrollY / maxY, 0), 1);
            if (window.AndroidBridge) {
                window.AndroidBridge.sendEvent(JSON.stringify({
                    app: 'all', action: 'scroll', x: normX, y: normY
                }));
            }
            scrollTicking = false;
        });
    }, { passive: true });

    /* ── Video event listeners ───────────────────────────────────────────── */
    function attachVideo(video) {
        if (video.__zbAttached) return;
        video.__zbAttached = true;

        video.addEventListener('play', function () {
            if (isRemoteUpdate || !window.AndroidBridge) return;
            window.AndroidBridge.sendEvent(JSON.stringify({
                app: 'video', action: 'play'
            }));
        });
        video.addEventListener('pause', function () {
            if (isRemoteUpdate || !window.AndroidBridge) return;
            window.AndroidBridge.sendEvent(JSON.stringify({
                app: 'video', action: 'pause'
            }));
        });
        video.addEventListener('seeked', function () {
            if (isRemoteUpdate || !window.AndroidBridge) return;
            window.AndroidBridge.sendEvent(JSON.stringify({
                app: 'video', action: 'seek', time: video.currentTime
            }));
        });
    }

    function attachAllVideos() {
        document.querySelectorAll('video').forEach(attachVideo);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', attachAllVideos);
    } else {
        attachAllVideos();
    }

    /* Watch for videos added after initial load (e.g. lazy-loaded players) */
    if (window.MutationObserver) {
        new MutationObserver(function (mutations) {
            mutations.forEach(function (m) {
                m.addedNodes.forEach(function (node) {
                    if (node.tagName === 'VIDEO') { attachVideo(node); }
                    if (node.querySelectorAll) {
                        node.querySelectorAll('video').forEach(attachVideo);
                    }
                });
            });
        }).observe(document.documentElement, { childList: true, subtree: true });
    }

    /* ── Receive sync commands from Kotlin ──────────────────────────────── */
    window.applySync = function (jsonStr) {
        try {
            var data = JSON.parse(jsonStr);
            isRemoteUpdate = true;

            if (data.action === 'scroll') {
                var docEl = document.documentElement;
                var maxX  = Math.max(1, docEl.scrollWidth  - window.innerWidth);
                var maxY  = Math.max(1, docEl.scrollHeight - window.innerHeight);
                window.scrollTo(data.x * maxX, data.y * maxY);

            } else if (data.action === 'zoom') {
                document.body.style.transform       = 'scale(' + data.scale + ')';
                document.body.style.transformOrigin = 'top left';

            } else if (data.action === 'play') {
                document.querySelectorAll('video').forEach(function (v) {
                    v.play().catch(function () {});
                });

            } else if (data.action === 'pause') {
                document.querySelectorAll('video').forEach(function (v) { v.pause(); });

            } else if (data.action === 'seek') {
                document.querySelectorAll('video').forEach(function (v) {
                    v.currentTime = data.time;
                });
            }

            /* Reset guard after JS event loop settles */
            setTimeout(function () { isRemoteUpdate = false; }, 150);
        } catch (e) {
            console.error('ZeroBezel applySync error:', e);
        }
    };

    console.log('ZeroBezel sync.js injected successfully');
})();
