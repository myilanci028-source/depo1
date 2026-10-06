namespace MubelOne.Services;

public sealed class BootstrapService
{
    private readonly Action<string> _log;

    public BootstrapService(Action<string> log) => _log = log;

    public async Task RunAsync(CancellationToken ct)
    {
        var store = new LocalStore();
        await store.InitializeAsync();
        _log("Yerel öğrenme deposu hazır.");
        _log($"Seed harita: {VegaSeedCatalog.SanalMagazaTables.Length} SBH/Sanal Mağaza tablosu.");
        _log($"Seed harita: {VegaSeedCatalog.BelgeHeaderByIzahat.Count} BELGEIZAHAT → başlık eşleşmesi.");

        // Eski çalışmalar kullanıcının hiçbir dosyasını değiştirmeden yalnız indekslenir.
        var harvester = new SourceHarvester(store, _log);
        await harvester.RunAsync(ct);

        var discovery = new DiscoveryService(_log);
        var candidates = discovery.FindConnectionCandidates();
        _log($"Otomatik bağlantı adayı: {candidates.Count}");

        var result = await discovery.TryDiscoverAsync(candidates, ct);
        if (result is null)
        {
            _log("YSERVER otomatik bağlantısı henüz kurulamadı.");
            _log("Hiçbir server verisi değiştirilmedi. Yerel kaynak haritası kullanılabilir durumda.");
            return;
        }

        var (snapshot, connectionString) = result.Value;
        _log($"SQL: {snapshot.ServerName}  {snapshot.ProductVersion}  {snapshot.Edition}");
        _log($"Database sayısı: {snapshot.Databases.Count}");
        _log($"YILANCIOGLU tablo sayısı (görülebilen): {snapshot.YilanciogluTables.Count}");

        var periods = snapshot.YilanciogluTables
            .Where(x => x.StartsWith("F0103D", StringComparison.OrdinalIgnoreCase))
            .Select(x => x.Length >= 10 ? x[..10] : x)
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .OrderBy(x => x)
            .ToArray();

        if (periods.Length > 0)
            _log("Bulunan firma/dönem önekleri: " + string.Join(", ", periods));

        var sbhFound = snapshot.YilanciogluTables
            .Where(x => x.StartsWith("SBH103", StringComparison.OrdinalIgnoreCase))
            .ToArray();
        if (sbhFound.Length > 0)
            _log($"Vega Sanal Mağaza / SBH yüzeyi: {sbhFound.Length} tablo canlı şemada bulundu.");

        if (snapshot.YilanciogluDatabaseId is null)
        {
            _log("YILANCIOGLU DB_ID alınamadı; öğrenme oturumu açılmadı.");
            return;
        }

        var learner = new XeLearningService(
            connectionString,
            snapshot.YilanciogluDatabaseId.Value,
            store,
            _log);

        await learner.RunAsync(ct);
    }
}
