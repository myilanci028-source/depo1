namespace MubelOne.Models;

public sealed record DiscoverySnapshot(
    string ServerName,
    string ProductVersion,
    string Edition,
    IReadOnlyList<string> Databases,
    IReadOnlyList<string> YilanciogluTables,
    int? YilanciogluDatabaseId);
