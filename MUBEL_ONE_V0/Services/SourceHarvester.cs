using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;

namespace MubelOne.Services;

/// <summary>
/// Eski LINQPad/SQL/BAT/ayar çalışmalarını değiştirmeden tarar.
/// Ham içerik veya parola kopyalamaz; yalnız dosya özeti + tablo/işlem kanıtı çıkarır.
/// </summary>
public sealed class SourceHarvester
{
    private readonly LocalStore _store;
    private readonly Action<string> _log;

    private static readonly string[] Roots =
    {
        @"E:\muharrem",
        @"E:\muharrem\BANKAAKTARIM",
        @"E:\muharrem\BANKAAKTARIM\portalaktarim",
        @"C:\TempLINQPad",
        @"C:\mubelbank",
        @"H:\MUBELBANK_YEDEKLER",
        @"D:\Arctos"
    };

    private static readonly HashSet<string> Extensions = new(StringComparer.OrdinalIgnoreCase)
    {
        ".linq",".cs",".txt",".sql",".ps1",".bat",".cmd",".config",".xml",".json",".log"
    };

    private static readonly Regex TableRegex = new(
        @"(?i)\b(?:F\d{4}(?:D\d{4})?|SBH\d+)?TBL[A-Z0-9_]+\b|\bSBH\d+[A-Z0-9_]+\b",
        RegexOptions.Compiled | RegexOptions.CultureInvariant);

    private static readonly Regex DmlRegex = new(
        @"(?i)\b(INSERT\s+INTO|UPDATE|DELETE\s+FROM|MERGE\s+INTO|EXEC(?:UTE)?)\b",
        RegexOptions.Compiled | RegexOptions.CultureInvariant);

    public SourceHarvester(LocalStore store, Action<string> log)
    {
        _store = store;
        _log = log;
    }

    public async Task RunAsync(CancellationToken ct)
    {
        int files = 0, evidence = 0;
        var roots = Roots
            .Concat(new[]
            {
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments), "LINQPad Queries"),
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments), "LINQPad Scripts"),
                Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory)
            })
            .Where(Directory.Exists)
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToArray();

        foreach (var root in roots)
        {
            await foreach (var file in EnumerateSafeAsync(root, ct))
            {
                files++;
                try
                {
                    var fi = new FileInfo(file);
                    if (fi.Length > 20 * 1024 * 1024) continue;

                    var text = await File.ReadAllTextAsync(file, ct);
                    var tables = TableRegex.Matches(text)
                        .Select(m => m.Value.ToUpperInvariant())
                        .Distinct()
                        .OrderBy(x => x)
                        .Take(500)
                        .ToArray();

                    var verbs = DmlRegex.Matches(text)
                        .Select(m => Regex.Replace(m.Value.ToUpperInvariant(), @"\s+", " "))
                        .Distinct()
                        .ToArray();

                    var important = tables.Length > 0 ||
                                    text.Contains("yenibelgeaktarim", StringComparison.OrdinalIgnoreCase) ||
                                    text.Contains("VegaWebService", StringComparison.OrdinalIgnoreCase) ||
                                    text.Contains("VEGABOS_YENI", StringComparison.OrdinalIgnoreCase);

                    if (!important) continue;

                    var hash = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(text)));
                    await _store.SaveSourceEvidenceAsync(new SourceEvidence(
                        file,
                        fi.Extension,
                        fi.LastWriteTimeUtc,
                        fi.Length,
                        hash,
                        string.Join(",", tables),
                        string.Join(",", verbs)));

                    // Belge.sql, LINQPad ve eski aktarım kodlarından kaynak -> hedef soy ağacı çıkar.
                    // Ham literal değerler hiçbir zaman saklanmaz.
                    var dbName = InferDatabaseName(text);
                    foreach (var statement in SplitStatements(text).Take(5000))
                    {
                        if (!DmlRegex.IsMatch(statement)) continue;

                        var safeStatement = SecretRedactor.Sql(statement);
                        var analyzed = OperationClassifier.Analyze(safeStatement);
                        var edges = SqlLineageAnalyzer.Analyze(
                            dbName,
                            safeStatement,
                            null,
                            analyzed.Fingerprint,
                            new DateTimeOffset(fi.LastWriteTimeUtc, TimeSpan.Zero),
                            "D",
                            $"Yerel kaynak: {Path.GetFileName(file)}");

                        if (edges.Count > 0)
                            await _store.SaveLineageAsync(edges);
                    }

                    evidence++;
                }
                catch { /* tarayıcı hiçbir kaynak dosyasını değiştirmez; hatalı dosyayı atlar */ }
            }
        }

        _log($"KAYNAK HASADI: {files} dosya görüldü, {evidence} Vega kanıt kaynağı indekslendi.");
    }

    private static string InferDatabaseName(string text)
    {
        var m = Regex.Match(text, @"(?im)^\s*USE\s+\[?(?<db>[A-Z0-9_\-]+)\]?\s*$");
        return m.Success ? m.Groups["db"].Value : "YILANCIOGLU";
    }

    private static IEnumerable<string> SplitStatements(string text)
    {
        foreach (var chunk in Regex.Split(text, @"(?im)^\s*GO\s*$"))
        {
            foreach (var statement in chunk.Split(';'))
            {
                var s = statement.Trim();
                if (s.Length == 0) continue;
                if (s.Length > 100_000) s = s[..100_000];
                yield return s;
            }
        }
    }

    private static async IAsyncEnumerable<string> EnumerateSafeAsync(
        string root,
        [System.Runtime.CompilerServices.EnumeratorCancellation] CancellationToken ct)
    {
        var stack = new Stack<string>();
        stack.Push(root);

        while (stack.Count > 0)
        {
            ct.ThrowIfCancellationRequested();
            var dir = stack.Pop();

            if (dir.Contains("\\node_modules\\", StringComparison.OrdinalIgnoreCase) ||
                dir.Contains("\\.git\\", StringComparison.OrdinalIgnoreCase) ||
                dir.Contains("\\Windows\\", StringComparison.OrdinalIgnoreCase))
                continue;

            string[] subdirs;
            string[] files;
            try
            {
                subdirs = Directory.GetDirectories(dir);
                files = Directory.GetFiles(dir);
            }
            catch
            {
                subdirs = Array.Empty<string>();
                files = Array.Empty<string>();
            }

            foreach (var sub in subdirs)
                stack.Push(sub);

            foreach (var file in files)
                if (Extensions.Contains(Path.GetExtension(file)))
                    yield return file;

            await Task.Yield();
        }
    }
}
