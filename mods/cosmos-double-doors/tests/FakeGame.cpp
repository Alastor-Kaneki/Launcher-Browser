#include "FakeGame.hpp"

#include <stdexcept>

// Out-of-line key functions so every vtable and type_info is emitted.
Tag::~Tag() = default;
EndTag::~EndTag() = default;
ByteTag::~ByteTag() = default;
IntTag::~IntTag() = default;
StringTag::~StringTag() = default;
CompoundTag::~CompoundTag() = default;

BlockType::~BlockType() = default;
bool BlockType::isInteractiveBlock() const { return false; }
int BlockType::getVariant(const Block &) const { return 0; }
void BlockType::neighborChanged(BlockSource &, const BlockPos &,
                                const BlockPos &) const {}
bool BlockType::canSurvive(BlockSource &, const BlockPos &) const {
  return true;
}
int BlockType::getLight(const Block &) const { return 0; }
void BlockType::entityInside(BlockSource &, const BlockPos &) const {}

DoorBlock::~DoorBlock() = default;
bool DoorBlock::isInteractiveBlock() const { return true; }
int DoorBlock::getVariant(const Block &) const { return mIron ? 6 : 0; }

TrapDoorBlock::~TrapDoorBlock() = default;
bool TrapDoorBlock::isInteractiveBlock() const { return true; }

Player::~Player() = default;
ServerPlayer::~ServerPlayer() = default;
LocalPlayer::~LocalPlayer() = default;

namespace BlockEvents {
BlockEventBase::~BlockEventBase() = default;
const Block &BlockEventBase::getBlock() const {
  return getBlockSource().getBlock(mPos);
}
BlockPlayerInteractEvent::~BlockPlayerInteractEvent() = default;
const BlockSource &BlockPlayerInteractEvent::getBlockSource() const {
  return *mPlayer.mRegion;
}
} // namespace BlockEvents

namespace {

CompoundTagVariant makeTag(const std::string &key, const StateValue &value) {
  CompoundTagVariant v;
  if (const auto *text = std::get_if<std::string>(&value)) {
    v.mTagStorage = StringTag(*text);
  } else if (key.size() > 4 && key.compare(key.size() - 4, 4, "_bit") == 0) {
    v.mTagStorage = ByteTag(static_cast<int8_t>(std::get<int>(value)));
  } else {
    v.mTagStorage = IntTag(std::get<int>(value));
  }
  return v;
}

std::deque<Block> &palette() {
  static std::deque<Block> blocks;
  return blocks;
}

BlockType &airType() {
  static BlockType type("minecraft:air");
  return type;
}

std::tuple<int, int, int> key(const BlockPos &p) { return {p.x, p.y, p.z}; }

BlockPos above(BlockPos p) { return {p.x, p.y + 1, p.z}; }
BlockPos below(BlockPos p) { return {p.x, p.y - 1, p.z}; }

} // namespace

Block::Block(const BlockType &type, StateMap states)
    : mBlockType(&type), mStates(std::move(states)) {
  CompoundTagVariant name;
  name.mTagStorage = StringTag(type.mName);
  CompoundTagVariant stateTag;
  CompoundTag stateCompound;
  for (const auto &[k, v] : mStates) stateCompound.mTags[k] = makeTag(k, v);
  stateTag.mTagStorage = std::move(stateCompound);
  CompoundTagVariant version;
  version.mTagStorage = IntTag(18168865);
  mSerializationId.mTags["name"] = std::move(name);
  mSerializationId.mTags["states"] = std::move(stateTag);
  mSerializationId.mTags["version"] = std::move(version);
  mTags = {"minecraft:is_tool_item_destructible"};
}

Block::~Block() = default;

int Block::state(const std::string &k) const {
  auto it = mStates.find(k);
  if (it == mStates.end()) throw std::runtime_error("missing state " + k);
  return std::get<int>(it->second);
}

const Block &permutation(const BlockType &type, const StateMap &states) {
  for (const auto &block : palette())
    if (block.mBlockType == &type && block.mStates == states) return block;
  return palette().emplace_back(type, states);
}

const Block &air() { return permutation(airType(), {}); }

const Block &BlockSource::getBlock(const BlockPos &pos) const {
  auto it = mBlocks.find(key(pos));
  return it == mBlocks.end() ? air() : *it->second;
}
void BlockSource::setBlock(const BlockPos &pos, const Block &block) {
  mBlocks[key(pos)] = &block;
}
void BlockSource::removeBlock(const BlockPos &pos) { mBlocks.erase(key(pos)); }

namespace fake_game {

Counters &counters() {
  static Counters c;
  return c;
}

namespace {
const Block &withOpen(const Block &block, bool open) {
  StateMap states = block.mStates;
  states["open_bit"] = open ? 1 : 0;
  return permutation(*block.mBlockType, states);
}
} // namespace

void placeDoor(BlockSource &region, const DoorBlock &type, BlockPos lower,
               const std::string &cardinal, int hinge, bool open) {
  for (int upper = 0; upper <= 1; ++upper) {
    StateMap states{{"minecraft:cardinal_direction", cardinal},
                    {"door_hinge_bit", hinge},
                    {"open_bit", open ? 1 : 0},
                    {"upper_block_bit", upper}};
    region.setBlock(upper ? above(lower) : lower, permutation(type, states));
  }
}

void placeTrapdoor(BlockSource &region, const TrapDoorBlock &type,
                   BlockPos pos, int direction, bool upsideDown, bool open) {
  StateMap states{{"direction", direction},
                  {"open_bit", open ? 1 : 0},
                  {"upside_down_bit", upsideDown ? 1 : 0}};
  region.setBlock(pos, permutation(type, states));
}

bool isOpen(const BlockSource &region, BlockPos pos) {
  return region.getBlock(pos).state("open_bit") != 0;
}

} // namespace fake_game

// "Vanilla" behaviour: iron doors ignore players; otherwise both halves flip
// and one sound plays.
void DoorBlock::_useDoor(BlockEvents::BlockPlayerInteractEvent &event) const {
  ++fake_game::counters().doorUseCalls;
  if (mIron) return;
  auto &region = const_cast<BlockSource &>(event.getBlockSource());
  const Block &clicked = region.getBlock(event.mPos);
  if (clicked.mBlockType != this) return;
  const BlockPos lower =
      clicked.state("upper_block_bit") ? below(event.mPos) : event.mPos;
  const Block &lowerBlock = region.getBlock(lower);
  if (lowerBlock.mBlockType != this) return;
  const bool open = !lowerBlock.state("open_bit");
  region.setBlock(lower, fake_game::withOpen(lowerBlock, open));
  const Block &upperBlock = region.getBlock(above(lower));
  if (upperBlock.mBlockType == this)
    region.setBlock(above(lower), fake_game::withOpen(upperBlock, open));
  ++fake_game::counters().sounds;
  event.mSuccessful = true;
}

void TrapDoorBlock::_useTrapDoor(
    BlockEvents::BlockPlayerInteractEvent &event) const {
  ++fake_game::counters().trapdoorUseCalls;
  auto &region = const_cast<BlockSource &>(event.getBlockSource());
  const Block &clicked = region.getBlock(event.mPos);
  if (clicked.mBlockType != this) return;
  region.setBlock(event.mPos,
                  fake_game::withOpen(clicked, !clicked.state("open_bit")));
  ++fake_game::counters().sounds;
  event.mSuccessful = true;
}
