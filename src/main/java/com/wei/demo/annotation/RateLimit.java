package com.wei.demo.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 限流注解，用在限流方法上
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /**
     * 限流唯一标示
     *
     * @return
     */
    String key() default "";

    /**
     * 限流时间
     *
     * @return
     */
    int time();

    /**
     * 限流次数
     *
     * @return
     */
    int count();
}
