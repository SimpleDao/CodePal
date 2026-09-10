plugins {
    id("java")
    id("org.jetbrains.intellij") version "1.17.4"

}

group = "com.codepal"
version = "2.2.1"

repositories {
    maven { url = uri("https://maven.aliyun.com/repository/public") }
    mavenCentral()
}

dependencies {
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("org.commonmark:commonmark:0.22.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.22.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.22.0")
    implementation("org.xerial:sqlite-jdbc:3.45.3.0") {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
    implementation("com.mysql:mysql-connector-j:8.4.0") {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

intellij {
    //localPath.set("E:/P-plugins/ideaIC-2023.2.5")
    version.set("2023.2.5")
    //localPath.set("D:/tools/I-IDEA/IntelliJ IDEA 2026.1")
    //version.set("2023.3.7")
    type.set("IC")
    //plugins.set(listOf())

    plugins.set(listOf("java"))

    // 不强制改写 since/until-build：打包后插件不带 until-build 上限，
    // 所有 ≥2023.2 的 IDE 都能安装（官方推荐的最广兼容做法）。
    updateSinceUntilBuild.set(false)
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
        options.encoding = "UTF-8"
    }

    patchPluginXml {
        sinceBuild.set("232")
    }

    // 关闭 gradle-intellij-plugin 的 GitHub 自更新检查：本环境无法稳定访问 GitHub，
    // 否则 initializeIntelliJPlugin 会因 getHeaderField("Location") 返回 null 抛 NPE。
    initializeIntelliJPlugin {
        selfUpdateCheck = false
    }

    signPlugin {
        // 离线签名：使用 libs/ 下的本地 marketplace-zip-signer-cli.jar，避免 signPlugin 去 GitHub
        // 下载签名器（本环境 GitHub 不可达，downloadZipSigner 会因解析 latest 版本而抛 NPE）。
        // 若 libs/ 下缺少该 jar，可手动获取：
        //   curl -o libs/marketplace-zip-signer-cli.jar https://maven.aliyun.com/repository/public/org/jetbrains/marketplace-zip-signer-cli/0.1.43/marketplace-zip-signer-cli-0.1.43.jar
        val zipSignerJar = file("libs/marketplace-zip-signer-cli.jar")
        cliPath.set(zipSignerJar.absolutePath)

        val certFile = file("chain.crt")
        val keyFile = file("private.pem")
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN") ?: certFile.takeIf { it.exists() }?.readText())
        privateKey.set(System.getenv("PRIVATE_KEY") ?: keyFile.takeIf { it.exists() }?.readText())
        password.set(System.getenv("PRIVATE_KEY_PASSWORD") ?: "")
    }

    // 关闭从 GitHub 下载签名器的任务（signPlugin 依赖它，禁用后会被跳过，改用上面的本地 jar）
    // 放在 afterEvaluate 里，确保晚于插件自身配置，禁用才不会被覆盖。
    afterEvaluate {
        tasks.getByName<Task>("downloadZipSigner").enabled = false
    }

    publishPlugin {
        // Token 读取顺序：环境变量 PUBLISH_TOKEN → 项目根目录 publish.properties。
        // 后者是本地私密文件（已在 .gitignore 忽略），这样 IDEA Gradle 面板
        // 双击 publishPlugin 就能直接发布，无需命令行设环境变量。
        token.set(
            providers.environmentVariable("PUBLISH_TOKEN")
                .orElse(providers.provider {
                    // 直接按 key=value 解析（build.gradle.kts 里 java.* 标识符有冲突，不用 Properties）
                    val f = rootProject.file("publish.properties")
                    if (f.exists()) {
                        f.readLines()
                            .firstOrNull { it.trim().startsWith("PUBLISH_TOKEN=") }
                            ?.substringAfter("PUBLISH_TOKEN=")
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                    } else null
                })
        )
    }
}
