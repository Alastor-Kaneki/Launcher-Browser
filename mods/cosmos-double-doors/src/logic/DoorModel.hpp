#pragma once

// Game-independent description of doors and trapdoors. Everything in
// src/logic is plain C++ so it can be unit tested on the host.

#include <cstdint>
#include <map>
#include <optional>
#include <string>
#include <string_view>

namespace cdd {

struct BlockCoord {
  int x = 0;
  int y = 0;
  int z = 0;

  friend bool operator==(const BlockCoord &, const BlockCoord &) = default;
  BlockCoord operator+(const BlockCoord &o) const {
    return {x + o.x, y + o.y, z + o.z};
  }
  BlockCoord scaled(int k) const { return {x * k, y * k, z * k}; }
};

// Horizontal facing using Java Edition semantics, which have a well known
// geometric meaning (hinge side, free edge). Bedrock state values are
// converted into this in fromBedrockStates().
enum class Facing : uint8_t { North, East, South, West };

inline BlockCoord facingVector(Facing f) {
  switch (f) {
  case Facing::North: return {0, 0, -1};
  case Facing::East: return {1, 0, 0};
  case Facing::South: return {0, 0, 1};
  case Facing::West: return {-1, 0, 0};
  }
  return {};
}
inline Facing rotateClockwise(Facing f) {
  return static_cast<Facing>((static_cast<int>(f) + 1) % 4);
}
inline Facing rotateCounterClockwise(Facing f) {
  return static_cast<Facing>((static_cast<int>(f) + 3) % 4);
}
inline Facing opposite(Facing f) {
  return static_cast<Facing>((static_cast<int>(f) + 2) % 4);
}

enum class OpeningKind : uint8_t { Door, Trapdoor };
enum class MaterialFamily : uint8_t { Wood, Copper, Iron };

// One door (both halves folded together) or one trapdoor.
struct Opening {
  OpeningKind kind = OpeningKind::Door;
  std::string name; // e.g. "minecraft:spruce_door"
  MaterialFamily family = MaterialFamily::Wood;
  Facing facing = Facing::North;
  bool open = false;
  bool hingeRight = false; // doors only
  bool upsideDown = false; // trapdoors only
  BlockCoord pos;          // lower half for doors
};

// Raw block state as serialised by Bedrock ("states" compound of the block's
// NBT). Numeric/bool tags are stored in `ints`, string tags in `strings`.
struct RawBlockStates {
  std::string name;
  std::map<std::string, int, std::less<>> ints;
  std::map<std::string, std::string, std::less<>> strings;

  std::optional<int> getInt(std::string_view key) const {
    auto it = ints.find(key);
    if (it == ints.end()) return std::nullopt;
    return it->second;
  }
  std::optional<std::string_view> getString(std::string_view key) const {
    auto it = strings.find(key);
    if (it == strings.end()) return std::nullopt;
    return it->second;
  }
};

MaterialFamily familyFromName(std::string_view name);

// Bedrock "minecraft:cardinal_direction" for doors -> Java facing.
// Verified against GeyserMC's Java<->Bedrock block mappings:
// Java north=east, south=west, west=north, east=south.
std::optional<Facing> doorFacingFromCardinal(std::string_view cardinal);
// Legacy integer "direction" state of doors (pre cardinal_direction).
std::optional<Facing> doorFacingFromLegacyDirection(int direction);
// Bedrock trapdoor integer "direction" -> Java facing.
// Verified against GeyserMC mappings: 0=east 1=west 2=south 3=north.
std::optional<Facing> trapdoorFacingFromDirection(int direction);

struct DoorHalf {
  Opening opening; // `pos` is the position of this half
  bool upper = false;
};

// Interprets door states of a single half. Returns nullopt if required states
// are missing (unknown/unsupported format).
std::optional<DoorHalf> parseDoorHalf(const RawBlockStates &states,
                                      BlockCoord pos);
std::optional<Opening> parseTrapdoor(const RawBlockStates &states,
                                     BlockCoord pos);

// Folds both halves into one door. `upper` may be absent (half-broken door);
// the lower half is authoritative for facing/open, hinge is taken from the
// upper half when present (where legacy Bedrock stored it).
Opening combineDoorHalves(const DoorHalf &lower,
                          const std::optional<DoorHalf> &upper);

} // namespace cdd
