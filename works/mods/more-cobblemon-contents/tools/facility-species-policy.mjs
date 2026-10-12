export const UNEVOLVED_ITEM_EXCEPTIONS = Object.freeze({
  chansey: "cobblemon:eviolite",
  porygon2: "cobblemon:eviolite",
  pikachu: "cobblemon:light_ball",
});

export function isApprovedFacilitySpecies(speciesId, base, form = null) {
  const evolutions = form && Object.hasOwn(form, "evolutions") ? form.evolutions : base.evolutions;
  return (evolutions ?? []).length === 0
    || speciesId === "ursaring" // User explicitly retains Ursaring.
    || Object.hasOwn(UNEVOLVED_ITEM_EXCEPTIONS, speciesId);
}
