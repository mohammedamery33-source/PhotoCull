package com.photocull

import android.net.Uri

data class Photo(
    val uri: Uri,
    val parent: Uri,
    val name: String,
    val mime: String,
    val modified: Long
)

data class Analysis(
    val sharpness: Float,
    val brightness: Float,
    val faces: Int,
    val closedFaces: Int,
    val hash: Long,
    val group: Int = -1,
    val bestInGroup: Boolean = true
)

enum class Flag(val label: String) {
    BLUR("مهزوزة"),
    CLOSED("عين مقفولة"),
    DARK("غامقة"),
    BRIGHT("فاتحة زيادة"),
    SIMILAR("مكررة")
}

enum class Filter(val label: String) {
    ALL("الكل"),
    PROBLEMS("فيها مشاكل"),
    BLUR("مهزوزة"),
    CLOSED("عين مقفولة"),
    EXPOSURE("إضاءة"),
    SIMILAR("مكررة"),
    GOOD("سليمة")
}
