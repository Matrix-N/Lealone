/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.agent.provider;

import java.util.concurrent.atomic.AtomicReference;

import com.lealone.agent.AppCodeAgent;

public class DeepSeekCodeAgent extends AppCodeAgent {

    public DeepSeekCodeAgent() {
        super("deepseek");
    }

    @Override
    protected void afterInit() {
        if (model == null)
            model = "deepseek-v4-pro";
        if (url == null)
            url = "https://api.deepseek.com";
    }

    @Override
    public String send(String userPrompt, AtomicReference<String> previousResponseId) {
        return null;
    }
}
