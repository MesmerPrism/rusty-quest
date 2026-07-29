#!/usr/bin/env bash
set -euo pipefail

app_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
native_root="$app_root/native"
lock_file="$native_root/manifold-source.lock.json"
lock_revision="$(sed -n 's/.*"revision": "\([^"]*\)".*/\1/p' "$lock_file")"
lock_tree="$(sed -n 's/.*"tree": "\([^"]*\)".*/\1/p' "$lock_file")"
test -n "$lock_revision"
test -n "$lock_tree"
manifold_root="${RUSTY_MANIFOLD_SOURCE_ROOT:?RUSTY_MANIFOLD_SOURCE_ROOT is required while the pinned commit is unpublished}"
ndk_root="${ANDROID_NDK_ROOT:?ANDROID_NDK_ROOT is required}"

head="$(git -C "$manifold_root" rev-parse HEAD)"
tree="$(git -C "$manifold_root" rev-parse 'HEAD^{tree}')"
test "$head" = "$lock_revision"
test "$tree" = "$lock_tree"
test -z "$(git -C "$manifold_root" status --porcelain)"

case "$(uname -s)" in
  Linux*) host_tag="linux-x86_64" ;;
  Darwin*) host_tag="darwin-x86_64" ;;
  *) echo "Unsupported NDK host" >&2; exit 1 ;;
esac
linker="$ndk_root/toolchains/llvm/prebuilt/$host_tag/bin/aarch64-linux-android34-clang"
test -x "$linker"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$linker"
export CC_AARCH64_LINUX_ANDROID="$linker"
export AR_AARCH64_LINUX_ANDROID="$ndk_root/toolchains/llvm/prebuilt/$host_tag/bin/llvm-ar"
export CARGO_NET_GIT_FETCH_WITH_CLI=true
export GIT_CONFIG_COUNT=1
export GIT_CONFIG_KEY_0="url.file://$manifold_root/.insteadOf"
export GIT_CONFIG_VALUE_0="https://github.com/MesmerPrism/rusty-manifold.git"

cargo build \
  --locked \
  --manifest-path "$native_root/Cargo.toml" \
  --target aarch64-linux-android \
  --release

output_root="${1:-$app_root/app/build/generated/native-jniLibs}"
mkdir -p "$output_root/arm64-v8a"
cp \
  "$native_root/target/aarch64-linux-android/release/librusty_quest_spatial_video_local_control.so" \
  "$output_root/arm64-v8a/librusty_quest_spatial_video_local_control.so"
printf 'native_local_control_revision=%s\n' "$head"
printf 'native_local_control_tree=%s\n' "$tree"
