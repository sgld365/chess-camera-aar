     1|// DCloud uni-app SDK 编译期桩
     2|
     3|package io.dcloud.feature.uniapp.bridge;
     4|
     5|import com.alibaba.fastjson.JSONObject;
     6|
     7|public interface UniJSCallback {
     8|    void invoke(Object result);
     9|    void invokeAndKeepAlive(Object result);
    10|}
    11|