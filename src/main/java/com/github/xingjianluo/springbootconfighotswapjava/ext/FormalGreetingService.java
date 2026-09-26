package com.github.xingjianluo.springbootconfighotswapjava.ext;

/**
 * 正式问候实现（可热替换为默认实现的替代）。
 */
public class FormalGreetingService implements GreetingService {

    public static final String ID = "formal";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String greet(String name) {
        return "Dear " + name + ", greetings";
    }
}
