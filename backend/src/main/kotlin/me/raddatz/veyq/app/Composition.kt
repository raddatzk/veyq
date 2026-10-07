package me.raddatz.veyq.app

// The root holds no code of its own: it composes `:core` and the applications (`:apps:memories`, `:apps:files`) into the one application. This file
// exists only because `./gradlew quarkusDev` refuses a project without a source file in `src/main/kotlin` ("At
// least one source directory should contain sources before starting Quarkus in dev mode"), and dev mode is run from
// this project (`.run/Run Backend.run.xml`, backend/CLAUDE.md). Keep it empty of declarations: code belongs in the
// module whose behaviour it is.
