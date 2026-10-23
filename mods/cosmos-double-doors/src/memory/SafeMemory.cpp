#include "SafeMemory.hpp"

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <mutex>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <unistd.h>

namespace cdd::mem {

namespace {

std::atomic<bool> gVmReadvUsable{true};

bool readWithVmReadv(uintptr_t address, void *out, size_t size) {
  iovec local{out, size};
  iovec remote{reinterpret_cast<void *>(address), size};
  const ssize_t n = process_vm_readv(getpid(), &local, 1, &remote, 1, 0);
  if (n == static_cast<ssize_t>(size)) return true;
  if (n < 0 && (errno == ENOSYS || errno == EPERM))
    gVmReadvUsable.store(false, std::memory_order_relaxed);
  return false;
}

// Fallback for kernels/sandboxes without process_vm_readv: write() into a
// pipe validates the source buffer in the kernel and fails with EFAULT
// instead of raising a signal.
bool readWithPipe(uintptr_t address, void *out, size_t size) {
  static std::mutex pipeMutex;
  static int fds[2] = {-1, -1};
  std::lock_guard lock(pipeMutex);
  if (fds[0] < 0 && pipe2(fds, O_CLOEXEC | O_NONBLOCK) != 0) return false;

  auto *dst = static_cast<char *>(out);
  size_t done = 0;
  while (done < size) {
    const size_t chunk = std::min<size_t>(size - done, 4096);
    const ssize_t w =
        write(fds[1], reinterpret_cast<const void *>(address + done), chunk);
    if (w <= 0) {
      char drain[4096];
      while (::read(fds[0], drain, sizeof(drain)) > 0) {
      }
      return false;
    }
    const ssize_t r = ::read(fds[0], dst + done, static_cast<size_t>(w));
    if (r != w) return false;
    done += static_cast<size_t>(w);
  }
  return true;
}

} // namespace

bool tryRead(uintptr_t address, void *out, size_t size) {
  if (size == 0) return true;
  address = untag(address);
  if (address == 0 || address + size < address) return false;
  if (gVmReadvUsable.load(std::memory_order_relaxed)) {
    if (readWithVmReadv(address, out, size)) return true;
    if (gVmReadvUsable.load(std::memory_order_relaxed)) return false;
  }
  return readWithPipe(address, out, size);
}

std::optional<std::string> readCString(uintptr_t address, size_t maxLength) {
  // Read page-bounded chunks so a string ending just before an unmapped page
  // is still readable.
  constexpr uintptr_t kPage = 4096;
  std::string result;
  uintptr_t cursor = address;
  while (result.size() < maxLength) {
    const size_t untilPageEnd = kPage - (cursor % kPage);
    const size_t chunk = std::min(untilPageEnd, maxLength - result.size());
    char buffer[kPage];
    if (!tryRead(cursor, buffer, chunk)) return std::nullopt;
    if (const void *nul = std::memchr(buffer, '\0', chunk)) {
      result.append(buffer, static_cast<const char *>(nul) - buffer);
      return result;
    }
    result.append(buffer, chunk);
    cursor += chunk;
  }
  return std::nullopt;
}

} // namespace cdd::mem
