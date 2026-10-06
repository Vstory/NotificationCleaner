package io.github.vstory.hook.notifyfilter.data

enum class FloatingBottomBarStyle(val storageValue: String) {
    Miuix("miuix"),
    IosLike("ios_like");

    companion object {
        fun fromStorage(value: String): FloatingBottomBarStyle =
            entries.firstOrNull { it.storageValue == value } ?: Miuix
    }
}

enum class BottomBarMode(val storageValue: String) {
    IconAndText("icon_and_text"),
    IconOnly("icon_only");

    companion object {
        fun fromStorage(value: String): BottomBarMode =
            entries.firstOrNull { it.storageValue == value } ?: IconAndText
    }
}
