/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.storage;

import com.lealone.storage.fs.FilePath;

@FunctionalInterface
public interface StorageMapFilter {

    boolean accept(String mapName, FilePath file);

    public static StorageMapFilter createLastDateFilter(Long lastDate) {
        return (mapName, file) -> {
            return (lastDate == null || file.lastModified() > lastDate.longValue());
        };
    }
}