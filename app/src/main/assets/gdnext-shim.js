/*
 * gp-next Tauri shim（Android / WebView）
 *
 * 游戏包（pvzge_web-master）的 index.html 内联了一份 Tauri polyfill，其文件系统由 localStorage
 * 模拟：read_dir 恒返回空数组，write_text_file 的参数形状与真实命令不一致，导致 gp-next 的
 * 数据包系统在 WebView 下无法工作。
 *
 * 本脚本不修改游戏包，而是在页面中包装 __TAURI_INTERNALS__.invoke：
 *   - plugin:fs|*、plugin:path|resolve_directory、plugin:opener|open_path 交由原生处理；
 *   - 其余命令继续由原 polyfill 处理，行为不变。
 *
 * 数据目录约定（与 GameActivity.gpNextDir 一致）：
 *   AppData 根为 filesDir，游戏会在其后拼接 /gp-next，因此数据实际位于 filesDir/gp-next。
 *   读操作走 https://appassets.androidplatform.net/gp-next/... 虚拟路径，由 WebViewAssetLoader
 *   的 PathHandler 映射到该目录，流式返回原始字节，无需 base64 中转。
 *
 * 当 window.__gdForceJsModding === true 时代为调用一次 enableJsModding()，以解开游戏侧默认
 * 关闭、且无法从界面打开的 JS Modding 开关（见文件末尾）。
 */
(function () {
    'use strict';

    var bridge = window.GardendlessBridge;
    if (!bridge || window.__gdHooked) return;
    window.__gdHooked = true;

    var TAG = '[gd-next-shim]';
    var ORIGIN = 'https://appassets.androidplatform.net/';

    // ── 全屏与导出文件名 ──
    // 以下两项在 WebView 下必须由原生接管：
    // - 全屏：polyfill 将 plugin:window|set_fullscreen 实现为空函数，且 WebView 对非 video
    //   元素不回调 onShowCustomView；
    // - 导出文件名：polyfill 使用 prompt() 询问，而 WebView 不响应 prompt，恒返回 null。

    // 游戏启动约 2 秒后会按自身状态同步一次 setFullscreen(false)，覆盖掉由原生从
    // SharedPreferences 恢复的全屏状态。因此在启动保护期内忽略这条自动同步的 false；
    // true 一律放行，不影响用户主动操作。
    var bootGuard = true;
    setTimeout(function () { bootGuard = false; }, 6000);

    function setFullscreen(v) {
        if (bootGuard && !v) { bootGuard = false; return; }
        bootGuard = false;
        try { bridge.setFullscreen(!!v); } catch (e) { /* 桥不可用时静默降级 */ }
    }

    function setExportName(name) {
        if (!name) return;
        try { bridge.setExportName(String(name)); } catch (e) { /* 忽略 */ }
    }

    // 导出文件名：游戏通过 <a download="文件名" href="data:..."> 配合 click() 触发导出，
    // 文件名只存在于 a.download 上，不会传给原生，因此在此捕获
    function isDataHref(el) {
        return !!(el && el.getAttribute && (el.getAttribute('href') || '').indexOf('data:') === 0);
    }

    var origAnchorClick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function () {
        if (this.download && isDataHref(this)) setExportName(this.download);
        return origAnchorClick.apply(this, arguments);
    };
    document.addEventListener('click', function (e) {
        var a = e.target && e.target.closest ? e.target.closest('a[download]') : null;
        if (a && isDataHref(a)) setExportName(a.getAttribute('download'));
    }, true);

    // 标准 Fullscreen API 路径（游戏若改用该路径亦可覆盖）
    var ep = Element.prototype;
    var reqFullscreen = ep.requestFullscreen || ep.webkitRequestFullscreen || ep.webkitRequestFullScreen;
    if (reqFullscreen) {
        ep.requestFullscreen = function () { setFullscreen(true); return reqFullscreen.apply(this, arguments); };
    }
    var dp = Document.prototype;
    var exitFullscreen = dp.exitFullscreen || dp.webkitExitFullscreen || dp.webkitCancelFullScreen;
    if (exitFullscreen) {
        dp.exitFullscreen = function () { setFullscreen(false); return exitFullscreen.apply(this, arguments); };
    }

    // ── gp-next 数据目录 ──

    var appRoot = null;

    function appDataRoot() {
        if (appRoot === null) {
            try { appRoot = String(bridge.appDataRoot() || '').replace(/\\/g, '/').replace(/\/+$/, ''); }
            catch (e) { appRoot = ''; }
        }
        return appRoot;
    }

    // 游戏传给 plugin:fs 的路径为相对 AppData 的路径（如 gp-next\packs\Foo\pack.json），
    // 也可能拼接了 resolve_directory 的返回值而成为绝对路径；此处统一收敛为 "gp-next/xxx"。
    function relPath(rawPath) {
        var s = String(rawPath == null ? '' : rawPath).replace(/\\/g, '/').replace(/^\/+/, '');
        var root = appDataRoot();
        if (root && s.indexOf(root + '/') === 0) s = s.slice(root.length + 1);
        return s;
    }

    function urlOf(rawPath) {
        return ORIGIN + relPath(rawPath);
    }

    // 按 Tauri 语义，文件不存在时抛错，由游戏侧 catch 成 null / false。
    //
    // read_text_file 同样必须返回字节而非字符串：dist-js 的实现为
    //   `r instanceof ArrayBuffer ? r : Uint8Array.from(r)`，之后才交给 TextDecoder.decode。
    // 若返回字符串，Uint8Array.from 会按字符数组处理（每个字符转为 NaN 再变为 0），
    // 解码结果全为 \0。真实 Tauri 命令返回的也是原始字节（tauri::ipc::Response），
    // 因此两种读操作统一使用 arrayBuffer。
    function readBytes(rawPath, label) {
        return fetch(urlOf(rawPath), { cache: 'no-store' }).then(function (res) {
            if (!res.ok) throw new Error(label + ' failed (' + res.status + '): ' + relPath(rawPath));
            return res.arrayBuffer();
        });
    }

    function bodyToText(payload) {
        if (typeof payload === 'string') return payload;
        if (payload instanceof ArrayBuffer) return new TextDecoder().decode(new Uint8Array(payload));
        if (payload && payload.buffer instanceof ArrayBuffer) return new TextDecoder().decode(payload);
        return String(payload == null ? '' : payload);
    }

    function bodyToBytes(payload) {
        if (payload instanceof ArrayBuffer) return new Uint8Array(payload);
        if (payload && payload.buffer instanceof ArrayBuffer) return new Uint8Array(payload.buffer, payload.byteOffset, payload.byteLength);
        if (typeof payload === 'string') return new TextEncoder().encode(payload);
        return new Uint8Array(0);
    }

    // 分块编码，避免大文件在参数展开时导致栈溢出
    function bytesToBase64(bytes) {
        var chunk = 0x8000;
        var parts = '';
        for (var i = 0; i < bytes.length; i += chunk) {
            parts += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
        }
        return btoa(parts);
    }

    /** 读取 ReadableStream 的全部内容，返回 Uint8Array */
    function readStreamBytes(stream) {
        var reader = stream.getReader();
        var chunks = [];
        var total = 0;
        function pump() {
            return reader.read().then(function (r) {
                if (r.done) {
                    reader.releaseLock();
                    var out = new Uint8Array(total);
                    var offset = 0;
                    for (var i = 0; i < chunks.length; i++) {
                        out.set(chunks[i], offset);
                        offset += chunks[i].length;
                    }
                    return out;
                }
                chunks.push(r.value);
                total += r.value.length;
                return pump();
            });
        }
        return pump();
    }

    /**
     * 处理 gp-next 相关命令；返回 null 表示不接管，继续交由原 polyfill。
     *
     * @param {string} cmd 命令名
     * @param {object} args invoke 的第二个参数
     * @param {*} body invoke 的第二个参数原样（write_text_file 的 body 为 Uint8Array）
     * @param {object} options invoke 的第三个参数（write_text_file 的 path 位于 headers 中）
     */
    function handleGpNextCommand(cmd, args, body, options) {
        switch (cmd) {
            case 'plugin:path|resolve_directory':
                // 返回 AppData 根目录，游戏会自行拼上 /gp-next
                return Promise.resolve(appDataRoot() || '/');

            case 'plugin:fs|read_text_file':
                return readBytes(args.path, 'read_text_file');

            case 'plugin:fs|read_file':
                return readBytes(args.path, 'read_file');

            // dist-js 会把返回值直接交给 FileInfo 映射（首个字段即 isFile），
            // 因此必须返回对象；失败时抛错，不可返回 null，否则映射处会读取 null 的属性。
            case 'plugin:fs|stat':
            case 'plugin:fs|lstat': {
                var statJson = bridge.fsStat(relPath(args.path));
                if (statJson === null || statJson === undefined) {
                    throw new Error(cmd + ' failed: ' + relPath(args.path));
                }
                return Promise.resolve(JSON.parse(statJson));
            }

            case 'plugin:fs|rename': {
                // 新版 dist-js 新增的命令：invoke('plugin:fs|rename', { oldPath, newPath, options })
                if (!bridge.fsRename(relPath(args.oldPath), relPath(args.newPath))) {
                    throw new Error('rename failed: ' + relPath(args.oldPath));
                }
                return Promise.resolve(null);
            }

            case 'plugin:fs|write_file': {
                // 新版 dist-js 新增的命令：invoke('plugin:fs|write_file', bytes, { headers: { path, options } })
                var wfHeaders = (options && options.headers) || {};
                var wfPath = wfHeaders.path ? decodeURIComponent(wfHeaders.path) : (args && args.path);
                if (!wfPath) throw new Error('write_file requires a path');
                if (body && typeof body.getReader === 'function') {
                    return readStreamBytes(body).then(function (bytes) {
                        if (!bridge.fsWriteBytes(relPath(wfPath), bytesToBase64(bytes))) {
                            throw new Error('write_file failed: ' + wfPath);
                        }
                        return null;
                    });
                }
                if (!bridge.fsWriteBytes(relPath(wfPath), bytesToBase64(bodyToBytes(body)))) {
                    throw new Error('write_file failed: ' + wfPath);
                }
                return Promise.resolve(null);
            }

            case 'plugin:fs|read_dir': {
                var json = bridge.fsReadDir(relPath(args.path));
                return Promise.resolve(json === null ? [] : JSON.parse(json));
            }

            case 'plugin:fs|exists':
                return Promise.resolve(!!bridge.fsExists(relPath(args.path)));

            case 'plugin:fs|mkdir':
                bridge.fsMkdir(relPath(args.path));
                return Promise.resolve(null);

            case 'plugin:fs|remove':
                bridge.fsRemove(relPath(args.path));
                return Promise.resolve(null);

            case 'plugin:fs|write_text_file': {
                // 真实形态为 invoke(cmd, bodyBytes, { headers: { path, options } })。
                // polyfill 读取的是 args.headers.path，而 args 实际是 body，因此该命令在
                // polyfill 下始终失败。
                var headers = (options && options.headers) || {};
                var path = headers.path ? decodeURIComponent(headers.path) : (args && args.path);
                if (!path) throw new Error('write_text_file requires a path');
                if (!bridge.fsWriteText(relPath(path), bodyToText(body))) {
                    throw new Error('write_text_file failed: ' + path);
                }
                return Promise.resolve(null);
            }

            case 'plugin:opener|open_path':
                // 目前仅 patcher 页的「打开补丁文件夹」使用，落到系统的 gp-next 文档根
                try { bridge.openDataFolder(); } catch (e) { /* 忽略 */ }
                return Promise.resolve(null);
        }
        return null;
    }

    // 本脚本接管的命令：全部 plugin:fs|* 以及以下两个精确匹配项
    var HANDLED = /^plugin:fs\|/;
    var HANDLED_EXACT = ['plugin:path|resolve_directory', 'plugin:opener|open_path'];

    function patchTauri() {
        var ti = window.__TAURI_INTERNALS__;
        if (!ti || typeof ti.invoke !== 'function' || ti.__gdHooked) return false;
        ti.__gdHooked = true;

        var origInvoke = ti.invoke;
        ti.invoke = function (cmd, args, options) {
            var name = String(cmd || '');

            if (HANDLED_EXACT.indexOf(name) >= 0 || HANDLED.test(name)) {
                try {
                    var result = handleGpNextCommand(name, args || {}, args, options);
                    if (result) return result;
                    // 未接管的 fs 命令会落入 polyfill 的 default 分支并返回 null，
                    // 通常表现为难以定位的 "Cannot read properties of null"，故在此显式告警。
                    console.warn(TAG, 'unhandled command, falling back to polyfill:', name);
                } catch (e) {
                    console.warn(TAG, name + ' failed:', e);
                    return Promise.reject(e);
                }
            }

            if (name === 'plugin:window|set_fullscreen') {
                setFullscreen(!!(args && args.value));
                return Promise.resolve(null);
            }

            return origInvoke.apply(this, arguments);
        };
        console.log(TAG, 'installed, appData =', appDataRoot());
        return true;
    }

    // polyfill 在 head 中同步执行，可能晚于本脚本，故短暂轮询等待
    if (!patchTauri()) {
        var tries = 0;
        var timer = setInterval(function () {
            if (patchTauri() || ++tries > 100) clearInterval(timer);
        }, 50);
    }

    // ── JS Modding 自动启用 ──
    // 游戏侧 experimental.jsModding 默认关闭，且「实验性」页中的对应开关处于锁定状态
    // （pointer-events:none，回调会把 true 还原为 false），唯一入口是控制台的
    // window.gpNext.mods.enableJsModding()。因此这里在启动后代为调用一次。
    //
    // 幂等处理：enableJsModding() 会先持久化 experimental.jsModding=true，再重新载入 JS 模组。
    // 若已持久化为开启，则本次启动的初始加载已载入过模组，这里直接返回，避免多一次
    // dispose + reload；若持久化为关闭（含用户在界面中关闭），则重新开启。

    /** 读取 localStorage 中已持久化的 jsModding 开关 */
    function jsModdingPersisted() {
        try {
            var s = JSON.parse(localStorage.getItem('gp-next-settings') || '{}');
            return !!(s && s.experimental && s.experimental.jsModding === true);
        } catch (e) {
            return false;
        }
    }

    /** 尝试启用 JS Modding；返回 false 表示 window.gpNext.mods 尚未挂载 */
    function tryEnableJsModding() {
        var mods = window.gpNext && window.gpNext.mods;
        if (!mods || typeof mods.enableJsModding !== 'function') return false;
        if (jsModdingPersisted()) {
            console.log(TAG, 'JS Modding already enabled');
            return true;
        }
        Promise.resolve()
            .then(function () { return mods.enableJsModding(); })
            .then(function () { console.log(TAG, 'JS Modding enabled'); })
            .catch(function (e) { console.warn(TAG, 'enableJsModding failed:', e); });
        return true;
    }

    // window.gpNext.mods 由游戏在 phase-4 挂载（引擎就绪且补丁加载完成后），故轮询等待。
    // 上限 300 秒，覆盖引擎等待超时 30 秒与补丁加载的余量。
    if (window.__gdForceJsModding === true) {
        if (!tryEnableJsModding()) {
            var modTries = 0;
            var modTimer = setInterval(function () {
                if (tryEnableJsModding() || ++modTries > 3000) clearInterval(modTimer);
            }, 100);
        }
    }
})();
