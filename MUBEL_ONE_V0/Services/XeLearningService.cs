using System.Xml.Linq;
using Microsoft.Data.SqlClient;

namespace MubelOne.Services;

public sealed class XeLearningService
{
    private const string SessionName = "MUBEL_ONE_LEARN";
    private readonly string _connectionString;
    private readonly int _databaseId;
    private readonly LocalStore _store;
    private readonly Action<string> _log;

    public XeLearningService(string connectionString, int databaseId, LocalStore store, Action<string> log)
    {
        _connectionString = connectionString;
        _databaseId = databaseId;
        _store = store;
        _log = log;
    }

    public async Task RunAsync(CancellationToken ct)
    {
        try
        {
            await CreateEphemeralSessionAsync(ct);
            _log("ÖĞRENME: geçici Extended Events oturumu başladı (STARTUP_STATE=OFF, ring_buffer).");
            _log("ÖĞRENME: YILANCIOGLU verisine tablo/trigger/kolon eklenmedi.");

            while (!ct.IsCancellationRequested)
            {
                await ReadRingBufferAsync(ct);
                await Task.Delay(TimeSpan.FromSeconds(2), ct);
            }
        }
        catch (OperationCanceledException) { }
        catch (Exception ex)
        {
            _log($"ÖĞRENME pasif kaldı: {ex.Message}");
        }
        finally
        {
            try { await DropSessionAsync(CancellationToken.None); } catch { }
        }
    }

    private async Task CreateEphemeralSessionAsync(CancellationToken ct)
    {
        await using var cn = new SqlConnection(WithMaster(_connectionString));
        await cn.OpenAsync(ct);

        var cmd = cn.CreateCommand();
        cmd.CommandText = $"""
        IF EXISTS (SELECT 1 FROM sys.server_event_sessions WHERE name=N'{SessionName}')
            DROP EVENT SESSION [{SessionName}] ON SERVER;

        CREATE EVENT SESSION [{SessionName}] ON SERVER
        ADD EVENT sqlserver.rpc_completed(
            ACTION(sqlserver.client_app_name,sqlserver.client_hostname,sqlserver.database_id,
                   sqlserver.server_principal_name,sqlserver.session_id,sqlserver.sql_text,
                   sqlserver.transaction_id)
            WHERE ([sqlserver].[database_id]=({_databaseId}))),
        ADD EVENT sqlserver.sql_statement_completed(
            ACTION(sqlserver.client_app_name,sqlserver.client_hostname,sqlserver.database_id,
                   sqlserver.server_principal_name,sqlserver.session_id,sqlserver.sql_text,
                   sqlserver.transaction_id)
            WHERE ([sqlserver].[database_id]=({_databaseId}))),
        ADD EVENT sqlserver.sp_statement_completed(
            ACTION(sqlserver.client_app_name,sqlserver.client_hostname,sqlserver.database_id,
                   sqlserver.server_principal_name,sqlserver.session_id,sqlserver.sql_text,
                   sqlserver.transaction_id)
            WHERE ([sqlserver].[database_id]=({_databaseId})))
        ADD TARGET package0.ring_buffer(SET max_events_limit=(5000),max_memory=(8192))
        WITH (MAX_MEMORY=4096 KB, EVENT_RETENTION_MODE=ALLOW_SINGLE_EVENT_LOSS,
              MAX_DISPATCH_LATENCY=1 SECONDS, TRACK_CAUSALITY=ON, STARTUP_STATE=OFF);

        ALTER EVENT SESSION [{SessionName}] ON SERVER STATE=START;
        """;
        await cmd.ExecuteNonQueryAsync(ct);
    }

    private readonly HashSet<string> _seen = new(StringComparer.Ordinal);

    private async Task ReadRingBufferAsync(CancellationToken ct)
    {
        await using var cn = new SqlConnection(WithMaster(_connectionString));
        await cn.OpenAsync(ct);

        var cmd = cn.CreateCommand();
        cmd.CommandText = $"""
        SELECT CAST(t.target_data AS xml)
        FROM sys.dm_xe_session_targets t
        JOIN sys.dm_xe_sessions s ON s.address=t.event_session_address
        WHERE s.name=N'{SessionName}' AND t.target_name=N'ring_buffer';
        """;

        var value = await cmd.ExecuteScalarAsync(ct);
        if (value is null || value == DBNull.Value) return;

        var doc = XDocument.Parse(Convert.ToString(value)!);
        foreach (var ev in doc.Descendants("event"))
        {
            var eventName = (string?)ev.Attribute("name") ?? "unknown";
            var stamp = DateTimeOffset.TryParse((string?)ev.Attribute("timestamp"), out var dto)
                ? dto : DateTimeOffset.UtcNow;

            string? Action(string name) => ev.Elements("action")
                .FirstOrDefault(x => string.Equals((string?)x.Attribute("name"), name, StringComparison.OrdinalIgnoreCase))
                ?.Element("value")?.Value;

            string? Data(string name) => ev.Elements("data")
                .FirstOrDefault(x => string.Equals((string?)x.Attribute("name"), name, StringComparison.OrdinalIgnoreCase))
                ?.Element("value")?.Value;

            var sql = Action("sql_text") ?? Data("statement") ?? Data("batch_text") ?? "";
            if (string.IsNullOrWhiteSpace(sql)) continue;

            var app = Action("client_app_name");
            if (app?.StartsWith("MUBEL ONE", StringComparison.OrdinalIgnoreCase) == true) continue;

            var upper = sql.TrimStart().ToUpperInvariant();
            if (!(upper.StartsWith("INSERT ") || upper.StartsWith("UPDATE ") || upper.StartsWith("DELETE ") ||
                  upper.StartsWith("MERGE ") || upper.StartsWith("EXEC ") || upper.StartsWith("EXECUTE ") ||
                  upper.Contains("SP_EXECUTESQL"))) continue;

            var unique = $"{eventName}|{stamp:O}|{Action("session_id")}|{sql}";
            if (!_seen.Add(unique)) continue;
            if (_seen.Count > 20000) _seen.Clear();

            var a = OperationClassifier.Analyze(sql);
            int? sid = int.TryParse(Action("session_id"), out var si) ? si : null;

            await _store.SaveEventAsync(new ObservedEvent(
                stamp, eventName, "YILANCIOGLU", sid, Action("transaction_id"),
                app, Action("client_hostname"), Action("server_principal_name"),
                sql, a.Fingerprint, a.Operation, a.Confidence, a.Evidence));

            _log($"ÖĞRENİLDİ: {a.Operation ?? "Yeni işlem deseni"} [{a.Fingerprint}]  {a.Evidence}");
        }
    }

    private async Task DropSessionAsync(CancellationToken ct)
    {
        await using var cn = new SqlConnection(WithMaster(_connectionString));
        await cn.OpenAsync(ct);
        var cmd = cn.CreateCommand();
        cmd.CommandText = $"""
        IF EXISTS (SELECT 1 FROM sys.dm_xe_sessions WHERE name=N'{SessionName}')
            ALTER EVENT SESSION [{SessionName}] ON SERVER STATE=STOP;
        IF EXISTS (SELECT 1 FROM sys.server_event_sessions WHERE name=N'{SessionName}')
            DROP EVENT SESSION [{SessionName}] ON SERVER;
        """;
        await cmd.ExecuteNonQueryAsync(ct);
    }

    private static string WithMaster(string cs)
    {
        var b = new SqlConnectionStringBuilder(cs) { InitialCatalog = "master", ApplicationName = "MUBEL ONE V0" };
        return b.ConnectionString;
    }
}
