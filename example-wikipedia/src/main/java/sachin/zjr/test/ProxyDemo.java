package sachin.zjr.test;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Proxy;

/**
 * @Author Sachin
 * @Date 2022/4/30
 **/

/**
 *
 * 如何正确反射调用 interface中的default方法？
 *
 * Correct Reflective Access to Interface Default Methods in Java 8, 9, 10
 * https://blog.jooq.org/correct-reflective-access-to-interface-default-methods-in-java-8-9-10/
 *
 */
interface  Duck{
    default void quack() {
        System.out.println("Quack");
    }
}

public class ProxyDemo {
    public static void main(String[] args) {


        Duck duck = (Duck) Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(), new Class[]{Duck.class},
                (proxy, method, args1) -> {

                    MethodHandles.lookup().in(Duck.class).unreflectSpecial(method, Duck.class)
                            .bindTo(proxy).invokeWithArguments();
                    return null;
        });

        duck.quack();
    }
}
