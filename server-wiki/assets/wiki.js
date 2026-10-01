/*
 * Builds the wiki's shared frame around a page's <main>: the header with the search box, the rail from nav.js,
 * breadcrumbs, a table of contents from the page's <h2>s and previous/next links.
 *
 * Plain script, no modules or fetch: the wiki is opened from disk (file://), where browsers block both.
 * Each page sets <body data-root> to the path back to the wiki root ("." or "..").
 */
(function () {
  "use strict";

  var nav = window.WIKI_NAV;
  var main = document.querySelector("main");
  if (!nav || !main) return;

  var root = document.body.getAttribute("data-root") || ".";

  // ---- The player's live data, when the Minecraft server serves the wiki ----------------------------------------
  // A `/wiki` link carries ?t=<token>. It is kept in this browser and taken out of the address bar, and every
  // page load asks the server for the latest dashboard with it, so a refresh always shows current values.
  var TOKEN_KEY = "mccWikiToken";
  function storage(action) { try { return action(window.localStorage); } catch (ignored) { return null; } }
  var linkToken = new URLSearchParams(location.search).get("t");
  if (linkToken) {
    storage(function (store) { store.setItem(TOKEN_KEY, linkToken); });
    history.replaceState(null, "", location.pathname + location.hash);
  }
  var token = linkToken || storage(function (store) { return store.getItem(TOKEN_KEY); });
  var served = location.protocol === "http:" || location.protocol === "https:";
  var mePromise = null;

  window.MccWiki = {
    /** Resolves to the player's dashboard, or rejects with "offline", "no_token" or "unknown_token". */
    me: function () {
      if (mePromise) return mePromise;
      mePromise = !served ? Promise.reject(new Error("offline"))
        : !token ? Promise.reject(new Error("no_token"))
        : fetch(root + "/api/me", { headers: { "X-MCC-Wiki-Token": token }, cache: "no-store" }).then(function (response) {
          if (response.status === 401) throw new Error("unknown_token");
          if (!response.ok) throw new Error("server_" + response.status);
          return response.json();
        });
      return mePromise;
    },
    forget: function () { storage(function (store) { store.removeItem(TOKEN_KEY); }); },
  };

  // Fills <span data-me="bp"> and the like on any page; a path like "sections.league.cap" reaches content data.
  function fillMe(me) {
    Array.prototype.forEach.call(document.querySelectorAll("[data-me]"), function (node) {
      var value = node.getAttribute("data-me").split(".").reduce(function (at, key) { return at == null ? at : at[key]; }, me);
      if (value != null) node.textContent = typeof value === "number" ? value.toLocaleString("ko-KR") : String(value);
    });
  }
  var pages = [];
  nav.sections.forEach(function (section) {
    section.pages.forEach(function (page) {
      pages.push({ path: page.path, title: page.title, icon: page.icon || "", keywords: page.keywords || "", section: section.title });
    });
  });

  var here = decodeURIComponent(location.pathname).replace(/\\/g, "/");
  var current = pages.filter(function (page) { return here === "/" + page.path || here.endsWith("/" + page.path); })[0]
    || (/\/$/.test(here) ? pages[0] : null);
  // A page that is not in the rail (such as one Pokemon's entry) can name the rail page it belongs under.
  var parentPath = document.body.getAttribute("data-nav-parent");
  var child = !current && parentPath ? pages.filter(function (page) { return page.path === parentPath; })[0] : null;
  if (child) current = child;

  function href(path) { return root + "/" + path; }

  function el(tag, attrs, children) {
    var node = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (key) {
      if (key === "text") node.textContent = attrs[key];
      else node.setAttribute(key, attrs[key]);
    });
    (children || []).forEach(function (child) { if (child) node.appendChild(child); });
    return node;
  }

  // ---- Header -------------------------------------------------------------------------------------------------

  var input = el("input", { type: "search", placeholder: "위키 검색", "aria-label": "위키 검색", autocomplete: "off" });
  var results = el("ul", { class: "wiki-search-results", role: "listbox" });
  var header = el("header", { class: "wiki-header" }, [
    el("a", { class: "wiki-brand", href: href(pages[0].path) }, [
      el("strong", { text: nav.title }),
      nav.subtitle ? el("span", { text: nav.subtitle }) : null,
    ]),
    el("div", { class: "spacer" }),
    el("div", { class: "wiki-search" }, [input, results]),
  ]);

  // ---- Rail ---------------------------------------------------------------------------------------------------

  var rail = el("nav", { class: "wiki-rail", "aria-label": "목차" });
  nav.sections.forEach(function (section) {
    rail.appendChild(el("h2", { text: section.title }));
    section.pages.forEach(function (page) {
      var link = el("a", { href: href(page.path) }, [
        el("span", { class: "icon", text: page.icon || "•" }),
        el("span", { text: page.title }),
      ]);
      if (current && current.path === page.path) {
        link.className = "current";
        link.setAttribute("aria-current", "page");
      }
      rail.appendChild(link);
    });
  });

  // ---- Content: breadcrumbs, table of contents, previous and next ---------------------------------------------

  var content = el("article", { class: "wiki-content" });
  if (current && current !== pages[0]) {
    content.appendChild(el("div", { class: "wiki-crumbs" }, [
      el("a", { href: href(pages[0].path), text: pages[0].title }),
      document.createTextNode(" › " + current.section + " › "),
      child ? el("a", { href: href(current.path), text: current.title }) : document.createTextNode(current.title),
    ]));
  }
  while (main.firstChild) content.appendChild(main.firstChild);

  var headings = content.querySelectorAll("h2");
  if (headings.length >= 2 && !document.body.hasAttribute("data-no-toc")) {
    var list = el("ol");
    Array.prototype.forEach.call(headings, function (heading, index) {
      if (!heading.id) heading.id = "s" + (index + 1);
      list.appendChild(el("li", {}, [el("a", { href: "#" + heading.id, text: heading.textContent })]));
    });
    var toc = el("nav", { class: "wiki-toc", "aria-label": "이 문서의 목차" }, [el("strong", { text: "목차" }), list]);
    var title = content.querySelector("h1");
    content.insertBefore(toc, title ? title.nextSibling : content.firstChild);
  }

  if (current && !child) {
    var index = pages.indexOf(current);
    var pager = el("nav", { class: "wiki-pager", "aria-label": "이전 다음 문서" });
    if (index > 0) {
      pager.appendChild(el("a", { class: "prev", href: href(pages[index - 1].path) }, [
        el("small", { text: "← 이전" }), el("span", { text: pages[index - 1].title }),
      ]));
    }
    if (index < pages.length - 1) {
      pager.appendChild(el("a", { class: "next", href: href(pages[index + 1].path) }, [
        el("small", { text: "다음 →" }), el("span", { text: pages[index + 1].title }),
      ]));
    }
    content.appendChild(pager);
  }

  var shell = el("div", { class: "wiki-shell" }, [
    header,
    el("div", { class: "wiki-body" }, [rail, content]),
  ]);
  main.replaceWith(shell);
  document.body.appendChild(el("footer", { class: "wiki-footer", text: nav.title }));
  if (current && !child) document.title = current === pages[0] ? nav.title : current.title + " · " + nav.title;

  if (document.querySelector("[data-me]")) window.MccWiki.me().then(fillMe, function () {});

  // ---- Search: titles, sections and keywords from nav.js ------------------------------------------------------

  var active = -1;
  function showResults() {
    var query = input.value.trim().toLowerCase();
    results.innerHTML = "";
    active = -1;
    if (!query) return;
    pages.filter(function (page) {
      return (page.title + " " + page.section + " " + page.keywords).toLowerCase().indexOf(query) >= 0;
    }).slice(0, 8).forEach(function (page) {
      var link = el("a", { href: href(page.path), role: "option" }, [
        document.createTextNode(page.title), el("small", { text: page.section }),
      ]);
      results.appendChild(el("li", {}, [link]));
    });
  }
  function move(step) {
    var links = results.querySelectorAll("a");
    if (!links.length) return;
    if (active >= 0) links[active].classList.remove("active");
    active = (active + step + links.length) % links.length;
    links[active].classList.add("active");
  }
  input.addEventListener("input", showResults);
  input.addEventListener("keydown", function (event) {
    if (event.key === "ArrowDown") { move(1); event.preventDefault(); }
    else if (event.key === "ArrowUp") { move(-1); event.preventDefault(); }
    else if (event.key === "Enter") {
      var links = results.querySelectorAll("a");
      var target = links[active >= 0 ? active : 0];
      if (target) location.href = target.href;
    } else if (event.key === "Escape") { input.value = ""; showResults(); }
  });
  document.addEventListener("click", function (event) {
    if (!header.contains(event.target)) results.innerHTML = "";
  });
  document.addEventListener("keydown", function (event) {
    if (event.key === "/" && document.activeElement !== input) { input.focus(); event.preventDefault(); }
  });
})();
