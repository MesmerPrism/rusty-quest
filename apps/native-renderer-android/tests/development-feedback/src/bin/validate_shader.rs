use native_development_feedback_tests::development_feedback_shader::{validate, Stage};
fn main() {
    let args: Vec<_> = std::env::args().collect();
    assert!(
        (3..=4).contains(&args.len()),
        "validate_shader vertex|fragment <SPIR-V> [--exercise-refusals]"
    );
    let stage = match args[1].as_str() {
        "vertex" => Stage::Vertex,
        "fragment" => Stage::Fragment,
        _ => panic!("stage"),
    };
    let bytes = std::fs::read(&args[2]).expect("shader bytes");
    validate(&bytes, stage).expect("compatible developer scene shader");
    println!("compatible {:?} shader", stage);
    if args.len() == 4 {
        assert_eq!(args[3], "--exercise-refusals");
        assert!(validate(
            &bytes,
            if stage == Stage::Vertex {
                Stage::Fragment
            } else {
                Stage::Vertex
            }
        )
        .is_err());
        let mut bad = bytes.clone();
        bad[0] = 0;
        assert!(validate(&bad, stage).is_err());
        let mut words: Vec<_> = bytes
            .chunks_exact(4)
            .map(|b| u32::from_le_bytes(b.try_into().unwrap()))
            .collect();
        let mut cursor = 5;
        let mut location = None;
        let mut offset = None;
        while cursor < words.len() {
            let count = (words[cursor] >> 16) as usize;
            let opcode = words[cursor] & 65535;
            if opcode == 71 && count == 4 && words[cursor + 2] == 30 {
                location = Some(cursor + 3);
            }
            if opcode == 72 && count == 5 && words[cursor + 3] == 35 {
                offset = Some(cursor + 4);
            }
            cursor += count;
        }
        let index = location.expect("actual stage location");
        words[index] = 99;
        let encode = |words: &[u32]| {
            words
                .iter()
                .flat_map(|w| w.to_le_bytes())
                .collect::<Vec<_>>()
        };
        assert!(validate(&encode(&words), stage).is_err());
        if stage == Stage::Vertex {
            let mut words: Vec<_> = bytes
                .chunks_exact(4)
                .map(|b| u32::from_le_bytes(b.try_into().unwrap()))
                .collect();
            words[offset.expect("actual push block offset")] += 4;
            assert!(validate(&encode(&words), stage).is_err());
        }
        let mut descriptors = bytes.clone();
        descriptors.extend_from_slice(&encode(&[(4 << 16) | 71, 1, 33, 0]));
        assert!(validate(&descriptors, stage).is_err());
        assert!(validate(&bytes[..bytes.len() - 1], stage).is_err());
        println!(
            "actual-artifact stage/header/location/push/descriptor/truncation refusals passed"
        );
    }
}
