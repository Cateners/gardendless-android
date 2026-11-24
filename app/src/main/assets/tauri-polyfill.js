/*
 * Tauri polyfill（Android / WebView）
 *
 * 来源：游戏包 index.html 内联的 polyfill（0.15 及更早版本）。
 * 0.16 起游戏包移除了它，导致 window.__TAURI_INTERNALS__ 与 window.isTauri 都不存在。
 *
 * 与上游的差异：
 * 1. 补上 Tauri 真实运行时会设置的 window.isTauri；
 * 2. 补上 __TAURI__.dialog —— 游戏导出存档依赖它，缺失时 a.dialog.save 会抛
 *    TypeError 且无法被捕获，导出直接中断；
 * 3. __TAURI__.fs.writeTextFile 识别导出占位路径，转交原生弹 SAF 保存。
 *
 * 注入顺序：本文件 → gdnext-shim.js（后者包装 __TAURI_INTERNALS__.invoke，
 * 把 plugin:fs|* 等命令改路由到真实文件系统）。
 */
window.isTauri = true;

(function () {
    'use strict';

    console.log('[Tauri Polyfill] Initializing...');

    // 导出占位路径：dialog.save 返回它，随后的 writeTextFile 命中时转交原生。
    // WebView 无法同步得知用户选择的真实路径，只能先占位、写入时再弹框。
    const EXPORT_PREFIX = 'gd-export://';

    const storage = {
        files: new Map(),
        directories: new Set(['gp-next', 'gp-next/packs', 'gp-next/patches', 'gp-next/__gpn_edits']),

        init() {
            try {
                const saved = localStorage.getItem('__tauri_fs_mock');
                if (saved) {
                    const data = JSON.parse(saved);
                    this.files = new Map(Object.entries(data));
                }
            } catch (e) {
                console.warn('[Polyfill] Failed to restore files:', e);
            }
            this.ensureDefaultFiles();
        },

        ensureDefaultFiles() {
            const defaultSettings = {
                version: 1,
                packOrder: [],
                disabledPacks: [],
                scrollSensitivity: { enabled: true, wheel: 1, discreteMinIntervalMs: 0 }
            };
            if (!this.files.has('gp-next/settings.json')) {
                this.files.set('gp-next/settings.json', JSON.stringify(defaultSettings, null, 2));
            }
        },

        save() {
            try {
                const data = Object.fromEntries(this.files);
                localStorage.setItem('__tauri_fs_mock', JSON.stringify(data));
            } catch (e) {
                console.warn('[Polyfill] Failed to save files:', e);
            }
        },

        readFile(path) {
            const normalized = this.normalizePath(path);
            const content = this.files.get(normalized);
            if (content === undefined) {
                throw new Error(`File not found:${path}`);
            }
            return content;
        },

        writeFile(path, content) {
            const normalized = this.normalizePath(path);
            this.files.set(normalized, content);
            this.save();
        },

        deleteFile(path) {
            const normalized = this.normalizePath(path);
            this.files.delete(normalized);
            this.save();
        },

        readDir(path) {
            const normalized = this.normalizePath(path);
            const files = [];
            const prefix = normalized + (normalized.endsWith('/') ? '' : '/');

            for (const filePath of this.files.keys()) {
                if (filePath.startsWith(prefix)) {
                    const relative = filePath.substring(prefix.length);
                    const firstPart = relative.split('/')[0];
                    if (!files.includes(firstPart)) {
                        files.push({
                            name: firstPart,
                            isFile: !relative.includes('/') && !firstPart.includes('.')
                        });
                    }
                }
            }

            for (const dir of this.directories) {
                if (dir.startsWith(prefix)) {
                    const name = dir.substring(prefix.length).split('/')[0];
                    if (name && !files.some(f => f.name === name)) {
                        files.push({ name, isFile: false });
                    }
                }
            }

            return files;
        },

        exists(path) {
            const normalized = this.normalizePath(path);
            return this.files.has(normalized) || this.directories.has(normalized);
        },

        createDir(path, recursive = true) {
            const normalized = this.normalizePath(path);
            this.directories.add(normalized);
            this.save();
        },

        normalizePath(path) {
            let normalized = path.replace(/^gp-next[\\/]?/i, '');
            normalized = normalized.replace(/\\/g, '/');
            return normalized;
        }
    };

    let callbackId = 0;
    const callbacks = new Map();

    function transformCallback(callback, once = false) {
        const id = ++callbackId;
        callbacks.set(id, { callback, once });
        return id;
    }

    function handleCallback(id, payload) {
        const entry = callbacks.get(id);
        if (entry) {
            entry.callback(payload);
            if (entry.once) {
                callbacks.delete(id);
            }
        }
    }

    async function invokeTauriAPI(cmd, args) {
        console.debug(`[Tauri API]${cmd}`, args);
        await new Promise(resolve => setTimeout(resolve, 10));

        try {
            switch (cmd) {
                case 'plugin:fs|read_text_file':
                    return storage.readFile(args.path);

                case 'plugin:fs|read_file': {
                    const content = storage.readFile(args.path);
                    return new TextEncoder().encode(content).buffer;
                }

                case 'plugin:fs|write_text_file':
                    storage.writeFile(args.headers.path, args.payload);
                    return null;

                case 'plugin:fs|read_dir':
                    return storage.readDir(args.path);

                case 'plugin:fs|exists':
                    return storage.exists(args.path);

                case 'plugin:fs|mkdir':
                    storage.createDir(args.path, args.options?.recursive);
                    return null;

                case 'plugin:fs|remove':
                    storage.deleteFile(args.path);
                    return null;

                case 'plugin:dialog|save': {
                    // WebView 不响应 prompt，这里恒为 null；游戏因此会直接放弃导出。
                    // 导出存档实际走 __TAURI__.dialog.save（见文件末尾），不经过此分支。
                    const defaultPath = args.options?.defaultPath || 'download.json';
                    const fileName = prompt('保存文件为:', defaultPath);
                    if (fileName) {
                        return fileName;
                    }
                    return null;
                }

                case 'plugin:window|get_all_windows':
                    return [{ label: 'main' }];

                case 'plugin:window|inner_size':
                    return { width: window.innerWidth, height: window.innerHeight };

                case 'plugin:window|outer_size':
                    return { width: window.outerWidth, height: window.outerHeight };

                case 'plugin:window|inner_position':
                    return { x: window.screenX, y: window.screenY };

                case 'plugin:window|outer_position':
                    return { x: window.screenX, y: window.screenY };

                case 'plugin:window|is_fullscreen':
                    return !!document.fullscreenElement;

                case 'plugin:window|is_maximized':
                    return false;

                case 'plugin:window|is_minimized':
                    return false;

                case 'plugin:window|is_focused':
                    return document.hasFocus();

                case 'plugin:window|scale_factor':
                    return window.devicePixelRatio || 1;

                case 'plugin:window|set_fullscreen':
                    return null;

                case 'plugin:window|set_size':
                    console.warn('[Polyfill] Cannot set window size in browser');
                    return null;

                case 'plugin:window|set_position':
                    console.warn('[Polyfill] Cannot set window position in browser');
                    return null;

                case 'plugin:window|center':
                    console.warn('[Polyfill] Cannot center window in browser');
                    return null;

                case 'plugin:window|close':
                    window.close();
                    return null;

                case 'plugin:window|hide':
                    console.warn('[Polyfill] Cannot hide window in browser');
                    return null;

                case 'plugin:window|show':
                    console.warn('[Polyfill] Cannot show window in browser');
                    return null;

                case 'plugin:path|resolve_directory':
                    return args.directory === 14 ? '/gp-next' : '/';

                case 'plugin:path|basename':
                    return args.path.split(/[/\\]/).pop();

                case 'plugin:path|dirname': {
                    const parts = args.path.split(/[/\\]/);
                    parts.pop();
                    return parts.join('/');
                }

                case 'plugin:opener|open_url':
                    window.open(args.url, '_blank');
                    return null;

                case 'plugin:opener|open_path':
                    console.warn('[Polyfill] Cannot open system path:', args.path);
                    return null;

                case 'plugin:drpc|spawn_thread':
                    console.log('[Polyfill] Discord RPC would spawn with ID:', args.id);
                    return null;

                case 'plugin:drpc|destroy_thread':
                    console.log('[Polyfill] Discord RPC would destroy');
                    return null;

                case 'plugin:drpc|is_running':
                    return false;

                case 'plugin:drpc|set_activity':
                    console.log('[Polyfill] Discord RPC would set activity:', args.activityJson);
                    return null;

                case 'plugin:image|from_bytes':
                case 'plugin:image|from_path':
                    return { rid: Date.now() };

                case 'plugin:image|rgba':
                    return new Uint8Array([0, 0, 0, 0]);

                case 'plugin:image|size':
                    return { width: 64, height: 64 };

                case 'plugin:event|listen':
                    return transformCallback(args.handler);

                case 'plugin:event|unlisten':
                    callbacks.delete(args.eventId);
                    return null;

                case 'plugin:event|emit':
                    console.log('[Polyfill] Event emitted:', args.event, args.payload);
                    return null;

                default:
                    console.warn(`[Polyfill]Unknown Tauri command:${cmd}`, args);
                    return null;
            }
        } catch (error) {
            console.error(`[Polyfill]Error in ${cmd}:`, error);
            throw error;
        }
    }

    if (!window.__TAURI_INTERNALS__) {
        window.__TAURI_INTERNALS__ = {
            metadata: { currentWindow: { label: 'main' } },
            transformCallback: transformCallback,
            invoke: async function (cmd, args = {}) {
                try {
                    return await invokeTauriAPI(cmd, args);
                } catch (error) {
                    console.warn(`[Polyfill]API call failed,returning default:${cmd}`, error);
                    return getDefaultResponse(cmd);
                }
            }
        };
    }

    function getDefaultResponse(cmd) {
        if (cmd.includes('read_text_file') || cmd.includes('read_file')) return '{}';
        if (cmd.includes('read_dir')) return [];
        if (cmd.includes('exists')) return false;
        if (cmd.includes('is_')) return false;
        return null;
    }

    window.__TAURI_IPC_KEY__ = '__TAURI_IPC_KEY__';

    if (!window.__TAURI__) {
        window.__TAURI__ = {
            window: {
                getCurrent: () => ({
                    label: 'main',
                    listen: (event, handler) => {
                        console.log(`[Polyfill]Listening to event:${event}`);
                        return () => {};
                    }
                })
            },

            // 游戏导出存档时调用：a.dialog.save({ defaultPath }) → 随后 writeTextFile。
            dialog: {
                save: function (options) {
                    let name = (options && options.defaultPath) || 'download.json';
                    name = String(name).split(/[\\/]/).pop();
                    console.log('[Polyfill] dialog.save ->', name);
                    return Promise.resolve(EXPORT_PREFIX + encodeURIComponent(name));
                }
            },

            fs: {
                readTextFile: (path) => storage.readFile(path),
                writeTextFile: function (path, content) {
                    // 命中导出占位路径：不写 mock 存储，转交原生弹系统保存对话框
                    if (typeof path === 'string' && path.indexOf(EXPORT_PREFIX) === 0) {
                        const name = decodeURIComponent(path.slice(EXPORT_PREFIX.length));
                        console.log('[Polyfill] writeTextFile -> export:', name);
                        try {
                            window.GardendlessBridge.exportText(name, String(content));
                        } catch (e) {
                            console.warn('[Polyfill] export failed:', e);
                        }
                        return Promise.resolve();
                    }
                    storage.writeFile(path, content);
                    return Promise.resolve();
                }
            }
        };
    }

    if (!window.electron) {
        window.electron = {
            shell: {
                openExternal: async (url) => {
                    window.open(url, '_blank');
                    return true;
                }
            },
            ipcRenderer: {
                send: (channel, ...args) => {
                    console.log(`[Electron Mock]send:${channel}`, args);
                },
                sendSync: (channel, ...args) => {
                    console.log(`[Electron Mock]sendSync:${channel}`, args);
                    return null;
                },
                on: (channel, handler) => {
                    console.log(`[Electron Mock]on:${channel}`);
                }
            }
        };
    }

    storage.init();

    window.__tauriPolyfillDebug = {
        storage: storage,
        listFiles: () => Array.from(storage.files.keys()),
        clearStorage: () => {
            storage.files.clear();
            storage.save();
            console.log('[Polyfill] Storage cleared');
        },
        exportData: () => {
            const data = Object.fromEntries(storage.files);
            console.log('[Polyfill] Exporting data:', data);
            return data;
        }
    };

    console.log('[Tauri Polyfill] Initialized successfully');
    console.log('[Tauri Polyfill] Use window.__tauriPolyfillDebug for debugging');
})();
