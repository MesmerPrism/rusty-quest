#version 450

// Public fallback: this establishes no private metric meaning. The payload-only
// detailed shader supplies the same ABI when diagnostics are explicitly linked.
layout(local_size_x = 64, local_size_y = 1, local_size_z = 1) in;
layout(set = 0, binding = 9) buffer GenericDiagnosticRows { int values[]; } diagnostics;
void main() {
    if (gl_GlobalInvocationID.x == 0u) {
        diagnostics.values[23] = 0;
    }
}
