/* ZeroBezel sync.js — injected into every WebView via evaluateJavascript on onPageFinished */
(function () {
    if (window.__zbSyncLoaded) return;
    window.__zbSyncLoaded = true;

    var isRemoteUpdate = false;
    var scrollTicking  = false;

    /* ── Throttled window-scroll sender (browser) ───────────────────────── */
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
            /* Include currentTime + sentAt so receiver can compensate for latency */
            window.AndroidBridge.sendEvent(JSON.stringify({
                app: 'video', action: 'play',
                time: video.currentTime,
                sentAt: Date.now()
            }));
        });

        video.addEventListener('pause', function () {
            if (isRemoteUpdate || !window.AndroidBridge) return;
            window.AndroidBridge.sendEvent(JSON.stringify({
                app: 'video', action: 'pause',
                time: video.currentTime
            }));
        });

        video.addEventListener('seeked', function () {
            /*
             * LOOP-PREVENTION: When applySync sets currentTime, it sets
             * video.__zbSeekFromRemote = true BEFORE the assignment.
             * The seeked event fires later; we detect and reset the flag here
             * so no echo is sent back.
             */
            if (video.__zbSeekFromRemote) {
                video.__zbSeekFromRemote = false;
                return;
            }
            if (isRemoteUpdate || !window.AndroidBridge) return;
            window.AndroidBridge.sendEvent(JSON.stringify({
                app: 'video', action: 'seek',
                time: video.currentTime,
                sentAt: Date.now()
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

    /* ── Master applySync receiver ───────────────────────────────────────── */
    window.applySync = function (jsonStr) {
        /*
         * PDF and Gallery pages define window.__zbPageApplySync to handle their
         * own custom events (element scroll, lightbox, transform).
         * For those pages, skip the generic handler entirely.
         */
        if (window.__zbPageApplySync) {
            window.__zbPageApplySync(jsonStr);
            return;
        }

        /* Generic handler: browser scroll + video controls */
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
                    /* Lag compensation: advance time by network round-trip estimate */
                    var lag = data.sentAt ? (Date.now() - data.sentAt) / 1000 : 0;
                    if (data.time !== undefined) {
                        v.__zbSeekFromRemote = true;
                        v.currentTime = Math.max(0, data.time + lag);
                    }
                    v.play().catch(function () {});
                });

            } else if (data.action === 'pause') {
                document.querySelectorAll('video').forEach(function (v) {
                    if (data.time !== undefined) {
                        v.__zbSeekFromRemote = true;
                        v.currentTime = data.time;
                    }
                    v.pause();
                });

            } else if (data.action === 'seek') {
                document.querySelectorAll('video').forEach(function (v) {
                    var lag = data.sentAt ? (Date.now() - data.sentAt) / 1000 : 0;
                    v.__zbSeekFromRemote = true;  /* Reset in seeked handler */
                    v.currentTime = Math.max(0, data.time + lag);
                });
            }

            setTimeout(function () { isRemoteUpdate = false; }, 200);
        } catch (e) {
            console.error('ZeroBezel applySync error:', e);
        }
    };

    console.log('ZeroBezel sync.js injected');
})();
