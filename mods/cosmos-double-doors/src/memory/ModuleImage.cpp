#include "ModuleImage.hpp"

#include <cstring>
#include <link.h>

#include "SafeMemory.hpp"

namespace cdd::mem {

namespace {

struct SearchContext {
  std::string_view fileName;
  std::optional<ModuleImage> *result;
};

bool endsWith(std::string_view text, std::string_view suffix) {
  return text.size() >= suffix.size() &&
         text.substr(text.size() - suffix.size()) == suffix;
}

constexpr size_t kChunk = 1u << 20;

// Calls fn(chunkAddress, data, size) for every readable chunk of the
// selected segments. Chunks overlap by `overlap` bytes so matches spanning
// chunk borders are not missed.
template <typename Fn>
void forEachChunk(const std::vector<ModuleImage::Segment> &segments,
                  bool includeExecutable, size_t overlap, Fn &&fn) {
  std::vector<unsigned char> buffer(kChunk + overlap);
  for (const auto &segment : segments) {
    if (segment.executable && !includeExecutable) continue;
    for (uintptr_t at = segment.begin; at < segment.end; at += kChunk) {
      const size_t size =
          std::min<size_t>(kChunk + overlap, segment.end - at);
      if (!tryRead(at, buffer.data(), size)) continue;
      fn(at, buffer.data(), size);
    }
  }
}

} // namespace

std::optional<ModuleImage> ModuleImage::find(std::string_view fileName) {
  std::optional<ModuleImage> result;
  SearchContext context{fileName, &result};
  dl_iterate_phdr(
      [](dl_phdr_info *info, size_t, void *data) -> int {
        auto *ctx = static_cast<SearchContext *>(data);
        // The main executable may be reported with a null name.
        const std::string_view name = info->dlpi_name ? info->dlpi_name : "";
        if (!endsWith(name, ctx->fileName)) return 0;
        ModuleImage image;
        image.mPath = std::string(name);
        image.mBase = info->dlpi_addr;
        for (int i = 0; i < info->dlpi_phnum; ++i) {
          const auto &ph = info->dlpi_phdr[i];
          if (ph.p_type != PT_LOAD || ph.p_memsz == 0) continue;
          Segment segment;
          segment.begin = info->dlpi_addr + ph.p_vaddr;
          segment.end = segment.begin + ph.p_memsz;
          segment.executable = (ph.p_flags & PF_X) != 0;
          image.mSegments.push_back(segment);
        }
        *ctx->result = std::move(image);
        return 1;
      },
      &context);
  return result;
}

bool ModuleImage::isExecutableAddress(uintptr_t address) const {
  for (const auto &segment : mSegments) {
    if (segment.executable && address >= segment.begin &&
        address < segment.end)
      return true;
  }
  return false;
}

bool ModuleImage::contains(uintptr_t address) const {
  for (const auto &segment : mSegments) {
    if (address >= segment.begin && address < segment.end) return true;
  }
  return false;
}

std::vector<uintptr_t> ModuleImage::findBytes(std::string_view bytes,
                                               bool executable) const {
  std::vector<uintptr_t> hits;
  if (bytes.empty()) return hits;
  std::vector<Segment> selected;
  for (const auto &segment : mSegments)
    if (segment.executable == executable) selected.push_back(segment);
  forEachChunk(selected, true, bytes.size() - 1,
                   [&](uintptr_t at, const unsigned char *data, size_t size) {
                     const auto *cursor = data;
                     const auto *end = data + size;
                     while (cursor + bytes.size() <= end) {
                       const void *found = memmem(cursor, end - cursor,
                                                  bytes.data(), bytes.size());
                       if (!found) break;
                       const auto offset =
                           static_cast<const unsigned char *>(found) - data;
                       // Only report hits starting inside this chunk proper.
                       if (static_cast<size_t>(offset) < kChunk)
                         hits.push_back(at + offset);
                       cursor = static_cast<const unsigned char *>(found) + 1;
                     }
                   });
  return hits;
}

std::vector<uintptr_t> ModuleImage::findPointerInData(uintptr_t value) const {
  std::vector<uintptr_t> hits;
  forEachChunk(mSegments, false, 0,
                   [&](uintptr_t at, const unsigned char *data, size_t size) {
                     const uintptr_t first = (at + 7) & ~uintptr_t{7};
                     for (uintptr_t p = first; p + 8 <= at + size; p += 8) {
                       uintptr_t candidate;
                       std::memcpy(&candidate, data + (p - at), 8);
                       if (candidate == value) hits.push_back(p);
                     }
                   });
  return hits;
}

} // namespace cdd::mem
