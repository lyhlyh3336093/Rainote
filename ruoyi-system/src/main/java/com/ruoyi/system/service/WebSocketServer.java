package com.ruoyi.system.service;


import com.ruoyi.system.domain.Messagepojo;
import com.ruoyi.system.domain.ServerEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import javax.websocket.*;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArraySet;

// @ServerEndpoint 声明并创建了webSocket端点, 并且指明了请求路径
// id 为客户端请求时携带的参数, 用于服务端区分客户端使用
@ServerEndpoint(value = "/WebSocketServer/{uid}",encoders = {ServerEncoder.class})
@Component
public class WebSocketServer extends TextWebSocketHandler {

    // 日志对象
    private static final Logger log = LoggerFactory.getLogger(WebSocketServer.class);

    // 静态变量，用来记录当前在线连接数。应该把它设计成线程安全的。
    private static int onlineCount = 0;

    // concurrent包的线程安全Set，用来存放每个客户端对应的MyWebSocket对象。
    private static CopyOnWriteArraySet<WebSocketServer> webSocketSet = new CopyOnWriteArraySet<>();
    // private static ConcurrentHashMap<String,WebSocketServer> websocketList = new ConcurrentHashMap<>();


    // 与某个客户端的连接会话，需要通过它来给客户端发送数据
    private Session session;

    // 接收sid
    private String sid = "";

    /*
     * 客户端创建连接时触发
     * */
    @OnOpen
    public void onOpen(Session session, @PathParam("sid") String sid) {
        this.session = session;
        webSocketSet.add(this); // 加入set中
        addOnlineCount(); // 在线数加1
        Map<String, List<String>> params = session.getRequestParameterMap();
        List<String> userIds = params.get("uid");
        String userid ="";
        if (!userIds.isEmpty()) {
            // 取出userid参数的值
            userid = userIds.get(0);
            System.out.println("用户id是" + userid);

            // 在这里可以根据接收到的userid参数执行相应的逻辑
        }

        log.info("有新用户链接id为:" + userid + ", 当前在线人数为" + getOnlineCount());
        this.sid = userid;
        try {
            sendMessage("连接成功");
        } catch (IOException e) {
            log.error("websocket IO异常");
        }
    }

    /**
     * 客户端连接关闭时触发
     **/
    @OnClose
    public void onClose() {
        String id = this.sid;
        webSocketSet.remove(this); // 从set中删除
        subOnlineCount(); // 在线数减1
        log.info("id为"+id+"的用户连接关闭！当前在线人数为" + getOnlineCount());
    }

    /**
     * 接收到客户端消息时触发
     */
    @OnMessage
    public void onMessage(String message, Session session) {
        if(!message.equals("heartbeat")){
            log.info("收到来自窗口" + sid + "的信息:" + message);
        }
        Map<String, List<String>> params = session.getRequestParameterMap();
        List<String> fromId = params.get("from");
        List<String> toId = params.get("to");
        // 群发消息
//        for (WebSocketServer item : webSocketSet) {
//            try {
//                System.out.println("当前用户id："+item.sid+"收到了："+session.getId()+"的消息");
//                if(item.sid!=session.getId()){
//                    item.sendMessage(message);
//                }
//
//            } catch (IOException e) {
//                e.printStackTrace();
//            }
//        }
    }

    /**
     * 连接发生异常时候触发
     */
    @OnError
    public void onError(Session session, Throwable error) {
        log.error("发生错误");
        error.printStackTrace();
    }

    /**
     * 实现服务器主动推送(向浏览器发消息)
     */
    public void sendMessage(String message) throws IOException {
        log.info("服务器消息推送："+message);
        this.session.getBasicRemote().sendText(message);
    }



    /**
     * 实现服务器主动推送(向浏览器发消息)
     */
    public void sendMessageToUid(String message,String uid) throws IOException {
        log.info("服务器消息推送："+message);
        this.session.getBasicRemote().sendText(message);
    }

    /**
     * 发送消息到所有客户端
     * 指定sid则向指定客户端发消息
     * 不指定sid则向所有客户端发送消息
     * */
    public static void sendInfo(String message, @PathParam("sid") String sid) throws IOException {
        log.info("推送消息到窗口" + sid + "，推送内容:" + message);
        for (WebSocketServer item : webSocketSet) {
            try {
                // 这里可以设定只推送给这个sid的，为null则全部推送
                if (sid == null) {
                    item.sendMessage(message);
                } else if (item.sid.equals(sid)) {
                    item.sendMessage(message);
                }
            } catch (IOException e) {
                continue;
            }
        }
    }


    /**
     * 发送消息到所有客户端
     * 指定sid则向指定客户端发消息
     * 不指定sid则向所有客户端发送消息
     * */
    public static void singleTalk(String message, @PathParam("sid") String sid,@PathParam("from") String from,@PathParam("to") String to) throws IOException {
        log.info("私聊消息：" + sid + "的session，由"+from+"发送给"+to+"，发送内容:" + message);

        for (WebSocketServer item : webSocketSet) {
            try {
                 if (item.sid.equals(sid)) {
                     Messagepojo messagepojo = new Messagepojo();
                     messagepojo.setFrom(from);
                     messagepojo.setTo(to);
                     messagepojo.setMessage(message);
                     item.session.getBasicRemote().sendObject(messagepojo);

                }
            } catch (IOException e) {
                continue;
            } catch (EncodeException e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        }
    }

//    @Scheduled(fixedRate = 10000)
//    public void heartBeats(){
//        for (WebSocketServer item : webSocketSet) {
//            try {
//                item.sendMessage("heartBeats is OK!");
//            } catch (IOException e) {
//                continue;
//            }
//        }
//    }


    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {

        log.info("私聊消息：" + session.getId() + "的session，发送内容:" + message);
//        // 处理接收到的消息
//        String payload = message.getPayload();
//        // 假设消息格式为 "targetUserId:messageContent"
//        String[] parts = payload.split(":", 2);
//        String targetUserId = parts[0];
//        String messageContent = parts[1];
//        WebSocketSession targetSession;
//
//
//        for (WebSocketServer item : webSocketSet) {
//            //只有在线的才会在websocketset里，所以如果能找到对应的uid的session，那么就能直接发送消息过去
//            if(item.session.getId().equals(targetUserId)){
//                item.sendMessage(messageContent);
//            }
//        }

    }



    public static synchronized int getOnlineCount() {
        return onlineCount;
    }

    public static synchronized void addOnlineCount() {
        WebSocketServer.onlineCount++;
    }

    public static synchronized void subOnlineCount() {
        WebSocketServer.onlineCount--;
    }

    public static CopyOnWriteArraySet<WebSocketServer> getWebSocketSet() {
        return webSocketSet;
    }

    public String getSid() {
        return sid;
    }
}
