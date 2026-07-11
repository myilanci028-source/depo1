import SwiftUI
import AudioToolbox

private let navy = Color(red: 8 / 255, green: 20 / 255, blue: 34 / 255)
private let panel = Color(red: 18 / 255, green: 42 / 255, blue: 70 / 255)
private let gold = Color(red: 218 / 255, green: 169 / 255, blue: 92 / 255)

struct ContentView: View {
    @Environment(\.scenePhase) private var scenePhase
    @State private var records: [ScanRecord] = []
    @State private var suffixMode: SuffixMode = .enter
    @State private var soundEnabled = true
    @State private var vibrationEnabled = true
    @State private var lastMessage = "Barkodu çerçevenin ortasına getir"

    private let store = SharedBarcodeStore()

    var body: some View {
        TabView {
            scannerTab
                .tabItem { Label("Tara", systemImage: "barcode.viewfinder") }

            historyTab
                .tabItem { Label("Geçmiş", systemImage: "clock.arrow.circlepath") }

            settingsTab
                .tabItem { Label("Klavye", systemImage: "keyboard") }
        }
        .tint(gold)
        .background(navy.ignoresSafeArea())
        .onAppear(perform: reload)
        .onChange(of: scenePhase) { phase in
            if phase == .active { reload() }
        }
    }

    private var scannerTab: some View {
        NavigationStack {
            ZStack {
                navy.ignoresSafeArea()
                VStack(spacing: 14) {
                    VStack(spacing: 3) {
                        Text("YILANCIOĞLU")
                            .font(.headline.weight(.bold))
                            .foregroundStyle(gold)
                        Text("BARKOD KLAVYE iOS")
                            .font(.title2.weight(.heavy))
                            .foregroundStyle(.white)
                    }
                    .padding(.top, 8)

                    ScannerCameraView { value, format in
                        store.add(value: value, format: format)
                        lastMessage = "\(format) • \(value)"
                        giveFeedback()
                        reload()
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .overlay {
                        RoundedRectangle(cornerRadius: 18, style: .continuous)
                            .stroke(gold, lineWidth: 1.5)
                    }
                    .frame(maxHeight: 430)
                    .padding(.horizontal, 14)

                    Text(lastMessage)
                        .font(.subheadline.monospacedDigit())
                        .foregroundStyle(.white)
                        .lineLimit(2)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 18)

                    if let latest = records.first {
                        VStack(alignment: .leading, spacing: 7) {
                            Text("SON OKUMA")
                                .font(.caption.weight(.bold))
                                .foregroundStyle(gold)
                            Text(latest.value)
                                .font(.headline.monospaced())
                                .foregroundStyle(.white)
                                .textSelection(.enabled)
                            HStack {
                                Label(latest.format, systemImage: "barcode")
                                Spacer()
                                Text(latest.scannedAt, style: .time)
                            }
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        }
                        .padding(14)
                        .background(panel)
                        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                        .padding(.horizontal, 14)
                    }

                    Text("Okunan kodlar cihaz içinde tutulur. Hedef uygulamaya dönüp YILANCIOĞLU klavyesinden tek dokunuşla yazabilirsin.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 20)
                        .padding(.bottom, 5)
                }
            }
            .toolbar(.hidden, for: .navigationBar)
        }
    }

    private var historyTab: some View {
        NavigationStack {
            ZStack {
                navy.ignoresSafeArea()
                if records.isEmpty {
                    VStack(spacing: 12) {
                        Image(systemName: "barcode.viewfinder")
                            .font(.system(size: 48, weight: .semibold))
                            .foregroundStyle(gold)
                        Text("Henüz barkod yok")
                            .font(.title3.weight(.bold))
                            .foregroundStyle(.white)
                        Text("Tara bölümünden ilk barkodu okut.")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                    .padding(24)
                } else {
                    List {
                        ForEach(records) { record in
                            VStack(alignment: .leading, spacing: 6) {
                                Text(record.value)
                                    .font(.body.monospaced())
                                    .textSelection(.enabled)
                                HStack {
                                    Text(record.format)
                                    Spacer()
                                    Text(record.scannedAt.formatted(date: .abbreviated, time: .standard))
                                }
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            }
                            .listRowBackground(panel)
                        }
                    }
                    .scrollContentBackground(.hidden)
                }
            }
            .navigationTitle("Son Okumalar")
            .toolbar {
                if !records.isEmpty {
                    Button("Temizle", role: .destructive) {
                        store.clear()
                        reload()
                    }
                }
            }
        }
    }

    private var settingsTab: some View {
        NavigationStack {
            ZStack {
                navy.ignoresSafeArea()
                ScrollView {
                    VStack(alignment: .leading, spacing: 18) {
                        VStack(alignment: .leading, spacing: 10) {
                            Text("Klavye Kurulumu")
                                .font(.title2.weight(.bold))
                                .foregroundStyle(gold)
                            instruction("1", "Ayarlar → Genel → Klavye → Klavyeler bölümüne gir.")
                            instruction("2", "Yeni Klavye Ekle’den YILANCIOĞLU Barkod’u seç.")
                            instruction("3", "Tam Erişime İzin Ver seçeneğini aç. Bu izin yalnızca ana uygulama ile klavye arasında cihaz içi barkod paylaşımı içindir; ağ bağlantısı kullanılmaz.")
                            instruction("4", "Barkodu bu uygulamada okut, hedef uygulamaya dön, küre tuşundan YILANCIOĞLU klavyesini seç ve kod düğmesine dokun.")
                        }
                        .cardStyle()

                        VStack(alignment: .leading, spacing: 12) {
                            Text("Okuma Sonrası")
                                .font(.headline)
                                .foregroundStyle(gold)
                            Picker("Sonlandırma", selection: $suffixMode) {
                                ForEach(SuffixMode.allCases) { mode in
                                    Text(mode.title).tag(mode)
                                }
                            }
                            .pickerStyle(.segmented)
                            .onChange(of: suffixMode) { store.setSuffixMode($0) }

                            Toggle("Okuma sesi", isOn: $soundEnabled)
                                .tint(gold)
                                .onChange(of: soundEnabled) { store.setSoundEnabled($0) }

                            Toggle("Titreşim", isOn: $vibrationEnabled)
                                .tint(gold)
                                .onChange(of: vibrationEnabled) { store.setVibrationEnabled($0) }
                        }
                        .cardStyle()

                        VStack(alignment: .leading, spacing: 8) {
                            Label("Gizlilik", systemImage: "lock.shield")
                                .font(.headline)
                                .foregroundStyle(gold)
                            Text("Barkodlar sunucuya gönderilmez. Uygulamada reklam, analiz SDK’sı veya kullanıcı hesabı bulunmaz. Klavye internet olmadan temel giriş işlevini sürdürür.")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                        .cardStyle()

                        Text("V1.0 TEST • iOS 16+")
                            .font(.caption.weight(.bold))
                            .foregroundStyle(gold)
                            .frame(maxWidth: .infinity)
                    }
                    .padding(16)
                }
            }
            .navigationTitle("Klavye Ayarları")
        }
    }

    private func instruction(_ number: String, _ text: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Text(number)
                .font(.caption.weight(.heavy))
                .foregroundStyle(navy)
                .frame(width: 26, height: 26)
                .background(gold)
                .clipShape(Circle())
            Text(text)
                .font(.subheadline)
                .foregroundStyle(.white)
        }
    }

    private func reload() {
        records = store.loadRecords()
        suffixMode = store.suffixMode()
        soundEnabled = store.soundEnabled()
        vibrationEnabled = store.vibrationEnabled()
    }

    private func giveFeedback() {
        if soundEnabled {
            AudioServicesPlaySystemSound(1057)
        }
        if vibrationEnabled {
            UINotificationFeedbackGenerator().notificationOccurred(.success)
        }
    }
}

private extension View {
    func cardStyle() -> some View {
        self
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(panel)
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .stroke(gold.opacity(0.5), lineWidth: 1)
            }
    }
}
