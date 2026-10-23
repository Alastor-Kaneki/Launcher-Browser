#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <optional>

#include "logic/DoorModel.hpp"
#include "memory/ModuleImage.hpp"

namespace cdd::game {

// Reads a Bedrock `Block` (one block permutation) without relying on
// Mojang's private member offsets:
//  * the block's serialisation CompoundTag ({name, states, version}, the
//    same data written to world saves) is located by scanning the object for
//    a structurally valid libc++ std::map with those keys;
//  * the BlockType* member is located by scanning for the BlockType pointer
//    the game itself passed to the door hook.
// Both offsets are discovered once, then re-validated on every read.
class BlockStateReader {
public:
  explicit BlockStateReader(const mem::ModuleImage &image) : mImage(image) {}

  std::optional<RawBlockStates> readStates(uintptr_t block);

  // Learns where Block stores its BlockType*. `knownBlockType` must be the
  // BlockType of `block`. Returns false if it is not found, or if it differs
  // from an offset learned earlier.
  bool calibrateBlockTypeOffset(uintptr_t block, uintptr_t knownBlockType);
  std::optional<uintptr_t> blockTypeOf(uintptr_t block) const;

  [[nodiscard]] bool hasBlockTypeOffset() const {
    return mBlockTypeOffset.load(std::memory_order_acquire) != 0;
  }

private:
  std::optional<RawBlockStates> parseCompoundAt(uintptr_t tag) const;
  bool calibrateTagOffset(uintptr_t block);

  const mem::ModuleImage &mImage;
  std::atomic<size_t> mTagOffset{0};
  std::atomic<size_t> mBlockTypeOffset{0};
};

// Exposed for tests: parses a block CompoundTag laid out as Bedrock's
// Tag{vptr} + map<std::string, CompoundTagVariant>. String-valued states are
// recognised by sharing the StringTag vtable of the "name" entry; all other
// states are read as their low payload byte (bits and small enums).
std::optional<RawBlockStates> parseBlockCompound(uintptr_t tag);

} // namespace cdd::game
