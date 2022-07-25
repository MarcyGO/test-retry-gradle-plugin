Usage:
============

1. Install the plugin to the Maven local repository, and get the version, which is based on the commit SHA:
    ```
    git clone https://github.com/MarcyGO/test-retry-gradle-plugin.git
    git checkout nondex
    ver=$(./gradlew publishToMavenLocal | grep version | grep -o '[[:digit:]].*$')
    echo $ver
    ```
2. Add the following content into your build.gradle, and replace {ver} with the output of the above command):
    ```
    buildscript {
        repositories {
            mavenLocal()
        }
        dependencies {
            classpath "org.gradle:test-retry-gradle-plugin:{ver}"
        }
    }

    apply plugin: "org.gradle.test-retry"
    ```
3. Run NonDex on your project:
    ```
    ./gradlew test
4. You can also add parameters:
    ```
    ./gradlew test -DnondexRuns=10 -DnondexSeed=1234
