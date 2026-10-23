#pragma once

namespace cdd {

inline constexpr int kMinLinkDistance = 1;
inline constexpr int kMaxLinkDistance = 4;

inline int clampLinkDistance(int value) {
  if (value < kMinLinkDistance) return kMinLinkDistance;
  if (value > kMaxLinkDistance) return kMaxLinkDistance;
  return value;
}

} // namespace cdd
