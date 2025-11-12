package com.mkobit.jenkins.pipelines.sharedlibrary

import org.gradle.api.Plugin
import org.gradle.api.Project

class JenkinsSharedLibraryPlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        def extension = project.extensions.create('jenkinsSharedLibrary', JenkinsSharedLibraryExtension)

        project.tasks.register('jenkinsSharedLibraryInfo') {
            group = 'verification'
            description = 'Prints configured Jenkins shared library metadata.'
            doLast {
                project.logger.lifecycle("Jenkins core: ${extension.coreVersion}, test harness: ${extension.testHarnessVersion}, pipeline unit: ${extension.pipelineUnitVersion}")
            }
        }

        project.afterEvaluate {
            String pipelineUnitVersion = extension.pipelineUnitVersion ?: '1.19'
            project.dependencies.add('testImplementation', "com.lesfurets:jenkins-pipeline-unit:${pipelineUnitVersion}")
            if (project.configurations.findByName('integrationTestImplementation')) {
                project.dependencies.add('integrationTestImplementation', "com.lesfurets:jenkins-pipeline-unit:${pipelineUnitVersion}")
            }
        }
    }
}
