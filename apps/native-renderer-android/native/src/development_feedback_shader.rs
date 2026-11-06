//! Closed SPIR-V interface for the public developer scene. No descriptor resources.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Stage {
    Vertex,
    Fragment,
}
use std::collections::{BTreeMap, BTreeSet};
#[derive(Clone, Debug)]
enum Type {
    Float(u32),
    Int(u32, u32),
    Vector(u32, u32),
    Struct(Vec<u32>),
    Pointer(u32, u32),
}
pub fn validate(bytes: &[u8], stage: Stage) -> Result<(), String> {
    if bytes.len() < 20 || bytes.len() > 256 * 1024 || bytes.len() % 4 != 0 {
        return Err("SPIR-V byte limit/alignment".into());
    }
    let words: Vec<u32> = bytes
        .chunks_exact(4)
        .map(|b| u32::from_le_bytes([b[0], b[1], b[2], b[3]]))
        .collect();
    if words[0] != 0x07230203
        || !(0x00010000..=0x00010500).contains(&words[1])
        || words[3] == 0
        || words[3] > 65536
        || words[4] != 0
    {
        return Err("SPIR-V header".into());
    }
    let mut types = BTreeMap::new();
    let mut variables = Vec::new();
    let mut locations = BTreeMap::new();
    let mut builtins = BTreeMap::new();
    let mut member_builtins = BTreeMap::new();
    let mut offsets = BTreeMap::new();
    let mut flat = BTreeSet::new();
    let mut blocks = BTreeSet::new();
    let mut entries = 0;
    let mut memory_model = false;
    let mut shader_capability = false;
    let mut cursor = 5;
    while cursor < words.len() {
        let count = (words[cursor] >> 16) as usize;
        let opcode = words[cursor] & 0xffff;
        if count == 0 || cursor + count > words.len() {
            return Err("SPIR-V instruction bounds".into());
        }
        let a = &words[cursor + 1..cursor + count];
        match opcode {
            17 => {
                if a != [1] || shader_capability {
                    return Err("SPIR-V requires Shader-only capability".into());
                }
                shader_capability = true;
            }
            14 => {
                if a != [0, 1] || memory_model {
                    return Err("SPIR-V logical GLSL memory model required".into());
                }
                memory_model = true;
            }
            10 => return Err("SPIR-V extensions are outside the closed scene ABI".into()),
            15 => {
                entries += 1;
                if a.len() < 3 || a[0] != if stage == Stage::Vertex { 0 } else { 4 } {
                    return Err("SPIR-V entry stage".into());
                }
                let name: Vec<u8> = a[2..]
                    .iter()
                    .flat_map(|w| w.to_le_bytes())
                    .take_while(|b| *b != 0)
                    .collect();
                if name != b"main" {
                    return Err("SPIR-V main entry required".into());
                }
            }
            16 => {
                if a.len() < 2 || stage != Stage::Fragment || a[1] != 7 {
                    return Err("SPIR-V execution mode is outside scene ABI".into());
                }
            }
            21 if a.len() == 3 => {
                types.insert(a[0], Type::Int(a[1], a[2]));
            }
            22 if a.len() == 2 => {
                types.insert(a[0], Type::Float(a[1]));
            }
            23 if a.len() == 3 => {
                types.insert(a[0], Type::Vector(a[1], a[2]));
            }
            30 if !a.is_empty() => {
                types.insert(a[0], Type::Struct(a[1..].to_vec()));
            }
            32 if a.len() == 3 => {
                types.insert(a[0], Type::Pointer(a[1], a[2]));
            }
            59 if a.len() >= 3 => {
                variables.push((a[0], a[1], a[2]));
            }
            71 if a.len() >= 2 => match a[1] {
                33 | 34 => return Err("SPIR-V descriptors are forbidden".into()),
                30 if a.len() == 3 => {
                    if locations.insert(a[0], a[2]).is_some() {
                        return Err("duplicate SPIR-V location".into());
                    }
                }
                11 if a.len() == 3 => {
                    builtins.insert(a[0], a[2]);
                }
                14 => {
                    flat.insert(a[0]);
                }
                2 => {
                    blocks.insert(a[0]);
                }
                _ => {}
            },
            72 if a.len() >= 3 => match a[2] {
                35 if a.len() == 4 => {
                    if offsets.insert((a[0], a[1]), a[3]).is_some() {
                        return Err("duplicate SPIR-V member offset".into());
                    }
                }
                11 if a.len() == 4 => {
                    member_builtins.insert((a[0], a[1]), a[3]);
                }
                _ => {}
            },
            // Resource aliasing and alternate entry/execution models are outside this closed ABI.
            331 | 332 => return Err("SPIR-V alternate execution/decoration mode".into()),
            _ => {}
        }
        cursor += count;
    }
    if entries != 1 || !memory_model || !shader_capability {
        return Err("SPIR-V entry/memory/capability closure".into());
    }
    let mut actual = BTreeMap::new();
    let mut push_count = 0;
    for (pointer, id, storage) in variables {
        let Some(Type::Pointer(pointer_storage, pointee)) = types.get(&pointer) else {
            return Err("SPIR-V variable pointer".into());
        };
        if *pointer_storage != storage {
            return Err("SPIR-V variable storage mismatch".into());
        }
        if [0, 2, 12].contains(&storage) {
            return Err("SPIR-V external resources forbidden".into());
        }
        if storage == 9 {
            push_count += 1;
            let Some(Type::Struct(members)) = types.get(pointee) else {
                return Err("SPIR-V push struct".into());
            };
            if stage != Stage::Vertex || members.len() != 7 || !blocks.contains(pointee) {
                return Err("SPIR-V exact vertex push block required".into());
            }
            for (index, member) in members.iter().enumerate() {
                if signature(*member, &types) != "v4f32"
                    || offsets.get(&(*pointee, index as u32)) != Some(&(index as u32 * 16))
                {
                    return Err("SPIR-V exact 112-byte push layout required".into());
                }
            }
        }
        if storage == 1 || storage == 3 {
            if let Some(location) = locations.get(&id) {
                let kind = signature(*pointee, &types);
                if *location == 2 && !flat.contains(&id) {
                    return Err("SPIR-V particle kind must be flat".into());
                }
                if actual.insert((storage, *location), kind).is_some() {
                    return Err("SPIR-V duplicate stage interface".into());
                }
            } else if let Some(builtin) = builtins.get(&id) {
                if stage != Stage::Vertex || storage != 1 || ![42, 43].contains(builtin) {
                    return Err("SPIR-V unsupported builtin input/output".into());
                }
            } else if let Some(Type::Struct(members)) = types.get(pointee) {
                if stage != Stage::Vertex
                    || storage != 3
                    || members.is_empty()
                    || !blocks.contains(pointee)
                {
                    return Err("SPIR-V builtin block".into());
                }
                for index in 0..members.len() {
                    if !member_builtins
                        .get(&(*pointee, index as u32))
                        .is_some_and(|v| [0, 1, 3, 4].contains(v))
                    {
                        return Err("SPIR-V unknown builtin block member".into());
                    }
                }
            } else {
                return Err("SPIR-V unlocated stage interface".into());
            }
        }
    }
    let expected: BTreeMap<_, _> = match stage {
        Stage::Vertex => [((3, 0), "v2f32"), ((3, 1), "v3f32"), ((3, 2), "i32")]
            .into_iter()
            .collect(),
        Stage::Fragment => [
            ((1, 0), "v2f32"),
            ((1, 1), "v3f32"),
            ((1, 2), "i32"),
            ((3, 0), "v4f32"),
        ]
        .into_iter()
        .collect(),
    };
    if actual != expected || push_count != if stage == Stage::Vertex { 1 } else { 0 } {
        return Err("SPIR-V scene stage interface mismatch".into());
    }
    Ok(())
}
fn signature<'a>(id: u32, types: &BTreeMap<u32, Type>) -> &'a str {
    match types.get(&id) {
        Some(Type::Int(32, 1)) => "i32",
        Some(Type::Float(32)) => "f32",
        Some(Type::Vector(component, count))
            if matches!(types.get(component), Some(Type::Float(32))) =>
        {
            match count {
                2 => "v2f32",
                3 => "v3f32",
                4 => "v4f32",
                _ => "unsupported",
            }
        }
        _ => "unsupported",
    }
}
