#include "BlockStateReader.hpp"

#include <cstring>
#include <string>
#include <utility>
#include <vector>

#include "BedrockAbi.hpp"
#include "memory/SafeMemory.hpp"

namespace cdd::game {

namespace {

constexpr size_t kMaxScanBytes = 1024;
constexpr size_t kMaxMapEntries = 32;
constexpr int kMaxTreeDepth = 16;

struct MapEntry {
  std::string key;
  uintptr_t tag = 0; // address of the Tag inside the variant
};

bool collect(uintptr_t node, int depth, std::vector<MapEntry> &out) {
  if (node == 0) return true;
  if (depth > kMaxTreeDepth || out.size() > kMaxMapEntries) return false;
  if (!mem::looksLikePointer(node)) return false;

  const auto left = mem::readPointer(node + kMapNodeLeft);
  const auto right = mem::readPointer(node + kMapNodeRight);
  if (!left || !right) return false;
  if (!collect(*left, depth + 1, out)) return false;

  auto key = readLibcxxString(node + kMapNodeKey, 128);
  if (!key) return false;
  out.push_back({std::move(*key), node + kMapNodeTag});

  return collect(*right, depth + 1, out);
}

// Reads the std::map that follows the vptr of a CompoundTag.
std::optional<std::vector<MapEntry>> readCompoundMap(uintptr_t tag) {
  const uintptr_t map = tag + kTagPayload;
  const auto root = mem::readPointer(map + kMapRoot);
  const auto size = mem::read<uint64_t>(map + kMapSize);
  if (!root || !size || *size > kMaxMapEntries) return std::nullopt;
  std::vector<MapEntry> entries;
  if (*size == 0) {
    if (*root != 0) return std::nullopt;
    return entries;
  }
  if (!collect(*root, 0, entries) || entries.size() != *size)
    return std::nullopt;
  return entries;
}

const MapEntry *findEntry(const std::vector<MapEntry> &entries,
                          std::string_view key) {
  for (const auto &entry : entries)
    if (entry.key == key) return &entry;
  return nullptr;
}

} // namespace

std::optional<std::string> readLibcxxString(uintptr_t address,
                                            size_t maxLength) {
  unsigned char raw[24];
  if (!mem::tryRead(address, raw, sizeof(raw))) return std::nullopt;
  if ((raw[0] & 1u) == 0) {
    const size_t size = raw[0] >> 1;
    if (size > 22) return std::nullopt;
    return std::string(reinterpret_cast<const char *>(raw + 1), size);
  }
  uint64_t size = 0;
  uintptr_t data = 0;
  std::memcpy(&size, raw + 8, 8);
  std::memcpy(&data, raw + 16, 8);
  if (size > maxLength || mem::untag(data) < 0x10000)
    return std::nullopt;
  std::string value(size, '\0');
  if (!mem::tryRead(data, value.data(), size)) return std::nullopt;
  return value;
}

std::optional<RawBlockStates> parseBlockCompound(uintptr_t tag) {
  const auto entries = readCompoundMap(tag);
  if (!entries) return std::nullopt;

  const MapEntry *name = findEntry(*entries, "name");
  const MapEntry *states = findEntry(*entries, "states");
  if (!name || !states) return std::nullopt;

  RawBlockStates result;
  auto blockName = readLibcxxString(name->tag + kTagPayload, 128);
  if (!blockName || blockName->rfind("minecraft:", 0) != 0)
    return std::nullopt;
  result.name = std::move(*blockName);

  const auto stringTagVptr = mem::readPointer(name->tag);
  const auto stateEntries = readCompoundMap(states->tag);
  if (!stringTagVptr || !stateEntries) return std::nullopt;

  for (const auto &state : *stateEntries) {
    const auto vptr = mem::readPointer(state.tag);
    if (!vptr) return std::nullopt;
    if (*vptr == *stringTagVptr) {
      auto text = readLibcxxString(state.tag + kTagPayload, 64);
      if (!text) return std::nullopt;
      result.strings.emplace(state.key, std::move(*text));
    } else {
      const auto low = mem::read<int8_t>(state.tag + kTagPayload);
      if (!low) return std::nullopt;
      result.ints.emplace(state.key, *low);
    }
  }
  return result;
}

std::optional<RawBlockStates> BlockStateReader::parseCompoundAt(
    uintptr_t tag) const {
  const auto vptr = mem::readPointer(tag);
  if (!vptr || !mImage.contains(*vptr)) return std::nullopt;
  return parseBlockCompound(tag);
}

bool BlockStateReader::calibrateTagOffset(uintptr_t block) {
  for (size_t offset = 8; offset + kMapObjectSize + 8 <= kMaxScanBytes;
       offset += 8) {
    if (parseCompoundAt(block + offset)) {
      mTagOffset.store(offset, std::memory_order_release);
      return true;
    }
  }
  return false;
}

std::optional<RawBlockStates> BlockStateReader::readStates(uintptr_t block) {
  if (!mem::looksLikePointer(block)) return std::nullopt;
  size_t offset = mTagOffset.load(std::memory_order_acquire);
  if (offset == 0) {
    if (!calibrateTagOffset(block)) return std::nullopt;
    offset = mTagOffset.load(std::memory_order_acquire);
  }
  return parseCompoundAt(block + offset);
}

bool BlockStateReader::calibrateBlockTypeOffset(uintptr_t block,
                                                uintptr_t knownBlockType) {
  // First match wins: Blocks of one BlockType are allocated next to each
  // other, so later matches usually belong to the neighbouring permutation.
  size_t match = 0;
  for (size_t offset = 8; offset < kMaxScanBytes; offset += 8) {
    const auto value = mem::readPointer(block + offset);
    if (!value) break;
    if (*value == knownBlockType) {
      match = offset;
      break;
    }
  }
  if (match == 0) return false;
  const size_t previous = mBlockTypeOffset.load(std::memory_order_acquire);
  if (previous != 0 && previous != match) return false;
  mBlockTypeOffset.store(match, std::memory_order_release);
  return true;
}

std::optional<uintptr_t> BlockStateReader::blockTypeOf(uintptr_t block) const {
  const size_t offset = mBlockTypeOffset.load(std::memory_order_acquire);
  if (offset == 0) return std::nullopt;
  const auto value = mem::readPointer(block + offset);
  if (!value || !mem::looksLikePointer(*value)) return std::nullopt;
  return value;
}

} // namespace cdd::game
