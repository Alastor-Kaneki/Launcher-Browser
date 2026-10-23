#pragma once

// Itanium C++ ABI RTTI helpers. Minecraft's Android build keeps RTTI, so
// classes can be located by their mangled type name ("9DoorBlock") without
// symbols or hardcoded addresses.

#include <cstddef>
#include <cstdint>
#include <optional>
#include <string>
#include <string_view>

#include "ModuleImage.hpp"

namespace cdd::mem {

// Mangled dynamic type name of a polymorphic object ("12ServerPlayer").
std::optional<std::string> dynamicTypeName(uintptr_t object);

struct VtableInfo {
  uintptr_t addressPoint = 0; // what an object's vptr points to
  size_t slotCount = 0;       // consecutive entries pointing into code

  [[nodiscard]] std::optional<uintptr_t> slot(size_t index) const;
};

// Locates the primary vtable of `mangledName` inside `image` and counts its
// virtual function slots. Returns nullopt unless exactly one primary vtable
// is found.
std::optional<VtableInfo> findPrimaryVtable(const ModuleImage &image,
                                            std::string_view mangledName);

} // namespace cdd::mem
