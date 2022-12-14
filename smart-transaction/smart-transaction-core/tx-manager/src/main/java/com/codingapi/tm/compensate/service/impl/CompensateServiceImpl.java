package com.codingapi.tm.compensate.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.codingapi.tm.compensate.dao.CompensateDao;
import com.codingapi.tm.compensate.model.TransactionCompensateMsg;
import com.codingapi.tm.compensate.model.TxModel;
import com.codingapi.tm.compensate.service.CompensateService;
import com.codingapi.tm.config.ConfigReader;
import com.codingapi.tm.manager.ModelInfoManager;
import com.codingapi.tm.manager.service.TxManagerSenderService;
import com.codingapi.tm.manager.service.TxManagerService;
import com.codingapi.tm.model.ModelInfo;
import com.codingapi.tm.model.ModelName;
import com.codingapi.tm.netty.model.TxGroup;
import com.codingapi.tm.netty.model.TxInfo;
import com.lorne.core.framework.exception.ServiceException;
import com.lorne.core.framework.utils.DateUtil;
import com.lorne.core.framework.utils.encode.Base64Utils;
import com.lorne.core.framework.utils.http.HttpUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * create by lorne on 2017/11/11
 */
@Service
public class CompensateServiceImpl implements CompensateService {


    private Logger logger = LoggerFactory.getLogger(CompensateServiceImpl.class);

    @Autowired
    private CompensateDao compensateDao;

    @Autowired
    private ConfigReader configReader;

    @Autowired
    private TxManagerSenderService managerSenderService;

    @Autowired
    private TxManagerService managerService;


    private Executor threadPool = Executors.newFixedThreadPool(20);

    @Override
    public boolean saveCompensateMsg(final TransactionCompensateMsg transactionCompensateMsg) {

        TxGroup txGroup =managerService.getTxGroup(transactionCompensateMsg.getGroupId());
        if (txGroup == null) {
            return false;
        }

        managerService.deleteTxGroup(txGroup);

        //????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????

//        //?????????????????????????????????????????????
//        boolean hasNoNotify = false;
//
//        for(TxInfo txInfo:txGroup.getList()){
//            if(txInfo.getNotify()==0){
//                hasNoNotify = true;
//            }
//        }
//
//        if(!hasNoNotify){
//            //???????????????????????????
//            logger.info("TxGroup had notify ! ");
//            return true;
//        }


        transactionCompensateMsg.setTxGroup(txGroup);

        final String json = JSON.toJSONString(transactionCompensateMsg);

        logger.info("Compensate->" + json);

        final String compensateKey = compensateDao.saveCompensateMsg(transactionCompensateMsg);

        //??????????????????????????????????????????????????????????????????????????????success???????????????????????????
        threadPool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String groupId = transactionCompensateMsg.getGroupId();
                    JSONObject requestJson = new JSONObject();
                    requestJson.put("action", "compensate");
                    requestJson.put("groupId", groupId);
                    requestJson.put("json", json);

                    String url = configReader.getCompensateNotifyUrl();
                    logger.error("Compensate Callback Address->" + url);
                    String res = HttpUtils.postJson(url, requestJson.toJSONString());
                    logger.error("Compensate Callback Result->" + res);
                    if (configReader.isCompensateAuto()) {
                        //????????????,????????????????????????
                        if (res.contains("success")||res.contains("SUCCESS")) {
                            //????????????
                            autoCompensate(compensateKey, transactionCompensateMsg);
                        }
                    }
                } catch (Exception e) {
                    logger.error("Compensate Callback Fails->" + e.getMessage());
                }
            }
        });

        return StringUtils.isNotEmpty(compensateKey);


    }


    public void autoCompensate(final String compensateKey, TransactionCompensateMsg transactionCompensateMsg) {
        final String json = JSON.toJSONString(transactionCompensateMsg);
        logger.info("Auto Compensate->" + json);
        //????????????????????????...
        final int tryTime = configReader.getCompensateTryTime();
        boolean autoExecuteRes = false;
        try {
            int executeCount = 0;
            autoExecuteRes = _executeCompensate(json);
            logger.info("Automatic Compensate Result->" + autoExecuteRes + ",json->" + json);
            while (!autoExecuteRes) {
                logger.info("Compensate Failure, Entering Compensate Queue->" + autoExecuteRes + ",json->" + json);
                executeCount++;
                if(executeCount==3){
                    autoExecuteRes = false;
                    break;
                }
                try {
                    Thread.sleep(tryTime * 1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                autoExecuteRes = _executeCompensate(json);
            }

            //????????????????????????
            if(autoExecuteRes) {
                compensateDao.deleteCompensateByKey(compensateKey);
            }

        }catch (Exception e){
            logger.error("Auto Compensate Fails,msg:"+e.getLocalizedMessage());
            //??????????????????????????????
            autoExecuteRes = false;
        }

        //????????????????????????????????????
        String groupId = transactionCompensateMsg.getGroupId();
        JSONObject requestJson = new JSONObject();
        requestJson.put("action","notify");
        requestJson.put("groupId",groupId);
        requestJson.put("resState",autoExecuteRes);

        String url = configReader.getCompensateNotifyUrl();
        logger.error("Compensate Result Callback Address->" + url);
        String res = HttpUtils.postJson(url, requestJson.toJSONString());
        logger.error("Compensate Result Callback Result->" + res);

    }



    @Override
    public List<ModelName> loadModelList() {
        List<String> keys =  compensateDao.loadCompensateKeys();

        Map<String,Integer> models = new HashMap<>();

        for(String key:keys){
            if(key.length()>36){
                String name =  key.substring(11,key.length()-25);
                int v = 1;
                if(models.containsKey(name)){
                    v =  models.get(name)+1;
                }
                models.put(name,v);
            }
        }
        List<ModelName> names = new ArrayList<>();

        for(String key:models.keySet()){
            int v = models.get(key);
            ModelName modelName = new ModelName();
            modelName.setName(key);
            modelName.setCount(v);
            names.add(modelName);
        }
        return names;
    }

    @Override
    public List<String> loadCompensateTimes(String model) {
        return compensateDao.loadCompensateTimes(model);
    }

    @Override
    public List<TxModel> loadCompensateByModelAndTime(String path) {
        List<String> logs = compensateDao.loadCompensateByModelAndTime(path);

        List<TxModel> models = new ArrayList<>();
        for (String json : logs) {
            JSONObject jsonObject = JSON.parseObject(json);
            TxModel model = new TxModel();
            long currentTime = jsonObject.getLong("currentTime");
            model.setTime(DateUtil.formatDate(new Date(currentTime), DateUtil.FULL_DATE_TIME_FORMAT));
            model.setClassName(jsonObject.getString("className"));
            model.setMethod(jsonObject.getString("methodStr"));
            model.setExecuteTime(jsonObject.getInteger("time"));
            model.setBase64(Base64Utils.encode(json.getBytes()));
            model.setState(jsonObject.getInteger("state"));
            model.setOrder(currentTime);

            String groupId = jsonObject.getString("groupId");

            String key = path + "_" + groupId;
            model.setKey(key);

            models.add(model);
        }
        Collections.sort(models, new Comparator<TxModel>() {
            @Override
            public int compare(TxModel o1, TxModel o2) {
                if (o2.getOrder() > o1.getOrder()) {
                    return 1;
                } else {
                    return -1;
                }
            }
        });
        return models;
    }

    @Override
    public boolean hasCompensate() {
        return compensateDao.hasCompensate();
    }

    @Override
    public boolean delCompensate(String path) {
        compensateDao.deleteCompensateByPath(path);
        return true;
    }

    @Override
    public void reloadCompensate(TxGroup txGroup) {
        TxGroup compensateGroup = getCompensateByGroupId(txGroup.getGroupId());
        if (compensateGroup != null) {
            for (TxInfo txInfo : txGroup.getList()) {

                for (TxInfo cinfo : compensateGroup.getList()) {
                    if (cinfo.getModel().equals(txInfo.getModel()) && cinfo.getMethodStr().equals(txInfo.getMethodStr())) {

                        //??????????????????????????????????????????

                        int oldNotify = cinfo.getNotify();

                        if (oldNotify == 1) {
                            txInfo.setIsCommit(0);
                        } else {
                            txInfo.setIsCommit(1);
                        }
                    }
                }

            }
        }

        logger.info("Compensate Loaded->"+JSON.toJSONString(txGroup));
    }

    private TxGroup getCompensateByGroupId(String groupId) {
        String json = compensateDao.getCompensateByGroupId(groupId);
        if (json == null) {
            return null;
        }
        JSONObject jsonObject = JSON.parseObject(json);
        String txGroup = jsonObject.getString("txGroup");
        return JSON.parseObject(txGroup, TxGroup.class);
    }


    @Override
    public boolean executeCompensate(String path) throws ServiceException {

        String json = compensateDao.getCompensate(path);
        if (json == null) {
            throw new ServiceException("no data existing");
        }

        boolean hasOk = _executeCompensate(json);
        if (hasOk) {
            // ????????????????????????
            compensateDao.deleteCompensateByPath(path);

            return true;
        }
        return false;
    }


    private boolean _executeCompensate(String json) throws ServiceException {
        JSONObject jsonObject = JSON.parseObject(json);

        String model = jsonObject.getString("model");

        int startError = jsonObject.getInteger("startError");

        ModelInfo modelInfo = ModelInfoManager.getInstance().getModelByModel(model);
        if (modelInfo == null) {
            throw new ServiceException("current model offline.");
        }

        String data = jsonObject.getString("data");

        String groupId = jsonObject.getString("groupId");

        String res = managerSenderService.sendCompensateMsg(modelInfo.getChannelName(), groupId, data,startError);

        return "1".equals(res);
    }
}
