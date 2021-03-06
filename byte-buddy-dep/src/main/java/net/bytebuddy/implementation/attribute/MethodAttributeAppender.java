package net.bytebuddy.implementation.attribute;

import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.method.ParameterDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.objectweb.asm.MethodVisitor;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static net.bytebuddy.matcher.ElementMatchers.none;

/**
 * An appender that writes attributes or annotations to a given ASM {@link org.objectweb.asm.MethodVisitor}.
 */
public interface MethodAttributeAppender {

    /**
     * Applies this attribute appender to a given method visitor.
     *
     * @param methodVisitor     The method visitor to which the attributes that are represented by this attribute
     *                          appender are written to.
     * @param methodDescription The description of the method for which the given method visitor creates an
     *                          instrumentation for.
     */
    void apply(MethodVisitor methodVisitor, MethodDescription methodDescription);

    /**
     * A method attribute appender that does not append any attributes.
     */
    enum NoOp implements MethodAttributeAppender, Factory {

        /**
         * The singleton instance.
         */
        INSTANCE;

        @Override
        public MethodAttributeAppender make(TypeDescription typeDescription) {
            return this;
        }

        @Override
        public void apply(MethodVisitor methodVisitor, MethodDescription methodDescription) {
            /* do nothing */
        }

        @Override
        public String toString() {
            return "MethodAttributeAppender.NoOp." + name();
        }
    }

    /**
     * Implementation of a method attribute appender that writes all annotations of the instrumented method to the
     * method that is being created. This includes method and parameter annotations.
     */
    enum ForInstrumentedMethod implements MethodAttributeAppender, Factory {

        /**
         * The singleton instance.
         */
        INSTANCE;

        @Override
        public void apply(MethodVisitor methodVisitor, MethodDescription methodDescription) {
            AnnotationAppender methodAppender = new AnnotationAppender.Default(new AnnotationAppender.Target.OnMethod(methodVisitor));
            for (AnnotationDescription annotation : methodDescription.getDeclaredAnnotations()) {
                methodAppender.append(annotation, none(), AnnotationAppender.AnnotationVisibility.of(annotation));
            }
            int index = 0;
            for (ParameterDescription parameterDescription : methodDescription.getParameters()) {
                AnnotationAppender parameterAppender = new AnnotationAppender.Default(new AnnotationAppender.Target.OnMethodParameter(methodVisitor, index++));
                for (AnnotationDescription annotation : parameterDescription.getDeclaredAnnotations()) {
                    parameterAppender.append(annotation, none(), AnnotationAppender.AnnotationVisibility.of(annotation));
                }
            }
        }

        @Override
        public MethodAttributeAppender make(TypeDescription typeDescription) {
            return this;
        }

        @Override
        public String toString() {
            return "MethodAttributeAppender.ForInstrumentedMethod." + name();
        }
    }

    /**
     * A factory that creates method attribute appenders for a given type.
     */
    interface Factory {

        /**
         * Returns a method attribute appender that is applicable for a given type description.
         *
         * @param typeDescription The type for which a method attribute appender is to be applied for.
         * @return The method attribute appender which should be applied for the given type.
         */
        MethodAttributeAppender make(TypeDescription typeDescription);

        /**
         * A method attribute appender factory that combines several method attribute appender factories to be
         * represented as a single factory.
         */
        class Compound implements Factory {

            /**
             * The factories this compound factory represents in their application order.
             */
            private final Factory[] factory;

            /**
             * Creates a new compound method attribute appender factory.
             *
             * @param factory The factories that are to be combined by this compound factory in the order of their
             *                application.
             */
            public Compound(Factory... factory) {
                this.factory = factory;
            }

            @Override
            public MethodAttributeAppender make(TypeDescription typeDescription) {
                MethodAttributeAppender[] methodAttributeAppender = new MethodAttributeAppender[factory.length];
                int index = 0;
                for (Factory factory : this.factory) {
                    methodAttributeAppender[index++] = factory.make(typeDescription);
                }
                return new MethodAttributeAppender.Compound(methodAttributeAppender);
            }

            @Override
            public boolean equals(Object other) {
                return this == other || !(other == null || getClass() != other.getClass())
                        && Arrays.equals(factory, ((Compound) other).factory);
            }

            @Override
            public int hashCode() {
                return Arrays.hashCode(factory);
            }

            @Override
            public String toString() {
                return "MethodAttributeAppender.Factory.Compound{factory=" + Arrays.toString(factory) + '}';
            }
        }
    }

    /**
     * Appends an annotation to a method or method parameter. The visibility of the annotation is determined by the
     * annotation type's {@link java.lang.annotation.RetentionPolicy} annotation.
     */
    class ForAnnotation implements MethodAttributeAppender, Factory {

        /**
         * the annotations this method attribute appender is writing to its target.
         */
        private final List<? extends AnnotationDescription> annotations;

        /**
         * A matcher to identify default properties.
         */
        private final ElementMatcher<? super MethodDescription> defaultProperties;

        /**
         * The target to which the annotations are written to.
         */
        private final Target target;


        /**
         * Create a new annotation appender for a method.
         *
         * @param annotations The annotations to append to the target method.
         */
        public ForAnnotation(List<? extends AnnotationDescription> annotations) {
            this(annotations, none());
        }

        /**
         * Create a new annotation appender for a method parameter.
         *
         * @param parameterIndex The index of the target parameter.
         * @param annotations    The annotations to append to the target method parameter.
         */
        public ForAnnotation(int parameterIndex, List<? extends AnnotationDescription> annotations) {
            this(parameterIndex, annotations, none());
        }

        /**
         * Create a new annotation appender for a method.
         *
         * @param annotations       The annotations to append to the target method.
         * @param defaultProperties A matcher to identify default properties.
         */
        public ForAnnotation(List<? extends AnnotationDescription> annotations, ElementMatcher<? super MethodDescription> defaultProperties) {
            this.annotations = annotations;
            this.defaultProperties = defaultProperties;
            target = Target.OnMethod.INSTANCE;
        }

        /**
         * Create a new annotation appender for a method parameter.
         *
         * @param parameterIndex    The index of the target parameter.
         * @param annotations       The annotations to append to the target method parameter.
         * @param defaultProperties A matcher to identify default properties.
         */
        public ForAnnotation(int parameterIndex,
                             List<? extends AnnotationDescription> annotations,
                             ElementMatcher<? super MethodDescription> defaultProperties) {
            this.annotations = annotations;
            this.defaultProperties = defaultProperties;
            target = new Target.OnMethodParameter(parameterIndex);
        }

        @Override
        public void apply(MethodVisitor methodVisitor, MethodDescription methodDescription) {
            AnnotationAppender appender = new AnnotationAppender.Default(target.make(methodVisitor, methodDescription));
            for (AnnotationDescription annotation : this.annotations) {
                appender.append(annotation, defaultProperties, AnnotationAppender.AnnotationVisibility.of(annotation));
            }
        }

        @Override
        public MethodAttributeAppender make(TypeDescription typeDescription) {
            return this;
        }

        @Override
        public boolean equals(Object other) {
            return this == other || !(other == null || getClass() != other.getClass())
                    && annotations.equals(((ForAnnotation) other).annotations)
                    && defaultProperties.equals(((ForAnnotation) other).defaultProperties)
                    && target.equals(((ForAnnotation) other).target);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * annotations.hashCode() + defaultProperties.hashCode()) + target.hashCode();
        }

        @Override
        public String toString() {
            return "MethodAttributeAppender.ForAnnotation{" +
                    "annotations=" + annotations +
                    ", defaultProperties=" + defaultProperties +
                    ", target=" + target +
                    '}';
        }

        /**
         * Represents the target on which this method attribute appender should write its annotations to.
         */
        protected interface Target {

            /**
             * Materializes the target for a given creation process.
             *
             * @param methodVisitor     The method visitor to which the attributes that are represented by this
             *                          attribute appender are written to.
             * @param methodDescription The description of the method for which the given method visitor creates an
             *                          instrumentation for.
             * @return The target of the annotation appender this target represents.
             */
            AnnotationAppender.Target make(MethodVisitor methodVisitor, MethodDescription methodDescription);

            /**
             * A method attribute appender target for writing annotations directly onto the method.
             */
            enum OnMethod implements Target {

                /**
                 * The singleton instance.
                 */
                INSTANCE;

                @Override
                public AnnotationAppender.Target make(MethodVisitor methodVisitor, MethodDescription methodDescription) {
                    return new AnnotationAppender.Target.OnMethod(methodVisitor);
                }

                @Override
                public String toString() {
                    return "MethodAttributeAppender.ForAnnotation.Target.OnMethod." + name();
                }
            }

            /**
             * A method attribute appender target for writing annotations onto a given method parameter.
             */
            class OnMethodParameter implements Target {

                /**
                 * The index of the parameter to write the annotation to.
                 */
                private final int parameterIndex;

                /**
                 * Creates a target for a method attribute appender for a method parameter of the given index.
                 *
                 * @param parameterIndex The index of the target parameter.
                 */
                protected OnMethodParameter(int parameterIndex) {
                    this.parameterIndex = parameterIndex;
                }

                @Override
                public AnnotationAppender.Target make(MethodVisitor methodVisitor, MethodDescription methodDescription) {
                    if (parameterIndex >= methodDescription.getParameters().size()) {
                        throw new IllegalArgumentException("Method " + methodDescription + " has less then " + parameterIndex + " parameters");
                    }
                    return new AnnotationAppender.Target.OnMethodParameter(methodVisitor, parameterIndex);
                }

                @Override
                public boolean equals(Object other) {
                    return this == other || !(other == null || getClass() != other.getClass())
                            && parameterIndex == ((OnMethodParameter) other).parameterIndex;
                }

                @Override
                public int hashCode() {
                    return parameterIndex;
                }

                @Override
                public String toString() {
                    return "MethodAttributeAppender.ForAnnotation.Target.OnMethodParameter{parameterIndex=" + parameterIndex + '}';
                }
            }
        }
    }

    /**
     * Implementation of a method attribute appender that writes all annotations of a given loaded method to the
     * method that is being created. This includes method and parameter annotations. In order to being able to do so,
     * the target method and the given method must have compatible signatures, i.e. an identical number of method
     * parameters. Otherwise, an exception is thrown when this attribute appender is applied on a method.
     */
    class ForMethod implements MethodAttributeAppender, Factory {

        /**
         * The method of which the annotations are to be copied.
         */
        private final MethodDescription methodDescription;

        /**
         * A matcher to identify default properties.
         */
        private final ElementMatcher<? super MethodDescription> defaultProperties;

        /**
         * Creates a method attribute appender that copies the annotations of the given constructor.
         *
         * @param constructor The constructor to copy the attributes from.
         */
        public ForMethod(Constructor<?> constructor) {
            this(new MethodDescription.ForLoadedConstructor(constructor));
        }

        /**
         * Creates a method attribute appender that copies the annotations of the given method.
         *
         * @param method The method to copy the attributes from.
         */
        public ForMethod(Method method) {
            this(new MethodDescription.ForLoadedMethod(method));
        }

        /**
         * Creates a method attribute appender that copies the annotations of the given method description.
         *
         * @param methodDescription The method description to copy the attributes from.
         */
        public ForMethod(MethodDescription methodDescription) {
            this(methodDescription, none());
        }

        /**
         * Creates a method attribute appender that copies the annotations of the given constructor.
         *
         * @param constructor       The constructor to copy the attributes from.
         * @param defaultProperties A matcher to identify default properties.
         */
        public ForMethod(Constructor<?> constructor, ElementMatcher<? super MethodDescription> defaultProperties) {
            this(new MethodDescription.ForLoadedConstructor(constructor), defaultProperties);
        }

        /**
         * Creates a method attribute appender that copies the annotations of the given method.
         *
         * @param method            The method to copy the attributes from.
         * @param defaultProperties A matcher to identify default properties.
         */
        public ForMethod(Method method, ElementMatcher<? super MethodDescription> defaultProperties) {
            this(new MethodDescription.ForLoadedMethod(method), defaultProperties);
        }

        /**
         * Creates a method attribute appender that copies the annotations of the given method description.
         *
         * @param methodDescription The method description to copy the attributes from.
         * @param defaultProperties A matcher to identify default properties.
         */
        public ForMethod(MethodDescription methodDescription, ElementMatcher<? super MethodDescription> defaultProperties) {
            this.methodDescription = methodDescription;
            this.defaultProperties = defaultProperties;
        }

        @Override
        public void apply(MethodVisitor methodVisitor, MethodDescription methodDescription) {
            if (this.methodDescription.getParameters().size() > methodDescription.getParameters().size()) {
                throw new IllegalArgumentException(this.methodDescription + " has more parameters than the instrumented method " + methodDescription);
            }
            AnnotationAppender methodAppender = new AnnotationAppender.Default(new AnnotationAppender.Target.OnMethod(methodVisitor));
            for (AnnotationDescription annotation : this.methodDescription.getDeclaredAnnotations()) {
                methodAppender.append(annotation, defaultProperties, AnnotationAppender.AnnotationVisibility.of(annotation));
            }
            int index = 0;
            for (ParameterDescription parameterDescription : this.methodDescription.getParameters()) {
                AnnotationAppender parameterAppender = new AnnotationAppender.Default(new AnnotationAppender.Target.OnMethodParameter(methodVisitor, index++));
                for (AnnotationDescription annotation : parameterDescription.getDeclaredAnnotations()) {
                    parameterAppender.append(annotation, defaultProperties, AnnotationAppender.AnnotationVisibility.of(annotation));
                }
            }
        }

        @Override
        public MethodAttributeAppender make(TypeDescription typeDescription) {
            return this;
        }

        @Override
        public boolean equals(Object other) {
            return this == other || !(other == null || getClass() != other.getClass())
                    && methodDescription.equals(((ForMethod) other).methodDescription)
                    && defaultProperties.equals(((ForMethod) other).defaultProperties);
        }

        @Override
        public int hashCode() {
            return 31 * methodDescription.hashCode() + defaultProperties.hashCode();
        }

        @Override
        public String toString() {
            return "MethodAttributeAppender.ForMethod{" +
                    "methodDescription=" + methodDescription +
                    ", defaultProperties=" + defaultProperties +
                    '}';
        }
    }

    /**
     * A method attribute appender that combines several method attribute appenders to be represented as a single
     * method attribute appender.
     */
    class Compound implements MethodAttributeAppender {

        /**
         * The method attribute appenders this compound appender represents in their application order.
         */
        private final MethodAttributeAppender[] methodAttributeAppender;

        /**
         * Creates a new compound method attribute appender.
         *
         * @param methodAttributeAppender The method attribute appenders that are to be combined by this compound appender
         *                                in the order of their application.
         */
        public Compound(MethodAttributeAppender... methodAttributeAppender) {
            this.methodAttributeAppender = methodAttributeAppender;
        }

        @Override
        public void apply(MethodVisitor methodVisitor, MethodDescription methodDescription) {
            for (MethodAttributeAppender methodAttributeAppender : this.methodAttributeAppender) {
                methodAttributeAppender.apply(methodVisitor, methodDescription);
            }
        }

        @Override
        public boolean equals(Object other) {
            return this == other || !(other == null || getClass() != other.getClass())
                    && Arrays.equals(methodAttributeAppender, ((Compound) other).methodAttributeAppender);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(methodAttributeAppender);
        }

        @Override
        public String toString() {
            return "MethodAttributeAppender.Compound{methodAttributeAppender=" + Arrays.toString(methodAttributeAppender) + '}';
        }
    }
}
