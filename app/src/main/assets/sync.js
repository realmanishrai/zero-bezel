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

    /* ── Click Selector Generator Sync ── */
    document.addEventListener('click', function (e) {
        if (isRemoteUpdate || !window.AndroidBridge) return;
        
        var target = e.target.closest('a, button, input, [role="button"]');
        if (target) {
            var selector = generateSelector(target);
            window.AndroidBridge.sendEvent(JSON.stringify({
                app: 'all',
                action: 'click_element',
                selector: selector
            }));
        }
    }, true);

    function generateSelector(el) {
        if (el.id) return '#' + el.id;
        if (el.className) {
            var classes = el.className.split(/\s+/).filter(Boolean);
            if (classes.length > 0) {
                return el.tagName.toLowerCase() + '.' + classes.join('.');
            }
        }
        return el.tagName.toLowerCase();
    }

    /* ── Split View Custom Pinch & Pan Gestures ── */
    var currentExtendScale = 1.0;
    var panX = 0;
    var panY = 0;
    var pDist0 = 0, pScale0 = 1.0, pinching = false;
    var lastTouchX = 0, lastTouchY = 0, panning = false;

    window.addEventListener('touchstart', function (e) {
        if (!window.__zbExtendDisplay) return;
        if (e.touches.length === 2) {
            pinching = true;
            pDist0   = Math.hypot(e.touches[1].clientX - e.touches[0].clientX,
                                  e.touches[1].clientY - e.touches[0].clientY);
            pScale0   = currentExtendScale;
            e.preventDefault();
        } else if (e.touches.length === 1) {
            panning = true;
            lastTouchX = e.touches[0].clientX;
            lastTouchY = e.touches[0].clientY;
        }
    }, { passive: false });

    window.addEventListener('touchmove', function (e) {
        if (!window.__zbExtendDisplay) return;
        if (pinching && e.touches.length >= 2) {
            e.preventDefault();
            var d = Math.hypot(e.touches[1].clientX - e.touches[0].clientX,
                                 e.touches[1].clientY - e.touches[0].clientY);
            var newScale = Math.min(Math.max(pScale0 * (d / pDist0), 1.0), 6.0);
            applyExtendScaleAndPan(newScale, panX, panY);

            if (window.AndroidBridge) {
                window.AndroidBridge.sendEvent(JSON.stringify({
                    app: 'all', action: 'extend_zoom', scale: newScale, panX: panX, panY: panY
                }));
            }
        } else if (panning && e.touches.length === 1) {
            var dx = e.touches[0].clientX - lastTouchX;
            var dy = e.touches[0].clientY - lastTouchY;
            lastTouchX = e.touches[0].clientX;
            lastTouchY = e.touches[0].clientY;

            panX += dx / currentExtendScale;
            panY += dy / currentExtendScale;

            panX = Math.min(0, panX);
            panY = Math.min(0, panY);

            applyExtendScaleAndPan(currentExtendScale, panX, panY);

            if (window.AndroidBridge) {
                window.AndroidBridge.sendEvent(JSON.stringify({
                    app: 'all', action: 'extend_pan', scale: currentExtendScale, panX: panX, panY: panY
                }));
            }
        }
    }, { passive: false });

    window.addEventListener('touchend', function (e) {
        if (!window.__zbExtendDisplay) return;
        if (pinching && e.touches.length < 2) {
            pinching = false;
        }
        if (panning && e.touches.length === 0) {
            panning = false;
        }
    }, { passive: true });

    function applyExtendScaleAndPan(scale, px, py) {
        currentExtendScale = scale;
        panX = px;
        panY = py;
        
        var isClient = window.__zbIsClient;
        if (isClient) {
            document.documentElement.style.transform = 'translateX(-50%) scale(' + scale + ') translate(' + px + 'px, ' + py + 'px)';
        } else {
            document.documentElement.style.transform = 'scale(' + scale + ') translate(' + px + 'px, ' + py + 'px)';
        }
        document.documentElement.style.transformOrigin = 'top left';
    }

    function fixFixedElements(enabled) {
        var els = document.querySelectorAll('*');
        for (var i = 0; i < els.length; i++) {
            var el = els[i];
            if (enabled) {
                var style = window.getComputedStyle(el);
                if (style.position === 'fixed' || style.position === 'sticky') {
                    el.__zbOriginalPosition = el.style.position || style.position;
                    el.style.setProperty('position', 'absolute', 'important');
                }
            } else {
                if (el.__zbOriginalPosition) {
                    el.style.setProperty('position', el.__zbOriginalPosition, '');
                    el.__zbOriginalPosition = null;
                }
            }
        }
    }

    window.setExtendDisplay = function(enabled) {
        window.__zbExtendDisplay = enabled;
        var styleId = 'zb-split-style';
        var style = document.getElementById(styleId);
        
        if (enabled) {
            if (!style) {
                style = document.createElement('style');
                style.id = styleId;
                style.type = 'text/css';
                style.innerHTML = window.__zbSplitCss || 'html, body { transform-origin: top left !important; width: 200vw !important; height: 100vh !important; overflow: hidden !important; }';
                document.head.appendChild(style);
            }
            fixFixedElements(true);
            applyExtendScaleAndPan(1.0, 0, 0);
        } else {
            if (style) {
                style.parentNode.removeChild(style);
            }
            fixFixedElements(false);
            document.documentElement.style.transform = '';
            document.documentElement.style.transformOrigin = '';
        }
    };

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

            } else if (data.action === 'click_element') {
                var el = document.querySelector(data.selector);
                if (el) {
                    isRemoteUpdate = true;
                    el.click();
                    setTimeout(function () { isRemoteUpdate = false; }, 200);
                }

            } else if (data.action === 'extend_zoom') {
                if (window.__zbExtendDisplay) {
                    var px = data.panX !== undefined ? data.panX : panX;
                    var py = data.panY !== undefined ? data.panY : panY;
                    applyExtendScaleAndPan(data.scale, px, py);
                }

            } else if (data.action === 'extend_pan') {
                if (window.__zbExtendDisplay) {
                    applyExtendScaleAndPan(data.scale, data.panX, data.panY);
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
