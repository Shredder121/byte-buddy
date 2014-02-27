package com.blogspot.mydailyjava.bytebuddy.instrumentation.method.bytecode.bind;

import com.blogspot.mydailyjava.bytebuddy.instrumentation.Instrumentation;
import com.blogspot.mydailyjava.bytebuddy.instrumentation.method.MethodDescription;
import com.blogspot.mydailyjava.bytebuddy.instrumentation.method.bytecode.stack.StackManipulation;
import org.objectweb.asm.MethodVisitor;

/**
 * Representation of an attempt to delegate to a method that is illegal.
 */
public enum IllegalMethodDelegation implements MethodDelegationBinder.Binding {
    INSTANCE;

    @Override
    public boolean isValid() {
        return false;
    }

    @Override
    public Integer getTargetParameterIndex(Object parameterBindingToken) {
        throw new IllegalStateException();
    }

    @Override
    public MethodDescription getTarget() {
        throw new IllegalStateException();
    }

    @Override
    public StackManipulation.Size apply(MethodVisitor methodVisitor, Instrumentation.Context instrumentationContext) {
        throw new IllegalStateException();
    }
}