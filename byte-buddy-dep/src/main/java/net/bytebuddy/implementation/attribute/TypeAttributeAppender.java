package net.bytebuddy.implementation.attribute;

import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.objectweb.asm.ClassVisitor;

import java.util.Arrays;
import java.util.List;

import static net.bytebuddy.matcher.ElementMatchers.none;

/**
 * An appender that writes attributes or annotations to a given ASM {@link org.objectweb.asm.ClassVisitor}.
 */
public interface TypeAttributeAppender {

    /**
     * Applies this type attribute appender.
     *
     * @param classVisitor    The class visitor to which the annotations of this visitor should be written to.
     * @param typeDescription A description of the instrumented type that is target of the ongoing instrumentation.
     */
    void apply(ClassVisitor classVisitor, TypeDescription typeDescription);

    /**
     * A type attribute appender that does not append any attributes.
     */
    enum NoOp implements TypeAttributeAppender {

        /**
         * The singleton instance.
         */
        INSTANCE;

        @Override
        public void apply(ClassVisitor classVisitor, TypeDescription typeDescription) {
            /* do nothing */
        }

        @Override
        public String toString() {
            return "TypeAttributeAppender.NoOp." + name();
        }
    }

    /**
     * An attribute appender that writes all annotations that are found on a given target type to the
     * instrumented type this type attribute appender is applied onto. The visibility for the annotation
     * will be inferred from the annotations' {@link java.lang.annotation.RetentionPolicy}.
     */
    enum ForSuperType implements TypeAttributeAppender {

        /**
         * The singleton instance.
         */
        INSTANCE;

        @Override
        public void apply(ClassVisitor classVisitor, TypeDescription typeDescription) {
            AnnotationAppender annotationAppender = new AnnotationAppender.Default(new AnnotationAppender.Target.OnType(classVisitor));
            for (AnnotationDescription annotation : typeDescription.getSupertype().getDeclaredAnnotations()) {
                annotationAppender.append(annotation, none(), AnnotationAppender.AnnotationVisibility.of(annotation));
            }
        }

        @Override
        public String toString() {
            return "TypeAttributeAppender.ForSuperType." + name();
        }
    }

    /**
     * An attribute appender that appends a single annotation to a given type. The visibility for the annotation
     * will be inferred from the annotation's {@link java.lang.annotation.RetentionPolicy}.
     */
    class ForAnnotation implements TypeAttributeAppender {

        /**
         * The annotations to write to the given type.
         */
        private final List<? extends AnnotationDescription> annotations;

        /**
         * A matcher to identify default properties.
         */
        private final ElementMatcher<? super MethodDescription> defaultProperties;

        /**
         * Creates a new annotation attribute appender for explicit annotation values. All values, including default values, are copied.
         *
         * @param annotations The annotations to append.
         */
        public ForAnnotation(List<? extends AnnotationDescription> annotations) {
            this(annotations, none());
        }

        /**
         * Creates a new annotation attribute appender for explicit annotation values.
         *
         * @param annotations       The annotations to write to the given type.
         * @param defaultProperties A matcher to identify default properties.
         */
        public ForAnnotation(List<? extends AnnotationDescription> annotations, ElementMatcher<? super MethodDescription> defaultProperties) {
            this.annotations = annotations;
            this.defaultProperties = defaultProperties;
        }

        @Override
        public void apply(ClassVisitor classVisitor, TypeDescription typeDescription) {
            AnnotationAppender annotationAppender = new AnnotationAppender.Default(new AnnotationAppender.Target.OnType(classVisitor));
            for (AnnotationDescription annotation : annotations) {
                annotationAppender.append(annotation, defaultProperties, AnnotationAppender.AnnotationVisibility.of(annotation));
            }
        }

        @Override
        public boolean equals(Object other) {
            return this == other || !(other == null || getClass() != other.getClass())
                    && annotations.equals(((ForAnnotation) other).annotations)
                    && defaultProperties.equals(((ForAnnotation) other).defaultProperties);
        }

        @Override
        public int hashCode() {
            return annotations.hashCode() + 31 * defaultProperties.hashCode();
        }

        @Override
        public String toString() {
            return "TypeAttributeAppender.ForAnnotation{" +
                    "annotations=" + annotations +
                    ", defaultProperties=" + defaultProperties +
                    '}';
        }
    }

    /**
     * Writes all annotations that are declared for a given Java type to the target type.
     */
    class ForType implements TypeAttributeAppender {

        /**
         * The class of which the declared annotations are to be copied.
         */
        private final TypeDescription typeDescription;

        /**
         * A matcher to identify default properties.
         */
        private final ElementMatcher<? super MethodDescription> defaultProperties;

        /**
         * Creates a new attribute appender that writes all annotations declared for the given loaded type.
         *
         * @param type The loaded type.
         */
        public ForType(Class<?> type) {
            this(new TypeDescription.ForLoadedType(type));
        }

        /**
         * Creates a new attribute appender that writes all annotations declared for the given type description.
         *
         * @param typeDescription The type description.
         */
        public ForType(TypeDescription typeDescription) {
            this(typeDescription, none());
        }

        /**
         * Creates a new attribute appender that writes all annotations declared for the given loaded type.
         *
         * @param type              The loaded type.
         * @param defaultProperties A matcher to identify default properties.
         */
        public ForType(Class<?> type, ElementMatcher<? super MethodDescription> defaultProperties) {
            this(new TypeDescription.ForLoadedType(type), defaultProperties);
        }

        /**
         * Creates a new attribute appender that writes all annotations declared for the given type description.
         *
         * @param typeDescription   The type description.
         * @param defaultProperties A matcher to identify default properties.
         */
        public ForType(TypeDescription typeDescription, ElementMatcher<? super MethodDescription> defaultProperties) {
            this.typeDescription = typeDescription;
            this.defaultProperties = defaultProperties;
        }

        @Override
        public void apply(ClassVisitor classVisitor, TypeDescription typeDescription) {
            AnnotationAppender annotationAppender = new AnnotationAppender.Default(new AnnotationAppender.Target.OnType(classVisitor));
            for (AnnotationDescription annotation : this.typeDescription.getDeclaredAnnotations()) {
                annotationAppender.append(annotation, defaultProperties, AnnotationAppender.AnnotationVisibility.of(annotation));
            }
        }

        @Override
        public boolean equals(Object other) {
            return this == other || !(other == null || getClass() != other.getClass())
                    && typeDescription.equals(((ForType) other).typeDescription)
                    && defaultProperties.equals(((ForType) other).defaultProperties);
        }

        @Override
        public int hashCode() {
            return typeDescription.hashCode() + 31 * defaultProperties.hashCode();
        }

        @Override
        public String toString() {
            return "TypeAttributeAppender.ForType{" +
                    "typeDescription=" + typeDescription +
                    ", defaultProperties=" + defaultProperties +
                    '}';
        }
    }

    /**
     * A compound type attribute appender that concatenates a number of other attribute appenders.
     */
    class Compound implements TypeAttributeAppender {

        /**
         * The type attribute appenders this compound appender represents in their application order.
         */
        private final TypeAttributeAppender[] typeAttributeAppender;

        /**
         * Creates a new compound attribute appender.
         *
         * @param typeAttributeAppender The type attribute appenders to concatenate in the order of their application.
         */
        public Compound(TypeAttributeAppender... typeAttributeAppender) {
            this.typeAttributeAppender = typeAttributeAppender;
        }

        @Override
        public void apply(ClassVisitor classVisitor, TypeDescription typeDescription) {
            for (TypeAttributeAppender typeAttributeAppender : this.typeAttributeAppender) {
                typeAttributeAppender.apply(classVisitor, typeDescription);
            }
        }

        @Override
        public boolean equals(Object other) {
            return this == other || !(other == null || getClass() != other.getClass())
                    && Arrays.equals(typeAttributeAppender, ((Compound) other).typeAttributeAppender);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(typeAttributeAppender);
        }

        @Override
        public String toString() {
            return "TypeAttributeAppender.Compound{typeAttributeAppender=" + Arrays.toString(typeAttributeAppender) + '}';
        }
    }
}
