#pragma once

// Minimal mirrors of Bedrock types, limited to fields whose layout is fixed
// by the C++ ABI and standard library rather than by Mojang's private
// members. Layouts follow LeviLamina's 26.51 headers
// (BlockEvents::BlockEventBase / BlockPlayerInteractEvent) and are validated
// at runtime through RTTI before use.

#include <cstddef>
#include <cstdint>
#include <optional>
#include <string>

namespace cdd::game {

struct BlockPos {
  int x;
  int y;
  int z;
};
static_assert(sizeof(BlockPos) == 12);

// BlockEvents::BlockPlayerInteractEvent : BlockEvents::BlockEventBase
//   vptr; BlockPos const mPos; Player& mPlayer; std::optional<bool>
//   mSuccessful; std::optional<Vec3> const mHit; uchar mFace; HandSlot mHandSlot
struct PlayerInteractEventLayout {
  void *vptr;
  BlockPos pos;
  void *player;
  uint8_t successfulValue;
  uint8_t successfulEngaged;
  uint8_t pad0[2];
  float hit[3];
  uint8_t hitEngaged;
  uint8_t pad1[3];
  uint8_t face;
  uint8_t handSlot;
  uint8_t pad2[2];
};
static_assert(offsetof(PlayerInteractEventLayout, pos) == 8);
static_assert(offsetof(PlayerInteractEventLayout, player) == 24);
static_assert(offsetof(PlayerInteractEventLayout, successfulValue) == 32);
static_assert(offsetof(PlayerInteractEventLayout, hit) == 36);
static_assert(offsetof(PlayerInteractEventLayout, face) == 52);
static_assert(sizeof(PlayerInteractEventLayout) == 56);

// Itanium vtable of BlockEventBase: [0] ~D1, [1] ~D0, [2] getBlock(),
// [3] getBlockSource().
inline constexpr size_t kEventGetBlockSlot = 2;

inline constexpr const char *kPlayerInteractEventTypeName =
    "N11BlockEvents24BlockPlayerInteractEventE";
inline constexpr const char *kDoorBlockTypeName = "9DoorBlock";
inline constexpr const char *kTrapDoorBlockTypeName = "13TrapDoorBlock";
inline constexpr const char *kBlockTypeTypeName = "9BlockType";
inline constexpr const char *kGameLibrary = "libminecraftpe.so";

// Number of virtual slots BlockType had in the 26.51 headers (131 virtual
// functions + the second Itanium destructor). Only used for diagnostics: the
// real count is measured from the loaded game.
inline constexpr size_t kExpectedBlockTypeSlots = 133;

// ---- libc++ (NDK, unstable ABI v1, little endian) layouts -------------

// std::string: short form when bit 0 of byte 0 is clear (size = byte0 >> 1,
// inline data at +1); long form otherwise (size at +8, data pointer at +16).
std::optional<std::string> readLibcxxString(uintptr_t address,
                                            size_t maxLength = 256);

// std::map node: left, right, parent, is_black (padded to 32 bytes), then
// the value_type.
inline constexpr size_t kMapNodeLeft = 0;
inline constexpr size_t kMapNodeRight = 8;
inline constexpr size_t kMapNodeValue = 32;
// std::map object: begin_node*, end_node.left (== root), size.
inline constexpr size_t kMapRoot = 8;
inline constexpr size_t kMapSize = 16;
inline constexpr size_t kMapObjectSize = 24;

// Bedrock NBT: map<std::string, CompoundTagVariant>; the variant stores the
// Tag object first. Every Tag is { vptr; payload }, so the payload of a
// ByteTag/IntTag/StringTag/CompoundTag is at +8 of the tag.
inline constexpr size_t kMapNodeKey = kMapNodeValue;
inline constexpr size_t kMapNodeTag = kMapNodeValue + 24; // after std::string
inline constexpr size_t kTagPayload = 8;

} // namespace cdd::game
