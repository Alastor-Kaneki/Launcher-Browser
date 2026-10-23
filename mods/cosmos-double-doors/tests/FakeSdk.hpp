#pragma once

#include <cstddef>

namespace fake_sdk {
// Detour registered for `target`, or nullptr.
void *detourFor(void *target);
size_t installedHooks();
} // namespace fake_sdk
