package com.exam_bank.auth_service.dto.internal;

import java.io.Serializable;

public class UserPresenceEvent implements Serializable {

    private Long userId;
    private String role;
    private String eventType;
    private long timestamp;
    private int onlineCount;

    public UserPresenceEvent() {}

    public UserPresenceEvent(Long userId, String role, String eventType, long timestamp, int onlineCount) {
        this.userId = userId;
        this.role = role;
        this.eventType = eventType;
        this.timestamp = timestamp;
        this.onlineCount = onlineCount;
    }

    public static UserPresenceEvent snapshot(String role, int onlineCount) {
        return new UserPresenceEvent(null, role, "SNAPSHOT", System.currentTimeMillis(), onlineCount);
    }

    public static UserPresenceEvent join(Long userId, String role, int onlineCount) {
        return new UserPresenceEvent(userId, role, "JOIN", System.currentTimeMillis(), onlineCount);
    }

    public static UserPresenceEvent leave(Long userId, String role, int onlineCount) {
        return new UserPresenceEvent(userId, role, "LEAVE", System.currentTimeMillis(), onlineCount);
    }

    public Long getUserId() {
        return userId;
    }

    public String getRole() {
        return role;
    }

    public String getEventType() {
        return eventType;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public int getOnlineCount() {
        return onlineCount;
    }
}
