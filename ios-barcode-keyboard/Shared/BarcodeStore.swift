import Foundation

struct ScanRecord: Codable, Identifiable, Equatable {
    let id: UUID
    let value: String
    let format: String
    let scannedAt: Date

    init(id: UUID = UUID(), value: String, format: String, scannedAt: Date = Date()) {
        self.id = id
        self.value = value
        self.format = format
        self.scannedAt = scannedAt
    }
}

enum SuffixMode: String, CaseIterable, Identifiable {
    case enter
    case tab
    case space
    case none

    var id: String { rawValue }

    var title: String {
        switch self {
        case .enter: return "Enter"
        case .tab: return "Tab"
        case .space: return "Boşluk"
        case .none: return "Hiçbiri"
        }
    }

    var insertedText: String {
        switch self {
        case .enter: return "\n"
        case .tab: return "\t"
        case .space: return " "
        case .none: return ""
        }
    }
}

final class SharedBarcodeStore {
    static let appGroup = "group.com.yilancioglu.barcodekeyboard"
    static let recordsKey = "barcode_records_v1"
    static let suffixKey = "suffix_mode_v1"
    static let soundKey = "sound_enabled_v1"
    static let vibrationKey = "vibration_enabled_v1"

    private let defaults: UserDefaults
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init() {
        self.defaults = UserDefaults(suiteName: Self.appGroup) ?? .standard
    }

    func loadRecords() -> [ScanRecord] {
        guard let data = defaults.data(forKey: Self.recordsKey),
              let records = try? decoder.decode([ScanRecord].self, from: data) else {
            return []
        }
        return records
    }

    func add(value: String, format: String) {
        let cleaned = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleaned.isEmpty else { return }

        var records = loadRecords()
        if let first = records.first,
           first.value == cleaned,
           Date().timeIntervalSince(first.scannedAt) < 1.5 {
            return
        }

        records.insert(ScanRecord(value: cleaned, format: format), at: 0)
        if records.count > 50 {
            records = Array(records.prefix(50))
        }
        save(records)
    }

    func clear() {
        defaults.removeObject(forKey: Self.recordsKey)
    }

    func suffixMode() -> SuffixMode {
        let raw = defaults.string(forKey: Self.suffixKey) ?? SuffixMode.enter.rawValue
        return SuffixMode(rawValue: raw) ?? .enter
    }

    func setSuffixMode(_ mode: SuffixMode) {
        defaults.set(mode.rawValue, forKey: Self.suffixKey)
    }

    func soundEnabled() -> Bool {
        defaults.object(forKey: Self.soundKey) == nil ? true : defaults.bool(forKey: Self.soundKey)
    }

    func setSoundEnabled(_ enabled: Bool) {
        defaults.set(enabled, forKey: Self.soundKey)
    }

    func vibrationEnabled() -> Bool {
        defaults.object(forKey: Self.vibrationKey) == nil ? true : defaults.bool(forKey: Self.vibrationKey)
    }

    func setVibrationEnabled(_ enabled: Bool) {
        defaults.set(enabled, forKey: Self.vibrationKey)
    }

    private func save(_ records: [ScanRecord]) {
        guard let data = try? encoder.encode(records) else { return }
        defaults.set(data, forKey: Self.recordsKey)
    }
}
