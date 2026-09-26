package com.github.xingjianluo.springbootconfighotswapjava.hotswap.extension;

/**
 * 问候语扩展点 SPI。
 */
public interface GreetingExtension {

    /** 扩展唯一标识。 */
    String id();

    /** 根据配置中的问候语模板渲染。 */
    String greet(String name, String greetingTemplate);
}
