using System.Text.RegularExpressions;
using MubelOne.Models;

namespace MubelOne.Services;

/// <summary>
/// SQL metninden kaynak -> hedef tablo soy ağacını çıkarır.
/// Tam SQL parser değildir; güven puanlı kanıt üretir ve canlı XE + kaynak kod kanıtlarıyla birleşir.
/// </summary>
public static class SqlLineageAnalyzer
{
    private static readonly Regex QualifiedTable = new(
        @"(?ix)
        (?:
            \[(?<db>[^\]]+)\]\s*\.\s*
        )?
        (?:
            \[(?<schema>[^\]]+)\]\s*\.\s*
        )?
        \[?(?<table>(?:F\d{4}(?:D\d{4})?)?TBL[A-Z0-9_]+|SBH\d+[A-Z0-9_]+)\]?",
        RegexOptions.Compiled | RegexOptions.CultureInvariant);

    private static readonly Regex InsertTarget = new(
        @"(?ix)\bINSERT\s+(?:TOP\s*\([^\)]*\)\s+)?INTO\s+(?<obj>(?:\[[^\]]+\]\s*\.\s*){0,2}\[?[A-Z0-9_]+\]?)",
        RegexOptions.Compiled | RegexOptions.CultureInvariant);

    private static readonly Regex UpdateTarget = new(
        @"(?ix)\bUPDATE\s+(?<obj>(?:\[[^\]]+\]\s*\.\s*){0,2}\[?[A-Z0-9_]+\]?)",
        RegexOptions.Compiled | RegexOptions.CultureInvariant);

    private static readonly Regex DeleteTarget = new(
        @"(?ix)\bDELETE\s+(?:FROM\s+)?(?<obj>(?:\[[^\]]+\]\s*\.\s*){0,2}\[?[A-Z0-9_]+\]?)",
        RegexOptions.Compiled | RegexOptions.CultureInvariant);

    private static readonly Regex MergeTarget = new(
        @"(?ix)\bMERGE\s+(?:INTO\s+)?(?<obj>(?:\[[^\]]+\]\s*\.\s*){0,2}\[?[A-Z0-9_]+\]?)",
        RegexOptions.Compiled | RegexOptions.CultureInvariant);

    private static readonly Regex Sources = new(
        @"(?ix)\b(?:FROM|JOIN|USING)\s+(?<obj>(?:\[[^\]]+\]\s*\.\s*){0,2}\[?[A-Z0-9_]+\]?)",
        RegexOptions.Compiled | RegexOptions.CultureInvariant);

    public static IReadOnlyList<LineageEdge> Analyze(
        string databaseName,
        string sql,
        string? transactionId,
        string fingerprint,
        DateTimeOffset observedAt,
        string evidenceLevel,
        string evidenceSource)
    {
        var target = MatchTarget(sql, out var operation);
        if (target is null) return Array.Empty<LineageEdge>();

        var targetCanonical = Canonical(target, databaseName);
        var sources = Sources.Matches(sql)
            .Select(m => Canonical(m.Groups["obj"].Value, databaseName))
            .Where(x => !string.IsNullOrWhiteSpace(x))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .Where(x => !x.Equals(targetCanonical, StringComparison.OrdinalIgnoreCase))
            .ToArray();

        if (sources.Length == 0)
        {
            return new[]
            {
                new LineageEdge(databaseName, transactionId, fingerprint, operation,
                    targetCanonical, "(direct-values-or-procedure)", observedAt,
                    evidenceLevel, evidenceSource)
            };
        }

        return sources.Select(source =>
            new LineageEdge(databaseName, transactionId, fingerprint, operation,
                targetCanonical, source, observedAt, evidenceLevel, evidenceSource)).ToArray();
    }

    private static string? MatchTarget(string sql, out string operation)
    {
        foreach (var p in new[]
        {
            (InsertTarget, "INSERT"),
            (UpdateTarget, "UPDATE"),
            (DeleteTarget, "DELETE"),
            (MergeTarget, "MERGE")
        })
        {
            var m = p.Item1.Match(sql);
            if (m.Success)
            {
                operation = p.Item2;
                return m.Groups["obj"].Value;
            }
        }

        operation = "EXEC";
        return null;
    }

    private static string Canonical(string raw, string defaultDb)
    {
        var clean = Regex.Replace(raw, @"[\[\]\s]", "");
        var parts = clean.Split('.', StringSplitOptions.RemoveEmptyEntries);

        if (parts.Length == 1) return $"{defaultDb}.dbo.{parts[0].ToUpperInvariant()}";
        if (parts.Length == 2) return $"{defaultDb}.{parts[0]}.{parts[1]}".ToUpperInvariant();
        return $"{parts[^3]}.{parts[^2]}.{parts[^1]}".ToUpperInvariant();
    }
}
