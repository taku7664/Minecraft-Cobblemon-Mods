(function () {
  "use strict";
  var records = (window.WIKI_CRAFTING || {}).recipes || [];
  var buttons = Array.from(document.querySelectorAll("[data-category]"));
  var search = document.getElementById("craft-search");
  var results = document.getElementById("craft-results");
  var count = document.getElementById("craft-count");
  var category = "balls";
  function element(tag, text, className) {
    var node = document.createElement(tag);
    if (text) node.textContent = text;
    if (className) node.className = className;
    return node;
  }
  function render() {
    var query = search.value.trim().toLocaleLowerCase();
    var groups = new Map();
    records.forEach(function (recipe) {
      var haystack = [recipe.name, recipe.item, recipe.method].concat(recipe.ingredients.map(function (material) { return material.name; })).join(" ").toLocaleLowerCase();
      if (recipe.category !== category || (query && !haystack.includes(query))) return;
      if (!groups.has(recipe.item)) groups.set(recipe.item, []);
      groups.get(recipe.item).push(recipe);
    });
    results.replaceChildren();
    count.textContent = "품목 " + groups.size + "종 · 제작법 " + Array.from(groups.values()).reduce(function (total, recipes) { return total + recipes.length; }, 0) + "개";
    if (!groups.size) results.append(element("p", "이 분류에는 검색 결과가 없습니다. 다른 탭이나 검색어를 확인하세요.", "note"));
    Array.from(groups.values()).sort(function (a, b) { return a[0].name.localeCompare(b[0].name, "ko"); }).forEach(function (recipes) {
      var details = element("details", "", "craft-item");
      var summary = element("summary", recipes[0].name);
      summary.append(element("small", recipes.length > 1 ? "제작법 " + recipes.length + "개" : recipes[0].method));
      details.append(summary);
      if (query) details.open = true;
      var variants = element("div", "", "craft-variants");
      recipes.forEach(function (recipe) {
        var card = element("section", "", "craft-recipe");
        card.append(element("h3", recipe.method + (recipe.count ? " → " + recipe.count + "개" : "")));
        if (recipe.grid.length) {
          var grid = element("div", "", "craft-grid");
          grid.setAttribute("role", "img");
          grid.setAttribute("aria-label", "재료 배치: " + recipe.grid.map(function (row) { return row.map(function (cell) { return cell || "빈칸"; }).join(", "); }).join(" / "));
          recipe.grid.forEach(function (row) { row.forEach(function (cell) { grid.append(element("div", cell, "craft-slot")); }); });
          card.append(grid);
        }
        var materials = element("ul");
        recipe.ingredients.forEach(function (material) { materials.append(element("li", material.name + " × " + material.count)); });
        if (recipe.ingredients.length) card.append(materials);
        (recipe.steps || []).forEach(function (step) { card.append(element("p", step)); });
        if (recipe.note) card.append(element("p", recipe.note));
        variants.append(card);
      });
      details.append(variants);
      results.append(details);
    });
  }
  function select(button) {
    category = button.dataset.category;
    buttons.forEach(function (entry) { entry.setAttribute("aria-selected", String(entry === button)); entry.tabIndex = entry === button ? 0 : -1; });
    results.setAttribute("aria-labelledby", button.id);
    render();
  }
  buttons.forEach(function (button, index) {
    button.addEventListener("click", function () { select(button); });
    button.addEventListener("keydown", function (event) {
      var next;
      if (event.key === "ArrowRight") next = (index + 1) % buttons.length;
      if (event.key === "ArrowLeft") next = (index + buttons.length - 1) % buttons.length;
      if (event.key === "Home") next = 0;
      if (event.key === "End") next = buttons.length - 1;
      if (next !== undefined) { event.preventDefault(); buttons[next].focus(); select(buttons[next]); }
    });
  });
  search.addEventListener("input", render);
  render();
})();
