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
package org.gradle.plugin.use.resolve.internal;

import com.google.common.io.Files;
import org.gradle.api.Action;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.Plugin;
import org.gradle.api.UncheckedIOException;
import org.gradle.api.initialization.dsl.ScriptHandler;
import org.gradle.api.internal.initialization.ClassLoaderScope;
import org.gradle.api.internal.plugins.PluginImplementation;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.internal.project.ProjectRegistry;
import org.gradle.cache.CacheRepository;
import org.gradle.cache.PersistentCache;
import org.gradle.cache.internal.CacheKeyBuilder;
import org.gradle.configuration.ScriptPlugin;
import org.gradle.configuration.ScriptPluginFactory;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.classpath.DefaultClassPath;
import org.gradle.internal.resource.TextResourceLoader;
import org.gradle.plugin.use.PluginId;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.util.Collections;

import static org.gradle.internal.hash.HashUtil.createHash;
import static org.objectweb.asm.Opcodes.*;

/**
 * Plugin resolver for script plugins.
 */
public class ScriptPluginPluginResolver implements PluginResolver {

    private final ScriptPluginLoaderGenerator pluginsLoaderGenerator;

    public ScriptPluginPluginResolver(CacheRepository cacheRepository, CacheKeyBuilder cacheKeyBuilder, TextResourceLoader textResourceLoader, ClassLoaderScope targetScope) {
        pluginsLoaderGenerator = new ScriptPluginLoaderGenerator(cacheRepository, cacheKeyBuilder, textResourceLoader, targetScope);
    }

    public static String getDescription() {
        return "Script Plugins";
    }

    @Override
    public void resolve(ContextAwarePluginRequest pluginRequest, PluginResolutionResult result) {
        if (pluginRequest.getScript() == null) {
            return;
        }
        if (pluginRequest.getModule() != null) {
            throw new InvalidUserDataException("explicit artifact coordinates are not supported for script plugins applied using the plugins block");
        }
        if (pluginRequest.getVersion() != null) {
            throw new InvalidUserDataException("explicit version is not supported for script plugins applied using the plugins block");
        }
        if (!pluginRequest.isApply()) {
            throw new InvalidUserDataException("apply false is not supported for script plugins applied using the plugins block");
        }

        ScriptPluginImplementation scriptPluginImplementation = new ScriptPluginImplementation(pluginRequest, pluginsLoaderGenerator);
        result.found(getDescription(), new SimplePluginResolution(scriptPluginImplementation));
    }

    private static class ScriptPluginImplementation implements PluginImplementation<Object> {

        private final ContextAwarePluginRequest pluginRequest;
        private final ScriptPluginLoaderGenerator pluginLoaderGenerator;

        private Class<?> pluginLoaderClass;

        private ScriptPluginImplementation(ContextAwarePluginRequest pluginRequest, ScriptPluginLoaderGenerator pluginLoaderGenerator) {
            this.pluginRequest = pluginRequest;
            this.pluginLoaderGenerator = pluginLoaderGenerator;
        }

        @Override
        public Class<?> asClass() {
            if (pluginLoaderClass == null) {
                pluginLoaderClass = pluginLoaderGenerator.defineScriptPluginLoaderClass(pluginRequest, getDisplayName());
            }
            return pluginLoaderClass;
        }

        @Override
        public boolean isImperative() {
            return true;
        }

        @Override
        public boolean isHasRules() {
            return false;
        }

        @Override
        public Type getType() {
            return Type.IMPERATIVE_CLASS;
        }

        @Override
        public String getDisplayName() {
            return "script plugin '" + pluginRequest.getRelativeScriptUri() + "'";
        }

        @Nullable
        @Override
        public PluginId getPluginId() {
            return pluginRequest.getId();
        }

        @Override
        public boolean isAlsoKnownAs(PluginId id) {
            return id.equals(getPluginId());
        }
    }

    private static class ScriptPluginLoaderGenerator {

        private static final Type OBJECT_TYPE = Type.getType(Object.class);
        private static final Type STRING_TYPE = Type.getType(String.class);
        private static final Type INJECT_TYPE = Type.getType(Inject.class);
        private static final Type PLUGIN_TYPE = Type.getType(Plugin.class);
        private static final Type SCRIPT_HANDLER_TYPE = Type.getType(ScriptHandler.class);
        private static final Type SCRIPT_PLUGIN_TYPE = Type.getType(ScriptPlugin.class);
        private static final Type SCRIPT_PLUGIN_FACTORY_TYPE = Type.getType(ScriptPluginFactory.class);

        private static final Type PROJECT_REGISTRY_TYPE = Type.getType(ProjectRegistry.class);
        private static final Type PROJECT_INTERNAL_TYPE = Type.getType(ProjectInternal.class);
        private static final String PROJECT_INTERNAL_REGISTRY_SIGNATURE = 'L' + PROJECT_REGISTRY_TYPE.getInternalName() + '<' + PROJECT_INTERNAL_TYPE.getDescriptor() + ">;";

        private static final Type VOID_NO_ARG_METHOD = Type.getMethodType(Type.VOID_TYPE);
        private static final Type TO_STRING_METHOD = Type.getMethodType(STRING_TYPE);
        private static final Type APPLY_TO_OBJECT_METHOD = Type.getMethodType(Type.VOID_TYPE, OBJECT_TYPE);

        private static final String SYNTHETIC_LOADER_PACKAGE_NAME = "org.gradle.plugin.use.resolve.internal.script";
        private static final String SYNTHETIC_LOADER_PACKAGE_PATH = SYNTHETIC_LOADER_PACKAGE_NAME.replace(".", "/");
        private static final String SYNTHETIC_LOADER_TYPE_SIGNATURE = OBJECT_TYPE.getDescriptor() + ';' + PLUGIN_TYPE.getDescriptor() + '<' + OBJECT_TYPE.getDescriptor() + ";>;";
        private static final String SYNTHETIC_LOADER_CLASSNAME_PREFIX = "ScriptPluginSyntheticPluginLoader_";
        private static final Type SYNTHETIC_LOADER_CTOR = Type.getMethodType(Type.VOID_TYPE, PROJECT_REGISTRY_TYPE, SCRIPT_HANDLER_TYPE, SCRIPT_PLUGIN_FACTORY_TYPE);
        private static final String SYNTHETIC_LOADER_CTOR_SIGNATURE = '(' + PROJECT_INTERNAL_REGISTRY_SIGNATURE + SCRIPT_HANDLER_TYPE.getDescriptor() + SCRIPT_PLUGIN_FACTORY_TYPE.getDescriptor() + ")V";

        private static final Type SCRIPT_PLUGIN_LOADER_TYPE = Type.getType(ScriptPluginPluginLoader.class);
        private static final Type SCRIPT_PLUGIN_LOADER_LOAD_METHOD = Type.getMethodType(SCRIPT_PLUGIN_TYPE,
            STRING_TYPE, STRING_TYPE, STRING_TYPE, PROJECT_REGISTRY_TYPE, SCRIPT_HANDLER_TYPE, SCRIPT_PLUGIN_FACTORY_TYPE);

        private static final String PROJECT_REGISTRY_FIELD_NAME = "projectRegistry";
        private static final String SCRIPT_HANDLER_FIELD_NAME = "scriptHandler";
        private static final String SCRIPT_PLUGIN_FACTORY_FIELD_NAME = "scriptPluginFactory";

        private static final int SCRIPT_PLUGIN_LOADERS_CACHE_VERSION = 1;

        private final CacheRepository cacheRepository;
        private final CacheKeyBuilder cacheKeyBuilder;
        private final TextResourceLoader textResourceLoader;
        private final ClassLoaderScope parentLoaderScope;

        private ScriptPluginLoaderGenerator(CacheRepository cacheRepository, CacheKeyBuilder cacheKeyBuilder, TextResourceLoader textResourceLoader, ClassLoaderScope parentLoaderScope) {
            this.cacheRepository = cacheRepository;
            this.cacheKeyBuilder = cacheKeyBuilder;
            this.textResourceLoader = textResourceLoader;
            this.parentLoaderScope = parentLoaderScope;
        }

        private Class<?> defineScriptPluginLoaderClass(ContextAwarePluginRequest pluginRequest, final String displayName) {

            final String scriptContent = scriptContentFor(pluginRequest);
            final String scriptContentHash = createHash(scriptContent, "SHA1").asCompactString();
            final String classSimpleName = loaderClassSimpleNameFor(scriptContentHash);
            final String classBinaryName = loaderClassBinaryNameFor(classSimpleName);

            CacheKeyBuilder.CacheKeySpec cacheKeySpec = CacheKeyBuilder.CacheKeySpec
                .withPrefix("script-plugin-loaders")
                .plus(String.valueOf(SCRIPT_PLUGIN_LOADERS_CACHE_VERSION))
                .plus(scriptContentHash);

            final String cacheClassPathDirName = "cp";

            PersistentCache cache = cacheRepository.cache(cacheKeyBuilder.build(cacheKeySpec))
                .withInitializer(new Action<PersistentCache>() {
                    @Override
                    public void execute(@Nonnull PersistentCache cache) {

                        byte[] bytes = generateScriptPluginLoaderClass(classSimpleName, scriptContent, scriptContentHash, displayName);

                        String classFilePath = cacheClassPathDirName + "/" + SYNTHETIC_LOADER_PACKAGE_PATH + "/" + classSimpleName + ".class";
                        File classFile = new File(cache.getBaseDir(), classFilePath);

                        try {
                            Files.createParentDirs(classFile);
                            Files.write(bytes, classFile);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }
                })
                .open();
            cache.close();

            File classpathDir = new File(cache.getBaseDir(), cacheClassPathDirName);
            ClassLoaderScope loaderScope = parentLoaderScope.createChild("script-plugin-loader-" + scriptContentHash);
            loaderScope.export(DefaultClassPath.of(Collections.singleton(classpathDir)));
            loaderScope.lock();

            try {
                return loaderScope.getExportClassLoader().loadClass(classBinaryName);
            } catch (ClassNotFoundException e) {
                throw UncheckedException.throwAsUncheckedException(e);
            }
        }

        private String scriptContentFor(ContextAwarePluginRequest pluginRequest) {
            return textResourceLoader.loadUri("script plugin", pluginRequest.getScriptUri()).getText();
        }

        private String loaderClassSimpleNameFor(String scriptContentHash) {
            return SYNTHETIC_LOADER_CLASSNAME_PREFIX + scriptContentHash;
        }

        private String loaderClassBinaryNameFor(String classSimpleName) {
            return SYNTHETIC_LOADER_PACKAGE_NAME + "." + classSimpleName;
        }

        private byte[] generateScriptPluginLoaderClass(String classSimpleName, String scriptContent, String scriptContentHash, String displayName) {
            String syntheticLoaderInternalName = SYNTHETIC_LOADER_PACKAGE_PATH + "/" + classSimpleName;

            ClassWriter cw = new ClassWriter(0);
            cw.visit(V1_5, ACC_PUBLIC + ACC_SUPER, syntheticLoaderInternalName, SYNTHETIC_LOADER_TYPE_SIGNATURE, OBJECT_TYPE.getInternalName(), new String[]{PLUGIN_TYPE.getInternalName()});

            defineInstanceMembers(cw);
            defineConstructor(cw, syntheticLoaderInternalName);
            defineApplyMethod(cw, syntheticLoaderInternalName, scriptContent, scriptContentHash, displayName);
            defineToStringMethod(cw, displayName);

            cw.visitEnd();
            return cw.toByteArray();
        }

        private void defineInstanceMembers(ClassWriter cw) {
            cw.visitField(ACC_PRIVATE + ACC_FINAL, PROJECT_REGISTRY_FIELD_NAME, PROJECT_REGISTRY_TYPE.getDescriptor(), PROJECT_INTERNAL_REGISTRY_SIGNATURE, null).visitEnd();
            cw.visitField(ACC_PRIVATE + ACC_FINAL, SCRIPT_HANDLER_FIELD_NAME, SCRIPT_HANDLER_TYPE.getDescriptor(), null, null).visitEnd();
            cw.visitField(ACC_PRIVATE + ACC_FINAL, SCRIPT_PLUGIN_FACTORY_FIELD_NAME, SCRIPT_PLUGIN_FACTORY_TYPE.getDescriptor(), null, null).visitEnd();
        }

        private void defineConstructor(ClassWriter cw, String syntheticLoaderInternalName) {
            MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>", SYNTHETIC_LOADER_CTOR.getInternalName(), SYNTHETIC_LOADER_CTOR_SIGNATURE, null);
            ctor.visitAnnotation(INJECT_TYPE.getDescriptor(), true).visitEnd();
            ctor.visitMaxs(2, 4);
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitMethodInsn(INVOKESPECIAL, OBJECT_TYPE.getInternalName(), "<init>", VOID_NO_ARG_METHOD.getDescriptor(), false);
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitVarInsn(ALOAD, 1);
            ctor.visitFieldInsn(PUTFIELD, syntheticLoaderInternalName, PROJECT_REGISTRY_FIELD_NAME, PROJECT_REGISTRY_TYPE.getDescriptor());
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitVarInsn(ALOAD, 2);
            ctor.visitFieldInsn(PUTFIELD, syntheticLoaderInternalName, SCRIPT_HANDLER_FIELD_NAME, SCRIPT_HANDLER_TYPE.getDescriptor());
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitVarInsn(ALOAD, 3);
            ctor.visitFieldInsn(PUTFIELD, syntheticLoaderInternalName, SCRIPT_PLUGIN_FACTORY_FIELD_NAME, SCRIPT_PLUGIN_FACTORY_TYPE.getDescriptor());
            ctor.visitInsn(RETURN);
            ctor.visitEnd();
        }

        private void defineApplyMethod(ClassWriter cw, String syntheticLoaderInternalName, String scriptContent, String scriptContentHash, String displayName) {
            MethodVisitor apply = cw.visitMethod(ACC_PUBLIC, "apply", APPLY_TO_OBJECT_METHOD.getDescriptor(), null, null);
            apply.visitMaxs(7, 4);
            apply.visitTypeInsn(NEW, SCRIPT_PLUGIN_LOADER_TYPE.getInternalName());
            apply.visitInsn(DUP);
            apply.visitMethodInsn(INVOKESPECIAL, SCRIPT_PLUGIN_LOADER_TYPE.getInternalName(), "<init>", VOID_NO_ARG_METHOD.getDescriptor(), false);
            apply.visitVarInsn(ASTORE, 2);
            apply.visitVarInsn(ALOAD, 2);
            apply.visitLdcInsn(scriptContent);
            apply.visitLdcInsn(scriptContentHash);
            apply.visitLdcInsn(displayName);
            apply.visitVarInsn(ALOAD, 0);
            apply.visitFieldInsn(GETFIELD, syntheticLoaderInternalName, PROJECT_REGISTRY_FIELD_NAME, PROJECT_REGISTRY_TYPE.getDescriptor());
            apply.visitVarInsn(ALOAD, 0);
            apply.visitFieldInsn(GETFIELD, syntheticLoaderInternalName, SCRIPT_HANDLER_FIELD_NAME, SCRIPT_HANDLER_TYPE.getDescriptor());
            apply.visitVarInsn(ALOAD, 0);
            apply.visitFieldInsn(GETFIELD, syntheticLoaderInternalName, SCRIPT_PLUGIN_FACTORY_FIELD_NAME, SCRIPT_PLUGIN_FACTORY_TYPE.getDescriptor());
            apply.visitMethodInsn(INVOKEVIRTUAL, SCRIPT_PLUGIN_LOADER_TYPE.getInternalName(), "load", SCRIPT_PLUGIN_LOADER_LOAD_METHOD.getDescriptor(), false);
            apply.visitVarInsn(ASTORE, 3);
            apply.visitVarInsn(ALOAD, 3);
            apply.visitVarInsn(ALOAD, 1);
            apply.visitMethodInsn(INVOKEINTERFACE, SCRIPT_PLUGIN_TYPE.getInternalName(), "apply", APPLY_TO_OBJECT_METHOD.getDescriptor(), true);
            apply.visitInsn(RETURN);
            apply.visitEnd();
        }

        private void defineToStringMethod(ClassWriter cw, String string) {
            MethodVisitor toString = cw.visitMethod(ACC_PUBLIC, "toString", TO_STRING_METHOD.getDescriptor(), null, null);
            toString.visitMaxs(1, 1);
            toString.visitLdcInsn(string);
            toString.visitInsn(ARETURN);
            toString.visitEnd();
        }
    }
}
