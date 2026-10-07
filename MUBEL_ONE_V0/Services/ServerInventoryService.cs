using System.Security.Cryptography;
using System.Text;
using Microsoft.Data.SqlClient;

namespace MubelOne.Services;

/// <summary>
/// SQL Server üzerindeki erişilebilen tüm online kullanıcı databaselerini salt-okunur envantere alır.
/// Veri satırlarını kopyalamaz; şema, kolon, anahtar ve programlanabilir nesne yapısını haritalar.
/// </summary>
public sealed class ServerInventoryService
{
    private readonly string _connectionString;
    private readonly LocalStore _store;
    private readonly Action<string> _log;

    public ServerInventoryService(string connectionString, LocalStore store, Action<string> log)
    {
        _connectionString = connectionString;
        _store = store;
        _log = log;
    }

    public async Task<IReadOnlyList<(int Id, string Name)>> RunAsync(CancellationToken ct)
    {
        var databases = new List<(int, string)>();

        await using var master = new SqlConnection(WithCatalog(_connectionString, "master"));
        await master.OpenAsync(ct);

        var dbcmd = master.CreateCommand();
        dbcmd.CommandText = """
        SELECT database_id,name
        FROM sys.databases
        WHERE state_desc='ONLINE'
          AND database_id > 4
          AND source_database_id IS NULL
        ORDER BY CASE WHEN name=N'YILANCIOGLU' THEN 0 ELSE 1 END,name;
        """;

        await using (var dr = await dbcmd.ExecuteReaderAsync(ct))
        {
            while (await dr.ReadAsync(ct))
                databases.Add((dr.GetInt32(0), dr.GetString(1)));
        }

        foreach (var db in databases)
        {
            ct.ThrowIfCancellationRequested();
            try
            {
                await InventoryDatabaseAsync(db.Item1, db.Item2, ct);
            }
            catch (Exception ex)
            {
                _log($"ENVANTER: {db.Item2} okunamadı ({ex.GetType().Name}).");
            }
        }

        _log($"ENVANTER: {databases.Count} kullanıcı database'i bulundu.");
        return databases;
    }

    private async Task InventoryDatabaseAsync(int databaseId, string databaseName, CancellationToken ct)
    {
        await using var cn = new SqlConnection(WithCatalog(_connectionString, databaseName));
        await cn.OpenAsync(ct);

        await _store.UpsertDatabaseAsync(databaseId, databaseName);

        var cmd = cn.CreateCommand();
        cmd.CommandText = """
        SELECT
            s.name AS schema_name,
            o.name AS object_name,
            o.type_desc,
            ISNULL(CONVERT(nvarchar(max),sm.definition),N'') AS definition,
            ISNULL(pk.name,N'') AS primary_key_name,
            (
              SELECT STRING_AGG(CONCAT(c.name,':',ty.name,':',c.max_length,':',c.is_nullable),';')
              FROM sys.columns c
              JOIN sys.types ty ON ty.user_type_id=c.user_type_id
              WHERE c.object_id=o.object_id
            ) AS columns_signature
        FROM sys.objects o
        JOIN sys.schemas s ON s.schema_id=o.schema_id
        LEFT JOIN sys.sql_modules sm ON sm.object_id=o.object_id
        LEFT JOIN sys.key_constraints pk ON pk.parent_object_id=o.object_id AND pk.type='PK'
        WHERE o.is_ms_shipped=0
          AND o.type IN ('U','V','P','TR','FN','IF','TF')
        ORDER BY o.type_desc,s.name,o.name;
        """;

        int count = 0;
        await using var r = await cmd.ExecuteReaderAsync(ct);
        while (await r.ReadAsync(ct))
        {
            var schema = r.GetString(0);
            var name = r.GetString(1);
            var type = r.GetString(2);
            var definition = r.IsDBNull(3) ? "" : r.GetString(3);
            var pk = r.IsDBNull(4) ? "" : r.GetString(4);
            var cols = r.IsDBNull(5) ? "" : r.GetString(5);

            var structural = $"{databaseName}|{schema}|{name}|{type}|{pk}|{cols}|{SecretRedactor.Sql(definition)}";
            var hash = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(structural)));

            await _store.UpsertObjectAsync(
                databaseName, schema, name, type, pk, cols, hash,
                definition.Length == 0 ? null : SecretRedactor.Sql(definition));
            count++;
        }

        _log($"ENVANTER: {databaseName} -> {count} nesne haritalandı.");
    }

    private static string WithCatalog(string cs, string catalog)
    {
        var b = new SqlConnectionStringBuilder(cs)
        {
            InitialCatalog = catalog,
            ApplicationName = "MUBEL ONE V0 INVENTORY",
            TrustServerCertificate = true
        };
        return b.ConnectionString;
    }
}
