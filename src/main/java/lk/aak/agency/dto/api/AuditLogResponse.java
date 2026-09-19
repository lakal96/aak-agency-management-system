package lk.aak.agency.dto.api;

import lk.aak.agency.model.AuditLog;

import java.time.LocalDateTime;

public class AuditLogResponse {

    private final Long id;
    private final String username;
    private final String action;
    private final String entityType;
    private final Long entityId;
    private final String details;
    private final LocalDateTime createdAt;

    public AuditLogResponse(AuditLog log) {
        this.id = log.getId();
        this.username = log.getUsername();
        this.action = log.getAction();
        this.entityType = log.getEntityType();
        this.entityId = log.getEntityId();
        this.details = log.getDetails();
        this.createdAt = log.getCreatedAt();
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getAction() {
        return action;
    }

    public String getEntityType() {
        return entityType;
    }

    public Long getEntityId() {
        return entityId;
    }

    public String getDetails() {
        return details;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
