package dev.sweety

enum class Backend(val libName: String) {
    CPP("native_cpp"),
    RUST("native_rust");

    companion object {
        fun fromProperty(): Backend =
            if (System.getProperty("jni.backend", "cpp").lowercase() == "rust") RUST else CPP
    }
}
