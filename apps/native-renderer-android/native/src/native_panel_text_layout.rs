//! Layout of cached platform-shaped runs; no per-frame platform font calls.
use crate::{
    native_panel_text::{GlyphInstance, TextAtlas},
    native_panel_toolbar::ToolbarKey,
};
pub(crate) const LABELS: &[&str] = &[
    "FORMS",
    "CENTER",
    "SIZE",
    "DETAIL",
    "ON",
    "OFF",
    "AUTO",
    "1X",
    "1.5X",
    "2X",
    "1024x768",
    "1536x1152",
    "2048x1536",
    "SCALE",
    "TO WORLD",
    "TO WRIST",
    "FPS",
    "0",
    "1",
    "2",
    "3",
    "4",
    "5",
    "6",
    "7",
    "8",
    "9",
    "%",
    "NO HAND",
    "INFO",
    "STATUS",
];
pub(crate) fn glyphs(atlas: &TextAtlas, key: ToolbarKey) -> Vec<GlyphInstance> {
    let mut out = Vec::with_capacity(160);
    let mut draw = |index: usize, x: f32, center_y: f32, height: f32, centered: bool| {
        let Some(run) = atlas.runs.get(index) else {
            return 0.0;
        };
        let width = run.advance * height;
        let baseline = center_y - (run.ascent + run.descent) * height * 0.5;
        out.extend(atlas.layout_run(
            index,
            [if centered { x - width * 0.5 } else { x }, baseline],
            height,
            [0.93, 0.96, 1.0, 1.0],
        ));
        width
    };
    for i in 0..4 {
        draw(
            i,
            crate::native_panel_ui_geometry::BUTTONS[i][0],
            0.59,
            0.044,
            true,
        );
    }
    let mut x = -0.57;
    for index in [
        0,
        if key.forms { 4 } else { 5 },
        3,
        match key.detail {
            crate::native_hybrid_panel::DetailMode::Auto => 6,
            crate::native_hybrid_panel::DetailMode::Base => 7,
            crate::native_hybrid_panel::DetailMode::Medium => 8,
            crate::native_hybrid_panel::DetailMode::High => 9,
        },
        if key.width == 2048 {
            12
        } else if key.width == 1536 {
            11
        } else {
            10
        },
    ] {
        x += draw(index, x, 0.68, 0.031, false) + 0.014;
    }
    draw(29, 0.20, 0.765, 0.038, true);
    draw(13, -0.55, 0.732, 0.027, false);
    let percent = (key.physical_width / 0.96 * 100.0)
        .round()
        .clamp(0.0, 999.0) as u16;
    let mut x = -0.35;
    for digit in percent.to_string().bytes() {
        x += draw(17 + (digit - b'0') as usize, x, 0.732, 0.027, false);
    }
    draw(27, x, 0.732, 0.027, false);
    let mut x = -0.09 + draw(16, -0.09, 0.732, 0.027, false) + 0.014;
    for digit in key.fps.to_string().bytes() {
        x += draw(17 + (digit - b'0') as usize, x, 0.732, 0.027, false);
    }
    draw(
        if key.wrist {
            14
        } else if !key.wrist_available {
            28
        } else {
            15
        },
        0.45,
        0.765,
        0.038,
        true,
    );
    out
}
