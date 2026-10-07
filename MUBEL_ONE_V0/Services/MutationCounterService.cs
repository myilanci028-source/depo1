using Microsoft.Data.SqlClient;

namespace MubelOne.Services;

/// <summary>
/// Veri satırlarını değiştirmeden, SQL Server DMV sayaçlarından tablo bazında insert/update/delete
/// hareket miktarlarını takip eder. XE olaylarıyla birlikte kullanıldığında belge zincirini güçlendirir.
/// </summary>
public sealed class MutationCounterService
{
    private readonly string _connectionString;
    private readonly LocalStore _store;
    private readonly Action<string> _log;

    public MutationCounterService(string connectionString, LocalStore store, Action<string> log)
    {
        _connectionString = connectionString;
        _store = store;
        _log = log;
    }

    public async Task SnapshotAsync(string databaseName, int databaseId, CancellationToken ct)
    {
        await using var cn = new SqlConnection(WithCatalog(_connectionString, databaseName));
        await cn.OpenAsync(ct);

        var cmd = cn.CreateCommand();
        cmd.CommandText = """
        SELECT
            s.name,
            t.name,
            SUM(ISNULL(os.leaf_insert_count,0)) AS inserts,
            SUM(ISNULL(os.leaf_update_count,0)) AS updates,
            SUM(ISNULL(os.leaf_delete_count,0)) AS deletes
        FROM sys.tables t
        JOIN sys.schemas s ON s.schema_id=t.schema_id
        OUTER APPLY sys.dm_db_index_operational_stats(DB_ID(),t.object_id,NULL,NULL) os
        WHERE t.is_ms_shipped=0
        GROUP BY s.name,t.name;
        """;

        int changed = 0;
        await using var r = await cmd.ExecuteReaderAsync(ct);
        while (await r.ReadAsync(ct))
        {
            var delta = await _store.UpsertMutationCounterAsync(
                databaseName,
                $"{r.GetString(0)}.{r.GetString(1)}",
                Convert.ToInt64(r.GetValue(2)),
                Convert.ToInt64(r.GetValue(3)),
                Convert.ToInt64(r.GetValue(4)));

            if (delta.InsertDelta != 0 || delta.UpdateDelta != 0 || delta.DeleteDelta != 0)
            {
                changed++;
                _log($"TABLO HAREKETİ: {databaseName}.{r.GetString(0)}.{r.GetString(1)}  " +
                     $"+I:{delta.InsertDelta} +U:{delta.UpdateDelta} +D:{delta.DeleteDelta}");
            }
        }

        if (changed > 0)
            _log($"DMV: {databaseName} içinde {changed} tabloda yeni hareket görüldü.");
    }

    private static string WithCatalog(string cs, string catalog)
    {
        var b = new SqlConnectionStringBuilder(cs)
        {
            InitialCatalog = catalog,
            ApplicationName = "MUBEL ONE V0 COUNTER",
            TrustServerCertificate = true
        };
        return b.ConnectionString;
    }
}
