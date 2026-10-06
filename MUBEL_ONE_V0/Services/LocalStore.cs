using Microsoft.Data.Sqlite;

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
        CREATE INDEX IF NOT EXISTS ix_observed_events_time
            ON observed_events(captured_at);
        CREATE INDEX IF NOT EXISTS ix_observed_events_fp
            ON observed_events(fingerprint);

        CREATE TABLE IF NOT EXISTS learned_operations(
            fingerprint TEXT PRIMARY KEY,
            first_seen TEXT NOT NULL,
            last_seen TEXT NOT NULL,
            seen_count INTEGER NOT NULL,
            inferred_operation TEXT NULL,
            confidence REAL NOT NULL DEFAULT 0,
            evidence TEXT NULL
        );
        """;
        await cmd.ExecuteNonQueryAsync();
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
