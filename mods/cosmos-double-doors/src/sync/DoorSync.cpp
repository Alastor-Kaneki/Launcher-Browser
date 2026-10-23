#include "DoorSync.hpp"

#include <atomic>
#include <cstring>
#include <exception>
#include <optional>
#include <string>

#include <pl/memory/Hook.hpp>

#include "game/BedrockAbi.hpp"
#include "game/BlockStateReader.hpp"
#include "logic/PairFinder.hpp"
#include "memory/Rtti.hpp"
#include "memory/SafeMemory.hpp"

namespace cdd::sync {

using game::PlayerInteractEventLayout;

namespace {

// Bytes copied from the game's event when synthesising an event for the
// partner. Larger than the 26.51 layout (56) so that a few trailing fields
// added by a newer build are carried over unchanged.
constexpr size_t kEventCopyBytes = 128;
constexpr size_t kEventVtableSlots = 4; // ~D1, ~D0, getBlock, getBlockSource

thread_local int tSyncDepth = 0;

struct DepthGuard {
  DepthGuard() { ++tSyncDepth; }
  ~DepthGuard() { --tSyncDepth; }
};

struct EventCopy {
  alignas(16) unsigned char bytes[kEventCopyBytes] = {};

  PlayerInteractEventLayout &layout() {
    return *reinterpret_cast<PlayerInteractEventLayout *>(bytes);
  }
  void *object() { return bytes; }
};

bool isServerPlayerType(const std::string &name) {
  return name == "12ServerPlayer" || name == "15SimulatedPlayer";
}

} // namespace

struct DoorSync::State {
  State(game::GameBindings b, const DoubleDoorsConfig &c, pl::log::Logger &l)
      : bindings(std::move(b)), reader(bindings.image), config(c), log(l) {}

  game::GameBindings bindings;
  game::BlockStateReader reader;
  DoubleDoorsConfig config;
  pl::log::Logger &log;

  game::UseFn originalDoorUse = nullptr;
  game::UseFn originalTrapdoorUse = nullptr;
  pl::memory::HookHandle doorHook;
  pl::memory::HookHandle trapdoorHook;

  std::atomic<bool> halted{false};
  std::atomic<uintptr_t> verifiedEventVptr{0};
  std::atomic<bool> loggedClientSkip{false};

  void halt(const char *reason) {
    if (!halted.exchange(true))
      log.error("Double doors disabled for this session: {}", reason);
  }

  template <typename... Args>
  void verbose(std::string_view fmt, Args &&...args) {
    if (config.verboseLogging) log.info(fmt, std::forward<Args>(args)...);
  }

  uintptr_t vtableFor(OpeningKind kind) const {
    return kind == OpeningKind::Door ? bindings.door.vtable.addressPoint
                                     : bindings.trapdoor.vtable.addressPoint;
  }

  PairingRules rules() const {
    return {config.requireMatchingBlockType, config.allowDifferentVariants,
            config.requireMatchingOrientation, config.maxLinkDistance};
  }
};

namespace {

std::atomic<DoorSync::State *> gActive{nullptr};

struct Interaction {
  OpeningKind kind;
  EventCopy event; // pristine copy of the game's event
  Opening before;
};

// BlockEventBase::getBlock() on a copy of the event retargeted to `pos`.
// The event resolves the block through the player's own dimension
// BlockSource, so no BlockSource layout knowledge is needed here.
std::optional<uintptr_t> blockAt(const EventCopy &tmpl, BlockCoord pos) {
  EventCopy copy = tmpl;
  copy.layout().pos = {pos.x, pos.y, pos.z};
  const auto vptr = reinterpret_cast<uintptr_t>(copy.layout().vptr);
  const auto fn =
      mem::readPointer(vptr + game::kEventGetBlockSlot * sizeof(uintptr_t));
  if (!fn) return std::nullopt;
  using GetBlockFn = const void *(*)(const void *);
  const auto block = reinterpret_cast<uintptr_t>(
      reinterpret_cast<GetBlockFn>(*fn)(copy.object()));
  if (!mem::looksLikePointer(block)) return std::nullopt;
  return block;
}

// Kind of opening stored at `block`, decided by the BlockType's vtable.
std::optional<OpeningKind> openingKindOf(DoorSync::State &s, uintptr_t block) {
  const auto type = s.reader.blockTypeOf(block);
  if (!type) return std::nullopt;
  const auto vptr = mem::readPointer(*type);
  if (!vptr) return std::nullopt;
  if (*vptr == s.bindings.door.vtable.addressPoint) return OpeningKind::Door;
  if (*vptr == s.bindings.trapdoor.vtable.addressPoint)
    return OpeningKind::Trapdoor;
  return std::nullopt;
}

std::optional<DoorHalf> readDoorHalf(DoorSync::State &s, const EventCopy &tmpl,
                                     BlockCoord pos) {
  const auto block = blockAt(tmpl, pos);
  if (!block || openingKindOf(s, *block) != OpeningKind::Door)
    return std::nullopt;
  const auto states = s.reader.readStates(*block);
  if (!states) return std::nullopt;
  return parseDoorHalf(*states, pos);
}

// Reads the door/trapdoor whose block is at `pos`. Doors are normalised to
// their lower half; `isLowerHalf` reports which half `pos` was.
Probe probeAt(DoorSync::State &s, const EventCopy &tmpl, BlockCoord pos) {
  Probe probe;
  const auto block = blockAt(tmpl, pos);
  if (!block) {
    probe.kind = Probe::Kind::Unreadable;
    return probe;
  }
  const auto kind = openingKindOf(s, *block);
  if (!kind) return probe; // Kind::Other
  const auto states = s.reader.readStates(*block);
  if (!states) {
    probe.kind = Probe::Kind::Unreadable;
    return probe;
  }

  if (*kind == OpeningKind::Trapdoor) {
    auto trapdoor = parseTrapdoor(*states, pos);
    if (!trapdoor) {
      probe.kind = Probe::Kind::Unreadable;
      return probe;
    }
    probe.kind = Probe::Kind::Opening;
    probe.opening = std::move(*trapdoor);
    return probe;
  }

  auto half = parseDoorHalf(*states, pos);
  if (!half) {
    probe.kind = Probe::Kind::Unreadable;
    return probe;
  }

  std::optional<DoorHalf> lower;
  std::optional<DoorHalf> upper;
  if (half->upper) {
    upper = std::move(half);
    lower = readDoorHalf(s, tmpl, pos + BlockCoord{0, -1, 0});
    if (lower && lower->upper) lower.reset();
  } else {
    lower = std::move(half);
    upper = readDoorHalf(s, tmpl, pos + BlockCoord{0, 1, 0});
    if (upper && !upper->upper) upper.reset();
  }
  if (!lower) {
    probe.kind = Probe::Kind::Unreadable; // orphaned upper half
    return probe;
  }
  probe.kind = Probe::Kind::Opening;
  probe.isLowerHalf = (pos == lower->opening.pos);
  probe.opening = combineDoorHalves(*lower, upper);
  return probe;
}

std::optional<Opening> readOpening(DoorSync::State &s, const EventCopy &tmpl,
                                   OpeningKind kind, BlockCoord pos) {
  const Probe probe = probeAt(s, tmpl, pos);
  if (probe.kind != Probe::Kind::Opening || probe.opening.kind != kind)
    return std::nullopt;
  return probe.opening;
}

// Confirms the event really is a BlockPlayerInteractEvent with the expected
// vtable shape before any of its virtuals are called.
bool validateEvent(DoorSync::State &s, uintptr_t event) {
  const auto vptr = mem::readPointer(event);
  if (!vptr) return false;
  if (*vptr == s.verifiedEventVptr.load(std::memory_order_acquire)) return true;

  const auto typeName = mem::dynamicTypeName(event);
  if (!typeName || *typeName != game::kPlayerInteractEventTypeName) {
    s.halt("door hook received an unexpected event type");
    return false;
  }
  size_t slots = 0;
  while (slots <= kEventVtableSlots) {
    const auto entry = mem::readPointer(*vptr + slots * sizeof(uintptr_t));
    if (!entry || !s.bindings.image.isExecutableAddress(*entry)) break;
    ++slots;
  }
  if (slots != kEventVtableSlots) {
    s.halt("BlockPlayerInteractEvent vtable shape changed");
    return false;
  }
  s.verifiedEventVptr.store(*vptr, std::memory_order_release);
  return true;
}

std::optional<Interaction> beginInteraction(DoorSync::State &s,
                                            OpeningKind kind,
                                            const void *self, void *event) {
  const auto eventAddress = reinterpret_cast<uintptr_t>(event);
  if (!validateEvent(s, eventAddress)) return std::nullopt;

  Interaction ia{kind, {}, {}};
  if (!mem::tryRead(eventAddress, ia.event.bytes, kEventCopyBytes) &&
      !mem::tryRead(eventAddress, ia.event.bytes,
                    sizeof(PlayerInteractEventLayout)))
    return std::nullopt;

  // Only act where the world is authoritative (local worlds, LAN/hosted
  // worlds). On remote servers and Realms the client never runs this with a
  // ServerPlayer, so vanilla behaviour is left untouched.
  const auto playerType = mem::dynamicTypeName(
      reinterpret_cast<uintptr_t>(ia.event.layout().player));
  if (!playerType || !isServerPlayerType(*playerType)) {
    if (!s.loggedClientSkip.exchange(true))
      s.log.info("Interaction by {} is not server-authoritative here; "
                 "leaving it to the server",
                 playerType.value_or("<unknown>"));
    return std::nullopt;
  }

  const auto selfAddress = reinterpret_cast<uintptr_t>(self);
  if (mem::readPointer(selfAddress) != s.vtableFor(kind)) return std::nullopt;

  const auto &pos = ia.event.layout().pos;
  const BlockCoord clicked{pos.x, pos.y, pos.z};
  const auto block = blockAt(ia.event, clicked);
  if (!block) return std::nullopt;
  if (!s.reader.hasBlockTypeOffset() &&
      !s.reader.calibrateBlockTypeOffset(*block, selfAddress)) {
    s.halt("could not locate the BlockType pointer inside Block");
    return std::nullopt;
  }
  if (s.reader.blockTypeOf(*block) != selfAddress) return std::nullopt;

  auto before = readOpening(s, ia.event, kind, clicked);
  if (!before) {
    s.verbose("Unreadable {} at {} {} {}; vanilla only",
              kind == OpeningKind::Door ? "door" : "trapdoor", clicked.x,
              clicked.y, clicked.z);
    return std::nullopt;
  }
  ia.before = std::move(*before);
  return ia;
}

void finishInteraction(DoorSync::State &s, Interaction &ia,
                       game::UseFn original) {
  const auto after = readOpening(s, ia.event, ia.kind, ia.before.pos);
  if (!after || after->open == ia.before.open) return; // nothing toggled

  const auto partner = findPartner(
      *after,
      [&](BlockCoord pos) { return probeAt(s, ia.event, pos); }, s.rules());
  if (!partner) {
    s.verbose("{} at {} {} {} has no partner", after->name, after->pos.x,
              after->pos.y, after->pos.z);
    return;
  }
  if (partner->open == after->open) return; // already in sync

  const auto partnerBlock = blockAt(ia.event, partner->pos);
  if (!partnerBlock) return;
  const auto partnerType = s.reader.blockTypeOf(*partnerBlock);
  if (!partnerType || mem::readPointer(*partnerType) != s.vtableFor(ia.kind))
    return;

  EventCopy partnerEvent = ia.event;
  partnerEvent.layout().pos = {partner->pos.x, partner->pos.y, partner->pos.z};
  partnerEvent.layout().successfulValue = 0;
  partnerEvent.layout().successfulEngaged = 0;
  {
    DepthGuard guard;
    original(reinterpret_cast<const void *>(*partnerType),
             partnerEvent.object());
  }

  const auto result = readOpening(s, ia.event, ia.kind, partner->pos);
  if (result && result->open == after->open) {
    s.verbose("Synced {} at {} {} {} -> {}", partner->name, partner->pos.x,
              partner->pos.y, partner->pos.z, after->open ? "open" : "closed");
  } else {
    s.verbose("Partner {} at {} {} {} refused to toggle", partner->name,
              partner->pos.x, partner->pos.y, partner->pos.z);
  }
}

void handleUse(OpeningKind kind, const void *self, void *event) {
  DoorSync::State *s = gActive.load(std::memory_order_acquire);
  // gActive is cleared only after the hooks are removed, so s is non-null
  // whenever a detour runs; the check is defensive.
  if (!s) return;
  const game::UseFn original = kind == OpeningKind::Door
                                   ? s->originalDoorUse
                                   : s->originalTrapdoorUse;
  const bool kindEnabled = kind == OpeningKind::Door ? s->config.doubleDoors
                                                     : s->config.doubleTrapdoors;
  if (tSyncDepth > 0 || !s->config.enabled || !kindEnabled ||
      s->halted.load(std::memory_order_relaxed)) {
    original(self, event);
    return;
  }

  std::optional<Interaction> ia;
  try {
    ia = beginInteraction(*s, kind, self, event);
  } catch (const std::exception &ex) {
    s->halt(ex.what());
  } catch (...) {
    s->halt("unknown error while reading the clicked block");
  }

  original(self, event); // the player's own interaction, always vanilla

  if (!ia) return;
  try {
    finishInteraction(*s, *ia, original);
  } catch (const std::exception &ex) {
    s->halt(ex.what());
  } catch (...) {
    s->halt("unknown error while syncing the partner");
  }
}

void detourDoorUse(const void *self, void *event) {
  handleUse(OpeningKind::Door, self, event);
}

void detourTrapdoorUse(const void *self, void *event) {
  handleUse(OpeningKind::Trapdoor, self, event);
}

} // namespace

DoorSync::DoorSync(game::GameBindings bindings,
                   const DoubleDoorsConfig &config, pl::log::Logger &log)
    : mState(std::make_unique<State>(std::move(bindings), config, log)) {}

DoorSync::~DoorSync() { uninstall(); }

bool DoorSync::install() {
  State &s = *mState;
  DoorSync::State *expected = nullptr;
  if (!gActive.compare_exchange_strong(expected, &s)) {
    s.log.error("Another DoorSync instance is already installed");
    return false;
  }

  s.doorHook = pl::memory::HookHandle(
      reinterpret_cast<void *>(s.bindings.door.useFunction),
      reinterpret_cast<void *>(&detourDoorUse),
      reinterpret_cast<void **>(&s.originalDoorUse));
  s.trapdoorHook = pl::memory::HookHandle(
      reinterpret_cast<void *>(s.bindings.trapdoor.useFunction),
      reinterpret_cast<void *>(&detourTrapdoorUse),
      reinterpret_cast<void **>(&s.originalTrapdoorUse));

  if (!s.doorHook.installed() || !s.trapdoorHook.installed() ||
      !s.originalDoorUse || !s.originalTrapdoorUse) {
    s.log.error("Failed to install door hooks (door={}, trapdoor={})",
                s.doorHook.installed(), s.trapdoorHook.installed());
    uninstall();
    return false;
  }

  if (s.config.redstoneSync) {
    s.log.warn("redstoneSync is enabled, but this build has no verified "
               "redstone hook for the running game; redstone keeps vanilla "
               "behaviour (each door reacts to its own signal)");
  }
  s.log.info("Hooks installed: doors={}, trapdoors={}, matchType={}, "
             "variants={}, orientation={}, distance={}",
             s.config.doubleDoors, s.config.doubleTrapdoors,
             s.config.requireMatchingBlockType,
             s.config.allowDifferentVariants,
             s.config.requireMatchingOrientation, s.config.maxLinkDistance);
  return true;
}

void DoorSync::uninstall() {
  if (!mState) return;
  mState->doorHook.reset();
  mState->trapdoorHook.reset();
  DoorSync::State *expected = mState.get();
  gActive.compare_exchange_strong(expected, nullptr);
}

} // namespace cdd::sync
