#include "PairFinder.hpp"

#include <algorithm>
#include <vector>

#include "Limits.hpp"

namespace cdd {

namespace {

using Accept = std::function<bool(const Opening &)>;

// Walks up to maxLinkDistance blocks from `origin` in `direction`. Blocks
// that are not doors/trapdoors are treated as gaps; the first door/trapdoor
// encountered either is the partner or blocks the search.
std::optional<Opening> walk(const Opening &self, BlockCoord direction,
                            const ProbeFn &probe, const PairingRules &rules,
                            const Accept &accept) {
  const int distance = clampLinkDistance(rules.maxLinkDistance);
  for (int k = 1; k <= distance; ++k) {
    const BlockCoord pos = self.pos + direction.scaled(k);
    const Probe found = probe(pos);
    if (found.kind == Probe::Kind::Unreadable) return std::nullopt;
    if (found.kind == Probe::Kind::Other) continue;

    const Opening &other = found.opening;
    if (other.kind != self.kind) return std::nullopt;
    if (other.kind == OpeningKind::Door && !found.isLowerHalf)
      return std::nullopt;
    if (!typesCompatible(self, other, rules) || !accept(other))
      return std::nullopt;
    return other;
  }
  return std::nullopt;
}

std::optional<Opening> findDoorPartner(const Opening &self,
                                       const ProbeFn &probe,
                                       const PairingRules &rules) {
  // Java semantics: a left-hinged door's partner is on its clockwise side.
  const Facing towardsFreeEdge = self.hingeRight
                                     ? rotateCounterClockwise(self.facing)
                                     : rotateClockwise(self.facing);

  if (rules.requireMatchingOrientation) {
    return walk(self, facingVector(towardsFreeEdge), probe, rules,
                [&](const Opening &other) {
                  return other.facing == self.facing &&
                         other.hingeRight != self.hingeRight;
                });
  }

  const Accept samePlane = [&](const Opening &other) {
    return other.facing == self.facing || other.facing == opposite(self.facing);
  };
  for (Facing side : {towardsFreeEdge, opposite(towardsFreeEdge)}) {
    if (auto partner = walk(self, facingVector(side), probe, rules, samePlane))
      return partner;
  }
  return std::nullopt;
}

std::optional<Opening> findTrapdoorPartner(const Opening &self,
                                           const ProbeFn &probe,
                                           const PairingRules &rules) {
  // A trapdoor's free edge points along its facing; the partner sits there,
  // facing back, on the same half of the block.
  if (rules.requireMatchingOrientation) {
    return walk(self, facingVector(self.facing), probe, rules,
                [&](const Opening &other) {
                  return other.facing == opposite(self.facing) &&
                         other.upsideDown == self.upsideDown;
                });
  }

  const Accept sameHalf = [&](const Opening &other) {
    return other.upsideDown == self.upsideDown;
  };
  for (Facing side : {self.facing, opposite(self.facing),
                      rotateClockwise(self.facing),
                      rotateCounterClockwise(self.facing)}) {
    if (auto partner = walk(self, facingVector(side), probe, rules, sameHalf))
      return partner;
  }
  return std::nullopt;
}

} // namespace

bool typesCompatible(const Opening &a, const Opening &b,
                     const PairingRules &rules) {
  if (a.kind != b.kind) return false;
  if (!rules.requireMatchingBlockType) return true;
  if (rules.allowDifferentVariants) return a.family == b.family;
  return a.name == b.name;
}

std::optional<Opening> findPartner(const Opening &self, const ProbeFn &probe,
                                   const PairingRules &rules) {
  return self.kind == OpeningKind::Door ? findDoorPartner(self, probe, rules)
                                        : findTrapdoorPartner(self, probe, rules);
}

} // namespace cdd
