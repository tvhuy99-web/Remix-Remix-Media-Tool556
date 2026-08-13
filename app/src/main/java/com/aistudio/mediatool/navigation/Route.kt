package com.aistudio.mediatool.navigation

sealed class Route(val path: String) {
    object Main : Route("main")
    object StudioProjects : Route("studio")
    object StudioProject : Route("studio/project/{projectId}") {
        fun create(projectId: String): String = "studio/project/$projectId"
    }
    object StudioLab : Route("studio/lab/{projectId}") {
        fun create(projectId: String): String = "studio/lab/$projectId"
    }
    object Record : Route("record")
    object Trim : Route("trim")
    object Join : Route("join")
    object Mix : Route("mix")
    object Img2Vid : Route("img2vid")
    object Sub : Route("sub")
    object Stem : Route("stem")
    object VoiceCleanup : Route("voice_cleanup")
    object Other : Route("other")
    object Settings : Route("settings")
}
