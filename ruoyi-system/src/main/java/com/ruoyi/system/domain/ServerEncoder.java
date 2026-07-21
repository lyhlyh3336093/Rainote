package com.ruoyi.system.domain;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.system.domain.Messagepojo;

import javax.websocket.EncodeException;
import javax.websocket.Encoder;
import javax.websocket.EndpointConfig;


/**
 * definition for our encoder
 *
 * @编写人: 夏小雪 日期:2015年6月14日 时间:上午11:58:23
 */
public class ServerEncoder implements Encoder.Text<Messagepojo> {

    @Override
    public void destroy() {
        // TODO Auto-generated method stub

    }

    @Override
    public void init(EndpointConfig arg0) {
        // TODO Auto-generated method stub

    }

    @Override
    public String encode(Messagepojo messagepojo) throws EncodeException {

        return JSONObject.toJSONString(messagepojo);
    }

}