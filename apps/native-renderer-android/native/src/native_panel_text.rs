//! Android platform-shaped immutable monochrome SDF atlas; no embedded font assets.
#[derive(Debug)]
pub(crate) struct TextAtlas {
    pub(crate) width: u32,
    pub(crate) height: u32,
    pub(crate) pixels: Vec<u8>,
    pub(crate) runs: Vec<TextRun>,
}
#[derive(Debug)]
pub(crate) struct TextRun {
    pub(crate) advance: f32,
    pub(crate) ascent: f32,
    pub(crate) descent: f32,
    pub(crate) glyphs: Vec<TextGlyph>,
}
#[derive(Debug)]
pub(crate) struct TextGlyph {
    pub(crate) bounds: [f32; 4],
    pub(crate) uv: [f32; 4],
}
#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct GlyphInstance {
    pub(crate) rect: [f32; 4],
    pub(crate) uv: [f32; 4],
    pub(crate) color: [f32; 4],
}
impl TextAtlas {
    pub(crate) fn layout_run(
        &self,
        index: usize,
        baseline: [f32; 2],
        em_height: f32,
        color: [f32; 4],
    ) -> Vec<GlyphInstance> {
        if !em_height.is_finite()
            || em_height <= 0.0
            || em_height > 100.0
            || !baseline.iter().all(|x| x.is_finite() && x.abs() <= 10000.0)
            || !color
                .iter()
                .all(|x| x.is_finite() && (0.0..=1.0).contains(x))
        {
            return Vec::new();
        }
        let Some(run) = self.runs.get(index) else {
            return Vec::new();
        };
        run.glyphs
            .iter()
            .map(|g| {
                let [x, y, w, h] = g.bounds;
                GlyphInstance {
                    rect: [
                        baseline[0] + (x + w * 0.5) * em_height,
                        baseline[1] + (y + h * 0.5) * em_height,
                        w * em_height * 0.5,
                        h * em_height * 0.5,
                    ],
                    uv: g.uv,
                    color,
                }
            })
            .collect()
    }
}
struct Wire<'a> {
    data: &'a [u8],
    at: usize,
}
impl Wire<'_> {
    fn word(&mut self) -> Result<u32, String> {
        let end = self.at.checked_add(4).ok_or("wire overflow")?;
        let bytes = self.data.get(self.at..end).ok_or("truncated font wire")?;
        self.at = end;
        Ok(u32::from_le_bytes(bytes.try_into().unwrap()))
    }
    fn float(&mut self) -> Result<f32, String> {
        let x = f32::from_bits(self.word()?);
        if !x.is_finite() || x.abs() > 4096.0 {
            Err("invalid font metric".into())
        } else {
            Ok(x)
        }
    }
    fn four(&mut self) -> Result<[f32; 4], String> {
        Ok([self.float()?, self.float()?, self.float()?, self.float()?])
    }
}
pub(crate) fn parse(data: &[u8]) -> Result<TextAtlas, String> {
    parse_bounded(data, 32, 1024, 1_082_000)
}
/// Separate page namespace; toolbar budgets and wire stay unchanged.
pub(crate) fn parse_page(data: &[u8]) -> Result<TextAtlas, String> {
    parse_bounded(data, 128, 4096, 1_300_000)
}
fn parse_bounded(
    data: &[u8],
    run_limit: u32,
    glyph_limit: u32,
    wire_limit: usize,
) -> Result<TextAtlas, String> {
    if data.len() > wire_limit {
        return Err("font wire budget".into());
    }
    let mut r = Wire { data, at: 0 };
    if r.word()? != 0x3154504e || r.word()? != 1 {
        return Err("font wire version".into());
    }
    let width = r.word()?;
    let height = r.word()?;
    let count = r.word()?;
    let pixels = r.word()?;
    if r.word()? != 64
        || r.word()? != 8
        || width == 0
        || height == 0
        || width > 1024
        || height > 1024
        || count > run_limit
        || pixels != width * height
    {
        return Err("font atlas header".into());
    }
    let mut runs = Vec::with_capacity(count as usize);
    let mut total = 0;
    for _ in 0..count {
        let advance = r.float()?;
        let ascent = r.float()?;
        let descent = r.float()?;
        let n = r.word()?;
        if advance < 0.0
            || ascent > 0.0
            || descent < 0.0
            || n > glyph_limit
            || total + n > glyph_limit
        {
            return Err("font run budget/metrics".into());
        }
        total += n;
        let mut glyphs = Vec::with_capacity(n as usize);
        for _ in 0..n {
            let bounds = r.four()?;
            let uv = r.four()?;
            if bounds[2] <= 0.0
                || bounds[3] <= 0.0
                || bounds[2] > 4.0
                || bounds[3] > 4.0
                || !uv.iter().all(|v| (0.0..=1.0).contains(v))
                || uv[0] >= uv[2]
                || uv[1] >= uv[3]
            {
                return Err("invalid font glyph".into());
            }
            // Tile bounds and UV span describe the same padded texels.
            if ((uv[2] - uv[0]) * width as f32 - bounds[2] * 64.0).abs() > 0.02
                || ((uv[3] - uv[1]) * height as f32 - bounds[3] * 64.0).abs() > 0.02
            {
                return Err("font tile geometry mismatch".into());
            }
            glyphs.push(TextGlyph { bounds, uv });
        }
        runs.push(TextRun {
            advance,
            ascent,
            descent,
            glyphs,
        });
    }
    if data.len().checked_sub(r.at) != Some(pixels as usize) {
        return Err("font pixel length/trailing bytes".into());
    }
    Ok(TextAtlas {
        width,
        height,
        pixels: data[r.at..].to_vec(),
        runs,
    })
}
#[cfg(target_os = "android")]
pub(crate) fn prepare(
    app: &android_activity::AndroidApp,
    labels: &[&str],
) -> Result<TextAtlas, String> {
    use jni::{
        jni_sig, jni_str,
        objects::{JByteArray, JClass, JClassLoader, JObject, JValue},
        JavaVM,
    };
    if labels.len() > 32 || labels.iter().any(|s| s.encode_utf16().count() > 256) {
        return Err("font label budget".into());
    }
    let vm = unsafe { JavaVM::from_raw(app.vm_as_ptr().cast()) };
    let raw = app.activity_as_ptr() as jni::sys::jobject;
    let bytes = vm
        .attach_current_thread(|env| -> jni::errors::Result<Vec<u8>> {
            let activity = unsafe { env.as_cast_raw::<JObject>(&raw)? };
            let loader = env
                .call_method(
                    &activity,
                    jni_str!("getClassLoader"),
                    jni_sig!("()Ljava/lang/ClassLoader;"),
                    &[],
                )?
                .l()?;
            let loader: JClassLoader = env.cast_local::<JClassLoader>(loader)?;
            let name =
                env.new_string("io.github.mesmerprism.rustyquest.native_renderer.NativePanelFont")?;
            let class = JClass::for_name_with_loader(env, name, true, loader)?;
            let strings = env.find_class(jni_str!("java/lang/String"))?;
            let array = env.new_object_array(labels.len() as i32, strings, JObject::null())?;
            for (i, label) in labels.iter().enumerate() {
                let s = env.new_string(*label)?;
                array.set_element(env, i, JObject::from(s))?;
            }
            let array = JObject::from(array);
            let result = env
                .call_static_method(
                    class,
                    jni_str!("prepare"),
                    jni_sig!("([Ljava/lang/String;)[B"),
                    &[JValue::Object(&array)],
                )?
                .l()?;
            let bytes: JByteArray = env.cast_local::<JByteArray>(result)?;
            // Bound before JNI copies a Java allocation into native memory.
            if bytes.len(env)? > 1_082_000 {
                return Err(jni::errors::Error::JniCall(
                    jni::errors::JniError::InvalidArguments,
                ));
            }
            env.convert_byte_array(&bytes)
        })
        .map_err(|e| format!("platform font preparation: {e}"))?;
    parse(&bytes)
}
#[cfg(test)]
mod tests {
    use super::*;
    fn fixture() -> Vec<u8> {
        let mut b = Vec::new();
        for x in [0x3154504e, 1, 64, 64, 1, 4096, 64, 8] {
            b.extend(u32::to_le_bytes(x));
        }
        for x in [1.2f32, -0.9, 0.3] {
            b.extend(x.to_le_bytes());
        }
        b.extend(1u32.to_le_bytes());
        for x in [-0.1f32, -0.8, 0.5, 1.0, 0.0, 0.0, 0.5, 1.0] {
            b.extend(x.to_le_bytes());
        }
        b.extend(vec![0; 4096]);
        b
    }
    #[test]
    fn strict_wire_and_layout() {
        let a = parse(&fixture()).unwrap();
        assert_eq!((a.width, a.height, a.pixels.len()), (64, 64, 4096));
        assert_eq!(std::mem::size_of::<GlyphInstance>(), 48);
        let g = a.layout_run(0, [2., 3.], 0.2, [1.; 4]);
        assert!((g[0].rect[0] - 2.03).abs() < 1e-6);
        assert!((g[0].rect[1] - 2.94).abs() < 1e-6);
        assert_eq!(g[0].rect[2], 0.05);
        assert_eq!(a.runs[0].advance, 1.2);
        assert_eq!(a.runs[0].ascent, -0.9);
        assert_eq!(a.runs[0].descent, 0.3);
    }
    #[test]
    fn page_limits_do_not_expand_the_toolbar_namespace() {
        let mut b = Vec::new();
        for v in [0x3154504eu32, 1, 64, 64, 128, 4096, 64, 8] {
            b.extend(v.to_le_bytes());
        }
        for _ in 0..128 {
            for v in [0f32, -0.9, 0.3] {
                b.extend(v.to_le_bytes());
            }
            b.extend(0u32.to_le_bytes());
        }
        b.extend(vec![0; 4096]);
        assert!(parse(&b).is_err());
        assert_eq!(parse_page(&b).unwrap().runs.len(), 128);
        b[16..20].copy_from_slice(&129u32.to_le_bytes());
        assert!(parse_page(&b).is_err());
        let fixture = fixture();
        let glyph = &fixture[48..80];
        let mut b = fixture[..44].to_vec();
        b.extend(4096u32.to_le_bytes());
        for _ in 0..4096 {
            b.extend(glyph);
        }
        b.extend(vec![0; 4096]);
        assert!(parse(&b).is_err());
        assert_eq!(parse_page(&b).unwrap().runs[0].glyphs.len(), 4096);
    }
    #[test]
    fn rejects_every_truncation_and_trailing() {
        let b = fixture();
        for n in 0..b.len() {
            assert!(parse(&b[..n]).is_err(), "{n}");
        }
        let mut b = b;
        b.push(0);
        assert!(parse(&b).is_err());
    }
    #[test]
    fn rejects_nonfinite_and_geometry() {
        for offset in [32, 36, 40, 48, 52, 56, 60, 64, 68, 72, 76] {
            let mut b = fixture();
            b[offset..offset + 4].copy_from_slice(&f32::NAN.to_le_bytes());
            assert!(parse(&b).is_err());
        }
        let mut b = fixture();
        b[72..76].copy_from_slice(&0.6f32.to_le_bytes());
        assert!(parse(&b).is_err());
    }
    #[test]
    fn rejects_header_budgets() {
        for (at, value) in [
            (8, 0),
            (12, 1025),
            (16, 33),
            (20, 10),
            (24, 32),
            (28, 4),
            (44, 1025),
        ] {
            let mut b = fixture();
            b[at..at + 4].copy_from_slice(&u32::to_le_bytes(value));
            assert!(parse(&b).is_err());
        }
    }
    #[test]
    fn layout_rejects_bad_caller_inputs() {
        let a = parse(&fixture()).unwrap();
        assert!(a.layout_run(1, [0.; 2], 1., [1.; 4]).is_empty());
        for h in [0., -1., f32::NAN, f32::INFINITY] {
            assert!(a.layout_run(0, [0.; 2], h, [1.; 4]).is_empty());
        }
        assert!(a.layout_run(0, [f32::NAN, 0.], 1., [1.; 4]).is_empty());
        assert!(a.layout_run(0, [0.; 2], 1., [2.; 4]).is_empty());
    }
    #[test]
    fn shaped_positions_are_preserved_not_rekerned() {
        let mut a = parse(&fixture()).unwrap();
        for positions in [[0., 0.54], [0., 0.41], [0., 0.0], [0.7, 0.0], [0., 0.83]] {
            a.runs[0].glyphs = positions
                .iter()
                .map(|x| TextGlyph {
                    bounds: [*x, -0.8, 0.5, 1.],
                    uv: [0., 0., 0.5, 1.],
                })
                .collect();
            let out = a.layout_run(0, [0.; 2], 1., [1.; 4]);
            assert!((out[1].rect[0] - out[0].rect[0] - (positions[1] - positions[0])).abs() < 1e-6);
        }
    }
}
