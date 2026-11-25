package edu.inventory.node.core;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * JDBC access to the single {@code node_state} row.
 *
 * <p>The write-path methods ({@link #lockForWrite()}, {@link #issueNextSeq()}, {@link #markApplied(long)})
 * join the caller's transaction ({@link Propagation#MANDATORY}) and fail without one: the caller
 * (inventory on the primary, replication on a replica, recovery during replay) commits the business rows,
 * {@code operation_log} and {@code node_state} together. {@link #initializeRoleIfMissing(String)} is the
 * exception: a one-off startup step outside the write path, so it runs in its own transaction.
 */
@Repository
public class NodeStateRepository {

    private static final String COLUMNS = "role, epoch, last_issued_seq, last_applied_seq, status";
    private static final RowMapper<NodeState> MAPPER = (rs, rowNum) -> new NodeState(
            rs.getString("role"),
            rs.getLong("epoch"),
            rs.getLong("last_issued_seq"),
            rs.getLong("last_applied_seq"),
            rs.getString("status"));

    private final JdbcTemplate jdbc;

    public NodeStateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Reads the row without locking (status reporting). */
    public NodeState read() {
        return jdbc.queryForObject("SELECT " + COLUMNS + " FROM node_state WHERE singleton = TRUE", MAPPER);
    }

    /**
     * Locks the row ({@code SELECT ... FOR UPDATE}) until the caller's transaction ends. Every write path
     * takes this lock first, so writes on a node are serialised in {@code seq} order.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public NodeState lockForWrite() {
        return jdbc.queryForObject("SELECT " + COLUMNS + " FROM node_state WHERE singleton = TRUE FOR UPDATE", MAPPER);
    }

    /**
     * Primary only: issues {@code seq = last_issued_seq + 1} and advances {@code last_issued_seq} and
     * {@code last_applied_seq} together. The counter lives in the locked row, not in a database sequence,
     * so a rollback also undoes the increment and leaves no gap. Call {@link #lockForWrite()} first when
     * checks (such as request deduplication) must run under the same lock.
     *
     * @return the new {@code seq} and the epoch read in the same statement; stamp the event with this epoch
     * @throws IllegalStateException if the node is not {@code PRIMARY} or
     *         {@code last_issued_seq != last_applied_seq} (never true on a primary)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public IssuedSeq issueNextSeq() {
        List<IssuedSeq> issued = jdbc.query("""
                UPDATE node_state
                   SET last_issued_seq = last_issued_seq + 1,
                       last_applied_seq = last_applied_seq + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE singleton = TRUE AND role = 'PRIMARY' AND last_issued_seq = last_applied_seq
                RETURNING epoch, last_issued_seq""",
                (rs, rowNum) -> new IssuedSeq(rs.getLong("epoch"), rs.getLong("last_issued_seq")));
        if (issued.isEmpty()) {
            throw new IllegalStateException("Cannot issue seq: node is not PRIMARY or counters differ " + read());
        }
        return issued.get(0);
    }

    /**
     * Replica and replay: records that the event with {@code seq} has been applied. Only the next
     * contiguous {@code seq} is accepted; {@code last_issued_seq} is not touched.
     *
     * @throws IllegalStateException if {@code seq != last_applied_seq + 1}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markApplied(long seq) {
        int updated = jdbc.update("""
                UPDATE node_state
                   SET last_applied_seq = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE singleton = TRUE AND last_applied_seq = ? - 1""", seq, seq);
        if (updated != 1) {
            throw new IllegalStateException("Cannot mark seq " + seq + " applied: not contiguous with " + read());
        }
    }

    /**
     * Stores {@code initialRole} only if the node has no role yet (fresh database). Called once at startup
     * by {@code NodeRoleInitializer}; opens its own transaction (see the class comment).
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
