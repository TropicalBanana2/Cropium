"""Read the supplied download around the user-recorded mine; never modify the save."""
import argparse
import collections
import gzip
import io
import json
import math
import re
import zlib
from pathlib import Path

import nbtlib


def read_blocks(save, min_x, max_x, min_z, max_z, min_y=110, max_y=124, with_properties=False):
    blocks = {}
    region = Path(save) / "dimensions/minecraft/overworld/region"
    for rx in range(min_x // 512, max_x // 512 + 1):
        for rz in range(min_z // 512, max_z // 512 + 1):
            data = (region / f"r.{rx}.{rz}.mca").read_bytes()
            for i in range(1024):
                cx, cz = rx * 32 + i % 32, rz * 32 + i // 32
                if cx * 16 > max_x or cx * 16 + 15 < min_x or cz * 16 > max_z or cz * 16 + 15 < min_z:
                    continue
                offset = int.from_bytes(data[i * 4:i * 4 + 4], "big") >> 8
                if not offset:
                    continue
                start = offset * 4096
                size = int.from_bytes(data[start:start + 4], "big")
                compression = data[start + 4]
                payload = data[start + 5:start + 4 + size]
                raw = gzip.decompress(payload) if compression == 1 else zlib.decompress(payload) if compression == 2 else payload
                chunk = nbtlib.File.parse(io.BytesIO(raw))
                for section in chunk["sections"]:
                    sy = int(section["Y"]) * 16
                    if sy > max_y or sy + 15 < min_y:
                        continue
                    states = section.get("block_states", {})
                    palette = states.get("palette", [])
                    if not palette:
                        continue
                    names = [str(entry["Name"]).removeprefix("minecraft:") for entry in palette]
                    bits = max(4, (len(names) - 1).bit_length())
                    per = 64 // bits
                    mask = (1 << bits) - 1
                    packed = states.get("data", [])
                    for index in range(4096):
                        x, y, z = cx * 16 + (index & 15), sy + (index >> 8), cz * 16 + ((index >> 4) & 15)
                        if not (min_x <= x <= max_x and min_y <= y <= max_y and min_z <= z <= max_z):
                            continue
                        entry = 0 if len(names) == 1 else (int(packed[index // per]) >> ((index % per) * bits)) & mask
                        blocks[x, y, z] = (names[entry], dict(palette[entry].get("Properties", {}))) if with_properties else names[entry]
    return blocks


def verify(save):
    """Check source coordinates and entry clearance against independent saved block data."""
    source = (Path(__file__).resolve().parents[2] /
              "src/main/java/com/salesfarm/croppilot/MineLayout.java").read_text(encoding="utf-8")
    constants = {name: int(value) for name, value in re.findall(r"static final int (\w+) = (\d+);", source)}
    x0, x1 = constants["MIN_X"], constants["MAX_X"]
    z0, z1, floor = constants["MIN_Z"], constants["MAX_Z"], constants["FLOOR_Y"]
    entry = [(float(x), float(z)) for x, z in re.findall(r"new MotionMath.Vec2\(([\d.]+), ([\d.]+)\)", source)]
    blocks = read_blocks(save, x0-1, 435, z0-3, z1+1, 110, 123, True)
    non_colliding = {"air", "cave_air", "void_air", "light"}

    def block(x, y, z):
        assert (x,y,z) in blocks, f"Missing saved block {x,y,z}"
        return blocks[x,y,z]

    def top(x, z):
        for y in range(122, 109, -1):
            name, props = block(x,y,z)
            if name not in non_colliding:
                return y + (0.5 if name.endswith("_slab") and props.get("type") == "bottom" else 1.0)
        raise AssertionError(f"No support at {x,z}")

    rim = 0
    materials = collections.Counter()
    for x in range(x0-1, x1+2):
        for z in range(z0-1, z1+2):
            name, _ = block(x,floor,z)
            if x in (x0-1, x1+1) or z in (z0-1, z1+1):
                assert name.startswith("waxed_") and name.endswith("cut_copper_stairs"), (x,z,name)
                rim += 1
            else:
                materials[name] += 1
                assert name not in non_colliding and not name.endswith("_stairs"), (x,z,name)
                for y in range(floor+1, floor+4):
                    assert block(x,y,z)[0] in non_colliding, f"Interior obstruction at {x,y,z}"
    print(f"PASS: {sum(materials.values())} interior blocks at Y={floor}: {dict(materials)}; {rim} waxed copper rim blocks")

    start = (431.271, 1527.239)
    previous_top = 115.0
    samples = 0
    for end in entry:
        distance = math.dist(start, end)
        steps = math.ceil(distance / 0.125)
        for step in range(steps+1):
            t = step / steps
            x,z = (start[i] + (end[i]-start[i])*t for i in range(2))
            # Player half-width + a 0.25-block allowance for smoothing the shallow bend.
            footprint = [(bx,bz) for bx in range(math.floor(x-.55), math.floor(x+.55)+1)
                         for bz in range(math.floor(z-.55), math.floor(z+.55)+1)]
            height = max(top(bx,bz) for bx,bz in footprint)
            assert abs(height-previous_top) <= .6, f"Unwalkable entry step at {x,z}: {previous_top}->{height}"
            for bx,bz in footprint:
                for by in range(math.ceil(height), math.ceil(height+1.8)):
                    assert block(bx,by,bz)[0] in non_colliding, f"Entry head obstruction at {bx,by,bz}"
            previous_top = height
            samples += 1
        start = end
    assert previous_top == floor+1
    assert x0+5 < start[0] < x1-4 and z0+5 < start[1] < z1-4
    print(f"PASS: {samples} buffered entry samples; slab steps <=0.5 blocks, headroom clear, ends inside buffered mine at feet Y={previous_top}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("save")
    parser.add_argument("--y", type=int)
    parser.add_argument("--entry", action="store_true")
    parser.add_argument("--verify", action="store_true")
    args = parser.parse_args()
    if args.verify:
        verify(args.save)
        return
    blocks = read_blocks(args.save, 310, 441, 1517, 1619)
    if args.entry:
        for z in range(1520, 1545):
            print(z, " ".join(f"{x}:{blocks.get((x,114,z),'?')}/{blocks.get((x,115,z),'?')}" for x in range(389, 435)))
        return
    if args.y is None:
        for y in range(110, 125):
            counts = collections.Counter(name for (x, by, z), name in blocks.items()
                                         if by == y and 321 <= x <= 401 and 1528 <= z <= 1608)
            print(y, counts.most_common(12))
        for x, z in [(401,1528),(321,1608),(360,1560),(431,1527)]:
            print("COLUMN", x, z, [(y, blocks.get((x,y,z))) for y in range(110,125)])
        return
    y = args.y
    counts = collections.Counter()
    for z in range(1525, 1612):
        row = []
        for x in range(318, 405):
            name = blocks.get((x, y, z), "?")
            counts[name] += 1
            code = "." if name == "air" else "S" if "waxed" in name and "copper_stairs" in name else "s" if "stairs" in name else "p" if "sponge" in name else "O" if "ore" in name else "c" if "copper" in name else "#"
            row.append(code)
        print(z, "".join(row))
    print(json.dumps(counts, indent=2))


if __name__ == "__main__":
    main()
