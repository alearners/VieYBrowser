/* Vie 6.5.0 移植：控制台桥(viek.console / end) + Java HTTP 代理(gm / XHR) */
(function () {
    if (window.__vieBridge) return;
    window.__vieBridge = 1;

    /* ---------- 1. console.* 转发到 Java，落盘运行日志 ---------- */
    var raw = {
        log: console.log, error: console.error, warn: console.warn,
        info: console.info, debug: console.debug
    };
    function send(level, args) {
        try {
            var msg = Array.prototype.map.call(args, function (a) {
                if (a instanceof Error) return a.stack || a.message;
                if (typeof a === 'object') { try { return JSON.stringify(a); } catch (e) { return String(a); } }
                return String(a);
            }).join(' ');
            if (msg.length > 2000) msg = msg.slice(0, 2000) + '…';
            viey.console(level, msg);
        } catch (e) { }
    }
    ['log', 'error', 'warn', 'info', 'debug'].forEach(function (k) {
        console[k] = function () {
            send(k, arguments);
            if (raw[k]) { try { raw[k].apply(console, arguments); } catch (e) { } }
        };
    });
    window.addEventListener('error', function (e) {
        send('error', [(e.message || '') + ' @' + (e.filename || '') + ':' + (e.lineno || 0)]);
    });
    window.addEventListener('unhandledrejection', function (e) {
        send('error', ['unhandledrejection: ' + e.reason]);
    });

    /* ---------- 2. HTTP 请求走 Java（绕同源限制，等价 6.5.0 的 gm 桥） ---------- */
    var seq = 0, pend = {};
    window.vieHttpDone = function (id, json) {
        var cb = pend[id];
        if (!cb) return;
        delete pend[id];
        var r;
        try { r = JSON.parse(json); } catch (e) { r = { ok: false, error: 'bad-response' }; }
        cb(r);
    };

    function b64ToBytes(b64) {
        var bin = atob(b64), arr = new Uint8Array(bin.length);
        for (var i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
        return arr;
    }

    window.viek = window.viek || {};
    window.viek.http = function (url, opts, cb) {
        opts = opts || {};
        var id = 'r' + (++seq);
        pend[id] = function (r) {
            if (!r.ok) { if (cb) cb({ ok: false, error: r.error || 'network' }, null); return; }
            var rtype = (opts.responseType || '').toLowerCase();
            var body = r.body || '';
            var out = null;
            try {
                if (rtype === 'json') out = JSON.parse(body);
                else if (rtype === 'arraybuffer') out = b64ToBytes(body).buffer;
                else if (rtype === 'blob') out = new Blob([b64ToBytes(body)]);
                else if (rtype === 'document') out = new DOMParser().parseFromString(body, 'text/html');
            } catch (e) { out = null; }
            var res = {
                status: r.status, statusText: String(r.status), readyState: 4,
                responseHeaders: r.headers || '', finalUrl: r.url || url,
                responseText: body, response: out, responseXML: (rtype === 'document' ? out : null)
            };
            if (cb) cb(res, out);
        };
        try {
            viey.http(id, (opts.method || 'GET').toUpperCase(), url,
                JSON.stringify(opts.headers || {}),
                (typeof opts.body === 'string' ? opts.body : (opts.body ? JSON.stringify(opts.body) : '')),
                (opts.responseType || ''), (opts.timeout || 20000));
        } catch (e) {
            delete pend[id];
            if (cb) cb({ ok: false, error: 'bridge-missing' }, null);
        }
        return id;
    };

    /* 兼容 6.5.0 脚本里的 viek.fetch（返回 Promise） */
    window.viek.fetch = function (url, opts) {
        return new Promise(function (resolve, reject) {
            window.viek.http(url, opts || {}, function (r) {
                if (r.ok === false) reject(new Error(r.error || 'network'));
                else resolve(r);
            });
        });
    };
})();
