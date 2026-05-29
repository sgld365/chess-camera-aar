     1|// DCloud uni-app SDK 编译期桩
     2|
     3|package io.dcloud.feature.uniapp.annotation;
     4|
     5|import java.lang.annotation.ElementType;
     6|import java.lang.annotation.Retention;
     7|import java.lang.annotation.RetentionPolicy;
     8|import java.lang.annotation.Target;
     9|
    10|@Retention(RetentionPolicy.RUNTIME)
    11|@Target(ElementType.METHOD)
    12|public @interface UniJSMethod {
    13|    boolean uiThread() default true;
    14|}
    15|