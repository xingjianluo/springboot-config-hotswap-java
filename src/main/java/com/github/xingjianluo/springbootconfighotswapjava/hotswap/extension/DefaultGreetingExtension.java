package com.github.xingjianluo.springbootconfighotswapjava.hotswap.extension;

/**
 * 内置默认问候实现：模板 + 名字。
 */
public class DefaultGreetingExtension implements GreetingExtension {

    public static final String ID = "default";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String greet(String name, String greetingTemplate) {
        return greetingTemplate + ", " + name + "!";
    }
}
