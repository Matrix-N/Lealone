/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.common.logging;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.lealone.common.logging.impl.ConsoleLoggerFactory;
import com.lealone.common.logging.impl.FileLoggerFactory;
import com.lealone.common.util.Utils;

public abstract class LoggerFactory {

    protected abstract Logger createLogger(String name);

    public static final String LOGGER_FACTORY_CLASS_NAME = "lealone.logger.factory";
    private static final ConcurrentHashMap<String, Logger> loggers = new ConcurrentHashMap<>();
    private static LoggerFactory loggerFactory = getLoggerFactory();

    public static void init(Map<String, String> parameters) {
        String type = parameters.get("type");
        if ("file".contentEquals(type))
            loggerFactory = new FileLoggerFactory(parameters);
        else
            loggerFactory = new ConsoleLoggerFactory();
    }

    private static LoggerFactory getLoggerFactory() {
        String factoryClassName = null;
        try {
            factoryClassName = System.getProperty(LOGGER_FACTORY_CLASS_NAME);
        } catch (Exception e) {
        }
        if (factoryClassName != null) {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            try {
                Class<?> clz = loader.loadClass(factoryClassName);
                return Utils.newInstance(clz);
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "Error instantiating class \"" + factoryClassName + "\"", e);
            }
        } else {
            return new ConsoleLoggerFactory();
        }
    }

    public static Logger getLogger(Class<?> clazz) {
        String name = clazz.isAnonymousClass() ? clazz.getEnclosingClass().getCanonicalName()
                : clazz.getCanonicalName();
        return getLogger(name);
    }

    public static Logger getLogger(String name) {
        Logger logger = loggers.get(name);
        if (logger == null) {
            logger = loggerFactory.createLogger(name);
            Logger oldLogger = loggers.putIfAbsent(name, logger);
            if (oldLogger != null) {
                logger = oldLogger;
            }
        }
        return logger;
    }

    public static void removeLogger(String name) {
        loggers.remove(name);
    }
}
