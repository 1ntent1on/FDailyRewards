package com.fretka46.fDailyRewards.Utils;

import com.fretka46.fDailyRewards.FDailyRewards;

import java.util.logging.Logger;

public final class Log {
    private Log() {}

    private static Logger logger() {
        return FDailyRewards.get().getLogger();
    }

    public static void info(String message) {
        logger().info(message);
    }

    public static void warning(String message) {
        logger().warning(message);
    }

    public static void severe(String message) {
        logger().severe(message);
    }

    public static void severe(String message, Throwable t) {
        logger().log(java.util.logging.Level.SEVERE, message, t);
    }

    public static void debug(String message) {
        if (FDailyRewards.get().getConfig().getBoolean("debug", false)) {
            logger().info("[DEBUG] " + message);
        }
    }
}
