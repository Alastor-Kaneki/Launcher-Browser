#pragma once

#include <memory>

#include <pl/Logger.hpp>

#include "config/DoubleDoorsConfig.hpp"
#include "game/GameBindings.hpp"

namespace cdd::sync {

// Owns the installed hooks. While installed, every player interaction with a
// door or trapdoor goes through the vanilla handler first; if that toggled
// the block, the partner (if any) is toggled through the same vanilla
// handler, so sounds, permissions, iron-door rules, game events and network
// updates are all produced by the game itself.
class DoorSync {
public:
  DoorSync(game::GameBindings bindings, const DoubleDoorsConfig &config,
           pl::log::Logger &log);
  ~DoorSync();

  DoorSync(const DoorSync &) = delete;
  DoorSync &operator=(const DoorSync &) = delete;

  bool install();
  void uninstall();

  struct State; // defined in DoorSync.cpp

private:
  std::unique_ptr<State> mState;
};

} // namespace cdd::sync
