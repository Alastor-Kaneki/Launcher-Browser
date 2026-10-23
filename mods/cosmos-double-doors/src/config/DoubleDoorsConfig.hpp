#pragma once

#include <optional>
#include <string_view>

#include <pl/Config.hpp>

#include "logic/Limits.hpp"

namespace cdd {

// Persisted as config/config.json inside the installed mod directory.
// Field names are part of the on-disk format: do not rename them.
struct DoubleDoorsConfig {
  int version = 1;
  bool enabled = true;
  bool doubleDoors = true;
  bool doubleTrapdoors = true;
  bool requireMatchingBlockType = true;
  bool allowDifferentVariants = true;
  bool requireMatchingOrientation = true;
  bool redstoneSync = false;
  int maxLinkDistance = 1;
  bool verboseLogging = false;
};

} // namespace cdd

namespace pl::config {

template <> struct Schema<cdd::DoubleDoorsConfig> {
  static constexpr std::string_view title = "Cosmos Double Doors";
  static constexpr std::string_view description =
      "Opens and closes paired doors and trapdoors together. Changes apply "
      "the next time Minecraft is launched.";

  static constexpr FieldSchema field(std::string_view name) {
    if (name == "version")
      return {"Version", "Config schema version managed by the mod.",
              std::nullopt, std::nullopt, true};
    if (name == "enabled")
      return {"Enabled", "Master switch for the whole mod.", std::nullopt,
              std::nullopt, false};
    if (name == "doubleDoors")
      return {"Double Doors",
              "Open and close adjacent paired doors together.", std::nullopt,
              std::nullopt, false};
    if (name == "doubleTrapdoors")
      return {"Double Trapdoors",
              "Open and close facing trapdoor pairs together.", std::nullopt,
              std::nullopt, false};
    if (name == "requireMatchingBlockType")
      return {"Require Matching Block Type",
              "Only link doors of the same kind (see Allow Different "
              "Variants).",
              std::nullopt, std::nullopt, false};
    if (name == "allowDifferentVariants")
      return {"Allow Different Variants",
              "When matching types are required, treat every wood variant as "
              "one kind and every copper variant (any oxidation or waxing) as "
              "another kind.",
              std::nullopt, std::nullopt, false};
    if (name == "requireMatchingOrientation")
      return {"Require Matching Orientation",
              "Doors must face the same way with mirrored hinges; trapdoors "
              "must face each other on the same half.",
              std::nullopt, std::nullopt, false};
    if (name == "redstoneSync")
      return {"Redstone Sync",
              "Mirror redstone-driven door changes onto the partner. Needs a "
              "verified redstone binding for the running game version; when "
              "none is available vanilla redstone behaviour is kept.",
              std::nullopt, std::nullopt, false};
    if (name == "maxLinkDistance")
      return {"Maximum Linking Distance",
              "How many blocks away (along the door plane) a partner may be.",
              static_cast<double>(cdd::kMinLinkDistance),
              static_cast<double>(cdd::kMaxLinkDistance), false};
    if (name == "verboseLogging")
      return {"Verbose Logging", "Log every pairing decision to logcat.",
              std::nullopt, std::nullopt, false};
    return {};
  }
};

} // namespace pl::config
