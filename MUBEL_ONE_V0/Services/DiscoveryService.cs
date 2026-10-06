using System.Text.RegularExpressions;
using Microsoft.Data.SqlClient;
using MubelOne.Models;

namespace MubelOne.Services;

public sealed class DiscoveryService
{
    private readonly Action<string> _log;

    public DiscoveryService(Action<string> log) => _log = log;

    public IReadOnlyList<string> FindConnectionCandidates()
    {
        var roots = new[]
        {
            @"D:\Arctos",
            @"C:\Arctos",
            @"E:\muharrem\BANKAAKTARIM",
            @"C:\mubelbank"
        };

        var found = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

        foreach (var root in roots.Where(Directory.Exists))
        {
            _log($"Yerel kaynak bulundu: {root}");
            foreach (var file in SafeEnumerate(root).Take(300))
            {
                try
                {
                    var text = File.ReadAllText(file);
                    foreach (var candidate in ExtractConnectionStrings(text))
                        found.Add(candidate);
                }
                catch { }
            }
        }

        // Son çare: yalnız Windows kimlik doğrulamasıyla bilinen sunucular denenir.
        found.Add("Server=YSERVER;Database=YILANCIOGLU;Integrated Security=True;TrustServerCertificate=True;Connect Timeout=2");
        found.Add("Server=192.168.1.250;Database=YILANCIOGLU;Integrated Security=True;TrustServerCertificate=True;Connect Timeout=2");

        return found.ToList();
    }

    public async Task<(DiscoverySnapshot Snapshot, string ConnectionString)?> TryDiscoverAsync(
        IEnumerable<string> candidates,
        CancellationToken ct)
    {
        foreach (var raw in candidates)
        {
            var cs = Normalize(raw);
            if (cs is null) continue;

            try
            {
                await using var cn = new SqlConnection(cs);
                await cn.OpenAsync(ct);

                var info = cn.CreateCommand();
                info.CommandText = """
                SELECT
                    CAST(@@SERVERNAME AS nvarchar(256)),
                    CAST(SERVERPROPERTY('ProductVersion') AS nvarchar(128)),
                    CAST(SERVERPROPERTY('Edition') AS nvarchar(256));
                """;
                await using var r = await info.ExecuteReaderAsync(ct);
                await r.ReadAsync(ct);
                var server = r.GetString(0);
                var version = r.GetString(1);
                var edition = r.GetString(2);
                await r.CloseAsync();

                var dbs = new List<string>();
                var dbcmd = cn.CreateCommand();
                dbcmd.CommandText = "SELECT name FROM sys.databases WHERE state_desc='ONLINE' ORDER BY name";
                await using (var dr = await dbcmd.ExecuteReaderAsync(ct))
                    while (await dr.ReadAsync(ct)) dbs.Add(dr.GetString(0));

                int? dbid = null;
                var tables = new List<string>();
                if (dbs.Any(x => x.Equals("YILANCIOGLU", StringComparison.OrdinalIgnoreCase)))
                {
                    var tcmd = cn.CreateCommand();
                    tcmd.CommandText = """
                    SELECT DB_ID('YILANCIOGLU');
                    SELECT TOP (5000) name
                    FROM YILANCIOGLU.sys.tables
                    WHERE is_ms_shipped=0
                    ORDER BY name;
                    """;
                    var scalar = await tcmd.ExecuteScalarAsync(ct);
                    if (scalar is not null && scalar != DBNull.Value) dbid = Convert.ToInt32(scalar);

                    // Ayrı komut: ilk ExecuteScalar ikinci result seti tüketmez.
                    var tcmd2 = cn.CreateCommand();
                    tcmd2.CommandText = """
                    SELECT TOP (5000) name
                    FROM YILANCIOGLU.sys.tables
                    WHERE is_ms_shipped=0
                    ORDER BY name;
                    """;
                    await using var tr = await tcmd2.ExecuteReaderAsync(ct);
                    while (await tr.ReadAsync(ct)) tables.Add(tr.GetString(0));
                }

                return (new DiscoverySnapshot(server, version, edition, dbs, tables, dbid), cs);
            }
            catch (Exception ex)
            {
                _log($"Bağlantı adayı geçmedi: {SafeServerName(cs)} ({ex.GetType().Name})");
            }
        }

        return null;
    }

    private static IEnumerable<string> SafeEnumerate(string root)
    {
        var names = new[] { "*.udl", "*.ini", "*.config", "*.xml", "*.json" };
        foreach (var pattern in names)
        {
            IEnumerable<string> files;
            try { files = Directory.EnumerateFiles(root, pattern, SearchOption.AllDirectories); }
            catch { continue; }

            foreach (var file in files)
            {
                if (file.Contains("\\node_modules\\", StringComparison.OrdinalIgnoreCase)) continue;
                yield return file;
            }
        }
    }

    private static IEnumerable<string> ExtractConnectionStrings(string text)
    {
        foreach (Match m in Regex.Matches(
                     text,
                     @"(?i)(?:Provider=[^;]+;)?(?:Data Source|Server)\s*=\s*[^\r\n""]+;[^\r\n""]*(?:Initial Catalog|Database)\s*=\s*[^\r\n"";]+[^\r\n""]*"))
        {
            yield return m.Value.Trim();
        }

        foreach (Match m in Regex.Matches(text, @"(?i)connectionString\s*=\s*""([^""]+)"""))
            yield return m.Groups[1].Value;
    }

    private static string? Normalize(string raw)
    {
        try
        {
            var cleaned = Regex.Replace(raw, @"(?i)^\s*Provider\s*=\s*[^;]+;\s*", "");
            var b = new SqlConnectionStringBuilder(cleaned)
            {
                ApplicationName = "MUBEL ONE V0",
                ConnectTimeout = Math.Min(5, Math.Max(2, new SqlConnectionStringBuilder(cleaned).ConnectTimeout)),
                TrustServerCertificate = true
            };
            return b.ConnectionString;
        }
        catch { return null; }
    }

    private static string SafeServerName(string cs)
    {
        try { return new SqlConnectionStringBuilder(cs).DataSource; }
        catch { return "bilinmeyen"; }
    }
}
