package com.ruoyi.system.domain;

import java.io.Serializable;

/**
 *  socket 所用实体类
 * 
 */
public class Messagepojo implements Serializable {

    private static final long serialVersionUID = -6451812593150428369L;

    private String from;// 信息来源
    private String messageType;// 消息类型
    private String message;// 消息内容
    private String to;// 发送目的地
    private String infoSourceIP;// 信息来源ip
    private String createtime;// 消息保存时间
    private String otherContent;// 其他信息

    public Messagepojo() {
        super();
    }

    public Messagepojo(String from, String messageType, String message,
                       String to, String infoSourceIP, String createtime,
                       String otherContent) {
        super();
        this.from = from;
        this.messageType = messageType;
        this.message = message;
        this.to = to;
        this.infoSourceIP = infoSourceIP;
        this.createtime = createtime;
        this.otherContent = otherContent;
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getMessageType() {
        return messageType;
    }

    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getTo() {
        return to;
    }

    public void setTo(String to) {
        this.to = to;
    }

    public String getInfoSourceIP() {
        return infoSourceIP;
    }

    public void setInfoSourceIP(String infoSourceIP) {
        this.infoSourceIP = infoSourceIP;
    }

    public String getCreatetime() {
        return createtime;
    }

    public void setCreatetime(String createtime) {
        this.createtime = createtime;
    }

    public String getOtherContent() {
        return otherContent;
    }

    public void setOtherContent(String otherContent) {
        this.otherContent = otherContent;
    }

    @Override
    public String toString() {
        return "Messagepojo [from=" + from + ", messageType=" + messageType
                + ", message=" + message + ", to=" + to
                + ", infoSourceIP=" + infoSourceIP + ", createtime="
                + createtime + ", otherContent=" + otherContent + "]";
    }

}