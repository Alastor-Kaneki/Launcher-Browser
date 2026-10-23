// Host unit tests for state parsing and pair detection (src/logic).
#include <map>
#include <tuple>

#include "TestSupport.hpp"
#include "logic/PairFinder.hpp"

using namespace cdd;

namespace {

RawBlockStates doorStates(const std::string &name, const std::string &cardinal,
                          int hinge, int open, int upper) {
  RawBlockStates s;
  s.name = name;
  s.strings["minecraft:cardinal_direction"] = cardinal;
  s.ints["door_hinge_bit"] = hinge;
  s.ints["open_bit"] = open;
  s.ints["upper_block_bit"] = upper;
  return s;
}

RawBlockStates trapdoorStates(const std::string &name, int direction, int open,
                              int upsideDown) {
  RawBlockStates s;
  s.name = name;
  s.ints["direction"] = direction;
  s.ints["open_bit"] = open;
  s.ints["upside_down_bit"] = upsideDown;
  return s;
}

Opening door(const std::string &name, const std::string &cardinal, int hinge,
             BlockCoord pos, int open = 0) {
  auto half = parseDoorHalf(doorStates(name, cardinal, hinge, open, 0), pos);
  return half->opening;
}

Opening trapdoor(const std::string &name, int direction, BlockCoord pos,
                 int upsideDown = 0) {
  return *parseTrapdoor(trapdoorStates(name, direction, 0, upsideDown), pos);
}

using Key = std::tuple<int, int, int>;

struct World {
  std::map<Key, Probe> blocks;
  void put(const Opening &o, bool lower = true) {
    Probe p;
    p.kind = Probe::Kind::Opening;
    p.opening = o;
    p.isLowerHalf = lower;
    blocks[{o.pos.x, o.pos.y, o.pos.z}] = p;
  }
  void unreadable(BlockCoord c) {
    Probe p;
    p.kind = Probe::Kind::Unreadable;
    blocks[{c.x, c.y, c.z}] = p;
  }
  ProbeFn probe() const {
    return [this](BlockCoord c) {
      auto it = blocks.find({c.x, c.y, c.z});
      return it == blocks.end() ? Probe{} : it->second;
    };
  }
};

PairingRules strict() { return {}; }

} // namespace

// --- Bedrock state conversion (values cross-checked with GeyserMC) ---------

TEST(cardinal_direction_maps_to_java_facing) {
  CHECK(doorFacingFromCardinal("east") == Facing::North);
  CHECK(doorFacingFromCardinal("west") == Facing::South);
  CHECK(doorFacingFromCardinal("north") == Facing::West);
  CHECK(doorFacingFromCardinal("south") == Facing::East);
  CHECK(!doorFacingFromCardinal("up"));
}

TEST(trapdoor_direction_maps_to_java_facing) {
  CHECK(trapdoorFacingFromDirection(0) == Facing::East);
  CHECK(trapdoorFacingFromDirection(1) == Facing::West);
  CHECK(trapdoorFacingFromDirection(2) == Facing::South);
  CHECK(trapdoorFacingFromDirection(3) == Facing::North);
  CHECK(!trapdoorFacingFromDirection(4));
}

TEST(parse_door_half_requires_core_states) {
  auto ok = parseDoorHalf(doorStates("minecraft:oak_door", "east", 1, 1, 1),
                          {1, 2, 3});
  REQUIRE(ok);
  CHECK(ok->upper);
  CHECK(ok->opening.open);
  CHECK(ok->opening.hingeRight);
  CHECK(ok->opening.facing == Facing::North);

  RawBlockStates missing;
  missing.name = "minecraft:oak_door";
  missing.ints["open_bit"] = 0;
  CHECK(!parseDoorHalf(missing, {}));
}

TEST(legacy_integer_door_direction_still_parses) {
  RawBlockStates s;
  s.name = "minecraft:wooden_door";
  s.ints["direction"] = 3;
  s.ints["open_bit"] = 0;
  s.ints["upper_block_bit"] = 0;
  auto half = parseDoorHalf(s, {});
  REQUIRE(half);
  CHECK(half->opening.facing == Facing::North);
}

TEST(material_families) {
  CHECK(familyFromName("minecraft:wooden_door") == MaterialFamily::Wood);
  CHECK(familyFromName("minecraft:pale_oak_door") == MaterialFamily::Wood);
  CHECK(familyFromName("minecraft:waxed_oxidized_copper_door") ==
        MaterialFamily::Copper);
  CHECK(familyFromName("minecraft:exposed_copper_trapdoor") ==
        MaterialFamily::Copper);
  CHECK(familyFromName("minecraft:iron_door") == MaterialFamily::Iron);
}

TEST(combine_prefers_upper_hinge) {
  auto lower = *parseDoorHalf(doorStates("minecraft:oak_door", "east", 0, 1, 0),
                              {0, 0, 0});
  auto upper = parseDoorHalf(doorStates("minecraft:oak_door", "east", 1, 0, 1),
                             {0, 1, 0});
  auto combined = combineDoorHalves(lower, upper);
  CHECK(combined.hingeRight);
  CHECK(combined.open); // open comes from the lower half
  CHECK(combined.pos == (BlockCoord{0, 0, 0}));
}

// --- Door pairing -----------------------------------------------------------

// Java oak door facing north (Bedrock "east"), hinge left: partner is east.
TEST(mirrored_double_door_links_both_ways) {
  World w;
  auto a = door("minecraft:wooden_door", "east", 0, {0, 64, 0});
  auto b = door("minecraft:wooden_door", "east", 1, {1, 64, 0});
  w.put(a);
  w.put(b);
  auto fromA = findPartner(a, w.probe(), strict());
  auto fromB = findPartner(b, w.probe(), strict());
  REQUIRE(fromA);
  REQUIRE(fromB);
  CHECK(fromA->pos == b.pos);
  CHECK(fromB->pos == a.pos);
}

TEST(every_facing_links_on_the_free_edge) {
  // Bedrock cardinal -> expected partner offset for a left-hinged door.
  const std::tuple<const char *, BlockCoord> cases[] = {
      {"east", {1, 0, 0}},   // java north -> east
      {"south", {0, 0, 1}},  // java east -> south
      {"west", {-1, 0, 0}},  // java south -> west
      {"north", {0, 0, -1}}, // java west -> north
  };
  for (const auto &[cardinal, offset] : cases) {
    World w;
    auto a = door("minecraft:spruce_door", cardinal, 0, {5, 70, 5});
    auto b = door("minecraft:spruce_door", cardinal, 1, a.pos + offset);
    w.put(a);
    w.put(b);
    auto partner = findPartner(a, w.probe(), strict());
    CHECK(partner && partner->pos == b.pos);
  }
}

TEST(single_door_has_no_partner) {
  World w;
  auto a = door("minecraft:wooden_door", "east", 0, {0, 64, 0});
  w.put(a);
  CHECK(!findPartner(a, w.probe(), strict()));
}

TEST(same_hinge_side_does_not_link_when_orientation_required) {
  World w;
  auto a = door("minecraft:wooden_door", "east", 0, {0, 64, 0});
  auto b = door("minecraft:wooden_door", "east", 0, {1, 64, 0});
  w.put(a);
  w.put(b);
  CHECK(!findPartner(a, w.probe(), strict()));

  auto relaxed = strict();
  relaxed.requireMatchingOrientation = false;
  CHECK(findPartner(a, w.probe(), relaxed));
}

TEST(different_facing_does_not_link) {
  World w;
  auto a = door("minecraft:wooden_door", "east", 0, {0, 64, 0});
  auto b = door("minecraft:wooden_door", "north", 1, {1, 64, 0});
  w.put(a);
  w.put(b);
  CHECK(!findPartner(a, w.probe(), strict()));
  auto relaxed = strict();
  relaxed.requireMatchingOrientation = false;
  CHECK(!findPartner(a, w.probe(), relaxed)); // perpendicular never links
}

TEST(opposite_facing_links_only_when_orientation_relaxed) {
  World w;
  auto a = door("minecraft:wooden_door", "east", 0, {0, 64, 0});
  auto b = door("minecraft:wooden_door", "west", 0, {1, 64, 0});
  w.put(a);
  w.put(b);
  CHECK(!findPartner(a, w.probe(), strict()));
  auto relaxed = strict();
  relaxed.requireMatchingOrientation = false;
  CHECK(findPartner(a, w.probe(), relaxed));
}

TEST(door_behind_is_not_a_partner) {
  World w;
  auto a = door("minecraft:wooden_door", "east", 0, {0, 64, 0});
  // Directly behind/in front (along the facing axis), mirrored hinge.
  w.put(a);
  w.put(door("minecraft:wooden_door", "east", 1, {0, 64, -1}));
  w.put(door("minecraft:wooden_door", "east", 1, {0, 64, 1}));
  CHECK(!findPartner(a, w.probe(), strict()));
}

TEST(block_type_matching_rules) {
  World w;
  auto oak = door("minecraft:wooden_door", "east", 0, {0, 64, 0});
  auto spruce = door("minecraft:spruce_door", "east", 1, {1, 64, 0});
  w.put(oak);
  w.put(spruce);

  CHECK(findPartner(oak, w.probe(), strict())); // variants allowed
  auto exact = strict();
  exact.allowDifferentVariants = false;
  CHECK(!findPartner(oak, w.probe(), exact));
}

TEST(wood_and_copper_do_not_mix_unless_type_matching_disabled) {
  World w;
  auto oak = door("minecraft:wooden_door", "east", 0, {0, 64, 0});
  auto copper = door("minecraft:weathered_copper_door", "east", 1, {1, 64, 0});
  w.put(oak);
  w.put(copper);
  CHECK(!findPartner(oak, w.probe(), strict()));
  auto any = strict();
  any.requireMatchingBlockType = false;
  CHECK(findPartner(oak, w.probe(), any));
}

TEST(copper_oxidation_and_wax_variants_link) {
  World w;
  auto a = door("minecraft:copper_door", "south", 0, {0, 64, 0});
  auto b = door("minecraft:waxed_oxidized_copper_door", "south", 1,
                {0, 64, 1});
  w.put(a);
  w.put(b);
  CHECK(findPartner(a, w.probe(), strict()));
}

TEST(link_distance) {
  World w;
  auto a = door("minecraft:wooden_door", "east", 0, {0, 64, 0});
  auto b = door("minecraft:wooden_door", "east", 1, {2, 64, 0});
  w.put(a);
  w.put(b);
  CHECK(!findPartner(a, w.probe(), strict()));
  auto far = strict();
  far.maxLinkDistance = 2;
  CHECK(findPartner(a, w.probe(), far));
  far.maxLinkDistance = 99; // clamped, still fine
  CHECK(findPartner(a, w.probe(), far));
}

TEST(incompatible_door_blocks_search) {
  World w;
  auto a = door("minecraft:wooden_door", "east", 0, {0, 64, 0});
  w.put(a);
  w.put(door("minecraft:iron_door", "east", 1, {1, 64, 0}));
  w.put(door("minecraft:wooden_door", "east", 1, {2, 64, 0}));
  auto far = strict();
  far.maxLinkDistance = 2;
  CHECK(!findPartner(a, w.probe(), far));
}

TEST(upper_half_at_same_level_is_not_a_partner) {
  World w;
  auto a = door("minecraft:wooden_door", "east", 0, {0, 64, 0});
  auto b = door("minecraft:wooden_door", "east", 1, {1, 64, 0});
  w.put(a);
  w.put(b, /*lower=*/false);
  CHECK(!findPartner(a, w.probe(), strict()));
}

TEST(unreadable_neighbour_is_never_linked) {
  World w;
  auto a = door("minecraft:wooden_door", "east", 0, {0, 64, 0});
  w.put(a);
  w.unreadable({1, 64, 0});
  CHECK(!findPartner(a, w.probe(), strict()));
}

TEST(door_never_links_to_trapdoor) {
  World w;
  auto a = door("minecraft:wooden_door", "east", 0, {0, 64, 0});
  w.put(a);
  w.put(trapdoor("minecraft:trapdoor", 0, {1, 64, 0}));
  CHECK(!findPartner(a, w.probe(), strict()));
}

// --- Trapdoor pairing -------------------------------------------------------

TEST(facing_trapdoors_link) {
  World w;
  // direction 3 = java north: free edge points north (-z).
  auto a = trapdoor("minecraft:trapdoor", 3, {0, 64, 0});
  auto b = trapdoor("minecraft:trapdoor", 2, {0, 64, -1}); // faces south
  w.put(a);
  w.put(b);
  auto fromA = findPartner(a, w.probe(), strict());
  auto fromB = findPartner(b, w.probe(), strict());
  CHECK(fromA && fromA->pos == b.pos);
  CHECK(fromB && fromB->pos == a.pos);
}

TEST(trapdoors_on_different_halves_do_not_link) {
  World w;
  auto a = trapdoor("minecraft:trapdoor", 3, {0, 64, 0}, 0);
  auto b = trapdoor("minecraft:trapdoor", 2, {0, 64, -1}, 1);
  w.put(a);
  w.put(b);
  CHECK(!findPartner(a, w.probe(), strict()));
}

TEST(back_to_back_trapdoors_do_not_link_when_strict) {
  World w;
  auto a = trapdoor("minecraft:trapdoor", 3, {0, 64, 0});
  auto b = trapdoor("minecraft:trapdoor", 2, {0, 64, 1}); // hinge to hinge
  w.put(a);
  w.put(b);
  CHECK(!findPartner(a, w.probe(), strict()));
}

TEST(side_by_side_trapdoors_link_only_when_relaxed) {
  World w;
  auto a = trapdoor("minecraft:trapdoor", 3, {0, 64, 0});
  auto b = trapdoor("minecraft:trapdoor", 3, {1, 64, 0});
  w.put(a);
  w.put(b);
  CHECK(!findPartner(a, w.probe(), strict()));
  auto relaxed = strict();
  relaxed.requireMatchingOrientation = false;
  CHECK(findPartner(a, w.probe(), relaxed));
}

int main() { return cdd_test::runAll(); }
