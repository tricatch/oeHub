package tricatch.oe.hub.model;

import java.time.LocalDateTime;

// Common audit columns every table carries (see CLAUDE.md "Database Schema"). created_by/
// updated_by hold a HUB_USR.user_no value but are never FK-constrained (soft reference), so
// deleting a user never blocks on rows they created or touched elsewhere.
public abstract class Auditable {
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createAt;
    private LocalDateTime updatedAt;

    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }

    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }

    public LocalDateTime getCreateAt() { return createAt; }
    public void setCreateAt(LocalDateTime createAt) { this.createAt = createAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
