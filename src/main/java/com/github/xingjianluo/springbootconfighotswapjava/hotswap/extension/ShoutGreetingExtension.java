package com.github.xingjianluo.springbootconfighotswapjava.hotswap.extension;

/**
 * 示例替换实现：全大写问候。
 */
public class ShoutGreetingExtension implements GreetingExtension {

    public static final String ID = "shout";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String greet(String name, String greetingTemplate) {
        return (greetingTemplate + ", " + name + "!").toUpperCase();
    }
}
