package io.github.mesmerprism.rustymanifold.broker;

import android.content.Context;

import org.json.JSONObject;

import io.github.mesmerprism.rustyquest.media.PackedStereoMediaSourceRuntime;

/** Legacy product facade over the reusable packed stereo media implementation. */
final class RemoteCameraPackedStereoSourceRuntime {
    private RemoteCameraPackedStereoSourceRuntime() { }

    static JSONObject ensureStarted(Context context, String sessionId, String sourceKind,
            String sourceHost, String sourcePorts, String mediaProfiles, String cameraIds,
            String mediaLayout, String frameLayout) throws Exception {
        return PackedStereoMediaSourceRuntime.ensureStarted(context, sessionId, sourceKind,
                sourceHost, sourcePorts, mediaProfiles, cameraIds, mediaLayout, frameLayout);
    }

    static JSONObject stop(String sessionId, String reason) throws Exception {
        return PackedStereoMediaSourceRuntime.stop(sessionId, reason);
    }

    static JSONObject statusForSession(String sessionId) throws Exception {
        return PackedStereoMediaSourceRuntime.statusForSession(sessionId);
    }
}
