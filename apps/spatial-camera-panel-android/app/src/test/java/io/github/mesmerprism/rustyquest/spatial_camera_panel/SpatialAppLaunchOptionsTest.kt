package io.github.mesmerprism.rustyquest.spatial_camera_panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SpatialAppLaunchOptionsTest {
  @Test
  fun acceptsOnlyBoundedUniqueAppOwnedOptions() {
    val options =
        listOf(
            SpatialAppLaunchOption("locked-sequence-1", "Sequence one", "Resolved app option"),
            SpatialAppLaunchOption("locked-sequence-2", "Sequence two", "Resolved app option"),
        )

    assertEquals(options, SpatialAppLaunchOptionsContract.validate(options))
  }

  @Test
  fun rejectsDuplicateAndOversizedFields() {
    val option = SpatialAppLaunchOption("one", "One", "Resolved app option")
    assertThrows(IllegalArgumentException::class.java) {
      SpatialAppLaunchOptionsContract.validate(listOf(option, option))
    }
    assertThrows(IllegalArgumentException::class.java) {
      SpatialAppLaunchOptionsContract.validate(
          listOf(option.copy(optionId = "x".repeat(SpatialAppLaunchOptionsContract.MAX_OPTION_ID_LENGTH + 1)))
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      SpatialAppLaunchOptionsContract.validate(
          listOf(
              option.copy(
                  displayLabel =
                      "x".repeat(SpatialAppLaunchOptionsContract.MAX_LABEL_LENGTH + 1)
              )
          )
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      SpatialAppLaunchOptionsContract.validate(
          listOf(
              option.copy(
                  description =
                      "x".repeat(SpatialAppLaunchOptionsContract.MAX_DESCRIPTION_LENGTH + 1)
              )
          )
      )
    }
  }
}
