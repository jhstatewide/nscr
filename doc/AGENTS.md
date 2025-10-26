# NSCR

## Overview

This project (New and Shiny Container Registry) is a Kotlin program that has it as a goal to be an OCI compliant container registry, meaning OCI-compliant container runtimes
and things compliant with it can push, pull images from it.

We use H2 SQL as a backend datastore.

This project uses Gradle Kotlin DSL, meaning our build file is build.gradle.kts

We also have tests baked in, so you can do "./gradlew test" and run the tests.
