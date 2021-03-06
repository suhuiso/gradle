/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.language.cpp.plugins;

import org.gradle.api.Action;
import org.gradle.api.Incubating;
import org.gradle.api.Plugin;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.attributes.Usage;
import org.gradle.api.internal.file.FileOperations;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.AppliedPlugin;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.language.base.plugins.LifecycleBasePlugin;
import org.gradle.language.cpp.CppApplication;
import org.gradle.language.cpp.CppComponent;
import org.gradle.language.cpp.internal.DefaultCppApplication;
import org.gradle.language.cpp.internal.NativeVariant;
import org.gradle.nativeplatform.tasks.InstallExecutable;
import org.gradle.nativeplatform.tasks.LinkExecutable;

import javax.inject.Inject;

import static org.gradle.language.cpp.CppBinary.DEBUGGABLE_ATTRIBUTE;

/**
 * <p>A plugin that produces a native executable from C++ source.</p>
 *
 * <p>Assumes the source files are located in `src/main/cpp` and header files are located in `src/main/headers`.</p>
 *
 * <p>Adds a {@link CppComponent} extension to the project to allow configuration of the executable.</p>
 *
 * @since 4.1
 */
@Incubating
public class CppExecutablePlugin implements Plugin<ProjectInternal> {
    private final FileOperations fileOperations;

    /**
     * Injects a {@link FileOperations} instance.
     *
     * @since 4.2
     */
    @Inject
    public CppExecutablePlugin(FileOperations fileOperations) {
        this.fileOperations = fileOperations;
    }

    @Override
    public void apply(final ProjectInternal project) {
        project.getPluginManager().apply(CppBasePlugin.class);

        ConfigurationContainer configurations = project.getConfigurations();
        ProviderFactory providers = project.getProviders();
        TaskContainer tasks = project.getTasks();
        ObjectFactory objectFactory = project.getObjects();

        // Add the application extension
        final CppApplication application = project.getExtensions().create(CppApplication.class, "executable", DefaultCppApplication.class,  "main", objectFactory, fileOperations, providers, configurations);
        project.getComponents().add(application);
        project.getComponents().add(application.getDebugExecutable());
        project.getComponents().add(application.getReleaseExecutable());

        // Configure the component
        application.getBaseName().set(project.getName());

        // Install the debug variant by default
        InstallExecutable install = (InstallExecutable) tasks.getByName("installDebug");
        tasks.getByName(LifecycleBasePlugin.ASSEMBLE_TASK_NAME).dependsOn(install);

        // TODO - add lifecycle tasks to assemble each variant

        LinkExecutable linkDebug = (LinkExecutable) tasks.getByName("linkDebug");
        LinkExecutable linkRelease = (LinkExecutable) tasks.getByName("linkRelease");

        final Usage runtimeUsage = objectFactory.named(Usage.class, Usage.NATIVE_RUNTIME);

        final Configuration debugRuntimeElements = configurations.create("debugRuntimeElements");
        debugRuntimeElements.extendsFrom(application.getImplementationDependencies());
        debugRuntimeElements.setCanBeResolved(false);
        debugRuntimeElements.getAttributes().attribute(Usage.USAGE_ATTRIBUTE, runtimeUsage);
        debugRuntimeElements.getAttributes().attribute(DEBUGGABLE_ATTRIBUTE, true);
        debugRuntimeElements.getOutgoing().artifact(linkDebug.getBinaryFile());

        final Configuration releaseRuntimeElements = configurations.create("releaseRuntimeElements");
        releaseRuntimeElements.extendsFrom(application.getImplementationDependencies());
        releaseRuntimeElements.setCanBeResolved(false);
        releaseRuntimeElements.getAttributes().attribute(Usage.USAGE_ATTRIBUTE, runtimeUsage);
        releaseRuntimeElements.getAttributes().attribute(DEBUGGABLE_ATTRIBUTE, false);
        releaseRuntimeElements.getOutgoing().artifact(linkRelease.getBinaryFile());

        project.getPluginManager().withPlugin("maven-publish", new Action<AppliedPlugin>() {
            @Override
            public void execute(AppliedPlugin appliedPlugin) {
                project.getExtensions().configure(PublishingExtension.class, new Action<PublishingExtension>() {
                    @Override
                    public void execute(PublishingExtension extension) {
                        extension.getPublications().create("main", MavenPublication.class, new Action<MavenPublication>() {
                            @Override
                            public void execute(MavenPublication publication) {
                                // TODO - should track changes to properties
                                publication.setGroupId(project.getGroup().toString());
                                publication.setArtifactId(application.getBaseName().get());
                                publication.setVersion(project.getVersion().toString());
                            }
                        });
                        extension.getPublications().create("debug", MavenPublication.class, new Action<MavenPublication>() {
                            @Override
                            public void execute(MavenPublication publication) {
                                // TODO - should track changes to properties
                                publication.setGroupId(project.getGroup().toString());
                                publication.setArtifactId(application.getBaseName().get() + "_debug");
                                publication.setVersion(project.getVersion().toString());
                                publication.from(new NativeVariant("debug", null, null, runtimeUsage, debugRuntimeElements));
                            }
                        });
                        extension.getPublications().create("release", MavenPublication.class, new Action<MavenPublication>() {
                            @Override
                            public void execute(MavenPublication publication) {
                                // TODO - should track changes to properties
                                publication.setGroupId(project.getGroup().toString());
                                publication.setArtifactId(application.getBaseName().get() + "_release");
                                publication.setVersion(project.getVersion().toString());
                                publication.from(new NativeVariant("release", null, null, runtimeUsage, releaseRuntimeElements));
                            }
                        });
                    }
                });
            }
        });
    }
}
