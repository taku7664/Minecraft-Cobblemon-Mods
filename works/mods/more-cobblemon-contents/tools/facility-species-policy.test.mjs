import assert from "node:assert/strict";
import test from "node:test";
import { isApprovedFacilitySpecies, UNEVOLVED_ITEM_EXCEPTIONS } from "./facility-species-policy.mjs";

test("unapproved evolving species are rejected without a species denylist", () => {
  assert.equal(isApprovedFacilitySpecies("dipplin", { evolutions: [{ result: "hydrapple" }] }), false);
  assert.equal(isApprovedFacilitySpecies("future_species", { evolutions: [{ result: "future_evolution" }] }), false);
  assert.equal(isApprovedFacilitySpecies("hydrapple", { evolutions: [] }), true);
});

test("the exact form overrides or inherits base evolution data", () => {
  assert.equal(isApprovedFacilitySpecies("qwilfish", { evolutions: [] }), true);
  assert.equal(isApprovedFacilitySpecies("qwilfish", { evolutions: [] }, { evolutions: [{ result: "overqwil" }] }), false);
  assert.equal(isApprovedFacilitySpecies("regional_species", { evolutions: [{ result: "evolved" }] }, {}), false);
  assert.equal(isApprovedFacilitySpecies("regional_species", { evolutions: [{ result: "evolved" }] }, { evolutions: [] }), true);
});

test("only the approved item exceptions and the user retained Ursaring bypass evolution filtering", () => {
  for (const species of ["chansey", "porygon2", "pikachu", "ursaring"]) {
    assert.equal(isApprovedFacilitySpecies(species, { evolutions: [{ result: "evolved" }] }), true);
  }
  for (const species of ["scyther", "vigoroth"]) {
    assert.equal(isApprovedFacilitySpecies(species, { evolutions: [{ result: "evolved" }] }), false);
  }
  assert.deepEqual(UNEVOLVED_ITEM_EXCEPTIONS, {
    chansey: "cobblemon:eviolite",
    porygon2: "cobblemon:eviolite",
    pikachu: "cobblemon:light_ball",
  });
});
