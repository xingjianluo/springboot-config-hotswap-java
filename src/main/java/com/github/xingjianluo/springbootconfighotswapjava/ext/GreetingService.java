package com.github.xingjianluo.springbootconfighotswapjava.ext;

/**
 * 扩展点：问候语生成。
 */
public interface GreetingService {

    String id();

    String greet(String name);
}
