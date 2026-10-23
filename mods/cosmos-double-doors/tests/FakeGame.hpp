#pragma once

// A miniature stand-in for the parts of Minecraft Bedrock the mod touches.
// Class names, inheritance and member order mirror LeviLamina's 26.51
// headers so the RTTI names ("9DoorBlock", "12ServerPlayer",
// "N11BlockEvents24BlockPlayerInteractEventE") and the libc++ layouts the
// mod discovers at runtime are produced by the real compiler and standard
// library. The "vanilla" door logic is deliberately simple: it toggles both
// halves and counts a sound per toggle.

#include <cstdint>
#include <deque>
#include <map>
#include <optional>
#include <string>
#include <tuple>
#include <variant>
#include <vector>

// ---- NBT --------------------------------------------------------------------
class Tag {
public:
  virtual ~Tag();
};
class EndTag : public Tag {
public:
  ~EndTag() override;
};
class ByteTag : public Tag {
public:
  explicit ByteTag(int8_t v = 0) : data(v) {}
  ~ByteTag() override;
  int8_t data;
};
class IntTag : public Tag {
public:
  explicit IntTag(int v = 0) : data(v) {}
  ~IntTag() override;
  int data;
};
class StringTag : public Tag, public std::string {
public:
  explicit StringTag(std::string v = {}) : std::string(std::move(v)) {}
  ~StringTag() override;
};
class CompoundTagVariant;
class CompoundTag : public Tag {
public:
  ~CompoundTag() override;
  std::map<std::string, CompoundTagVariant, std::less<void>> mTags;
};
class CompoundTagVariant {
public:
  std::variant<EndTag, ByteTag, IntTag, StringTag, CompoundTag> mTagStorage;
};

// ---- World ------------------------------------------------------------------
struct BlockPos {
  int x, y, z;
};
struct Vec3 {
  float x, y, z;
};

class Block;
class BlockSource;
class Player;
namespace BlockEvents {
class BlockPlayerInteractEvent;
}

class BlockType {
public:
  explicit BlockType(std::string name) : mName(std::move(name)) {}
  virtual ~BlockType();
  virtual bool isInteractiveBlock() const;
  virtual int getVariant(const Block &) const;
  virtual void neighborChanged(BlockSource &, const BlockPos &,
                               const BlockPos &) const;
  virtual bool canSurvive(BlockSource &, const BlockPos &) const;
  virtual int getLight(const Block &) const;
  virtual void entityInside(BlockSource &, const BlockPos &) const;
  std::string mName;
};

class DoorBlock : public BlockType {
public:
  DoorBlock(std::string name, bool iron)
      : BlockType(std::move(name)), mIron(iron) {}
  ~DoorBlock() override;
  bool isInteractiveBlock() const override;
  int getVariant(const Block &) const override;
  virtual void _useDoor(BlockEvents::BlockPlayerInteractEvent &event) const;
  bool mIron;
};

class TrapDoorBlock : public BlockType {
public:
  explicit TrapDoorBlock(std::string name) : BlockType(std::move(name)) {}
  ~TrapDoorBlock() override;
  bool isInteractiveBlock() const override;
  virtual void _useTrapDoor(BlockEvents::BlockPlayerInteractEvent &event) const;
};

using StateValue = std::variant<int, std::string>;
using StateMap = std::map<std::string, StateValue>;

class Block {
public:
  Block(const BlockType &type, StateMap states);
  virtual ~Block();

  unsigned char mComponents[96] = {};
  const BlockType *mBlockType;
  uint64_t mCachedComponentData = 0;
  unsigned char mDirectData[96] = {};
  std::vector<std::string> mTags;
  CompoundTag mSerializationId;
  uint64_t mSerializationIdHash = 0;
  StateMap mStates; // test-side convenience copy

  int state(const std::string &key) const;
};

// Interns one Block per (type, states), like Bedrock's block palette.
const Block &permutation(const BlockType &type, const StateMap &states);
const Block &air();

class BlockSource {
public:
  const Block &getBlock(const BlockPos &pos) const;
  void setBlock(const BlockPos &pos, const Block &block);
  void removeBlock(const BlockPos &pos);

  std::map<std::tuple<int, int, int>, const Block *> mBlocks;
};

class Player {
public:
  explicit Player(BlockSource &region) : mRegion(&region) {}
  virtual ~Player();
  BlockSource *mRegion;
};
class ServerPlayer : public Player {
public:
  using Player::Player;
  ~ServerPlayer() override;
};
class LocalPlayer : public Player {
public:
  using Player::Player;
  ~LocalPlayer() override;
};

namespace BlockEvents {
class BlockEventBase {
public:
  explicit BlockEventBase(BlockPos pos) : mPos(pos) {}
  virtual ~BlockEventBase();
  virtual const Block &getBlock() const;
  virtual const BlockSource &getBlockSource() const = 0;
  const BlockPos mPos;
};

class BlockPlayerInteractEvent : public BlockEventBase {
public:
  BlockPlayerInteractEvent(BlockPos pos, Player &player)
      : BlockEventBase(pos), mPlayer(player), mHit(Vec3{0.5f, 0.5f, 0.5f}),
        mFace(2), mHandSlot(0) {}
  ~BlockPlayerInteractEvent() override;
  const BlockSource &getBlockSource() const override;

  Player &mPlayer;
  std::optional<bool> mSuccessful;
  const std::optional<Vec3> mHit;
  const unsigned char mFace;
  unsigned char mHandSlot;
};
} // namespace BlockEvents

namespace fake_game {
struct Counters {
  int doorUseCalls = 0;
  int trapdoorUseCalls = 0;
  int sounds = 0;
};
Counters &counters();

// Places a door (both halves). `cardinal` is Bedrock's
// minecraft:cardinal_direction value.
void placeDoor(BlockSource &region, const DoorBlock &type, BlockPos lower,
               const std::string &cardinal, int hinge, bool open = false);
void placeTrapdoor(BlockSource &region, const TrapDoorBlock &type,
                   BlockPos pos, int direction, bool upsideDown,
                   bool open = false);
bool isOpen(const BlockSource &region, BlockPos pos);
} // namespace fake_game
