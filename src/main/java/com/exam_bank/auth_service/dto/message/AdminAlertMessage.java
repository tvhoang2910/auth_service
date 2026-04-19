package com.exam_bank.auth_service.dto.message;

import java.util.List;
import java.util.Map;

public class AdminAlertMessage {

    private String type;
    private String title;
    private String body;
    private List<String> targetRoles;
    private String url;
    private Map<String, Object> metadata;

    public AdminAlertMessage() {
    }

    public AdminAlertMessage(
            String type,
            String title,
            String body,
            List<String> targetRoles,
            String url,
            Map<String, Object> metadata) {
        this.type = type;
        this.title = title;
        this.body = body;
        this.targetRoles = targetRoles;
        this.url = url;
        this.metadata = metadata;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public List<String> getTargetRoles() {
        return targetRoles;
    }

    public void setTargetRoles(List<String> targetRoles) {
        this.targetRoles = targetRoles;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
}
