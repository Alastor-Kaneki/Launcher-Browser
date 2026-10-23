#pragma once

#include <memory>
#include <optional>

#include <pl/Config.hpp>
#include <pl/Mod.hpp>

#include "config/DoubleDoorsConfig.hpp"
#include "sync/DoorSync.hpp"

namespace cdd {

// Pocket Cosmos / LeviLaunchroid lifecycle entry point.
class CosmosDoubleDoors {
public:
  static CosmosDoubleDoors &instance();

  CosmosDoubleDoors();

  [[nodiscard]] ll::mod::NativeMod &getSelf() const { return mSelf; }

  bool load();
  bool enable();
  bool disable();
  bool unload();

private:
  ll::mod::NativeMod &mSelf;
  std::optional<pl::config::ConfigFile<DoubleDoorsConfig>> mConfig;
  std::unique_ptr<sync::DoorSync> mSync;
};

} // namespace cdd
