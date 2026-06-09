# Rusty Quest Architecture

Rusty Quest owns platform profile and validation contracts for Quest-hosted
apps.

## Ownership

- runtime profile contracts;
- Android property hygiene and write/readback plans;
- Quest device profile catalogs;
- launch and validation receipts;
- platform tooling wrappers.
- Quest-owned Android package adapters for platform-hosted broker surfaces.

## Non-Ownership

- Makepad widget or shell implementation;
- Matter mesh, SDF/ADF, collision, or particle truth;
- Optics view/projection/appearance truth;
- Manifold command/session authority;
- Lattice reference-space or tracked-pose authority.

ADB writes are generated operations from validated profiles. They are not
hand-authored settings authority.

## Manifold Broker Android Package

The Quest lane owns the Android package identity for the on-device Manifold
broker adapter:

```text
io.github.mesmerprism.rustymanifold.broker/.BrokerStartActivity
```

Manifold remains the command/session/stream authority. The Android app is a
platform adapter that exposes `/manifold/v1/events` and acknowledges
`rusty.manifold.command.envelope.v1` requests. It deliberately avoids
synthesizing live stream events; live Polar, controller, and Makepad streams
must come from their own providers.
