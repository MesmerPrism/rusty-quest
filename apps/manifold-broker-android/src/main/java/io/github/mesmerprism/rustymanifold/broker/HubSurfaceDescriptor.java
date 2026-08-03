package io.github.mesmerprism.rustymanifold.broker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Validated surface descriptor with server-substituted Android identity. */
public final class HubSurfaceDescriptor {
    public static final class Command {
        private final String commandId;
        private final String displayLabel;
        private final String requiredControllerCapability;

        public Command(String commandId, String displayLabel, String requiredControllerCapability) {
            this.commandId = requireToken(
                    commandId, ConnectionHubProtocol.MAX_COMMAND_ID_CHARS, "command");
            this.displayLabel = requireText(
                    displayLabel, ConnectionHubProtocol.MAX_LABEL_CHARS, "command display_label");
            this.requiredControllerCapability = requireToken(
                    requiredControllerCapability,
                    ConnectionHubProtocol.MAX_COMMAND_ID_CHARS,
                    "required_controller_capability");
        }
        public String commandId() { return commandId; }
        public String displayLabel() { return displayLabel; }
        public String requiredControllerCapability() { return requiredControllerCapability; }
    }
    private final int schemaVersion;
    private final String surfaceId;
    private final String displayLabel;
    private final String description;
    private final HubProviderIdentity providerIdentity;
    private final List<Command> commands;
    private final String surfaceContractSha256;

    public HubSurfaceDescriptor(
            int schemaVersion,
            String surfaceId,
            String displayLabel,
            String description,
            HubProviderIdentity providerIdentity,
            List<Command> commands,
            String claimedSurfaceContractSha256) {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("unsupported surface schema version");
        }
        this.schemaVersion = schemaVersion;
        this.surfaceId = requireToken(surfaceId, ConnectionHubProtocol.MAX_SURFACE_ID_CHARS, "surface_id");
        this.displayLabel = requireText(displayLabel, ConnectionHubProtocol.MAX_LABEL_CHARS, "display_label");
        this.description = requireText(description, ConnectionHubProtocol.MAX_DESCRIPTION_CHARS, "description");
        this.providerIdentity = Objects.requireNonNull(providerIdentity, "providerIdentity");
        if (commands == null || commands.isEmpty()
                || commands.size() > ConnectionHubProtocol.MAX_COMMANDS) {
            throw new IllegalArgumentException("command allowlist is empty or too large");
        }
        Set<String> unique = new LinkedHashSet<>();
        List<Command> checkedCommands = new ArrayList<>();
        String priorCommandId = null;
        for (Command command : commands) {
            if (!unique.add(command.commandId())) {
                throw new IllegalArgumentException("duplicate command");
            }
            if (priorCommandId != null
                    && priorCommandId.compareTo(command.commandId()) >= 0) {
                throw new IllegalArgumentException("commands must be ordinally sorted");
            }
            checkedCommands.add(command);
            priorCommandId = command.commandId();
        }
        this.commands = Collections.unmodifiableList(checkedCommands);
        String calculated = "sha256:" + sha256Hex(canonicalContract());
        if (!calculated.equals(claimedSurfaceContractSha256)) {
            throw new SecurityException("surface contract SHA-256 mismatch");
        }
        this.surfaceContractSha256 = calculated;
    }

    public int schemaVersion() { return schemaVersion; }
    public String surfaceId() { return surfaceId; }
    public String displayLabel() { return displayLabel; }
    public String description() { return description; }
    public HubProviderIdentity providerIdentity() { return providerIdentity; }
    public List<Command> commands() { return commands; }
    public String surfaceContractSha256() { return surfaceContractSha256; }

    public boolean permits(String command) {
        for (Command item : commands) {
            if (item.commandId().equals(command)) { return true; }
        }
        return false;
    }

    public String canonicalContract() {
        StringBuilder value = new StringBuilder();
        value.append("v1\n").append(surfaceId).append('\n')
                .append(displayLabel).append('\n').append(description).append('\n');
        for (Command command : commands) {
            value.append(command.commandId()).append('|')
                    .append(command.displayLabel()).append('|')
                    .append(command.requiredControllerCapability()).append('\n');
        }
        return value.toString();
    }

    public static String contractSha256(
            String surfaceId,
            String displayLabel,
            String description,
            List<Command> commands) {
        StringBuilder value = new StringBuilder();
        value.append("v1\n").append(surfaceId.trim()).append('\n')
                .append(displayLabel.trim()).append('\n').append(description.trim()).append('\n');
        for (Command command : commands) {
            value.append(command.commandId()).append('|')
                    .append(command.displayLabel()).append('|')
                    .append(command.requiredControllerCapability()).append('\n');
        }
        return "sha256:" + sha256Hex(value.toString());
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder(64);
            for (byte item : digest) {
                output.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
            }
            return output.toString();
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    static String requireToken(String value, int max, String name) {
        String candidate = Objects.requireNonNull(value, name).trim();
        if (candidate.isEmpty() || candidate.length() > max
                || !candidate.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")) {
            throw new IllegalArgumentException("invalid " + name);
        }
        return candidate;
    }

    static String requireText(String value, int max, String name) {
        String candidate = Objects.requireNonNull(value, name).trim();
        if (candidate.isEmpty() || candidate.length() > max
                || candidate.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("invalid " + name);
        }
        return candidate;
    }
}
