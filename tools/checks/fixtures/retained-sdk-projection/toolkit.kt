package com.meta.spatial.toolkit
class Transform(val transform:com.meta.spatial.core.Pose)
class PanelDimensions(val v:com.meta.spatial.core.Vector2)
class Visible(val v:Boolean)
class Hittable(val v:MeshCollision)
enum class MeshCollision{NoCollision}
