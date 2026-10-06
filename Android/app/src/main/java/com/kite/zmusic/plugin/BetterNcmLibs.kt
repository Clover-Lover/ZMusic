package com.kite.zmusic.plugin

/**
 * BetterNCM 插件会在自己的上下文里找 `loadedPlugins`。
 * 歌词、请求、当前歌曲、样式表按官方调用形状接上。
 * RefinedNowPlaying 只保证对象在。样式表、背景图、InfLink 和前端播放接到 ZMusic。
 */
internal object BetterNcmLibs {
    const val LYRIC_ASSET = "liblyric.js"

    val modules: List<Bundled> = listOf(
        Bundled("betterncm.lib.LibEAPIRequest", "LibEAPIRequest", "LibEAPIRequest"),
        Bundled("betterncm.lib.liblyric", "LibLyric", "liblyric"),
        Bundled("betterncm.lib.libsonginfo", "LibSongInfo", "libsonginfo"),
        Bundled("betterncm.lib.StylesheetLoader", "StylesheetLoader", "StylesheetLoader"),
        Bundled("betterncm.lib.list-scroll-fix", "ListScrollFix", "list-scroll-fix"),
        Bundled("betterncm.lib.light-dark-theme-switcher", "明暗主题切换", "light-dark-theme-switcher"),
        Bundled("betterncm.lib.RefinedNowPlaying", "RefinedNowPlaying", "RefinedNowPlaying"),
        Bundled("betterncm.lib.StyleSnippet", "StyleSnippet", "StyleSnippet"),
        Bundled("betterncm.lib.BGEnhanced", "BGEnhanced", "BGEnhanced"),
        Bundled("betterncm.lib.InfLinkrs", "InfLinkrs", "InfLinkrs"),
        Bundled("betterncm.lib.LibFrontendPlay", "LibFrontendPlay", "LibFrontendPlay"),
    )

    fun isModule(id: String): Boolean = modules.any { it.moduleId == id }

    fun moduleRecords(): List<PluginRecord> = modules.map { lib ->
        PluginRecord(
            id = lib.moduleId,
            name = lib.name,
            version = 1,
            entry = "index.js",
            engineMin = 1,
            engineMax = null,
            enabled = true,
            quarantined = false,
        )
    }

    fun isLyricUrl(url: String): Boolean = url.contains("/api/song/lyric")

    /**
     * 每个 BetterNCM 插件上下文里先放好依赖对象，再执行官方 LibLyric 脚本。
     * `injects[0]` 指向库自己，主题插件会从这里取 `loadStylesheet`。
     */
    fun bootScript(): String = """
        function __bncmSeal() {
          __bncmOnLoad = [];
          __bncmOnAll = [];
          __bncmOnConfig = [];
          plugin.loadError = null;
          plugin.finished = false;
        }
        function __bncmEmit(target, type, detail) {
          var list = target.__bncmListeners && target.__bncmListeners[type];
          if (!list) return;
          var evt = { type: type, target: target };
          if (detail !== undefined) evt.detail = detail;
          for (var i = 0; i < list.length; i++) {
            try { list[i].call(target, evt); } catch (e) {}
          }
        }
        function __bncmListen(target, type, fn) {
          if (!target.__bncmListeners) target.__bncmListeners = {};
          var list = target.__bncmListeners[type] || (target.__bncmListeners[type] = []);
          list.push(fn);
        }
        function __bncmLib(slug, name, version) {
          var entry = {
            manifest: { manifest_version: 1, name: name, slug: slug, version: version },
            pluginPath: "C:/plugins_runtime/" + slug,
            slug: slug,
            finished: true,
            addEventListener: function(type, fn) { __bncmListen(this, type, fn); },
            removeEventListener: function(type, fn) {
              var list = this.__bncmListeners && this.__bncmListeners[type];
              if (!list) return;
              this.__bncmListeners[type] = list.filter(function(item) { return item !== fn; });
            },
            dispatchEvent: function(evt) { __bncmEmit(this, evt && evt.type, evt && evt.detail); return true; }
          };
          entry.injects = [entry];
          entry.mainPlugin = entry;
          loadedPlugins[slug] = entry;
          return entry;
        }
        var __bncmEapi = __bncmLib("LibEAPIRequest", "LibEAPIRequest", "1.6.2");
        __bncmEapi.PlayState = { 1: "Playing", 2: "Pausing", Playing: 1, Pausing: 2 };
        __bncmEapi.NCM_IMAGE_CDNS = ["https://p1.music.126.net/", "https://p2.music.126.net/"];
        __bncmEapi.isRequestAvailable = function() { return true; };
        __bncmEapi.getRequestFnName = function() { return "zmusic"; };
        __bncmEapi.classname = function() {
          var names = [];
          for (var i = 0; i < arguments.length; i++) {
            var item = arguments[i];
            if (typeof item === "string" && names.indexOf(item) < 0) names.push(item);
          }
          return names.join(" ");
        };
        __bncmEapi.getNCMImageUrl = function(id) {
          var text = String(id == null ? "" : id);
          return "https://p1.music.126.net/" + text + "/" + text + ".jpg";
        };
        __bncmEapi.getPlayingSong = function() {
          var raw = __zmusicBncm("ncm.playing");
          if (!raw) return null;
          try { return JSON.parse(raw); } catch (e) { return null; }
        };
        __bncmEapi.eapiRequest = function(url, options) {
          options = options || {};
          var payload = JSON.stringify({
            url: String(url || ""),
            query: options.query || {},
            data: options.data || {}
          });
          var raw = __zmusicBncm("eapi", payload);
          var body = {};
          try { body = JSON.parse(raw); } catch (e) { body = { code: 500 }; }
          var failed = body && typeof body.code === "number" && body.code !== 200 && body.code !== 201;
          if (failed && options.onerror) options.onerror(body);
          else if (options.onload) options.onload(body);
          return Promise.resolve(body);
        };
        __bncmEapi.getLyric = function(id) {
          return __bncmEapi.eapiRequest("/api/song/lyric/v1", { type: "json", query: { id: id } });
        };
        __bncmEapi.getSongDetail = function() {
          var ids = Array.prototype.slice.call(arguments);
          return __bncmEapi.eapiRequest("/api/v3/song/detail", {
            type: "json",
            data: { c: JSON.stringify(ids.map(function(id) { return { id: id }; })) }
          });
        };
        __bncmEapi.getMusicURL = function(id, br) {
          return __bncmEapi.eapiRequest("/api/song/enhance/download/url", {
            method: "POST",
            type: "json",
            data: { id: id, br: br || 999000 }
          });
        };
        var __bncmSong = __bncmLib("libsonginfo", "LibSongInfo", "1.0.0");
        __bncmSong.trackPlaying = null;
        __bncmSong.autioId = "";
        __bncmSong.playState = 2;
        __bncmSong.duration = -1;
        __bncmSong.loadProgress = 0;
        __bncmSong.playProgress = 0;
        __bncmSong.getPlaying = function() {
          var raw = __zmusicBncm("ncm.playing");
          if (!raw) return null;
          try { return JSON.parse(raw); } catch (e) { return null; }
        };
        __bncmSong.getMusicId = function() {
          var song = __bncmSong.trackPlaying || __bncmSong.getPlaying();
          var origin = song && song.originFromTrack;
          if (origin && origin.lrcid) return origin.lrcid;
          if (origin && origin.track && origin.track.tid) return origin.track.tid;
          var data = song && song.data;
          return data && data.id ? data.id : 0;
        };
        function getPlaying() {
          return __bncmSong.getPlaying();
        }
        var __bncmNativeCalls = {};
        function __bncmNativeKey(name, channel) {
          return String(name) + "\n" + String(channel || "");
        }
        var legacyNativeCmder = {
          appendRegisterCall: function(name, channel, fn) {
            var key = __bncmNativeKey(name, channel);
            var list = __bncmNativeCalls[key] || (__bncmNativeCalls[key] = []);
            if (typeof fn === "function") list.push(fn);
            return true;
          },
          removeRegisterCall: function(name, channel, fn) {
            var key = __bncmNativeKey(name, channel);
            var list = __bncmNativeCalls[key] || [];
            __bncmNativeCalls[key] = list.filter(function(item) { return item !== fn; });
            return true;
          },
          triggerRegisterCall: function(name, channel) {
            var key = __bncmNativeKey(name, channel);
            var list = (__bncmNativeCalls[key] || []).slice();
            var args = Array.prototype.slice.call(arguments, 2);
            for (var i = 0; i < list.length; i++) {
              try { list[i].apply(null, args); } catch (e) {}
            }
          },
          call: function(name, cb, args) {
            var raw = __zmusicBncm("cmder", String(name || ""), JSON.stringify(args || []));
            var result = null;
            if (raw != null && raw !== "") {
              try { result = JSON.parse(String(raw)); } catch (e) { result = raw; }
            }
            if (typeof cb === "function") { try { cb(result); } catch (e2) {} }
            return result;
          },
          _envAdapter: {
            callAdapter: function(name, cb, args) {
              return legacyNativeCmder.call(name, cb, args);
            }
          }
        };
        var __bncmStateReady = false;
        setInterval(function() {
          var song = __bncmSong.getPlaying();
          var id = String(__bncmSong.getMusicId() || "");
          var duration = song && song.data && song.data.duration ? song.data.duration : -1;
          if (id !== __bncmSong.autioId) {
            var prevId = __bncmSong.autioId;
            if (prevId) {
              legacyNativeCmder.triggerRegisterCall("End", "audioplayer", prevId, { code: 0, from: "switch" });
            }
            __bncmSong.autioId = id;
            __bncmSong.trackPlaying = song;
            __bncmStateReady = false;
            __bncmEmit(__bncmSong, "audio-id-updated");
            legacyNativeCmder.triggerRegisterCall("Load", "audioplayer", id, {
              id: id,
              duration: duration > 0 ? duration / 1000 : 0
            });
            if (typeof __bncmPlay !== "undefined" && __bncmPlay) {
              __bncmEmit(__bncmPlay, "updateCurrentAudioPlayer", __bncmPlay.currentAudioPlayer);
            }
          }
          if (duration !== __bncmSong.duration) {
            __bncmSong.duration = duration;
            __bncmEmit(__bncmSong, "duration-updated");
          }
          var state = song && song.playing ? 1 : 2;
          if (state !== __bncmSong.playState || !__bncmStateReady) {
            __bncmSong.playState = state;
            __bncmStateReady = true;
            __bncmEmit(__bncmSong, "play-state-updated");
            legacyNativeCmder.triggerRegisterCall("PlayState", "audioplayer", id, state, state);
          }
          var progress = song && song.positionMs ? song.positionMs : 0;
          var loadedNow = duration > 0 ? 1 : 0;
          if (progress !== __bncmSong.playProgress) {
            __bncmSong.playProgress = progress;
            __bncmEmit(__bncmSong, "play-progress-updated");
            legacyNativeCmder.triggerRegisterCall("PlayProgress", "audioplayer", id, progress / 1000, loadedNow);
          }
          var loaded = loadedNow;
          if (loaded !== __bncmSong.loadProgress) {
            __bncmSong.loadProgress = loaded;
            __bncmEmit(__bncmSong, "load-progress-updated");
          }
          var volume = Number(__zmusicBncm("player.volume"));
          if (isNaN(volume)) volume = 1;
          if (volume !== __bncmSong.volume) {
            __bncmSong.volume = volume;
            legacyNativeCmder.triggerRegisterCall("Volume", "audioplayer", id, volume, volume, volume);
          }
        }, 500);
        var __bncmCss = __bncmLib("StylesheetLoader", "StylesheetLoader", "0.1.3");
        var __bncmSheets = [];
        function __bncmApplySheet(owner, path, id, config) {
          config = config || {};
          var css = "";
          try { css = betterncm_native.fs.readFileText(String(path)) || ""; } catch (e) { css = ""; }
          var vars = {};
          var flags = [];
          var unflags = [];
          for (var key in config) {
            var item = config[key] || {};
            var reflect = item.reflect || "cssVar";
            var value = item.default;
            try {
              if (owner && owner.getConfig) value = owner.getConfig(key, item.default);
            } catch (e) {}
            if (reflect === "cssVar" && item.key) vars[item.key] = value == null ? "" : String(value);
            if (reflect === "bodyFlag" && item.class) {
              if (value) flags.push(item.class);
              else unflags.push(item.class);
            }
          }
          __zmusicBncm("dom.style", String(id || path || "sheet"), String(css), JSON.stringify({
            vars: vars, flags: flags, unflags: unflags
          }));
          return Promise.resolve(true);
        }
        __bncmCss.loadStylesheet = function(owner, path, id, config) {
          __bncmSheets.push([owner, path, id, config]);
          return __bncmApplySheet(owner, path, id, config);
        };
        __bncmCss.reload = function() {
          for (var i = 0; i < __bncmSheets.length; i++) {
            var item = __bncmSheets[i];
            __bncmApplySheet(item[0], item[1], item[2], item[3]);
          }
          return Promise.resolve(true);
        };
        __bncmLib("list-scroll-fix", "ListScrollFix", "0.1.0");
        var __bncmTheme = __bncmLib("light-dark-theme-switcher", "明暗主题切换", "0.1.3");
        try { localStorage.setItem("cc.microblock.themeswitcher.theme", localStorage.getItem("cc.microblock.themeswitcher.theme") || "light"); } catch (e) {}
        __bncmTheme.switchToLight = function() {
          try { localStorage.setItem("cc.microblock.themeswitcher.theme", "light"); } catch (e) {}
          return true;
        };
        __bncmTheme.switchToDark = function() {
          try { localStorage.setItem("cc.microblock.themeswitcher.theme", "dark"); } catch (e) {}
          return true;
        };
        __bncmTheme.switchTheme = function() {
          var dark = false;
          try { dark = localStorage.getItem("cc.microblock.themeswitcher.theme") !== "light"; } catch (e) {}
          return dark ? __bncmTheme.switchToLight() : __bncmTheme.switchToDark();
        };
        __bncmLib("RefinedNowPlaying", "RefinedNowPlaying", "2.19.5");
        var __bncmSnippet = __bncmLib("StyleSnippet", "StyleSnippet", "0.2.2");
        __bncmSnippet.reload = function() { return __bncmCss.reload(); };
        var __bncmBg = __bncmLib("BGEnhanced", "BGEnhanced", "0.3.8");
        __bncmBg.setBackground = function(source) {
          var url = "";
          if (typeof source === "string") url = source;
          else if (source && typeof source === "object") url = source.url || source.src || source.image || "";
          url = String(url || "");
          if (/^https?:\/\//i.test(url)) {
            __zmusicBncm("dom.style", "BGEnhanced", "html{background-image:url(" + JSON.stringify(url) + ")}", "{}");
          }
          return Promise.resolve(true);
        };
        var __bncmRpc = {};
        var __bncmLink = __bncmLib("InfLinkrs", "InfLinkrs", "3.2.11");
        __bncmLink.register = function(name, fn) {
          if (typeof fn === "function") __bncmRpc[String(name)] = fn;
          return Promise.resolve(true);
        };
        __bncmLink.on = function(name, fn) { return __bncmLink.register(name, fn); };
        __bncmLink.call = function(name, cb) {
          var fn = __bncmRpc[String(name)];
          var args = Array.prototype.slice.call(arguments, 2);
          var result = null;
          if (typeof fn === "function") {
            try { result = fn.apply(null, args); } catch (e) {}
          }
          if (typeof cb === "function") { try { cb(result); } catch (e2) {} }
          return Promise.resolve(result);
        };
        __bncmLink.send = function(name, data) { return __bncmLink.call(name, null, data); };
        var __bncmPlay = __bncmLib("LibFrontendPlay", "LibFrontendPlay", "1.1.0");
        __bncmPlay.getPlaying = __bncmSong.getPlaying;
        __bncmPlay.play = function() {
          return String(__zmusicBncm("channel", "audioplayer.play", "[]")) === "true";
        };
        __bncmPlay.pause = function() {
          return String(__zmusicBncm("channel", "audioplayer.pause", "[]")) === "true";
        };
        __bncmPlay.currentAudioPlayer = {
          get paused() {
            var song = __bncmSong.getPlaying();
            return !(song && song.playing);
          },
          get currentTime() {
            var song = __bncmSong.getPlaying();
            return song && song.positionMs ? song.positionMs / 1000 : 0;
          },
          set currentTime(value) {
            channel.call("audioplayer.seek", null, ["", "", value]);
          },
          get volume() {
            var raw = __zmusicBncm("player.volume");
            var n = Number(raw);
            return isNaN(n) ? 1 : n;
          },
          set volume(value) {
            channel.call("audioplayer.setVolume", null, ["", "", value]);
          },
          get playbackRate() {
            var raw = __zmusicBncm("player.rate");
            var n = Number(raw);
            return isNaN(n) ? 1 : n;
          },
          set playbackRate(value) {
            __zmusicBncm("player.rate", String(value));
          },
          play: function() { return __bncmPlay.play(); },
          pause: function() { return __bncmPlay.pause(); },
          addEventListener: function(type, fn) { __bncmListen(this, type, fn); }
        };
        var InfLinkApi = {
          getCurrentSong: function() {
            var song = __bncmSong.getPlaying();
            var data = song && song.data || {};
            var artists = data.artists || [];
            var names = [];
            for (var i = 0; i < artists.length; i++) {
              if (artists[i] && artists[i].name) names.push(artists[i].name);
            }
            return {
              id: data.id || 0,
              songName: data.name || "",
              artists: names.join(" / "),
              coverUrl: (data.album && data.album.picUrl) || data.coverUrl || ""
            };
          },
          play: function() { return __bncmPlay.play(); },
          pause: function() { return __bncmPlay.pause(); },
          addEventListener: function(type, fn) {
            if (typeof fn !== "function") return;
            var mapped = type === "songChange" ? "audio-id-updated" : String(type || "");
            __bncmListen(__bncmSong, mapped, function() { fn({ type: type, detail: InfLinkApi.getCurrentSong() }); });
          }
        };
        var __bncmMarket = __bncmLib("PluginMarket", "PluginMarket", "1.0.0");
        __bncmMarket.addTemporaryCustomSource = function() { return true; };
        if (typeof fetch === "undefined") {
          var fetch = function() {
            return Promise.resolve({
              ok: false,
              status: 0,
              json: function() { return Promise.resolve({}); },
              text: function() { return Promise.resolve(""); }
            });
          };
        }
        if (typeof legacyNativeCmder === "undefined") {
          var legacyNativeCmder = {
            appendRegisterCall: function() { return true; },
            triggerRegisterCall: function() {}
          };
        }
        if (typeof channel === "undefined") {
          var channel = {
            call: function(name, cb) {
              if (typeof cb === "function") { try { cb(); } catch (e) {} }
              return Promise.resolve(null);
            },
            registerCall: function() {},
            encryptId: function(id) { return String(id); }
          };
        }
        if (typeof React === "undefined") {
          var __bncmReactHooks = [];
          var __bncmReactCursor = 0;
          var __bncmReactRoot = null;
          function __bncmReactMount(vnode, parent) {
            if (vnode == null || vnode === false || vnode === true) return;
            if (Array.isArray(vnode)) {
              for (var i = 0; i < vnode.length; i++) __bncmReactMount(vnode[i], parent);
              return;
            }
            if (typeof vnode === "string" || typeof vnode === "number") {
              var text = document.createElement("span");
              text.textContent = String(vnode);
              parent.appendChild(text);
              return;
            }
            if (typeof vnode.type === "function") {
              __bncmReactMount(vnode.type(vnode.props || {}), parent);
              return;
            }
            if (vnode.type === "fragment") {
              var kids = vnode.children || [];
              for (var c = 0; c < kids.length; c++) __bncmReactMount(kids[c], parent);
              return;
            }
            if (typeof vnode.type !== "string") return;
            var el = document.createElement(vnode.type);
            var props = vnode.props || {};
            if (props.className) el.className = String(props.className);
            if (props.id) el.id = String(props.id);
            if (typeof props.onClick === "function") el.addEventListener("click", props.onClick);
            parent.appendChild(el);
            var children = vnode.children || [];
            for (var k = 0; k < children.length; k++) __bncmReactMount(children[k], el);
          }
          function __bncmReactPaint(vnode, container) {
            if (!container || !container.__bncmNode) return;
            __zmusicBncm("dom.clear", container.__bncmNode);
            __bncmReactCursor = 0;
            __bncmReactMount(vnode, container);
          }
          var React = {
            createElement: function(type, props) {
              var children = [];
              var raw = Array.prototype.slice.call(arguments, 2);
              for (var i = 0; i < raw.length; i++) {
                if (Array.isArray(raw[i])) children = children.concat(raw[i]);
                else children.push(raw[i]);
              }
              var nodeProps = props || {};
              if (nodeProps.children != null) {
                var extra = Array.isArray(nodeProps.children) ? nodeProps.children : [nodeProps.children];
                children = extra.concat(children);
              }
              return { __bncmEl: true, type: type, props: nodeProps, children: children };
            },
            Fragment: "fragment",
            Component: function() {},
            PureComponent: function() {},
            useState: function(init) {
              var index = __bncmReactCursor++;
              if (__bncmReactHooks.length <= index) {
                __bncmReactHooks.push(typeof init === "function" ? init() : init);
              }
              return [__bncmReactHooks[index], function(next) {
                var current = __bncmReactHooks[index];
                __bncmReactHooks[index] = typeof next === "function" ? next(current) : next;
                if (__bncmReactRoot) {
                  setTimeout(function() {
                    __bncmReactPaint(__bncmReactRoot.vnode, __bncmReactRoot.container);
                  }, 0);
                }
              }];
            },
            useEffect: function(fn) { if (typeof fn === "function") setTimeout(fn, 0); },
            useLayoutEffect: function(fn) { if (typeof fn === "function") fn(); },
            useMemo: function(fn) { return fn(); },
            useCallback: function(fn) { return fn; },
            useRef: function(value) { return { current: value }; },
            useContext: function() { return {}; },
            createContext: function() { return { Provider: "provider", Consumer: "consumer" }; },
            forwardRef: function(fn) { return fn; },
            memo: function(fn) { return fn; },
            cloneElement: function(el) { return el; },
            isValidElement: function(el) { return !!(el && el.__bncmEl); },
            Children: {
              map: function(children, fn) {
                var list = Array.isArray(children) ? children : (children == null ? [] : [children]);
                var out = [];
                for (var i = 0; i < list.length; i++) out.push(fn(list[i], i));
                return out;
              },
              forEach: function(children, fn) {
                var list = Array.isArray(children) ? children : (children == null ? [] : [children]);
                for (var i = 0; i < list.length; i++) fn(list[i], i);
              },
              count: function(children) {
                if (children == null) return 0;
                return Array.isArray(children) ? children.length : 1;
              },
              toArray: function(children) {
                if (children == null) return [];
                return Array.isArray(children) ? children.slice() : [children];
              }
            }
          };
          var ReactDOM = {
            render: function(vnode, container) {
              __bncmReactRoot = { vnode: vnode, container: container };
              __bncmReactPaint(vnode, container);
              return container;
            },
            createRoot: function(container) {
              return {
                render: function(vnode) { ReactDOM.render(vnode, container); },
                unmount: function() { if (container && container.__bncmNode) __zmusicBncm("dom.clear", container.__bncmNode); }
              };
            },
            unmountComponentAtNode: function(container) {
              if (container && container.__bncmNode) __zmusicBncm("dom.clear", container.__bncmNode);
            },
            findDOMNode: function(el) { return el && el.__bncmNode ? el : null; }
          };
        }
        if (typeof document === "undefined") {
          function __bncmEl(tag) {
            var nodeId = __zmusicBncm("dom.create", String(tag || "div"), "{}", "[]");
            return __bncmNode(nodeId, tag);
          }
          var __bncmDocEvents = new EventTarget();
          var document = {
            createElement: function(tag) { return __bncmEl(tag); },
            getElementById: function(id) { return __bncmById[id] || null; },
            getElementsByTagName: function(tag) {
              var raw = __zmusicBncm("dom.queryAll", String(tag || ""));
              var ids = [];
              try { ids = JSON.parse(raw || "[]"); } catch (e) { ids = []; }
              var out = [];
              for (var i = 0; i < ids.length; i++) out.push(__bncmNode(ids[i]));
              return out;
            },
            createTextNode: function(text) {
              var node = document.createElement("span");
              node.textContent = text == null ? "" : String(text);
              return node;
            },
            querySelector: function(sel) {
              var text = String(sel || "");
              if (text === ":root" || text === "html") return document.documentElement;
              if (text === "head") return document.head;
              if (text === "body") return document.body;
              var found = __zmusicBncm("dom.query", text);
              return found ? __bncmNode(found) : null;
            },
            querySelectorAll: function(sel) {
              var raw = __zmusicBncm("dom.queryAll", String(sel || ""));
              var ids = [];
              try { ids = JSON.parse(raw || "[]"); } catch (e) { ids = []; }
              var out = [];
              for (var i = 0; i < ids.length; i++) out.push(__bncmNode(ids[i]));
              return out;
            },
            addEventListener: function(type, listener, options) {
              __bncmDocEvents.addEventListener(type, listener, options);
            },
            removeEventListener: function(type, listener) {
              __bncmDocEvents.removeEventListener(type, listener);
            },
            dispatchEvent: function(event) {
              return __bncmDocEvents.dispatchEvent(event);
            }
          };
          document.head = __bncmEl("head");
          document.body = __bncmEl("body");
          document.documentElement = __bncmEl("html");
        }
    """.trimIndent()

    data class Bundled(
        val moduleId: String,
        val name: String,
        val slug: String,
    )
}
