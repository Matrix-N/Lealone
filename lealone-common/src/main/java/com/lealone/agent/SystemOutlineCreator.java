/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.agent;

import com.lealone.db.plugin.Plugin;

public interface SystemOutlineCreator extends Plugin {

    public void createNode(SystemOutlineNode node);
}
