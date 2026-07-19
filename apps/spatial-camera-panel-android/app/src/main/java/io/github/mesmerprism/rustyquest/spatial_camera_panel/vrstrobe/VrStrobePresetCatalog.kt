package io.github.mesmerprism.rustyquest.spatial_camera_panel.vrstrobe

internal sealed interface VrStrobePortalPreset {
  val id: String
  val title: String
  val sourceLabel: String
}

internal data class VrStrobeInterferencePreset(
    override val id: String,
    override val title: String,
    override val sourceLabel: String,
    val profile: VrStrobeInterferenceProfile,
) : VrStrobePortalPreset

internal data class VrStrobeTemporalPreset(
    override val id: String,
    override val title: String,
    override val sourceLabel: String,
    val profile: VrStrobeTemporalProfile,
) : VrStrobePortalPreset

internal object VrStrobePresetCatalog {
  const val UPSTREAM_REPOSITORY = "https://github.com/trevorhewitt/vr_strobe"
  const val UPSTREAM_COMMIT = "52c71cc069f4102bc4148e05c5fd3fc4d5466479"

  val interference: List<VrStrobeInterferencePreset> by lazy {
    listOf(
        interferencePreset(
            id = "source-sim-7hz-red",
            title = "Red interference preset",
            sourceLabel = "Simulated 7 Hz Closed Eye Lucia",
            payload = "eyJjIjp7ImNvbG9yQ291bnQiOjIsImNvbDEiOiIjMDAwMDAwIiwiY29sMiI6IiNmZjAwMDAiLCJjb2wzIjoiIzAwYWFmZiJ9LCJhIjp7Im9zY0FjdGl2ZSI6MSwib3NjRnJlcSI6MzMuNzUsIm9zY1NoYXBlIjoyLjF9LCJnIjp7InNjYWxlIjoyNC45LCJzaGVhclgiOjAsInNoZWFyWSI6MCwib2Zmc2V0WCI6MC42OTcsIm9mZnNldFkiOi0wLjY3Miwic2hha2VBbXAiOjAuMDA2LCJzaGFrZUZyZXEiOjUwLCJyb3RTcGVlZCI6MS4zNSwic3RlcEZhY3RvciI6MC4xOTF9LCJwIjp7InRyYWlsQW1vdW50IjowLjU3LCJibHVyUmFkaXVzIjo5LjIsImdsb3dTdHJlbmd0aCI6MCwiYnJpZ2h0bmVzcyI6LTAuMDUsImNvbnRyYXN0IjoxLjA1fSwiZSI6eyJub2lzZUZyZXEiOjQuNTk4LCJub2lzZVN0cmVuZ3RoIjowLjU2LCJub2lzZUJpYXMiOjAuNSwidmlnQ2VudGVyIjo1LCJ2aWdFZGdlIjo1LCJ2aWdCaWFzIjowLjE4Nn0sInMiOlt7ImFjdGl2ZSI6MSwic3RyZW5ndGgiOjAuOCwicGVyaW9kIjozLjIsInNwZWVkIjoxLCJwaXZvdFgiOjAsInBpdm90WSI6MCwiZGlzdG9ydEZyZXEiOjAsImRpc3RvcnRBbXAiOjAsImRpc3RvcnRTcGVlZCI6MSwiZGlzdE11bHRQYXIiOjEsImRpc3RNdWx0T3J0aCI6MSwid2F2ZUZyZXEiOjEuMiwid2F2ZUFtcCI6MC4wOCwid2F2ZVNoYXBlIjoxLCJhbmdsZSI6My4wMDIsInJvdFNwZWVkIjotMC40OTksImV4dGVudCI6MH1dLCJyIjpbeyJhY3RpdmUiOjEsInN0cmVuZ3RoIjowLjgsInBlcmlvZCI6My4yLCJzcGVlZCI6MSwicGl2b3RYIjowLCJwaXZvdFkiOjAsImRpc3RvcnRGcmVxIjowLCJkaXN0b3J0QW1wIjowLCJkaXN0b3J0U3BlZWQiOjEsImRpc3RNdWx0UGFyIjoxLCJkaXN0TXVsdE9ydGgiOjEsIndhdmVGcmVxIjoxLjIsIndhdmVBbXAiOjAuMDgsIndhdmVTaGFwZSI6MSwicm90UGl2WCI6LTAuNywicm90UGl2WSI6LTEuMzU3LCJyb3RTcGVlZCI6MS4xNiwibm9pc2VNb3ZlIjoxLjIxfV0sInkiOlt7ImFjdGl2ZSI6MSwic3RyZW5ndGgiOjAuOCwicGVyaW9kIjo1MCwic3BlZWQiOjEsInBpdm90WCI6MCwicGl2b3RZIjowLCJkaXN0b3J0RnJlcSI6MCwiZGlzdG9ydEFtcCI6MCwiZGlzdG9ydFNwZWVkIjoxLCJkaXN0TXVsdFBhciI6MSwiZGlzdE11bHRPcnRoIjoxLCJ3YXZlRnJlcSI6MS4yLCJ3YXZlQW1wIjowLjA4LCJ3YXZlU2hhcGUiOjEsInJvdFBpdlgiOi0wLjU5LCJyb3RQaXZZIjotMC4wMiwicm90U3BlZWQiOjAsIm5vaXNlTW92ZSI6MH1dLCJuIjpbXX0=",
        ),
        interferencePreset(
            id = "source-sim-40hz-pale",
            title = "Pale three-color interference preset",
            sourceLabel = "Simulated 40 Hz Open Eye Roxiva",
            payload = "eyJjIjp7ImNvbG9yQ291bnQiOjMsImNvbDEiOiIjMDAwMDAwIiwiY29sMiI6IiNkYmY4ZmYiLCJjb2wzIjoiI2ZmZmZmZiJ9LCJhIjp7Im9zY0FjdGl2ZSI6MSwib3NjRnJlcSI6NDAsIm9zY1NoYXBlIjozLjJ9LCJnIjp7InNjYWxlIjoxMC43LCJzaGVhclgiOjAsInNoZWFyWSI6MC4wMiwib2Zmc2V0WCI6MCwib2Zmc2V0WSI6MCwic2hha2VBbXAiOjAuMDEsInNoYWtlRnJlcSI6NDAsInJvdFNwZWVkIjowLjIxLCJzdGVwRmFjdG9yIjowLjE5MX0sInAiOnsidHJhaWxBbW91bnQiOjAuNjcsImJsdXJSYWRpdXMiOjIuOCwiZ2xvd1N0cmVuZ3RoIjowLCJicmlnaHRuZXNzIjowLjM0LCJjb250cmFzdCI6MC42Mn0sImUiOnsibm9pc2VGcmVxIjoyLjgsIm5vaXNlU3RyZW5ndGgiOjAuMzIsIm5vaXNlQmlhcyI6MC41LCJ2aWdDZW50ZXIiOjAuMDUsInZpZ0VkZ2UiOjEuODMsInZpZ0JpYXMiOjB9LCJzIjpbeyJhY3RpdmUiOjEsInN0cmVuZ3RoIjotMC43LCJwZXJpb2QiOjIxLCJzcGVlZCI6Ny41OTIsImFuZ2xlIjozLjAwMiwicm90U3BlZWQiOi0wLjQ5OSwicGl2b3RYIjotMS45NDQsInBpdm90WSI6MC4zOTYsImV4dGVudCI6OX0seyJhY3RpdmUiOjEsInN0cmVuZ3RoIjoxLjMsInBlcmlvZCI6MTMuMiwic3BlZWQiOjIsImFuZ2xlIjowLCJyb3RTcGVlZCI6MCwicGl2b3RYIjotMC44LCJwaXZvdFkiOi0xLjIyLCJleHRlbnQiOjB9XSwiciI6W3siYWN0aXZlIjoxLCJzdHJlbmd0aCI6MC40LCJwZXJpb2QiOjEyLjgsInNwZWVkIjo2LjIsInBpdm90WCI6LTAuMDIsInBpdm90WSI6LTEuMTQ2LCJyb3RQaXZYIjotMC43LCJyb3RQaXZZIjotMS4zNTcsInJvdFNwZWVkIjoxLjE2LCJub2lzZU1vdmUiOjEuMjF9XSwieSI6W3siYWN0aXZlIjoxLCJzdHJlbmd0aCI6MC44LCJwZXJpb2QiOjEwNSwic3BlZWQiOjEsInBpdm90WCI6MCwicGl2b3RZIjowLCJyb3RQaXZYIjowLCJyb3RQaXZZIjowLCJyb3RTcGVlZCI6MCwibm9pc2VNb3ZlIjowfV19",
        ),
        interferencePreset(
            id = "source-sim-red",
            title = "Red motion preset",
            sourceLabel = "Simulated red",
            payload = "eyJjIjp7ImNvbG9yQ291bnQiOjIsImNvbDEiOiIjMDAwMDAwIiwiY29sMiI6IiNmZjAwMDAiLCJjb2wzIjoiIzAwYWFmZiJ9LCJhIjp7Im9zY0FjdGl2ZSI6MSwib3NjRnJlcSI6NDAsIm9zY1NoYXBlIjowLjF9LCJnIjp7InNjYWxlIjo0OC4xLCJzaGVhclgiOjAsInNoZWFyWSI6MCwib2Zmc2V0WCI6MCwib2Zmc2V0WSI6MCwic2hha2VBbXAiOjAsInNoYWtlRnJlcSI6NDAsInJvdFNwZWVkIjowLjMyLCJzdGVwRmFjdG9yIjowfSwicCI6eyJ0cmFpbEFtb3VudCI6MCwiYmx1clJhZGl1cyI6MCwiZ2xvd1N0cmVuZ3RoIjowLCJicmlnaHRuZXNzIjowLCJjb250cmFzdCI6MC43Mn0sImUiOnsibm9pc2VGcmVxIjoxLCJub2lzZVN0cmVuZ3RoIjowLCJub2lzZUJpYXMiOjAuNSwidmlnQ2VudGVyIjowLCJ2aWdFZGdlIjoxLjkxLCJ2aWdCaWFzIjowfSwicyI6W3siYWN0aXZlIjoxLCJzdHJlbmd0aCI6MC44LCJwZXJpb2QiOjEwLCJzcGVlZCI6LTEuMywicGl2b3RYIjowLCJwaXZvdFkiOjAsImRpc3RvcnRGcmVxIjoxLCJkaXN0b3J0QW1wIjoxLjY4LCJkaXN0b3J0U3BlZWQiOjEsImRpc3RNdWx0UGFyIjowLjQsImRpc3RNdWx0T3J0aCI6MSwid2F2ZUZyZXEiOjYuOCwid2F2ZUFtcCI6My45Nywid2F2ZVNoYXBlIjowLCJhbmdsZSI6MCwicm90U3BlZWQiOjEuMTgsImV4dGVudCI6OH0seyJhY3RpdmUiOjEsInN0cmVuZ3RoIjowLjgsInBlcmlvZCI6MTAsInNwZWVkIjotMS4zLCJwaXZvdFgiOjAsInBpdm90WSI6MCwiZGlzdG9ydEZyZXEiOjEsImRpc3RvcnRBbXAiOjEuNjgsImRpc3RvcnRTcGVlZCI6MSwiZGlzdE11bHRQYXIiOjAuNCwiZGlzdE11bHRPcnRoIjoxLCJ3YXZlRnJlcSI6Ni44LCJ3YXZlQW1wIjozLjk3LCJ3YXZlU2hhcGUiOjAsImFuZ2xlIjowLCJyb3RTcGVlZCI6MC40MSwiZXh0ZW50IjoxNH1dLCJyIjpbeyJhY3RpdmUiOjEsInN0cmVuZ3RoIjowLjgsInBlcmlvZCI6MTAsInNwZWVkIjotMS4zLCJwaXZvdFgiOjAsInBpdm90WSI6MCwiZGlzdG9ydEZyZXEiOjEsImRpc3RvcnRBbXAiOjEuNjgsImRpc3RvcnRTcGVlZCI6MSwiZGlzdE11bHRQYXIiOjAuNCwiZGlzdE11bHRPcnRoIjoxLCJ3YXZlRnJlcSI6Ni44LCJ3YXZlQW1wIjozLjk3LCJ3YXZlU2hhcGUiOjAsInJvdFBpdlgiOjAsInJvdFBpdlkiOjAsInJvdFNwZWVkIjotMC40OCwibm9pc2VNb3ZlIjowfV0sInkiOltdLCJuIjpbeyJhY3RpdmUiOjEsInN0cmVuZ3RoIjoyLCJzY2FsZSI6Ni4zLCJ6U3BlZWQiOjEwLCJ6T2Zmc2V0IjotODIuMSwicGl2b3RYIjotMC40OSwicGl2b3RZIjowfV19",
        ),
        interferencePreset(
            id = "source-sim-blorbs",
            title = "Blorbs preset",
            sourceLabel = "Simulated blorbs",
            payload = "eyJjIjp7ImNvbG9yQ291bnQiOjIsImNvbDEiOiIjMDAwMDAwIiwiY29sMiI6IiNmZjAwMDAiLCJjb2wzIjoiIzAwYWFmZiJ9LCJhIjp7Im9zY0FjdGl2ZSI6MCwib3NjRnJlcSI6MTYuNDg4LCJvc2NTaGFwZSI6Ny44MjJ9LCJnIjp7InNjYWxlIjowLjY2Miwic2hlYXJYIjowLCJzaGVhclkiOjAsIm9mZnNldFgiOi0wLjcwMiwib2Zmc2V0WSI6LTAuODgxLCJzaGFrZUFtcCI6MCwic2hha2VGcmVxIjoxMi41MzksInJvdFNwZWVkIjoyLjAyNSwic3RlcEZhY3RvciI6MC43OTN9LCJwIjp7InRyYWlsQW1vdW50IjowLjI4OSwiYmx1clJhZGl1cyI6MCwiZ2xvd1N0cmVuZ3RoIjowLjA0NSwiYnJpZ2h0bmVzcyI6LTAuNTI0LCJjb250cmFzdCI6MC43NDd9LCJlIjp7Im5vaXNlRnJlcSI6MS44MTEsIm5vaXNlU3RyZW5ndGgiOjAuOTU2LCJub2lzZUJpYXMiOjAuMjM0LCJ2aWdDZW50ZXIiOjAuNDIyLCJ2aWdFZGdlIjo1LCJ2aWdCaWFzIjowLjM1fSwicyI6W3siYWN0aXZlIjoxLCJzdHJlbmd0aCI6LTAuNjQxLCJwZXJpb2QiOjMwLjYxNiwic3BlZWQiOi0wLjkxLCJwaXZvdFgiOjEuNzYxLCJwaXZvdFkiOi0xLjM1OCwiZGlzdG9ydEZyZXEiOjE3LjcwNiwiZGlzdG9ydEFtcCI6MCwiZGlzdG9ydFNwZWVkIjotOC4yNywiZGlzdE11bHRQYXIiOjMuMjcyLCJkaXN0TXVsdE9ydGgiOjQuMDQzLCJ3YXZlRnJlcSI6MTIuMDYyLCJ3YXZlQW1wIjowLjY4OCwid2F2ZVNoYXBlIjowLjk4NSwiYW5nbGUiOjAuNzgzLCJyb3RTcGVlZCI6LTEuMTA2LCJleHRlbnQiOjB9LHsiYWN0aXZlIjoxLCJzdHJlbmd0aCI6LTAuNjQxLCJwZXJpb2QiOjMwLjYxNiwic3BlZWQiOi0wLjkxLCJwaXZvdFgiOjEuNzYxLCJwaXZvdFkiOi0xLjM1OCwiZGlzdG9ydEZyZXEiOjE3LjcwNiwiZGlzdG9ydEFtcCI6MCwiZGlzdG9ydFNwZWVkIjotOC4yNywiZGlzdE11bHRQYXIiOjMuMjcyLCJkaXN0TXVsdE9ydGgiOjQuMDQzLCJ3YXZlRnJlcSI6MTIuMDYyLCJ3YXZlQW1wIjowLjY4OCwid2F2ZVNoYXBlIjowLjk4NSwiYW5nbGUiOjEuNDgyLCJyb3RTcGVlZCI6MC4yNDgsImV4dGVudCI6MH1dLCJyIjpbeyJhY3RpdmUiOjEsInN0cmVuZ3RoIjotMC42NDEsInBlcmlvZCI6MzAuNjE2LCJzcGVlZCI6LTAuOTEsInBpdm90WCI6MS43NjEsInBpdm90WSI6LTEuMzU4LCJkaXN0b3J0RnJlcSI6MTcuNzA2LCJkaXN0b3J0QW1wIjowLCJkaXN0b3J0U3BlZWQiOi04LjI3LCJkaXN0TXVsdFBhciI6My4yNzIsImRpc3RNdWx0T3J0aCI6NC4wNDMsIndhdmVGcmVxIjoxMi4wNjIsIndhdmVBbXAiOjAuNjg4LCJ3YXZlU2hhcGUiOjAuOTg1LCJyb3RQaXZYIjoxLjM4Mywicm90UGl2WSI6MS41NDQsInJvdFNwZWVkIjotMC40NzksIm5vaXNlTW92ZSI6MS4zMTV9XSwieSI6W10sIm4iOlt7ImFjdGl2ZSI6MSwic3RyZW5ndGgiOjEuODEzLCJzY2FsZSI6MjQuNzIzLCJ6U3BlZWQiOjcuMjM1LCJ6T2Zmc2V0IjotNDIuNTYyLCJwaXZvdFgiOjEuMTA3LCJwaXZvdFkiOjAuNjA0fV19",
        ),
        interferencePreset(
            id = "source-sim-shlorgs",
            title = "Shlorgs preset",
            sourceLabel = "Simulated shlorgs",
            payload = "eyJjIjp7ImNvbG9yQ291bnQiOjMsImNvbDEiOiIjYjViNWI1IiwiY29sMiI6IiNmZjAwMDAiLCJjb2wzIjoiIzAwMDU5NCJ9LCJhIjp7Im9zY0FjdGl2ZSI6MSwib3NjRnJlcSI6MjUuOTksIm9zY1NoYXBlIjo0LjJ9LCJnIjp7InNjYWxlIjo0LjE3OSwic2hlYXJYIjowLCJzaGVhclkiOjAsIm9mZnNldFgiOjAuMDU5LCJvZmZzZXRZIjowLjM2NSwic2hha2VBbXAiOjAsInNoYWtlRnJlcSI6OC4yNDMsInJvdFNwZWVkIjoxLjY2OSwic3RlcEZhY3RvciI6MC4zMDR9LCJwIjp7InRyYWlsQW1vdW50IjowLCJibHVyUmFkaXVzIjowLCJnbG93U3RyZW5ndGgiOjAsImJyaWdodG5lc3MiOi0wLjI3NywiY29udHJhc3QiOjAuNDk3fSwiZSI6eyJub2lzZUZyZXEiOjEuNjQ3LCJub2lzZVN0cmVuZ3RoIjowLjg1Miwibm9pc2VCaWFzIjowLjE0OSwidmlnQ2VudGVyIjo1LCJ2aWdFZGdlIjo1LCJ2aWdCaWFzIjowLjY4N30sInMiOlt7ImFjdGl2ZSI6MSwic3RyZW5ndGgiOi0xLjcyOSwicGVyaW9kIjozLjU0NCwic3BlZWQiOjcuMzA0LCJwaXZvdFgiOi0xLjc5LCJwaXZvdFkiOjEuNjQ5LCJkaXN0b3J0RnJlcSI6Mi4yODEsImRpc3RvcnRBbXAiOjAsImRpc3RvcnRTcGVlZCI6LTUuNTEsImRpc3RNdWx0UGFyIjozLjAzNCwiZGlzdE11bHRPcnRoIjoxLjAzNywid2F2ZUZyZXEiOjE5LjY0OCwid2F2ZUFtcCI6MS45MTQsIndhdmVTaGFwZSI6MC4zMDksImFuZ2xlIjoyLjc0MSwicm90U3BlZWQiOjEuMDkxLCJleHRlbnQiOjEwfSx7ImFjdGl2ZSI6MSwic3RyZW5ndGgiOi0xLjcyOSwicGVyaW9kIjozLjU0NCwic3BlZWQiOjcuMzA0LCJwaXZvdFgiOi0xLjc5LCJwaXZvdFkiOjEuNjQ5LCJkaXN0b3J0RnJlcSI6Mi4yODEsImRpc3RvcnRBbXAiOjAsImRpc3RvcnRTcGVlZCI6LTUuNTEsImRpc3RNdWx0UGFyIjozLjAzNCwiZGlzdE11bHRPcnRoIjoxLjAzNywid2F2ZUZyZXEiOjE5LjY0OCwid2F2ZUFtcCI6MS45MTQsIndhdmVTaGFwZSI6MC4zMDksImFuZ2xlIjo1LjUyNiwicm90U3BlZWQiOi0xLjQzMSwiZXh0ZW50IjowfV0sInIiOlt7ImFjdGl2ZSI6MSwic3RyZW5ndGgiOi0xLjcyOSwicGVyaW9kIjozLjU0NCwic3BlZWQiOjcuMzA0LCJwaXZvdFgiOi0xLjc5LCJwaXZvdFkiOjEuNjQ5LCJkaXN0b3J0RnJlcSI6Mi4yODEsImRpc3RvcnRBbXAiOjAsImRpc3RvcnRTcGVlZCI6LTUuNTEsImRpc3RNdWx0UGFyIjozLjAzNCwiZGlzdE11bHRPcnRoIjoxLjAzNywid2F2ZUZyZXEiOjE5LjY0OCwid2F2ZUFtcCI6MS45MTQsIndhdmVTaGFwZSI6MC4zMDksInJvdFBpdlgiOi0wLjI5OCwicm90UGl2WSI6MS44MDUsInJvdFNwZWVkIjotMS40ODEsIm5vaXNlTW92ZSI6MC4yNjh9XSwieSI6W10sIm4iOlt7ImFjdGl2ZSI6MSwic3RyZW5ndGgiOjEuMzc5LCJzY2FsZSI6NC44MTQsInpTcGVlZCI6OC4xODQsInpPZmZzZXQiOjAuNjM5LCJwaXZvdFgiOjEuMzU2LCJwaXZvdFkiOi0xLjE4M31dfQ==",
        ),
    )
  }

  val temporal: List<VrStrobeTemporalPreset> =
      listOf(
          temporalPreset("source-strobe-7hz", "7 Hz black/white", "Real 7 Hz Black & White Strobe", frequency = 7f),
          temporalPreset(
              "source-strobe-14hz-noise",
              "14 Hz noise-phase black/white",
              "Real 14 Hz Noistrobe",
              frequency = 14f,
              noisePhase1 = true,
              noiseAmplitude1 = 1f,
              fixationEnabled = true,
          ),
          temporalPreset("source-strobe-20hz", "20 Hz black/white", "Real 20 Hz Black & White Strobe", frequency = 20f),
          temporalPreset(
              "source-strobe-12hz-red",
              "12 Hz black/red",
              "Real 12 Hz Red Strobe",
              frequency = 12f,
              color2 = VrStrobeColor.RED,
          ),
      )

  val all: List<VrStrobePortalPreset>
    get() = interference + temporal

  private fun interferencePreset(
      id: String,
      title: String,
      sourceLabel: String,
      payload: String,
  ): VrStrobeInterferencePreset =
      VrStrobeInterferencePreset(
          id = id,
          title = title,
          sourceLabel = sourceLabel,
          profile =
              VrStrobeProfileCodec.decodeInterferencePayload(
                  id = id,
                  title = title,
                  sourceLabel = sourceLabel,
                  sourcePayload = payload,
              ),
      )

  private fun temporalPreset(
      id: String,
      title: String,
      sourceLabel: String,
      frequency: Float,
      color2: VrStrobeColor = VrStrobeColor.WHITE,
      noisePhase1: Boolean = false,
      noiseAmplitude1: Float = 0.2f,
      fixationEnabled: Boolean = false,
  ): VrStrobeTemporalPreset =
      VrStrobeTemporalPreset(
          id = id,
          title = title,
          sourceLabel = sourceLabel,
          profile =
              VrStrobeTemporalProfile(
                  id = id,
                  title = title,
                  sourceLabel = sourceLabel,
                  color2 = color2,
                  frequencyHz = frequency,
                  noisePhase1 = noisePhase1,
                  noiseAmplitude1 = noiseAmplitude1,
                  fixationEnabled = fixationEnabled,
              ),
      )
}
