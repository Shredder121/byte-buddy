package com.blogspot.mydailyjava.bytebuddy.utility;

import com.blogspot.mydailyjava.bytebuddy.instrumentation.method.MethodDescription;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static com.blogspot.mydailyjava.bytebuddy.utility.UserInput.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsSame.sameInstance;
import static org.junit.Assert.assertEquals;

public class UserInputTest {

    private static final String FOO = "foo", BAR = "bar", QUX = "qux";

    @Test
    public void testNonNull() throws Exception {
        Object object = new Object();
        assertThat(nonNull(object), sameInstance(object));
    }

    @Test(expected = NullPointerException.class)
    public void testNonNullThrowsException() throws Exception {
        nonNull(null);
    }

    @Test
    public void testIsInterface() throws Exception {
        assertEquals(Runnable.class, isInterface(Runnable.class));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testIsInterfaceThrowsException() throws Exception {
        isInterface(Object.class);
    }

    @Test
    public void testClassIsImplementable() throws Exception {
        assertEquals(Object.class, isImplementable(Object.class));
    }

    @Test
    public void testInterfaceIsImplementable() throws Exception {
        assertEquals(Runnable.class, isImplementable(Runnable.class));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPrimitiveIsImplementableThrowsException() throws Exception {
        isImplementable(int.class);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testArrayIsImplementableThrowsException() throws Exception {
        isImplementable(Object[].class);
    }

    @Test
    public void testJoin() throws Exception {
        assertThat(join(Arrays.asList(FOO, BAR), QUX), is(Arrays.asList(FOO, BAR, QUX)));
    }

    @Test
    public void testIsValidIdentifier() throws Exception {
        assertThat(isValidIdentifier(FOO), is(FOO));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testIsValidIdentifierThrowsException() throws Exception {
        isValidIdentifier(MethodDescription.CONSTRUCTOR_INTERNAL_NAME);
    }

    @Test
    public void testIsNotEmpty() throws Exception {
        List<String> list = Arrays.asList(FOO);
        assertThat(isNotEmpty(list, FOO), sameInstance(list));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testIsNotEmptyThrowsException() throws Exception {
        isNotEmpty(Arrays.asList(), FOO);
    }
}