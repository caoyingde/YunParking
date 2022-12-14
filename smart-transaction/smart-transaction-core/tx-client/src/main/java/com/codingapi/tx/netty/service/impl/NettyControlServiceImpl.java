package com.codingapi.tx.netty.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.codingapi.tx.control.service.TransactionControlService;
import com.codingapi.tx.framework.utils.SocketManager;
import com.codingapi.tx.listener.service.ModelNameService;
import com.codingapi.tx.netty.service.MQTxManagerService;
import com.codingapi.tx.netty.service.NettyControlService;
import com.codingapi.tx.netty.service.NettyService;
import com.codingapi.tx.framework.utils.IpAddressUtils;
import com.lorne.core.framework.utils.task.ConditionUtils;
import com.lorne.core.framework.utils.task.IBack;
import com.lorne.core.framework.utils.task.Task;
import io.netty.channel.ChannelHandlerContext;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * create by lorne on 2017/11/11
 */
@Service
public class NettyControlServiceImpl implements NettyControlService {



    @Autowired
    private NettyService nettyService;


    @Autowired
    private TransactionControlService transactionControlService;


    @Autowired
    private MQTxManagerService mqTxManagerService;

    @Autowired
    private ModelNameService modelNameService;


    private Executor threadPool = Executors.newFixedThreadPool(100);


    @Override
    public void restart() {
        nettyService.close();
        try {
            Thread.sleep(1000 * 3);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        nettyService.start();
    }

    @Override
    public void uploadModelInfo() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (!SocketManager.getInstance().isNetState()|| !IpAddressUtils.isIpAddress(modelNameService.getIpAddress())) {
                    try {
                        Thread.sleep(1000 * 5);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                mqTxManagerService.uploadModelInfo();
            }
        }).start();
    }

    @Override
    public void executeService(final ChannelHandlerContext ctx,final String json) {
        threadPool.execute(new Runnable() {
            @Override
            public void run() {
                if (StringUtils.isNotEmpty(json)) {
                    JSONObject resObj = JSONObject.parseObject(json);
                    if (resObj.containsKey("a")) {
                        // tm???????????????tx?????????????????????

                        transactionControlService.notifyTransactionMsg(ctx,resObj,json);
                    }else{
                        //tx???????????????tm?????????????????????

                        String key = resObj.getString("k");
                        responseMsg(key,resObj);
                    }
                }
            }
        });
    }


    private void responseMsg(String key, JSONObject resObj) {
        if (!"h".equals(key)) {
            final String data = resObj.getString("d");
            Task task = ConditionUtils.getInstance().getTask(key);
            if (task != null) {
                if (task.isAwait()) {
                    task.setBack(new IBack() {
                        @Override
                        public Object doing(Object... objs) throws Throwable {
                            return data;
                        }
                    });
                    task.signalTask();
                }
            }
        } else {
            //????????????
            final String data = resObj.getString("d");
            SocketManager.getInstance().setNetState(true);
            if (StringUtils.isNotEmpty(data)) {
                try {
                    SocketManager.getInstance().setDelay(Integer.parseInt(data));
                } catch (Exception e) {
                    SocketManager.getInstance().setDelay(1);
                }
            }
        }
    }
}
