namespace MubelOne.Services;

/// <summary>
/// Kullanıcının 15.07.2026 YILANCIOGLU şema/kod dökümlerinden çıkarılmış başlangıç kanıtları.
/// Bunlar "yazma reçetesi" değildir. Canlı XE gözlemiyle doğrulanana kadar yalnız sınıflandırma
/// ve keşif için kullanılır.
/// </summary>
public static class VegaSeedCatalog
{
    public static readonly IReadOnlyDictionary<int, string> BelgeHeaderByIzahat =
        new Dictionary<int, string>
        {
            [53] = "TBLCARGIRBASLIK",
            [55] = "TBLCARGIRBASLIK",
            [58] = "TBLCARGIRBASLIK",
            [13] = "TBLCARGIRBASLIK",
            [18] = "TBLCARGIRBASLIK",
            [14] = "TBLCARGIRIADEBASLIK",
            [54] = "TBLCARCIKBASLIK",
            [11] = "TBLCARCIKBASLIK",
            [19] = "TBLCARCIKBASLIK",
            [12] = "TBLCARCIKIADEBASLIK",

            [20] = "TBLALFATBASLIK",
            [22] = "TBLALFATBASLIK",
            [30] = "TBLALFATBASLIK",
            [45] = "TBLALFATBASLIK",
            [86] = "TBLALFATBASLIK",
            [88] = "TBLALFATBASLIK",
            [51] = "TBLALFATBASLIK",
            [142] = "TBLALFATBASLIK",
            [144] = "TBLALFATBASLIK",

            [21] = "TBLSATFATBASLIK",
            [23] = "TBLSATFATBASLIK",
            [25] = "TBLSATFATBASLIK",
            [31] = "TBLSATFATBASLIK",
            [46] = "TBLSATFATBASLIK",
            [47] = "TBLSATFATBASLIK",
            [87] = "TBLSATFATBASLIK",
            [50] = "TBLSATFATBASLIK",
            [100] = "TBLSATFATBASLIK",
            [101] = "TBLSATFATBASLIK",
            [102] = "TBLSATFATBASLIK",
            [143] = "TBLSATFATBASLIK",
            [145] = "TBLSATFATBASLIK",

            [27] = "TBLSATIRSBASLIK",
            [29] = "TBLSATIRSBASLIK",
            [48] = "TBLSATIRSBASLIK",
            [26] = "TBLALIRSBASLIK",
            [28] = "TBLALIRSBASLIK",

            [32] = "TBLSTKGIRBASLIK",
            [106] = "TBLSTKGIRBASLIK",
            [103] = "TBLSTKGIRBASLIK",
            [33] = "TBLSTKCIKBASLIK",
            [49] = "TBLSTKCIKBASLIK",
            [105] = "TBLSTKCIKBASLIK",
            [104] = "TBLSTKCIKBASLIK",

            [15] = "TBLTAHSILBASLIK",
            [16] = "TBLODEMEBASLIK",
            [43] = "TBLOTOTESLIMBASLIK",
            [111] = "TBLDEPOHARBASLIK",
            [112] = "TBLDEPOHARBASLIK",
            [60] = "TBLALSIPBASLIK",
            [158] = "TBLALSIPBASLIK",
            [61] = "TBLVERSIPBASLIK",
            [83] = "TBLBANKGIRBASLIK",
            [84] = "TBLBANKCIKBASLIK",
            [126] = "TBLBANKCIKBASLIK",
        };

    public static readonly IReadOnlyDictionary<int, string> PortfolioStatus =
        new Dictionary<int, string>
        {
            [1]="Çek Portföyde",[2]="Çek Ciro Edilmiş",[3]="Çek Ciro",
            [4]="Çek Tahsil Edilmiş",[5]="Çek Tahsil",[6]="Çek Ödenmiş",[7]="Çek Ödeme",
            [8]="Senet Portföyde",[9]="Senet Ciro Edilmiş",[10]="Senet Ciro",
            [11]="Senet Tahsil Edilmiş",[12]="Senet Tahsil",[13]="Senet Ödenmiş",
            [15]="Çek Takasa Verilmiş",[16]="Çek Takas",[17]="Bankadan Tahsil Edilmiş Çek",
            [18]="Bankadan Tahsil Çek",[19]="Senet Takasa Verilmiş",[20]="Senet Takas",
            [21]="Senet Bankadan Tahsil Edilmiş",[22]="Senet Bankadan Tahsil",
            [23]="Bankadan Ödenmiş Çek",[24]="Çek Bankadan Ödeme",
            [25]="Senet Bankadan Ödenmiş",[26]="Senet Bankadan Ödeme",
            [27]="Kredi Kartı Bankada",[28]="Kredi Kartı Tahsil Edilmiş",[29]="Kredi Kartı Tahsil",
            [30]="Taksit Portföyde",[31]="Taksit Tahsil Edilmiş",[32]="Taksit Tahsil",
            [33]="Virman",[34]="Çek İade",[35]="Çek İade",[36]="Senet İade",[37]="Senet İade",
            [38]="Ciro Çek İade",[39]="Ciro Çek İade",[40]="Ciro Senet İade",[41]="Ciro Senet İade",
            [42]="Karşılıksız Çek",[43]="Karşılıksız Çek",[44]="Karşılıksız Senet",
            [45]="Karşılıksız Senet",[46]="Banka Virman",[47]="Banka Havale",
            [48]="Taksit İade",[49]="Taksit İade",[50]="Kredi Kartı İade",[51]="Kredi Kartı İade",
            [52]="Kredi Kartı Tahsil",[53]="Kredi Kartı Tahsil",
        };

    public static readonly string[] SanalMagazaTables =
    {
        "SBH103ETICARETSIPLISTACIKLAMA",
        "SBH103ETICARETSIPLISTBASLIK",
        "SBH103ETICARETSIPLISTHAREKET",
        "SBH103ETICARETSIPLISTVARYANT",
        "SBH103VSBATCHS",
        "SBH103VSCARIKODLAR",
        "SBH103VSDEGERESLE",
        "SBH103VSFIYATBILGILERI",
        "SBH103VSFIYATLAR",
        "SBH103VSGENELAYARLAR",
        "SBH103VSGGSIL",
        "SBH103VSGGVARATTR",
        "SBH103VSHATALAR",
        "SBH103VSHBIPTALLER",
        "SBH103VSHBORDERS",
        "SBH103VSIADETALEPBASLIK",
        "SBH103VSIADETALEPHAREKET",
        "SBH103VSINDALANTANIMLARI",
        "SBH103VSIPTALLER",
        "SBH103VSISLEMLER",
        "SBH103VSISVARYASYONLAR",
        "SBH103VSKAMPANYALAR",
        "SBH103VSKARGOENTEGRASYON",
        "SBH103VSKARGOFIYAT",
        "SBH103VSKARSILIKLAR",
        "SBH103VSKATATTR",
        "SBH103VSKATATTRMDL",
        "SBH103VSKATATTRMDL2",
        "SBH103VSKATEGORI",
        "SBH103VSKATEGORIESLE",
        "SBH103VSKOMISYON",
        "SBH103VSMARKETS",
        "SBH103VSMESAJLAR",
        "SBH103VSMUSERS",
        "SBH103VSN11KARGOSABLON",
        "SBH103VSOZELKODLAR",
        "SBH103VSPTTATTRS",
        "SBH103VSPZKARGOSABLON",
        "SBH103VSRENKKODLARI",
        "SBH103VSRESIMLER",
        "SBH103VSSETTINGS",
        "SBH103VSSTOKLAR",
        "SBH103VSTBLATTRS",
        "SBH103VSTMOZELLIK",
        "SBH103VSTYLOADS",
        "SBH103VSWEBURUNATTR",
        "SBH103VSWEBURUNITEMATTR",
        "SBH103VSWEBURUNITEMS",
        "SBH103VSWEBURUNITEMSRESIMLER",
        "SBH103VSWEBURUNLIST",
        "SBH103VSWEBURUNRESIMLER",
        "SBH103VSWEBVARGROUPS",
    };

    public static readonly string[] SecretColumnNames =
    {
        "PASSWORD","PASSWD","PWD","APIKEY","APISECRET","TOKEN","REFRESHTOKEN",
        "ROLEPASS","SECRET","ACCESS_TOKEN","REFRESH_TOKEN"
    };
}
