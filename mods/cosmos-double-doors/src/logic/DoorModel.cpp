#include "DoorModel.hpp"

namespace cdd {

namespace {

std::string_view stripNamespace(std::string_view name) {
  if (auto colon = name.find(':'); colon != std::string_view::npos)
    return name.substr(colon + 1);
  return name;
}

} // namespace

MaterialFamily familyFromName(std::string_view name) {
  const auto id = stripNamespace(name);
  if (id.find("copper") != std::string_view::npos) return MaterialFamily::Copper;
  if (id.find("iron") != std::string_view::npos) return MaterialFamily::Iron;
  return MaterialFamily::Wood;
}

std::optional<Facing> doorFacingFromCardinal(std::string_view cardinal) {
  if (cardinal == "east") return Facing::North;
  if (cardinal == "west") return Facing::South;
  if (cardinal == "north") return Facing::West;
  if (cardinal == "south") return Facing::East;
  return std::nullopt;
}

std::optional<Facing> doorFacingFromLegacyDirection(int direction) {
  switch (direction) {
  case 0: return Facing::East;
  case 1: return Facing::South;
  case 2: return Facing::West;
  case 3: return Facing::North;
  default: return std::nullopt;
  }
}

std::optional<Facing> trapdoorFacingFromDirection(int direction) {
  switch (direction) {
  case 0: return Facing::East;
  case 1: return Facing::West;
  case 2: return Facing::South;
  case 3: return Facing::North;
  default: return std::nullopt;
  }
}

std::optional<DoorHalf> parseDoorHalf(const RawBlockStates &states,
                                      BlockCoord pos) {
  std::optional<Facing> facing;
  if (auto cardinal = states.getString("minecraft:cardinal_direction"))
    facing = doorFacingFromCardinal(*cardinal);
  else if (auto direction = states.getInt("direction"))
    facing = doorFacingFromLegacyDirection(*direction);

  const auto open = states.getInt("open_bit");
  const auto upper = states.getInt("upper_block_bit");
  if (!facing || !open || !upper) return std::nullopt;

  DoorHalf half;
  half.upper = *upper != 0;
  half.opening.kind = OpeningKind::Door;
  half.opening.name = states.name;
  half.opening.family = familyFromName(states.name);
  half.opening.facing = *facing;
  half.opening.open = *open != 0;
  half.opening.hingeRight = states.getInt("door_hinge_bit").value_or(0) != 0;
  half.opening.pos = pos;
  return half;
}

std::optional<Opening> parseTrapdoor(const RawBlockStates &states,
                                     BlockCoord pos) {
  std::optional<Facing> facing;
  if (auto direction = states.getInt("direction"))
    facing = trapdoorFacingFromDirection(*direction);
  else if (auto cardinal = states.getString("minecraft:cardinal_direction"))
    facing = doorFacingFromCardinal(*cardinal);

  const auto open = states.getInt("open_bit");
  if (!facing || !open) return std::nullopt;

  Opening t;
  t.kind = OpeningKind::Trapdoor;
  t.name = states.name;
  t.family = familyFromName(states.name);
  t.facing = *facing;
  t.open = *open != 0;
  t.upsideDown = states.getInt("upside_down_bit").value_or(0) != 0;
  t.pos = pos;
  return t;
}

Opening combineDoorHalves(const DoorHalf &lower,
                          const std::optional<DoorHalf> &upper) {
  Opening door = lower.opening;
  if (upper && upper->opening.hingeRight != door.hingeRight)
    door.hingeRight = upper->opening.hingeRight;
  return door;
}

} // namespace cdd
