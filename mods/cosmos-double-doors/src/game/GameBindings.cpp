#include "GameBindings.hpp"

#include <pl/memory/Vtable.hpp>

#include "BedrockAbi.hpp"

namespace cdd::game {

namespace {

std::optional<OpeningClass> resolveOpeningClass(const mem::ModuleImage &image,
                                                const char *typeName,
                                                pl::log::Logger &log) {
  auto vtable = mem::findPrimaryVtable(image, typeName);
  if (!vtable) {
    log.error("RTTI: no unique primary vtable for {}", typeName);
    return std::nullopt;
  }
  OpeningClass result;
  result.typeName = typeName;
  result.vtable = *vtable;
  // _useDoor/_useTrapDoor is the only virtual these classes add on top of
  // BlockType, so it occupies the last slot.
  result.useSlot = vtable->slotCount - 1;
  result.useFunction = vtable->slot(result.useSlot).value_or(0);
  log.info("RTTI: {} vtable at +{:#x}, {} slots, use() in slot {}", typeName,
           vtable->addressPoint - image.base(), vtable->slotCount,
           result.useSlot);
  return result;
}

} // namespace

std::optional<GameBindings> GameBindings::resolve(pl::log::Logger &log,
                                                  const Options &options) {
  auto image = mem::ModuleImage::find(options.library);
  if (!image) {
    log.error("{} is not loaded in this process", options.library);
    return std::nullopt;
  }

  GameBindings bindings;
  bindings.image = std::move(*image);

  if (auto blockType =
          mem::findPrimaryVtable(bindings.image, kBlockTypeTypeName)) {
    bindings.blockTypeSlots = blockType->slotCount;
    log.info("RTTI: BlockType has {} virtual slots (26.51 headers: {})",
             blockType->slotCount, kExpectedBlockTypeSlots);
  } else {
    log.warn("RTTI: BlockType vtable not found; using door/trapdoor "
             "consistency checks only");
  }

  auto door = resolveOpeningClass(bindings.image, kDoorBlockTypeName, log);
  auto trapdoor =
      resolveOpeningClass(bindings.image, kTrapDoorBlockTypeName, log);
  if (!door || !trapdoor) return std::nullopt;

  // Structural checks: both classes add exactly one virtual to BlockType.
  if (door->vtable.slotCount != trapdoor->vtable.slotCount) {
    log.error("Unsupported game layout: DoorBlock has {} slots, "
              "TrapDoorBlock has {}",
              door->vtable.slotCount, trapdoor->vtable.slotCount);
    return std::nullopt;
  }
  if (bindings.blockTypeSlots != 0 &&
      door->vtable.slotCount != bindings.blockTypeSlots + 1) {
    log.error("Unsupported game layout: DoorBlock adds {} virtuals to "
              "BlockType (expected 1)",
              static_cast<long>(door->vtable.slotCount) -
                  static_cast<long>(bindings.blockTypeSlots));
    return std::nullopt;
  }
  if (door->vtable.slotCount < options.minBlockTypeSlots + 1) {
    log.error("Unsupported game layout: DoorBlock vtable too small ({})",
              door->vtable.slotCount);
    return std::nullopt;
  }
  if (door->useFunction == 0 || trapdoor->useFunction == 0 ||
      door->useFunction == trapdoor->useFunction) {
    log.error("Unsupported game layout: door/trapdoor use() slots are not "
              "distinct functions");
    return std::nullopt;
  }

  // Cross-check against the launcher SDK's own RTTI resolver.
  for (const OpeningClass *cls : {&*door, &*trapdoor}) {
    if (!options.sdkCrossCheck) break;
    const uintptr_t viaSdk = pl::memory::resolveVtableFunction(
        cls->typeName, cls->useSlot, options.library);
    if (viaSdk == 0) {
      log.warn("SDK resolver could not confirm {} slot {}", cls->typeName,
               cls->useSlot);
    } else if (viaSdk != cls->useFunction) {
      log.error("SDK resolver disagrees on {} slot {}", cls->typeName,
                cls->useSlot);
      return std::nullopt;
    }
  }

  bindings.door = std::move(*door);
  bindings.trapdoor = std::move(*trapdoor);
  return bindings;
}

} // namespace cdd::game
