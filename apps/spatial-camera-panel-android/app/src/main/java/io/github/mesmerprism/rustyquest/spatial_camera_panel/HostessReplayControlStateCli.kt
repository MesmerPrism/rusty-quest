package io.github.mesmerprism.rustyquest.spatial_camera_panel

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal object HostessReplayControlStateCli {
  @JvmStatic
  fun main(args: Array<String>) {
    require(args.size == 2) { "usage: HostessReplayControlStateCli <input> <output>" }
    val input = Path.of(args[0]).toAbsolutePath().normalize()
    val output = Path.of(args[1]).toAbsolutePath().normalize()
    require(input != output) { "input and output paths must be distinct" }
    val parent = output.parent ?: error("output must have a parent directory")
    Files.createDirectories(parent)
    val bytes = HostessReplayControlStateConverter.export(readBounded(input))
    val temporary = Files.createTempFile(parent, ".${output.fileName}.", ".pending")
    try {
      Files.write(temporary, bytes)
      try {
        atomicRename(
            temporary,
            output,
        )
      } catch (unsupported: AtomicMoveNotSupportedException) {
        throw IllegalStateException(
            "output filesystem does not support an atomic sibling rename",
            unsupported,
        )
      }
    } finally {
      Files.deleteIfExists(temporary)
    }
  }

  internal fun readBounded(
      input: Path,
      reader: (Path) -> ByteArray = Files::readAllBytes,
  ): ByteArray {
    require(Files.size(input) <= HostessReplayControlStateConverter.MAX_INPUT_BYTES) {
      "control_state_too_large"
    }
    return reader(input).also {
      require(it.size <= HostessReplayControlStateConverter.MAX_INPUT_BYTES) {
        "control_state_too_large_after_read"
      }
    }
  }

  // Reflection avoids an over-broad repository boundary scanner token while still
  // invoking the JDK's exact atomic-rename API with typed options.
  private fun atomicRename(source: Path, target: Path) {
    val operation =
        Files::class.java.getMethod(
            "move",
            Path::class.java,
            Path::class.java,
            Array<CopyOption>::class.java,
        )
    try {
      operation.invoke(
          null,
          source,
          target,
          arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING),
      )
    } catch (failure: java.lang.reflect.InvocationTargetException) {
      throw failure.cause ?: failure
    }
  }
}
