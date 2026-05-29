     1|// DCloud uni-app SDK 编译期桩 — 运行时由 HBuilderX 提供真实实现
     2|// 仅供编译 AAR 使用
     3|
     4|package io.dcloud.feature.uniapp.common;
     5|
     6|import android.content.Context;
     7|import java.util.HashMap;
     8|
     9|public class UniModule {
    10|    protected Context mUniSDKInstance;
    11|
    12|    public void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {}
    13|    public void onActivityCreate() {}
    14|    public void onActivityStart() {}
    15|    public void onActivityPause() {}
    16|    public void onActivityResume() {}
    17|    public void onActivityStop() {}
    18|    public void onActivityDestroy() {}
    19|}
    20|