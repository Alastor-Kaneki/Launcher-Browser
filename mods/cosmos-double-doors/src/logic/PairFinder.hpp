#pragma once

#include <functional>
#include <optional>

#include "DoorModel.hpp"

namespace cdd {

struct PairingRules {
  bool requireMatchingBlockType = true;
  bool allowDifferentVariants = true;
  bool requireMatchingOrientation = true;
  int maxLinkDistance = 1;
};

// What the world contains at a position, as far as pairing is concerned.
struct Probe {
  enum class Kind { Other, Opening, Unreadable } kind = Kind::Other;
  Opening opening; // valid when kind == Opening
  // For doors: true when the block at the probed position is the lower half
  // (pairing only links doors whose lower halves share a Y level).
  bool isLowerHalf = true;
};

using ProbeFn = std::function<Probe(BlockCoord)>;

bool typesCompatible(const Opening &a, const Opening &b,
                     const PairingRules &rules);

// Finds the partner of `self`. Positions are probed lazily so callers only
// read the blocks that matter. Returns nullopt when there is no valid,
// unambiguous partner.
std::optional<Opening> findPartner(const Opening &self, const ProbeFn &probe,
                                   const PairingRules &rules);

} // namespace cdd
