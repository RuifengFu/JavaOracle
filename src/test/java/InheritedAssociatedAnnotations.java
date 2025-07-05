import java.lang.annotation.*;
import java.lang.reflect.*;
import java.util.Arrays;

public class InheritedAssociatedAnnotations {

    public static void main(String[] args) {
        checkAssociated(A3.class);
        checkAssociated(B3.class);
        checkAssociated(C3.class);
        checkAssociated(D3.class);
    }

    private static void checkAssociated(Class<?> clazz) {
        AnnotatedElement ae = clazz;
        Ann[] actual = ae.getAnnotationsByType(Ann.class);
        Ann[] expected = ae.getAnnotation(ExpectedAssociated.class).value();

        if (!Arrays.equals(actual, expected)) {
            throw new RuntimeException(String.format(
                    "Test failed for %s: Expected %s but got %s.",
                    ae,
                    Arrays.toString(expected),
                    Arrays.toString(actual)));
        }

        checkContainerAndDeclared(clazz);
    }

    private static void checkContainerAndDeclared(Class<?> clazz) {
        AnnotatedElement ae = clazz;
        // Check container via getAnnotation
        AnnCont container = ae.getAnnotation(AnnCont.class);
        // Check container via getAnnotationsByType
        AnnCont[] containers = ae.getAnnotationsByType(AnnCont.class);

        // Check declared annotations for Ann
        Ann[] declaredAnn = ae.getDeclaredAnnotationsByType(Ann.class);
        Ann declaredSingleAnn = ae.getDeclaredAnnotation(Ann.class);
        // Check declared container
        AnnCont declaredContainer = ae.getDeclaredAnnotation(AnnCont.class);

        String className = clazz.getName();
        if (className.equals("A3")) {
            checkA3(ae, container, containers, declaredAnn, declaredSingleAnn, declaredContainer);
        } else if (className.equals("B3")) {
            checkB3(ae, container, containers, declaredAnn, declaredSingleAnn, declaredContainer);
        } else if (className.equals("C3")) {
            checkC3(ae, container, containers, declaredAnn, declaredSingleAnn, declaredContainer);
        } else if (className.equals("D3")) {
            checkD3(ae, container, containers, declaredAnn, declaredSingleAnn, declaredContainer);
        }
    }

    private static void checkA3(AnnotatedElement ae, AnnCont container, AnnCont[] containers, Ann[] declaredAnn, Ann declaredSingleAnn, AnnCont declaredContainer) {
        // Check for container: should be null
        assert container == null : "Expected container to be null for A3, but got " + container;
        assert containers.length == 0 : "Expected 0 containers for A3, but got " + containers.length;
        // Check declared Ann
        assert declaredAnn.length == 0 : "Expected 0 declared Ann annotations for A3, but got " + declaredAnn.length;
        assert declaredSingleAnn == null : "Expected declared single Ann to be null for A3, but got " + declaredSingleAnn;
        assert declaredContainer == null : "Expected declared container to be null for A3, but got " + declaredContainer;
        // Check getAnnotations: should have 2 annotations: ExpectedAssociated and Ann(20)
        Annotation[] all = ae.getAnnotations();
        assert all.length == 2 : "Expected 2 annotations for A3, but got " + all.length;
        // But we don't know the order -> we can check by type
        boolean foundExpectedAssociated = false;
        boolean foundAnn20 = false;
        for (Annotation a : all) {
            if (a instanceof ExpectedAssociated) {
                foundExpectedAssociated = true;
            } else if (a instanceof Ann) {
                if (((Ann)a).value() == 20) {
                    foundAnn20 = true;
                }
            }
        }
        assert foundExpectedAssociated : "Expected ExpectedAssociated annotation to be present for A3";
        assert foundAnn20 : "Expected @Ann(20) annotation to be present for A3";
    }

    private static void checkB3(AnnotatedElement ae, AnnCont container, AnnCont[] containers, Ann[] declaredAnn, Ann declaredSingleAnn, AnnCont declaredContainer) {
        // container should be non-null and have value [Ann(10), Ann(11)]
        assert container != null : "Expected non-null container for B3";
        assert container.value().length == 2 : "Expected container to have 2 Ann annotations for B3, but got " + container.value().length;
        assert container.value()[0].value() == 10 : "Expected first Ann in container to have value 10 for B3, but got " + container.value()[0].value();
        assert container.value()[1].value() == 11 : "Expected second Ann in container to have value 11 for B3, but got " + container.value()[1].value();
        assert containers.length == 1 : "Expected 1 container for B3, but got " + containers.length;
        assert containers[0] == container : "Expected container from getAnnotationsByType to match the one from getAnnotation for B3";

        // declaredAnn: empty
        assert declaredAnn.length == 0 : "Expected 0 declared Ann annotations for B3, but got " + declaredAnn.length;
        assert declaredSingleAnn == null : "Expected declared single Ann to be null for B3, but got " + declaredSingleAnn;
        assert declaredContainer == null : "Expected declared container to be null for B3, but got " + declaredContainer;

        // getAnnotations: should have 3: ExpectedAssociated, Ann(20), and the container
        Annotation[] all = ae.getAnnotations();
        assert all.length == 3 : "Expected 3 annotations for B3, but got " + all.length;
        boolean foundExpectedAssociated = false;
        boolean foundAnn20 = false;
        boolean foundContainer = false;
        for (Annotation a : all) {
            if (a instanceof ExpectedAssociated) {
                foundExpectedAssociated = true;
            } else if (a instanceof Ann) {
                if (((Ann)a).value() == 20) {
                    foundAnn20 = true;
                }
            } else if (a instanceof AnnCont) {
                foundContainer = true;
                AnnCont ac = (AnnCont) a;
                assert ac.value().length == 2 : "Expected container in getAnnotations to have 2 Ann annotations for B3, but got " + ac.value().length;
                assert ac.value()[0].value() == 10 : "Expected first Ann in container in getAnnotations to have value 10 for B3, but got " + ac.value()[0].value();
                assert ac.value()[1].value() == 11 : "Expected second Ann in container in getAnnotations to have value 11 for B3, but got " + ac.value()[1].value();
            }
        }
        assert foundExpectedAssociated : "Expected ExpectedAssociated annotation to be present for B3";
        assert foundAnn20 : "Expected @Ann(20) annotation to be present for B3";
        assert foundContainer : "Expected container annotation to be present for B3";
    }

    private static void checkC3(AnnotatedElement ae, AnnCont container, AnnCont[] containers, Ann[] declaredAnn, Ann declaredSingleAnn, AnnCont declaredContainer) {
        // container should be non-null and have value [Ann(20), Ann(21)]
        assert container != null : "Expected non-null container for C3";
        assert container.value().length == 2 : "Expected container to have 2 Ann annotations for C3, but got " + container.value().length;
        assert container.value()[0].value() == 20 : "Expected first Ann in container to have value 20 for C3, but got " + container.value()[0].value();
        assert container.value()[1].value() == 21 : "Expected second Ann in container to have value 21 for C3, but got " + container.value()[1].value();
        assert containers.length == 1 : "Expected 1 container for C3, but got " + containers.length;
        assert containers[0] == container : "Expected container from getAnnotationsByType to match the one from getAnnotation for C3";

        // declaredAnn: empty
        assert declaredAnn.length == 0 : "Expected 0 declared Ann annotations for C3, but got " + declaredAnn.length;
        assert declaredSingleAnn == null : "Expected declared single Ann to be null for C3, but got " + declaredSingleAnn;
        assert declaredContainer == null : "Expected declared container to be null for C3, but got " + declaredContainer;

        // getAnnotations: should have 2: ExpectedAssociated and the container
        Annotation[] all = ae.getAnnotations();
        System.out.println(Arrays.toString(all));
        assert all.length == 2 : "Expected 2 annotations for C3, but got " + all.length;
        boolean foundExpectedAssociated = false;
        boolean foundContainer = false;
        for (Annotation a : all) {
            if (a instanceof ExpectedAssociated) {
                foundExpectedAssociated = true;
            } else if (a instanceof AnnCont) {
                foundContainer = true;
                AnnCont ac = (AnnCont) a;
                assert ac.value().length == 2 : "Expected container in getAnnotations to have 2 Ann annotations for C3, but got " + ac.value().length;
                assert ac.value()[0].value() == 20 : "Expected first Ann in container in getAnnotations to have value 20 for C3, but got " + ac.value()[0].value();
                assert ac.value()[1].value() == 21 : "Expected second Ann in container in getAnnotations to have value 21 for C3, but got " + ac.value()[1].value();
            }
        }
        assert foundExpectedAssociated : "Expected ExpectedAssociated annotation to be present for C3";
        assert foundContainer : "Expected container annotation to be present for C3";
    }

    private static void checkD3(AnnotatedElement ae, AnnCont container, AnnCont[] containers, Ann[] declaredAnn, Ann declaredSingleAnn, AnnCont declaredContainer) {
        // container should be non-null and have value [Ann(20), Ann(21)] (from D2)
        assert container != null : "Expected non-null container for D3";
        assert container.value().length == 2 : "Expected container to have 2 Ann annotations for D3, but got " + container.value().length;
        assert container.value()[0].value() == 20 : "Expected first Ann in container to have value 20 for D3, but got " + container.value()[0].value();
        assert container.value()[1].value() == 21 : "Expected second Ann in container to have value 21 for D3, but got " + container.value()[1].value();
        assert containers.length == 1 : "Expected 1 container for D3, but got " + containers.length;
        assert containers[0] == container : "Expected container from getAnnotationsByType to match the one from getAnnotation for D3";

        // declaredAnn: empty
        assert declaredAnn.length == 0 : "Expected 0 declared Ann annotations for D3, but got " + declaredAnn.length;
        assert declaredSingleAnn == null : "Expected declared single Ann to be null for D3, but got " + declaredSingleAnn;
        assert declaredContainer == null : "Expected declared container to be null for D3, but got " + declaredContainer;

        // getAnnotations: should have 2: ExpectedAssociated and the container
        Annotation[] all = ae.getAnnotations();
        assert all.length == 2 : "Expected 2 annotations for D3, but got " + all.length;
        boolean foundExpectedAssociated = false;
        boolean foundContainer = false;
        for (Annotation a : all) {
            if (a instanceof ExpectedAssociated) {
                foundExpectedAssociated = true;
            } else if (a instanceof AnnCont) {
                foundContainer = true;
                AnnCont ac = (AnnCont) a;
                assert ac.value().length == 2 : "Expected container in getAnnotations to have 2 Ann annotations for D3, but got " + ac.value().length;
                assert ac.value()[0].value() == 20 : "Expected first Ann in container in getAnnotations to have value 20 for D3, but got " + ac.value()[0].value();
                assert ac.value()[1].value() == 21 : "Expected second Ann in container in getAnnotations to have value 21 for D3, but got " + ac.value()[1].value();
            }
        }
        assert foundExpectedAssociated : "Expected ExpectedAssociated annotation to be present for D3";
        assert foundContainer : "Expected container annotation to be present for D3";
    }

}

@Retention(RetentionPolicy.RUNTIME)
@interface ExpectedAssociated {
    Ann[] value();
}


@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Repeatable(AnnCont.class)
@interface Ann {
    int value();
}

@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@interface AnnCont {
    Ann[] value();
}


@Ann(10)
class A1 {}

@Ann(20)
class A2 extends A1 {}

@ExpectedAssociated({@Ann(20)})
class A3 extends A2 {}


@Ann(10) @Ann(11)
class B1 {}

@Ann(20)
class B2 extends B1 {}

@ExpectedAssociated({@Ann(20)})
class B3 extends B2 {}


@Ann(10)
class C1 {}

@Ann(20) @Ann(21)
class C2 extends C1 {}

@ExpectedAssociated({@Ann(20), @Ann(21)})
class C3 extends C2 {}


@Ann(10) @Ann(11)
class D1 {}

@Ann(20) @Ann(21)
class D2 extends D1 {}

@ExpectedAssociated({@Ann(20), @Ann(21)})
class D3 extends D2 {}

