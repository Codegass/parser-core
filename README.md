# Parser Core

This project provides core parsing functionalities.

## How to Use in Other Gradle Projects

To include this project as a dependency in your Gradle project, you can use [JitPack](https://jitpack.io/).

1.  **Add JitPack to your repositories**

    Add the JitPack repository to your `settings.gradle(.kts)` file (recommended for Gradle 7.0+):

    ```gradle
    // settings.gradle.kts
    dependencyResolutionManagement {
        repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
        repositories {
            mavenCentral()
            maven { url = uri("https://jitpack.io") }
        }
    }
    ```

    Or, if you are using an older Gradle version, add it to your root `build.gradle(.kts)` file:

    ```gradle
    // build.gradle.kts (Groovy: build.gradle)
    allprojects {
        repositories {
            mavenCentral()
            maven { url = uri("https://jitpack.io") }
        }
    }
    ```

2.  **Add the dependency**

    Add the following to your module's `build.gradle(.kts)` file:

    ```gradle
    dependencies {
        // Replace 'BRANCH_NAME', 'TAG_NAME', or 'COMMIT_HASH' with the desired version
        // For example: 'main-SNAPSHOT', 'v1.0.0', or a specific commit hash
        implementation("com.github.Codegass:parser-core:COMMIT_HASH")
    }
    ```

    *   To use the latest commit on a branch (e.g., `main`): `implementation("com.github.Codegass:parser-core:main-SNAPSHOT")`
    *   To use a specific tag (e.g., `v1.0.0`): `implementation("com.github.Codegass:parser-core:v1.0.0")`
    *   To use a specific commit hash: `implementation("com.github.Codegass:parser-core:YOUR_COMMIT_HASH")`

    **Note:** Using `-SNAPSHOT` versions will always pull the latest commit from the specified branch. For stable builds, it's recommended to use tags or specific commit hashes. JitPack uses the `git@github.com:Codegass/parser-core.git` URL you provided implicitly when you specify `com.github.Codegass:parser-core`. 