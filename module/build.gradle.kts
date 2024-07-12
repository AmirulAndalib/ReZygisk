import android.databinding.tool.ext.capitalizeUS
import java.security.MessageDigest
import org.apache.tools.ant.filters.ReplaceTokens

import org.apache.tools.ant.filters.FixCrLfFilter

import org.apache.commons.codec.binary.Hex
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.EdECPrivateKey
import java.security.interfaces.EdECPublicKey
import java.security.spec.EdECPrivateKeySpec
import java.security.spec.NamedParameterSpec
import java.util.TreeSet

plugins {
    alias(libs.plugins.agp.lib)
}

val moduleId: String by rootProject.extra
val moduleName: String by rootProject.extra
val verCode: Int by rootProject.extra
val verName: String by rootProject.extra
val minKsuVersion: Int by rootProject.extra
val minKsudVersion: Int by rootProject.extra
val maxKsuVersion: Int by rootProject.extra
val minMagiskVersion: Int by rootProject.extra
val commitHash: String by rootProject.extra

android.buildFeatures {
    androidResources = false
    buildConfig = false
}

androidComponents.onVariants { variant ->
    val variantLowered = variant.name.lowercase()
    val variantCapped = variant.name.capitalizeUS()
    val buildTypeLowered = variant.buildType?.lowercase()

    val moduleDir = layout.buildDirectory.dir("outputs/module/$variantLowered")
    val zipFileName = "$moduleName-$verName-$verCode-$commitHash-$buildTypeLowered.zip".replace(' ', '-')

    val prepareModuleFilesTask = task<Sync>("prepareModuleFiles$variantCapped") {
        group = "module"
        dependsOn(
            ":loader:assemble$variantCapped",
            ":zygiskd:buildAndStrip",
        )
        into(moduleDir)
        from("${rootProject.projectDir}/README.md")
        from("$projectDir/src") {
            exclude("module.prop", "customize.sh", "post-fs-data.sh", "service.sh", "mazoku")
            filter<FixCrLfFilter>("eol" to FixCrLfFilter.CrLf.newInstance("lf"))
        }
        from("$projectDir/src") {
            include("module.prop")
            expand(
                "moduleId" to moduleId,
                "moduleName" to moduleName,
                "versionName" to "$verName ($verCode-$commitHash-$variantLowered)",
                "versionCode" to verCode
            )
        }
        from("$projectDir/src/mazoku")
        from("$projectDir/src") {
            include("customize.sh", "post-fs-data.sh", "service.sh")
            val tokens = mapOf(
                "DEBUG" to if (buildTypeLowered == "debug") "true" else "false",
                "MIN_KSU_VERSION" to "$minKsuVersion",
                "MIN_KSUD_VERSION" to "$minKsudVersion",
                "MAX_KSU_VERSION" to "$maxKsuVersion",
                "MIN_MAGISK_VERSION" to "$minMagiskVersion",
            )
            filter<ReplaceTokens>("tokens" to tokens)
            filter<FixCrLfFilter>("eol" to FixCrLfFilter.CrLf.newInstance("lf"))
        }
        into("bin") {
            from(project(":zygiskd").layout.buildDirectory.file("rustJniLibs/android"))
            include("**/zygiskd")
        }
        into("lib") {
            from(project(":loader").layout.buildDirectory.file("intermediates/stripped_native_libs/$variantLowered/out/lib"))
        }
        into("webroot") {
            from("${rootProject.projectDir}/webroot")
        }

        val root = moduleDir.get()

        doLast {
            if (file("private_key").exists()) {
                println("=== Guards the peace of Machikado ===")
                val privateKey = file("private_key").readBytes()
                val publicKey = file("public_key").readBytes()
                val namedSpec = NamedParameterSpec("ed25519")
                val privKeySpec = EdECPrivateKeySpec(namedSpec, privateKey)
                val kf = KeyFactory.getInstance("ed25519")
                val privKey = kf.generatePrivate(privKeySpec);
                val sig = Signature.getInstance("ed25519")
                fun File.sha(realFile: File? = null) {
                    val path = this.path.replace("\\", "/")
                    sig.update(this.name.toByteArray())
                    sig.update(0) // null-terminated string
                    val real = realFile ?: this
                    val buffer = ByteBuffer.allocate(8)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putLong(real.length())
                        .array()
                    sig.update(buffer)
                    real.forEachBlock { bytes, size ->
                        sig.update(bytes, 0, size)
                    }
                }

                fun getSign(name: String, abi32: String, abi64: String) {
                    val set = TreeSet<Pair<File, File?>> { o1, o2 ->
                        o1.first.path.replace("\\", "/")
                            .compareTo(o2.first.path.replace("\\", "/"))
                    }
                    set.add(Pair(root.file("module.prop").asFile, null))
                    set.add(Pair(root.file("sepolicy.rule").asFile, null))
                    set.add(Pair(root.file("post-fs-data.sh").asFile, null))
                    set.add(Pair(root.file("service.sh").asFile, null))
                    set.add(Pair(root.file("mazoku").asFile, null))
                    set.add(
                        Pair(
                            root.file("lib/libzygisk.so").asFile,
                            root.file("lib/$abi32/libzygisk.so").asFile
                        )
                    )
                    set.add(
                        Pair(
                            root.file("lib64/libzygisk.so").asFile,
                            root.file("lib/$abi64/libzygisk.so").asFile
                        )
                    )
                    set.add(
                        Pair(
                            root.file("bin/zygisk-ptrace32").asFile,
                            root.file("lib/$abi32/libzygisk_ptrace.so").asFile
                        )
                    )
                    set.add(
                        Pair(
                            root.file("bin/zygisk-ptrace64").asFile,
                            root.file("lib/$abi64/libzygisk_ptrace.so").asFile
                        )
                    )
                    set.add(
                        Pair(
                            root.file("bin/zygiskd32").asFile,
                            root.file("bin/$abi32/zygiskd").asFile
                        )
                    )
                    set.add(
                        Pair(
                            root.file("bin/zygiskd64").asFile,
                            root.file("bin/$abi64/zygiskd").asFile
                        )
                    )
                    set.add(Pair(root.file("webroot/index.html").asFile, null))

                    set.add(Pair(root.file("webroot/js/main.js").asFile, null))
                    set.add(Pair(root.file("webroot/js/kernelsu.js").asFile, null))
                    set.add(Pair(root.file("webroot/js/theme.js").asFile, null))
                    set.add(Pair(root.file("webroot/js/language.js").asFile, null))
                    set.add(Pair(root.file("webroot/js/restoreError.js").asFile, null))
                    set.add(Pair(root.file("webroot/js/navbar.js").asFile, null))

                    set.add(Pair(root.file("webroot/js/translate/action.js").asFile, null))
                    set.add(Pair(root.file("webroot/js/translate/home.js").asFile, null))
                    set.add(Pair(root.file("webroot/js/translate/modules.js").asFile, null))
                    set.add(Pair(root.file("webroot/js/translate/settings.js").asFile, null))

                    set.add(Pair(root.file("webroot/js/themes/dark/index.js").asFile, null))
                    set.add(Pair(root.file("webroot/js/themes/dark/navbar.js").asFile, null))

                    set.add(Pair(root.file("webroot/js/themes/light/index.js").asFile, null))
                    set.add(Pair(root.file("webroot/js/themes/light/navbar.js").asFile, null))
                    set.add(Pair(root.file("webroot/js/themes/light/icon.js").asFile, null))

                    set.add(Pair(root.file("webroot/js/list/language.js").asFile, null))

                    set.add(Pair(root.file("webroot/js/switcher/fontChanger.js").asFile, null))

                    set.add(Pair(root.file("webroot/lang/en_US.json").asFile, null))
                    set.add(Pair(root.file("webroot/lang/ja_JP.json").asFile, null))
                    set.add(Pair(root.file("webroot/lang/pt_BR.json").asFile, null))
                    set.add(Pair(root.file("webroot/lang/ro_RO.json").asFile, null))
                    set.add(Pair(root.file("webroot/lang/ru_RU.json").asFile, null))
                    set.add(Pair(root.file("webroot/lang/vi_VN.json").asFile, null))
                    set.add(Pair(root.file("webroot/lang/zh_CN.json").asFile, null))
                    set.add(Pair(root.file("webroot/lang/zh_TW.json").asFile, null))

                    set.add(Pair(root.file("webroot/js/modal/language.js").asFile, null))
                    set.add(Pair(root.file("webroot/js/modal/errorHistory.js").asFile, null))

                    set.add(Pair(root.file("webroot/css/index.css").asFile, null))
                    set.add(Pair(root.file("webroot/css/fonts.css").asFile, null))

                    set.add(Pair(root.file("webroot/fonts/ProductSans-Italic.ttf").asFile, null))
                    set.add(Pair(root.file("webroot/fonts/ProductSans-Regular.ttf").asFile, null))
                    
                    set.add(Pair(root.file("webroot/assets/mark.svg").asFile, null))
                    set.add(Pair(root.file("webroot/assets/tick.svg").asFile, null))
                    set.add(Pair(root.file("webroot/assets/warn.svg").asFile, null))
                    set.add(Pair(root.file("webroot/assets/module.svg").asFile, null))
                    set.add(Pair(root.file("webroot/assets/expand.svg").asFile, null))
                    set.add(Pair(root.file("webroot/assets/settings.svg").asFile, null))
                    set.add(Pair(root.file("webroot/assets/close.svg").asFile, null))
                    set.add(Pair(root.file("webroot/assets/content.svg").asFile, null))
                    set.add(Pair(root.file("webroot/assets/error.svg").asFile, null))
                    set.add(Pair(root.file("webroot/assets/action.svg").asFile, null))
                    set.add(Pair(root.file("webroot/assets/home.svg").asFile, null))
                    sig.initSign(privKey)
                    set.forEach { it.first.sha(it.second) }
                    val signFile = root.file(name).asFile
                    signFile.writeBytes(sig.sign())
                    signFile.appendBytes(publicKey)
                }

                getSign("machikado.arm", "armeabi-v7a", "arm64-v8a")
                getSign("machikado.x86", "x86", "x86_64")
            } else {
                println("no private_key found, this build will not be signed")
                root.file("machikado.arm").asFile.createNewFile()
                root.file("machikado.x86").asFile.createNewFile()
            }

            fileTree(moduleDir).visit {
                if (isDirectory) return@visit
                val md = MessageDigest.getInstance("SHA-256")
                file.forEachBlock(4096) { bytes, size ->
                    md.update(bytes, 0, size)
                }
                file(file.path + ".sha256").writeText(Hex.encodeHexString(md.digest()))
            }
        }
    }

    val zipTask = task<Zip>("zip$variantCapped") {
        group = "module"
        dependsOn(prepareModuleFilesTask)
        archiveFileName.set(zipFileName)
        destinationDirectory.set(layout.buildDirectory.file("outputs/release").get().asFile)
        from(moduleDir)
    }

    val pushTask = task<Exec>("push$variantCapped") {
        group = "module"
        dependsOn(zipTask)
        commandLine("adb", "push", zipTask.outputs.files.singleFile.path, "/data/local/tmp")
    }

    val installKsuTask = task("installKsu$variantCapped") {
        group = "module"
        dependsOn(pushTask)
        doLast {
            exec {
                commandLine(
                    "adb", "shell", "echo",
                    "/data/adb/ksud module install /data/local/tmp/$zipFileName",
                    "> /data/local/tmp/install.sh"
                )
            }
            exec { commandLine("adb", "shell", "chmod", "755", "/data/local/tmp/install.sh") }
            exec { commandLine("adb", "shell", "su", "-c", "/data/local/tmp/install.sh") }
        }
    }

    val installMagiskTask = task<Exec>("installMagisk$variantCapped") {
        group = "module"
        dependsOn(pushTask)
        commandLine("adb", "shell", "su", "-M", "-c", "magisk --install-module /data/local/tmp/$zipFileName")
    }

    task<Exec>("installKsuAndReboot$variantCapped") {
        group = "module"
        dependsOn(installKsuTask)
        commandLine("adb", "reboot")
    }

    task<Exec>("installMagiskAndReboot$variantCapped") {
        group = "module"
        dependsOn(installMagiskTask)
        commandLine("adb", "reboot")
    }
}
