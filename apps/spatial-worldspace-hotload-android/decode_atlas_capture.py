"""Decode observed per-eye diagnostic pixels. Requires the declared fiducial grid.

Only PNG pixels determine calibration and tokens; source/API state is never input.
This tool does not establish headset acceptance from synthetic fixtures.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path

import numpy as np
from PIL import Image


class CaptureError(ValueError):
    pass


def project(h, points):
    p = np.column_stack((np.asarray(points, dtype=float), np.ones(len(points)))) @ h.T
    if np.any(np.abs(p[:, 2]) < 1e-8):
        raise CaptureError("DEGENERATE_PROJECTION")
    return p[:, :2] / p[:, 2:]


def homography(source, target):
    # Normalize both domains before DLT, avoiding metre/pixel conditioning bias.
    def normalize(points):
        points = np.asarray(points, float)
        center = points.mean(axis=0)
        distance = np.linalg.norm(points - center, axis=1).mean()
        if distance < 1e-8:
            raise CaptureError("DEGENERATE_FIDUCIALS")
        scale = 2**.5 / distance
        t = np.array([[scale, 0, -scale*center[0]], [0, scale, -scale*center[1]], [0, 0, 1]])
        return project(t, points), t
    a, ta = normalize(source)
    b, tb = normalize(target)
    rows = []
    for (x, y), (u, v) in zip(a, b):
        rows.extend([[-x, -y, -1, 0, 0, 0, u*x, u*y, u],
                     [0, 0, 0, -x, -y, -1, v*x, v*y, v]])
    _, singular, vectors = np.linalg.svd(rows)
    if singular[-1] < 1e-8:
        raise CaptureError("DEGENERATE_FIDUCIALS")
    h = np.linalg.inv(tb) @ vectors[-1].reshape(3, 3) @ ta
    return h / h[2, 2]


def components(mask):
    # Eight-neighbour components, bounded to observed colored pixels.
    pending = set(map(tuple, np.argwhere(mask)))
    found = []
    while pending:
        seed = pending.pop()
        stack, points = [seed], [seed]
        while stack:
            y, x = stack.pop()
            for dy in (-1, 0, 1):
                for dx in (-1, 0, 1):
                    q = y+dy, x+dx
                    if q in pending:
                        pending.remove(q)
                        stack.append(q)
                        points.append(q)
        if len(points) >= 9:
            found.append(np.asarray(points))
    return found


def cell_coordinate(expression, name, index):
    # The contract declares affine centres, never execute expressions from JSON.
    match = re.fullmatch(r"\s*([+-]?[\d.]+)\s*([+-])\s*"+name+r"\s*\*\s*([\d.]+)\s*", expression)
    if not match:
        raise CaptureError("UNSUPPORTED_GRID_COORDINATES")
    return float(match[1]) + (1 if match[2] == "+" else -1)*index*float(match[3])


def sample(rgb, h, x, y, half_width, half_height):
    points = [[x+dx*half_width, y+dy*half_height] for dy in (-.45, 0, .45) for dx in (-.45, 0, .45)]
    pixels = project(h, points)
    height, width = rgb.shape[:2]
    if np.any(pixels < 1) or np.any(pixels[:, 0] >= width-1) or np.any(pixels[:, 1] >= height-1):
        raise CaptureError("GRID_OUT_OF_BOUNDS")
    # Require enough projected cell area to resolve its interior, not one pixel.
    corners = project(h, [[x-half_width,y-half_height], [x+half_width,y-half_height], [x+half_width,y+half_height], [x-half_width,y+half_height]])
    if np.any(corners < 1) or np.any(corners[:,0] >= width-1) or np.any(corners[:,1] >= height-1):
        raise CaptureError("CLIPPED_CELL")
    if min(np.linalg.norm(corners[i]-corners[(i+1)%4]) for i in range(4)) < 3:
        raise CaptureError("CELL_UNDERSAMPLED")
    pixels = np.rint(pixels).astype(int)
    values = rgb[pixels[:, 1], pixels[:, 0]]
    return np.median(values, axis=0), values


def classify(value, values, black, white):
    if value[0] > .5 and value[2] > .5 and value[1] < .35:
        return "unavailable"
    if value[1] > .45 and value[1] > max(value[0], value[2])+.2:
        return "pass"
    if value[0] > .45 and value[0] > max(value[1], value[2])+.2:
        return "fail"
    if value[2] > .45 and value[2] > max(value[0], value[1])+.2:
        return "unused"
    luminance = values.mean(axis=1)
    midpoint = (black+white)/2
    margin = (white-black)*.22
    if np.all(luminance > midpoint+margin) and np.max(np.ptp(values, axis=1)) < .2:
        return 1
    if np.all(luminance < midpoint-margin) and np.max(np.ptp(values, axis=1)) < .2:
        return 0
    raise CaptureError("AMBIGUOUS_CELL")


def decode_eye(rgb, grid, expected_version=None):
    if grid.get("schema") != "rusty.quest.atlas_diagnostic.grid.v1" or grid.get("columns") != 32 or grid.get("bitOrder") != "least-significant-left":
        raise CaptureError("UNSUPPORTED_GRID_CONTRACT")
    if sorted(d["row"] for d in grid["rowMap"]) != list(range(grid["rows"])):
        raise CaptureError("INCOMPLETE_ROW_CONTRACT")
    calibration = grid["calibration"]
    markers = calibration["fiducials"]
    if len(markers) != 4 or len({m["id"] for m in markers}) != 4:
        raise CaptureError("UNSUPPORTED_FIDUCIAL_CONTRACT")
    observed = []
    marker_details = []
    for marker in markers:
        color = np.asarray(marker["rgb"], float)
        if color.max() > 1:
            color /= 255
        # Normalize brightness to tolerate capture exposure, retaining saturation.
        normalized = rgb / np.maximum(rgb.max(axis=2, keepdims=True), .001)
        target = color / color.max()
        mask = (np.linalg.norm(normalized-target, axis=2) < .16) & (rgb.max(axis=2) > .45)
        candidates = components(mask)
        if not candidates:
            raise CaptureError("MISSING_FIDUCIAL:"+marker["id"])
        if len(candidates) != 1:
            raise CaptureError("AMBIGUOUS_FIDUCIAL:"+marker["id"])
        c = candidates[0]
        lo, hi = c.min(axis=0), c.max(axis=0)
        if lo.min() <= 1 or hi[0] >= rgb.shape[0]-2 or hi[1] >= rgb.shape[1]-2:
            raise CaptureError("CLIPPED_FIDUCIAL:"+marker["id"])
        if len(c) / np.prod(hi-lo+1) < .45:
            raise CaptureError("FRAGMENTED_FIDUCIAL:"+marker["id"])
        center = c.mean(axis=0)[::-1]
        observed.append(center)
        marker_details.append({"id":marker["id"], "centerPixels":center.tolist(), "observedPixels":len(c)})
    h = homography([m["center"][:2] for m in markers], observed)
    # Four centres fit exactly; verify observed marker extent independently.
    extent_errors = []
    for marker, detail in zip(markers, marker_details):
        x, y = marker["center"][:2]
        w, v = marker["halfWidth"], marker["halfHeight"]
        corners = project(h, [[x-w,y-v],[x+w,y-v],[x+w,y+v],[x-w,y+v]])
        area = abs(np.dot(corners[:,0], np.roll(corners[:,1],1))-np.dot(corners[:,1],np.roll(corners[:,0],1)))/2
        error = abs(area-detail["observedPixels"])/max(area, 1)
        extent_errors.append(error)
        if error > .35:
            raise CaptureError("FIDUCIAL_EXTENT_MISMATCH:"+marker["id"])
        # Independent black/white surrounds distinguish markers from scene colors.
        black_w, black_v = marker["blackGuardOuterHalfWidth"], marker["blackGuardOuterHalfHeight"]
        white_w, white_v = marker["whiteGuardOuterHalfWidth"], marker["whiteGuardOuterHalfHeight"]
        outer = project(h,[[x-white_w,y-white_v],[x+white_w,y-white_v],[x+white_w,y+white_v],[x-white_w,y+white_v]])
        if np.any(outer < 1) or np.any(outer[:,0]>=rgb.shape[1]-1) or np.any(outer[:,1]>=rgb.shape[0]-1):
            raise CaptureError("CLIPPED_FIDUCIAL_GUARD:"+marker["id"])
        for ew, ev, expected in (((w+black_w)/2,(v+black_v)/2,0), ((black_w+white_w)/2,(black_v+white_v)/2,1)):
            guard_points = [[x+dx*ew,y+dy*ev] for dx,dy in ((-1,-1),(-1,0),(-1,1),(0,-1),(0,1),(1,-1),(1,0),(1,1))]
            pixels = project(h,guard_points)
            if np.any(pixels < 1) or np.any(pixels[:,0]>=rgb.shape[1]-1) or np.any(pixels[:,1]>=rgb.shape[0]-1):
                raise CaptureError("CLIPPED_FIDUCIAL_GUARD:"+marker["id"])
            pixels = np.rint(pixels).astype(int)
            values = rgb[pixels[:,1],pixels[:,0]]
            if (expected == 0 and np.any(values.mean(axis=1)>.25)) or (expected == 1 and np.any(values.mean(axis=1)<.7)):
                raise CaptureError("FIDUCIAL_GUARD_MISMATCH:"+marker["id"])
    plane = grid["physicalSdkPlane"]
    samples = []
    for row in range(grid["rows"]):
        y = cell_coordinate(plane["cellCenterY"], "row", row)
        samples.append([sample(rgb, h, cell_coordinate(plane["cellCenterX"], "column", col), y,
                               plane["halfCellWidth"], plane["halfCellHeight"]) for col in range(grid["columns"])])
    white = float(np.median([samples[0][i][0].mean() for i in range(8)]))
    black = float(np.median([samples[0][i][0].mean() for i in range(8,16)]))
    if white-black < calibration.get("minimumContrast", .35):
        raise CaptureError("LOW_CONTRAST")
    bits = [[classify(value, values, black, white) for value, values in row] for row in samples]
    if bits[0][:16] != [1]*8+[0]*8 or bits[0][29:] != [1,0,1]:
        raise CaptureError("ANCHOR_PATTERN_MISMATCH")
    def integer(row, count=32):
        if not all(type(b) is int for b in row[:count]):
            raise CaptureError("NON_BINARY_TOKEN")
        return sum(b << i for i,b in enumerate(row[:count]))
    page = integer(bits[0][16:19],3)
    profile = integer(bits[0][19:21],2)
    revision = integer(bits[0][21:29],8)
    if page >= grid["headerPages"]:
        raise CaptureError("PAGE_OUT_OF_RANGE")
    rows, headers, tokens = [], {}, {}
    failures = []
    for declaration in grid["rowMap"]:
        index, kind = declaration["row"], declaration["kind"]
        row = bits[index]
        entry = {"row":index,"kind":kind}
        if kind == "individual-predicates":
            entry["predicates"] = {name:row[int(col)] for col,name in declaration["columns"].items()}
            for name,value in entry["predicates"].items():
                if value not in (("unused",) if name.startswith("blue unused") else ("pass","fail")):
                    raise CaptureError("AMBIGUOUS_PREDICATE")
            if any(v == "fail" for v in entry["predicates"].values()):
                failures.append("RENDERED_PREDICATE_FAIL")
        elif index != 0:
            if all(v == "unavailable" for v in row):
                entry["value"] = None
            else:
                count = 8 if kind.endswith("-u8") else 32
                entry["value"] = integer(row,count)
                if count == 8 and row[8:] != [0]*24:
                    raise CaptureError("U8_PADDING_MISMATCH")
            if "word" in declaration:
                word = declaration["word"]
                if isinstance(word,str):
                    match = re.fullmatch(r"page\*8\+(\d+)",word)
                    if not match:
                        raise CaptureError("UNSUPPORTED_HEADER_INDEX")
                    word = page*grid["headerWordsPerPage"]+int(match[1])
                if str(word) in headers and headers[str(word)] != entry["value"]:
                    raise CaptureError("DUPLICATE_HEADER_DISAGREEMENT")
                headers[str(word)] = entry["value"]
            if "endpoint" in declaration:
                tokens.setdefault(declaration["endpoint"],{})[declaration["field"]] = entry["value"]
        rows.append(entry)
    if expected_version is not None:
        if "1" not in headers:
            failures.append("SOURCE_VERSION_NOT_OBSERVED_ON_PAGE")
        elif headers["1"] != expected_version:
            failures.append("SOURCE_VERSION_MISMATCH")
    # Report observed joins without inventing tokens for unavailable endpoints.
    joins = {}
    for endpoint, fields in tokens.items():
        joins[endpoint] = {}
        for field, value in fields.items():
            declared_word = grid.get("tokenHeaderWords",{}).get(field)
            header_word = None if declared_word is None else str(declared_word)
            if header_word is not None:
                expected = headers.get(header_word)
                joins[endpoint][field] = None if value is None or expected is None else value == expected
                if joins[endpoint][field] is False:
                    failures.append("OBSERVED_TOKEN_HEADER_MISMATCH:"+endpoint+":"+field)
    return {"status":"decoded" if not failures else "rejected", "failureCodes":failures,
            "calibration":{"fiducials":marker_details,"homography":h.tolist(),"contrast":white-black,
                           "maximumMarkerAreaRelativeError":max(extent_errors),"method":"observed-fiducial-centres-DLT"},
            "metadata":{"page":page,"profile":profile,"revision":revision},"headers":headers,"tokens":tokens,
            "observedTokenHeaderJoins":joins,"rows":rows}


def decode_capture(path, grid_path, expected_version=None):
    raw = Path(path).read_bytes()
    grid_raw = Path(grid_path).read_bytes()
    grid = json.loads(grid_raw)
    with Image.open(path) as img:
        if img.format != "PNG" or img.width%2:
            raise CaptureError("REQUIRES_EVEN_WIDTH_SIDE_BY_SIDE_PNG")
        rgb = np.asarray(img.convert("RGB"),float)/255
    output = {"schema":"rusty.quest.atlas_diagnostic.capture_decode.v1", "captureSha256":hashlib.sha256(raw).hexdigest(),
              "gridSha256":hashlib.sha256(grid_raw).hexdigest(),"pixelSource":"actual-input-PNG",
              "headsetAcceptance":False,"eyes":{}}
    for name, image in (("left",rgb[:,:rgb.shape[1]//2]),("right",rgb[:,rgb.shape[1]//2:])):
        try:
            output["eyes"][name] = decode_eye(image,grid,expected_version)
        except (CaptureError,KeyError) as error:
            output["eyes"][name] = {"status":"rejected","failureCodes":[str(error)]}
    output["status"] = "decoded" if all(e["status"] == "decoded" for e in output["eyes"].values()) else "rejected"
    return output


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("capture")
    parser.add_argument("--grid",required=True)
    parser.add_argument("--expected-source-version",type=int)
    parser.add_argument("--output",required=True)
    args = parser.parse_args()
    result = decode_capture(args.capture,args.grid,args.expected_source_version)
    Path(args.output).write_text(json.dumps(result,indent=2)+"\n",encoding="utf-8")
    raise SystemExit(0 if result["status"] == "decoded" else 2)
