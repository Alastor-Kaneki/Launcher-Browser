#pragma once

#include <cstdint>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace cdd::mem {

// Loaded segments of one shared library, read from its program headers
// (dl_iterate_phdr), so no section headers or symbols are required.
class ModuleImage {
public:
  struct Segment {
    uintptr_t begin = 0;
    uintptr_t end = 0;
    bool executable = false;
  };

  // Finds a loaded library whose path ends with `fileName`.
  static std::optional<ModuleImage> find(std::string_view fileName);

  [[nodiscard]] const std::string &path() const { return mPath; }
  [[nodiscard]] uintptr_t base() const { return mBase; }
  [[nodiscard]] const std::vector<Segment> &segments() const {
    return mSegments;
  }
  [[nodiscard]] bool isExecutableAddress(uintptr_t address) const;
  [[nodiscard]] bool contains(uintptr_t address) const;

  // Every address where `bytes` occurs, in non-executable segments or (with
  // `executable`) in executable ones. .rodata lives in the executable
  // segment when a library is linked with --no-rosegment.
  [[nodiscard]] std::vector<uintptr_t> findBytes(std::string_view bytes,
                                                 bool executable) const;
  // Every 8-byte aligned slot in non-executable segments holding `value`.
  [[nodiscard]] std::vector<uintptr_t> findPointerInData(uintptr_t value) const;

private:
  std::string mPath;
  uintptr_t mBase = 0;
  std::vector<Segment> mSegments;
};

} // namespace cdd::mem
