// Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.lambda.execution.sam

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.EnvironmentUtil
import com.intellij.util.text.SemVer
import com.intellij.util.text.nullize
import software.aws.toolkits.jetbrains.services.cloudformation.CloudFormationTemplate
import software.aws.toolkits.jetbrains.services.cloudformation.SERVERLESS_FUNCTION_TYPE
import software.aws.toolkits.jetbrains.settings.SamSettings
import software.aws.toolkits.jetbrains.utils.FileInfoCache
import software.aws.toolkits.resources.message
import java.nio.file.Paths

class SamCommon {
    companion object {
        val mapper = jacksonObjectMapper()
        const val SAM_BUILD_DIR = ".aws-sam"
        const val SAM_INFO_VERSION_KEY = "version"

        // Inclusive
        val expectedSamMinVersion = SemVer("0.7.0", 0, 7, 0)

        // Exclusive
        val expectedSamMaxVersion = SemVer("0.16.0", 0, 16, 0)

        private val versionCache = object : FileInfoCache<SemVer>() {
            override fun getFileInfo(path: String): SemVer {
                val commandLine = getSamCommandLine(path)
                    .withParameters("--info")

                val process = CapturingProcessHandler(commandLine).runProcess()

                if (process.exitCode != 0) {
                    throw IllegalStateException(process.stderr)
                } else {
                    if (process.stdout.isEmpty()) {
                        throw IllegalStateException(message("sam.executable.empty_info"))
                    }
                    val tree = mapper.readTree(process.stdout)
                    val version = tree.get(SAM_INFO_VERSION_KEY).asText()
                    return SemVer.parseFromText(version)
                        ?: throw IllegalStateException(message("sam.executable.version_parse_error", version))
                }
            }
        }

        fun getSamCommandLine(path: String? = SamSettings.getInstance().executablePath): GeneralCommandLine {
            val sanitizedPath = path.nullize(true)
                ?: throw RuntimeException(message("sam.cli_not_configured"))

            // we have some env-hacks that we want to do, so we're building our own environment using the same util as GeneralCommandLine
            // GeneralCommandLine will apply some more env patches prior to process launch (see startProcess()) so this should be fine
            val effectiveEnvironment = EnvironmentUtil.getEnvironmentMap().toMutableMap()
            // apply hacks
            effectiveEnvironment.apply {
                // GitHub issue: https://github.com/aws/aws-toolkit-jetbrains/issues/645
                // strip out any AWS credentials in the parent environment
                remove("AWS_ACCESS_KEY_ID")
                remove("AWS_SECRET_ACCESS_KEY")
                remove("AWS_SESSION_TOKEN")
                // GitHub issue: https://github.com/aws/aws-toolkit-jetbrains/issues/577
                // coerce the locale to UTF-8 as specified in PEP 538
                // this is needed for Python 3.0 up to Python 3.7.0 (inclusive)
                // we can remove this once our IDE minimum version has a fix for https://youtrack.jetbrains.com/issue/PY-30780
                // currently only seeing this on OS X, so only scoping to that
                if (SystemInfo.isMac) {
                    // on other platforms this could be C.UTF-8 or C.UTF8
                    this["LC_CTYPE"] = "UTF-8"
                    // we're not setting PYTHONIOENCODING because we might break SAM on py2.7
                }
            }

            return GeneralCommandLine(sanitizedPath)
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE)
                .withEnvironment(effectiveEnvironment)
        }

        private fun checkVersion(semVer: SemVer): String? {
            val samVersionOutOfRangeMessage = message("sam.executable.version_wrong", expectedSamMinVersion, expectedSamMaxVersion, semVer)
            if (semVer >= expectedSamMaxVersion) {
                return "$samVersionOutOfRangeMessage ${message("sam.executable.version_too_high")}"
            } else if (semVer < expectedSamMinVersion) {
                return "$samVersionOutOfRangeMessage ${message("sam.executable.version_too_low")}"
            }
            return null
        }

        /**
         * @return The error message to display, else null if it is valid
         */
        fun validate(path: String? = SamSettings.getInstance().executablePath): String? {
            val sanitizedPath = path.nullize(true)
                ?: return message("sam.cli_not_configured")

            return try {
                checkVersion(versionCache.getResult(sanitizedPath))
            } catch (e: Exception) {
                return e.message
            }
        }

        /**
         * @return The SemVer of SAM, or null if unable to determine it
         */
        fun getSamVersion(path: String? = SamSettings.getInstance().executablePath): SemVer? {
            val sanitizedPath = path.nullize(true)
                ?: return null

            return try {
                versionCache.getResult(sanitizedPath)
            } catch (e: Exception) {
                return null
            }
        }

        fun getTemplateFromDirectory(projectRoot: VirtualFile): VirtualFile? {
            val yamlFiles = VfsUtil.getChildren(projectRoot).filter { it.name.endsWith("yaml") || it.name.endsWith("yml") }
            assert(yamlFiles.size == 1) { println(message("cloudformation.yaml.too_many_files", yamlFiles.size)) }
            return yamlFiles.first()
        }

        fun getCodeUrisFromTemplate(project: Project, template: VirtualFile?): List<VirtualFile> {
            template ?: return listOf()
            val cfTemplate = CloudFormationTemplate.parse(project, template)

            val codeUris = mutableListOf<VirtualFile>()
            val templatePath = Paths.get(template.parent.path)
            val localFileSystem = LocalFileSystem.getInstance()

            cfTemplate.resources().filter { it.isType(SERVERLESS_FUNCTION_TYPE) }.forEach { resource ->
                val codeUriValue = resource.getScalarProperty("CodeUri")
                val codeUriPath = templatePath.resolve(codeUriValue)
                localFileSystem.refreshAndFindFileByIoFile(codeUriPath.toFile())
                    ?.takeIf { it.isDirectory }
                    ?.let { codeUri ->
                        codeUris.add(codeUri)
                    }
            }
            return codeUris
        }

        fun setSourceRoots(projectRoot: VirtualFile, project: Project, modifiableModel: ModifiableRootModel) {
            val template = SamCommon.getTemplateFromDirectory(projectRoot)
            val codeUris = SamCommon.getCodeUrisFromTemplate(project, template)
            modifiableModel.contentEntries.forEach { contentEntry ->
                if (contentEntry.file == projectRoot) {
                    codeUris.forEach { contentEntry.addSourceFolder(it, false) }
                }
            }
        }

        fun excludeSamDirectory(projectRoot: VirtualFile, modifiableModel: ModifiableRootModel) {
            modifiableModel.contentEntries.forEach { contentEntry ->
                if (contentEntry.file == projectRoot) {
                    contentEntry.addExcludeFolder(VfsUtilCore.pathToUrl(Paths.get(projectRoot.path, SAM_BUILD_DIR).toString()))
                }
            }
        }
    }
}