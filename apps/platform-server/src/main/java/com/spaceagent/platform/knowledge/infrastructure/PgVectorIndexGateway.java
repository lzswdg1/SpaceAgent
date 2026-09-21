package com.spaceagent.platform.knowledge.infrastructure;

import com.spaceagent.platform.knowledge.domain.VectorIndexGateway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/** Compact rebuildable replica. Exact scoped cosine and local lexical recall; no access authority. */
public class PgVectorIndexGateway implements VectorIndexGateway {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final int timeout;

    public PgVectorIndexGateway(JdbcTemplate jdbc, int timeout) {
        if (timeout < 1 || timeout > 30) throw new IllegalArgumentException("Invalid pgvector query timeout");
        this.jdbc = jdbc;
        this.timeout = timeout;
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
    }

    @Override public void validateSpace(Space space) {
        if (space.dimensions() > 16000) throw new IllegalArgumentException("PGVECTOR_DIMENSIONS_UNSUPPORTED: maximum is 16000");
    }

    @Override public void ensureSpace(Space space) {
        validateSpace(space);
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                INSERT INTO platform_knowledge_pgvector_spaces(space_id,schema_kind,fingerprint,dimensions)
                VALUES(?,?,?,?) ON CONFLICT(space_id,schema_kind) DO NOTHING
                """, space.id(), space.schema(), space.fingerprint(), space.dimensions());
            requireSpace(space);
        });
    }

    @Override public boolean spaceExists(Space space) {
        validateSpace(space);
        var rows = jdbc.queryForList("SELECT fingerprint,dimensions FROM platform_knowledge_pgvector_spaces WHERE space_id=? AND schema_kind=?",
                space.id(), space.schema());
        if (rows.isEmpty()) return false;
        var row = rows.getFirst();
        if (!space.fingerprint().equals(row.get("fingerprint")) || ((Number) row.get("dimensions")).intValue() != space.dimensions()) {
            throw new IllegalStateException("PGVECTOR_SPACE_SCHEMA_MISMATCH");
        }
        return true;
    }

    private void requireSpace(Space space) {
        if (!spaceExists(space)) throw new IllegalStateException("PGVECTOR_SPACE_NOT_FOUND");
    }

    @Override public void upsertBatch(Space space, List<Entry> entries) {
        var rows = rows(space, entries);
        tx.executeWithoutResult(status -> {
            requireSpace(space);
            int[][] counts = jdbc.batchUpdate("""
                INSERT INTO platform_knowledge_pgvector_entries AS original
                  (space_id,schema_kind,dimensions,scope_key,base_id,document_id,generation_id,chunk_id,
                   content_hash,payload_hash,embedding,lexical)
                VALUES(?,?,?,?,?,?,?,?,?,?,CAST(? AS public.vector),to_tsvector('pg_catalog.simple',?))
                ON CONFLICT(space_id,schema_kind,scope_key,base_id,generation_id,chunk_id)
                DO UPDATE SET payload_hash=original.payload_hash
                  WHERE original.payload_hash=EXCLUDED.payload_hash
                """, rows, rows.size(), (statement, row) -> bindEntry(statement, space, row));
            for (var batch : counts) for (int count : batch) {
                if (count != 1) throw new IllegalStateException("PGVECTOR_IMMUTABLE_ENTRY_CONFLICT");
            }
        });
    }

    private static void bindEntry(PreparedStatement s, Space space, Row row) throws SQLException {
        var e = row.entry();
        s.setString(1, space.id()); s.setString(2, space.schema()); s.setInt(3, space.dimensions());
        s.setString(4, e.scopeKey()); s.setString(5, e.baseId()); s.setString(6, e.documentId());
        s.setString(7, e.generationId()); s.setString(8, e.chunkId()); s.setString(9, e.contentHash());
        s.setString(10, row.payloadHash()); s.setString(11, row.vector()); s.setString(12, row.lexical());
    }

    @Override public boolean verifyBatch(Space space, List<Entry> entries) {
        var expected = rows(space, entries);
        requireSpace(space);
        var parameters = new ArrayList<Object>(List.of(space.id(), space.schema()));
        for (var row : expected) parameters.addAll(List.of(row.entry().scopeKey(), row.entry().baseId(), row.entry().generationId(), row.entry().chunkId()));
        String tuples = String.join(",", Collections.nCopies(expected.size(), "(?,?,?,?)"));
        var actual = jdbc.query("SELECT scope_key,base_id,generation_id,chunk_id,payload_hash FROM platform_knowledge_pgvector_entries "
                + "WHERE space_id=? AND schema_kind=? AND (scope_key,base_id,generation_id,chunk_id) IN (" + tuples + ")",
                (r, n) -> Map.entry(key(r.getString(1), r.getString(2), r.getString(3), r.getString(4)), r.getString(5)), parameters.toArray());
        Map<String, String> hashes = new HashMap<>();
        actual.forEach(row -> hashes.put(row.getKey(), row.getValue()));
        return expected.stream().allMatch(row -> row.payloadHash().equals(hashes.get(entryKey(row.entry()))));
    }

    @Override public List<Match> search(Space space, Scope scope, List<Double> query, int topK) {
        topK(topK);
        String vector = vector(space, query);
        requireSpace(space);
        var parameters = new ArrayList<Object>(List.of(vector, space.id(), space.schema(), scope.key(), scope.baseId()));
        parameters.addAll(scope.generationIds()); parameters.add(topK);
        // Scope is applied before scoring all matching rows. No global ANN post-filter/overfetch leak.
        return query("""
            SELECT document_id,generation_id,chunk_id,content_hash,
                   1-(embedding OPERATOR(public.<=>) CAST(? AS public.vector)) AS score
            FROM platform_knowledge_pgvector_entries
            WHERE space_id=? AND schema_kind=? AND scope_key=? AND base_id=? AND generation_id IN (
            """ + placeholders(scope) + ") ORDER BY score DESC,generation_id,chunk_id LIMIT ?", parameters);
    }

    @Override public List<Match> lexicalSearch(Space space, Scope scope, String text, int topK) {
        if (!space.schema().equals("hybrid_v2")) return List.of();
        topK(topK);
        if (text == null || text.isBlank() || text.length() > 4000) throw new IllegalArgumentException("Invalid lexical query");
        requireSpace(space);
        var tokens = PgVectorLexicalTokens.tokens(text, 64);
        // Prefer CJK bigrams over broad single-character recall when the query contains phrases.
        boolean bigrams = tokens.stream().anyMatch(token -> token.startsWith("b"));
        String lex = String.join(" | ", tokens.stream().filter(token -> !bigrams || !token.startsWith("h")).toList());
        if (lex.isEmpty()) return List.of();
        var parameters = new ArrayList<Object>(List.of(lex, space.id(), space.schema(), scope.key(), scope.baseId()));
        parameters.addAll(scope.generationIds()); parameters.add(topK);
        return query("""
            SELECT document_id,generation_id,chunk_id,content_hash,ts_rank_cd(lexical,q) AS score
            FROM platform_knowledge_pgvector_entries, to_tsquery('pg_catalog.simple',?) q
            WHERE space_id=? AND schema_kind=? AND scope_key=? AND base_id=? AND generation_id IN (
            """ + placeholders(scope) + ") AND lexical @@ q ORDER BY score DESC,generation_id,chunk_id LIMIT ?", parameters);
    }

    private List<Match> query(String sql, List<Object> parameters) {
        return jdbc.query(connection -> {
            var statement = connection.prepareStatement(sql);
            statement.setQueryTimeout(timeout);
            for (int i = 0; i < parameters.size(); i++) statement.setObject(i + 1, parameters.get(i));
            return statement;
        }, (r, n) -> match(r));
    }

    private static Match match(ResultSet r) throws SQLException {
        double score = r.getDouble("score");
        if (r.wasNull() || !Double.isFinite(score)) throw new IllegalStateException("PGVECTOR_SCORE_INVALID");
        return new Match(r.getString("document_id"), r.getString("generation_id"), r.getString("chunk_id"), r.getString("content_hash"), score);
    }

    @Override public void deleteGeneration(Space space, Scope scope, String generation) {
        requireGeneration(scope, generation);
        requireSpace(space);
        jdbc.update("DELETE FROM platform_knowledge_pgvector_entries WHERE space_id=? AND schema_kind=? AND scope_key=? AND base_id=? AND generation_id=?",
                space.id(), space.schema(), scope.key(), scope.baseId(), generation);
    }

    @Override public boolean generationDeleted(Space space, Scope scope, String generation) {
        requireGeneration(scope, generation);
        requireSpace(space);
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT NOT EXISTS(SELECT 1 FROM platform_knowledge_pgvector_entries "
                + "WHERE space_id=? AND schema_kind=? AND scope_key=? AND base_id=? AND generation_id=?)", Boolean.class,
                space.id(), space.schema(), scope.key(), scope.baseId(), generation));
    }

    private List<Row> rows(Space space, List<Entry> entries) {
        validateSpace(space);
        if (entries == null || entries.isEmpty() || entries.size() > 64 || (long) entries.size() * space.dimensions() > 262144) {
            throw new IllegalArgumentException("Vector batch exceeds bounds");
        }
        var result = new ArrayList<Row>();
        var unique = new HashSet<String>();
        for (var entry : entries) {
            if (!unique.add(entryKey(entry))) throw new IllegalArgumentException("Duplicate vector entry");
            String vector = vector(space, entry.vector());
            String lexical = "", text = "";
            if (space.schema().equals("hybrid_v2")) {
                text = entry.text();
                if (text == null || text.getBytes(StandardCharsets.UTF_8).length > 65535 || !hash(text).equals(entry.contentHash())) {
                    throw new IllegalArgumentException("Hybrid text must match authoritative chunk hash");
                }
                lexical = String.join(" ", PgVectorLexicalTokens.tokens(text, 65536));
            }
            String payload = hash(String.join("\n", "PGVECTOR_COSINE_LEXICAL_V1", entry.scopeKey(), entry.baseId(), entry.documentId(),
                    entry.generationId(), entry.chunkId(), entry.contentHash(), vector, text, lexical));
            result.add(new Row(entry, vector, lexical, payload));
        }
        return List.copyOf(result);
    }

    private String vector(Space space, List<Double> values) {
        validateSpace(space);
        if (values == null || values.size() != space.dimensions()) throw new IllegalArgumentException("Vector dimensions differ from space");
        var numbers = new ArrayList<String>(); boolean nonzero = false;
        for (Double value : values) {
            if (value == null || !Double.isFinite(value) || !Float.isFinite(value.floatValue())) throw new IllegalArgumentException("Invalid vector component");
            float number = value.floatValue(); nonzero |= number != 0;
            numbers.add(Float.toString(number));
        }
        if (!nonzero) throw new IllegalArgumentException("Cosine vector cannot be zero");
        return "[" + String.join(",", numbers) + "]";
    }

    private static void topK(int value) { if (value < 1 || value > 100) throw new IllegalArgumentException("Invalid vector topK"); }
    private static void requireGeneration(Scope scope, String generation) {
        if (!scope.generationIds().contains(generation)) throw new IllegalArgumentException("Generation outside allowed scope");
    }
    private static String placeholders(Scope scope) { return String.join(",", Collections.nCopies(scope.generationIds().size(), "?")); }
    private static String key(String scope, String base, String generation, String chunk) { return String.join("\n", scope, base, generation, chunk); }
    private static String entryKey(Entry entry) { return key(entry.scopeKey(), entry.baseId(), entry.generationId(), entry.chunkId()); }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private record Row(Entry entry, String vector, String lexical, String payloadHash) {}
}
