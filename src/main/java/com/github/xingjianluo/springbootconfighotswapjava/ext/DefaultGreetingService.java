package com.github.xingjianluo.springbootconfighotswapjava.ext;

/**
 * 默认问候实现。
 */
public class DefaultGreetingService implements GreetingService {

    public static final String ID = "default";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String greet(String name) {
        return "Hello, " + name;
    }
}
