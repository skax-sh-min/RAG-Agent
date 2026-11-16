package com.example.ragagent.service;

/** {@link LauncherWatchdogTest} 가 "살아 있다가 강제로 죽는 프로세스"로 쓰려고 띄우는 아무것도 안 하는 프로세스. */
public final class SleepingProcess {

    private SleepingProcess() {}

    public static void main(String[] args) throws InterruptedException {
        Thread.sleep(600_000);
    }
}
