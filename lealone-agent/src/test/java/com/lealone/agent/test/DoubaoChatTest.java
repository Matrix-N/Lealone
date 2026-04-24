/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.agent.test;

import java.util.concurrent.atomic.AtomicReference;

import com.lealone.agent.doubao.DoubaoAgent;

public class DoubaoChatTest extends DoubaoAgent {

    public static void main(String[] args) {
        new DoubaoChatTest().run(args);
    }

    public void run(String[] args) {
        init(args);
        AtomicReference<String> previousResponseId = new AtomicReference<>();
        System.out.println(send("你是谁", previousResponseId));
        System.out.println(send("我刚才说了什么", previousResponseId));
    }
}
