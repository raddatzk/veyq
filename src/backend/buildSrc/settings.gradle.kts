// The root's version catalog, so `buildSrc` and the backend name every version in one file.
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}
