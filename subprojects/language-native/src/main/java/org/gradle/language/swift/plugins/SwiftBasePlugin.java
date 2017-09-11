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

package org.gradle.language.swift.plugins;

import org.gradle.api.Action;
import org.gradle.api.Incubating;
import org.gradle.api.Plugin;
import org.gradle.api.Task;
import org.gradle.api.file.Directory;
import org.gradle.api.file.DirectoryVar;
import org.gradle.api.file.RegularFile;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.internal.tasks.TaskContainerInternal;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.util.PatternSet;
import org.gradle.language.base.plugins.LifecycleBasePlugin;
import org.gradle.language.nativeplatform.internal.Names;
import org.gradle.language.swift.SwiftBinary;
import org.gradle.language.swift.SwiftBundle;
import org.gradle.language.swift.SwiftExecutable;
import org.gradle.language.swift.SwiftSharedLibrary;
import org.gradle.language.swift.tasks.CreateSwiftBundle;
import org.gradle.language.swift.tasks.SwiftCompile;
import org.gradle.model.internal.registry.ModelRegistry;
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform;
import org.gradle.nativeplatform.tasks.InstallExecutable;
import org.gradle.nativeplatform.tasks.LinkMachOBundle;
import org.gradle.nativeplatform.tasks.LinkExecutable;
import org.gradle.nativeplatform.tasks.LinkSharedLibrary;
import org.gradle.nativeplatform.toolchain.NativeToolChain;
import org.gradle.nativeplatform.toolchain.internal.NativeToolChainInternal;
import org.gradle.nativeplatform.toolchain.internal.NativeToolChainRegistryInternal;
import org.gradle.nativeplatform.toolchain.internal.PlatformToolProvider;
import org.gradle.nativeplatform.toolchain.plugins.SwiftCompilerPlugin;

import java.util.concurrent.Callable;

/**
 * A common base plugin for the Swift executable and library plugins
 *
 * @since 4.1
 */
@Incubating
public class SwiftBasePlugin implements Plugin<ProjectInternal> {
    @Override
    public void apply(ProjectInternal project) {
        project.getPluginManager().apply(LifecycleBasePlugin.class);
        project.getPluginManager().apply(SwiftCompilerPlugin.class);

        // TODO - Merge with CppBasePlugin to remove code duplication

        final TaskContainerInternal tasks = project.getTasks();
        final DirectoryVar buildDirectory = project.getLayout().getBuildDirectory();
        final ModelRegistry modelRegistry = project.getModelRegistry();
        final ProviderFactory providers = project.getProviders();

        project.getComponents().withType(SwiftBinary.class, new Action<SwiftBinary>() {
            @Override
            public void execute(final SwiftBinary binary) {
                final Names names = Names.of(binary.getName());
                SwiftCompile compile = tasks.create(names.getCompileTaskName("swift"), SwiftCompile.class);
                compile.includes(binary.getCompileImportPath());
                compile.source(binary.getSwiftSource());
                if (binary.isDebuggable()) {
                    compile.setDebuggable(true);
                } else {
                    compile.setOptimized(true);
                }
                compile.setModuleName(binary.getModule());
                compile.getObjectFileDir().set(buildDirectory.dir("obj/" + names.getDirName()));

                DefaultNativePlatform currentPlatform = new DefaultNativePlatform("current");
                compile.setTargetPlatform(currentPlatform);

                // TODO - make this lazy
                NativeToolChain toolChain = modelRegistry.realize("toolChains", NativeToolChainRegistryInternal.class).getForPlatform(currentPlatform);
                compile.setToolChain(toolChain);

                if (binary instanceof SwiftExecutable) {
                    // Add a link task
                    LinkExecutable link = tasks.create(names.getTaskName("link"), LinkExecutable.class);
                    link.source(compile.getObjectFileDir().getAsFileTree().matching(new PatternSet().include("**/*.obj", "**/*.o")));
                    link.lib(binary.getLinkLibraries());
                    final PlatformToolProvider toolProvider = ((NativeToolChainInternal) toolChain).select(currentPlatform);
                    Provider<RegularFile> exeLocation = buildDirectory.file(providers.provider(new Callable<String>() {
                        @Override
                        public String call() {
                            return toolProvider.getExecutableName("exe/" + names.getDirName() + binary.getModule().get());
                        }
                    }));
                    link.setOutputFile(exeLocation);
                    link.setTargetPlatform(currentPlatform);
                    link.setToolChain(toolChain);
                    link.setDebuggable(binary.isDebuggable());

                    // Add an install task
                    // TODO - maybe not for all executables
                    final InstallExecutable install = tasks.create(names.getTaskName("install"), InstallExecutable.class);
                    install.setPlatform(link.getTargetPlatform());
                    install.setToolChain(link.getToolChain());
                    install.setDestinationDir(buildDirectory.dir("install/" + names.getDirName()));
                    install.setExecutable(link.getBinaryFile());
                    install.lib(binary.getRuntimeLibraries());
                } else if (binary instanceof SwiftSharedLibrary) {
                    // Add a link task
                    final LinkSharedLibrary link = tasks.create(names.getTaskName("link"), LinkSharedLibrary.class);
                    link.source(compile.getObjectFileDir().getAsFileTree().matching(new PatternSet().include("**/*.obj", "**/*.o")));
                    link.lib(binary.getLinkLibraries());
                    // TODO - need to set soname
                    final PlatformToolProvider toolProvider = ((NativeToolChainInternal) toolChain).select(currentPlatform);
                    Provider<RegularFile> runtimeFile = buildDirectory.file(providers.provider(new Callable<String>() {
                        @Override
                        public String call() {
                            return toolProvider.getSharedLibraryName("lib/" + names.getDirName() + binary.getModule().get());
                        }
                    }));
                    link.setOutputFile(runtimeFile);
                    link.setTargetPlatform(currentPlatform);
                    link.setToolChain(toolChain);
                    link.setDebuggable(binary.isDebuggable());
                } else if (binary instanceof SwiftBundle) {
                    // Add a link task
                    LinkMachOBundle link = tasks.create(names.getTaskName("link"), LinkMachOBundle.class);
                    link.source(compile.getObjectFileDir().getAsFileTree().matching(new PatternSet().include("**/*.obj", "**/*.o")));
                    link.lib(binary.getLinkLibraries());
                    final PlatformToolProvider toolProvider = ((NativeToolChainInternal) toolChain).select(currentPlatform);
                    Provider<RegularFile> exeLocation = buildDirectory.file(providers.provider(new Callable<String>() {
                        @Override
                        public String call() {
                            return toolProvider.getExecutableName("exe/" + names.getDirName() + binary.getModule().get());
                        }
                    }));
                    link.setOutputFile(exeLocation);
                    link.setTargetPlatform(currentPlatform);
                    link.setToolChain(toolChain);
                    link.setDebuggable(binary.isDebuggable());

                    final CreateSwiftBundle bundle = tasks.create(names.getTaskName("bundleSwift"), CreateSwiftBundle.class);
                    bundle.getExecutableFile().set(link.getBinaryFile());
                    bundle.getInformationFile().set(((SwiftBundle) binary).getInformationPropertyList());
                    Provider<Directory> bundleLocation = buildDirectory.dir(providers.provider(new Callable<String>() {
                        @Override
                        public String call() throws Exception {
                            return "bundle/" + names.getDirName() + binary.getModule().get() + ".xctest";
                        }
                    }));
                    bundle.getOutputDir().set(bundleLocation);
                    bundle.onlyIf(new Spec<Task>() {
                        @Override
                        public boolean isSatisfiedBy(Task element) {
                            return bundle.getExecutableFile().getAsFile().get().exists();
                        }
                    });
                }
            }
        });
    }
}
