package io.github.mesmerprism.rustyquest.native_renderer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Worker-only verifier/materializer for the packaged experiment-session trust closure. */
final class ExperimentSessionPackagedClosure {
    static final String PROFILE_ASSET_ID = "experiment-session-profile";
    static final String PROFILE_DESTINATION = "session-config/experiment-session.json";
    static final String MATERIALIZED_DIRECTORY = "viscereality-recordings";
    static final String MATERIALIZED_FILE = "experiment-session-profile.json";
    private static final String CLOSURE_SCHEMA =
        "rusty.quest.native_app_private_asset_closure.v1";
    private static final String OUTER_SCHEMA =
        "rusty.viscereality.experiment_session_profile.v1";
    private static final String PROJECTION_SCHEMA =
        "rusty.quest.experiment_session.runtime_projection.v1";

    interface AssetReader {
        byte[] read(String logicalDestination) throws Exception;
    }

    static final class Anchors {
        final String profileSha256;
        final String providerManifestSha256;
        final String inventorySha256;

        Anchors(String profileSha256, String providerManifestSha256, String inventorySha256) {
            this.profileSha256 = safe(profileSha256);
            this.providerManifestSha256 = safe(providerManifestSha256);
            this.inventorySha256 = safe(inventorySha256);
        }

        boolean absent() {
            return profileSha256.isEmpty()
                && providerManifestSha256.isEmpty()
                && inventorySha256.isEmpty();
        }

        void requireComplete() {
            if (!sha256(profileSha256)
                    || !sha256(providerManifestSha256)
                    || !sha256(inventorySha256)) {
                fail("experiment-session-compiled-anchors-invalid");
            }
        }
    }

    static final class AudioEntry {
        final String conditionId;
        final String logicalDestination;
        final String sourceSha256;
        final long sourceBytes;
        final String mediaType;

        AudioEntry(String conditionId, AssetEntry packaged) {
            this.conditionId = conditionId;
            this.logicalDestination = packaged.logicalDestination;
            this.sourceSha256 = packaged.sourceSha256;
            this.sourceBytes = packaged.sourceBytes;
            this.mediaType = packaged.mediaType;
        }
    }

    static final class Result {
        final boolean active;
        final String providerId;
        final String inventorySha256;
        final AudioEntry[] audioEntries;

        private Result(boolean active, String providerId, String inventorySha256,
                AudioEntry[] audioEntries) {
            this.active = active;
            this.providerId = safe(providerId);
            this.inventorySha256 = safe(inventorySha256);
            this.audioEntries = audioEntries == null ? new AudioEntry[0] : audioEntries.clone();
        }

        static Result inactive() {
            return new Result(false, "", "", new AudioEntry[0]);
        }
    }

    private static final class AssetEntry {
        final String assetId;
        final String logicalDestination;
        final String sourceSha256;
        final long sourceBytes;
        final String mediaType;

        AssetEntry(Map<String, Object> object) {
            requireExactFields(object, "asset_id", "staged_object", "logical_destination",
                "source_sha256", "source_bytes", "media_type");
            assetId = string(object, "asset_id");
            string(object, "staged_object");
            logicalDestination = string(object, "logical_destination");
            sourceSha256 = string(object, "source_sha256");
            sourceBytes = positiveLong(object, "source_bytes");
            mediaType = string(object, "media_type");
            if (!token(assetId) || !path(logicalDestination) || !sha256(sourceSha256)) {
                fail("experiment-session-closure-asset-invalid");
            }
        }
    }

    private static final class AudioIdentity {
        final String logicalDestination;
        final String sourceSha256;
        final long sourceBytes;
        final String mediaType;

        AudioIdentity(Map<String, Object> object) {
            requireExactFields(object, "logical_destination", "source_sha256",
                "source_bytes", "media_type");
            logicalDestination = string(object, "logical_destination");
            sourceSha256 = string(object, "source_sha256");
            sourceBytes = positiveLong(object, "source_bytes");
            mediaType = string(object, "media_type");
            if (!path(logicalDestination) || !sha256(sourceSha256)
                    || !mediaType.startsWith("audio/")) {
                fail("experiment-session-profile-audio-invalid");
            }
        }

        void requireSame(AudioIdentity other, String reason) {
            if (other == null
                    || !logicalDestination.equals(other.logicalDestination)
                    || !sourceSha256.equals(other.sourceSha256)
                    || sourceBytes != other.sourceBytes
                    || !mediaType.equals(other.mediaType)) {
                fail(reason);
            }
        }

        void requireSame(AssetEntry other, String reason) {
            if (other == null
                    || !logicalDestination.equals(other.logicalDestination)
                    || !sourceSha256.equals(other.sourceSha256)
                    || sourceBytes != other.sourceBytes
                    || !mediaType.equals(other.mediaType)) {
                fail(reason);
            }
        }
    }

    private ExperimentSessionPackagedClosure() {}

    static Result prepare(byte[] featureLockBytes, AssetReader assets, Path filesRoot,
            Anchors anchors) throws Exception {
        if (featureLockBytes == null || assets == null || filesRoot == null || anchors == null) {
            fail("experiment-session-packaged-closure-input-missing");
        }
        Map<String, Object> lock = object(parse(strictUtf8(featureLockBytes,
            "feature-lock")), "feature-lock");
        Map<String, Object> buildInputs = object(lock.get("build_inputs"), "build_inputs");
        Map<String, Object> closure = object(buildInputs.get("private_asset_closure"),
            "private_asset_closure");
        if (!CLOSURE_SCHEMA.equals(string(closure, "schema"))) {
            fail("experiment-session-closure-schema-invalid");
        }
        String mode = string(closure, "mode");
        List<Object> encodedAssets = array(closure.get("assets"), "assets");
        long assetCount = nonNegativeLong(closure, "asset_count");
        if ("inactive".equals(mode)) {
            if (assetCount != 0L || !encodedAssets.isEmpty() || !anchors.absent()) {
                fail("experiment-session-inactive-closure-invalid");
            }
            return Result.inactive();
        }
        if (!"linked-provider".equals(mode) || assetCount != 3L
                || encodedAssets.size() != 3) {
            fail("experiment-session-closure-cardinality-invalid");
        }
        anchors.requireComplete();
        String providerId = string(closure, "provider_id");
        String manifestSha256 = string(closure, "provider_manifest_sha256");
        String inventorySha256 = string(closure, "inventory_sha256");
        if (!token(providerId)
                || !anchors.providerManifestSha256.equals(manifestSha256)
                || !anchors.inventorySha256.equals(inventorySha256)) {
            fail("experiment-session-closure-anchor-mismatch");
        }

        LinkedHashMap<String, AssetEntry> entries = new LinkedHashMap<String, AssetEntry>();
        for (Object encoded : encodedAssets) {
            AssetEntry entry = new AssetEntry(object(encoded, "asset"));
            if (entries.put(entry.assetId, entry) != null) {
                fail("experiment-session-closure-asset-id-duplicate");
            }
        }
        if (!entries.keySet().equals(new java.util.LinkedHashSet<String>(Arrays.asList(
                "condition-audio-a", "condition-audio-b", PROFILE_ASSET_ID)))) {
            fail("experiment-session-closure-asset-id-invalid");
        }
        AssetEntry profileEntry = entries.get(PROFILE_ASSET_ID);
        if (!PROFILE_DESTINATION.equals(profileEntry.logicalDestination)
                || !"application/json".equals(profileEntry.mediaType)
                || !anchors.profileSha256.equals(profileEntry.sourceSha256)) {
            fail("experiment-session-profile-closure-mismatch");
        }

        byte[] profileBytes = assets.read(profileEntry.logicalDestination);
        if (profileBytes == null || profileBytes.length != profileEntry.sourceBytes
                || !profileEntry.sourceSha256.equals(digest(profileBytes))) {
            fail("experiment-session-profile-bytes-mismatch");
        }
        String profileText = strictUtf8(profileBytes, "experiment-session-profile");
        Map<String, Object> profile = object(parse(profileText), "experiment-session-profile");
        requireExactFields(profile, "schema_id", "profile_id", "visibility",
            "recording_default", "kiosk_requested_default", "non_audio_profile",
            "non_audio_profile_sha256", "radius_observation", "runtime_projection",
            "conditions");
        if (!OUTER_SCHEMA.equals(string(profile, "schema_id"))) {
            fail("experiment-session-outer-schema-invalid");
        }

        Map<String, AudioIdentity> outerAudio = parseOuterConditions(profile);
        Map<String, Object> projection = object(profile.get("runtime_projection"),
            "runtime_projection");
        requireExactFields(projection, "schema", "provider_id",
            "effective_radius_profile", "conditions");
        if (!PROJECTION_SCHEMA.equals(string(projection, "schema"))) {
            fail("experiment-session-runtime-projection-schema-invalid");
        }
        if (!providerId.equals(string(projection, "provider_id"))) {
            fail("experiment-session-runtime-provider-drift");
        }
        validateRadiusProfile(object(projection.get("effective_radius_profile"),
            "effective_radius_profile"));
        Map<String, Object> projectedConditions = object(projection.get("conditions"),
            "runtime conditions");
        requireExactFields(projectedConditions, "condition-a", "condition-b");

        AssetEntry packagedA = entries.get("condition-audio-a");
        AssetEntry packagedB = entries.get("condition-audio-b");
        validateProjectedCondition("condition-a", projectedConditions, outerAudio, packagedA);
        validateProjectedCondition("condition-b", projectedConditions, outerAudio, packagedB);
        if (packagedA.sourceSha256.equals(packagedB.sourceSha256)) {
            fail("experiment-session-audio-identities-not-distinct");
        }

        materialize(filesRoot, profileBytes);
        return new Result(true, providerId, inventorySha256, new AudioEntry[] {
            new AudioEntry("condition-a", packagedA),
            new AudioEntry("condition-b", packagedB)
        });
    }

    private static Map<String, AudioIdentity> parseOuterConditions(Map<String, Object> profile) {
        List<Object> values = array(profile.get("conditions"), "outer conditions");
        if (values.size() != 2) fail("experiment-session-outer-conditions-invalid");
        LinkedHashMap<String, AudioIdentity> result = new LinkedHashMap<String, AudioIdentity>();
        for (Object value : values) {
            Map<String, Object> condition = object(value, "outer condition");
            requireExactFields(condition, "condition_id", "ui_label",
                "completion_threshold_ms", "audio");
            String conditionId = string(condition, "condition_id");
            String expectedLabel = "condition-a".equals(conditionId) ? "Condition 1"
                : "condition-b".equals(conditionId) ? "Condition 2" : "";
            if (!expectedLabel.equals(string(condition, "ui_label"))
                    || exactLong(condition, "completion_threshold_ms") != 30_000L
                    || result.put(conditionId,
                        new AudioIdentity(object(condition.get("audio"), "outer audio"))) != null) {
                fail("experiment-session-outer-condition-invalid");
            }
        }
        if (!result.keySet().equals(new java.util.LinkedHashSet<String>(
                Arrays.asList("condition-a", "condition-b")))) {
            fail("experiment-session-outer-conditions-invalid");
        }
        return result;
    }

    private static void validateProjectedCondition(String conditionId,
            Map<String, Object> projectedConditions,
            Map<String, AudioIdentity> outerAudio, AssetEntry packaged) {
        Map<String, Object> condition = object(projectedConditions.get(conditionId),
            "runtime condition");
        requireExactFields(condition, "completion_threshold_ms", "audio");
        if (exactLong(condition, "completion_threshold_ms") != 30_000L) {
            fail("experiment-session-runtime-threshold-invalid");
        }
        AudioIdentity projected = new AudioIdentity(object(condition.get("audio"),
            "runtime audio"));
        projected.requireSame(outerAudio.get(conditionId),
            "experiment-session-outer-runtime-audio-drift");
        projected.requireSame(packaged, "experiment-session-runtime-closure-audio-drift");
    }

    private static void validateRadiusProfile(Map<String, Object> radius) {
        if (!(radius.size() == 2 || radius.size() == 3 || radius.size() == 4)
                || !radius.containsKey("configured_radius_min_m")
                || !radius.containsKey("configured_radius_max_m")) {
            fail("experiment-session-runtime-radius-profile-invalid");
        }
        for (String key : radius.keySet()) {
            if (!"configured_radius_min_m".equals(key)
                    && !"configured_radius_max_m".equals(key)
                    && !"oblateness".equals(key) && !"axis_profile".equals(key)) {
                fail("experiment-session-runtime-radius-profile-invalid");
            }
        }
        double minimum = positiveFinite(radius, "configured_radius_min_m");
        double maximum = positiveFinite(radius, "configured_radius_max_m");
        if (maximum < minimum) fail("experiment-session-runtime-radius-profile-invalid");
        if (radius.containsKey("oblateness")) validateRadiusEndpoints(
            object(radius.get("oblateness"), "oblateness"));
        if (radius.containsKey("axis_profile")) validateRadiusEndpoints(
            object(radius.get("axis_profile"), "axis_profile"));
    }

    private static void validateRadiusEndpoints(Map<String, Object> endpoints) {
        requireExactFields(endpoints, "at_radius_min", "at_radius_max");
        finite(endpoints, "at_radius_min");
        finite(endpoints, "at_radius_max");
    }

    private static void materialize(Path filesRoot, byte[] profileBytes) throws IOException {
        Path directory = filesRoot.resolve(MATERIALIZED_DIRECTORY);
        Files.createDirectories(directory);
        Path target = directory.resolve(MATERIALIZED_FILE);
        if (Files.exists(target)) {
            requireExistingExact(target, profileBytes);
            return;
        }
        boolean created = false;
        try {
            try (FileChannel channel = FileChannel.open(target,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                created = true;
                ByteBuffer source = ByteBuffer.wrap(profileBytes);
                while (source.hasRemaining()) channel.write(source);
                channel.force(true);
            }
        } catch (FileAlreadyExistsException race) {
            requireExistingExact(target, profileBytes);
            return;
        } catch (IOException failure) {
            if (created) {
                try { Files.deleteIfExists(target); }
                catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
        requireExistingExact(target, profileBytes);
    }

    private static void requireExistingExact(Path target, byte[] expected) throws IOException {
        if (!Arrays.equals(expected, Files.readAllBytes(target))) {
            fail("experiment-session-materialized-profile-drift");
        }
    }

    private static String strictUtf8(byte[] bytes, String label) {
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xef
                && (bytes[1] & 0xff) == 0xbb && (bytes[2] & 0xff) == 0xbf) {
            fail(label + "-bom-forbidden");
        }
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes));
            return decoded.toString();
        } catch (CharacterCodingException error) {
            fail(label + "-utf8-invalid");
            return "";
        }
    }

    private static String digest(byte[] bytes) throws Exception {
        byte[] encoded = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result = new StringBuilder(64);
        for (byte value : encoded) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private static Object parse(String text) {
        return new JsonParser(text).parse();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String label) {
        if (!(value instanceof Map)) fail(label + "-object-required");
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Object value, String label) {
        if (!(value instanceof List)) fail(label + "-array-required");
        return (List<Object>) value;
    }

    private static String string(Map<String, Object> object, String key) {
        Object value = object.get(key);
        if (!(value instanceof String) || ((String) value).isEmpty()) fail(key + "-invalid");
        return (String) value;
    }

    private static long positiveLong(Map<String, Object> object, String key) {
        long value = exactLong(object, key);
        if (value <= 0L) fail(key + "-invalid");
        return value;
    }

    private static long nonNegativeLong(Map<String, Object> object, String key) {
        long value = exactLong(object, key);
        if (value < 0L) fail(key + "-invalid");
        return value;
    }

    private static long exactLong(Map<String, Object> object, String key) {
        Object value = object.get(key);
        if (!(value instanceof Long)) fail(key + "-invalid");
        return ((Long) value).longValue();
    }

    private static double positiveFinite(Map<String, Object> object, String key) {
        double value = finite(object, key);
        if (value <= 0.0) fail(key + "-invalid");
        return value;
    }

    private static double finite(Map<String, Object> object, String key) {
        Object value = object.get(key);
        if (!(value instanceof Number)) fail(key + "-invalid");
        double decoded = ((Number) value).doubleValue();
        if (Double.isInfinite(decoded) || Double.isNaN(decoded)) fail(key + "-invalid");
        return decoded;
    }

    private static void requireExactFields(Map<String, Object> object, String... fields) {
        java.util.LinkedHashSet<String> expected =
            new java.util.LinkedHashSet<String>(Arrays.asList(fields));
        if (!object.keySet().equals(expected)) fail("json-fields-invalid");
    }

    private static boolean token(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,255}");
    }

    private static boolean path(String value) {
        return value != null && !value.isEmpty() && !value.startsWith("/")
            && value.indexOf('\\') < 0 && !value.contains("..") && value.indexOf('\u0000') < 0;
    }

    private static boolean sha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static String safe(String value) { return value == null ? "" : value; }

    private static void fail(String reason) { throw new IllegalArgumentException(reason); }

    /** Small strict JSON reader kept host-runnable so every trust-damage case is executable. */
    private static final class JsonParser {
        private final String source;
        private int offset;

        JsonParser(String source) { this.source = source == null ? "" : source; }

        Object parse() {
            Object value = value();
            whitespace();
            if (offset != source.length()) fail("json-trailing-content");
            return value;
        }

        private Object value() {
            whitespace();
            if (offset >= source.length()) fail("json-unexpected-end");
            char current = source.charAt(offset);
            if (current == '{') return object();
            if (current == '[') return array();
            if (current == '"') return string();
            if (current == 't') { literal("true"); return Boolean.TRUE; }
            if (current == 'f') { literal("false"); return Boolean.FALSE; }
            if (current == 'n') { literal("null"); return null; }
            if (current == '-' || (current >= '0' && current <= '9')) return number();
            fail("json-token-invalid");
            return null;
        }

        private Map<String, Object> object() {
            expect('{');
            LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
            whitespace();
            if (consume('}')) return result;
            while (true) {
                whitespace();
                if (offset >= source.length() || source.charAt(offset) != '"') {
                    fail("json-object-key-invalid");
                }
                String key = string();
                whitespace();
                expect(':');
                Object value = value();
                if (result.containsKey(key)) {
                    fail("json-object-key-duplicate");
                }
                result.put(key, value);
                whitespace();
                if (consume('}')) return result;
                expect(',');
            }
        }

        private List<Object> array() {
            expect('[');
            ArrayList<Object> result = new ArrayList<Object>();
            whitespace();
            if (consume(']')) return result;
            while (true) {
                result.add(value());
                whitespace();
                if (consume(']')) return result;
                expect(',');
            }
        }

        private String string() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (offset < source.length()) {
                char current = source.charAt(offset++);
                if (current == '"') return result.toString();
                if (current == '\\') {
                    if (offset >= source.length()) fail("json-string-invalid");
                    char escaped = source.charAt(offset++);
                    switch (escaped) {
                        case '"': case '\\': case '/': result.append(escaped); break;
                        case 'b': result.append('\b'); break;
                        case 'f': result.append('\f'); break;
                        case 'n': result.append('\n'); break;
                        case 'r': result.append('\r'); break;
                        case 't': result.append('\t'); break;
                        case 'u': result.append(unicode()); break;
                        default: fail("json-string-escape-invalid");
                    }
                } else {
                    if (current < 0x20) fail("json-string-control-invalid");
                    result.append(current);
                }
            }
            fail("json-string-unterminated");
            return "";
        }

        private char unicode() {
            if (offset + 4 > source.length()) fail("json-unicode-invalid");
            int value = 0;
            for (int index = 0; index < 4; index += 1) {
                int digit = Character.digit(source.charAt(offset++), 16);
                if (digit < 0) fail("json-unicode-invalid");
                value = (value << 4) | digit;
            }
            return (char) value;
        }

        private Number number() {
            int start = offset;
            if (consume('-') && offset >= source.length()) fail("json-number-invalid");
            if (consume('0')) {
                if (offset < source.length() && Character.isDigit(source.charAt(offset))) {
                    fail("json-number-leading-zero");
                }
            } else {
                digits();
            }
            boolean fractional = false;
            if (consume('.')) { fractional = true; digits(); }
            if (consume('e') || consume('E')) {
                fractional = true;
                consume('+');
                consume('-');
                digits();
            }
            String encoded = source.substring(start, offset);
            try {
                if (fractional) return Double.valueOf(encoded);
                return Long.valueOf(encoded);
            } catch (NumberFormatException error) {
                fail("json-number-invalid");
                return Long.valueOf(0L);
            }
        }

        private void digits() {
            int start = offset;
            while (offset < source.length() && Character.isDigit(source.charAt(offset))) offset++;
            if (start == offset) fail("json-number-invalid");
        }

        private void literal(String expected) {
            if (!source.regionMatches(offset, expected, 0, expected.length())) {
                fail("json-literal-invalid");
            }
            offset += expected.length();
        }

        private void whitespace() {
            while (offset < source.length()) {
                char current = source.charAt(offset);
                if (current != ' ' && current != '\n' && current != '\r' && current != '\t') return;
                offset++;
            }
        }

        private boolean consume(char expected) {
            if (offset < source.length() && source.charAt(offset) == expected) {
                offset++;
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            if (!consume(expected)) fail("json-structure-invalid");
        }
    }
}
