//! Raw XR_FB_passthrough style call for the native Meta passthrough layer.

use std::{ffi::c_void, ptr};

use openxr as xr;

use crate::native_renderer_passthrough_style_options::{
    NativePassthroughStyleMode, NativePassthroughStyleSettings, PASSTHROUGH_STYLE_COLOR_MAP_ENTRIES,
};

pub(crate) fn apply_passthrough_layer_style(
    session: &xr::Session<xr::Vulkan>,
    layer: &xr::PassthroughLayerFB,
    settings: NativePassthroughStyleSettings,
) -> Result<&'static str, String> {
    if !settings.mode.requests_style_call() {
        return Ok("disabled");
    }

    let extension = session
        .instance()
        .exts()
        .fb_passthrough
        .as_ref()
        .ok_or_else(|| "XR_FB_passthrough function table is not loaded".to_owned())?;

    match settings.mode {
        NativePassthroughStyleMode::Disabled => Ok("disabled"),
        NativePassthroughStyleMode::EdgeAndOpacity => {
            let style = passthrough_style(settings, ptr::null());
            unsafe {
                ensure_xr_success(
                    (extension.passthrough_layer_set_style)(layer.as_raw(), &style),
                    "xrPassthroughLayerSetStyleFB",
                )?;
            }
            Ok("PassthroughStyleFB")
        }
        NativePassthroughStyleMode::BrightnessContrastSaturation => {
            let bcs = xr::sys::PassthroughBrightnessContrastSaturationFB {
                ty: xr::sys::PassthroughBrightnessContrastSaturationFB::TYPE,
                next: ptr::null(),
                brightness: settings.brightness,
                contrast: settings.contrast,
                saturation: settings.saturation,
            };
            let style = passthrough_style(settings, &bcs as *const _ as *const c_void);
            unsafe {
                ensure_xr_success(
                    (extension.passthrough_layer_set_style)(layer.as_raw(), &style),
                    "xrPassthroughLayerSetStyleFB",
                )?;
            }
            Ok("PassthroughBrightnessContrastSaturationFB")
        }
        NativePassthroughStyleMode::MonoToRgba => {
            let texture_color_map = xr_color_map(settings);
            let color_map = xr::sys::PassthroughColorMapMonoToRgbaFB {
                ty: xr::sys::PassthroughColorMapMonoToRgbaFB::TYPE,
                next: ptr::null(),
                texture_color_map,
            };
            let style = passthrough_style(settings, &color_map as *const _ as *const c_void);
            unsafe {
                ensure_xr_success(
                    (extension.passthrough_layer_set_style)(layer.as_raw(), &style),
                    "xrPassthroughLayerSetStyleFB",
                )?;
            }
            Ok("PassthroughColorMapMonoToRgbaFB")
        }
    }
}

fn passthrough_style(
    settings: NativePassthroughStyleSettings,
    next: *const c_void,
) -> xr::sys::PassthroughStyleFB {
    xr::sys::PassthroughStyleFB {
        ty: xr::sys::PassthroughStyleFB::TYPE,
        next,
        texture_opacity_factor: settings.opacity,
        edge_color: xr_color(settings.edge_color_rgba),
    }
}

fn xr_color_map(
    settings: NativePassthroughStyleSettings,
) -> [xr::sys::Color4f; PASSTHROUGH_STYLE_COLOR_MAP_ENTRIES] {
    let mut xr_map = [xr::sys::Color4f::default(); PASSTHROUGH_STYLE_COLOR_MAP_ENTRIES];
    let source_map = settings.mono_to_rgba_color_map();
    for (target, source) in xr_map.iter_mut().zip(source_map.iter()) {
        *target = xr_color(*source);
    }
    xr_map
}

fn xr_color(color: [f32; 4]) -> xr::sys::Color4f {
    xr::sys::Color4f {
        r: color[0].clamp(0.0, 1.0),
        g: color[1].clamp(0.0, 1.0),
        b: color[2].clamp(0.0, 1.0),
        a: color[3].clamp(0.0, 1.0),
    }
}

fn ensure_xr_success(result: xr::sys::Result, operation: &str) -> Result<(), String> {
    if result.into_raw() < xr::sys::Result::SUCCESS.into_raw() {
        Err(format!("{operation} failed: {result:?}"))
    } else {
        Ok(())
    }
}
