package io.github.mesmerprism.rustyquest.native_renderer;
import org.json.JSONObject;

    /** JSON adapter invoked exclusively by {@link ExperimentSessionAndroidShell}'s worker. */
final class ExperimentSessionJsonCodec
            implements ExperimentSessionAndroidShell.Codec {
        private final java.util.function.Supplier<ExperimentSessionPanelCoordinator> coordinator;
        ExperimentSessionJsonCodec(java.util.function.Supplier<ExperimentSessionPanelCoordinator> coordinator) {
            if (coordinator == null) throw new IllegalArgumentException("coordinator required");
            this.coordinator = coordinator;
        }
        @Override
        public String statusCommand() {
            try {
                return new JSONObject()
                    .put("schema", ExperimentSessionPanelCoordinator.SCHEMA)
                    .put("operation", "status")
                    .toString();
            } catch (Exception error) {
                throw new IllegalStateException("could not encode session status", error);
            }
        }

        @Override
        public String command(
                ExperimentSessionPanelCoordinator.NativeCommand command,
                boolean start,
                long elapsedRealtimeNanos,
                long utcNanos) {
            try {
                JSONObject request = new JSONObject()
                    .put("schema", ExperimentSessionPanelCoordinator.SCHEMA)
                    .put("operation", command.operation)
                    .put("operation_id", command.operationId)
                    .put("expected_generation", command.expectedGeneration)
                    .put("elapsed_realtime_ns", elapsedRealtimeNanos);
                if (start) {
                    request
                        .put("condition", command.condition)
                        .put(
                            "completion_threshold_ms",
                            ExperimentSessionPanelCoordinator.COMPLETION_THRESHOLD_MS
                        )
                        .put("breath_guidance_bias_percent",
                            command.breathGuidanceBiasPercent)
                        .put("started_at_utc_ns", utcNanos);
                }
                return request.toString();
            } catch (Exception error) {
                throw new IllegalStateException("could not encode session command", error);
            }
        }

        @Override
        public String statusResponseReason(String responseJson) {
            try { return new JSONObject(responseJson).optString("reason_code", "unknown"); }
            catch (Exception unavailable) { return "unknown"; }
        }

        @Override
        public ExperimentSessionAndroidShell.SessionReadback parse(
                String responseJson,
                ExperimentSessionPanelCoordinator.NativeCommand expected,
                boolean start) {
            try {
                JSONObject response = new JSONObject(responseJson == null ? "{}" : responseJson);
                if (!"rusty.quest.experiment_session.response.v1".equals(
                        response.optString("schema", ""))) {
                    throw new IllegalArgumentException("unexpected session response schema");
                }
                String commandStatus = response.optString("command_status", "rejected");
                JSONObject projection = response.optJSONObject("readback");
                if (projection == null) {
                    throw new IllegalArgumentException("session response omitted readback");
                }
                if (!validNonNegativeLong(projection, "generation")) {
                    String unavailableReason = response.optString("reason_code", "");
                    if ("rejected".equals(commandStatus)
                            && ExperimentSessionAndroidShell.retryableReason(unavailableReason)) {
                        return new ExperimentSessionAndroidShell.SessionReadback(null,
                            commandStatus, "unavailable", "unavailable", "unavailable",
                            "not-requested", 0L, -1L, "", unavailableReason, "unknown", "", 0L, "", "");
                    }
                    throw new IllegalArgumentException("authoritative-session-generation-unavailable");
                }
                JSONObject counts = projection.optJSONObject("counts");
                JSONObject byCondition = counts == null
                    ? null : counts.optJSONObject("by_condition");
                JSONObject conditionOne = byCondition == null
                    ? null : byCondition.optJSONObject("condition-a");
                JSONObject conditionTwo = byCondition == null
                    ? null : byCondition.optJSONObject("condition-b");
                boolean countsAvailable = validNonNegativeLong(counts, "completed")
                    && validNonNegativeLong(counts, "stopped_early")
                    && validNonNegativeLong(counts, "errors");
                boolean countsInvalid = counts != null && !countsAvailable;
                boolean perConditionCounts = countsAvailable
                    && validNonNegativeLong(conditionOne, "completed")
                    && validNonNegativeLong(conditionOne, "stopped_early")
                    && validNonNegativeLong(conditionOne, "errors")
                    && validNonNegativeLong(conditionTwo, "completed")
                    && validNonNegativeLong(conditionTwo, "stopped_early")
                    && validNonNegativeLong(conditionTwo, "errors");
                String operationId = projection.optString("last_operation_id", "");
                String operationStatus = projection.optString(
                    "last_operation_status",
                    commandStatus
                );
                boolean accepted = "accepted".equals(operationStatus);
                String phase = projection.optString("phase", "");
                String routeAction = projection.optString("route_action", "none");
                JSONObject recordingResult = projection.optJSONObject("recording_result");
                String recordingStatus = recordingResult == null
                    ? "unknown" : recordingResult.optString("status", "unknown");
                boolean restartReceipt = ExperimentSessionPanelCoordinator.restartFinalizationMatches(
                    projection.opt("phase"), projection.opt("route_action"),
                    projection.opt("preparation_mode"), projection.opt("last_operation_id"),
                    projection.opt("finalized_operation_id"), projection.opt("generation"),
                    projection.opt("finalized_session_generation"));
                if (restartReceipt && !"saved".equals(recordingStatus)) accepted = false;
                ExperimentSessionPanelState before = coordinator.get().snapshot();
                String activeCondition = before.activeCondition;
                if (projection.has("condition_id")) {
                    Object nativeCondition = projection.opt("condition_id");
                    if (nativeCondition == JSONObject.NULL && !"active".equals(phase)) {
                        activeCondition = "none";
                    } else if (nativeCondition instanceof String
                            && ((String)nativeCondition).matches("condition-[ab]")) {
                        activeCondition = (String)nativeCondition;
                    } else {
                        throw new IllegalArgumentException("authoritative-session-condition-invalid");
                    }
                } else if ("active".equals(phase)) {
                    throw new IllegalArgumentException("authoritative-active-session-condition-unavailable");
                } else if ("idle".equals(phase)) {
                    // Legacy idle status did not always carry this optional identity.
                    activeCondition = "none";
                }
                boolean startingOperation = start && expected != null
                    && expected.operationId.equals(operationId);
                if (startingOperation && "active".equals(phase)
                        && (!projection.optBoolean("profile_identity_bound", false)
                            || !projection.optBoolean("audio_identity_bound", false))) {
                    accepted = false;
                }
                JSONObject guidanceProjection = projection.optJSONObject("breath_guidance");
                boolean guidanceExpected = startingOperation && "pattern-ready".equals(
                    ControlPanelActivity.conditionBreathGuidanceReadiness(expected.condition));
                if (guidanceExpected
                        && (guidanceProjection == null
                            || !guidanceProjection.optBoolean("identity_bound", false)
                            || guidanceProjection.optInt("bias_percent", -1)
                                != expected.breathGuidanceBiasPercent)) {
                    accepted = false;
                }
                String polarDetail = polarInputDetail(projection);
                boolean durable = accepted && restartReceipt
                    && "saved".equals(recordingStatus);
                String responseReason = response.optString("reason_code", "");
                String projectionReason = projection.optString("last_reason", "");
                String reason = restartReceipt && !"saved".equals(recordingStatus)
                    ? "recording-not-saved" : !projectionReason.isEmpty() && !"none".equals(projectionReason)
                    ? projectionReason : responseReason;
                ExperimentSessionPanelCoordinator.NativeReceipt receipt =
                    new ExperimentSessionPanelCoordinator.NativeReceipt(
                        operationId,
                        accepted,
                        durable,
                        projection.optLong("generation", 0L),
                        projection.optLong("revision", 0L),
                        phase,
                        projection.optBoolean("audio_technical_hold", false)
                            ? "audio-error" : projection.optString("control_state", ""),
                        activeCondition,
                        "active".equals(phase),
                        projection.optString("completion", ""),
                        projection.optLong("active_time_ms", 0L),
                        "running".equals(projection.optString("recovery_status", "not-run"))
                            || "error".equals(
                                projection.optString("recovery_status", "not-run")),
                        projection.optString("storage_status", "unavailable"),
                        projection.optBoolean("kiosk_requested", true),
                        conditionOne == null ? 0L : conditionOne.optLong("completed", 0L),
                        conditionTwo == null ? 0L : conditionTwo.optLong("completed", 0L),
                        conditionOne == null
                            ? 0L : conditionOne.optLong("stopped_early", 0L),
                        conditionTwo == null
                            ? 0L : conditionTwo.optLong("stopped_early", 0L),
                        perConditionCounts,
                        countsAvailable,
                        countsInvalid,
                        counts == null ? 0L : counts.optLong("completed", 0L),
                        counts == null ? 0L : counts.optLong("stopped_early", 0L),
                        counts == null ? 0L : counts.optLong("errors", 0L),
                        routeAction,
                        projection.optLong("route_action_revision", 0L),
                        projection.optBoolean("audio_technical_hold", false)
                            ? "audio-technical-hold"
                            : polarDetail != null ? polarDetail
                            : startingOperation && !accepted
                            ? "Condition was not armed: " + emptySessionReason(reason) + "."
                            : reason
                                + " · recovery="
                                + projection.optString("recovery_status", "unavailable")
                                + " · kiosk="
                                + projection.optString("kiosk_effective", "unknown")
                                + "/"
                                + projection.optString(
                                    "kiosk_enforcement",
                                    "not-enforced"),
                        durable && "none".equals(routeAction)
                    );
                return new ExperimentSessionAndroidShell.SessionReadback(
                    receipt,
                    commandStatus,
                    projection.optString("initialization_status", "unavailable"),
                    projection.optString("inventory_status", "inventory-unavailable"),
                    projection.optString("recording_root_status", "unavailable"),
                    projection.optString("shutdown_status", "not-requested"),
                    projection.optLong("shutdown_ack_revision", 0L),
                    projection.optLong("finalized_session_generation", 0L),
                    projection.optString("finalized_operation_id", ""),
                    reason,
                    recordingStatus,
                    recordingResult == null ? "" : recordingResult.optString("error", ""),
                    projection.optLong("runtime_epoch", 0L),
                    projection.optString("last_operation_status", ""),
                    projection.optString("last_reason", "")
                ).withArmAcceptance(parseArmAcceptance(projection));
            } catch (RuntimeException error) {
                throw error;
            } catch (Exception error) {
                throw new IllegalArgumentException("could not parse session response", error);
            }
        }

        private static ExperimentSessionAndroidShell.ArmAcceptance parseArmAcceptance(JSONObject value) {
            if (!Boolean.TRUE.equals(value.opt("arm_durable_checkpoint"))
                    || !Boolean.TRUE.equals(value.opt("profile_identity_bound"))
                    || !Boolean.TRUE.equals(value.opt("audio_identity_bound"))) return null;
            for (String field : new String[]{"arm_generation", "arm_runtime_epoch"}) {
                Object raw=value.opt(field);
                if (!(raw instanceof Integer) && !(raw instanceof Long)) return null;
                if (((Number)raw).longValue() <= 0L) return null;
            }
            Object id=value.opt("arm_operation_id"),condition=value.opt("arm_condition");
            if (!(id instanceof String) || ((String)id).isEmpty()
                    || !(condition instanceof String) || ((String)condition).isEmpty()) return null;
            return new ExperimentSessionAndroidShell.ArmAcceptance((String)id,
                ((Number)value.opt("arm_generation")).longValue(),
                ((Number)value.opt("arm_runtime_epoch")).longValue(),(String)condition,true);
        }

        private static boolean validNonNegativeLong(JSONObject value, String field) {
            if (value == null || !value.has(field) || value.isNull(field)) return false;
            Object raw = value.opt(field);
            if (!(raw instanceof Number)) return false;
            Number number = (Number) raw;
            double floating = number.doubleValue();
            long integer = number.longValue();
            return !Double.isNaN(floating)
                && !Double.isInfinite(floating)
                && floating == (double) integer
                && integer >= 0L;
        }

        private static String polarInputDetail(JSONObject projection) {
            JSONObject input=projection.optJSONObject("polar_input");
            if(input==null || !Boolean.TRUE.equals(input.opt("required"))) return null;
            if(!Boolean.TRUE.equals(input.opt("fresh")))
                return "Waiting for fresh Polar ACC. Automatic reconnection continues; the official clock is held.";
            if(Boolean.TRUE.equals(input.opt("pause_latched")))
                return "Polar reconnected. The session remains paused; explicitly resume when ready.";
            return null;
        }

        private static String emptySessionReason(String reason) {
            if ("profile-identity-not-packaged".equals(reason)) {
                return "the packaged study profile identity did not match";
            }
            if ("audio-identity-not-packaged".equals(reason)) {
                return "the packaged condition audio identity did not match";
            }
            if (reason == null || reason.isEmpty() || "none".equals(reason)) {
                return "native admission was rejected";
            }
            return reason;
        }
    }

