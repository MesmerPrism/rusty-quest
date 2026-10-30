use crate::scissor_abi::{Pixels, Project};

#[repr(C)]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Guarded {
    pub before: u64,
    pub output: Pixels,
    pub after: u64,
}
impl Guarded {
    pub fn new() -> Self {
        Self {
            before: 0xaabbccdd,
            output: Pixels::default(),
            after: 0x11223344,
        }
    }
    fn intact(&self) -> bool {
        self.before == 0xaabbccdd && self.after == 0x11223344
    }
}
pub fn probe(function: Project, inset: bool) -> Result<(), &'static str> {
    let mut value = Guarded::new();
    let status = unsafe { function(1920, 1080, 0.1, 0.2, 0.55, 0.5, &mut value.output, 16) };
    let expected = if inset {
        Pixels {
            x: 193,
            y: 217,
            width: 1054,
            height: 538,
        }
    } else {
        Pixels {
            x: 192,
            y: 216,
            width: 1056,
            height: 540,
        }
    };
    if status != 0 || !value.intact() || value.output != expected {
        return Err("scratch projection");
    }
    for (w, h, x, len) in [
        (0, 1, 0., 16),
        (1, 0, 0., 16),
        (1, 1, f32::NAN, 16),
        (1, 1, 0., 15),
        (1, 1, 0., 17),
    ] {
        let saved = value;
        if unsafe { function(w, h, x, 0., 1., 1., &mut value.output, len) } == 0 || value != saved {
            return Err("scratch refusal writes");
        }
    }
    if unsafe { function(1, 1, 0., 0., 1., 1., std::ptr::null_mut(), 16) } == 0 {
        return Err("scratch null output");
    }
    Ok(())
}
pub fn dispatch(
    function: Project,
    completed: &mut u64,
    output: &mut Guarded,
    width: u32,
    height: u32,
    rect: [f32; 4],
) -> Result<(), &'static str> {
    let next = completed.checked_add(1).ok_or("counter overflow")?;
    let saved = *output;
    let status = unsafe {
        function(
            width,
            height,
            rect[0],
            rect[1],
            rect[2],
            rect[3],
            &mut output.output,
            16,
        )
    };
    let v = output.output;
    let within = v.x >= 0
        && v.y >= 0
        && v.width > 0
        && v.height > 0
        && (v.x as u32)
            .checked_add(v.width)
            .is_some_and(|end| end <= width)
        && (v.y as u32)
            .checked_add(v.height)
            .is_some_and(|end| end <= height);
    if status != 0 || !output.intact() || !within {
        *output = saved;
        return Err("projection rejected");
    }
    *completed = next;
    Ok(())
}
