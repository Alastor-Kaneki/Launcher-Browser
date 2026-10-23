#!/usr/bin/env python3
"""Offline pre-flight check of a libminecraftpe.so for Cosmos Double Doors.

Runs the same RTTI discovery the mod performs at runtime (src/memory/Rtti.cpp,
src/game/GameBindings.cpp) against a library file on disk, so you can tell
whether a Minecraft build is structurally compatible before launching it.

Usage:
    python3 tools/inspect_libminecraftpe.py path/to/libminecraftpe.so
    (--min-block-type-slots N lowers the plausibility floor; used by the
     self-test against the small fake game library.)

Extract libminecraftpe.so from your own Minecraft APK (lib/arm64-v8a/).
Only the Python standard library is required.

Exit status: 0 = compatible, 1 = incompatible, 2 = could not analyse.
"""

import struct
import sys

PT_LOAD = 1
PT_DYNAMIC = 2
PF_X = 1
DT_NULL = 0
DT_SYMTAB = 6
DT_RELA = 7
DT_RELASZ = 8
DT_RELR = 36
DT_RELRSZ = 35
DT_ANDROID_RELA = 0x60000011
DT_ANDROID_RELASZ = 0x60000012
R_AARCH64_RELATIVE = 1027
R_AARCH64_ABS64 = 257
R_AARCH64_GLOB_DAT = 1025

DOOR = "9DoorBlock"
TRAPDOOR = "13TrapDoorBlock"
BLOCK_TYPE = "9BlockType"
EVENT = "N11BlockEvents24BlockPlayerInteractEventE"
SERVER_PLAYER = "12ServerPlayer"
EXPECTED_BLOCK_TYPE_SLOTS = 133  # LeviLamina 26.51 headers
MIN_BLOCK_TYPE_SLOTS = 100
EVENT_SLOTS = 4


class Image:
    """The library as the loader would map it, with RELATIVE relocations."""

    def __init__(self, data):
        if data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
            raise ValueError("not a little-endian ELF64 file")
        (e_machine,) = struct.unpack_from("<H", data, 18)
        if e_machine != 183:
            raise ValueError("not an AArch64 library (e_machine=%d)" % e_machine)
        self.data = data
        e_phoff, = struct.unpack_from("<Q", data, 32)
        e_phentsize, e_phnum = struct.unpack_from("<HH", data, 54)
        self.segments = []
        dynamic = None
        for i in range(e_phnum):
            p_type, p_flags, p_offset, p_vaddr, _, p_filesz, p_memsz, _ = (
                struct.unpack_from("<IIQQQQQQ", data, e_phoff + i * e_phentsize))
            if p_type == PT_LOAD and p_memsz:
                self.segments.append((p_vaddr, p_memsz, p_offset, p_filesz,
                                      bool(p_flags & PF_X)))
            elif p_type == PT_DYNAMIC:
                dynamic = (p_offset, p_filesz)
        self.relocated = {}
        self.symtab = None
        if dynamic:
            self._apply_relocations(*dynamic)

    # -- memory model --------------------------------------------------------
    def _file_offset(self, vaddr):
        for start, memsz, offset, filesz, _ in self.segments:
            if start <= vaddr < start + filesz:
                return offset + (vaddr - start)
        return None

    def is_exec(self, vaddr):
        return any(s <= vaddr < s + m and x for s, m, _, _, x in self.segments)

    def qword(self, vaddr):
        if vaddr in self.relocated:
            return self.relocated[vaddr]
        off = self._file_offset(vaddr)
        if off is None or off + 8 > len(self.data):
            return None
        return struct.unpack_from("<Q", self.data, off)[0]

    # -- relocations ---------------------------------------------------------
    def _apply_relocations(self, dyn_off, dyn_size):
        tags = {}
        for i in range(0, dyn_size, 16):
            tag, val = struct.unpack_from("<qQ", self.data, dyn_off + i)
            if tag == DT_NULL:
                break
            tags[tag] = val
        self.symtab = tags.get(DT_SYMTAB)
        if DT_RELA in tags:
            self._rela(tags[DT_RELA], tags.get(DT_RELASZ, 0))
        if DT_RELR in tags:
            self._relr(tags[DT_RELR], tags.get(DT_RELRSZ, 0))
        if DT_ANDROID_RELA in tags:
            self._android_aps2(tags[DT_ANDROID_RELA], tags.get(DT_ANDROID_RELASZ, 0))

    def _symbol_value(self, index):
        """st_value of a defined dynamic symbol, or None if undefined."""
        if not self.symtab or index == 0:
            return None
        off = self._file_offset(self.symtab + index * 24)
        if off is None:
            return None
        _, _, _, shndx, value, _ = struct.unpack_from("<IBBHQQ", self.data, off)
        return value if shndx != 0 else None

    def _apply(self, r_offset, r_info, r_addend):
        kind, sym = r_info & 0xFFFFFFFF, r_info >> 32
        if kind == R_AARCH64_RELATIVE:
            self.relocated[r_offset] = r_addend & 0xFFFFFFFFFFFFFFFF
        elif kind in (R_AARCH64_ABS64, R_AARCH64_GLOB_DAT):
            value = self._symbol_value(sym)
            if value is not None:
                self.relocated[r_offset] = (value + r_addend) & 0xFFFFFFFFFFFFFFFF

    def _rela(self, vaddr, size):
        off = self._file_offset(vaddr)
        for i in range(0, size, 24):
            r_offset, r_info, r_addend = struct.unpack_from("<QQq", self.data, off + i)
            self._apply(r_offset, r_info, r_addend)

    def _relr(self, vaddr, size):
        off = self._file_offset(vaddr)
        where = 0
        for i in range(0, size, 8):
            (entry,) = struct.unpack_from("<Q", self.data, off + i)
            if entry & 1 == 0:
                self._relr_apply(entry)
                where = entry + 8
            else:
                bits = entry >> 1
                for bit in range(63):
                    if bits & (1 << bit):
                        self._relr_apply(where + bit * 8)
                where += 63 * 8

    def _relr_apply(self, addr):
        off = self._file_offset(addr)
        if off is not None:
            self.relocated[addr] = struct.unpack_from("<Q", self.data, off)[0]

    def _android_aps2(self, vaddr, size):
        off = self._file_offset(vaddr)
        blob = self.data[off:off + size]
        if blob[:4] != b"APS2":
            return
        pos = [4]

        def sleb():
            result, shift = 0, 0
            while True:
                byte = blob[pos[0]]
                pos[0] += 1
                result |= (byte & 0x7F) << shift
                shift += 7
                if byte & 0x80 == 0:
                    if byte & 0x40:
                        result -= 1 << shift
                    return result

        count = sleb()
        r_offset = sleb()
        r_info = r_addend = 0
        done = 0
        while done < count:
            group_size = sleb()
            flags = sleb()
            grouped_by_info = flags & 1
            grouped_by_delta = flags & 2
            grouped_by_addend = flags & 4
            has_addend = flags & 8
            delta = sleb() if grouped_by_delta else 0
            if grouped_by_info:
                r_info = sleb()
            if has_addend and grouped_by_addend:
                r_addend += sleb()
            elif not has_addend:
                r_addend = 0
            for _ in range(group_size):
                r_offset += delta if grouped_by_delta else sleb()
                if not grouped_by_info:
                    r_info = sleb()
                if has_addend and not grouped_by_addend:
                    r_addend += sleb()
                self._apply(r_offset, r_info, r_addend)
            done += group_size

    # -- scans ----------------------------------------------------------------
    def find_bytes(self, needle):
        hits = []
        for start, _, offset, filesz, _ in self.segments:
            chunk = self.data[offset:offset + filesz]
            i = chunk.find(needle)
            while i >= 0:
                hits.append(start + i)
                i = chunk.find(needle, i + 1)
        return hits

    def find_pointer(self, value):
        hits = [a for a, v in self.relocated.items() if v == value]
        packed = struct.pack("<Q", value)
        for start, _, offset, filesz, is_exec in self.segments:
            if is_exec:
                continue
            chunk = self.data[offset:offset + filesz]
            i = chunk.find(packed)
            while i >= 0:
                addr = start + i
                if addr % 8 == 0:
                    if addr not in self.relocated:
                        hits.append(addr)
                i = chunk.find(packed, i + 1)
        return sorted(set(hits))


def primary_vtable(image, name):
    """Mirror of cdd::mem::findPrimaryVtable. Returns (address_point, slots)."""
    found = None
    for name_addr in image.find_bytes(name.encode() + b"\0"):
        before = image._file_offset(name_addr - 1)
        if before is None or image.data[before] != 0:
            continue
        for name_ref in image.find_pointer(name_addr):
            type_info = name_ref - 8
            for ti_ref in image.find_pointer(type_info):
                if image.qword(ti_ref - 8) != 0:
                    continue
                address_point = ti_ref + 8
                slots = []
                while len(slots) < 4096:
                    entry = image.qword(address_point + 8 * len(slots))
                    if entry is None or not image.is_exec(entry):
                        break
                    slots.append(entry)
                if not slots:
                    continue
                if found and found[0] != address_point:
                    return "ambiguous"
                found = (address_point, slots)
    return found


def main(argv):
    global MIN_BLOCK_TYPE_SLOTS
    args = list(argv[1:])
    if "--min-block-type-slots" in args:
        i = args.index("--min-block-type-slots")
        MIN_BLOCK_TYPE_SLOTS = int(args[i + 1])
        del args[i:i + 2]
    if len(args) != 1:
        print(__doc__)
        return 2
    argv = [argv[0]] + args
    try:
        with open(argv[1], "rb") as f:
            image = Image(f.read())
    except (OSError, ValueError) as error:
        print("error:", error)
        return 2

    print("Relocations applied: %d" % len(image.relocated))
    ok = True

    def report(label, value):
        nonlocal ok
        if value is None or value == "ambiguous":
            print("  %-34s %s" % (label, "AMBIGUOUS" if value else "NOT FOUND"))
            ok = False
            return None
        print("  %-34s vtable +%#x, %d slots" % (label, value[0], len(value[1])))
        return value

    print("RTTI discovery:")
    block_type = primary_vtable(image, BLOCK_TYPE)
    if block_type in (None, "ambiguous"):
        print("  %-34s not found (optional)" % BLOCK_TYPE)
        block_type = None
    else:
        print("  %-34s vtable +%#x, %d slots (26.51 headers: %d)" % (
            BLOCK_TYPE, block_type[0], len(block_type[1]), EXPECTED_BLOCK_TYPE_SLOTS))
    door = report(DOOR, primary_vtable(image, DOOR))
    trapdoor = report(TRAPDOOR, primary_vtable(image, TRAPDOOR))
    event = report(EVENT, primary_vtable(image, EVENT))

    if image.find_bytes(b"\0" + SERVER_PLAYER.encode() + b"\0"):
        print("  %-34s present" % SERVER_PLAYER)
    else:
        print("  %-34s NOT FOUND" % SERVER_PLAYER)
        ok = False

    print("Structural checks:")

    def check(label, condition):
        nonlocal ok
        print("  [%s] %s" % ("ok" if condition else "FAIL", label))
        ok = ok and condition

    if door and trapdoor:
        n = len(door[1])
        check("DoorBlock and TrapDoorBlock have the same slot count",
              n == len(trapdoor[1]))
        if block_type:
            check("DoorBlock adds exactly one virtual to BlockType",
                  n == len(block_type[1]) + 1)
        check("vtable size is plausible (>= %d)" % (MIN_BLOCK_TYPE_SLOTS + 1),
              n >= MIN_BLOCK_TYPE_SLOTS + 1)
        check("_useDoor and _useTrapDoor are distinct functions",
              door[1][-1] != trapdoor[1][-1])
        print("  hook targets: DoorBlock slot %d -> +%#x, TrapDoorBlock slot %d -> +%#x"
              % (n - 1, door[1][-1], len(trapdoor[1]) - 1, trapdoor[1][-1]))
    if event:
        check("BlockPlayerInteractEvent vtable has %d slots" % EVENT_SLOTS,
              len(event[1]) == EVENT_SLOTS)

    print("Result:", "COMPATIBLE (structure matches; in-game test still required)"
          if ok else "NOT COMPATIBLE - the mod will stay inactive on this build")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
