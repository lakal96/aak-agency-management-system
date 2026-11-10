package lk.aak.agency.intake;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** "Someone outside the system last checked in at ...", for example the Gmail script at every run. */
@Entity
@Table(name = "integration_heartbeat")
public class IntegrationHeartbeat {

    @Id
    @Column(length = 60)
    private String name;

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    @Column(length = 300)
    private String detail;

    public IntegrationHeartbeat() {
    }

    public IntegrationHeartbeat(String name) {
        this.name = name;
    }

    public String getName() { return name; }
    public LocalDateTime getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(LocalDateTime lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
}
