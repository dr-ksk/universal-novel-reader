/**
 * 小説家になろう 標準パーサースクリプト (syosetu_standard.js)
 *
 * 【アーキテクチャ】
 * アプリがWebViewに注入したパブリックAPI（window.AppBridge）を呼び出してデータを保存します。
 * - 目次取得時: window.AppBridge.saveNovelIndex(...)
 * - 1話閲覧時:  window.AppBridge.saveEpisodeContent(...)
 *
 * ※仕様の穴:
 * 両メソッドは常にグローバルに公開されているため、
 * 目次取得処理の中でループを回して saveEpisodeContent を反復実行しても、
 * アプリ側は公開APIとしてそのまま受け取り、キャッシュとして保存します。
 */
const SiteAdapter = {
  metadata: {
    id: "syosetu_standard",
    name: "小説家になろう 標準パーサー",
    version: "1.0.0",
    domainPattern: "^https?:\\/\\/(ncode|novel18)\\.syosetu\\.com\\/[a-z0-9]+\\/?"
  },

  /**
   * ① 一覧（目次）抽出
   * @param {Document} document
   * @param {string} currentUrl
   */
  parseIndex: async (document, currentUrl) => {
    // 1. 小説タイトル
    const titleEl = document.querySelector('.novel_title, p.novel_title, h1');
    const title = titleEl ? titleEl.textContent.trim() : "";

    // 2. 作者名
    const authorEl = document.querySelector('.novel_writername, .novel_writername a');
    let author = "";
    if (authorEl) {
      author = authorEl.textContent.replace(/^作者：\s*/, '').trim();
    }

    // 3. あらすじ
    const synopsisEl = document.querySelector('#novel_ex');
    const synopsis = synopsisEl ? synopsisEl.textContent.trim() : "";

    // 4. 各エピソードの抽出
    const episodeNodes = document.querySelectorAll('.novel_sublist2');
    const episodes = [];

    if (episodeNodes.length > 0) {
      let currentChapter = null;
      let epIndex = 1;

      const indexContainer = document.querySelector('.index_box') || document.querySelector('#novel_contents');
      if (indexContainer) {
        const children = indexContainer.querySelectorAll('.chapter_title, .novel_sublist2');
        children.forEach(node => {
          if (node.classList.contains('chapter_title')) {
            currentChapter = node.textContent.trim();
          } else if (node.classList.contains('novel_sublist2')) {
            const link = node.querySelector('dd.subtitle a') || node.querySelector('a');
            const dateEl = node.querySelector('dt.long_update');
            if (link) {
              const fullUrl = new URL(link.getAttribute('href'), currentUrl).href;
              episodes.push({
                index: epIndex++,
                chapter: currentChapter,
                title: link.textContent.trim(),
                url: fullUrl,
                updatedAt: dateEl ? dateEl.textContent.trim() : null
              });
            }
          }
        });
      } else {
        episodeNodes.forEach((node, i) => {
          const link = node.querySelector('a');
          const dateEl = node.querySelector('.long_update');
          if (link) {
            episodes.push({
              index: i + 1,
              chapter: null,
              title: link.textContent.trim(),
              url: new URL(link.getAttribute('href'), currentUrl).href,
              updatedAt: dateEl ? dateEl.textContent.trim() : null
            });
          }
        });
      }
    } else {
      // 短編小説（1話完結）の場合
      episodes.push({
        index: 1,
        chapter: null,
        title: title || "短編",
        url: currentUrl,
        updatedAt: null
      });
    }

    // 6. 目次の次ページリンク判定（複数ページ目次対応）
    const nextPageEl = document.querySelector('.novelview_pager-next a, .pager_next a');
    const nextIndexUrl = nextPageEl ? new URL(nextPageEl.getAttribute('href'), currentUrl).href : null;

    const result = {
      topUrl: currentUrl,
      title,
      author,
      synopsis,
      coverUrl: null,
      totalEpisodes: episodes.length,
      episodes,
      nextIndexUrl
    };

    // ★ アプリ側の目次保存APIを呼び出す
    if (typeof window !== 'undefined' && window.AppBridge && window.AppBridge.saveNovelIndex) {
      window.AppBridge.saveNovelIndex(JSON.stringify(result));
    }

    return result;
  },

  /**
   * ② 各話本文抽出（閲覧時にオンデマンド実行）
   * @param {Document} document
   * @param {string} currentUrl
   * @param {object} context - { topUrl, episodeIndex }
   */
  parseEpisode: async (document, currentUrl, context = {}) => {
    // 1. 各話タイトル
    const subTitleEl = document.querySelector('.novel_subtitle, .p-novel__title');
    const episodeTitle = subTitleEl ? subTitleEl.textContent.trim() : "";

    // 2. 本文コンテナの取得
    const honbunEl = document.querySelector('#novel_honbun, .p-novel__body');
    let contentHtml = "";
    const images = [];

    if (honbunEl) {
      const clone = honbunEl.cloneNode(true);
      clone.querySelectorAll('script, style, iframe, .ads, .novel_attention').forEach(el => el.remove());

      clone.querySelectorAll('img').forEach(img => {
        const src = img.getAttribute('src');
        if (src) {
          const absoluteUrl = new URL(src, currentUrl).href;
          img.setAttribute('src', absoluteUrl);
          images.push(absoluteUrl);
        }
      });

      contentHtml = clone.innerHTML.trim();
    }

    // 3. 前後の話URL
    let prevUrl = null;
    let nextUrl = null;

    const prevEl = document.querySelector('.novel_bn a:first-child, a.p-novel__pager-prev');
    if (prevEl && prevEl.textContent.includes('前')) {
      prevUrl = new URL(prevEl.getAttribute('href'), currentUrl).href;
    }

    const nextEl = document.querySelector('.novel_bn a:last-child, a.p-novel__pager-next');
    if (nextEl && nextEl.textContent.includes('次')) {
      nextUrl = new URL(nextEl.getAttribute('href'), currentUrl).href;
    }

    const result = {
      topUrl: context.topUrl || "",
      episodeUrl: currentUrl,
      episodeIndex: context.episodeIndex || 1,
      episodeTitle,
      chapterTitle: null,
      contentHtml,
      prevUrl,
      nextUrl,
      images
    };

    // ★ アプリ側の1話本文保存APIを呼び出す
    if (typeof window !== 'undefined' && window.AppBridge && window.AppBridge.saveEpisodeContent) {
      window.AppBridge.saveEpisodeContent(JSON.stringify(result));
    }

    return result;
  }
};

if (typeof module !== 'undefined' && module.exports) {
  module.exports = SiteAdapter;
}
