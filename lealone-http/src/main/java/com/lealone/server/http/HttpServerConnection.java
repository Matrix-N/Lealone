/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.server.http;

import com.lealone.db.scheduler.Scheduler;
import com.lealone.net.NetBuffer;
import com.lealone.net.NetEventLoop;
import com.lealone.net.TransferConnection;
import com.lealone.net.TransferOutputStream.GlobalWritableChannel;
import com.lealone.net.WritableChannel;
import com.lealone.server.http.util.net.NioEndpoint;
import com.lealone.server.http.util.net.NioSocketWrapper;
import com.lealone.server.http.util.net.SocketEvent;

public class HttpServerConnection extends TransferConnection {

    private final HttpServer httpServer;
    private final NioSocketWrapper socketWrapper;

    public HttpServerConnection(HttpServer httpServer, WritableChannel channel, Scheduler scheduler) {
        super(channel, true);
        this.httpServer = httpServer;
        NioEndpoint endpoint = (NioEndpoint) (httpServer.getProtocolHandler().getEndpoint());
        socketWrapper = endpoint.createSocketWrapper(writableChannel.getSocketChannel());
        NetEventLoop netEventLoop = (NetEventLoop) scheduler.getNetEventLoop();
        socketWrapper.setSelector(netEventLoop.getSelector());
        socketWrapper.setSchedulerId(scheduler.getId());
        netEventLoop.setPreferBatchWrite(true);

        socketWrapper.setGlobalWritableChannel(
                new GlobalWritableChannel(channel, scheduler.getOutputBuffer()));
    }

    @Override
    public int getPacketLengthByteCount() {
        return -1;
    }

    @Override
    public void handle(NetBuffer buffer, boolean autoRecycle) {
        if (socketWrapper.processPendingOperation())
            return;
        socketWrapper.processSocket(SocketEvent.OPEN_READ, false);
        if (socketWrapper.isClosed())
            httpServer.removeConnection(this);
    }
}
