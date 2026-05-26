plugins {
    application
    id("dev.sweety.nativegen")
    id("dev.sweety.nativegen.cpp")
    id("dev.sweety.nativegen.rust")
}

application {
    mainClass.set("dev.sweety.example.mathops.Main")
}
