package com.github.postyizhan.betterquesting.platform.api;

public interface PlatformLifecycle {
    void onServerStarted();

    void onServerStopping();
}
