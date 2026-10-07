using Microsoft.Data.Sqlite;
using MubelOne.Models;

namespace MubelOne.Services;

public sealed class LocalStore
{
    public static string Root => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData),
        "MUBEL_ONE");

    public static string DbPath => Path.Combine(Root, "mubel_one.db");

    public async Task InitializeAsync()
    {
        Directory.CreateDirectory(Root);

        await using var cn = new SqliteConnection($"Data Source={DbPath}");
        await cn.OpenAsync();

        var cmd = cn.CreateCommand();
        cmd.CommandText = """
        PRAGMA journal_mode=WAL;

        CREATE TABLE IF NOT EXISTS observed_events(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            captured_at TEXT NOT NULL,
            event_name TEXT NOT NULL,
            database_name TEXT NULL,
            session_id INTEGER NULL,
            transaction_id TEXT NULL,
            client_app TEXT NULL,
            client_host TEXT NULL,
            login_name TEXT NULL,
            sql_text TEXT NOT NULL,
            fingerprint TEXT NOT NULL
        );
        CREATE INDEX IF NOT EXISTS ix_observed_events_time ON observed_events(captured_at);
        CREATE INDEX IF NOT EXISTS ix_observed_events_fp ON observed_events(fingerprint);

        CREATE TABLE IF NOT EXISTS learned_operations(
            fingerprint TEXT PRIMARY KEY,
            first_seen TEXT NOT NULL,
            last_seen TEXT NOT NULL,
            seen_count INTEGER NOT NULL,
            inferred_operation TEXT NULL,
            confidence REAL NOT NULL DEFAULT 0,
            evidence TEXT NULL
        );

        CREATE TABLE IF NOT EXISTS source_evidence(
            source_path TEXT PRIMARY KEY,
            extension TEXT NOT NULL,
            modified_utc TEXT NOT NULL,
            size_bytes INTEGER NOT NULL,
            sha256 TEXT NOT NULL,
            table_names TEXT NULL,
            dml_verbs TEXT NULL,
            indexed_at TEXT NOT NULL
        );
        CREATE INDEX IF NOT EXISTS ix_source_evidence_sha ON source_evidence(sha256);

        CREATE TABLE IF NOT EXISTS schema_catalog(
            object_name TEXT PRIMARY KEY,
            object_group TEXT NOT NULL,
            evidence_level TEXT NOT NULL,
            source_name TEXT NOT NULL,
            first_seen TEXT NOT NULL,
            last_seen TEXT NOT NULL
        );

        CREATE TABLE IF NOT EXISTS document_code_map(
            code INTEGER NOT NULL,
            map_type TEXT NOT NULL,
            mapped_value TEXT NOT NULL,
            evidence_level TEXT NOT NULL,
            source_name TEXT NOT NULL,
            PRIMARY KEY(code,map_type)
        );

        CREATE TABLE IF NOT EXISTS integration_health(
            integration_key TEXT PRIMARY KEY,
            display_name TEXT NOT NULL,
            state TEXT NOT NULL,
            detail TEXT NULL,
            checked_at TEXT NOT NULL
        );

        CREATE TABLE IF NOT EXISTS database_catalog(
            database_id INTEGER NOT NULL,
            database_name TEXT PRIMARY KEY,
            first_seen TEXT NOT NULL,
            last_seen TEXT NOT NULL
        );

        CREATE TABLE IF NOT EXISTS server_object_catalog(
            database_name TEXT NOT NULL,
            schema_name TEXT NOT NULL,
            object_name TEXT NOT NULL,
            object_type TEXT NOT NULL,
            primary_key_name TEXT NULL,
            columns_signature TEXT NULL,
            definition_hash TEXT NOT NULL,
            definition_redacted TEXT NULL,
            first_seen TEXT NOT NULL,
            last_seen TEXT NOT NULL,
            PRIMARY KEY(database_name,schema_name,object_name,object_type)
        );
        CREATE INDEX IF NOT EXISTS ix_server_object_catalog_db ON server_object_catalog(database_name);
        CREATE INDEX IF NOT EXISTS ix_server_object_catalog_name ON server_object_catalog(object_name);

        CREATE TABLE IF NOT EXISTS lineage_edges(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            database_name TEXT NOT NULL,
            transaction_id TEXT NULL,
            fingerprint TEXT NOT NULL,
            operation TEXT NOT NULL,
            target_object TEXT NOT NULL,
            source_object TEXT NOT NULL,
            observed_at TEXT NOT NULL,
            evidence_level TEXT NOT NULL,
            evidence_source TEXT NOT NULL
        );
        CREATE INDEX IF NOT EXISTS ix_lineage_target ON lineage_edges(target_object);
        CREATE INDEX IF NOT EXISTS ix_lineage_source ON lineage_edges(source_object);
        CREATE INDEX IF NOT EXISTS ix_lineage_tx ON lineage_edges(transaction_id);

        CREATE TABLE IF NOT EXISTS mutation_counters(
            database_name TEXT NOT NULL,
            object_name TEXT NOT NULL,
            insert_count INTEGER NOT NULL,
            update_count INTEGER NOT NULL,
            delete_count INTEGER NOT NULL,
            checked_at TEXT NOT NULL,
            PRIMARY KEY(database_name,object_name)
        );
        """;
        await cmd.ExecuteNonQueryAsync();

        await SeedAsync(cn);
    }

    private static async Task SeedAsync(SqliteConnection cn)
    {
        await using var tx = await cn.BeginTransactionAsync();

        foreach (var table in VegaSeedCatalog.SanalMagazaTables)
        {
            var cmd = cn.CreateCommand();
            cmd.Transaction = (SqliteTransaction)tx;
            cmd.CommandText = """
            INSERT INTO schema_catalog(object_name,object_group,evidence_level,source_name,first_seen,last_seen)
            VALUES($n,'VegaSanalMagaza','D','YILANCIOGLU tablo.sql 2026-07-15',$t,$t)
            ON CONFLICT(object_name) DO UPDATE SET last_seen=excluded.last_seen;
            """;
            cmd.Parameters.AddWithValue("$n", table);
            cmd.Parameters.AddWithValue("$t", DateTimeOffset.UtcNow.ToString("O"));
            await cmd.ExecuteNonQueryAsync();
        }

        foreach (var kv in VegaSeedCatalog.BelgeHeaderByIzahat)
        {
            var cmd = cn.CreateCommand();
            cmd.Transaction = (SqliteTransaction)tx;
            cmd.CommandText = """
            INSERT INTO document_code_map(code,map_type,mapped_value,evidence_level,source_name)
            VALUES($c,'BELGEIZAHAT_HEADER',$v,'D','YILANCIOGLU script.sql 2026-07-14')
            ON CONFLICT(code,map_type) DO UPDATE SET
                mapped_value=excluded.mapped_value,
                evidence_level=excluded.evidence_level,
                source_name=excluded.source_name;
            """;
            cmd.Parameters.AddWithValue("$c", kv.Key);
            cmd.Parameters.AddWithValue("$v", kv.Value);
            await cmd.ExecuteNonQueryAsync();
        }

        foreach (var kv in VegaSeedCatalog.PortfolioStatus)
        {
            var cmd = cn.CreateCommand();
            cmd.Transaction = (SqliteTransaction)tx;
            cmd.CommandText = """
            INSERT INTO document_code_map(code,map_type,mapped_value,evidence_level,source_name)
            VALUES($c,'PORTFOY_STATUS',$v,'D','YILANCIOGLU script.sql 2026-07-14')
            ON CONFLICT(code,map_type) DO UPDATE SET mapped_value=excluded.mapped_value;
            """;
            cmd.Parameters.AddWithValue("$c", kv.Key);
            cmd.Parameters.AddWithValue("$v", kv.Value);
            await cmd.ExecuteNonQueryAsync();
        }

        await tx.CommitAsync();
    }

    public async Task SaveEventAsync(ObservedEvent e)
    {
        await using var cn = new SqliteConnection($"Data Source={DbPath}");
        await cn.OpenAsync();

        var cmd = cn.CreateCommand();
        cmd.CommandText = """
        INSERT INTO observed_events
        (captured_at,event_name,database_name,session_id,transaction_id,client_app,client_host,login_name,sql_text,fingerprint)
        VALUES($t,$e,$d,$s,$x,$a,$h,$l,$q,$f);

        INSERT INTO learned_operations(fingerprint,first_seen,last_seen,seen_count,inferred_operation,confidence,evidence)
        VALUES($f,$t,$t,1,$op,$c,$ev)
        ON CONFLICT(fingerprint) DO UPDATE SET
            last_seen=excluded.last_seen,
            seen_count=learned_operations.seen_count+1,
            inferred_operation=CASE
                WHEN excluded.confidence >= learned_operations.confidence THEN excluded.inferred_operation
                ELSE learned_operations.inferred_operation END,
            confidence=MAX(learned_operations.confidence,excluded.confidence),
            evidence=CASE
                WHEN excluded.confidence >= learned_operations.confidence THEN excluded.evidence
                ELSE learned_operations.evidence END;
        """;

        cmd.Parameters.AddWithValue("$t", e.CapturedAt.ToString("O"));
        cmd.Parameters.AddWithValue("$e", e.EventName);
        cmd.Parameters.AddWithValue("$d", (object?)e.DatabaseName ?? DBNull.Value);
        cmd.Parameters.AddWithValue("$s", (object?)e.SessionId ?? DBNull.Value);
        cmd.Parameters.AddWithValue("$x", (object?)e.TransactionId ?? DBNull.Value);
        cmd.Parameters.AddWithValue("$a", (object?)e.ClientApp ?? DBNull.Value);
        cmd.Parameters.AddWithValue("$h", (object?)e.ClientHost ?? DBNull.Value);
        cmd.Parameters.AddWithValue("$l", (object?)e.LoginName ?? DBNull.Value);
        cmd.Parameters.AddWithValue("$q", e.SqlText);
        cmd.Parameters.AddWithValue("$f", e.Fingerprint);
        cmd.Parameters.AddWithValue("$op", (object?)e.InferredOperation ?? DBNull.Value);
        cmd.Parameters.AddWithValue("$c", e.Confidence);
        cmd.Parameters.AddWithValue("$ev", (object?)e.Evidence ?? DBNull.Value);
        await cmd.ExecuteNonQueryAsync();
    }

    public async Task SaveSourceEvidenceAsync(SourceEvidence e)
    {
        await using var cn = new SqliteConnection($"Data Source={DbPath}");
        await cn.OpenAsync();

        var cmd = cn.CreateCommand();
        cmd.CommandText = """
        INSERT INTO source_evidence
        (source_path,extension,modified_utc,size_bytes,sha256,table_names,dml_verbs,indexed_at)
        VALUES($p,$x,$m,$s,$h,$t,$v,$i)
        ON CONFLICT(source_path) DO UPDATE SET
            extension=excluded.extension,
            modified_utc=excluded.modified_utc,
            size_bytes=excluded.size_bytes,
            sha256=excluded.sha256,
            table_names=excluded.table_names,
            dml_verbs=excluded.dml_verbs,
            indexed_at=excluded.indexed_at;
        """;
        cmd.Parameters.AddWithValue("$p", e.SourcePath);
        cmd.Parameters.AddWithValue("$x", e.Extension);
        cmd.Parameters.AddWithValue("$m", e.ModifiedUtc.ToString("O"));
        cmd.Parameters.AddWithValue("$s", e.SizeBytes);
        cmd.Parameters.AddWithValue("$h", e.Sha256);
        cmd.Parameters.AddWithValue("$t", (object?)e.TableNames ?? DBNull.Value);
        cmd.Parameters.AddWithValue("$v", (object?)e.DmlVerbs ?? DBNull.Value);
        cmd.Parameters.AddWithValue("$i", DateTimeOffset.UtcNow.ToString("O"));
        await cmd.ExecuteNonQueryAsync();
    }

    public async Task UpsertDatabaseAsync(int databaseId, string databaseName)
    {
        await using var cn = new SqliteConnection($"Data Source={DbPath}");
        await cn.OpenAsync();
        var now = DateTimeOffset.UtcNow.ToString("O");
        var cmd = cn.CreateCommand();
        cmd.CommandText = """
        INSERT INTO database_catalog(database_id,database_name,first_seen,last_seen)
        VALUES($i,$n,$t,$t)
        ON CONFLICT(database_name) DO UPDATE SET
          database_id=excluded.database_id,
          last_seen=excluded.last_seen;
        """;
        cmd.Parameters.AddWithValue("$i", databaseId);
        cmd.Parameters.AddWithValue("$n", databaseName);
        cmd.Parameters.AddWithValue("$t", now);
        await cmd.ExecuteNonQueryAsync();
    }

    public async Task UpsertObjectAsync(
        string databaseName,
        string schemaName,
        string objectName,
        string objectType,
        string primaryKeyName,
        string columnsSignature,
        string definitionHash,
        string? definitionRedacted)
    {
        await using var cn = new SqliteConnection($"Data Source={DbPath}");
        await cn.OpenAsync();
        var now = DateTimeOffset.UtcNow.ToString("O");
        var cmd = cn.CreateCommand();
        cmd.CommandText = """
        INSERT INTO server_object_catalog
        (database_name,schema_name,object_name,object_type,primary_key_name,columns_signature,
         definition_hash,definition_redacted,first_seen,last_seen)
        VALUES($d,$s,$n,$t,$p,$c,$h,$r,$x,$x)
        ON CONFLICT(database_name,schema_name,object_name,object_type) DO UPDATE SET
          primary_key_name=excluded.primary_key_name,
          columns_signature=excluded.columns_signature,
          definition_hash=excluded.definition_hash,
          definition_redacted=excluded.definition_redacted,
          last_seen=excluded.last_seen;
        """;
        cmd.Parameters.AddWithValue("$d", databaseName);
        cmd.Parameters.AddWithValue("$s", schemaName);
        cmd.Parameters.AddWithValue("$n", objectName);
        cmd.Parameters.AddWithValue("$t", objectType);
        cmd.Parameters.AddWithValue("$p", (object?)primaryKeyName ?? DBNull.Value);
        cmd.Parameters.AddWithValue("$c", (object?)columnsSignature ?? DBNull.Value);
        cmd.Parameters.AddWithValue("$h", definitionHash);
        cmd.Parameters.AddWithValue("$r", (object?)definitionRedacted ?? DBNull.Value);
        cmd.Parameters.AddWithValue("$x", now);
        await cmd.ExecuteNonQueryAsync();
    }

    public async Task SaveLineageAsync(IEnumerable<LineageEdge> edges)
    {
        await using var cn = new SqliteConnection($"Data Source={DbPath}");
        await cn.OpenAsync();
        await using var tx = await cn.BeginTransactionAsync();

        foreach (var e in edges)
        {
            var cmd = cn.CreateCommand();
            cmd.Transaction = (SqliteTransaction)tx;
            cmd.CommandText = """
            INSERT INTO lineage_edges
            (database_name,transaction_id,fingerprint,operation,target_object,source_object,
             observed_at,evidence_level,evidence_source)
            VALUES($d,$x,$f,$o,$t,$s,$a,$l,$e);
            """;
            cmd.Parameters.AddWithValue("$d", e.DatabaseName);
            cmd.Parameters.AddWithValue("$x", (object?)e.TransactionId ?? DBNull.Value);
            cmd.Parameters.AddWithValue("$f", e.Fingerprint);
            cmd.Parameters.AddWithValue("$o", e.Operation);
            cmd.Parameters.AddWithValue("$t", e.TargetObject);
            cmd.Parameters.AddWithValue("$s", e.SourceObject);
            cmd.Parameters.AddWithValue("$a", e.ObservedAt.ToString("O"));
            cmd.Parameters.AddWithValue("$l", e.EvidenceLevel);
            cmd.Parameters.AddWithValue("$e", e.EvidenceSource);
            await cmd.ExecuteNonQueryAsync();
        }

        await tx.CommitAsync();
    }

    public async Task<(long InsertDelta,long UpdateDelta,long DeleteDelta)> UpsertMutationCounterAsync(
        string databaseName,
        string objectName,
        long inserts,
        long updates,
        long deletes)
    {
        await using var cn = new SqliteConnection($"Data Source={DbPath}");
        await cn.OpenAsync();

        long oldI = inserts, oldU = updates, oldD = deletes;
        var get = cn.CreateCommand();
        get.CommandText = """
        SELECT insert_count,update_count,delete_count
        FROM mutation_counters
        WHERE database_name=$d AND object_name=$o;
        """;
        get.Parameters.AddWithValue("$d", databaseName);
        get.Parameters.AddWithValue("$o", objectName);

        await using (var r = await get.ExecuteReaderAsync())
        {
            if (await r.ReadAsync())
            {
                oldI = r.GetInt64(0);
                oldU = r.GetInt64(1);
                oldD = r.GetInt64(2);
            }
        }

        var cmd = cn.CreateCommand();
        cmd.CommandText = """
        INSERT INTO mutation_counters(database_name,object_name,insert_count,update_count,delete_count,checked_at)
        VALUES($d,$o,$i,$u,$x,$t)
        ON CONFLICT(database_name,object_name) DO UPDATE SET
          insert_count=excluded.insert_count,
          update_count=excluded.update_count,
          delete_count=excluded.delete_count,
          checked_at=excluded.checked_at;
        """;
        cmd.Parameters.AddWithValue("$d", databaseName);
        cmd.Parameters.AddWithValue("$o", objectName);
        cmd.Parameters.AddWithValue("$i", inserts);
        cmd.Parameters.AddWithValue("$u", updates);
        cmd.Parameters.AddWithValue("$x", deletes);
        cmd.Parameters.AddWithValue("$t", DateTimeOffset.UtcNow.ToString("O"));
        await cmd.ExecuteNonQueryAsync();

        return (Math.Max(0,inserts-oldI), Math.Max(0,updates-oldU), Math.Max(0,deletes-oldD));
    }
}

public sealed record ObservedEvent(
    DateTimeOffset CapturedAt,
    string EventName,
    string? DatabaseName,
    int? SessionId,
    string? TransactionId,
    string? ClientApp,
    string? ClientHost,
    string? LoginName,
    string SqlText,
    string Fingerprint,
    string? InferredOperation,
    double Confidence,
    string? Evidence);

public sealed record SourceEvidence(
    string SourcePath,
    string Extension,
    DateTime ModifiedUtc,
    long SizeBytes,
    string Sha256,
    string? TableNames,
    string? DmlVerbs);
