#!/usr/bin/env python3
"""Validates a Cosmos Double Doors .levipack (archive layout, manifest,
config files and the native library's ELF properties).

Usage: python3 scripts/validate_levipack.py dist/cosmos-double-doors.levipack
"""

import json
import posixpath
import struct
import sys
import zipfile

EM_AARCH64 = 183
ET_DYN = 3
PT_LOAD = 1
PT_DYNAMIC = 2
DT_NEEDED = 1
DT_STRTAB = 5
SHT_DYNSYM = 11
REQUIRED_EXPORT = "PLGetModRegistration"
REQUIRED_NEEDED = {"libpreloader.so"}
MIN_PAGE_ALIGN = 16384  # Android 15+ 16 KB page devices


class Failure(Exception):
    pass


def check(condition, message):
    if not condition:
        raise Failure(message)
    print("  ok  " + message)


def parse_elf(blob):
    check(blob[:4] == b"\x7fELF", "entry is an ELF file")
    check(blob[4] == 2 and blob[5] == 1, "ELF is 64-bit little-endian")
    e_type, e_machine = struct.unpack_from("<HH", blob, 16)
    check(e_machine == EM_AARCH64, "ELF machine is AArch64 (arm64-v8a)")
    check(e_type == ET_DYN, "ELF is a shared object")

    e_phoff, e_shoff = struct.unpack_from("<QQ", blob, 32)
    e_phentsize, e_phnum, e_shentsize, e_shnum = struct.unpack_from("<HHHH", blob, 54)

    loads, dynamic = [], None
    for i in range(e_phnum):
        p_type, _, p_offset, p_vaddr, _, p_filesz, _, p_align = struct.unpack_from(
            "<IIQQQQQQ", blob, e_phoff + i * e_phentsize)
        if p_type == PT_LOAD:
            loads.append((p_offset, p_vaddr, p_align))
        elif p_type == PT_DYNAMIC:
            dynamic = (p_offset, p_filesz)
    check(loads and all(a >= MIN_PAGE_ALIGN for _, _, a in loads),
          "PT_LOAD segments are 16 KB aligned")

    def vaddr_to_offset(vaddr):
        best = None
        for offset, start, _ in loads:
            if vaddr >= start and (best is None or start > best[1]):
                best = (offset, start)
        return best[0] + (vaddr - best[1])

    check(dynamic is not None, "has a dynamic section")
    tags = {}
    needed = []
    for i in range(0, dynamic[1], 16):
        tag, value = struct.unpack_from("<qQ", blob, dynamic[0] + i)
        if tag == 0:
            break
        if tag == DT_NEEDED:
            needed.append(value)
        else:
            tags[tag] = value
    strtab = vaddr_to_offset(tags[DT_STRTAB])

    def cstr(offset):
        end = blob.index(b"\0", offset)
        return blob[offset:end].decode()

    needed_names = [cstr(strtab + n) for n in needed]
    print("      DT_NEEDED: " + ", ".join(needed_names))
    check(REQUIRED_NEEDED.issubset(needed_names),
          "links against the launcher's libpreloader.so")

    # Count dynamic symbols via the section headers (always present for
    # stripped-unneeded libraries) and look for the registration export.
    exported = set()
    for i in range(e_shnum):
        (sh_name, sh_type, _, _, sh_offset, sh_size, sh_link, _, _,
         sh_entsize) = struct.unpack_from("<IIQQQQIIQQ", blob, e_shoff + i * e_shentsize)
        if sh_type != SHT_DYNSYM:
            continue
        link_off = struct.unpack_from("<Q", blob,
                                      e_shoff + sh_link * e_shentsize + 24)[0]
        for j in range(sh_size // sh_entsize):
            st_name, st_info, _, st_shndx, _, _ = struct.unpack_from(
                "<IBBHQQ", blob, sh_offset + j * sh_entsize)
            binding = st_info >> 4
            if st_shndx != 0 and binding in (1, 2):  # GLOBAL / WEAK, defined
                exported.add(cstr(link_off + st_name))
    check(REQUIRED_EXPORT in exported,
          "exports %s (PL_REGISTER_MOD)" % REQUIRED_EXPORT)
    return needed_names


def safe_relative(path):
    parts = path.split("/")
    return path and not path.startswith("/") and ".." not in parts and "" not in parts


def main(argv):
    if len(argv) != 2:
        print(__doc__)
        return 2
    path = argv[1]
    print("Validating " + path)
    try:
        check(path.endswith(".levipack"), "file extension is .levipack")
        with zipfile.ZipFile(path) as archive:
            check(archive.testzip() is None, "archive CRCs are valid")
            names = archive.namelist()
            for name in names:
                print("      " + name)
            check(all(safe_relative(n.rstrip("/")) for n in names),
                  "all entries use safe relative paths")
            check("manifest.json" in names, "manifest.json is at the archive root")
            manifest = json.loads(archive.read("manifest.json"))
            check(manifest.get("type") == "preload-native",
                  "manifest type is preload-native")
            for field in ("name", "author", "version", "entry"):
                check(isinstance(manifest.get(field), str) and manifest[field].strip(),
                      "manifest has '%s'" % field)
            check(manifest["name"] == "Cosmos Double Doors", "mod name")
            check(manifest["author"] == "Alastor Kaneki", "author")
            versions = manifest.get("minecraft_versions", [])
            check(isinstance(versions, list) and all(isinstance(v, str) for v in versions),
                  "minecraft_versions is a list of patterns: %s" % versions)
            entry = posixpath.normpath(manifest["entry"])
            check(safe_relative(entry) and entry.endswith(".so"),
                  "entry is a safe relative .so path")
            check(entry in names, "entry library is packaged")
            check(not any(posixpath.basename(n) == "libpreloader.so" for n in names),
                  "launcher runtime (libpreloader.so) is not bundled")
            for cfg in ("config/config.json", "config/config.schema.json"):
                check(cfg in names, cfg + " is packaged")
                json.loads(archive.read(cfg))
            config = json.loads(archive.read("config/config.json"))
            for key in ("enabled", "doubleDoors", "doubleTrapdoors",
                        "requireMatchingBlockType", "allowDifferentVariants",
                        "requireMatchingOrientation", "redstoneSync",
                        "maxLinkDistance"):
                check(key in config, "config option '%s' present" % key)
            check(config["maxLinkDistance"] == 1, "default link distance is 1")
            parse_elf(archive.read(entry))
    except (Failure, zipfile.BadZipFile, KeyError, ValueError) as error:
        print("FAIL " + str(error))
        return 1
    print("levipack is valid")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
