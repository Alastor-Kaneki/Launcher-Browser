// Test doubles for the launcher-provided pieces (libpreloader.so hooks and
// liblog) so the device tests can run as a static executable under qemu.
#include "FakeSdk.hpp"

#include <cstdarg>
#include <cstdio>
#include <map>

#include <android/log.h>
#include <pl/memory/Hook.hpp>
#include <pl/memory/Vtable.hpp>

namespace {
std::map<void *, void *> &hooks() {
  static std::map<void *, void *> table;
  return table;
}
} // namespace

namespace fake_sdk {
void *detourFor(void *target) {
  auto it = hooks().find(target);
  return it == hooks().end() ? nullptr : it->second;
}
size_t installedHooks() { return hooks().size(); }
} // namespace fake_sdk

namespace pl::memory {

int hook(FuncPtr target, FuncPtr detour, FuncPtr *originalFunc,
         HookPriority) {
  if (!target || !detour || !originalFunc || hooks().count(target)) return -1;
  hooks()[target] = detour;
  *originalFunc = target; // an inline hook's trampoline behaves like this
  return 0;
}

bool unhook(FuncPtr target, FuncPtr) { return hooks().erase(target) == 1; }

uintptr_t resolveVtableFunction(std::string_view, size_t, std::string_view) {
  return 0;
}

} // namespace pl::memory

extern "C" int __android_log_print(int prio, const char *tag, const char *fmt,
                                   ...) {
  std::printf("    [log %d %s] ", prio, tag);
  va_list args;
  va_start(args, fmt);
  std::vprintf(fmt, args);
  va_end(args);
  std::printf("\n");
  return 0;
}
