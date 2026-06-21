/* ZeroBezel sync.js — injected into every WebView via evaluateJavascript on onPageFinished */
(function () {
    if (window.__zbSyncLoaded) return;
    window.__zbSyncLoaded = true;

    var isRemoteUpdate = false;
    var scrollTicking  = false;

    /* ── URL semantic comparison helper ── */
    function isUrlSemanticallyDifferent(url1, url2) {
        if (url1 === url2) return false;
        try {
            var u1 = new URL(url1, window.location.href);
            var u2 = new URL(url2, window.location.href);
            if (u1.origin !== u2.origin) return true;
            if (u1.pathname !== u2.pathname) return true;
            
            var ignoredParams = ['t', 'start', 'time', 'utm_source', 'utm_medium', 'utm_campaign', 'origin', 'feature'];
            
            function getCleanParams(urlObj) {
                var params = [];
                urlObj.searchParams.forEach(function (val, key) {
                    if (ignoredParams.indexOf(key) === -1) {
                        params.push(key + '=' + val);
                    }
                });
                params.sort();
                return params.join('&');
            }
            
            return getCleanParams(u1) !== getCleanParams(u2);
        } catch (e) {
            return url1 !== url2;
        }
    }

    /* ── Scroll helper: works for normal pages AND pages with custom scroll roots ── */
    function getScrollEl() {
        /* document.scrollingElement is the "real" scrolling element per spec.
           Falls back to documentElement for older Android WebViews. */
        return document.scrollingElement || document.documentElement;
    }

    /* ── Throttled scroll sender ─────────────────────────────────────────── */
    window.addEventListener('scroll', function () {
        if (isRemoteUpdate || scrollTicking) return;
        scrollTicking = true;
        requestAnimationFrame(function () {
            var el   = getScrollEl();
            var maxX = Math.max(1, el.scrollWidth  - window.innerWidth);
            var maxY = Math.max(1, el.scrollHeight - window.innerHeight);
            var normX = Math.min(Math.max(el.scrollLeft / maxX, 0), 1);
            var normY = Math.min(Math.max(el.scrollTop  / maxY, 0), 1);
            if (window.AndroidBridge) {
                window.AndroidBridge.sendEvent(JSON.stringify({
                    app: 'all', action: 'scroll', x: normX, y: normY
                }));
            }
            scrollTicking = false;
        });
    }, { passive: true });

    /* ── Browser click sync ──────────────────────────────────────────────── */
    document.addEventListener('click', function (e) {
        if (isRemoteUpdate || !window.AndroidBridge) return;
        /* Skip clicks that we synthesised ourselves */
        if (e.__zbRemote) return;
        var el   = getScrollEl();
        var nx   = (window.scrollX + e.clientX) / Math.max(1, el.scrollWidth);
        var ny   = (window.scrollY + e.clientY) / Math.max(1, el.scrollHeight);
        window.AndroidBridge.sendEvent(JSON.stringify({
            app: 'browser', action: 'click', nx: nx, ny: ny
        }));
    }, { capture: true, passive: true });

    /* ── History API intercept (SPA route changes) ───────────────────────── */
    (function () {
        var lastSentUrl = window.location.href;
        function wrapHistory(method) {
            var orig = history[method];
            history[method] = function () {
                var result = orig.apply(this, arguments);
                var newUrl = window.location.href;
                if (!isRemoteUpdate && window.AndroidBridge && isUrlSemanticallyDifferent(lastSentUrl, newUrl)) {
                    lastSentUrl = newUrl;
                    window.AndroidBridge.sendEvent(JSON.stringify({
                        app: 'browser', action: 'load_url', url: newUrl
                    }));
                }
                return result;
            };
        }
        if (window.history) {
            wrapHistory('pushState');
            wrapHistory('replaceState');
        }
    })();

    /* ── Video event listeners ───────────────────────────────────────────── */
    function attachVideo(video) {
        if (video.__zbAttached) return;
        video.__zbAttached = true;

        video.addEventListener('play', function () {
            if (isRemoteUpdate) return;
            if (video.__zbIgnoreNextPlay) {
                video.__zbIgnoreNextPlay = false;
                return;
            }

            video.pause();
            video.__zbIgnoreNextPause = true;

            var t = video.currentTime;
            if (window.AndroidBridge) {
                window.AndroidBridge.sendEvent(JSON.stringify({
                    app: 'video', action: 'play',
                    time: t, delay: 250
                }));
            }

            setTimeout(function () {
                video.__zbIgnoreNextPlay = true;
                video.play().catch(function () {});
            }, 250);
        });

        video.addEventListener('pause', function () {
            if (isRemoteUpdate) return;
            if (video.__zbIgnoreNextPause) {
                video.__zbIgnoreNextPause = false;
                return;
            }
            if (window.AndroidBridge) {
                window.AndroidBridge.sendEvent(JSON.stringify({
                    app: 'video', action: 'pause',
                    time: video.currentTime
                }));
            }
        });

        video.addEventListener('seeked', function () {
            if (video.__zbSeekFromRemote) {
                video.__zbSeekFromRemote = false;
                return;
            }
            if (isRemoteUpdate || !window.AndroidBridge) return;

            var wasPaused = video.paused;
            var t = video.currentTime;

            if (!wasPaused) {
                video.pause();
                video.__zbIgnoreNextPause = true;
            }

            window.AndroidBridge.sendEvent(JSON.stringify({
                app: 'video', action: 'seek',
                time: t,
                wasPlaying: !wasPaused,
                delay: 250
            }));

            if (!wasPaused) {
                setTimeout(function () {
                    video.__zbIgnoreNextPlay = true;
                    video.play().catch(function () {});
                }, 250);
            }
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
         * PDF and Gallery pages define window.__zbPageApplySync.
         * Those pages fully own their own sync (custom element scroll,
         * lightbox, transform, zoom). The generic handler is skipped.
         */
        if (window.__zbPageApplySync) {
            window.__zbPageApplySync(jsonStr);
            return;
        }

        /* Generic handler: browser + video */
        try {
            var data = JSON.parse(jsonStr);
            isRemoteUpdate = true;

            if (data.action === 'scroll') {
                var el   = getScrollEl();
                var maxX = Math.max(1, el.scrollWidth  - window.innerWidth);
                var maxY = Math.max(1, el.scrollHeight - window.innerHeight);
                window.scrollTo(data.x * maxX, data.y * maxY);

            } else if (data.action === 'zoom') {
                document.body.style.transform       = 'scale(' + data.scale + ')';
                document.body.style.transformOrigin = 'top left';

            } else if (data.action === 'play') {
                document.querySelectorAll('video').forEach(function (v) {
                    if (data.time !== undefined) {
                        v.__zbSeekFromRemote = true;
                        v.currentTime = data.time;
                    }
                    var delay = data.delay ? 180 : 0;
                    v.__zbIgnoreNextPlay = true;
                    setTimeout(function () {
                        v.__zbIgnoreNextPlay = true;
                        v.play().catch(function () {});
                    }, delay);
                });

            } else if (data.action === 'pause') {
                document.querySelectorAll('video').forEach(function (v) {
                    if (data.time !== undefined) {
                        v.__zbSeekFromRemote = true;
                        v.currentTime = data.time;
                    }
                    v.__zbIgnoreNextPause = true;
                    v.pause();
                });

            } else if (data.action === 'seek') {
                document.querySelectorAll('video').forEach(function (v) {
                    v.__zbSeekFromRemote = true;
                    v.currentTime = data.time;
                    if (data.wasPlaying) {
                        v.__zbIgnoreNextPause = true;
                        v.pause();
                        var delay = data.delay ? 180 : 0;
                        setTimeout(function () {
                            v.__zbIgnoreNextPlay = true;
                            v.play().catch(function () {});
                        }, delay);
                    }
                });

            } else if (data.action === 'click') {
                /* Synthesise a click at the same normalised document position */
                var el   = getScrollEl();
                var absX = data.nx * el.scrollWidth;
                var absY = data.ny * el.scrollHeight;
                var cx   = absX - window.scrollX;
                var cy   = absY - window.scrollY;
                var target = document.elementFromPoint(cx, cy);
                if (target) {
                    var evt = new MouseEvent('click', {
                        bubbles: true, cancelable: true, clientX: cx, clientY: cy
                    });
                    evt.__zbRemote = true;  /* Suppress re-broadcast in capture listener */
                    target.dispatchEvent(evt);
                }

            } else if (data.action === 'load_url') {
                /* SPA navigation from History API intercept */
                var url = data.url;
                if (url && isUrlSemanticallyDifferent(window.location.href, url)) {
                    window.location.href = url;
                }
            } else if (data.action === 'open_file') {
                if (data.app === 'video') {
                    var select = document.getElementById('video-select');
                    if (select && select.value !== data.id) {
                        select.value = data.id;
                        if (window.loadVideo) {
                            window.loadVideo(data.id, data.name);
                        }
                    }
                }
            }

            setTimeout(function () { isRemoteUpdate = false; }, 200);
        } catch (e) {
            console.error('ZeroBezel applySync error:', e);
        }
    };

    console.log('ZeroBezel sync.js injected');
})();
