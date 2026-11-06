"""Synthetic image evidence only; these tests do not assert headset acceptance."""
import json
from pathlib import Path
import tempfile
import unittest

import numpy as np
from PIL import Image, ImageDraw

from decode_atlas_capture import decode_capture, project, cell_coordinate


GRID = json.loads(Path(__file__).with_name("atlas-diagnostic-grid.json").read_text())


def fixture(matrix, missing=None, duplicate=False, contrast=False, clip=False, guard=False, version=4, unavailable=False):
    image = Image.new("RGB", (800,1000), (24,24,24))
    draw = ImageDraw.Draw(image)
    def square(x,y,w,h,color):
        vertices = project(matrix,[[x-w,y-h],[x+w,y-h],[x+w,y+h],[x-w,y+h]])
        draw.polygon([tuple(v) for v in vertices], fill=color)
    headers = {i:((0xa55a1234+i*13579)&0xffffffff) for i in range(48)}
    headers.update({1:version,4:0x8fedabcd,5:0xfa765432,35:1,17:0x12345678,47:0x8fedabcd})
    anchors = [1]*8+[0]*8+[0]*3+[1,0]+[int((73>>i)&1) for i in range(8)]+[1,0,1]
    plane = GRID["physicalSdkPlane"]
    token_values = {}
    for declaration in GRID["rowMap"]:
        row,kind = declaration["row"],declaration["kind"]
        if row == 0:
            colors = [(245,245,245) if b else (2,2,2) for b in anchors]
        elif kind == "individual-predicates":
            colors = [(0,230,0)]*31+[(0,0,230)]
        else:
            if "word" in declaration:
                word = declaration["word"]
                if isinstance(word,str):
                    word = int(word.rsplit("+",1)[1])
                value = headers[word]
            elif "endpoint" in declaration:
                value = (0xfa765432 if declaration["field"] == "frame" else 0x8fedabcd)
                token_values.setdefault(declaration["endpoint"],{})[declaration["field"]] = value
            elif kind.endswith("-u8"):
                value = (row*7)%256
            else:
                value = 1024
            colors = [(245,245,245) if (value>>col)&1 else (2,2,2) for col in range(32)]
            if unavailable and declaration.get("endpoint") == "handFirst":
                colors = [(230,0,230)]*32
        if contrast:
            colors = [(135,135,135) if c[0]>200 else (110,110,110) for c in colors]
        for col,color in enumerate(colors):
            square(cell_coordinate(plane["cellCenterX"],"column",col),cell_coordinate(plane["cellCenterY"],"row",row),plane["halfCellWidth"],plane["halfCellHeight"],color)
    for marker in GRID["calibration"]["fiducials"]:
        if marker["id"] == missing:
            continue
        x,y = marker["center"]
        for field,color in (("whiteGuardOuter",(245,245,245)),("blackGuardOuter",(2,2,2)),("",tuple(round(v*255) for v in marker["rgb"]))):
            w = marker[field+"HalfWidth"] if field else marker["halfWidth"]
            h = marker[field+"HalfHeight"] if field else marker["halfHeight"]
            square(x,y,w,h,color)
        if guard and marker["id"] == "TL":
            square(x,y,.07,.07,(0,255,255))
    if duplicate:
        draw.rectangle((15,450,40,475),fill=(0,255,255))
    if clip:
        # Retain the visible portion of a color component at the image border.
        draw.rectangle((0,420,20,450),fill=(0,255,255))
    return image,headers,token_values


class DecoderTest(unittest.TestCase):
    def run_images(self,left,right,expected=4,grid_contract=None):
        with tempfile.TemporaryDirectory() as directory:
            capture=Path(directory)/"stereo.png"
            combined=Image.new("RGB",(1600,1000))
            combined.paste(left,(0,0));combined.paste(right,(800,0));combined.save(capture)
            grid=Path(directory)/"grid.json";grid.write_text(json.dumps(grid_contract or GRID))
            return decode_capture(capture,grid,expected)
    def matrices(self):
        return [np.array([[460,8,370],[12,-480,1180],[.06,.015,1.]]),
                np.array([[480,-6,430],[-7,-465,1170],[-.045,.025,1.]])]
    def test_projective_stereo_full_u32_and_source_steps(self):
        a,b=self.matrices()
        left,headers,tokens=fixture(a);right,_,_=fixture(b)
        result=self.run_images(left,right)
        self.assertEqual(result["status"],"decoded",result)
        self.assertEqual(len(result["captureSha256"]),64)
        self.assertFalse(result["headsetAcceptance"])
        for eye in result["eyes"].values():
            self.assertEqual(eye["tokens"],tokens)
            self.assertEqual(eye["headers"]["17"],headers[17])
            self.assertEqual(eye["metadata"],{"page":0,"profile":1,"revision":73})
            self.assertEqual(len(eye["rows"]),GRID["rows"])
    def test_missing_and_ambiguous_markers(self):
        a,b=self.matrices();right,_,_=fixture(b)
        for options,code in (({"missing":"TR"},"MISSING_FIDUCIAL"),({"duplicate":True},"AMBIGUOUS_FIDUCIAL")):
            left,_,_=fixture(a,**options);result=self.run_images(left,right)
            self.assertEqual(result["status"],"rejected")
            self.assertTrue(result["eyes"]["left"]["failureCodes"][0].startswith(code),result)
    def test_low_contrast_and_guard_damage(self):
        a,b=self.matrices();right,_,_=fixture(b)
        for options,code in (({"contrast":True},"LOW_CONTRAST"),({"guard":True},"FIDUCIAL")):
            left,_,_=fixture(a,**options);result=self.run_images(left,right)
            self.assertTrue(result["eyes"]["left"]["failureCodes"][0].startswith(code),result)
    def test_clipped_marker(self):
        a,b=self.matrices();right,_,_=fixture(b)
        # Shift whole plane so TL and BL centers reach the left edge.
        a=a.copy();a[0]=a[0]-100*a[2]
        left,_,_=fixture(a);result=self.run_images(left,right)
        self.assertEqual(result["eyes"]["left"]["status"],"rejected")
        self.assertTrue(any("CLIPPED" in c or "MISSING" in c for c in result["eyes"]["left"]["failureCodes"]),result)
    def test_wrong_version_rejected_from_observed_bits(self):
        a,b=self.matrices();left,_,_=fixture(a,version=3);right,_,_=fixture(b)
        result=self.run_images(left,right)
        self.assertIn("SOURCE_VERSION_MISMATCH",result["eyes"]["left"]["failureCodes"])
    def test_declared_grid_out_of_bounds(self):
        a,b=self.matrices();left,_,_=fixture(a);right,_,_=fixture(b)
        changed=json.loads(json.dumps(GRID))
        changed["physicalSdkPlane"]["cellCenterX"]="-.465+column*0.090"
        result=self.run_images(left,right,grid_contract=changed)
        self.assertIn(result["eyes"]["left"]["failureCodes"][0],("GRID_OUT_OF_BOUNDS","CLIPPED_CELL"))
    def test_unavailable_endpoint_is_null_not_zero(self):
        a,b=self.matrices();left,_,_=fixture(a,unavailable=True);right,_,_=fixture(b)
        result=self.run_images(left,right)
        self.assertEqual(result["status"],"decoded",result)
        self.assertEqual(result["eyes"]["left"]["tokens"]["handFirst"],{"frame":None,"generation":None})


if __name__ == "__main__":
    unittest.main()
