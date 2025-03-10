/*
 * Copyright (c) 2019, 2024 Oracle and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.helidon.integrations.graal.nativeimage.extension;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.helidon.common.Reflected;
import io.helidon.common.features.HelidonFeatures;
import io.helidon.common.types.TypeName;
import io.helidon.logging.common.LogConfig;
import io.helidon.service.registry.DescriptorHandler;
import io.helidon.service.registry.ServiceDiscovery;
import io.helidon.service.registry.ServiceLoader__ServiceDescriptor;
import io.helidon.service.registry.ServiceRegistryConfig;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeClassInitialization;
import org.graalvm.nativeimage.hosted.RuntimeReflection;
import org.graalvm.nativeimage.hosted.RuntimeResourceAccess;

/**
 * Feature to add reflection configuration to the image for Helidon, CDI and Jersey.
 * Override the one in dependencies (native-image-extension from Helidon)
 */
public class HelidonReflectionFeature implements Feature {
    private static final boolean ENABLED = NativeConfig.option("reflection.enable-feature", true);

    private static final String AT_ENTITY = "jakarta.persistence.Entity";
    private static final String AT_MAPPED_SUPERCLASS = "jakarta.persistence.MappedSuperclass";
    private static final String REGISTRY_DESCRIPTOR = "io.helidon.service.registry.ServiceDescriptor";

    private final NativeTrace tracer = new NativeTrace();
    private NativeUtil util;

    @Override
    public boolean isInConfiguration(IsInConfigurationAccess access) {
        return ENABLED;
    }

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        // need the application classloader
        Class<?> logConfigClass = access.findClassByName(LogConfig.class.getName());
        ClassLoader classLoader = access.getApplicationClassLoader();

        tracer.parsing(() -> "Classpath as provided by the access: " + access.getApplicationClassPath());
        tracer.parsing(() -> "Modulepath as provided by the access: " + access.getApplicationModulePath());

        // initialize logging (if on classpath)
        try {
            logConfigClass.getMethod("initClass")
                    .invoke(null);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // make sure we print all the warnings for native image
        HelidonFeatures.nativeBuildTime(classLoader);

        // we need to initialize open-api type (as it fails at runtime, uses class files from classpath)
        initLogging(access);

        // load configuration
        HelidonReflectionConfiguration config = HelidonReflectionConfiguration.load(access, classLoader, tracer);

        // classpath scanning using the correct classloader
        ScanResult scan = new ClassGraph()
                .overrideClasspath(access.getApplicationClassPath())
                .enableAllInfo()
                .scan();

        util = new NativeUtil(tracer,
                              scan,
                              access::findClassByName,
                              config.excluded()::contains);

        // create context (to know what was processed and what should be registered)
        BeforeAnalysisContext context = new BeforeAnalysisContext(access, scan, config.excluded());

        // process each configured annotation
        config.annotations().forEach(it -> processAnnotated(context, it));
        // process each configured interface or class
        config.hierarchy().forEach(it -> processClassHierarchy(context, it));
        // process each configured interface or class including private fields and methods
        config.fullHierarchy().forEach(it -> processFullClassHierarchy(context, it));
        // process each configured class
        config.classes().forEach(it -> addSingleClass(context, it));

        // Service descriptors
        processServiceDescriptors(context);

        // JPA Entity registration
        processEntity(context);

        // all classes, fields and methods annotated with @Reflected
        addAnnotatedWithReflected(context);


        /*
         *
         *  And finally register with native image
         *
         */
        registerForReflection(context);
    }

    private void initLogging(BeforeAnalysisAccess access) {
        String jul = "io.helidon.logging.jul.JulProvider";
        Class<?> classByName = access.findClassByName(jul);
        if (classByName != null) {
            addBuildTime(access, "java.util.logging.StreamHandler");
            addBuildTime(access, "java.util.logging.Handler");
            addBuildTime(access, "io.helidon.logging.jul.HelidonConsoleHandler");
            addBuildTime(access, jul);
        }
    }

    private void addBuildTime(BeforeAnalysisAccess access, String className) {
        Class<?> classByName = access.findClassByName(className);
        if (classByName == null) {
            return;
        }
        // only register it if on classpath
        RuntimeClassInitialization.initializeAtBuildTime(classByName);
    }

    private void processAnnotated(BeforeAnalysisContext context, Class<?> annotationClass) {

        Class<Annotation> annotation = util.cast(annotationClass, Annotation.class);
        tracer.parsing(() -> "Looking up annotated by " + annotation.getName());

        Set<Class<?>> annotated = util.findAnnotated(annotationClass.getName());

        annotated.forEach(it -> processClassHierarchy(context, it));
    }

    private void processClassHierarchy(BeforeAnalysisContext context, Class<?> superclass) {

        // this class is always registered (interface or class)
        context.register(superclass).addDefaults();

        tracer.parsing(() -> "Looking up implementors of " + superclass.getName());

        processSubClasses(context, superclass);

        util.findInterfaces(superclass)
                .forEach(it -> addSingleClass(context, it));
    }

    private void processSubClasses(BeforeAnalysisContext context, Class<?> aClass) {
        Set<Class<?>> subclasses = util.findSubclasses(aClass.getName());

        processClasses(context, subclasses);
    }

    private void processServiceDescriptors(BeforeAnalysisContext context) {
        ServiceDiscovery sd = ServiceDiscovery.create(ServiceRegistryConfig.builder()
                                                              .discoverServices(false)
                                                              .discoverServicesFromServiceLoader(true)
                                                              .build());

        sd.allMetadata()
                .stream()
                .map(DescriptorHandler::descriptor)
                .filter(it -> it instanceof ServiceLoader__ServiceDescriptor)
                .map(it -> (ServiceLoader__ServiceDescriptor) it)
                .map(ServiceLoader__ServiceDescriptor::serviceType)
                .forEach(it -> processServiceLoaderDescriptor(context, it));

        Class<?> classByName = context.access().findClassByName(REGISTRY_DESCRIPTOR);
        tracer.parsing(() -> "Discovering service descriptors. Top level type: " + classByName);
        if (classByName != null) {
            processServiceDescriptors(context, classByName);
        }
    }

    private void processServiceLoaderDescriptor(BeforeAnalysisContext context, TypeName serviceImpl) {
        Class<?> classByName = context.access().findClassByName(serviceImpl.fqName());
        if (classByName == null) {
            tracer.parsing(() -> "    " + serviceImpl.fqName());
            tracer.parsing(() -> "        service implementation is not on classpath");
            return;
        }
        tracer.parsing(() -> "    " + classByName.getName());
        tracer.parsing(() -> "        Added for registration");

        try {
            Constructor<?> constructor = classByName.getConstructor();
            context.register(classByName).add(constructor);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("Cannot find default constructor for provider implementation class " + classByName,
                                       e);
        }
    }

    private void processServiceDescriptors(BeforeAnalysisContext context, Class<?> clazz) {
        Set<Class<?>> subclasses = util.findSubclasses(clazz.getName());

        for (Class<?> aClass : subclasses) {
            if (context.process(aClass)) {
                tracer.parsing(() -> "    " + aClass.getName());
                tracer.parsing(() -> "        Added for registration");

                try {
                    Field field = aClass.getDeclaredField("INSTANCE");
                    context.register(aClass).add(field);
                } catch (NoSuchFieldException ignored) {
                    // do not register, as this is not accessible via reflection
                }
                processServiceDescriptors(context, aClass);
            }
        }
    }

    private void addSingleClass(BeforeAnalysisContext context,
                                Class<?> theClass) {
        if (context.process(theClass)) {
            tracer.parsing(theClass::getName);
            tracer.parsing(() -> "  Added for registration");
            superclasses(context, theClass);
            context.register(theClass).addDefaults();
        }
    }

    private void addAnnotatedWithReflected(BeforeAnalysisContext context) {
        // want to make sure we use the correct classloader
        String annotation = Reflected.class.getName();

        tracer.parsing(() -> "Looking up annotated by " + annotation);

        // all annotated classes
        util.findAnnotated(annotation)
                .forEach(it -> {
                    tracer.parsing(() -> " class " + it.getName());
                    context.register(it).addAll();
                });

        // all annotated methods and constructors
        util.processAnnotatedExecutables(annotation,
                                         (clazz, constructor) -> context.register(clazz).add(constructor),
                                         (clazz, method) -> context.register(clazz).add(method));

        // fields
        util.processAnnotatedFields(annotation, (clazz, field) -> context.register(clazz).add(field));
    }

    @SuppressWarnings("unchecked")
    private void processEntity(BeforeAnalysisContext context) {
        final Class<? extends Annotation> entityAnnotation = (Class<? extends Annotation>) context.access()
                .findClassByName(AT_ENTITY);
        final Class<? extends Annotation> superclassAnnotation = (Class<? extends Annotation>) context.access()
                .findClassByName(AT_MAPPED_SUPERCLASS);
        Set<Class<?>> annotatedSet = new HashSet<>();
        tracer.parsing(() -> "Looking up annotated by " + AT_ENTITY);
        if (entityAnnotation != null) {
            annotatedSet.addAll(util.findAnnotated(AT_ENTITY));
        }
        tracer.parsing(() -> "Looking up annotated by " + AT_MAPPED_SUPERCLASS);
        if (superclassAnnotation != null) {
            annotatedSet.addAll(util.findAnnotated(AT_MAPPED_SUPERCLASS));
        }
        if (annotatedSet.isEmpty()) {
            return;
        }
        annotatedSet.forEach(aClass -> {
            tracer.parsing(() -> "Processing annotated class " + aClass.getName());
            String resourceName = aClass.getName().replace('.', '/') + ".class";
            InputStream resourceStream = aClass.getClassLoader().getResourceAsStream(resourceName);
            try {
                RuntimeResourceAccess.addResource(aClass.getModule(), resourceName, resourceStream.readAllBytes());
            } catch (IOException e) {
                throw new RuntimeException("Failed to read entity class file", e);
            }
            for (Field declaredField : aClass.getDeclaredFields()) {
                if (!Modifier.isPublic(declaredField.getModifiers()) && declaredField.getAnnotations().length == 0) {
                    RuntimeReflection.register(declaredField);
                    tracer.parsing(() -> "    added non annotated field " + declaredField);
                }
            }
        });
    }

    private void registerForReflection(BeforeAnalysisContext context) {
        Collection<Register> toRegister = context.toRegister();

        tracer.section(() -> "Registering " + toRegister.size() + " classes for reflection");

        // register for reflection
        for (Register register : toRegister) {
            // first validate if all fields are on classpath
            if (!register.validated) {
                register.validate();
            }
            // only register classes on the image classpath (not necessarily discovered by the scanning)
            if (register.valid) {
                register(register.clazz);

                if (!register.clazz.isInterface()) {
                    register.fields.forEach(this::register);
                    register.constructors.forEach(this::register);
                }

                register.methods.forEach(this::register);
            } else {
                tracer.trace(() -> register.clazz.getName() + " is not registered, as it had failed fields or superclass.");
            }
        }
    }

    private void register(Constructor<?> constructor) {
        tracer.trace(() -> "    " + constructor.getDeclaringClass().getSimpleName()
                + "("
                + params(constructor.getParameterTypes())
                + ")");

        RuntimeReflection.register(constructor);
    }

    private String typeToString(Type type) {
        if (type instanceof Class) {
            return ((Class<?>) type).getName();
        } else {
            return type.toString();
        }
    }

    private String params(Type[] parameterTypes) {
        if (parameterTypes.length == 0) {
            return "";
        }
        return Arrays.stream(parameterTypes)
                .map(this::typeToString)
                .collect(Collectors.joining(", "));
    }

    private void register(Field field) {
        tracer.trace(() -> "    "
                + Modifier.toString(field.getModifiers())
                + " " + typeToString(field.getGenericType())
                + " " + field.getName());

        RuntimeReflection.register(field);
    }

    private void register(Method method) {
        tracer.trace(() -> "    "
                + Modifier.toString(method.getModifiers())
                + " " + typeToString(method.getGenericReturnType())
                + " " + method.getName()
                + "(" + params(method.getGenericParameterTypes()) + ")");

        RuntimeReflection.register(method);
    }

    private void register(Class<?> clazz) {
        tracer.trace(() -> "Registering " + clazz.getName() + " for reflection");

        RuntimeReflection.register(clazz);
    }

    private void addFullSingleClass(BeforeAnalysisContext context,
                                    Class<?> theClass) {
        if (context.process(theClass)) {
            tracer.parsing(theClass::getName);
            tracer.parsing(() -> "  Added for full registration");
            superclasses(context, theClass);
            context.register(theClass).addAll();
        }
    }

    private void processFullClassHierarchy(BeforeAnalysisContext context,
                                           Class<?> superclass) {

        // this class is always registered (interface or class)
        context.register(superclass).addAll();

        tracer.parsing(() -> "Looking up implementors of " + superclass.getName());

        processFullClasses(context, util.findSubclasses(superclass.getName()));

        for (Class<?> anInterface : superclass.getInterfaces()) {
            // unless excluded
            if (context.isExcluded(anInterface)) {
                tracer.parsing(() -> "  Interface " + anInterface.getName() + " is explicitly excluded");
            } else {
                addFullSingleClass(context, anInterface);
            }
        }
    }

    private void processFullClasses(BeforeAnalysisContext context, Set<Class<?>> classes) {
        for (Class<?> aClass : classes) {
            if (context.process(aClass)) {
                tracer.parsing(() -> "    " + aClass.getName());
                tracer.parsing(() -> "        Added for registration");

                superclasses(context, aClass);
                context.register(aClass).addAll();

                int modifiers = aClass.getModifiers();
                if (!Modifier.isFinal(modifiers)) {
                    processSubClasses(context, aClass);
                }
            } else {
                context.register(aClass).addAll();
            }
        }
    }

    private void processClasses(BeforeAnalysisContext context, Set<Class<?>> classes) {
        for (Class<?> aClass : classes) {
            if (context.process(aClass)) {
                tracer.parsing(() -> "    " + aClass.getName());
                tracer.parsing(() -> "        Added for registration");

                superclasses(context, aClass);
                context.register(aClass).addDefaults();

                int modifiers = aClass.getModifiers();
                if (!Modifier.isFinal(modifiers)) {
                    processSubClasses(context, aClass);
                }
            }
        }
    }

    private void superclasses(BeforeAnalysisContext context, Class<?> aClass) {
        Set<Class<?>> superclasses = util.findSuperclasses(aClass);
        for (Class<?> superclass : superclasses) {
            if (context.process(superclass)) {
                tracer.parsing(superclass::getName);
                tracer.parsing(() -> "  Added for registration");
                context.register(superclass).addDefaults();
            }
        }
    }

    final class BeforeAnalysisContext {
        private final BeforeAnalysisAccess access;
        private final Set<Class<?>> processed = new HashSet<>();
        private final Set<Class<?>> excluded = new HashSet<>();
        private final Map<Class<?>, Register> registers = new HashMap<>();
        private final ScanResult scan;

        private BeforeAnalysisContext(BeforeAnalysisAccess access, ScanResult scan, Set<Class<?>> excluded) {
            this.access = access;
            this.scan = scan;
            this.excluded.addAll(excluded);
        }

        BeforeAnalysisAccess access() {
            return access;
        }

        ScanResult scan() {
            return scan;
        }

        public boolean process(Class<?> theClass) {
            return processed.add(theClass);
        }

        public Register register(Class<?> theClass) {
            return registers.computeIfAbsent(theClass, Register::new);
        }

        public Collection<Register> toRegister() {
            return registers.values();
        }

        boolean isExcluded(Class<?> theClass) {
            return excluded.contains(theClass);
        }
    }

    private class Register {
        private final Set<Method> methods = new HashSet<>();
        private final Set<Field> fields = new HashSet<>();
        private final Set<Constructor<?>> constructors = new HashSet<>();

        private final Class<?> clazz;

        private boolean validated;
        private boolean valid = true;

        private Register(Class<?> clazz) {
            this.clazz = clazz;
        }

        void validate() {
            validated = true;
            validateTypeParams();
            if (!valid) {
                return;
            }
            addFields(true, true);
        }

        boolean add(Method m) {
            return methods.add(m);
        }

        boolean add(Field f) {
            return fields.add(f);
        }

        boolean add(Constructor<?> c) {
            return constructors.add(c);
        }

        void addAll() {
            if (!validated) {
                validated = true;
                validateTypeParams();
            }
            if (!valid) {
                return;
            }
            addFields(true, false);
            if (!valid) {
                return;
            }
            addMethods();
            if (clazz.isInterface()) {
                return;
            }
            addConstructors();
        }

        void addDefaults() {
            validated = true;
            validateTypeParams();
            if (!valid) {
                return;
            }
            addFields(false, false);
            if (!valid) {
                return;
            }
            addMethods();
            if (clazz.isInterface()) {
                return;
            }
            addConstructors();
        }

        @SuppressWarnings("ResultOfMethodCallIgnored")
        private void validateTypeParams() {
            try {
                clazz.getGenericSuperclass();
            } catch (Exception e) {
                // this is now reported with each build, because ProtobufEncoder is part of netty codec
                tracer.parsing(() -> "Type parameter of superclass is not on classpath of "
                        + clazz.getName()
                        + " error: "
                        + e.getMessage());
                valid = false;
            }
        }

        private void addConstructors() {
            try {
                Constructor<?>[] constructors = clazz.getConstructors();
                for (Constructor<?> constructor : constructors) {
                    add(constructor);
                }
            } catch (NoClassDefFoundError e) {
                tracer.trace(() -> "Public constructors of "
                        + clazz.getName()
                        + " not added to reflection, as a type is not on classpath: "
                        + e.getMessage());
            }
            try {
                // add all declared
                Constructor<?>[] constructors = clazz.getDeclaredConstructors();
                for (Constructor<?> constructor : constructors) {
                    add(constructor);
                }
            } catch (NoClassDefFoundError e) {
                tracer.trace(() -> "Constructors of "
                        + clazz.getName()
                        + " not added to reflection, as a type is not on classpath: "
                        + e.getMessage());
            }
        }

        void addFields(boolean all, boolean validateOnly) {
            try {
                Field[] fields = clazz.getFields();
                // add all public fields
                for (Field field : fields) {
                    if (!validateOnly) {
                        add(field);
                    }
                }
            } catch (NoClassDefFoundError e) {
                this.valid = false;

                if (validateOnly) {
                    tracer.trace(() -> "Validation of fields of "
                            + clazz.getName()
                            + " failed, as a type is not on classpath: "
                            + e.getMessage());
                } else {
                    tracer.trace(() -> "Public fields of "
                            + clazz.getName()
                            + " not added to reflection, as a type is not on classpath: "
                            + e.getMessage());
                }

            }
            try {
                for (Field declaredField : clazz.getDeclaredFields()) {
                    // there may be fields referencing classes not on the classpath
                    if (!Modifier.isPublic(declaredField.getModifiers())) {
                        // public already registered
                        if (all || declaredField.getAnnotations().length > 0) {
                            if (!validateOnly) {
                                add(declaredField);
                            }
                        }
                    }
                }
            } catch (NoClassDefFoundError e) {
                this.valid = false;

                if (validateOnly) {
                    tracer.trace(() -> "Validation of fields of "
                            + clazz.getName()
                            + " failed, as a type is not on classpath: "
                            + e.getMessage());
                } else {
                    tracer.trace(() -> "Fields of "
                            + clazz.getName()
                            + " not added to reflection, as a type is not on classpath: "
                            + e.getMessage());
                }
            }
        }

        void addMethods() {
            try {
                Method[] methods = clazz.getMethods();
                for (Method method : methods) {
                    boolean register;

                    // we do not want wait, notify etc
                    register = (method.getDeclaringClass() != Object.class);

                    if (register) {
                        // we do not want toString(), hashCode(), equals(java.lang.Object)
                        switch (method.getName()) {
                        case "hashCode":
                        case "toString":
                            register = !util.hasParams(method);
                            break;
                        case "equals":
                            register = !util.hasParams(method, Object.class);
                            break;
                        default:
                            // do nothing
                        }
                    }

                    if (register) {
                        tracer.trace(() -> "  " + method.getName() + "(" + Arrays.toString(method.getParameterTypes()) + ")");

                        add(method);
                    }
                }
            } catch (Throwable e) {
                tracer.trace(() -> "   Cannot register methods of " + clazz.getName() + ": "
                        + e.getClass().getName() + ": " + e.getMessage());
            }
        }
    }
}

