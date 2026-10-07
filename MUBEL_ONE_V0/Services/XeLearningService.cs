using System.Xml.Linq;
using Microsoft.Data.SqlClient;

namespace MubelOne.Services;

public sealed class XeLearningService
{
    private const string SessionName = "MUBEL_ONE_LEARN";
    private readonly string _connectionString;
    private readonly IReadOnlyDictionary<int,string> _databases;
    private readonly LocalStore _store;
    private readonly Action<string> _log;

    public XeLearningService(
        string connectionString,
        IReadOnlyDictionary<int,string> databases,
        LocalStore store,
        Action<string> log)
    {
        _connectionString = connectionString;
        _databases = databases;
        _store = store;
        _log = log;
    }

    public async Task RunAsync(CancellationToken ct)
    {
        if (_databases.Count == 0)
        {
            _log("ÖĞRENME: izlenecek kullanıcı database'i bulunamadı.");
            return;
        }

        try
        {
            await CreateEphemeralSessionAsync(ct);
            _log($"ÖĞRENME: geçici Extended Events oturumu başladı; {_databases.Count} kullanıcı database'i izleniyor.");
            _log("ÖĞRENME: YILANCIOGLU veya diğer databaselere tablo/trigger/kolon eklenmedi.");

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

        var predicate = string.Join(" OR ",
            _databases.Keys.OrderBy(x => x).Select(id => $"[sqlserver].[database_id]=({id})"));

        var cmd = cn.CreateCommand();
        cmd.CommandText = $"""
        IF EXISTS (SELECT 1 FROM sys.server_event_sessions WHERE name=N'{SessionName}')
            DROP EVENT SESSION [{SessionName}] ON SERVER;

        CREATE EVENT SESSION [{SessionName}] ON SERVER
        ADD EVENT sqlserver.rpc_completed(
            ACTION(sqlserver.client_app_name,sqlserver.client_hostname,sqlserver.database_id,
                   sqlserver.server_principal_name,sqlserver.session_id,sqlserver.sql_text,
                   sqlserver.transaction_id)
            WHERE ({predicate})),
        ADD EVENT sqlserver.sql_statement_completed(
            ACTION(sqlserver.client_app_name,sqlserver.client_hostname,sqlserver.database_id,
                   sqlserver.server_principal_name,sqlserver.session_id,sqlserver.sql_text,
                   sqlserver.transaction_id)
            WHERE ({predicate})),
        ADD EVENT sqlserver.sp_statement_completed(
            ACTION(sqlserver.client_app_name,sqlserver.client_hostname,sqlserver.database_id,
                   sqlserver.server_principal_name,sqlserver.session_id,sqlserver.sql_text,
                   sqlserver.transaction_id)
            WHERE ({predicate}))
        ADD TARGET package0.ring_buffer(SET max_events_limit=(10000),max_memory=(16384))
        WITH (MAX_MEMORY=8192 KB, EVENT_RETENTION_MODE=ALLOW_SINGLE_EVENT_LOSS,
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
                  upper.Contains("SP_EXECUTESQL") || upper.Contains("SP_EXECUTE"))) continue;

            var dbId = int.TryParse(Action("database_id"), out var di) ? di : 0;
            var databaseName = _databases.TryGetValue(dbId, out var dn) ? dn : $"DBID_{dbId}";

            var unique = $"{eventName}|{stamp:O}|{Action("session_id")}|{databaseName}|{sql}";
            if (!_seen.Add(unique)) continue;
            if (_seen.Count > 50000) _seen.Clear();

            // Ham cari, müşteri, token, parola ve literal değerler yerel öğrenme DB'sine alınmaz.
            var safeSql = SecretRedactor.Sql(sql);
            var a = OperationClassifier.Analyze(safeSql);
            int? sid = int.TryParse(Action("session_id"), out var si) ? si : null;
            var transactionId = Action("transaction_id");

            await _store.SaveEventAsync(new ObservedEvent(
                stamp, eventName, databaseName, sid, transactionId,
                app, Action("client_hostname"), Action("server_principal_name"),
                safeSql, a.Fingerprint, a.Operation, a.Confidence, a.Evidence));

            var edges = SqlLineageAnalyzer.Analyze(
                databaseName, safeSql, transactionId, a.Fingerprint, stamp,
                "A", "YSERVER Extended Events");
            if (edges.Count > 0)
                await _store.SaveLineageAsync(edges);

            var priority = databaseName.Equals("YILANCIOGLU", StringComparison.OrdinalIgnoreCase) ? "★ " : "";
            _log($"{priority}ÖĞRENİLDİ: {databaseName} / {a.Operation ?? "Yeni işlem deseni"} [{a.Fingerprint}] {a.Evidence}");
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
        var b = new SqlConnectionStringBuilder(cs)
        {
            InitialCatalog = "master",
            ApplicationName = "MUBEL ONE V0",
            TrustServerCertificate = true
        };
        return b.ConnectionString;
    }
}
