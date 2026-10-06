using System.Text.RegularExpressions;

namespace MubelOne.Services;

/// <summary>
/// Öğrenme loguna müşteri metni, parola, token veya uzun kimlik değerleri taşımamak için
/// SQL literal değerlerini saklamadan önce temizler. Yapısal tablo/kolon isimleri korunur.
/// </summary>
public static class SecretRedactor
{
    private static readonly Regex Strings = new(
        @"N?'(?:''|[^'])*'",
        RegexOptions.Compiled | RegexOptions.CultureInvariant);

    private static readonly Regex Hex = new(
        @"\b0x[0-9A-Fa-f]{8,}\b",
        RegexOptions.Compiled | RegexOptions.CultureInvariant);

    private static readonly Regex SensitiveAssignments = new(
        @"(?ix)\b(password|passwd|pwd|apikey|api_key|apisecret|api_secret|token|refreshtoken|secret)\b\s*=\s*([^,;\r\n]+)",
        RegexOptions.Compiled | RegexOptions.CultureInvariant);

    public static string Sql(string sql)
    {
        if (string.IsNullOrWhiteSpace(sql)) return string.Empty;

        var clean = SensitiveAssignments.Replace(sql, "$1=[REDACTED]");
        clean = Strings.Replace(clean, "'[STR]'");
        clean = Hex.Replace(clean, "0x[HEX]");

        // Log şişmesini ve olası toplu değer sızıntısını engelle.
        if (clean.Length > 32_000)
            clean = clean[..32_000] + " /* TRUNCATED */";

        return clean;
    }
}
