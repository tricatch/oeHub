rootProject.name = "oeHub"

// oe-proxy is developed in the sibling ../oeProxy checkout. When it is present Gradle builds it
// and substitutes it for io.github.tricatch:oe-proxy; otherwise the published artifact is used.
if (file("../oeProxy").isDirectory) includeBuild("../oeProxy")
