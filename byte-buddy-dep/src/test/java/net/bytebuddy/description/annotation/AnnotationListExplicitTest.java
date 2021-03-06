package net.bytebuddy.description.annotation;

import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.test.utility.MockitoRule;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.mockito.Mock;

import java.lang.annotation.Inherited;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.when;

public class AnnotationListExplicitTest {

    @Rule
    public TestRule mockitoRule = new MockitoRule(this);

    @Mock
    private AnnotationDescription foo, bar;

    @Mock
    private AnnotationDescription.Loadable<Foo> fooLoadable;

    @Before
    public void setUp() throws Exception {
        when(foo.getAnnotationType()).thenReturn(new TypeDescription.ForLoadedType(Foo.class));
        when(foo.prepare(Foo.class)).thenReturn(fooLoadable);
        when(bar.getAnnotationType()).thenReturn(new TypeDescription.ForLoadedType(Bar.class));
    }

    @Test
    public void testInheritedNonIgnored() throws Exception {
        AnnotationList annotationList = new AnnotationList.Explicit(Arrays.asList(foo, bar))
                .inherited(Collections.<TypeDescription>emptySet());
        assertThat(annotationList.size(), is(1));
        assertThat(annotationList, hasItem(bar));
    }

    @Test
    public void testInheritedIgnored() throws Exception {
        AnnotationList annotationList = new AnnotationList.Explicit(Arrays.asList(foo, bar))
                .inherited(Collections.singleton(new TypeDescription.ForLoadedType(Bar.class)));
        assertThat(annotationList.size(), is(0));
    }

    @Test
    public void testContainment() throws Exception {
        AnnotationList annotationList = new AnnotationList.Explicit(Collections.singletonList(foo));
        assertThat(annotationList.isAnnotationPresent(Foo.class), is(true));
        assertThat(annotationList.isAnnotationPresent(Bar.class), is(false));
    }

    @Test
    public void testPreparation() throws Exception {
        AnnotationList annotationList = new AnnotationList.Explicit(Collections.singletonList(foo));
        assertThat(annotationList.ofType(Foo.class), is(fooLoadable));
        assertThat(annotationList.ofType(Bar.class), nullValue());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testMulti() throws Exception {
        Iterator<AnnotationDescription> annotationDescriptions = Arrays.asList(foo, bar).iterator();
        for (AnnotationList annotationList : AnnotationList.Explicit.asList(Arrays.asList(Collections.singletonList(foo), Collections.singletonList(bar)))) {
            assertThat(annotationList.size(), is(1));
            assertThat(annotationList, hasItem(annotationDescriptions.next()));
        }
    }

    @Test
    public void testSubList() throws Exception {
        assertThat(new AnnotationList.Explicit(Collections.singletonList(foo)).subList(0, 1),
                is((AnnotationList) new AnnotationList.Explicit(Collections.singletonList(foo))));
    }

    private @interface Foo {

    }

    @Inherited
    private @interface Bar {

    }
}
