/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.agent;

public class SystemOutline {

    private static SystemOutlineCreator creator;

    public static SystemOutlineCreator getCreator() {
        return creator;
    }

    public static void setCreator(SystemOutlineCreator creator) {
        SystemOutline.creator = creator;
    }

    public static void createNode(SystemOutlineNode node) {
        if (creator != null)
            creator.createNode(node);
    }
}
