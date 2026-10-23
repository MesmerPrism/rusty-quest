//! One SDK app-lifetime session owner, sharing canonical reducer/writer source.
#![allow(dead_code)]
#![recursion_limit = "256"]
#[path = "../../../native-renderer-android/native/src/breath_guidance.rs"]
mod breath_guidance;
#[path = "../../../native-renderer-android/native/src/experiment_session.rs"]
mod experiment_session;
#[path = "../../../native-renderer-android/native/src/experiment_session_runtime.rs"]
mod experiment_session_runtime;
#[path = "../../../native-renderer-android/native/src/session_recording_clock.rs"]
mod session_recording_clock;
#[path = "../../../native-renderer-android/native/src/session_recording_contract.rs"]
mod session_recording_contract;
#[path = "../../../native-renderer-android/native/src/session_recording_inventory.rs"]
mod session_recording_inventory;
#[path = "../../../native-renderer-android/native/src/session_recording_recovery.rs"]
mod session_recording_recovery;
#[path = "../../../native-renderer-android/native/src/session_recording_writer.rs"]
mod session_recording_writer;
#[path = "../../../native-renderer-android/native/src/projection_target_input.rs"]
mod projection_target_input;
#[path = "../../../native-renderer-android/native/src/native_controller_breath_state.rs"]
mod native_controller_breath_state;
#[path = "../../../native-renderer-android/native/src/openxr_controller_breath_adapter.rs"]
mod openxr_controller_breath_adapter;
#[path = "../../../native-renderer-android/native/src/bounded_breath_phase_integrator.rs"]
mod bounded_breath_phase_integrator;
#[path = "../../../native-renderer-android/native/src/breath_composition_driver.rs"]
mod breath_composition_driver;
#[path = "../../../native-renderer-android/native/src/native_renderer_properties.rs"]
mod native_renderer_properties;
#[path = "../../../native-renderer-android/native/src/native_renderer_property_values.rs"]
mod native_renderer_property_values;
#[path = "../../../native-renderer-android/native/src/breath_calibration_controller_action.rs"]
mod breath_calibration_controller_action;
mod experiment_session_platform;
mod services;
mod scope;
mod proof;
mod controller;
mod controller_button;
#[cfg(target_os = "android")]
mod android;
