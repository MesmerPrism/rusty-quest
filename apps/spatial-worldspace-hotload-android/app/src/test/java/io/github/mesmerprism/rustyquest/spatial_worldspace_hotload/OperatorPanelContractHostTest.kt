package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload

import com.meta.spatial.runtime.PanelShapeConfig
import com.meta.spatial.runtime.PanelShapeType

/** Execute the real pinned SDK's default shape against our consumer admission. */
object OperatorPanelContractHostTest {
    @JvmStatic fun main(args:Array<String>) {
        val shape=PanelShapeConfig()
        check(shape.pivotOffsetWidth==.5f&&shape.pivotOffsetHeight==.5f)
        SdkOperatorGeometry.requireCenteredQuad(shape.panelShapeType==PanelShapeType.QUAD,shape.pivotOffsetWidth,shape.pivotOffsetHeight)
        println("Operator real SDK centered-quad contract passed: 2")
    }
}
