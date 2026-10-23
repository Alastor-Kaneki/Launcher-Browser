#include "Rtti.hpp"

#include <string>
#include <vector>

#include "SafeMemory.hpp"

namespace cdd::mem {

namespace {
constexpr size_t kMaxSlots = 4096;
constexpr size_t kMaxTypeNameLength = 256;
} // namespace

std::optional<std::string> dynamicTypeName(uintptr_t object) {
  const auto vptr = readPointer(object);
  if (!vptr || !looksLikePointer(*vptr)) return std::nullopt;
  // vptr[-1] is the std::type_info*, whose second word is the name.
  const auto typeInfo = readPointer(*vptr - sizeof(uintptr_t));
  if (!typeInfo || !looksLikePointer(*typeInfo)) return std::nullopt;
  const auto name = readPointer(*typeInfo + sizeof(uintptr_t));
  if (!name) return std::nullopt;
  // Some ABIs flag non-unique RTTI names in the top bit.
  const uintptr_t namePtr = *name & ~(uintptr_t{1} << 63);
  return readCString(namePtr, kMaxTypeNameLength);
}

std::optional<uintptr_t> VtableInfo::slot(size_t index) const {
  if (index >= slotCount) return std::nullopt;
  return readPointer(addressPoint + index * sizeof(uintptr_t));
}

namespace {

// 0 hits -> nullopt; 1 -> that vtable; conflicting hits -> ambiguous (sets
// `ambiguous` and returns nullopt).
std::optional<VtableInfo> vtableForNames(const ModuleImage &image,
                                         const std::vector<uintptr_t> &names,
                                         bool &ambiguous) {
  std::optional<VtableInfo> found;
  for (uintptr_t nameAddress : names) {
    // The name must start a string, not be the tail of a longer one.
    const auto before = read<char>(nameAddress - 1);
    if (!before || *before != '\0') continue;

    for (uintptr_t nameRef : image.findPointerInData(nameAddress)) {
      const uintptr_t typeInfo = nameRef - sizeof(uintptr_t);
      for (uintptr_t tiRef : image.findPointerInData(typeInfo)) {
        // Primary vtable layout: [offset-to-top == 0][type_info*][slots...]
        const auto offsetToTop = read<intptr_t>(tiRef - sizeof(uintptr_t));
        if (!offsetToTop || *offsetToTop != 0) continue;

        VtableInfo info;
        info.addressPoint = tiRef + sizeof(uintptr_t);
        while (info.slotCount < kMaxSlots) {
          const auto entry =
              readPointer(info.addressPoint + info.slotCount * sizeof(uintptr_t));
          if (!entry || !image.isExecutableAddress(*entry)) break;
          ++info.slotCount;
        }
        if (info.slotCount == 0) continue;
        if (found && found->addressPoint != info.addressPoint) {
          ambiguous = true;
          return std::nullopt;
        }
        found = info;
      }
    }
  }
  return found;
}

} // namespace

std::optional<VtableInfo> findPrimaryVtable(const ModuleImage &image,
                                            std::string_view mangledName) {
  std::string needle(mangledName);
  needle.push_back('\0');

  // Type names normally sit in .rodata; only scan code segments (much
  // larger) when the library keeps .rodata there.
  for (bool executable : {false, true}) {
    bool ambiguous = false;
    auto found =
        vtableForNames(image, image.findBytes(needle, executable), ambiguous);
    if (ambiguous) return std::nullopt;
    if (found) return found;
  }
  return std::nullopt;
}

} // namespace cdd::mem
