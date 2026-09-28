use super::*;
unsafe fn render_source_set_common_graph(
    window: *mut vk::ANativeWindow,
    requested_width: u32,
    requested_height: u32,
    max_frames: u32,
    cancellation: Arc<AtomicBool>,
    ready_tx: std::sync::mpsc::SyncSender<Result<(), String>>,
) -> Result<(), String> {
    if cancellation.load(Ordering::Acquire) {
        return Err("source-set-session-cancelled-before-wsi".to_string());
    }
    let mut owned = PeerCommonGraphResources::new(CameraHwbWsiParts::create(
        window,
        requested_width,
        requested_height,
    )?);
    let run_result = (|| -> Result<(), String> {
        if cancellation.load(Ordering::Acquire) {
            return Err("source-set-session-cancelled-after-wsi".to_string());
        }

        let deadline = Instant::now() + Duration::from_millis(CAMERA_HWB_PROBE_WAIT_FRAME_MS);
        let mut first_frame = loop {
            if cancellation.load(Ordering::Acquire) {return Err("source-set-cancelled-before-seed".into());}
            if let Some(seed)=crate::spatial_stereo_source_import::source_carrier_seed()? {break seed;}
            if Instant::now()>=deadline{return Err("source-set-real-seed-timeout".into());}
            thread::yield_now();
        };
        let wsi = owned.wsi.as_ref().expect("peer WSI initialized");
        let (source_import_properties, source_format_props) =
            query_ahb_vulkan_import_properties(&wsi.ahb_device, &first_frame.hardware_buffer)?;
        owned.normalizer = Some(PackedSbsNormalizer::create(
            &wsi.device,
            &wsi.memory_properties,
            first_frame.descriptor.width,
            first_frame.descriptor.height,
            source_import_properties.format_key,
            &source_format_props,
        )?);
        let normalizer = owned.normalizer.as_ref().expect("normalizer initialized");
        owned.sampled_packed_image = Some(import_ahb_sampled_image(
            &wsi.device,
            &wsi.memory_properties,
            &first_frame.hardware_buffer,
            AhbVulkanSampledImageCreateInfo {
                width: first_frame.descriptor.width,
                height: first_frame.descriptor.height,
                format_key: source_import_properties.format_key,
                allocation_size: source_import_properties.allocation_size,
                memory_type_bits: source_import_properties.memory_type_bits,
                sampler_ycbcr_conversion: normalizer.source_sampler_ycbcr_conversion(),
                debug_label: "retained-source-seed",
            },
        )?);
        owned
            .normalizer
            .as_mut()
            .expect("normalizer initialized")
            .update_source(
                &wsi.device,
                owned
                    .sampled_packed_image
                    .as_ref()
                    .expect("packed image initialized")
                    .image_view,
            );

        let normalized_format_properties = wsi
            .instance
            .get_physical_device_format_properties(wsi.physical_device, NORMALIZED_EYE_FORMAT);
        if !normalized_format_properties
            .optimal_tiling_features
            .contains(
                vk::FormatFeatureFlags::COLOR_ATTACHMENT | vk::FormatFeatureFlags::SAMPLED_IMAGE,
            )
        {
            return Err("peer-normalized-eye-format-unsupported".to_string());
        }
        let mut normalized_ahb_format_props =
            vk::AndroidHardwareBufferFormatPropertiesANDROID::default();
        normalized_ahb_format_props.format = NORMALIZED_EYE_FORMAT;
        normalized_ahb_format_props.format_features =
            normalized_format_properties.optimal_tiling_features;
        let normalized_format_key = AhbVulkanFormatKey {
            format: NORMALIZED_EYE_FORMAT,
            external_format: 0,
        };
        owned.pending_camera_resources = Some(create_camera_hwb_probe_resources(
            &wsi.device,
            wsi.render_pass,
            normalized_format_key,
            &normalized_ahb_format_props,
            CameraHwbProbeMode::RawColorProjection,
        )?);
        owned.pending_public_guide_targets = Some(allocate_spatial_public_guide_targets(
            &wsi.device,
            &wsi.memory_properties,
            owned
                .pending_camera_resources
                .as_ref()
                .expect("camera resources initialized")
                .descriptor_set_layout,
            wsi.render_pass,
        )?);
        let normalized_views = owned
            .normalizer
            .as_ref()
            .expect("normalizer initialized")
            .image_views();
        let descriptor_set = allocate_camera_hwb_probe_descriptor_set(
            &wsi.device,
            owned
                .pending_camera_resources
                .as_ref()
                .expect("camera resources initialized"),
            normalized_views[0],
            Some(normalized_views[1]),
            CameraHwbProbeMode::RawColorProjection,
        )?;
        owned.processing_graph = Some(CameraProcessingGraph {
            camera_resources: owned
                .pending_camera_resources
                .take()
                .expect("camera resources initialized"),
            descriptor_set,
            public_guide_targets: Some(
                owned
                    .pending_public_guide_targets
                    .take()
                    .expect("public guide targets initialized"),
            ),
            camera_replay_capture: None,
            sampler_mode: "normalized-rgba-full-eye",
            format_key: normalized_format_key,
            format_props: normalized_ahb_format_props,
        });
        owned.projection_readback = Some(ProjectionReadback::new(
            wsi.surface_format,
            wsi.composite_alpha,
            wsi.extent,
            wsi.capabilities.supported_usage_flags,
            wsi.memory_properties,
        ));
        let surface_generation = NEXT_SDK_SURFACE_GENERATION.fetch_add(1, Ordering::AcqRel);
        crate::spatial_stereo_qualification::carrier_live();
        #[cfg(rq_environment_depth_spatial_sdk_api_layer)]
        crate::spatial_stereo_qualification::carrier_device(wsi.foreign_queue_ownership_enabled,wsi.sdk_binding.enabled_capability_mask as u64,wsi.sdk_binding.session_generation);
        #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
        crate::spatial_stereo_qualification::carrier_device(wsi.foreign_queue_ownership_enabled,0,0);
        owned.video_renderer=Some(SpatialVideoProjectionRenderer::new(&wsi.instance,&wsi.device,
            wsi.memory_properties,wsi.render_pass,true));


        let PeerCommonGraphResources {
            wsi: Some(wsi),
            normalizer: Some(normalizer),
            sampled_packed_image: Some(sampled_packed_image),
            processing_graph: Some(processing_graph),
            projection_readback: Some(projection_readback),
            video_renderer: Some(video_renderer),
            ..
        } = &mut owned
        else {
            return Err("peer-common-graph-resource-owner-incomplete".to_string());
        };
        let device = &wsi.device;
        let ahb_device = &wsi.ahb_device;
        let memory_properties = wsi.memory_properties;
        let render_pass = wsi.render_pass;
        let framebuffers = &wsi.framebuffers;
        let extent = wsi.extent;
        let composite_alpha = wsi.composite_alpha;
        let images = &wsi.images;
        let command_buffers = &wsi.command_buffers;
        let frame_fence = wsi.frame_fence;
        let image_available = wsi.image_available;
        let render_finished = wsi.render_finished;
        let swapchain = wsi.swapchain;
        let swapchain_loader = &wsi.swapchain_loader;
        let gpu_timestamps = &mut wsi.gpu_timestamps;
        #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
        let queue = wsi.queue;
        #[cfg(rq_environment_depth_spatial_sdk_api_layer)]
        let sdk_binding = wsi.sdk_binding;
        let mut transition_packed_source = true;
        let mut frames_presented = 0_u32;
        let mut guard_band = CameraReprojectionGuardBandController::default();
        let render_started = Instant::now();

        let mut stereo_source_imports = if crate::own_stereo_capture_runtime::capture_route_selected() {
            if !source_bank_build::SOURCE_BANK_SHADER_COMPILED || !wsi.foreign_queue_ownership_enabled {
                return Err("selected-source-bank-provider-or-foreign-owner-unavailable".into());
            }
            let targets=processing_graph.public_guide_targets.as_mut().ok_or("source-bank-guide-owner-unavailable")?;
            targets.activate_stereo_banks(device,&wsi.instance,wsi.physical_device,&memory_properties,
                processing_graph.camera_resources.descriptor_set_layout,2,
                include_bytes!(concat!(env!("OUT_DIR"),"/spatial_source_bank.frag.spv")),
                include_bytes!(concat!(env!("OUT_DIR"),"/spatial_source_bank.vert.spv")))?;
            Some(crate::spatial_stereo_source_import::StereoSourceImports::create(device,
                &processing_graph.camera_resources,Some(wsi.queue_family_index))?)
        } else {None};
        first_frame.release_seed_content();
        let _ = ready_tx.send(Ok(()));
        while max_frames == 0 || frames_presented < max_frames {
            if cancellation.load(Ordering::Acquire) {
                break;
            }
            device.wait_for_fences(&[frame_fence],true,u64::MAX)
                .map_err(|e|format!("source-set-wait-fence-{e:?}"))?;
            video_renderer.retire_completed_frame_handles();
            if let Some(targets)=processing_graph.public_guide_targets.as_mut() {
                targets.retire_stereo_banks_after_fence(&device)?;
            }
            if let Some(imports)=stereo_source_imports.as_mut() {
                imports.retire_after_fence(device)?;
                crate::spatial_stereo_source_import::synchronize_peer_source()?;
                imports.refresh(device,&memory_properties,ahb_device,&processing_graph.camera_resources,
                    CameraHwbProbeMode::RawColorProjection,1_000_000_000)?;
            }
            device
                .reset_fences(&[frame_fence])
                .map_err(|error| format!("peer-reset-fence-{error:?}"))?;
            let image_index = match swapchain_loader.acquire_next_image(
                swapchain,
                u64::MAX,
                image_available,
                vk::Fence::null(),
            ) {
                Ok((index, _)) => index,
                Err(vk::Result::ERROR_OUT_OF_DATE_KHR) => break,
                Err(error) => return Err(format!("peer-acquire-next-image-{error:?}")),
            };
            let command_buffer = command_buffers[image_index as usize];
            let latency_settings = current_camera_latency_settings();
            let camera_reprojection = current_camera_latency_stereo_reprojection(None, None);
            let zone_settings = current_projection_zone_compositor_settings();
            projection_readback.observe_control(zone_settings);
            let projection_guard_band = guard_band.update_for_projection_buffer(
                latency_settings,
                zone_settings.buffer_geometry_mode,
                zone_settings.buffer_static_width_uv,
                zone_settings.buffer_minimum_width_uv,
                zone_settings.buffer_maximum_width_uv,
                zone_settings.buffer_maximum_speed_meters_per_second,
                camera_reprojection,
                boottime_now_ns(),
            );
            let normalized_images = normalizer.images();
            let video_settings = spatial_video_projection_settings();
            let latest_video_frame=if video_settings.active(){latest_spatial_video_projection_frame()}else{None};
            let stereo_inputs=if let Some(imports)=stereo_source_imports.as_mut() {
                let (words,revision)=crate::spatial_public_multistack_runtime::read_control_policy();
                let policy=crate::stereo_bank_transport_v1::StereoBankPolicyUniformV1 {
                    region_origins:[words[0],words[1],words[2],words[3]],
                    guide_origins:[words[4],words[5],0,0],source_state:[0,0,1,0] };
                // Conservative neutral demand covers every selected guide stage;
                // private effect demand may later reduce this prefix explicitly.
                let mut prefixes=[0usize;2];
                for &origin in &words[..4]{prefixes[origin as usize]=6;}
                for &origin in &words[4..]{if origin<2{prefixes[origin as usize]=6;}}
                Some(imports.recording_inputs(policy,revision,prefixes,frame_fence,u64::from(frames_presented)+1,surface_generation)?)
            } else {None};
            let record_result = record_camera_hwb_probe_command_buffer(
                &device,
                command_buffer,
                render_pass,
                framebuffers[image_index as usize],
                extent,
                &processing_graph.camera_resources,
                stereo_inputs.as_ref().and_then(|inputs|inputs.sources.iter().flatten().find(|source|inputs.demanded_prefixes[source.key.origin]>0))
                    .map_or(processing_graph.descriptor_set,|source|source.descriptor_set),
                normalized_images[0],
                Some(normalized_images[1]),
                false,
                false,
                if stereo_inputs.is_some(){None}else{Some((normalizer, sampled_packed_image, transition_packed_source))},
                processing_graph.public_guide_targets.as_mut(),
                stereo_inputs,
                render_started.elapsed().as_secs_f32(),
                Some(video_renderer),
                latest_video_frame.as_ref(),
                &video_settings,
                gpu_timestamps,
                image_index as usize,
                u64::from(frames_presented) + 1,
                camera_reprojection,
                projection_guard_band,
                latency_settings,
                processing_graph.camera_replay_capture.as_mut(),
                boottime_now_ns().max(0) as u64,
                CameraReplayFrameMetadata {
                    left_camera_id: "source-set-left".to_string(),
                    right_camera_id: "source-set-right".to_string(),
                    left_frame_index: first_frame.identity.pair_sequence,
                    right_frame_index: first_frame.identity.pair_sequence,
                    left_timestamp_ns: first_frame.identity.packed_pts_ns,
                    right_timestamp_ns: first_frame.identity.packed_pts_ns,
                    pair_delta_ns: first_frame.identity.left_timestamp_ns.abs_diff(first_frame.identity.right_timestamp_ns),
                },
                composite_alpha,
                images[image_index as usize],
                surface_generation,
                projection_readback,
            )?;
            transition_packed_source = false;

            #[cfg(not(rq_environment_depth_spatial_sdk_api_layer))]
            {
                let waits = [image_available];
                let stages = [vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT];
                let signals = [render_finished];
                let buffers = [command_buffer];
                let submits = [vk::SubmitInfo::default()
                    .wait_semaphores(&waits)
                    .wait_dst_stage_mask(&stages)
                    .command_buffers(&buffers)
                    .signal_semaphores(&signals)];
                if let Some(targets)=processing_graph.public_guide_targets.as_mut(){targets.mark_stereo_submission_entered()?;}
                device
                    .queue_submit(queue, &submits, frame_fence)
                    .map_err(|error| format!("peer-queue-submit-{error:?}"))?;
                let swapchains = [swapchain];
                let indices = [image_index];
                match swapchain_loader.queue_present(
                    queue,
                    &vk::PresentInfoKHR::default()
                        .wait_semaphores(&signals)
                        .swapchains(&swapchains)
                        .image_indices(&indices),
                ) {
                    Ok(_) => {}
                    Err(vk::Result::ERROR_OUT_OF_DATE_KHR) => break,
                    Err(error) => return Err(format!("peer-queue-present-{error:?}")),
                }
            }
            #[cfg(rq_environment_depth_spatial_sdk_api_layer)]
            {
                let request_id = (surface_generation << 32) | u64::from(frames_presented + 1);
                if let Some(targets)=processing_graph.public_guide_targets.as_mut(){targets.mark_stereo_sdk_submission_entered(sdk_binding.session_generation,request_id)?;}
                let enqueue = crate::spatial_sdk_depth_handoff::enqueue_spatial_submit_present(
                    sdk_binding,
                    request_id,
                    0,
                    surface_generation,
                    0,
                    command_buffer.as_raw(),
                    image_available.as_raw(),
                    render_finished.as_raw(),
                    frame_fence.as_raw(),
                    swapchain.as_raw(),
                    vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT.as_raw(),
                    image_index,
                );
                if enqueue != 3 && enqueue != 0 {
                    projection_readback.cancel_unsubmitted("peer-spatial-sdk-enqueue");
                    return Err(format!("peer-spatial-sdk-enqueue-{enqueue}"));
                }
                let retirement_deadline = Instant::now() + Duration::from_secs(2);
                let mut retirement =
                    crate::spatial_sdk_depth_handoff::SpatialSubmitRetirementState::new_bound(request_id,sdk_binding.session_generation,frame_fence.as_raw());
                let mut shutdown_reason = None;
                loop {
                    if shutdown_reason.is_none()
                        && (cancellation.load(Ordering::Acquire)
                            || Instant::now() >= retirement_deadline)
                    {
                        shutdown_reason = Some(if cancellation.load(Ordering::Acquire) {
                            "source-set-session-cancelled-during-sdk-retirement"
                        } else {
                            "peer-spatial-sdk-retirement-timeout"
                        });
                        crate::spatial_sdk_depth_handoff::request_spatial_depth_shutdown(
                            sdk_binding.session_generation,
                        );
                    }
                    if retirement.broker_status.is_none() {
                        match crate::spatial_sdk_depth_handoff::poll_spatial_submit_request(
                            request_id,
                        ) {
                            Ok(result) if result.status == 0 || result.status < 0 => {
                                if !retirement.observe_terminal(result) {
                                    crate::spatial_sdk_depth_handoff::request_spatial_depth_shutdown(
                                        sdk_binding.session_generation,
                                    );
                                    return Err(
                                        "peer-spatial-sdk-invalid-terminal-result".to_string()
                                    );
                                }
                            }
                            Ok(_) => retirement.observe_not_ready(),
                            Err(status)
                                if status == crate::spatial_sdk_depth_handoff::STATUS_NOT_READY =>
                            {
                                retirement.observe_not_ready();
                            }
                            Err(status) => {
                                // The broker did not provide a typed submitted/unsubmitted
                                // terminal result. Cancel the owning SDK session and keep the
                                // AImage-backed source pinned until device-idle teardown.
                                crate::spatial_sdk_depth_handoff::request_spatial_depth_shutdown(
                                    sdk_binding.session_generation,
                                );
                                return Err(format!("peer-spatial-sdk-poll-{status}"));
                            }
                        }
                    }
                    if retirement.accepted_submission_requires_fence() {
                        match device.get_fence_status(frame_fence) {
                            Ok(true) => retirement.observe_fence(),
                            Ok(false) => {}
                            Err(error) => {
                                crate::spatial_sdk_depth_handoff::request_spatial_depth_shutdown(
                                    sdk_binding.session_generation,
                                );
                                return Err(format!("peer-poll-fence-{error:?}"));
                            }
                        }
                    }
                    match retirement.action() {
                        crate::spatial_sdk_depth_handoff::SpatialSubmitRetirementAction::Wait => {}
                        crate::spatial_sdk_depth_handoff::SpatialSubmitRetirementAction::ReleaseSuccess => {
                            if let Some(reason) = shutdown_reason {
                                // ReleaseSuccess includes the actual accepted-frame fence observation.
                                if let Some(mut imports)=stereo_source_imports.take(){
                                    imports.retire_after_fence(device)?;imports.destroy(device)?;
                                }
                                return Err(reason.to_string());
                            }
                            break;
                        }
                        crate::spatial_sdk_depth_handoff::SpatialSubmitRetirementAction::ReleaseUnsubmittedFailure => {
                            if let Some(proof)=retirement.unsubmitted_proof(){
                                if let Some(targets)=processing_graph.public_guide_targets.as_mut(){targets.cancel_stereo_sdk_unsubmitted(proof)?;}
                                if let Some(mut imports)=stereo_source_imports.take(){
                                    imports.cancel_sdk_unsubmitted(sdk_binding.session_generation,retirement.request_id,proof)?;
                                    imports.destroy(device)?;
                                }
                                projection_readback.cancel_unsubmitted("peer-spatial-sdk-typed-unsubmitted");
                            }
                            return Err(shutdown_reason.map(str::to_string).unwrap_or_else(|| {
                                format!(
                                    "peer-spatial-sdk-unsubmitted-{}-vk-{}",
                                    retirement.broker_status.unwrap_or(-1),
                                    retirement.broker_vk_result,
                                )
                            }));
                        }
                        crate::spatial_sdk_depth_handoff::SpatialSubmitRetirementAction::ReleaseSubmittedFailure => {
                            // Submitted failure is releasable only after its actual fence observation.
                            if let Some(mut imports)=stereo_source_imports.take(){
                                imports.retire_after_fence(device)?;imports.destroy(device)?;
                            }
                            return Err(shutdown_reason.map(str::to_string).unwrap_or_else(|| {
                                format!(
                                    "peer-spatial-sdk-submitted-{}-vk-{}",
                                    retirement.broker_status.unwrap_or(-1),
                                    retirement.broker_vk_result,
                                )
                            }));
                        }
                    }
                    thread::yield_now();
                }
            }
            device
                .wait_for_fences(&[frame_fence], true, u64::MAX)
                .map_err(|error| format!("peer-retirement-fence-{error:?}"))?;
            if let Some(targets)=processing_graph.public_guide_targets.as_mut(){targets.retire_stereo_banks_after_fence(device)?;}
            projection_readback.retire_after_fence(&device);
            frames_presented = frames_presented.saturating_add(1);
            record_presented_frame(record_result.video_stats.ready,record_result.video_stats.rendered,
                record_result.video_stats.frame_index,record_result.video_stats.timestamp_ns,u64::from(frames_presented));
            if let Some(targets)=processing_graph.public_guide_targets.as_mut(){targets.retire_stereo_banks_after_fence(device)?;}
            if let Some(imports)=stereo_source_imports.as_mut(){imports.retire_after_fence(device)?;}

        }
        if let Some(mut imports)=stereo_source_imports {
            device.wait_for_fences(&[frame_fence],true,u64::MAX).map_err(|e|format!("stereo-final-retire-{e:?}"))?;
            if let Some(targets)=processing_graph.public_guide_targets.as_mut(){targets.retire_stereo_banks_after_fence(device)?;}
            imports.retire_after_fence(device)?;imports.destroy(device)?;
        }
        Ok(())
    })();

    let cleanup_result = owned.teardown();
    if let Err(error) = &cleanup_result {
        let stage = source_set_cleanup_failure_stage(error);
        crate::camera_hwb_marker::log_camera_hwb_marker(format!(
            "channel=source-set-cleanup status=rejected stage={stage} code=PHYSICAL_RETIREMENT_PENDING"));
    }
    crate::spatial_stereo_qualification::carrier_cleanup(cleanup_result.is_ok());
    run_result.and(cleanup_result)
}

pub(crate) unsafe fn start_source_set_common_graph(
    window: *mut ANativeWindow,
    requested_width: u32,
    requested_height: u32,
    frame_count: i32,
) -> i64 {
    let route_generation=match crate::own_packed_pool_jni::process_generation() {
        Ok(value)=>(value & i64::MAX as u64).max(1) as i64,Err(_)=>{if !window.is_null(){ACameraNativeWindow_release(window);}return 0;}
    };
    if window.is_null() || !crate::own_stereo_capture_runtime::capture_route_selected() {
        if !window.is_null(){ACameraNativeWindow_release(window);}
        return 0;
    }
    let max_frames = if frame_count <= 0 {
        0
    } else {
        (frame_count as u32).min(CAMERA_HWB_PROBE_MAX_FRAMES)
    };
    let window_address = window as usize;
    let (ready_tx, ready_rx) = std::sync::mpsc::sync_channel(1);
    let cancellation = Arc::new(AtomicBool::new(false));
    {
        let mut owner = PEER_COMMON_GRAPH_SESSION
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        if owner.claim.generation()==Some(route_generation) && owner.worker.as_ref().is_some_and(|worker|!worker.is_finished()) {
            // Reuse only this exact physical carrier; never replace or stop the Own worker.
            let same_window=owner.window_address==window_address;
            ACameraNativeWindow_release(window);return if same_window {1} else {0};
        }
        if !owner.claim.claim(route_generation) {
            ACameraNativeWindow_release(window);
            return 0;
        }
        owner.window_address=window_address;
        owner.cancellation = Some(cancellation.clone());
        let worker_cancellation = cancellation.clone();
        ACTIVE_PEER_COMMON_GRAPH_WORKERS.fetch_add(1, Ordering::AcqRel);
        let spawn = thread::Builder::new()
            .name(format!("spatial-peer-common-graph-{route_generation}"))
            .spawn(move || {
                let window = window_address as *mut vk::ANativeWindow;
                let result = std::panic::catch_unwind(|| unsafe {
                    render_source_set_common_graph(
                        window,
                        requested_width.max(64),
                        requested_height.max(64),
                        max_frames,
                        worker_cancellation,
                        ready_tx,
                    )
                })
                .unwrap_or_else(|_| Err("panic".to_string()));
                unsafe {
                    ACameraNativeWindow_release(window.cast::<ANativeWindow>());
                }
                ACTIVE_PEER_COMMON_GRAPH_WORKERS.fetch_sub(1, Ordering::AcqRel);
                publish_acquisition_stopped_if_quiescent();
                if let Err(error) = result {
                    log_marker(format!(
                        "status=peer-common-graph-failed routeGeneration={} error={} source=retained-stereo-set camera2Opened=false runtimeCrash=false",
                        route_generation,
                        marker_token(&error),
                    ));

                }
            });
        match spawn {
            Ok(worker) => owner.worker = Some(worker),
            Err(_) => {
                ACTIVE_PEER_COMMON_GRAPH_WORKERS.fetch_sub(1, Ordering::AcqRel);
                publish_acquisition_stopped_if_quiescent();
                owner.cancellation = None;
                let released = owner.claim.release(route_generation);
                debug_assert!(released);
                ACameraNativeWindow_release(window);
                return 0;
            }
        }
    }
    if matches!(
        ready_rx.recv_timeout(Duration::from_millis(CAMERA_HWB_PROBE_WAIT_FRAME_MS * 2)),
        Ok(Ok(()))
    ) {
        return 1;
    }
    let worker = {
        let mut owner = PEER_COMMON_GRAPH_SESSION
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        if owner.claim.generation() != Some(route_generation) {
            None
        } else {
            cancellation.store(true, Ordering::Release);
            owner.worker.take()
        }
    };
    if let Some(worker) = worker {
        let _ = worker.join();
        let mut owner = PEER_COMMON_GRAPH_SESSION
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        if owner.claim.generation() == Some(route_generation) {
            owner.cancellation = None;
            let released = owner.claim.release(route_generation);
            debug_assert!(released);
        }
    }

    0
}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeStartSourceSetCommonGraph(
    env:*mut c_void,_thiz:*mut c_void,surface:*mut c_void,width:c_int,height:c_int,frame_count:c_int
)->i64 {
    if surface.is_null(){return 0;}
    unsafe {let window=ANativeWindow_fromSurface(env,surface);start_source_set_common_graph(window.cast(),width.max(64) as u32,height.max(64) as u32,frame_count)}
}

fn source_set_cleanup_failure_stage(error: &str) -> &'static str {
    if error.starts_with("peer-device-wait-idle-") { "DEVICE_IDLE" }
    else if error == "stereo-fence-pending" || error.starts_with("stereo-retirement-fence-") { "SHARED_FENCE" }
    else if error == "stereo-pool-vk-retirement-pending" || error == "GPU registry poisoned"
        || error == "pool quota unavailable" || error.starts_with("Vk fence failed; hold quarantined")
        || error.starts_with("native queue submission failed; exact contents remain quarantined") { "CONTENT_HOLD" }
    else { "UNKNOWN_RETIREMENT" }
}
