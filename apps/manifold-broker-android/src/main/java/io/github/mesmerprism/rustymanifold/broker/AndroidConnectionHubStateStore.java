package io.github.mesmerprism.rustymanifold.broker;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** App-private persistence. Session cookies are opaque transport credentials. */
public final class AndroidConnectionHubStateStore implements ConnectionHubStateStore {
    private static final String NAME = "rusty_connection_hub_v1";
    private static final String KEY_STATE = "state_json";
    private static final String KEY_ALIAS =
            "io.github.mesmerprism.rustymanifold.broker.connection_hub_state_v1";
    private static final String ENVELOPE_SCHEMA =
            "rusty.quest.connection_hub.encrypted_android_state.v1";
    private static final String SCHEMA = "rusty.quest.connection_hub.android_state.v3";
    private static final String LEGACY_V2_SCHEMA = "rusty.quest.connection_hub.android_state.v2";
    private static final String LEGACY_SCHEMA = "rusty.quest.connection_hub.android_state.v1";
    private final SharedPreferences preferences;

    public AndroidConnectionHubStateStore(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    @Override
    public State load() {
        String raw = preferences.getString(KEY_STATE, "");
        if (raw == null || raw.isEmpty()) {
            return State.stopped();
        }
        try {
            JSONObject encrypted = new JSONObject(raw);
            if (!ENVELOPE_SCHEMA.equals(encrypted.optString("$schema", ""))) {
                return State.stopped();
            }
            byte[] iv = Base64.decode(encrypted.getString("iv_base64"), Base64.NO_WRAP);
            byte[] ciphertext = Base64.decode(
                    encrypted.getString("ciphertext_base64"), Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, requireKey(), new GCMParameterSpec(128, iv));
            JSONObject value = new JSONObject(new String(
                    cipher.doFinal(ciphertext), StandardCharsets.UTF_8));
            String schema = value.optString("$schema", "");
            if (!SCHEMA.equals(schema)
                    && !LEGACY_V2_SCHEMA.equals(schema)
                    && !LEGACY_SCHEMA.equals(schema)) {
                return State.stopped();
            }
            Map<String, SessionProjection> sessions = new LinkedHashMap<>();
            JSONArray rows = value.optJSONArray("session_projections");
            if (rows != null) {
                for (int index = 0; index < rows.length(); index += 1) {
                    JSONObject row = rows.getJSONObject(index);
                    String cookie = row.getString("cookie");
                    if (!cookie.matches("[A-Za-z0-9_-]{43}")) {
                        continue;
                    }
                    sessions.put(cookie, new SessionProjection(
                            row.getString("logical_session_id"),
                            row.getLong("transport_epoch"),
                            row.getLong("expires_at_ms"),
                            row.optLong("next_external_request_sequence", 1)));
                }
            }
            return new State(
                    value.optBoolean("desired_running", false),
                    value.optString("authority_envelope", ""),
                    sessions,
                    value.optLong("generation", 0),
                    value.optString("pending_operation", ""));
        } catch (Exception damaged) {
            return State.stopped();
        }
    }

    @Override
    public void save(State state) {
        try {
            JSONObject value = new JSONObject();
            value.put("$schema", SCHEMA);
            value.put("desired_running", state.desiredRunning);
            value.put("authority_envelope", state.authorityEnvelope);
            value.put("generation", state.generation);
            value.put("pending_operation", state.pendingOperation);
            JSONArray rows = new JSONArray();
            for (Map.Entry<String, SessionProjection> item : state.sessionProjections.entrySet()) {
                JSONObject row = new JSONObject();
                row.put("cookie", item.getKey());
                row.put("logical_session_id", item.getValue().logicalSessionId);
                row.put("transport_epoch", item.getValue().transportEpoch);
                row.put("expires_at_ms", item.getValue().expiresAtMs);
                row.put("next_external_request_sequence",
                        item.getValue().nextExternalRequestSequence);
                rows.put(row);
            }
            value.put("session_projections", rows);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, requireKey());
            byte[] ciphertext = cipher.doFinal(value.toString().getBytes(StandardCharsets.UTF_8));
            JSONObject encrypted = new JSONObject();
            encrypted.put("$schema", ENVELOPE_SCHEMA);
            encrypted.put("iv_base64", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
            encrypted.put("ciphertext_base64", Base64.encodeToString(ciphertext, Base64.NO_WRAP));
            if (!preferences.edit().putString(KEY_STATE, encrypted.toString()).commit()) {
                throw new IllegalStateException("encrypted Connection Hub state commit failed");
            }
        } catch (Exception error) {
            throw new IllegalStateException("failed to persist Connection Hub state", error);
        }
    }

    @Override
    public void clear() {
        if (!preferences.edit().remove(KEY_STATE).commit()) {
            throw new IllegalStateException("Connection Hub state removal commit failed");
        }
    }

    private static SecretKey requireKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        java.security.Key existing = keyStore.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) {
            return (SecretKey) existing;
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
