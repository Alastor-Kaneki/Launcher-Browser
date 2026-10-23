#include "CosmosDoubleDoors.hpp"

#include <filesystem>

#include "game/GameBindings.hpp"

namespace cdd {

CosmosDoubleDoors &CosmosDoubleDoors::instance() {
  static CosmosDoubleDoors mod;
  return mod;
}

CosmosDoubleDoors::CosmosDoubleDoors()
    : mSelf(*ll::mod::NativeMod::current()) {}

bool CosmosDoubleDoors::load() {
  auto &log = mSelf.getLogger();
  std::error_code ec;
  std::filesystem::create_directories(mSelf.getConfigDir(), ec);

  mConfig.emplace();
  if (!mConfig->load()) {
    // Keep running with defaults rather than refusing to load.
    log.warn("Could not read or write {}; using defaults",
             mConfig->configPath().string());
  }
  auto &config = mConfig->value();
  config.maxLinkDistance = clampLinkDistance(config.maxLinkDistance);
  log.info("Loaded {} {}", mSelf.getName(), mSelf.getVersion());
  return true;
}

bool CosmosDoubleDoors::enable() {
  auto &log = mSelf.getLogger();
  const auto &config = mConfig->value();
  if (!config.enabled) {
    log.info("Disabled in config; not installing hooks");
    return true;
  }

  // Unsupported or changed game builds fail here, before anything is
  // patched; returning true keeps the launcher and game running unmodded.
  auto bindings = game::GameBindings::resolve(log);
  if (!bindings) {
    log.error("This Minecraft build is not supported by {} {}; the mod "
              "stays inactive and the game runs unmodified",
              mSelf.getName(), mSelf.getVersion());
    return true;
  }

  mSync = std::make_unique<sync::DoorSync>(std::move(*bindings), config, log);
  if (!mSync->install()) {
    mSync.reset();
    log.error("Hook installation failed; the mod stays inactive");
  }
  return true;
}

bool CosmosDoubleDoors::disable() {
  mSync.reset();
  return true;
}

bool CosmosDoubleDoors::unload() {
  mSync.reset();
  mConfig.reset();
  return true;
}

} // namespace cdd

PL_REGISTER_MOD(cdd::CosmosDoubleDoors, cdd::CosmosDoubleDoors::instance())
