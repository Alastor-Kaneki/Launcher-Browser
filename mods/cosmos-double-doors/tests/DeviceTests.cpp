// ARM64 tests, built with the Android NDK and run under qemu-aarch64. They
// exercise the real mod code (SafeMemory, RTTI discovery, libc++ NBT reader,
// GameBindings, DoorSync hooks) against FakeGame, which is compiled with the
// same toolchain and standard library as Minecraft's Android build.
#include <cstddef>
#include <cstring>
#include <sys/mman.h>

#include "FakeGame.hpp"
#include "FakeSdk.hpp"
#include "TestSupport.hpp"
#include "game/BedrockAbi.hpp"
#include "game/BlockStateReader.hpp"
#include "game/GameBindings.hpp"
#include "memory/Rtti.hpp"
#include "memory/SafeMemory.hpp"
#include "sync/DoorSync.hpp"

using namespace cdd;
using fake_game::counters;
using fake_game::isOpen;

namespace {

pl::log::Logger &testLog() {
  return pl::log::Logger::getOrCreate("CosmosDoubleDoorsTest");
}

game::GameBindings::Options fakeGameOptions() {
  game::GameBindings::Options options;
  options.library = ""; // the test executable itself
  options.minBlockTypeSlots = 5;
  options.sdkCrossCheck = false;
  return options;
}

uintptr_t addr(const void *p) { return reinterpret_cast<uintptr_t>(p); }

// Simulates the game invoking DoorBlock::_useDoor / TrapDoorBlock::
// _useTrapDoor through the vtable, honouring installed (fake) inline hooks.
void use(Player &player, BlockPos pos) {
  const Block &block = player.mRegion->getBlock(pos);
  BlockEvents::BlockPlayerInteractEvent event(pos, player);
  const auto *type = block.mBlockType;
  const auto vtable = *reinterpret_cast<void *const *const *>(type);
  static const auto image = mem::ModuleImage::find("");
  size_t slots = 0;
  while (image->isExecutableAddress(addr(vtable[slots]))) ++slots;
  void *target = vtable[slots - 1];
  void *detour = fake_sdk::detourFor(target);
  using Fn = void (*)(const void *, void *);
  reinterpret_cast<Fn>(detour ? detour : target)(type, &event);
}

struct Scene {
  BlockSource region;
  ServerPlayer player{region};
  LocalPlayer client{region};
  DoorBlock oak{"minecraft:wooden_door", false};
  DoorBlock spruce{"minecraft:spruce_door", false};
  DoorBlock copper{"minecraft:copper_door", false};
  DoorBlock waxedCopper{"minecraft:waxed_weathered_copper_door", false};
  DoorBlock iron{"minecraft:iron_door", true};
  TrapDoorBlock trapdoor{"minecraft:trapdoor"};

  Scene() { counters() = {}; }
};

std::unique_ptr<sync::DoorSync> installSync(const DoubleDoorsConfig &config) {
  auto bindings = game::GameBindings::resolve(testLog(), fakeGameOptions());
  if (!bindings) return nullptr;
  auto sync =
      std::make_unique<sync::DoorSync>(std::move(*bindings), config, testLog());
  if (!sync->install()) return nullptr;
  return sync;
}

} // namespace

// ---- ABI building blocks ----------------------------------------------------

TEST(safe_memory_reads_valid_and_rejects_invalid) {
  int value = 42;
  CHECK(mem::read<int>(addr(&value)) == 42);
  CHECK(!mem::read<int>(0x10));

  void *page = mmap(nullptr, 4096, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
  REQUIRE(page != MAP_FAILED);
  CHECK(!mem::read<int>(addr(page)));
  munmap(page, 4096);
}

TEST(libcxx_string_reader_matches_std_string) {
  std::string shortText = "open_bit";
  std::string longText = "minecraft:waxed_weathered_copper_door";
  CHECK(game::readLibcxxString(addr(&shortText)) == shortText);
  CHECK(game::readLibcxxString(addr(&longText)) == longText);

  std::string empty;
  CHECK(game::readLibcxxString(addr(&empty)) == std::string());
}

TEST(event_layout_matches_header_mirror) {
  BlockSource region;
  ServerPlayer player(region);
  BlockEvents::BlockPlayerInteractEvent event({1, 2, 3}, player);
  CHECK(sizeof(event) == sizeof(game::PlayerInteractEventLayout));
  const auto *raw = reinterpret_cast<const game::PlayerInteractEventLayout *>(
      static_cast<const void *>(&event));
  CHECK(raw->pos.x == 1 && raw->pos.y == 2 && raw->pos.z == 3);
  CHECK(raw->player == static_cast<void *>(&player));
  CHECK(raw->face == 2);
}

TEST(rtti_names) {
  BlockSource region;
  ServerPlayer server(region);
  LocalPlayer local(region);
  CHECK(mem::dynamicTypeName(addr(&server)) == std::string("12ServerPlayer"));
  CHECK(mem::dynamicTypeName(addr(&local)) == std::string("11LocalPlayer"));
  BlockEvents::BlockPlayerInteractEvent event({0, 0, 0}, server);
  CHECK(mem::dynamicTypeName(addr(&event)) ==
        std::string(game::kPlayerInteractEventTypeName));
}

TEST(nbt_reader_parses_block_compound) {
  DoorBlock oak("minecraft:wooden_door", false);
  const Block &block = permutation(
      oak, {{"minecraft:cardinal_direction", std::string("east")},
            {"door_hinge_bit", 1},
            {"open_bit", 0},
            {"upper_block_bit", 1}});
  auto states = game::parseBlockCompound(addr(&block.mSerializationId));
  REQUIRE(states);
  CHECK(states->name == "minecraft:wooden_door");
  CHECK(states->getString("minecraft:cardinal_direction") == "east");
  CHECK(states->getInt("door_hinge_bit") == 1);
  CHECK(states->getInt("upper_block_bit") == 1);
  CHECK(states->getInt("open_bit") == 0);

  TrapDoorBlock trap("minecraft:trapdoor");
  const Block &t = permutation(
      trap, {{"direction", 3}, {"open_bit", 1}, {"upside_down_bit", 0}});
  auto ts = game::parseBlockCompound(addr(&t.mSerializationId));
  REQUIRE(ts);
  CHECK(ts->getInt("direction") == 3);
  CHECK(ts->getInt("open_bit") == 1);
}

TEST(block_reader_calibrates_offsets_with_neighbouring_permutations) {
  auto image = mem::ModuleImage::find("");
  REQUIRE(image);
  DoorBlock oak("minecraft:wooden_door", false);
  // Several permutations of one type, allocated back to back.
  const Block *blocks[4];
  for (int i = 0; i < 4; ++i)
    blocks[i] = &permutation(
        oak, {{"minecraft:cardinal_direction", std::string("south")},
              {"door_hinge_bit", i & 1},
              {"open_bit", i >> 1},
              {"upper_block_bit", 0}});

  game::BlockStateReader reader(*image);
  CHECK(reader.calibrateBlockTypeOffset(addr(blocks[1]), addr(&oak)));
  CHECK(reader.blockTypeOf(addr(blocks[2])) == addr(&oak));
  CHECK(reader.blockTypeOf(addr(blocks[0])) == addr(blocks[0]->mBlockType));
  for (int i = 0; i < 4; ++i) {
    auto states = reader.readStates(addr(blocks[i]));
    REQUIRE(states);
    CHECK(states->getInt("door_hinge_bit") == (i & 1));
    CHECK(states->getInt("open_bit") == (i >> 1));
  }
}

TEST(bindings_find_use_slots_from_rtti) {
  auto bindings = game::GameBindings::resolve(testLog(), fakeGameOptions());
  REQUIRE(bindings);
  CHECK(bindings->blockTypeSlots == 8); // 2 dtors + 6 virtuals
  CHECK(bindings->door.useSlot == 8);
  CHECK(bindings->trapdoor.useSlot == 8);

  DoorBlock oak("minecraft:wooden_door", false);
  const auto vtable = *reinterpret_cast<void *const *const *>(&oak);
  CHECK(bindings->door.vtable.addressPoint == addr(vtable));
  CHECK(bindings->door.useFunction == addr(vtable[8]));
}

// ---- End-to-end door synchronisation ---------------------------------------

TEST(opening_either_door_opens_and_closes_both) {
  Scene s;
  auto sync = installSync({});
  REQUIRE(sync);
  fake_game::placeDoor(s.region, s.oak, {0, 64, 0}, "east", 0);
  fake_game::placeDoor(s.region, s.oak, {1, 64, 0}, "east", 1);

  use(s.player, {0, 64, 0});
  CHECK(isOpen(s.region, {0, 64, 0}) && isOpen(s.region, {0, 65, 0}));
  CHECK(isOpen(s.region, {1, 64, 0}) && isOpen(s.region, {1, 65, 0}));
  CHECK(counters().doorUseCalls == 2); // one per door, no recursion
  CHECK(counters().sounds == 2);       // one vanilla sound per door

  use(s.player, {1, 65, 0}); // close via the partner's upper half
  CHECK(!isOpen(s.region, {0, 64, 0}) && !isOpen(s.region, {1, 64, 0}));
  CHECK(counters().doorUseCalls == 4);
}

TEST(single_door_behaves_vanilla) {
  Scene s;
  auto sync = installSync({});
  REQUIRE(sync);
  fake_game::placeDoor(s.region, s.oak, {0, 64, 0}, "north", 0);
  use(s.player, {0, 64, 0});
  CHECK(isOpen(s.region, {0, 64, 0}));
  CHECK(counters().doorUseCalls == 1);
}

TEST(mismatched_type_or_orientation_does_not_link) {
  Scene s;
  auto sync = installSync({});
  REQUIRE(sync);
  // Wood + copper, mirrored: type mismatch.
  fake_game::placeDoor(s.region, s.oak, {0, 64, 0}, "east", 0);
  fake_game::placeDoor(s.region, s.copper, {1, 64, 0}, "east", 1);
  // Same hinge: orientation mismatch.
  fake_game::placeDoor(s.region, s.oak, {10, 64, 0}, "east", 0);
  fake_game::placeDoor(s.region, s.oak, {11, 64, 0}, "east", 0);

  use(s.player, {0, 64, 0});
  use(s.player, {10, 64, 0});
  CHECK(isOpen(s.region, {0, 64, 0}) && !isOpen(s.region, {1, 64, 0}));
  CHECK(isOpen(s.region, {10, 64, 0}) && !isOpen(s.region, {11, 64, 0}));
}

TEST(copper_variants_and_wood_variants_link) {
  Scene s;
  auto sync = installSync({});
  REQUIRE(sync);
  fake_game::placeDoor(s.region, s.copper, {0, 64, 0}, "south", 0);
  fake_game::placeDoor(s.region, s.waxedCopper, {0, 64, 1}, "south", 1);
  fake_game::placeDoor(s.region, s.oak, {5, 64, 0}, "east", 0);
  fake_game::placeDoor(s.region, s.spruce, {6, 64, 0}, "east", 1);
  use(s.player, {0, 64, 0});
  use(s.player, {6, 64, 0});
  CHECK(isOpen(s.region, {0, 64, 1}));
  CHECK(isOpen(s.region, {5, 64, 0}));
}

TEST(iron_doors_stay_vanilla) {
  Scene s;
  DoubleDoorsConfig config;
  config.requireMatchingBlockType = false;
  auto sync = installSync(config);
  REQUIRE(sync);
  // Iron pair: the player cannot open either, so nothing is mirrored.
  fake_game::placeDoor(s.region, s.iron, {0, 64, 0}, "east", 0);
  fake_game::placeDoor(s.region, s.iron, {1, 64, 0}, "east", 1);
  use(s.player, {0, 64, 0});
  CHECK(!isOpen(s.region, {0, 64, 0}) && !isOpen(s.region, {1, 64, 0}));
  CHECK(counters().doorUseCalls == 1);
  // Wood next to iron (type matching off): the iron partner refuses.
  fake_game::placeDoor(s.region, s.oak, {10, 64, 0}, "east", 0);
  fake_game::placeDoor(s.region, s.iron, {11, 64, 0}, "east", 1);
  use(s.player, {10, 64, 0});
  CHECK(isOpen(s.region, {10, 64, 0}) && !isOpen(s.region, {11, 64, 0}));
}

TEST(broken_partner_leaves_remaining_door_intact) {
  Scene s;
  auto sync = installSync({});
  REQUIRE(sync);
  fake_game::placeDoor(s.region, s.oak, {0, 64, 0}, "east", 0);
  fake_game::placeDoor(s.region, s.oak, {1, 64, 0}, "east", 1);
  s.region.removeBlock({1, 64, 0});
  s.region.removeBlock({1, 65, 0});
  use(s.player, {0, 64, 0});
  CHECK(isOpen(s.region, {0, 64, 0}) && isOpen(s.region, {0, 65, 0}));
  CHECK(counters().doorUseCalls == 1);

  // Half-broken partner (only the upper half left): ignored safely.
  fake_game::placeDoor(s.region, s.oak, {1, 64, 0}, "east", 1);
  s.region.removeBlock({1, 64, 0});
  use(s.player, {0, 64, 0});
  CHECK(!isOpen(s.region, {0, 64, 0}));
  CHECK(!isOpen(s.region, {1, 65, 0}));
}

TEST(partner_already_in_target_state_is_not_toggled) {
  Scene s;
  auto sync = installSync({});
  REQUIRE(sync);
  fake_game::placeDoor(s.region, s.oak, {0, 64, 0}, "east", 0, false);
  fake_game::placeDoor(s.region, s.oak, {1, 64, 0}, "east", 1, true);
  use(s.player, {0, 64, 0}); // opens A; B already open
  CHECK(isOpen(s.region, {0, 64, 0}) && isOpen(s.region, {1, 64, 0}));
  CHECK(counters().doorUseCalls == 1);
}

TEST(trapdoors_sync_and_can_be_disabled_independently) {
  Scene s;
  {
    auto sync = installSync({});
    REQUIRE(sync);
    fake_game::placeTrapdoor(s.region, s.trapdoor, {0, 64, 0}, 3, false);
    fake_game::placeTrapdoor(s.region, s.trapdoor, {0, 64, -1}, 2, false);
    use(s.player, {0, 64, -1});
    CHECK(isOpen(s.region, {0, 64, 0}) && isOpen(s.region, {0, 64, -1}));
    CHECK(counters().trapdoorUseCalls == 2);
  }
  CHECK(fake_sdk::installedHooks() == 0); // hooks removed with the sync

  DoubleDoorsConfig config;
  config.doubleTrapdoors = false;
  auto sync = installSync(config);
  REQUIRE(sync);
  use(s.player, {0, 64, 0});
  CHECK(!isOpen(s.region, {0, 64, 0}) && isOpen(s.region, {0, 64, -1}));
  // Doors still pair while trapdoors are off.
  fake_game::placeDoor(s.region, s.oak, {5, 64, 0}, "east", 0);
  fake_game::placeDoor(s.region, s.oak, {6, 64, 0}, "east", 1);
  use(s.player, {5, 64, 0});
  CHECK(isOpen(s.region, {6, 64, 0}));
}

TEST(disabled_doors_or_master_switch_are_vanilla) {
  Scene s;
  DoubleDoorsConfig config;
  config.doubleDoors = false;
  auto sync = installSync(config);
  REQUIRE(sync);
  fake_game::placeDoor(s.region, s.oak, {0, 64, 0}, "east", 0);
  fake_game::placeDoor(s.region, s.oak, {1, 64, 0}, "east", 1);
  use(s.player, {0, 64, 0});
  CHECK(isOpen(s.region, {0, 64, 0}) && !isOpen(s.region, {1, 64, 0}));
}

TEST(client_side_players_are_left_to_the_server) {
  Scene s;
  auto sync = installSync({});
  REQUIRE(sync);
  fake_game::placeDoor(s.region, s.oak, {0, 64, 0}, "east", 0);
  fake_game::placeDoor(s.region, s.oak, {1, 64, 0}, "east", 1);
  use(s.client, {0, 64, 0});
  CHECK(isOpen(s.region, {0, 64, 0}) && !isOpen(s.region, {1, 64, 0}));
}

TEST(link_distance_option) {
  Scene s;
  DoubleDoorsConfig config;
  config.maxLinkDistance = 2;
  auto sync = installSync(config);
  REQUIRE(sync);
  fake_game::placeDoor(s.region, s.oak, {0, 64, 0}, "east", 0);
  fake_game::placeDoor(s.region, s.oak, {2, 64, 0}, "east", 1);
  use(s.player, {0, 64, 0});
  CHECK(isOpen(s.region, {2, 64, 0}));
}

TEST(rapid_repeated_use_stays_consistent) {
  Scene s;
  auto sync = installSync({});
  REQUIRE(sync);
  fake_game::placeDoor(s.region, s.oak, {0, 64, 0}, "east", 0);
  fake_game::placeDoor(s.region, s.oak, {1, 64, 0}, "east", 1);
  for (int i = 0; i < 50; ++i) use(s.player, {i % 2, 64 + (i % 3 == 0), 0});
  CHECK(isOpen(s.region, {0, 64, 0}) == isOpen(s.region, {1, 64, 0}));
  CHECK(counters().doorUseCalls == 100);
  CHECK(counters().sounds == 100);
}

int main() { return cdd_test::runAll(); }
