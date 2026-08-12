package com.gahyeonbot.adapters.world;

import com.gahyeonbot.application.behavior.WorldActionLedger;
import com.gahyeonbot.core.world.WorldActivity;
import com.gahyeonbot.core.world.WorldId;
import com.gahyeonbot.core.world.WorldPosition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public final class JdbcWorldActionLedger implements WorldActionLedger {
    private final JdbcTemplate jdbc;
    private final boolean postgres;

    public JdbcWorldActionLedger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.postgres = Boolean.TRUE.equals(jdbc.execute(
                (ConnectionCallback<Boolean>) connection -> "PostgreSQL".equalsIgnoreCase(
                        connection.getMetaData().getDatabaseProductName())));
    }

    @Override
    public boolean create(PendingAction action) {
        String insert = """
                INSERT INTO gahyeon_world_actions (
                    action_id, world_id, pending_world_id, expected_revision, target_room,
                    source_position_x, source_position_y, source_position_z,
                    position_x, position_y, position_z, activity, interaction_target,
                    status, requested_at, execute_after, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?)
                """;
        try {
            return jdbc.update(insert + (postgres ? " ON CONFLICT DO NOTHING" : ""),
                    action.actionId(), action.worldId().value(), action.worldId().value(),
                    action.expectedRevision(), action.room(), action.sourcePosition().x(),
                    action.sourcePosition().y(), action.sourcePosition().z(), action.position().x(),
                    action.position().y(), action.position().z(), action.activity().name(),
                    action.interactionTarget(), Timestamp.from(action.requestedAt()),
                    Timestamp.from(action.executeAfter()),
                    Timestamp.from(action.expiresAt())) == 1;
        } catch (DuplicateKeyException duplicate) {
            // H2 has no PostgreSQL ON CONFLICT clause. Its unique constraints
            // provide the same idempotency guarantee for local development.
            return false;
        }
    }

    @Override
    public Optional<ActionRecord> find(String actionId) {
        return jdbc.query("SELECT * FROM gahyeon_world_actions WHERE action_id = ?",
                this::map, actionId).stream().findFirst();
    }

    @Override
    public Optional<ActionRecord> findPending(WorldId worldId) {
        return jdbc.query("""
                        SELECT * FROM gahyeon_world_actions
                        WHERE pending_world_id = ?
                        """, this::map, worldId.value()).stream().findFirst();
    }

    @Override
    public List<ActionRecord> findExpired(Instant now, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return jdbc.query("""
                SELECT * FROM gahyeon_world_actions
                WHERE status IN ('PENDING', 'PROCESSING') AND expires_at < ?
                ORDER BY expires_at ASC LIMIT ?
                """, this::map, Timestamp.from(now), safeLimit);
    }

    @Override
    public List<ActionRecord> findReady(Instant now, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return jdbc.query("""
                SELECT * FROM gahyeon_world_actions
                WHERE status = 'PENDING' AND execute_after <= ?
                ORDER BY execute_after ASC LIMIT ?
                """, this::map, Timestamp.from(now), safeLimit);
    }

    @Override
    public int countPending() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM gahyeon_world_actions WHERE pending_world_id IS NOT NULL",
                Integer.class);
        return count == null ? 0 : count;
    }

    @Override
    public boolean claim(String actionId) {
        return jdbc.update("""
                UPDATE gahyeon_world_actions SET status = 'PROCESSING'
                WHERE action_id = ? AND status = 'PENDING'
                """, actionId) == 1;
    }

    @Override
    public boolean finishClaimed(
            String actionId, ActionStatus status, String result, Instant completedAt) {
        if (status == ActionStatus.PENDING || status == ActionStatus.PROCESSING) {
            throw new IllegalArgumentException("terminal status required");
        }
        return jdbc.update("""
                UPDATE gahyeon_world_actions
                SET status = ?, result = ?, completed_at = ?, pending_world_id = NULL
                WHERE action_id = ? AND status = 'PROCESSING'
                """, status.name(), result, Timestamp.from(completedAt), actionId) == 1;
    }

    @Override
    public boolean expirePending(String actionId, String result, Instant completedAt) {
        return jdbc.update("""
                UPDATE gahyeon_world_actions
                SET status = 'FAILED', result = ?, completed_at = ?, pending_world_id = NULL
                WHERE action_id = ? AND status = 'PENDING' AND expires_at < ?
                """, result, Timestamp.from(completedAt), actionId,
                Timestamp.from(completedAt)) == 1;
    }

    private ActionRecord map(ResultSet rs, int row) throws SQLException {
        Timestamp completed = rs.getTimestamp("completed_at");
        var pending = new PendingAction(
                rs.getString("action_id"), new WorldId(rs.getString("world_id")),
                rs.getLong("expected_revision"),
                new WorldPosition(rs.getDouble("source_position_x"),
                        rs.getDouble("source_position_y"), rs.getDouble("source_position_z")),
                rs.getString("target_room"),
                new WorldPosition(rs.getDouble("position_x"), rs.getDouble("position_y"),
                        rs.getDouble("position_z")),
                WorldActivity.valueOf(rs.getString("activity")),
                rs.getString("interaction_target"), rs.getTimestamp("requested_at").toInstant(),
                rs.getTimestamp("execute_after").toInstant(),
                rs.getTimestamp("expires_at").toInstant());
        return new ActionRecord(pending, ActionStatus.valueOf(rs.getString("status")),
                rs.getString("result"), completed == null ? null : completed.toInstant());
    }
}
