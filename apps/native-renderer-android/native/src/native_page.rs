//! Immutable bounded native presentation of the retained Android page owner.
//! Parsing errors are fixed reason codes: field strings never reach diagnostics.
use crate::{
    native_panel_text::{GlyphInstance, TextAtlas},
    native_panel_ui_geometry::Primitive,
};
use serde_json::{Map, Value};
use sha2::{Digest, Sha256};
use std::rc::Rc;
pub(crate) const MAX_PACKET: usize = 2 * 1024 * 1024;
const MAX_JSON: usize = 512 * 1024;
const MAX_FONT: usize = 1_300_000;
pub(crate) const UNBOUNDED_CLIP: [f32; 4] = [-10000., -10000., 10000., 10000.];
pub(crate) fn selected() -> bool {
    option_env!("RUSTY_QUEST_NATIVE_RENDERER_MESH_UI_NATIVE_PAGE") == Some("true")
}
#[derive(Clone, Debug, PartialEq)]
pub(crate) struct Batch {
    pub kind: BatchKind,
    pub first: u32,
    pub count: u32,
    pub clip: [f32; 4],
}
#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) enum BatchKind {
    Shapes,
    Text,
}
#[derive(Debug)]
pub(crate) struct Page {
    pub generation: u64,
    pub revision: u64,
    pub content_epoch: u64,
    pub atlas_epoch: u64,
    pub raster_epoch: u64,
    pub page_id: String,
    pub shapes: Vec<Primitive>,
    pub glyphs: Vec<GlyphInstance>,
    pub batches: Vec<Batch>,
    pub atlas: Rc<TextAtlas>,
}
#[derive(Clone)]
struct Stamp {
    generation: u64,
    revision: u64,
    content: u64,
    raster: u64,
    page: String,
    digest: [u8; 32],
}
#[derive(Default)]
pub(crate) struct PageCache {
    pub page: Option<Page>,
    atlas: Option<(u64, Rc<TextAtlas>, [u8; 32])>,
    last: Option<Stamp>,
}
fn object<'a>(v: &'a Value, keys: &[&str]) -> Result<&'a Map<String, Value>, &'static str> {
    let o = v.as_object().ok_or("object-required")?;
    if o.len() != keys.len() || keys.iter().any(|k| !o.contains_key(*k)) {
        return Err("object-fields");
    }
    Ok(o)
}
fn positive(v: &Value) -> Result<u64, &'static str> {
    v.as_u64()
        .filter(|x| *x > 0 && *x <= i64::MAX as u64)
        .ok_or("identity")
}
fn number(v: &Value) -> Result<f32, &'static str> {
    let d = v.as_f64().ok_or("number")?;
    let f = d as f32;
    if !f.is_finite() || f.abs() > 100000. {
        return Err("finite-number-budget");
    }
    Ok(f)
}
fn four(v: &Value) -> Result<[f32; 4], &'static str> {
    let a = v.as_array().filter(|a| a.len() == 4).ok_or("vec4")?;
    Ok([
        number(&a[0])?,
        number(&a[1])?,
        number(&a[2])?,
        number(&a[3])?,
    ])
}
fn color(v: &Value) -> Result<[f32; 4], &'static str> {
    let c = four(v)?;
    if c.iter().any(|x| !(0.0..=1.0).contains(x)) {
        return Err("color");
    }
    Ok(c)
}
fn array(v: &Value, max: usize) -> Result<&Vec<Value>, &'static str> {
    v.as_array()
        .filter(|a| a.len() <= max)
        .ok_or("array-budget")
}
fn index(v: &Value) -> Result<usize, &'static str> {
    v.as_u64()
        .and_then(|v| usize::try_from(v).ok())
        .ok_or("index")
}
fn point(x: f32, y: f32) -> [f32; 2] {
    [(x - 512.) / 768., (y - 384.) / 768.]
}
impl PageCache {
    pub(crate) fn seen(&self) -> (u64, u64) {
        (
            self.page.as_ref().map_or(0, |p| p.revision),
            self.atlas.as_ref().map_or(0, |a| a.0),
        )
    }
    /// Retain only immutable font bytes; a retired/unavailable owner must immediately stop drawing.
    pub(crate) fn invalidate(&mut self) {
        self.page = None;
    }
    pub(crate) fn adopt(&mut self, packet: &[u8], generation: u64) -> Result<(), &'static str> {
        if packet.len() < 16 || packet.len() > MAX_PACKET {
            return Err("packet-budget");
        }
        let word = |at| u32::from_le_bytes(packet[at..at + 4].try_into().unwrap());
        if word(0) != 0x3147504e || word(4) != 1 {
            return Err("packet-version");
        }
        let j = word(8) as usize;
        let f = word(12) as usize;
        if j == 0
            || j > MAX_JSON
            || f > MAX_FONT
            || 16usize.checked_add(j).and_then(|n| n.checked_add(f)) != Some(packet.len())
        {
            return Err("packet-length");
        }
        let value: Value = serde_json::from_slice(&packet[16..16 + j]).map_err(|_| "json")?;
        let scene_digest: [u8; 32] = Sha256::digest(&packet[16..16 + j]).into();
        let o = object(
            &value,
            &[
                "generation",
                "revision",
                "content_epoch",
                "atlas_epoch",
                "raster_epoch",
                "page_id",
                "logical_width",
                "logical_height",
                "complete",
                "interactive",
                "shapes",
                "text_runs",
                "batches",
            ],
        )?;
        let g = positive(&o["generation"])?;
        let revision = positive(&o["revision"])?;
        let content = positive(&o["content_epoch"])?;
        let epoch = positive(&o["atlas_epoch"])?;
        let raster = positive(&o["raster_epoch"])?;
        if g != generation
            || o["logical_width"].as_u64() != Some(1024)
            || o["logical_height"].as_u64() != Some(768)
            || o["complete"].as_bool() != Some(true)
            || o["interactive"].as_bool() != Some(true)
        {
            return Err("owner-or-presentation");
        }
        let page = o["page_id"]
            .as_str()
            .filter(|p| !p.is_empty() && p.len() <= 128 && !p.chars().any(char::is_control))
            .ok_or("page-identity")?;
        if let Some(old) = &self.last {
            if g != old.generation
                || revision < old.revision
                || (revision == old.revision && (self.page.is_some() || scene_digest != old.digest))
                || content < old.content
                || raster < old.raster
                || (page != old.page && content <= old.content)
            {
                return Err("stale-page");
            }
        }
        if self.atlas.as_ref().is_some_and(|old| epoch < old.0) {
            return Err("stale-atlas");
        }
        let new_font = if f > 0 {
            let bytes = &packet[16 + j..];
            let hash: [u8; 32] = Sha256::digest(bytes).into();
            if let Some(old) = self.atlas.as_ref().filter(|a| a.0 == epoch) {
                if hash != old.2 {
                    return Err("atlas-epoch-reused");
                }
                Some((epoch, old.1.clone(), hash))
            } else {
                let atlas = crate::native_panel_text::parse_page(bytes).map_err(|_| "page-font")?;
                Some((epoch, Rc::new(atlas), hash))
            }
        } else {
            None
        };
        let atlas = new_font
            .as_ref()
            .or(self.atlas.as_ref())
            .filter(|a| a.0 == epoch)
            .ok_or("missing-atlas")?
            .1
            .clone();
        let shape_values = array(&o["shapes"], 1024)?;
        let text_values = array(&o["text_runs"], 128)?;
        let batch_values = array(&o["batches"], 2048)?;
        let mut shapes = Vec::with_capacity(shape_values.len());
        for v in shape_values {
            let s = object(v, &["rect", "color", "shape"])?;
            let r = four(&s["rect"])?;
            let sh = four(&s["shape"])?;
            if r[2] <= 0.
                || r[3] <= 0.
                || sh[0] < 0.
                || sh[0] > r[2].min(r[3])
                || sh[2] != 0.
                || sh[3] != 0.
            {
                return Err("shape-geometry");
            }
            let c = point(r[0], r[1]);
            shapes.push(Primitive {
                rect: [c[0], c[1], r[2] / 768., r[3] / 768.],
                color: color(&s["color"])?,
                shape: [sh[0] / 768., sh[1], 0., 0.],
            });
        }
        let mut glyphs = Vec::new();
        let mut starts = Vec::with_capacity(text_values.len() + 1);
        starts.push(0usize);
        for v in text_values {
            let t = object(v, &["font_run", "x", "y", "size", "color"])?;
            let run = index(&t["font_run"])?;
            let run_data = atlas.runs.get(run).ok_or("font-run-index")?;
            let x = number(&t["x"])?;
            let y = number(&t["y"])?;
            let size = number(&t["size"])?;
            if size <= 0.
                || size > 768.
                || glyphs
                    .len()
                    .checked_add(run_data.glyphs.len())
                    .is_none_or(|n| n > 4096)
            {
                return Err("glyph-budget");
            }
            glyphs.extend(atlas.layout_run(run, point(x, y), size / 768., color(&t["color"])?));
            starts.push(glyphs.len());
        }
        let mut batches = Vec::with_capacity(batch_values.len());
        let mut covered_shapes = vec![false; shapes.len()];
        let mut covered_text = vec![false; text_values.len()];
        let mut draws = 0usize;
        for v in batch_values {
            let b = object(v, &["kind", "first", "count", "clip"])?;
            let first = index(&b["first"])?;
            let count = index(&b["count"])?;
            let end = first.checked_add(count).ok_or("batch-overflow")?;
            let clip = four(&b["clip"])?;
            if count == 0
                || clip[0] < 0.
                || clip[1] < 0.
                || clip[2] > 1024.
                || clip[3] > 768.
                || clip[0] >= clip[2]
                || clip[1] >= clip[3]
            {
                return Err("batch-clip-or-empty");
            }
            let (kind, start, len) = match b["kind"].as_str() {
                Some("shapes") if end <= shapes.len() => (BatchKind::Shapes, first, count),
                Some("text") if end <= text_values.len() => {
                    (BatchKind::Text, starts[first], starts[end] - starts[first])
                }
                _ => return Err("batch-range"),
            };
            match kind {
                BatchKind::Shapes => covered_shapes[first..end].fill(true),
                BatchKind::Text => covered_text[first..end].fill(true),
            }
            draws = draws
                .checked_add(len)
                .filter(|n| *n <= 8192)
                .ok_or("batch-work-budget")?;
            let a = point(clip[0], clip[1]);
            let z = point(clip[2], clip[3]);
            batches.push(Batch {
                kind,
                first: start as u32,
                count: len as u32,
                clip: [a[0], a[1], z[0], z[1]],
            });
        }
        if shapes.is_empty()
            || batches.is_empty()
            || draws == 0
            || covered_shapes
                .iter()
                .chain(covered_text.iter())
                .any(|covered| !covered)
        {
            return Err("incomplete-draw-ranges");
        }
        self.last = Some(Stamp {
            generation: g,
            revision,
            content,
            raster,
            page: page.to_owned(),
            digest: scene_digest,
        });
        if let Some(font) = new_font {
            self.atlas = Some(font);
        }
        self.page = Some(Page {
            generation: g,
            revision,
            content_epoch: content,
            atlas_epoch: epoch,
            raster_epoch: raster,
            page_id: page.to_owned(),
            shapes,
            glyphs,
            batches,
            atlas,
        });
        Ok(())
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::{json, Value};
    fn atlas() -> Vec<u8> {
        let mut b = Vec::new();
        for v in [0x3154504eu32, 1, 64, 64, 1, 4096, 64, 8] {
            b.extend(v.to_le_bytes());
        }
        for v in [1.2f32, -0.9, 0.3] {
            b.extend(v.to_le_bytes());
        }
        b.extend(1u32.to_le_bytes());
        for v in [-0.1f32, -0.8, 0.5, 1., 0., 0., 0.5, 1.] {
            b.extend(v.to_le_bytes());
        }
        b.extend(vec![0; 4096]);
        b
    }
    fn scene() -> Value {
        json!({"generation":7,"revision":1,"content_epoch":1,"atlas_epoch":1,"raster_epoch":1,"page_id":"native-page","logical_width":1024,"logical_height":768,"complete":true,"interactive":true,"shapes":[{"rect":[512.,384.,128.,24.],"color":[1.,1.,1.,1.],"shape":[4.,0.,0.,0.]}],"text_runs":[{"font_run":0,"x":100.,"y":200.,"size":32.,"color":[1.,1.,1.,1.]}],"batches":[{"kind":"shapes","first":0,"count":1,"clip":[0.,0.,1024.,768.]},{"kind":"text","first":0,"count":1,"clip":[10.,20.,1000.,700.]},{"kind":"shapes","first":0,"count":1,"clip":[0.,0.,1024.,768.]}]})
    }
    fn packet(v: &Value, font: &[u8]) -> Vec<u8> {
        let j = serde_json::to_vec(v).unwrap();
        let mut b = Vec::new();
        for x in [0x3147504eu32, 1, j.len() as u32, font.len() as u32] {
            b.extend(x.to_le_bytes());
        }
        b.extend(j);
        b.extend(font);
        b
    }
    /// Run explicitly with the neutral fixture emitted by the registered Java Wire.pack test.
    #[test]
    #[ignore = "requires NATIVE_PAGE_INTEROP_PACKET from the Java focused runner"]
    fn java_wire_pack_interoperability() {
        let path =
            std::env::var_os("NATIVE_PAGE_INTEROP_PACKET").expect("Java fixture path required");
        let bytes = std::fs::read(path).unwrap();
        let json_bytes = u32::from_le_bytes(bytes[8..12].try_into().unwrap()) as usize;
        let value: Value = serde_json::from_slice(&bytes[16..16 + json_bytes]).unwrap();
        let generation = value["generation"].as_u64().unwrap();
        let mut cache = PageCache::default();
        cache.adopt(&bytes, generation).unwrap();
        let page = cache.page.unwrap();
        assert_eq!(page.shapes.len(), 1);
        assert_eq!(page.glyphs.len(), 1);
        assert_eq!(
            page.batches.iter().map(|b| b.kind).collect::<Vec<_>>(),
            [BatchKind::Shapes, BatchKind::Text]
        );
    }
    #[test]
    fn complete_page_keeps_paint_order_clip_and_logical_geometry() {
        let mut c = PageCache::default();
        c.adopt(&packet(&scene(), &atlas()), 7).unwrap();
        let p = c.page.unwrap();
        assert_eq!(p.shapes[0].rect, [0., 0., 128. / 768., 24. / 768.]);
        assert_eq!(
            p.batches.iter().map(|b| b.kind).collect::<Vec<_>>(),
            [BatchKind::Shapes, BatchKind::Text, BatchKind::Shapes]
        );
        assert_eq!(
            p.batches[1].clip,
            [
                (10. - 512.) / 768.,
                (20. - 384.) / 768.,
                (1000. - 512.) / 768.,
                (700. - 384.) / 768.
            ]
        );
        assert_eq!(p.glyphs.len(), 1);
    }
    #[test]
    fn malformed_and_trailing_packets_fail_without_replacing_current() {
        let good = packet(&scene(), &atlas());
        let mut c = PageCache::default();
        c.adopt(&good, 7).unwrap();
        for n in [0, 1, 15, 16, good.len() - 1] {
            assert!(c.adopt(&good[..n], 7).is_err());
        }
        let mut extra = good.clone();
        extra.push(0);
        assert!(c.adopt(&extra, 7).is_err());
        assert_eq!(c.page.as_ref().unwrap().revision, 1);
    }
    #[test]
    fn epochs_ranges_and_missing_atlas_fail_closed() {
        for (key, value) in [
            ("generation", json!(8)),
            ("complete", json!(false)),
            ("interactive", json!(false)),
            ("logical_width", json!(2048)),
            ("revision", json!(0)),
        ] {
            let mut s = scene();
            s[key] = value;
            assert!(PageCache::default()
                .adopt(&packet(&s, &atlas()), 7)
                .is_err());
        }
        assert!(PageCache::default()
            .adopt(&packet(&scene(), &[]), 7)
            .is_err());
        let mut s = scene();
        s["batches"][0]["count"] = json!(u64::MAX);
        assert!(PageCache::default()
            .adopt(&packet(&s, &atlas()), 7)
            .is_err());
        s = scene();
        s["batches"][0]["clip"] = json!([0, 0, 0, 1]);
        assert!(PageCache::default()
            .adopt(&packet(&s, &atlas()), 7)
            .is_err());
    }
    #[test]
    fn invalidation_keeps_only_identical_revision_and_immutable_font() {
        let mut c = PageCache::default();
        let original = scene();
        c.adopt(&packet(&original, &atlas()), 7).unwrap();
        c.invalidate();
        let mut changed = original.clone();
        changed["shapes"][0]["rect"][0] = json!(513.);
        assert!(c.adopt(&packet(&changed, &[]), 7).is_err());
        c.adopt(&packet(&original, &[]), 7).unwrap();
        let mut next = original;
        next["revision"] = json!(2);
        let mut font = atlas();
        *font.last_mut().unwrap() = 1;
        assert!(c.adopt(&packet(&next, &font), 7).is_err());
        next["atlas_epoch"] = json!(2);
        c.adopt(&packet(&next, &font), 7).unwrap();
        next["revision"] = json!(3);
        next["atlas_epoch"] = json!(1);
        assert!(c.adopt(&packet(&next, &atlas()), 7).is_err());
    }
    #[test]
    fn complete_packet_cannot_omit_supplied_shape_or_text_ranges() {
        for kind in ["shapes", "text"] {
            let mut s = scene();
            s["batches"] = json!([{"kind":kind,"first":0,"count":1,"clip":[0,0,1024,768]}]);
            assert!(
                PageCache::default()
                    .adopt(&packet(&s, &atlas()), 7)
                    .is_err(),
                "unreferenced page content cannot replace original pixels"
            );
        }
    }
    #[test]
    fn geometry_color_unknown_fields_and_packet_overflow_are_rejected() {
        for (field, value) in [
            ("rect", json!([0, 0, -1, 1])),
            ("shape", json!([30, 0, 0, 0])),
            ("color", json!([1, 1, 1, 2])),
        ] {
            let mut s = scene();
            s["shapes"][0][field] = value;
            assert!(PageCache::default()
                .adopt(&packet(&s, &atlas()), 7)
                .is_err());
        }
        let mut s = scene();
        s["raw_field_text"] = json!("forbidden");
        assert!(PageCache::default()
            .adopt(&packet(&s, &atlas()), 7)
            .is_err());
        let mut b = packet(&scene(), &atlas());
        b[8..12].copy_from_slice(&u32::MAX.to_le_bytes());
        assert!(PageCache::default().adopt(&b, 7).is_err());
        b = packet(&scene(), &atlas());
        b[4..8].copy_from_slice(&2u32.to_le_bytes());
        assert!(PageCache::default().adopt(&b, 7).is_err());
        let mut s = scene();
        s["batches"][0]["first"] = json!(1);
        assert!(PageCache::default()
            .adopt(&packet(&s, &atlas()), 7)
            .is_err());
    }
    #[test]
    fn geometry_only_revision_reuses_atlas_and_rejects_retrograde_content() {
        let mut c = PageCache::default();
        c.adopt(&packet(&scene(), &atlas()), 7).unwrap();
        let old = c.page.as_ref().unwrap().atlas.clone();
        let mut s = scene();
        s["revision"] = json!(2);
        c.adopt(&packet(&s, &[]), 7).unwrap();
        assert!(Rc::ptr_eq(&old, &c.page.as_ref().unwrap().atlas));
        assert!(c.adopt(&packet(&scene(), &[]), 7).is_err());
        s["revision"] = json!(3);
        s["content_epoch"] = json!(0);
        assert!(c.adopt(&packet(&s, &[]), 7).is_err());
        s["content_epoch"] = json!(1);
        s["atlas_epoch"] = json!(2);
        assert!(c.adopt(&packet(&s, &[]), 7).is_err());
    }
}
