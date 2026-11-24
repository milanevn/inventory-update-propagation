package edu.inventory.node.recovery;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** JDBC access to the single {@code node_state} row. */
@Repository
public class NodeStateRepository {

    private final JdbcTemplate jdbc;

    public NodeStateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public NodeState read() {
        return jdbc.queryForObject(
                "SELECT role, epoch, last_issued_seq, last_applied_seq, status FROM node_state WHERE singleton = TRUE",
                (rs, rowNum) -> new NodeState(
                        rs.getString("role"),
                        rs.getLong("epoch"),
                        rs.getLong("last_issued_seq"),
                        rs.getLong("last_applied_seq"),
                        rs.getString("status")));
    }

    /**
     * Stores {@code initialRole} only if the node has no role yet (fresh database).
     *
     * @return the role stored in {@code node_state} after the call
     */
    @Transactional
    public String initializeRoleIfMissing(String initialRole) {
        jdbc.update("UPDATE node_state SET role = ?, updated_at = CURRENT_TIMESTAMP WHERE singleton = TRUE AND role IS NULL",
                initialRole);
        return jdbc.queryForObject("SELECT role FROM node_state WHERE singleton = TRUE", String.class);
    }
}
