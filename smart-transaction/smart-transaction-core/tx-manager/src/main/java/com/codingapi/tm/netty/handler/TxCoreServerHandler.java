package com.codingapi.tm.netty.handler;

/**
 * Created by lorne on 2017/6/29.
 */

import com.alibaba.fastjson.JSONObject;
import com.codingapi.tm.framework.utils.SocketManager;
import com.codingapi.tm.framework.utils.SocketUtils;
import com.codingapi.tm.manager.ModelInfoManager;
import com.codingapi.tm.netty.service.IActionService;
import com.codingapi.tm.netty.service.NettyService;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Handles a server-side channel.
 */

@ChannelHandler.Sharable
public class TxCoreServerHandler extends ChannelInboundHandlerAdapter { // (1)

    private NettyService nettyService;


    private Logger logger = LoggerFactory.getLogger(TxCoreServerHandler.class);


    private final static int max_size = 100;

    private Executor  threadPool = Executors.newFixedThreadPool(max_size);



    public TxCoreServerHandler(NettyService nettyService) {
        this.nettyService = nettyService;
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, Object msg) throws Exception {
        final String json = SocketUtils.getJson(msg);
        logger.info("request->"+json);
        threadPool.execute(new Runnable() {
            @Override
            public void run() {
                service(json,ctx);
            }
        });
    }

    private void service(String json,ChannelHandlerContext ctx){
        if (StringUtils.isNotEmpty(json)) {
            JSONObject jsonObject = JSONObject.parseObject(json);
            String action = jsonObject.getString("a");
            String key = jsonObject.getString("k");
            JSONObject params = JSONObject.parseObject(jsonObject.getString("p"));
            String modelName = ctx.channel().remoteAddress().toString();

            IActionService actionService =  nettyService.getActionService(action);

            String res = actionService.execute(modelName,key,params);

            JSONObject resObj = new JSONObject();
            resObj.put("k", key);
            resObj.put("d", res);

            SocketUtils.sendMsg(ctx,resObj.toString());

        }
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {

        //?????????????????????????????????
        if (SocketManager.getInstance().isAllowConnection()) {
            SocketManager.getInstance().addClient(ctx.channel());
        } else {
            ctx.close();
        }
        super.channelRegistered(ctx);
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {

        SocketManager.getInstance().removeClient(ctx.channel());
        String modelName = ctx.channel().remoteAddress().toString();
        SocketManager.getInstance().outLine(modelName);

        ModelInfoManager.getInstance().removeModelInfo(modelName);
        super.channelUnregistered(ctx);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        ctx.flush();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace();
        //ctx.close();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        //????????????
        if (IdleStateEvent.class.isAssignableFrom(evt.getClass())) {
            IdleStateEvent event = (IdleStateEvent) evt;
            if (event.state() == IdleState.READER_IDLE) {
                ctx.close();
            }
        }
    }

}
