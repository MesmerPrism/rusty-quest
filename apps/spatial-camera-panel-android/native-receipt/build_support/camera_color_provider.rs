//! Narrow compiled-provider ABI check; source policy remains with the caller.

pub fn declares_camera_color_sampler(bytes: &[u8]) -> bool {
    if bytes.len() < 20 || bytes.len() % 4 != 0 {
        return false;
    }
    let words: Vec<_> = bytes
        .chunks_exact(4)
        .map(|chunk| u32::from_le_bytes(chunk.try_into().unwrap()))
        .collect();
    if words[0] != 0x0723_0203 {
        return false;
    }
    let mut bindings = Vec::new();
    let mut sets = Vec::new();
    let mut cursor = 5;
    while cursor < words.len() {
        let count = (words[cursor] >> 16) as usize;
        if count == 0 || count > words.len() - cursor {
            return false;
        }
        if words[cursor] & 0xffff == 71 && count == 4 {
            match (words[cursor + 2], words[cursor + 3]) {
                (33, 10) => bindings.push(words[cursor + 1]),
                (34, 1) => sets.push(words[cursor + 1]),
                _ => {}
            }
        }
        cursor += count;
    }
    bindings.iter().any(|binding| sets.contains(binding))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn binary(words: &[u32]) -> Vec<u8> {
        words.iter().flat_map(|word| word.to_le_bytes()).collect()
    }

    #[test]
    fn admission_requires_set_and_binding_on_the_same_compiled_resource() {
        // SPIR-V header followed by OpDecorate ID7 Binding10 and DescriptorSet1.
        let words = [
            0x07230203,
            0x00010300,
            0,
            8,
            0,
            (4 << 16) | 71,
            7,
            33,
            10,
            (4 << 16) | 71,
            7,
            34,
            1,
        ];
        assert!(declares_camera_color_sampler(&binary(&words)));
        let mut unrelated = words;
        unrelated[10] = 6;
        assert!(!declares_camera_color_sampler(&binary(&unrelated)));
        assert!(!declares_camera_color_sampler(&binary(&words[..9])));
        assert!(!declares_camera_color_sampler(&[0; 20]));
        let mut zero_instruction = words;
        zero_instruction[5] = 71;
        assert!(!declares_camera_color_sampler(&binary(&zero_instruction)));
    }
}
