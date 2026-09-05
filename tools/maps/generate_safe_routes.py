"""Verify the embedded farm routes, or print a regenerated payload without writing files."""

import argparse
import base64
import json
import math
from pathlib import Path

import numpy as np
from scipy.ndimage import distance_transform_edt


ANCHOR_X = 498
ANCHOR_Z = 1474
ROUTE_CENTER = np.array((428.5, 1401.5))
OUTLINE_SCALES = (0.78, 0.81, 0.84)
MINIMUM_CLEARANCE = 14.0
EXCLUDED_STRUCTURE_ZONE = (477.0, 535.0, 1412.0, 1470.0)


def samples(start, end, spacing=0.15):
    distance = np.linalg.norm(end - start)
    count = max(2, math.ceil(distance / spacing) + 1)
    return (start + (end - start) * progress for progress in np.linspace(0.0, 1.0, count))


def chaikin(points, rounds=4):
    result = points
    for _ in range(rounds):
        smoothed = []
        for start, end in zip(result, result[1:] + result[:1]):
            smoothed.extend((start * 0.75 + end * 0.25, start * 0.25 + end * 0.75))
        result = smoothed
    return result


def resample_closed(points, spacing=3.0):
    edges = list(zip(points, points[1:] + points[:1]))
    lengths = [np.linalg.norm(end - start) for start, end in edges]
    perimeter = sum(lengths)
    count = max(3, round(perimeter / spacing))
    targets = np.linspace(0.0, perimeter, count, endpoint=False)
    result = []
    edge_index = 0
    edge_start = 0.0
    for target in targets:
        while target > edge_start + lengths[edge_index]:
            edge_start += lengths[edge_index]
            edge_index += 1
        start, end = edges[edge_index]
        result.append(start + (end - start) * ((target - edge_start) / lengths[edge_index]))
    return result


def segment_clearance(distance, min_x, min_z, start, end):
    result = math.inf
    for point in samples(start, end):
        column = int(math.floor(point[0])) - min_x
        row = int(math.floor(point[1])) - min_z
        if row < 0 or column < 0 or row >= distance.shape[0] or column >= distance.shape[1]:
            return 0.0
        result = min(result, float(distance[row, column]))
    return result


def in_structure_zone(point):
    min_x, max_x, min_z, max_z = EXCLUDED_STRUCTURE_ZONE
    return min_x <= point[0] <= max_x and min_z <= point[1] <= max_z


def maximum_corner_degrees(route):
    maximum = (0.0, 0)
    for index, point in enumerate(route):
        incoming = point - route[index - 1]
        outgoing = route[(index + 1) % len(route)] - point
        cosine = np.clip((incoming @ outgoing) / (np.linalg.norm(incoming) * np.linalg.norm(outgoing)), -1.0, 1.0)
        maximum = max(maximum, (math.degrees(math.acos(cosine)), index))
    return maximum


def outline_controls(source, scale, shortcut=False):
    scaled = [ROUTE_CENTER + (point - ROUTE_CENTER) * scale for point in source]
    arc = (scaled[1:3] + [np.array((380.0, 1437.0)), np.array((365.0, 1427.0))]
           + scaled[7:9] + [np.array((376.0, 1385.0)), np.array((380.0, 1375.0)),
                            np.array((390.0, 1368.0)), np.array((402.0, 1366.0)),
                            np.array((415.0, 1362.0))] + scaled[12:19])
    if shortcut:
        arc = arc[:6] + [np.array((390.0, 1393.0)), np.array((440.0, 1388.0)),
                         np.array((490.0, 1393.0)), arc[-1]]
    return arc + [np.array((529.0, 1386.0)), np.array((536.0, 1394.0)),
                  np.array((538.0, 1402.0)), np.array((534.0, 1409.0)),
                  np.array((525.0, 1411.0)), np.array((515.0, 1411.0)),
                  np.array((505.0, 1410.0)), np.array((485.0, 1408.0)),
                  np.array((460.0, 1415.0)), np.array((442.0, 1440.0))]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--payload", action="store_true", help="print verified route JSON instead of comparing the saved asset")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    map_path = root / "src/client/resources/assets/crop-pilot/maps/sales_minehut.json"
    obstacle_path = root / "src/client/resources/assets/crop-pilot/maps/sales_minehut_obstacles.json"
    route_path = root / "src/client/resources/assets/crop-pilot/maps/sales_minehut_safe_routes.json"
    farm = json.loads(map_path.read_text(encoding="utf-8"))
    obstacles = json.loads(obstacle_path.read_text(encoding="utf-8"))
    width = farm["maxX"] - farm["minX"] + 1
    depth = farm["maxZ"] - farm["minZ"] + 1
    states = np.frombuffer(base64.b64decode(farm["cells"]), dtype=np.uint8).reshape((depth, width)).copy()
    for relative_x, relative_z in obstacles["cells"]:
        column = ANCHOR_X + relative_x - farm["minX"]
        row = ANCHOR_Z + relative_z - farm["minZ"]
        states[row, column] = 3
    distance = distance_transform_edt(states == 1)
    source = [np.array((ANCHOR_X + x, ANCHOR_Z + z), dtype=float) for x, z in farm["routes"][3]]
    routes = [resample_closed(chaikin(outline_controls(source, scale))) for scale in OUTLINE_SCALES]
    routes.append(resample_closed(chaikin(outline_controls(source, OUTLINE_SCALES[1], shortcut=True))))

    reports = []
    for index, route in enumerate(routes):
        minimum = min(segment_clearance(distance, farm["minX"], farm["minZ"], start, end)
                      for start, end in zip(route, route[1:] + route[:1]))
        enters_structure = any(in_structure_zone(point)
                               for start, end in zip(route, route[1:] + route[:1])
                               for point in samples(start, end))
        corner, corner_index = maximum_corner_degrees(route)
        reports.append({
            "kind": "shortcut" if index == len(routes) - 1 else "outline",
            "points": len(route),
            "lapLength": round(sum(np.linalg.norm(end - start)
                                   for start, end in zip(route, route[1:] + route[:1])), 2),
            "maximumCornerDegrees": round(corner, 2),
            "worstCorner": [corner_index, *[round(value, 2) for value in route[corner_index]]],
            "minimumCellClearance": round(minimum, 2),
            "entersStructureZone": enters_structure,
        })
    if not args.payload:
        print(json.dumps(reports, indent=2))
    if any(report["minimumCellClearance"] < MINIMUM_CLEARANCE
           or report["maximumCornerDegrees"] > 20.0
           or report["entersStructureZone"] for report in reports):
        raise SystemExit("safe-outline verification failed")

    payload = {
        "sourceAnchorX": ANCHOR_X,
        "sourceAnchorZ": ANCHOR_Z,
        "routeType": "rounded-safe-outline",
        "outlineRouteCount": len(OUTLINE_SCALES),
        "shortcutRouteCount": 1,
        "minimumMapClearance": min(report["minimumCellClearance"] for report in reports),
        "excludedStructureZone": list(EXCLUDED_STRUCTURE_ZONE),
        "routes": [[[round(point[0] - ANCHOR_X, 3), round(point[1] - ANCHOR_Z, 3)]
                    for point in route] for route in routes],
    }
    if args.payload:
        print(json.dumps(payload, indent=2))
        return
    if json.loads(route_path.read_text(encoding="utf-8")) != payload:
        print(json.dumps(payload, indent=2))
        raise SystemExit("embedded safe-route file does not match the verified outline")


if __name__ == "__main__":
    main()
