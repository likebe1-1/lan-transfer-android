/* LanLink 官网：使用 GitHub 公共 REST API 获取 Releases / 仓库信息，纯静态、无后端。 */
(() => {
  "use strict";

  const REPO = "likebe1-1/lan-transfer-android";
  const API = "https://api.github.com/repos/" + REPO;
  const MANIFEST_URL = "https://raw.githubusercontent.com/" + REPO + "/master/docs/update.json";
  const CACHE_PREFIX = "lanlink.site.v1.";
  const CACHE_TTL = 10 * 60 * 1000;

  const $ = (sel, root = document) => root.querySelector(sel);
  const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));
  const setText = (id, text) => { const el = document.getElementById(id); if (el) el.textContent = text; };

  // ---------------------------------------------------------------- 网络与缓存

  function readCache(key, allowStale) {
    try {
      const raw = localStorage.getItem(CACHE_PREFIX + key);
      if (!raw) return null;
      const parsed = JSON.parse(raw);
      if (!parsed || typeof parsed.at !== "number") return null;
      const age = Date.now() - parsed.at;
      if (!allowStale && age > CACHE_TTL) return null;
      return { data: parsed.data, stale: age > CACHE_TTL, at: parsed.at };
    } catch (e) {
      return null;
    }
  }

  function writeCache(key, data) {
    try {
      localStorage.setItem(CACHE_PREFIX + key, JSON.stringify({ at: Date.now(), data }));
    } catch (e) { /* 忽略：隐私模式/容量不足 */ }
  }

  async function getJSON(key, url) {
    const fresh = readCache(key, false);
    if (fresh) return fresh;
    try {
      const response = await fetch(url, {
        headers: { Accept: "application/vnd.github+json" },
        cache: "no-store"
      });
      if (!response.ok) {
        const stale = readCache(key, true);
        if (stale) return { ...stale, error: "HTTP " + response.status };
        throw new Error("HTTP " + response.status + (response.status === 403 ? "（可能触发 GitHub API 频率限制）" : ""));
      }
      const data = await response.json();
      writeCache(key, data);
      return { data, stale: false, at: Date.now() };
    } catch (error) {
      const stale = readCache(key, true);
      if (stale) return { ...stale, error: String(error && error.message || error) };
      throw error;
    }
  }

  // ---------------------------------------------------------------- 工具函数

  function formatDate(value) {
    if (!value) return "—";
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return "—";
    return date.toLocaleDateString("zh-CN", { year: "numeric", month: "2-digit", day: "2-digit" });
  }

  function formatSize(bytes) {
    const value = Number(bytes);
    if (!Number.isFinite(value) || value <= 0) return "—";
    if (value < 1024) return value + " B";
    if (value < 1024 * 1024) return (value / 1024).toFixed(1) + " KB";
    return (value / 1024 / 1024).toFixed(2) + " MB";
  }

  function abiOf(name) {
    const file = String(name || "").toLowerCase();
    if (file.includes("arm64")) return { key: "arm64-v8a", label: "arm64-v8a" };
    if (file.includes("armeabi")) return { key: "armeabi-v7a", label: "armeabi-v7a" };
    if (file.includes("universal")) return { key: "universal", label: "universal" };
    if (file.includes("x86_64")) return { key: "x86_64", label: "x86_64" };
    if (file.includes("x86")) return { key: "x86", label: "x86" };
    return { key: "other", label: "APK" };
  }

  const ABI_ORDER = ["arm64-v8a", "armeabi-v7a", "universal", "x86_64", "x86", "other"];

  function shortHash(hash) {
    const value = String(hash || "");
    return value.length > 18 ? value.slice(0, 18) + "…" : (value || "—");
  }

  function escapeHtml(text) {
    return String(text == null ? "" : text)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  function inlineMarkdown(text) {
    let value = escapeHtml(text);
    value = value.replace(/`([^`]+)`/g, "<code>$1</code>");
    value = value.replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>");
    value = value.replace(/\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)/g, '<a href="$2" target="_blank" rel="noopener">$1</a>');
    return value;
  }

  /** 轻量 Markdown 渲染（标题/列表/引用/代码块/段落/简单表格），避免引入外部 CDN 依赖。 */
  function markdownToHtml(markdown) {
    const source = String(markdown || "").replace(/\r\n?/g, "\n");
    if (!source.trim()) return "";
    const lines = source.split("\n");
    const out = [];
    let inCode = false;
    let codeLines = [];
    let list = null; // "ul" | "ol"
    let paragraph = [];
    let tableRows = [];

    const flushParagraph = () => {
      if (paragraph.length) {
        out.push("<p>" + inlineMarkdown(paragraph.join(" ")) + "</p>");
        paragraph = [];
      }
    };
    const closeList = () => {
      if (list) { out.push("</" + list + ">"); list = null; }
    };
    const flushTable = () => {
      if (!tableRows.length) return;
      const rows = tableRows.slice();
      tableRows = [];
      const cells = (row) => row.replace(/^\||\|$/g, "").split("|").map((cell) => cell.trim());
      const header = cells(rows[0]);
      const dataRows = rows.slice(2).map(cells);
      let html = '<div class="md-table"><table><thead><tr>' + header.map((cell) => "<th>" + inlineMarkdown(cell) + "</th>").join("") + "</tr></thead><tbody>";
      for (const row of dataRows) {
        html += "<tr>" + row.map((cell) => "<td>" + inlineMarkdown(cell) + "</td>").join("") + "</tr>";
      }
      out.push(html + "</tbody></table></div>");
    };

    for (const line of lines) {
      if (line.trim().startsWith("```")) {
        flushParagraph(); closeList(); flushTable();
        if (!inCode) { inCode = true; codeLines = []; } else { out.push("<pre><code>" + escapeHtml(codeLines.join("\n")) + "</code></pre>"); inCode = false; }
        continue;
      }
      if (inCode) { codeLines.push(line); continue; }
      if (!line.trim()) { flushParagraph(); closeList(); flushTable(); continue; }
      if (line.trim().startsWith("|")) { flushParagraph(); closeList(); tableRows.push(line.trim()); continue; }
      flushTable();
      const heading = line.match(/^(#{1,6})\s+(.*)$/);
      if (heading) {
        flushParagraph(); closeList();
        const level = Math.min(6, heading[1].length + 1);
        out.push("<h" + level + ">" + inlineMarkdown(heading[2]) + "</h" + level + ">");
        continue;
      }
      if (/^---+$/.test(line.trim())) { flushParagraph(); closeList(); out.push("<hr>"); continue; }
      if (line.trim().startsWith(">")) { flushParagraph(); closeList(); out.push("<blockquote>" + inlineMarkdown(line.trim().replace(/^>\s?/, "")) + "</blockquote>"); continue; }
      const ordered = line.match(/^\s*\d+\.\s+(.*)$/);
      const unordered = line.match(/^\s*[-*+]\s+(.*)$/);
      if (ordered || unordered) {
        flushParagraph();
        const type = ordered ? "ol" : "ul";
        if (list !== type) { closeList(); out.push("<" + type + ">"); list = type; }
        out.push("<li>" + inlineMarkdown((ordered || unordered)[1]) + "</li>");
        continue;
      }
      closeList();
      paragraph.push(line.trim());
    }
    if (inCode) out.push("<pre><code>" + escapeHtml(codeLines.join("\n")) + "</code></pre>");
    flushParagraph(); closeList(); flushTable();
    return out.join("");
  }

  // ---------------------------------------------------------------- 数据加载

  async function loadRepo() {
    return getJSON("repo", API);
  }

  async function loadReleases() {
    return getJSON("releases", API + "/releases?per_page=30");
  }

  async function loadManifest() {
    // update.json 走 raw.githubusercontent，不消耗 GitHub API 配额；失败不影响主流程
    try {
      const response = await fetch(MANIFEST_URL, { cache: "no-store" });
      if (!response.ok) return null;
      return await response.json();
    } catch (e) {
      return null;
    }
  }

  // ---------------------------------------------------------------- 渲染

  function manifestByAbi(manifest) {
    const map = {};
    if (manifest && Array.isArray(manifest.apks)) {
      for (const apk of manifest.apks) {
        if (apk && apk.abi) map[apk.abi] = apk;
      }
    }
    return map;
  }

  function assetsOfRelease(release) {
    if (!release || !Array.isArray(release.assets)) return [];
    return release.assets
      .filter((asset) => /\.apk$/i.test(asset.name || ""))
      .map((asset) => {
        const abi = abiOf(asset.name);
        const digest = String(asset.digest || "").replace(/^sha256:/i, "");
        return {
          abi: abi.key,
          label: abi.label,
          name: asset.name,
          url: asset.browser_download_url,
          size: asset.size,
          sha256: digest
        };
      })
      .sort((a, b) => ABI_ORDER.indexOf(a.abi) - ABI_ORDER.indexOf(b.abi));
  }

  function manifestAssets(manifest) {
    if (!manifest || !Array.isArray(manifest.apks)) return [];
    const result = [];
    for (const apk of manifest.apks) {
      if (!apk || !Array.isArray(apk.urls) || !apk.urls.length) continue;
      const abi = abiOf(apk.abi);
      result.push({
        abi: abi.key,
        label: abi.label,
        name: "LanLink-v" + (manifest.versionName || "") + "-" + abi.label + ".apk",
        url: apk.urls[0],
        size: apk.size,
        sha256: apk.sha256 || ""
      });
    }
    return result.sort((a, b) => ABI_ORDER.indexOf(a.abi) - ABI_ORDER.indexOf(b.abi));
  }

  function renderDownloads(release, manifest, options = {}) {
    const assets = assetsOfRelease(release);
    const manifestMap = manifestByAbi(manifest);
    const fallbackAssets = manifestAssets(manifest);
    const list = assets.length ? assets : fallbackAssets;
    const container = $("#assetList");
    const version = release ? (release.tag_name || release.name || "") : (manifest ? "v" + (manifest.versionName || "") : "");
    const normalizedVersion = String(version).replace(/^v/i, "");

    if (!container) return;
    if (!list.length) {
      container.innerHTML = '<p class="muted">暂时无法获取下载列表，请直接访问 <a href="https://github.com/' + REPO + '/releases" target="_blank" rel="noopener">GitHub Releases</a>。</p>';
      return;
    }

    container.innerHTML = list.map((asset, index) => {
      const recommended = asset.abi === "arm64-v8a";
      const sha = asset.sha256 || (manifestMap[asset.abi] && manifestMap[asset.abi].sha256) || "";
      const sizeText = formatSize(asset.size);
      return (
        '<div class="asset">' +
          '<div class="asset-info">' +
            '<div class="asset-name">' + escapeHtml(asset.label) + (recommended ? ' <span class="asset-tag recommended">推荐</span>' : '') + (index === 0 ? ' <span class="asset-tag">下载</span>' : '') + '</div>' +
            '<div class="asset-meta">' + escapeHtml(asset.name) + ' · ' + sizeText + '</div>' +
            (sha ? '<div class="asset-meta">SHA-256: ' + escapeHtml(shortHash(sha)) + '</div>' : '') +
          '</div>' +
          '<div class="asset-actions">' +
            (sha ? '<button class="copy-btn" type="button" data-copy="' + escapeHtml(sha) + '">复制 SHA-256</button>' : '') +
            '<a class="btn btn-primary btn-sm" href="' + escapeHtml(asset.url) + '" target="_blank" rel="noopener">下载</a>' +
          '</div>' +
        '</div>'
      );
    }).join("");

    const recommended = list.find((asset) => asset.abi === "arm64-v8a") || list[0];
    const hero = $("#heroDownload");
    if (hero && recommended) hero.href = recommended.url;

    if (version) {
      setText("versionText", "最新版本 " + (String(version).startsWith("v") ? version : "v" + version));
      setText("statVersion", String(version).startsWith("v") ? version : "v" + version);
      setText("latestTag", "最新版本 " + (String(version).startsWith("v") ? version : "v" + version));
    }
    if (release && release.published_at) {
      setText("latestDate", "发布于 " + formatDate(release.published_at));
      setText("statUpdated", formatDate(release.published_at));
    }
    const releaseLink = $("#releaseLink");
    if (releaseLink && release && release.html_url) releaseLink.href = release.html_url;
    const downloadSub = $("#downloadSub");
    if (downloadSub && release) {
      downloadSub.textContent = "当前最新版本 " + version + " · 文件来自 GitHub Releases 官方附件，可直接下载。";
    }
    void normalizedVersion;
    void options;
  }

  function renderReleases(releases, manifest) {
    const container = $("#releaseList");
    if (!container) return;
    if (!Array.isArray(releases) || !releases.length) {
      container.innerHTML = '<p class="muted">暂时无法读取版本历史，请直接访问 <a href="https://github.com/' + REPO + '/releases" target="_blank" rel="noopener">GitHub Releases</a>。</p>';
      return;
    }
    const list = releases.filter((release) => !release.draft).slice(0, 15);
    container.innerHTML = list.map((release, index) => {
      const assets = assetsOfRelease(release);
      const assetLinks = assets.map((asset) => '<a href="' + escapeHtml(asset.url) + '" target="_blank" rel="noopener">' + escapeHtml(asset.label) + ' · ' + formatSize(asset.size) + '</a>').join("");
      return (
        '<details class="release"' + (index === 0 ? " open" : "") + '>' +
          '<summary>' +
            '<span class="release-tag">' + escapeHtml(release.tag_name || release.name || "") + '</span>' +
            (index === 0 ? '<span class="release-pill">最新</span>' : '') +
            '<span class="release-name">' + escapeHtml(release.name || "") + '</span>' +
            '<span class="release-date">' + formatDate(release.published_at) + '</span>' +
          '</summary>' +
          '<div class="release-body">' + markdownToHtml(release.body || "") +
            (assetLinks ? '<div class="release-assets">' + assetLinks + '</div>' : '') +
          '</div>' +
        '</details>'
      );
    }).join("");
    void manifest;
  }

  function renderRepoInfo(repo) {
    if (!repo) return;
    if (repo.stargazers_count != null) setText("statStars", String(repo.stargazers_count));
  }

  function showStatus(message, isError) {
    const el = $("#apiStatus");
    if (!el) return;
    el.textContent = message;
    el.style.color = isError ? "var(--muted)" : "";
  }

  // ---------------------------------------------------------------- 交互

  function initTheme() {
    const queryTheme = (() => {
      try { return new URLSearchParams(location.search).get("theme"); } catch (e) { return null; }
    })();
    const stored = (() => { try { return localStorage.getItem("lanlink.theme"); } catch (e) { return null; } })();
    const prefersDark = window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches;
    const theme = queryTheme === "dark" || queryTheme === "light"
      ? queryTheme
      : (stored || (prefersDark ? "dark" : "light"));
    document.documentElement.setAttribute("data-theme", theme);
    updateThemeIcon(theme);
  }

  function updateThemeIcon(theme) {
    const icon = $(".theme-icon");
    if (icon) icon.textContent = theme === "dark" ? "☀" : "☾";
  }

  function toggleTheme() {
    const current = document.documentElement.getAttribute("data-theme") === "dark" ? "dark" : "light";
    const next = current === "dark" ? "light" : "dark";
    document.documentElement.setAttribute("data-theme", next);
    updateThemeIcon(next);
    try { localStorage.setItem("lanlink.theme", next); } catch (e) { /* ignore */ }
  }

  async function copyText(text, button) {
    try {
      await navigator.clipboard.writeText(text);
    } catch (e) {
      const textarea = document.createElement("textarea");
      textarea.value = text;
      textarea.style.position = "fixed";
      textarea.style.opacity = "0";
      document.body.appendChild(textarea);
      textarea.select();
      try { document.execCommand("copy"); } catch (err) { /* ignore */ }
      textarea.remove();
    }
    if (button) {
      const original = button.textContent;
      button.textContent = "已复制";
      setTimeout(() => { button.textContent = original; }, 1400);
    }
  }

  function bindUI() {
    $("#themeToggle").addEventListener("click", toggleTheme);

    const nav = $("#siteNav");
    const menu = $("#menuToggle");
    if (menu && nav) {
      menu.addEventListener("click", () => {
        const open = nav.classList.toggle("open");
        menu.setAttribute("aria-expanded", open ? "true" : "false");
      });
      $$("a", nav).forEach((link) => link.addEventListener("click", () => {
        nav.classList.remove("open");
        menu.setAttribute("aria-expanded", "false");
      }));
    }

    document.addEventListener("click", (event) => {
      const copyButton = event.target.closest("[data-copy]");
      if (copyButton) {
        event.preventDefault();
        copyText(copyButton.getAttribute("data-copy"), copyButton);
      }
    });

    const track = $("#shotsTrack");
    const step = 340;
    $("#shotsPrev")?.addEventListener("click", () => track && track.scrollBy({ left: -step, behavior: "smooth" }));
    $("#shotsNext")?.addEventListener("click", () => track && track.scrollBy({ left: step, behavior: "smooth" }));

    const toTop = $("#toTop");
    if (toTop) {
      toTop.addEventListener("click", () => window.scrollTo({ top: 0, behavior: "smooth" }));
      window.addEventListener("scroll", () => {
        toTop.classList.toggle("show", window.scrollY > 600);
      }, { passive: true });
    }

    if ("IntersectionObserver" in window) {
      const observer = new IntersectionObserver((entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) {
            entry.target.classList.add("visible");
            observer.unobserve(entry.target);
          }
        }
      }, { threshold: 0.08 });
      $$(".feature, .shot, .release").forEach((el) => observer.observe(el));
    }
  }

  async function boot() {
    initTheme();
    bindUI();

    const [repoResult, releasesResult, manifest] = await Promise.all([
      loadRepo().catch((error) => ({ error: String(error && error.message || error) })),
      loadReleases().catch((error) => ({ error: String(error && error.message || error) })),
      loadManifest()
    ]);

    const repo = repoResult && repoResult.data ? repoResult.data : null;
    const releases = releasesResult && releasesResult.data ? releasesResult.data : null;
    const release = releases && releases.length ? (releases.find((item) => !item.draft && !item.prerelease) || releases[0]) : null;

    renderRepoInfo(repo);
    renderDownloads(release, manifest);
    renderReleases(releases, manifest);

    const warnings = [];
    if (!repo) warnings.push("仓库信息读取失败");
    if (!releases) warnings.push("Releases 读取失败（可能触发 GitHub API 频率限制，请稍后刷新）");
    if (releasesResult && releasesResult.stale) warnings.push("数据来自本地缓存");
    if (warnings.length) {
      showStatus("⚠️ " + warnings.join("；") + "。下载仍可访问 GitHub Releases 或蓝奏云。", true);
      $("#releaseHint").textContent = "提示：GitHub 公共 API 每小时有调用次数限制；下载链接本身不受影响。";
    } else {
      const at = releasesResult && releasesResult.at ? new Date(releasesResult.at) : new Date();
      showStatus("数据来自 GitHub 公共 API · 更新于 " + at.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" }));
    }

    if (release && release.published_at) {
      setText("statVersion", release.tag_name || "—");
    } else if (manifest) {
      setText("statVersion", "v" + (manifest.versionName || "—"));
      setText("versionText", "最新版本 v" + (manifest.versionName || "—"));
      setText("statUpdated", "—");
      setText("latestTag", "最新版本 v" + (manifest.versionName || "—"));
    }
    if (manifest && manifest.lanzouPassword) setText("lanzouPassword", manifest.lanzouPassword);
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", boot);
  } else {
    boot();
  }
})();