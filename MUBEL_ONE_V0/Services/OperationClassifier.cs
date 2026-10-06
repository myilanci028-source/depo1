using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;

namespace MubelOne.Services;

public static class OperationClassifier
{
    public static (string Fingerprint, string? Operation, double Confidence, string Evidence) Analyze(string sql)
    {
        var normalized = Regex.Replace(sql.ToUpperInvariant(), @"\s+", " ").Trim();
        var tables = Regex.Matches(normalized, @"\b(?:INTO|UPDATE|FROM|JOIN)\s+\[?(?:DBO\]\.)?\[?([A-Z0-9_]+)")
            .Select(m => m.Groups[1].Value)
            .Distinct()
            .OrderBy(x => x)
            .ToArray();

        var signature = string.Join("|", tables) + "|" +
                        Regex.Replace(normalized, @"'[^']*'|\b\d+(?:\.\d+)?\b", "?");
        var fp = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(signature)))[..24];

        var evidence = string.Join(", ", tables);
        var op = Infer(tables, normalized, out var confidence);
        return (fp, op, confidence, evidence);
    }

    private static string? Infer(string[] tables, string sql, out double confidence)
    {
        confidence = 0.35;
        bool Has(string s) => tables.Any(t => t.Contains(s, StringComparison.OrdinalIgnoreCase));

        if (Has("SATFATBASLIK") || Has("SATFATHAREKET"))
        { confidence = 0.90; return "Satış Faturası"; }

        if (Has("ALFATBASLIK") || Has("ALFATHAREKET"))
        { confidence = 0.90; return "Alış Faturası"; }

        if (Has("ALSIPBASLIK") || Has("ALSIPHAREKET") || Has("ALSIPLIST"))
        { confidence = 0.88; return "Alış Siparişi"; }

        if (Has("VERSIP"))
        { confidence = 0.88; return "Satış Siparişi"; }

        if (Has("STKCIKBASLIK") || Has("STKCIKHAREKET"))
        { confidence = 0.85; return "Stok Çıkış Belgesi"; }

        if (Has("STKGIRBASLIK") || Has("STKGIRHAREKET"))
        { confidence = 0.85; return "Stok Giriş Belgesi"; }

        if (Has("DEPOHARBASLIK") || Has("DEPOHARHAREKET"))
        { confidence = 0.90; return "Depo Transferi"; }

        if (Has("BANK") || Has("BNKVISA") || Has("EFTHAREKET"))
        { confidence = 0.75; return "Banka / POS Hareketi"; }

        if (Has("KASA"))
        { confidence = 0.70; return "Kasa Hareketi"; }

        if (Has("CEK"))
        { confidence = 0.78; return "Çek İşlemi"; }

        if (Has("SENET"))
        { confidence = 0.78; return "Senet İşlemi"; }

        if (sql.Contains("DELETE ")) { confidence = 0.55; return "Silme / İptal İşlemi"; }
        return null;
    }
}
