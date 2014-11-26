package net.bytebuddy.agent.builder;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ClassLoaderByteArrayInjector;
import net.bytebuddy.dynamic.scaffold.inline.ClassFileLocator;
import net.bytebuddy.dynamic.scaffold.inline.MethodRebaseResolver;
import net.bytebuddy.instrumentation.LoadedTypeInitializer;
import net.bytebuddy.instrumentation.method.MethodDescription;
import net.bytebuddy.instrumentation.method.bytecode.ByteCodeAppender;
import net.bytebuddy.instrumentation.method.bytecode.stack.Removal;
import net.bytebuddy.instrumentation.method.bytecode.stack.StackManipulation;
import net.bytebuddy.instrumentation.method.bytecode.stack.collection.ArrayFactory;
import net.bytebuddy.instrumentation.method.bytecode.stack.constant.ClassConstant;
import net.bytebuddy.instrumentation.method.bytecode.stack.constant.NullConstant;
import net.bytebuddy.instrumentation.method.bytecode.stack.constant.TextConstant;
import net.bytebuddy.instrumentation.method.bytecode.stack.member.MethodInvocation;
import net.bytebuddy.instrumentation.type.InstrumentedType;
import net.bytebuddy.instrumentation.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.StreamDrainer;

import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static net.bytebuddy.matcher.ElementMatchers.*;
import static net.bytebuddy.utility.ByteBuddyCommons.join;
import static net.bytebuddy.utility.ByteBuddyCommons.nonNull;

public interface AgentBuilder {

    Transformable redefine(RawMatcher rawMatcher);

    Transformable redefine(ElementMatcher<? super TypeDescription> typeMatcher);

    Transformable redefine(ElementMatcher<? super TypeDescription> typeMatcher,
                           ElementMatcher<? super ClassLoader> classLoaderMatcher);

    AgentBuilder withByteBuddy(ByteBuddy byteBuddy);

    AgentBuilder withListener(Listener listener);

    AgentBuilder withNativeMethodPrefix(String prefix);

    AgentBuilder disableSelfInitialization();

    AgentBuilder allowRetransformation();

    ClassFileTransformer makeRaw();

    ClassFileTransformer registerWith(Instrumentation instrumentation);

    ClassFileTransformer registerWithByteBuddyAgent();

    static interface Transformable {

        static interface Extendable extends AgentBuilder, Transformable {
            /* this is merely a unionizing interface that does not declare methods */
        }

        Extendable transform(Transformer transformer);
    }

    static class Default implements AgentBuilder {

        /**
         * The name of the Byte Buddy agent class.
         */
        private static final String BYTE_BUDDY_AGENT_TYPE = "net.bytebuddy.agent.ByteBuddyAgent";

        /**
         * The name of the {@code ByteBuddyAgent} class's method for obtaining an instrumentation.
         */
        private static final String GET_INSTRUMENTATION_METHOD = "getInstrumentation";

        /**
         * Base for access to a reflective member to make the code more readable.
         */
        private static final Object STATIC_METHOD = null;

        public static final String NO_NATIVE_PREFIX = "";

        private static final byte[] NO_TRANSFORMATION = null;

        private final ByteBuddy byteBuddy;

        private final BinaryLocator binaryLocator;

        private final Listener listener;

        private final String nativeMethodPrefix;

        private final boolean disableSelfInitialization;

        private final boolean retransformation;

        private final List<Entry> entries;


        public Default() {
            this(new ByteBuddy());
        }

        public Default(ByteBuddy byteBuddy) {
            this(nonNull(byteBuddy),
                    BinaryLocator.Default.INSTANCE,
                    Listener.NoOp.INSTANCE,
                    NO_NATIVE_PREFIX,
                    false,
                    false,
                    Collections.<Entry>emptyList());
        }

        protected Default(ByteBuddy byteBuddy,
                          BinaryLocator binaryLocator,
                          Listener listener,
                          String nativeMethodPrefix,
                          boolean disableSelfInitialization,
                          boolean retransformation,
                          List<Entry> entries) {
            this.byteBuddy = byteBuddy;
            this.binaryLocator = binaryLocator;
            this.listener = listener;
            this.nativeMethodPrefix = nativeMethodPrefix;
            this.disableSelfInitialization = disableSelfInitialization;
            this.retransformation = retransformation;
            this.entries = entries;
        }

        @Override
        public Transformable redefine(RawMatcher rawMatcher) {
            return new Matched(nonNull(rawMatcher), Transformer.NoOp.INSTANCE);
        }

        @Override
        public Transformable redefine(ElementMatcher<? super TypeDescription> typeMatcher) {
            return redefine(typeMatcher, any());
        }

        @Override
        public Transformable redefine(ElementMatcher<? super TypeDescription> typeMatcher,
                                      ElementMatcher<? super ClassLoader> classLoaderMatcher) {
            return redefine(new RawMatcher.ForElementMatcherPair(nonNull(typeMatcher), nonNull(classLoaderMatcher)));
        }

        @Override
        public AgentBuilder withByteBuddy(ByteBuddy byteBuddy) {
            return new Default(nonNull(byteBuddy),
                    binaryLocator,
                    listener,
                    nativeMethodPrefix,
                    disableSelfInitialization,
                    retransformation,
                    entries);
        }

        @Override
        public AgentBuilder withListener(Listener listener) {
            return new Default(byteBuddy,
                    binaryLocator,
                    new Listener.Compound(this.listener, nonNull(listener)),
                    nativeMethodPrefix,
                    disableSelfInitialization,
                    retransformation,
                    entries);
        }

        @Override
        public AgentBuilder withNativeMethodPrefix(String prefix) {
            return new Default(byteBuddy,
                    binaryLocator,
                    listener,
                    nonNull(prefix),
                    disableSelfInitialization,
                    retransformation,
                    entries);
        }

        @Override
        public AgentBuilder allowRetransformation() {
            return new Default(byteBuddy,
                    binaryLocator,
                    listener,
                    nativeMethodPrefix,
                    disableSelfInitialization,
                    true,
                    entries);
        }

        @Override
        public AgentBuilder disableSelfInitialization() {
            return new Default(byteBuddy,
                    binaryLocator,
                    listener,
                    nativeMethodPrefix,
                    true,
                    retransformation,
                    entries);
        }

        @Override
        public ClassFileTransformer makeRaw() {
            return new ExecutingTransformer();
        }

        @Override
        public ClassFileTransformer registerWith(Instrumentation instrumentation) {
            ClassFileTransformer classFileTransformer = makeRaw();
            if (!NO_NATIVE_PREFIX.equals(nonNull(nativeMethodPrefix))) {
                instrumentation.setNativeMethodPrefix(classFileTransformer, nativeMethodPrefix);
            }
            instrumentation.addTransformer(classFileTransformer, retransformation);
            return classFileTransformer;
        }

        @Override
        public ClassFileTransformer registerWithByteBuddyAgent() {
            try {
                return registerWith((Instrumentation) ClassLoader.getSystemClassLoader()
                        .loadClass(BYTE_BUDDY_AGENT_TYPE)
                        .getDeclaredMethod(GET_INSTRUMENTATION_METHOD)
                        .invoke(STATIC_METHOD));
            } catch (Exception e) {
                throw new IllegalStateException("The Byte Buddy agent is not installed or not accessible", e);
            }
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (other == null || getClass() != other.getClass()) return false;
            Default aDefault = (Default) other;
            return binaryLocator.equals(aDefault.binaryLocator)
                    && byteBuddy.equals(aDefault.byteBuddy)
                    && listener.equals(aDefault.listener)
                    && nativeMethodPrefix.equals(aDefault.nativeMethodPrefix)
                    && disableSelfInitialization == aDefault.disableSelfInitialization
                    && retransformation == aDefault.retransformation
                    && entries.equals(aDefault.entries);

        }

        @Override
        public int hashCode() {
            int result = byteBuddy.hashCode();
            result = 31 * result + binaryLocator.hashCode();
            result = 31 * result + listener.hashCode();
            result = 31 * result + nativeMethodPrefix.hashCode();
            result = 31 * result + (disableSelfInitialization ? 1 : 0);
            result = 31 * result + (retransformation ? 1 : 0);
            result = 31 * result + entries.hashCode();
            return result;
        }

        @Override
        public String toString() {
            return "AgentBuilder.Default{" +
                    "byteBuddy=" + byteBuddy +
                    ", binaryLocator=" + binaryLocator +
                    ", listener=" + listener +
                    ", nativeMethodPrefix=" + nativeMethodPrefix +
                    ", disableSelfInitialization=" + disableSelfInitialization +
                    ", retransformation=" + retransformation +
                    ", entries=" + entries +
                    '}';
        }

        protected class ExecutingTransformer implements ClassFileTransformer {

            private final MethodRebaseResolver.MethodNameTransformer methodNameTransformer;

            private final InitializationStrategy initializationStrategy;

            private final Set<String> ignoredTypes;

            public ExecutingTransformer() {
                methodNameTransformer = NO_NATIVE_PREFIX.equals(nativeMethodPrefix)
                        ? new MethodRebaseResolver.MethodNameTransformer.Suffixing()
                        : new MethodRebaseResolver.MethodNameTransformer.Prefixing(nativeMethodPrefix);
                initializationStrategy = disableSelfInitialization
                        ? InitializationStrategy.NoOp.INSTANCE
                        : new InitializationStrategy.SelfInjection();
                ignoredTypes = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
            }

            @Override
            public byte[] transform(ClassLoader classLoader,
                                    String internalTypeName,
                                    Class<?> classBeingRedefined,
                                    ProtectionDomain protectionDomain,
                                    byte[] binaryRepresentation) {
                String binaryTypeName = internalTypeName.replace('/', '.');
                if (ignoredTypes.remove(binaryTypeName)) {
                    return NO_TRANSFORMATION;
                }
                try {
                    BinaryLocator.Initialized initialized = binaryLocator.initialize(binaryTypeName, binaryRepresentation, classLoader);
                    TypeDescription typeDescription = initialized.getTypePool().describe(binaryTypeName);
                    for (Entry entry : entries) {
                        if (entry.matches(typeDescription, classLoader, classBeingRedefined, protectionDomain)) {
                            DynamicType.Unloaded<?> dynamicType = initializationStrategy.apply(
                                    entry.transform(byteBuddy.rebase(typeDescription,
                                            initialized.getClassFileLocator(),
                                            methodNameTransformer))).make();
                            Map<TypeDescription, LoadedTypeInitializer> loadedTypeInitializers = dynamicType.getLoadedTypeInitializers();
                            if (loadedTypeInitializers.size() > 1) {
                                ClassLoaderByteArrayInjector injector = new ClassLoaderByteArrayInjector(classLoader, protectionDomain);
                                for (Map.Entry<TypeDescription, byte[]> auxiliary : dynamicType.getRawAuxiliaryTypes().entrySet()) {
                                    ignoredTypes.add(auxiliary.getKey().getName());
                                    Class<?> type = injector.inject(auxiliary.getKey().getName(), auxiliary.getValue());
                                    loadedTypeInitializers.get(auxiliary.getValue()).onLoad(type);
                                }
                            }
                            initializationStrategy.register(binaryTypeName,
                                    classLoader,
                                    loadedTypeInitializers.get(dynamicType.getTypeDescription()));
                            listener.onTransformation(dynamicType);
                            return dynamicType.getBytes();
                        }
                    }
                    return NO_TRANSFORMATION;
                } catch (Throwable throwable) {
                    listener.onError(throwable);
                    return NO_TRANSFORMATION;
                } finally {
                    listener.afterTransformation(binaryTypeName);
                }
            }

            @Override
            public String toString() {
                return "AgentBuilder.Default.ExecutingTransformer{" +
                        "agentBuilder=" + Default.this +
                        ", methodNameTransformer=" + methodNameTransformer +
                        ", ignoredTypes=" + ignoredTypes +
                        '}';
            }
        }

        protected static class Entry implements RawMatcher, Transformer {

            private final RawMatcher rawMatcher;

            private final Transformer transformer;

            public Entry(RawMatcher rawMatcher, Transformer transformer) {
                this.rawMatcher = rawMatcher;
                this.transformer = transformer;
            }

            @Override
            public boolean matches(TypeDescription typeDescription,
                                   ClassLoader classLoader,
                                   Class<?> classBeingRedefined,
                                   ProtectionDomain protectionDomain) {
                return rawMatcher.matches(typeDescription, classLoader, classBeingRedefined, protectionDomain);
            }

            @Override
            public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder) {
                return transformer.transform(builder);
            }

            @Override
            public boolean equals(Object other) {
                return this == other || !(other == null || getClass() != other.getClass())
                        && rawMatcher.equals(((Entry) other).rawMatcher)
                        && transformer.equals(((Entry) other).transformer);
            }

            @Override
            public int hashCode() {
                int result = rawMatcher.hashCode();
                result = 31 * result + transformer.hashCode();
                return result;
            }

            @Override
            public String toString() {
                return "AgentBuilder.Default.Entry{" +
                        "rawMatcher=" + rawMatcher +
                        ", transformer=" + transformer +
                        '}';
            }
        }

        protected class Matched implements Transformable.Extendable {

            private final RawMatcher rawMatcher;

            private final Transformer transformer;

            public Matched(RawMatcher rawMatcher,
                           Transformer transformer) {
                this.rawMatcher = rawMatcher;
                this.transformer = transformer;
            }

            @Override
            public Transformable.Extendable transform(Transformer transformer) {
                return new Matched(rawMatcher, new Transformer.Compound(this.transformer, nonNull(transformer)));
            }

            @Override
            public Transformable redefine(RawMatcher rawMatcher) {
                return materialize().redefine(rawMatcher);
            }

            @Override
            public Transformable redefine(ElementMatcher<? super TypeDescription> typeMatcher) {
                return materialize().redefine(typeMatcher);
            }

            @Override
            public Transformable redefine(ElementMatcher<? super TypeDescription> typeMatcher,
                                          ElementMatcher<? super ClassLoader> classLoaderMatcher) {
                return materialize().redefine(typeMatcher, classLoaderMatcher);
            }

            @Override
            public AgentBuilder withByteBuddy(ByteBuddy byteBuddy) {
                return materialize().withByteBuddy(byteBuddy);
            }

            @Override
            public AgentBuilder withListener(Listener listener) {
                return materialize().withListener(listener);
            }

            @Override
            public AgentBuilder withNativeMethodPrefix(String prefix) {
                return materialize().withNativeMethodPrefix(prefix);
            }

            @Override
            public AgentBuilder disableSelfInitialization() {
                return materialize().disableSelfInitialization();
            }

            @Override
            public AgentBuilder allowRetransformation() {
                return materialize().allowRetransformation();
            }

            @Override
            public ClassFileTransformer makeRaw() {
                return materialize().makeRaw();
            }

            @Override
            public ClassFileTransformer registerWith(Instrumentation instrumentation) {
                return materialize().registerWith(instrumentation);
            }

            @Override
            public ClassFileTransformer registerWithByteBuddyAgent() {
                return materialize().registerWithByteBuddyAgent();
            }

            protected AgentBuilder materialize() {
                return new Default(byteBuddy,
                        binaryLocator,
                        listener,
                        nativeMethodPrefix,
                        disableSelfInitialization,
                        retransformation,
                        join(new Entry(rawMatcher, transformer), entries));
            }

            private Default getOuter() {
                return Default.this;
            }

            @Override
            public boolean equals(Object other) {
                return this == other || !(other == null || getClass() != other.getClass())
                        && rawMatcher.equals(((Matched) other).rawMatcher)
                        && transformer.equals(((Matched) other).transformer)
                        && Default.this.equals(((Matched) other).getOuter());
            }

            @Override
            public int hashCode() {
                int result = rawMatcher.hashCode();
                result = 31 * result + transformer.hashCode();
                result = 31 * result + Default.this.hashCode();
                return result;
            }

            @Override
            public String toString() {
                return "AgentBuilder.Default.Matched{" +
                        "rawMatcher=" + rawMatcher +
                        ", transformer=" + transformer +
                        ", agentBuilder=" + Default.this +
                        '}';
            }
        }

        public static interface InitializationStrategy {

            static class SelfInjection implements InitializationStrategy, net.bytebuddy.instrumentation.Instrumentation {

                private final Nexus.Accessor accessor;

                public SelfInjection() {
                    accessor = Nexus.Accessor.INSTANCE;
                }

                @Override
                public DynamicType.Builder<?> apply(DynamicType.Builder<?> builder) {
                    return builder.invokable(none()).intercept(this);
                }

                @Override
                public InstrumentedType prepare(InstrumentedType instrumentedType) {
                    return instrumentedType.withInitializer(accessor.initializerFor(instrumentedType));
                }

                @Override
                public ByteCodeAppender appender(Target instrumentationTarget) {
                    throw new IllegalStateException("The initialization strategy instrumentation must not be used");
                }

                @Override
                public void register(String name, ClassLoader classLoader, LoadedTypeInitializer loadedTypeInitializer) {
                    if (loadedTypeInitializer.isAlive()) {
                        accessor.register(name, classLoader, loadedTypeInitializer);
                    }
                }

                public static class Nexus {

                    private final String name;

                    private final ClassLoader classLoader;

                    public Nexus(Class<?> type) {
                        name = type.getName();
                        classLoader = type.getClassLoader();
                    }

                    public Nexus(String name, ClassLoader classLoader) {
                        this.name = name;
                        this.classLoader = classLoader;
                    }

                    @Override
                    public boolean equals(Object other) {
                        return this == other || !(other == null || getClass() != other.getClass())
                                && classLoader.equals(((Nexus) other).classLoader)
                                && name.equals(((Nexus) other).name);
                    }

                    @Override
                    public int hashCode() {
                        int result = name.hashCode();
                        result = 31 * result + classLoader.hashCode();
                        return result;
                    }

                    @Override
                    public String toString() {
                        return "AgentBuilder.Default.InitializationStrategy.SelfInjection.Nexus{" +
                                "name='" + name + '\'' +
                                ", classLoader=" + classLoader +
                                '}';
                    }

                    private static final ConcurrentMap<Nexus, Object> TYPE_INITIALIZERS = new ConcurrentHashMap<Nexus, Object>();

                    public static void initialize(Class<?> type)
                            throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
                        Object typeInitializer = TYPE_INITIALIZERS.remove(new Nexus(type));
                        if (typeInitializer != null) {
                            typeInitializer.getClass().getMethod("onLoad", Class.class).invoke(typeInitializer, type);
                        }
                    }

                    public static void register(String name, ClassLoader classLoader, Object typeInitializer) {
                        TYPE_INITIALIZERS.put(new Nexus(name, classLoader), typeInitializer);
                    }

                    protected static enum Accessor {

                        INSTANCE;

                        private final Method registration;

                        private final MethodDescription systemClassLoader;

                        private final MethodDescription loadClass;

                        private final MethodDescription findMethod;

                        private final MethodDescription invokeMethod;

                        private Accessor() {
                            try {
                                ClassLoader classLoader = ClassLoader.getSystemClassLoader();
                                ClassLoaderByteArrayInjector injector = new ClassLoaderByteArrayInjector(classLoader);
                                Class<?> nexus = injector.inject(Nexus.class.getName(), new StreamDrainer().drain(classLoader
                                        .getResourceAsStream(Nexus.class.getName().replace('.', '/') + ".class")));
                                registration = nexus.getDeclaredMethod("register", String.class, ClassLoader.class, Object.class);
                                systemClassLoader = new TypeDescription.ForLoadedType(ClassLoader.class).getDeclaredMethods()
                                        .filter(named("getSystemClassLoader")).getOnly();
                                loadClass = new TypeDescription.ForLoadedType(ClassLoader.class).getDeclaredMethods()
                                        .filter(named("loadClass").and(takesArguments(String.class))).getOnly();
                                findMethod = new TypeDescription.ForLoadedType(Class.class).getDeclaredMethods()
                                        .filter(named("getDeclaredMethod").and(takesArguments(String.class, Class[].class))).getOnly();
                                invokeMethod = new TypeDescription.ForLoadedType(Class.class).getDeclaredMethods()
                                        .filter(named("getDeclaredMethod").and(takesArguments(String.class, Class[].class))).getOnly();
                            } catch (Exception e) {
                                throw new IllegalStateException("Cannot create type initialization accessor", e);
                            }
                        }

                        public void register(String name, ClassLoader classLoader, Object typeInitializer) {
                            try {
                                registration.invoke(null, name, classLoader, typeInitializer);
                            } catch (IllegalAccessException e) {
                                throw new IllegalStateException("Cannot register type initializer for " + name, e);
                            } catch (InvocationTargetException e) {
                                throw new IllegalStateException("Cannot register type initializer for " + name, e.getCause());
                            }
                        }

                        public StackManipulation initializerFor(TypeDescription instrumentedType) {
                            return new StackManipulation.Compound(
                                    MethodInvocation.invoke(systemClassLoader),
                                    new TextConstant(Nexus.class.getName()),
                                    MethodInvocation.invoke(loadClass),
                                    new TextConstant("initialize"),
                                    ArrayFactory.targeting(new TypeDescription.ForLoadedType(Class.class))
                                            .withValues(Collections.singletonList(ClassConstant.of(new TypeDescription.ForLoadedType(Class.class)))),
                                    MethodInvocation.invoke(findMethod),
                                    NullConstant.INSTANCE,
                                    ArrayFactory.targeting(new TypeDescription.ForLoadedType(Object.class))
                                            .withValues(Collections.singletonList(ClassConstant.of(instrumentedType))),
                                    MethodInvocation.invoke(invokeMethod),
                                    Removal.SINGLE
                            );
                        }
                    }
                }
            }

            static enum NoOp implements InitializationStrategy {

                INSTANCE;

                @Override
                public DynamicType.Builder<?> apply(DynamicType.Builder<?> builder) {
                    return builder;
                }

                @Override
                public void register(String name, ClassLoader classLoader, LoadedTypeInitializer loadedTypeInitializer) {
                    /* do nothing */
                }
            }

            DynamicType.Builder<?> apply(DynamicType.Builder<?> builder);

            void register(String name, ClassLoader classLoader, LoadedTypeInitializer loadedTypeInitializer);
        }
    }

    static interface RawMatcher {

        boolean matches(TypeDescription typeDescription,
                        ClassLoader classLoader,
                        Class<?> classBeingRedefined,
                        ProtectionDomain protectionDomain);

        static class ForElementMatcherPair implements RawMatcher {

            private final ElementMatcher<? super TypeDescription> typeMatcher;

            private final ElementMatcher<? super ClassLoader> classLoaderMatcher;

            public ForElementMatcherPair(ElementMatcher<? super TypeDescription> typeMatcher,
                                         ElementMatcher<? super ClassLoader> classLoaderMatcher) {
                this.typeMatcher = typeMatcher;
                this.classLoaderMatcher = classLoaderMatcher;
            }

            @Override
            public boolean matches(TypeDescription typeDescription,
                                   ClassLoader classLoader,
                                   Class<?> classBeingRedefined,
                                   ProtectionDomain protectionDomain) {
                return classLoaderMatcher.matches(classLoader) && typeMatcher.matches(typeDescription);
            }

            @Override
            public boolean equals(Object other) {
                return this == other || !(other == null || getClass() != other.getClass())
                        && classLoaderMatcher.equals(((ForElementMatcherPair) other).classLoaderMatcher)
                        && typeMatcher.equals(((ForElementMatcherPair) other).typeMatcher);
            }

            @Override
            public int hashCode() {
                int result = typeMatcher.hashCode();
                result = 31 * result + classLoaderMatcher.hashCode();
                return result;
            }

            @Override
            public String toString() {
                return "AgentBuilder.RawMatcher.ForElementMatcherPair{" +
                        "typeMatcher=" + typeMatcher +
                        ", classLoaderMatcher=" + classLoaderMatcher +
                        '}';
            }
        }
    }

    static interface Transformer {

        static enum NoOp implements Transformer {

            INSTANCE;

            @Override
            public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder) {
                return builder;
            }
        }

        static class Compound implements Transformer {

            private final Transformer[] transformer;

            public Compound(Transformer... transformer) {
                this.transformer = transformer;
            }

            @Override
            public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder) {
                for (Transformer transformer : this.transformer) {
                    builder = transformer.transform(builder);
                }
                return builder;
            }

            @Override
            public boolean equals(Object other) {
                return this == other || !(other == null || getClass() != other.getClass())
                        && Arrays.equals(transformer, ((Compound) other).transformer);
            }

            @Override
            public int hashCode() {
                return Arrays.hashCode(transformer);
            }

            @Override
            public String toString() {
                return "AgentBuilder.Transformer.Compound{" +
                        "transformer=" + Arrays.toString(transformer) +
                        '}';
            }
        }

        DynamicType.Builder<?> transform(DynamicType.Builder<?> builder);
    }

    static interface BinaryLocator {

        Initialized initialize(String typeName, byte[] binaryRepresentation, ClassLoader classLoader);

        static enum Default implements BinaryLocator {

            INSTANCE;

            @Override
            public BinaryLocator.Initialized initialize(String typeName,
                                                        byte[] binaryRepresentation,
                                                        ClassLoader classLoader) {
                return new Initialized(typeName, binaryRepresentation, classLoader);
            }


            protected static class Initialized implements BinaryLocator.Initialized, ClassFileLocator {

                private final String typeName;

                private final byte[] binaryRepresentation;

                private final TypePool typePool;

                private final ClassFileLocator classFileLocator;

                public Initialized(String typeName, byte[] binaryRepresentation, ClassLoader classLoader) {
                    this.typeName = typeName;
                    this.binaryRepresentation = binaryRepresentation;
                    this.typePool = new TypePool.Default(new TypePool.CacheProvider.Simple(), new TypePool.SourceLocator.ForClassLoader(classLoader));
                    this.classFileLocator = new ClassFileLocator.ForClassLoader(classLoader);
                }

                @Override
                public TypePool getTypePool() {
                    return typePool;
                }

                @Override
                public ClassFileLocator getClassFileLocator() {
                    return this;
                }

                @Override
                public TypeDescription.BinaryRepresentation classFileFor(TypeDescription typeDescription) throws IOException {
                    return typeDescription.getName().equals(typeName)
                            ? new TypeDescription.BinaryRepresentation.Explicit(binaryRepresentation)
                            : classFileLocator.classFileFor(typeDescription);
                }

                @Override
                public boolean equals(Object other) {
                    if (this == other) return true;
                    if (other == null || getClass() != other.getClass()) return false;
                    Initialized that = (Initialized) other;
                    return Arrays.equals(binaryRepresentation, that.binaryRepresentation)
                            && classFileLocator.equals(that.classFileLocator)
                            && typeName.equals(that.typeName)
                            && typePool.equals(that.typePool);
                }

                @Override
                public int hashCode() {
                    int result = typeName.hashCode();
                    result = 31 * result + Arrays.hashCode(binaryRepresentation);
                    result = 31 * result + typePool.hashCode();
                    result = 31 * result + classFileLocator.hashCode();
                    return result;
                }

                @Override
                public String toString() {
                    return "AgentBuilder.BinaryLocator.Default.Initialized{" +
                            "typeName='" + typeName + '\'' +
                            ", binaryRepresentation=" + Arrays.toString(binaryRepresentation) +
                            ", typePool=" + typePool +
                            ", classFileLocator=" + classFileLocator +
                            '}';
                }
            }
        }

        static interface Initialized {

            TypePool getTypePool();

            ClassFileLocator getClassFileLocator();
        }
    }

    static interface Listener {

        void onTransformation(DynamicType dynamicType);

        void onError(Throwable throwable);

        void afterTransformation(String typeName);

        static enum NoOp implements Listener {

            INSTANCE;

            @Override
            public void onTransformation(DynamicType dynamicType) {
                /* do nothing */
            }

            @Override
            public void onError(Throwable throwable) {
                /* do nothing */
            }

            @Override
            public void afterTransformation(String typeName) {
                /* do nothing */
            }
        }

        static class Compound implements Listener {

            private final Listener[] listener;

            public Compound(Listener... listener) {
                this.listener = listener;
            }

            @Override
            public void onTransformation(DynamicType dynamicType) {
                for (Listener listener : this.listener) {
                    listener.onTransformation(dynamicType);
                }
            }

            @Override
            public void onError(Throwable throwable) {
                for (Listener listener : this.listener) {
                    listener.onError(throwable);
                }
            }

            @Override
            public void afterTransformation(String typeName) {
                for (Listener listener : this.listener) {
                    listener.afterTransformation(typeName);
                }
            }

            @Override
            public boolean equals(Object other) {
                return this == other || !(other == null || getClass() != other.getClass())
                        && Arrays.equals(listener, ((Compound) other).listener);
            }

            @Override
            public int hashCode() {
                return Arrays.hashCode(listener);
            }

            @Override
            public String toString() {
                return "AgentBuilder.ExceptionRegistrant.Compound{" +
                        "exceptionRegistrant=" + Arrays.toString(listener) +
                        '}';
            }
        }
    }
}