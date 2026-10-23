#pragma once

#include <cstddef>
#include <cstdint>
#include <optional>
#include <string>
#include <string_view>

#include <pl/Logger.hpp>

#include "memory/ModuleImage.hpp"
#include "memory/Rtti.hpp"

namespace cdd::game {

// DoorBlock::_useDoor / TrapDoorBlock::_useTrapDoor
// (BlockEvents::BlockPlayerInteractEvent&) const
using UseFn = void (*)(const void *self, void *event);

struct OpeningClass {
  std::string typeName;
  mem::VtableInfo vtable;
  size_t useSlot = 0;
  uintptr_t useFunction = 0;
};

// Everything the mod needs from the game binary, discovered from RTTI and
// cross-checked before any hook is installed. No hardcoded addresses,
// offsets or byte signatures are involved.
struct GameBindings {
  mem::ModuleImage image;
  size_t blockTypeSlots = 0; // 0 when BlockType's vtable was not found
  OpeningClass door;
  OpeningClass trapdoor;

  struct Options {
    std::string_view library = "libminecraftpe.so";
    // BlockType has ~130 virtuals in every recent version; far fewer means
    // the wrong class was found.
    size_t minBlockTypeSlots = 100;
    // Confirm slots with the launcher SDK's pl::memory::resolveVtableFunction.
    bool sdkCrossCheck = true;
  };

  // Returns nullopt (after logging why) when the running game does not match
  // the structure this build was written against.
  static std::optional<GameBindings> resolve(pl::log::Logger &log,
                                             const Options &options);
  static std::optional<GameBindings> resolve(pl::log::Logger &log) {
    return resolve(log, Options{});
  }
};

} // namespace cdd::game
