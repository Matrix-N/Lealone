/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.common.logging.impl;

import java.io.File;
import java.util.Map;

import com.lealone.common.logging.LoggerFactory;
import com.lealone.common.trace.TraceSystem;
import com.lealone.common.util.MapUtils;
import com.lealone.db.SysProperties;

public class FileLoggerFactory extends LoggerFactory {

    private final TraceSystem traceSystem;

    public FileLoggerFactory(Map<String, String> parameters) {
        traceSystem = new TraceSystem(
                new File(SysProperties.getBaseDir(), "lealone.log").getAbsolutePath());
        String str = MapUtils.getString(parameters, "level", "info");
        int level = TraceSystem.INFO;
        switch (str) {
        case "info":
        case "warm":
            level = TraceSystem.INFO;
            break;
        case "error":
            level = TraceSystem.ERROR;
        case "debug":
            level = TraceSystem.DEBUG;
        default:
            level = TraceSystem.OFF;
            break;
        }
        traceSystem.setLevelFile(level);
        traceSystem.setLevelSystemOut(level);
    }

    @Override
    public FileLogger createLogger(String name) {
        int pos = name.lastIndexOf('.');
        if (pos >= 0)
            name = name.substring(pos + 1);
        return new FileLogger(traceSystem.getTrace(name));
    }
}
