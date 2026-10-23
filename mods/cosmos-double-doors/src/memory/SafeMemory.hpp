#pragma once

// Fault-tolerant reads of game memory. Every pointer obtained from game
// objects goes through these helpers, so a wrong layout assumption turns into
// a failed read (and the mod standing down) instead of a SIGSEGV.

#include <cstddef>
#include <cstdint>
#include <optional>
#include <string>

namespace cdd::mem {

// Android tags heap pointers in the top byte (Top Byte Ignore, e.g. 0xb4 for
// Scudo allocations, plus MTE tags). Pointer values are compared as-is, but
// validated and passed to the kernel without the tag.
inline uintptr_t untag(uintptr_t value) {
  return value & ((uintptr_t{1} << 56) - 1);
}

// Copies `size` bytes from `address` in this process into `out`. Returns
// false if any byte is unmapped/unreadable.
bool tryRead(uintptr_t address, void *out, size_t size);

template <typename T> std::optional<T> read(uintptr_t address) {
  T value{};
  if (!tryRead(address, &value, sizeof(T))) return std::nullopt;
  return value;
}

inline std::optional<uintptr_t> readPointer(uintptr_t address) {
  if (address == 0 || (address & 7u) != 0) return std::nullopt;
  return read<uintptr_t>(address);
}

// Reads a NUL terminated string of at most maxLength characters.
std::optional<std::string> readCString(uintptr_t address, size_t maxLength);

// Plausible user-space data pointer (non-null, 8-byte aligned, canonical
// once the tag byte is removed).
inline bool looksLikePointer(uintptr_t value) {
  const uintptr_t address = untag(value);
  return address >= 0x10000 && (address & 7u) == 0 && (address >> 48) == 0;
}

} // namespace cdd::mem
