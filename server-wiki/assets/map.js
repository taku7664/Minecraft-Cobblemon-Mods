(function () {
  "use strict";
  // MccWiki preserves the game server origin when the client serves the wiki locally.
  var url = new URL(window.MccWiki.api(""), location.href);
  if (url.protocol === "file:") url = new URL("http://localhost:8101/");
  url.port = "8101";
  url.pathname = "/";
  url.search = "";
  url.hash = "world";
  document.getElementById("server-map").src = url.href;
  document.getElementById("map-open").href = url.href;
})();
