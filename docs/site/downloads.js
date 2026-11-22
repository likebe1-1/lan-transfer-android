/* LanLink 文件下载页：GitHub Releases 文件清单 + 多镜像下载（纯静态，无后端）。 */
(() => {
  "use strict";

  const REPO = "likebe1-1/lan-transfer-android";
  const FILES_URL = "docs/files.json";
  const FILES_RAW = "https://raw.githubusercontent.com/" + REPO + "/master/docs/files.json";
  const JSDELIVR_FILES = "https://cdn.jsdelivr.net/gh/" + REPO + "@master/docs/files.json";
  const JSDELIVR_MIRRORS = "https://cdn.jsdelivr.net/gh/" + REPO + "@master/docs/mirrors.json";
  const MIRRORS_URL = "docs/mirrors.json";
  const MIRRORS_RAW = "https://raw.githubusercontent.com/" + REPO + "/master/docs/mirrors.json";
  const API_RELEASES = "https://api.github.com/repos/" + REPO + "/releases?per_page=100";

  // 国内主源（Gitee）：仓库与文件镜像，raw 直连稳定
  const GITEE_REPO = "likebe1-1/lanlink-android";
  const GITEE_FILES_RAW = "https://gitee.com/" + GITEE_REPO + "/raw/master/files.json";
  const GITEE_MIRRORS_RAW = "https://gitee.com/" + GITEE_REPO + "/raw/master/mirrors.json";
  const STORAGE = {
    mirrors: "lanlink.dl.mirrors",
    preferred: "lanlink.dl.preferred",
    theme: "lanlink.theme"
  };

  const $ = (selector, scope = document) => scope.querySelector(selector);
  const $$ = (selector, scope = document) => Array.from(scope.querySelectorAll(selector));

  // ------------------------------------------------------------ 小工具

  const storage = {
    get(key, fallback) {
      try {
        const raw = localStorage.getItem(key);
        return raw ? JSON.parse(raw) : fallback;
      } catch (e) { return fallback; }
    },
    set(key, value) {
      try { localStorage.setItem(key, JSON.stringify(value)); } catch (e) { /* ignore */ }
    }
  };

  let banner = null;
  function notify(message, kind = "info") {
    if (!banner) {
      banner = document.createElement("div");
      banner.className = "toast";
      document.body.appendChild(banner);
    }
    banner.textContent = message;
    banner.dataset.kind = kind;
    banner.classList.add("show");
    clearTimeout(notify.timer);
    notify.timer = setTimeout(() => banner.classList.remove("show"), 3200);
  }

  function escapeHtml(value) {
    return String(value == null ? "" : value)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  function formatSize(bytes) {
    const value = Number(bytes);
    if (!Number.isFinite(value) || value <= 0) return "—";
    if (value < 1024) return value + " B";
    if (value < 1024 * 1024) return (value / 1024).toFixed(1) + " KB";
    if (value < 1024 * 1024 * 1024) return (value / 1024 / 1024).toFixed(2) + " MB";
    return (value / 1024 / 1024 / 1024).toFixed(2) + " GB";
  }

  function formatDate(value) {
    if (!value) return "—";
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return "—";
    return date.toLocaleDateString("zh-CN", { year: "numeric", month: "2-digit", day: "2-digit" });
  }

  function extensionOf(name) {
    const index = String(name).lastIndexOf(".");
    return index >= 0 ? String(name).slice(index + 1).toLowerCase() : "";
  }

  // 商店分发专用产物（F-Droid/izzy 的 fdroid flavor）不作为公开下载项，
  // 避免用户在官网下到关闭了应用内更新的包。
  function isStoreOnlyArtifact(name) {
    return /-fdroid-/i.test(String(name));
  }

  function kindOf(name) {
    const ext = extensionOf(name);
    if (ext === "apk" || ext === "aab" || ext === "ipa") return "apk";
    if (["zip", "7z", "rar", "tar", "gz", "tgz", "xz", "bz2"].includes(ext)) return "zip";
    if (["pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv", "json"].includes(ext)) return "doc";
    if (["png", "jpg", "jpeg", "webp", "gif", "svg", "bmp"].includes(ext)) return "image";
    if (["mp4", "mkv", "mov", "webm", "avi", "mp3", "flac", "wav"].includes(ext)) return "video";
    return "other";
  }

  const ICONS = { apk: "🤖", zip: "🗜️", doc: "📄", image: "🖼️", video: "🎬", other: "📦" };

  // ------------------------------------------------------------ 数据加载

  function withTimeout(promise, ms, label) {
    return Promise.race([
      promise,
      new Promise((resolve) => setTimeout(() => resolve({ __timeout: true, label }), ms))
    ]);
  }

  async function fetchJSON(urls, options = {}) {
    const perUrl = options.perUrlTimeout || 5000;
    const totalBudget = options.totalBudget || 15000;
    const startedAt = Date.now();
    let lastError = null;
    for (const url of urls) {
      if (Date.now() - startedAt > totalBudget) { lastError = new Error("加载超时"); break; }
      try {
        const response = await withTimeout(fetch(url, { cache: "no-store" }), 6000, url);
        if (!response || response.__timeout) { lastError = new Error(url + " → 超时"); continue; }
        if (!response.ok) { lastError = new Error(url + " → HTTP " + response.status); continue; }
        const data = await withTimeout(response.json(), 6000, url);
        if (!data || data.__timeout) { lastError = new Error(url + " → 解析超时"); continue; }
        return { data, url };
      } catch (error) {
        lastError = error;
      }
    }
    throw lastError || new Error("无法加载数据");
  }

  /** 文件清单：优先 docs/files.json（同源），再走 raw 与镜像。 */
  async function loadFiles(mirrors) {
    const candidates = [FILES_URL, FILES_RAW, JSDELIVR_FILES];
    for (const mirror of mirrors) {
      if (mirror.prefix) candidates.push(mirror.prefix + FILES_RAW);
    }
    try {
      const result = await fetchJSON(candidates, { perUrlTimeout: 4000, totalBudget: 20000 });
      const files = (Array.isArray(result.data.files) ? result.data.files : [])
        .filter((file) => !isStoreOnlyArtifact(file.name));
      return { files, source: result.url, generatedAt: result.data.generatedAt || "" };
    } catch (error) {
      // 兜底：GitHub API（未登录每小时 60 次，仅在清单不可用时使用），结果本地缓存 24 小时
      const cached = storage.get("lanlink.dl.apiCache", null);
      if (cached && cached.at && Date.now() - cached.at < 24 * 3600 * 1000 && Array.isArray(cached.files)) {
        return { files: cached.files, source: "GitHub API（本地缓存）", generatedAt: new Date(cached.at).toISOString() };
      }
      const apiResponse = await withTimeout(
        fetch(API_RELEASES, { headers: { Accept: "application/vnd.github+json" }, cache: "no-store" }),
        8000,
        "github api"
      );
      if (!apiResponse || apiResponse.__timeout) throw error;
      if (!apiResponse.ok) throw error;
      const releases = await apiResponse.json();
      const files = [];
      for (const release of releases) {
        if (release.draft) continue;
        for (const asset of release.assets || []) {
          if (isStoreOnlyArtifact(asset.name)) continue;
          files.push({
            id: release.tag_name + "/" + asset.name,
            name: asset.name,
            tag: release.tag_name,
            size: asset.size,
            sha256: String(asset.digest || "").replace(/^sha256:/i, ""),
            url: asset.browser_download_url,
            downloadCount: asset.download_count || 0,
            publishedAt: release.published_at || "",
            mirrorable: true
          });
        }
      }
      storage.set("lanlink.dl.apiCache", { at: Date.now(), files });
      return { files, source: "GitHub API（清单不可用时的兜底）", generatedAt: "" };
    }
  }

  async function loadMirrors() {
    const defaults = {
      mirrors: [
        { name: "gitee", kind: "gitee", enabled: true },
        { name: "github", prefix: "", kind: "direct", enabled: true },
        { name: "ghproxy", prefix: "https://ghproxy.net/", kind: "mirror", enabled: true },
        { name: "gh-proxy", prefix: "https://gh-proxy.com/", enabled: true },
        { name: "direct", prefix: "", enabled: true }
      ],
      updatedAt: ""
    };
    const candidates = [MIRRORS_URL, MIRRORS_RAW, JSDELIVR_MIRRORS];
    try {
      const result = await fetchJSON(candidates, { perUrlTimeout: 3000, totalBudget: 8000 });
      const data = result.data || {};
      const sources = Array.isArray(data.sources) ? data.sources : [];
      const rawMirrors = Array.isArray(data.mirrors) ? data.mirrors : [];
      const list = [];
      for (const source of sources) {
        if (!source || !source.name) continue;
        if (source.kind === "direct" && source.name === "github") {
          list.push({ name: "github", prefix: "", kind: "direct", enabled: source.enabled !== false });
        } else if (source.kind === "direct" && source.name === "gitee") {
          list.push({ name: "gitee", kind: "gitee", enabled: source.enabled !== false });
        }
      }
      for (const mirror of rawMirrors) {
        if (!mirror || !mirror.name) continue;
        list.push({
          name: mirror.name,
          prefix: mirror.prefix || "",
          // 镜像前缀只代理 GitHub；空前缀即 GitHub 直连
          kind: mirror.name === "gitee" ? "gitee" : (mirror.prefix ? "mirror" : "direct"),
          enabled: mirror.enabled !== false
        });
      }

      if (!list.length) return defaults;
      return { mirrors: list, updatedAt: data.updatedAt || "" };
    } catch (error) {
      return defaults;
    }
  }

  // ------------------------------------------------------------ 镜像状态

  let mirrors = [];
  let mirrorPrefs = storage.get(STORAGE.mirrors, {});
  let preferredMirror = storage.get(STORAGE.preferred, "auto");
  const probeResults = {};

  function activeMirrors() {
    return mirrors.filter((mirror) => mirrorPrefs[mirror.name] !== false);
  }

  /**
   * 生成下载地址：
   * - kind=gitee 的源把 GitHub 发布链接改写到 Gitee 镜像仓库（同一文件名）；
   * - 其余镜像为「前缀 + GitHub 原始链接」。
   */
  function buildUrl(file, mirror) {
    if (mirror && mirror.kind === "gitee") {
      const tag = file.tag || "";
      const name = String(file.name || "").split("/").pop();
      return "https://gitee.com/" + GITEE_REPO + "/releases/download/" + encodeURIComponent(tag) + "/" + encodeURIComponent(name);
    }
    const prefix = mirror ? (mirror.prefix || "") : "";
    const isGitHub = String(file.url || "").startsWith("https://github.com/");
    // 代理前缀仅用于 GitHub 链接；Gitee 直链原样使用
    if (!isGitHub) return file.url;
    return prefix + file.url;
  }

  function orderedMirrors() {
    const list = activeMirrors();
    if (preferredMirror === "auto") return list;
    const preferred = list.find((mirror) => mirror.name === preferredMirror);
    return preferred ? [preferred, ...list.filter((mirror) => mirror !== preferred)] : list;
  }

  // ------------------------------------------------------------ 渲染

  let currentFiles = [];
  let currentView = { search: "", kind: "all", sort: "new", grouped: true };

  function filteredFiles() {
    const search = currentView.search.trim().toLowerCase();
    let files = currentFiles.filter((file) => {
      if (currentView.kind !== "all" && kindOf(file.name) !== currentView.kind) return false;
      if (!search) return true;
      return String(file.name).toLowerCase().includes(search) || String(file.tag || "").toLowerCase().includes(search);
    });
    const sorters = {
      new: (a, b) => String(b.publishedAt).localeCompare(String(a.publishedAt)) || String(a.name).localeCompare(String(b.name)),
      old: (a, b) => String(a.publishedAt).localeCompare(String(b.publishedAt)) || String(a.name).localeCompare(String(b.name)),
      sizeDesc: (a, b) => (b.size || 0) - (a.size || 0),
      sizeAsc: (a, b) => (a.size || 0) - (b.size || 0),
      downloads: (a, b) => (b.downloadCount || 0) - (a.downloadCount || 0),
      name: (a, b) => String(a.name).localeCompare(String(b.name))
    };
    files = files.slice().sort(sorters[currentView.sort] || sorters.new);
    return files;
  }

  function fileRowHtml(file) {
    const kind = kindOf(file.name);
    const sha = file.sha256 || "";
    const mirrorsForFile = orderedMirrors();
    const badges = mirrorsForFile
      .filter((mirror) => mirror.kind === "gitee" || mirror.prefix)
      .slice(0, 3)
      .map((mirror) => '<span class="mirror-badge' + (probeResults[mirror.name] === "bad" ? " warn" : "") + '">' + escapeHtml(mirror.kind === "gitee" ? "Gitee" : mirror.name) + "</span>")
      .join("");
    return (
      '<div class="file-row" data-file-id="' + escapeHtml(file.id || file.name) + '">' +
        '<div class="file-icon" aria-hidden="true">' + (ICONS[kind] || ICONS.other) + "</div>" +
        "<div>" +
          '<div class="file-name">' + escapeHtml(file.name) + "</div>" +
          '<div class="file-meta">' +
            "<span>" + escapeHtml(file.tag || "") + "</span>" +
            "<span>" + formatSize(file.size) + "</span>" +
            "<span>发布 " + formatDate(file.publishedAt) + "</span>" +
            "<span>下载 " + (file.downloadCount || 0) + " 次</span>" +
            (sha ? "<code title=\"" + escapeHtml(sha) + "\">SHA-256 " + escapeHtml(sha.slice(0, 12)) + "…</code>" : "") +
            (badges ? "<span class=\"file-mirrors\">" + badges + "</span>" : "") +
          "</div>" +
        "</div>" +
        '<div class="file-actions">' +
          (sha ? '<button class="copy-btn" type="button" data-copy="' + escapeHtml(sha) + '">复制 SHA</button>' : "") +
          '<button class="copy-btn" type="button" data-copy-link="' + escapeHtml(file.id || file.name) + '">复制链接</button>' +
          '<button class="btn btn-ghost btn-sm" type="button" data-mirror-menu="' + escapeHtml(file.id || file.name) + '">镜像下载 ▾</button>' +
          '<a class="btn btn-primary btn-sm" href="' + escapeHtml(mirrorLinks(file)[0] ? mirrorLinks(file)[0].url : file.url) + '" target="_blank" rel="noopener">下载</a>' +
        "</div>" +
      "</div>"
    );
  }

  function mirrorLinks(file) {
    return orderedMirrors().map((mirror) => ({
      name: mirror.name || "direct",
      label: mirror.kind === "gitee" ? "Gitee（国内主源）"
        : (mirror.kind === "direct" ? "GitHub 直连" : (mirror.name + "（GitHub 代理）")),
      url: buildUrl(file, mirror)
    }));
  }

  async function renderFiles() {
    const container = $("#files");
    if (!container) return;
    const files = filteredFiles();
    if (!files.length) {
      container.innerHTML = '<div class="empty-state">没有匹配的文件</div>';
      return;
    }
    if (!currentView.grouped) {
      container.innerHTML = '<div class="version-group">' + files.map(fileRowHtml).join("") + "</div>";
      bindFileActions(container, files);
      return;
    }
    const groups = new Map();
    for (const file of files) {
      const tag = file.tag || "未分组";
      if (!groups.has(tag)) groups.set(tag, []);
      groups.get(tag).push(file);
    }
    const groupedHtml = Array.from(groups.entries()).map(([tag, groupFiles]) => {
      const newest = groupFiles.reduce((acc, item) => (String(item.publishedAt) > String(acc) ? item.publishedAt : acc), "");
      const totalSize = groupFiles.reduce((acc, item) => acc + (Number(item.size) || 0), 0);
      return (
        '<div class="version-group">' +
          '<div class="version-head">' +
            '<span class="version-tag">' + escapeHtml(tag) + "</span>" +
            '<span class="muted small">' + groupFiles.length + " 个文件 · " + formatSize(totalSize) + "</span>" +
            '<span class="version-meta">' + formatDate(newest) + "</span>" +
          "</div>" +
          groupFiles.map(fileRowHtml).join("") +
        "</div>"
      );
    }).join("");
    container.innerHTML = groupedHtml;
    bindFileActions(container, files);
  }

  function bindFileActions(container, files) {
    $$("[data-copy]", container).forEach((button) => {
      button.addEventListener("click", () => copy(button.getAttribute("data-copy"), button, "已复制 SHA-256"));
    });
    $$("[data-copy-link]", container).forEach((button) => {
      button.addEventListener("click", () => {
        const file = files.find((item) => String(item.id || item.name) === button.getAttribute("data-copy-link"));
        if (file) copy(file.url, button, "已复制下载直链");
      });
    });
    $$("[data-mirror-menu]", container).forEach((button) => {
      button.addEventListener("click", (event) => {
        event.preventDefault();
        const file = files.find((item) => String(item.id || item.name) === button.getAttribute("data-mirror-menu"));
        if (file) openMirrorMenu(button, file);
      });
    });
  }

  let menu = null;
  function closeMenu() {
    if (menu) { menu.remove(); menu = null; }
  }

  function openMirrorMenu(anchor, file) {
    closeMenu();
    const links = mirrorLinks(file);
    menu = document.createElement("div");
    menu.className = "mirror-menu";
    menu.innerHTML = links.map((link, index) =>
      '<a href="' + escapeHtml(link.url) + '" target="_blank" rel="noopener">' +
        '<span>' + escapeHtml(link.label) + "</span>" +
        '<span class="muted small">' + (index === 0 ? "推荐" : "") + "</span>" +
      "</a>"
    ).join("") +
      '<button type="button" data-fallback="' + escapeHtml(file.id || file.name) + '">自动尝试全部镜像 ↓</button>';
    document.body.appendChild(menu);
    const rect = anchor.getBoundingClientRect();
    menu.style.top = (window.scrollY + rect.bottom + 6) + "px";
    menu.style.left = Math.max(12, Math.min(window.scrollX + rect.right - 260, window.scrollX + document.documentElement.clientWidth - 272)) + "px";
    menu.querySelector("[data-fallback]").addEventListener("click", () => {
      closeMenu();
      autoFallback(file);
    });
    setTimeout(() => document.addEventListener("click", closeMenu, { once: true }), 0);
  }

  let fallbacking = false;
  async function autoFallback(file) {
    if (fallbacking) return;
    const links = mirrorLinks(file);
    fallbacking = true;
    for (const link of links) {
      notify("正在尝试镜像：" + link.label + "…");
      const ok = await probeUrl(link.url, 8000);
      if (ok) {
        fallbacking = false;
        notify("镜像可用：" + link.label + "，开始下载", "ok");
        window.open(link.url, "_blank", "noopener");
        return;
      }
      probeResults[link.name] = "bad";
      renderMirrorList();
    }
    fallbacking = false;
    notify("所有镜像都不可用，请稍后重试或使用 GitHub 直链", "bad");
    window.open(file.url, "_blank", "noopener");
  }

  async function probeUrl(url, timeout = 6000) {
    try {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), timeout);
      const response = await fetch(url, {
        method: "GET",
        headers: { Range: "bytes=0-0" },
        signal: controller.signal,
        cache: "no-store"
      });
      clearTimeout(timer);
      return response.ok || response.status === 206;
    } catch (error) {
      return false;
    }
  }

  async function copy(text, button, message) {
    try {
      await navigator.clipboard.writeText(text);
    } catch (e) {
      const area = document.createElement("textarea");
      area.value = text; area.style.position = "fixed"; area.style.opacity = "0";
      document.body.appendChild(area); area.select();
      try { document.execCommand("copy"); } catch (error) { /* ignore */ }
      area.remove();
    }
    if (button) {
      const original = button.textContent;
      button.textContent = "已复制";
      setTimeout(() => { button.textContent = original; }, 1200);
    }
    notify(message || "已复制", "ok");
  }

  // ------------------------------------------------------------ 镜像面板

  function renderMirrorList() {
    const container = $("#mirrorList");
    if (!container) return;
    container.innerHTML =
      '<label class="mirror-item">' +
        '<input type="radio" name="preferredMirror" value="auto"' + (preferredMirror === "auto" ? " checked" : "") + " />" +
        '<span class="mirror-name">自动（按顺序）</span>' +
        '<span class="mirror-url">按列表顺序尝试；下载页按钮默认使用第一个可用镜像</span>' +
      "</label>" +
      mirrors.map((mirror) => {
        const status = probeResults[mirror.name];
        const statusHtml = status === "ok" ? '<span class="mirror-status ok">可用</span>'
          : status === "bad" ? '<span class="mirror-status bad">不可用</span>'
          : status === "testing" ? '<span class="mirror-status testing">检测中</span>'
          : "";
        return (
          '<label class="mirror-item">' +
            '<input type="radio" name="preferredMirror" value="' + escapeHtml(mirror.name) + '"' + (preferredMirror === mirror.name ? " checked" : "") + " />" +
            '<span class="mirror-name">' + escapeHtml(mirror.name) + "</span>" +
            '<span class="mirror-url">' + escapeHtml(mirror.prefix || "（GitHub 直连）") + "</span>" +
            statusHtml +
          "</label>"
        );
      }).join("");

    $$('input[name="preferredMirror"]', container).forEach((input) => {
      input.addEventListener("change", () => {
        preferredMirror = input.value;
        storage.set(STORAGE.preferred, preferredMirror);
        renderFiles();
      });
    });
    const enabledNames = activeMirrors().map((mirror) => mirror.name || "direct");
    setText("statMirror", String(enabledNames.length));
    const note = $("#mirrorNote");
    if (note) {
      const bad = mirrors.filter((mirror) => probeResults[mirror.name] === "bad").map((mirror) => mirror.name);
      note.textContent = bad.length
        ? "本次检测不可用：" + bad.join("、") + "。镜像由第三方提供，可能随时变化；可在 App 内或本页重新检测。"
        : "提示：镜像为第三方公益服务，可能限速或失效；下载失败时请切换镜像或使用 GitHub 直链。";
    }
  }

  async function probeAll() {
    const button = $("#probeBtn");
    if (button) { button.disabled = true; button.textContent = "检测中…"; }
    mirrors.forEach((mirror) => { probeResults[mirror.name] = "testing"; });
    renderMirrorList();
    const sample = currentFiles.find((file) => file.mirrorable !== false) || currentFiles[0];
    if (!sample) {
      if (button) { button.disabled = false; button.textContent = "检测镜像可用性"; }
      return;
    }
    await Promise.all(mirrors.map(async (mirror) => {
      const ok = await probeUrl(buildUrl(sample, mirror), 8000);
      probeResults[mirror.name] = ok ? "ok" : "bad";
      renderMirrorList();
    }));
    if (button) { button.disabled = false; button.textContent = "检测镜像可用性"; }
    const okCount = mirrors.filter((mirror) => probeResults[mirror.name] === "ok").length;
    notify("镜像检测完成：" + okCount + "/" + mirrors.length + " 可用", okCount ? "ok" : "bad");
  }

  // ------------------------------------------------------------ 其它 UI

  function setText(id, value) {
    const element = document.getElementById(id);
    if (element) element.textContent = value;
  }

  function updateStats() {
    setText("statFiles", String(currentFiles.length));
    const versions = new Set(currentFiles.map((file) => file.tag).filter(Boolean));
    setText("statVersions", String(versions.size));
    const total = currentFiles.reduce((acc, file) => acc + (Number(file.size) || 0), 0);
    setText("statSize", formatSize(total));
  }

  function syncThemeIcon(theme) {
    const icon = $(".theme-icon");
    if (icon) icon.textContent = theme === "dark" ? "☀" : "☾";
  }

  function initTheme() {
    const queryTheme = (() => {
      try { return new URLSearchParams(location.search).get("theme"); } catch (e) { return null; }
    })();
    const stored = (() => { try { return localStorage.getItem(STORAGE.theme); } catch (e) { return null; } })();
    const prefersDark = window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches;
    const theme = (queryTheme === "dark" || queryTheme === "light") ? queryTheme : (stored || (prefersDark ? "dark" : "light"));
    document.documentElement.setAttribute("data-theme", theme);
    syncThemeIcon(theme);
    // 再同步一次 icon（部分浏览器在 defer 脚本执行前已完成 DOM 构建）
    requestAnimationFrame(() => syncThemeIcon(theme));
  }

  function bindChrome() {
    $("#themeToggle")?.addEventListener("click", () => {
      const current = document.documentElement.getAttribute("data-theme") === "dark" ? "dark" : "light";
      const next = current === "dark" ? "light" : "dark";
      document.documentElement.setAttribute("data-theme", next);
      const icon = $(".theme-icon");
      if (icon) icon.textContent = next === "dark" ? "☀" : "☾";
      try { localStorage.setItem(STORAGE.theme, next); } catch (e) { /* ignore */ }
    });
    const nav = $("#siteNav");
    const menuButton = $("#menuToggle");
    if (nav && menuButton) {
      menuButton.addEventListener("click", () => {
        const open = nav.classList.toggle("open");
        menuButton.setAttribute("aria-expanded", open ? "true" : "false");
      });
    }
    $("#toTop")?.addEventListener("click", () => window.scrollTo({ top: 0, behavior: "smooth" }));
    $("#probeBtn")?.addEventListener("click", probeAll);

    const search = $("#searchInput");
    search?.addEventListener("input", () => { currentView.search = search.value; renderFiles(); });
    const kind = $("#kindFilter");
    kind?.addEventListener("change", () => { currentView.kind = kind.value; renderFiles(); });
    const sort = $("#sortSelect");
    sort?.addEventListener("change", () => { currentView.sort = sort.value; renderFiles(); });
    const group = $("#groupToggle");
    group?.addEventListener("change", () => { currentView.grouped = group.checked; renderFiles(); });
  }

  function injectToastsStyle() {
    const style = document.createElement("style");
    style.textContent = `
      .toast { position: fixed; left: 50%; bottom: 28px; transform: translate(-50%, 20px); background: var(--card); color: var(--text);
        border: 1px solid var(--card-border); border-radius: 12px; padding: 12px 18px; font-size: .88rem; box-shadow: var(--shadow);
        opacity: 0; pointer-events: none; transition: opacity .2s ease, transform .2s ease; z-index: 80; max-width: min(90vw, 520px); }
      .toast.show { opacity: 1; transform: translate(-50%, 0); }
      .toast[data-kind="ok"] { border-color: rgba(72, 213, 151, .5); }
      .toast[data-kind="bad"] { border-color: rgba(255, 92, 92, .5); }
      .mirror-menu { position: absolute; z-index: 90; width: 260px; background: var(--card); border: 1px solid var(--card-border);
        border-radius: 12px; box-shadow: var(--shadow); padding: 6px; display: grid; gap: 2px; backdrop-filter: blur(12px); }
      .mirror-menu a, .mirror-menu button { display: flex; justify-content: space-between; gap: 10px; align-items: center;
        padding: 9px 11px; border-radius: 8px; color: var(--text); font-size: .84rem; text-decoration: none; background: none;
        border: 0; cursor: pointer; font-family: inherit; text-align: left; }
      .mirror-menu a:hover, .mirror-menu button:hover { background: var(--accent-weak); }
      .file-mirrors { display: inline-flex; gap: 4px; }
    `;
    document.head.appendChild(style);
  }

  async function boot() {
    injectToastsStyle();
    initTheme();
    syncThemeIcon(document.documentElement.getAttribute("data-theme") || "dark");
    bindChrome();

    const mirrorConfig = await loadMirrors();
    mirrors = mirrorConfig.mirrors;
    renderMirrorList();

    try {
      const result = await loadFiles(mirrors);
      currentFiles = result.files;
      updateStats();
      renderFiles();
      const status = $("#dataStatus");
      if (status) {
        let source = "GitHub Releases";
        if (result.source.includes("gitee.com")) source = "Gitee 镜像（国内主源）";
        else if (result.source.includes("files.json")) source = "GitHub 清单 docs/files.json";
        else if (result.source.includes("api.github.com")) source = "GitHub API（兜底）";
        status.textContent = "数据来源：" + source + (result.generatedAt ? " · 清单生成于 " + result.generatedAt : "") + " · 共 " + currentFiles.length + " 个文件";
      }
    } catch (error) {
      const container = $("#files");
      if (container) {
        container.innerHTML = '<div class="empty-state">加载失败：' + escapeHtml(String(error && error.message || error)) +
          '<br /><br /><a href="https://github.com/' + REPO + '/releases" target="_blank" rel="noopener">直接访问 GitHub Releases ↗</a></div>';
      }
      const status = $("#dataStatus");
      if (status) status.textContent = "数据加载失败，可直接访问 GitHub Releases。";
    }
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", boot);
  } else {
    boot();
  }
})();