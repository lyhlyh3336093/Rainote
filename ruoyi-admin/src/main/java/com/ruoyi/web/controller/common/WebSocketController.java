package com.ruoyi.web.controller.common;

import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.system.service.WebSocketServer;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Controller
@RequestMapping("/wms/websocket")
public class WebSocketController extends BaseController
{
    private String prefix = "wms/websocket";

//    @RequiresPermissions("wms:websocket:view")
    @GetMapping()
    public String socket() {
        return prefix + "/websocket"; // 页面的访问路径
    }

//    @RequiresPermissions("wms:websocket:edit")
    //    @PreAuthorize("@ss.hasPermi('wms:websocket:edit')")

//    //推送数据接口
//    @ResponseBody
//    @RequestMapping("/push/{cid}")
//    public Map<String, Object> pushToWeb(@PathVariable String cid, String message) {
//        if (message == null) {
//            message = "我是消息!!!";
//        }
//        Map<String, Object> result = new HashMap<>();
//        try {
//            WebSocketServer.sendInfo(message, cid);
//            result.put("code", 200);
//            result.put("msg", "success");
//        } catch (IOException e) {
//            e.printStackTrace();
//        }
//        return result;
//    }

}

