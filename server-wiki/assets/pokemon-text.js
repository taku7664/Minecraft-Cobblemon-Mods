(function () {
  var EGG_GROUPS = {
    monster: "괴수", grass: "식물", bug: "벌레", flying: "비행", field: "육상", fairy: "요정",
    human_like: "인간형", water_1: "수중 1", water_2: "수중 2", water_3: "수중 3",
    mineral: "광물", amorphous: "부정형", ditto: "메타몽", dragon: "드래곤", undiscovered: "알미발견"
  };
  var STRUCTURES = {
    "minecraft:desert_well": "사막 우물",
    "#cobblemon:ruin": "유적",
    "#cobblemon:ruins/arch": "유적 아치",
    "#cobblemon:shipwreck_cove": "난파선이 있는 만",
    "cobblemon:ruins/luna_henge_ruins": "달의 돌고리 유적",
    "cobblemon:ruins/sol_henge_ruins": "태양의 돌고리 유적",
    "cobblemon:ruins/stonjourner_henge_ruins": "돌헨진 유적",
    "cobblemon:shipwreck_coves/lush_shipwreck_cove": "풀이 무성한 난파선 만",
    "cobblemon:shipwreck_coves/submerged_shipwreck_cove": "물에 잠긴 난파선 만"
  };
  window.WikiPokemonText = {
    eggGroups: function (groups) {
      return groups.map(function (group) { return EGG_GROUPS[group] || "분류를 확인 중"; }).join(", ");
    },
    condition: function (text) {
      return text.replace(/#?[a-z0-9_.-]+:[a-z0-9_./-]+/g, function (id) {
        return STRUCTURES[id] || "이름을 확인 중인 구조물";
      });
    }
  };
})();
