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

        // 1) Eski LINQ/SQL/BAT/Arctos/Belge.sql çalışmalarını değiştirmeden indeksle.
        var harvester = new SourceHarvester(store, _log);
        await harvester.RunAsync(ct);

        // 2) Mevcut Arctos/config bağlantılarından YSERVER/192.168.1.250 dahil erişilebilir SQL'i bul.
        var discovery = new DiscoveryService(_log);
        var candidates = discovery.FindConnectionCandidates();
        _log($"Otomatik bağlantı adayı: {candidates.Count}");

        var result = await discovery.TryDiscoverAsync(candidates, ct);
        if (result is null)
        {
            _log("SQL Server otomatik bağlantısı henüz kurulamadı.");
            _log("Hiçbir server verisi değiştirilmedi. Yerel kaynak haritası kullanılabilir durumda.");
            return;
        }

        var (snapshot, connectionString) = result.Value;
        _log($"SQL: {snapshot.ServerName}  {snapshot.ProductVersion}  {snapshot.Edition}");
        _log($"Görülebilen database sayısı: {snapshot.Databases.Count}");
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

        // 3) Tüm erişilebilir kullanıcı databaselerini ve programlanabilir nesneleri salt-okunur haritala.
        var inventory = new ServerInventoryService(connectionString, store, _log);
        var dbs = await inventory.RunAsync(ct);
        var dbMap = dbs.ToDictionary(x => x.Id, x => x.Name);

        if (dbMap.Count == 0)
        {
            _log("Kullanıcı database envanteri boş; canlı öğrenme açılmadı.");
            return;
        }

        // 4) Canlı SQL olayları + kaynak/hedef tablo soy ağacı.
        var learner = new XeLearningService(connectionString, dbMap, store, _log);

        // 5) DMV tablo hareket sayaçları ile INSERT/UPDATE/DELETE etkisini bağımsız doğrula.
        // YILANCIOGLU sık, diğer databaseler daha seyrek kontrol edilir; server yükü sınırlı tutulur.
        var counterTask = RunMutationCountersAsync(connectionString, dbs, store, ct);
        var learnerTask = learner.RunAsync(ct);

        await Task.WhenAll(learnerTask, counterTask);
    }

    private async Task RunMutationCountersAsync(
        string connectionString,
        IReadOnlyList<(int Id,string Name)> dbs,
        LocalStore store,
        CancellationToken ct)
    {
        var counter = new MutationCounterService(connectionString, store, _log);
        long tick = 0;

        while (!ct.IsCancellationRequested)
        {
            tick++;

            foreach (var db in dbs)
            {
                ct.ThrowIfCancellationRequested();

                var isPriority = db.Name.Equals("YILANCIOGLU", StringComparison.OrdinalIgnoreCase);
                if (!isPriority && tick % 6 != 0) continue;

                try
                {
                    await counter.SnapshotAsync(db.Name, db.Id, ct);
                }
                catch (Exception ex)
                {
                    _log($"DMV: {db.Name} kontrol edilemedi ({ex.GetType().Name}).");
                }
            }

            await Task.Delay(TimeSpan.FromSeconds(5), ct);
        }
    }
}
