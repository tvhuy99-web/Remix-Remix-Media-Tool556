package com.example.navigation

sealed class Route(val path: String) {
    object Main : Route("main")
    object Record : Route("record")
    object Trim : Route("trim")
    object Join : Route("join")
    object Mix : Route("mix")
    object Img2Vid : Route("img2vid")
    object Sub : Route("sub")
    object Stem : Route("stem")
    object Other : Route("other")
    object Settings : Route("settings")
}
