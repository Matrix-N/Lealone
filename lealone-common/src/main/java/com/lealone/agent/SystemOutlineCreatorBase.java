/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.agent;

import com.lealone.db.plugin.Plugin;
import com.lealone.db.plugin.PluginBase;

public abstract class SystemOutlineCreatorBase extends PluginBase implements SystemOutlineCreator {

    @Override
    public Class<? extends Plugin> getPluginClass() {
        return SystemOutlineCreator.class;
    }
}
