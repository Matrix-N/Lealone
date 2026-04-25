/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.agent;

import com.lealone.db.DataHandler;
import com.lealone.db.Database;
import com.lealone.db.session.ServerSession;
import com.lealone.db.session.Session;

// 生成应用代码的CodeAgent
public abstract class AppCodeAgent extends CodeAgentBase {

    public AppCodeAgent(String name) {
        super(name);
    }

    @Override
    public String execute(String userPrompt, DataHandler db, Session session) {
        return new AgentExecutor(userPrompt, (Database) db, (ServerSession) session).execute();
    }
}
