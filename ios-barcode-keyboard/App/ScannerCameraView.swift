import SwiftUI
import AVFoundation

struct ScannerCameraView: UIViewControllerRepresentable {
    let onScan: (String, String) -> Void

    func makeUIViewController(context: Context) -> ScannerViewController {
        let controller = ScannerViewController()
        controller.onScan = onScan
        return controller
    }

    func updateUIViewController(_ uiViewController: ScannerViewController, context: Context) {
        uiViewController.onScan = onScan
    }
}

final class ScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onScan: ((String, String) -> Void)?

    private let session = AVCaptureSession()
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private let statusLabel = UILabel()
    private var lastValue = ""
    private var lastScanAt = Date.distantPast

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        configureOverlay()
        requestAndConfigureCamera()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        if !session.isRunning {
            DispatchQueue.global(qos: .userInitiated).async { [weak self] in
                self?.session.startRunning()
            }
        }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        if session.isRunning {
            DispatchQueue.global(qos: .utility).async { [weak self] in
                self?.session.stopRunning()
            }
        }
    }

    private func requestAndConfigureCamera() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            configureCamera()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                DispatchQueue.main.async {
                    granted ? self?.configureCamera() : self?.showPermissionMessage()
                }
            }
        default:
            showPermissionMessage()
        }
    }

    private func configureCamera() {
        guard session.inputs.isEmpty else { return }
        session.beginConfiguration()
        session.sessionPreset = .high

        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else {
            session.commitConfiguration()
            statusLabel.text = "Kamera kullanılamıyor"
            return
        }
        session.addInput(input)

        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else {
            session.commitConfiguration()
            statusLabel.text = "Barkod okuyucu başlatılamadı"
            return
        }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)

        let requested: [AVMetadataObject.ObjectType] = [
            .ean8, .ean13, .upce,
            .code39, .code39Mod43, .code93, .code128,
            .interleaved2of5, .itf14, .codabar,
            .qr, .dataMatrix, .pdf417, .aztec
        ]
        output.metadataObjectTypes = requested.filter { output.availableMetadataObjectTypes.contains($0) }
        session.commitConfiguration()

        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        layer.frame = view.bounds
        view.layer.insertSublayer(layer, at: 0)
        previewLayer = layer

        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            self?.session.startRunning()
        }
    }

    private func configureOverlay() {
        let guide = UIView()
        guide.translatesAutoresizingMaskIntoConstraints = false
        guide.layer.borderColor = UIColor(red: 218 / 255, green: 169 / 255, blue: 92 / 255, alpha: 1).cgColor
        guide.layer.borderWidth = 3
        guide.layer.cornerRadius = 18
        guide.backgroundColor = .clear
        view.addSubview(guide)

        statusLabel.translatesAutoresizingMaskIntoConstraints = false
        statusLabel.text = "Tüm yaygın 1D ve 2D barkodlar"
        statusLabel.textColor = .white
        statusLabel.font = .systemFont(ofSize: 13, weight: .semibold)
        statusLabel.textAlignment = .center
        statusLabel.backgroundColor = UIColor.black.withAlphaComponent(0.58)
        statusLabel.layer.cornerRadius = 10
        statusLabel.clipsToBounds = true
        view.addSubview(statusLabel)

        NSLayoutConstraint.activate([
            guide.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 34),
            guide.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -34),
            guide.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            guide.heightAnchor.constraint(equalTo: view.heightAnchor, multiplier: 0.44),

            statusLabel.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24),
            statusLabel.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24),
            statusLabel.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -12),
            statusLabel.heightAnchor.constraint(equalToConstant: 36)
        ])
    }

    private func showPermissionMessage() {
        statusLabel.text = "Kamera iznini Ayarlar’dan aç"
    }

    func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        guard let object = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              let value = object.stringValue,
              !value.isEmpty else { return }

        let now = Date()
        if value == lastValue && now.timeIntervalSince(lastScanAt) < 1.5 {
            return
        }
        lastValue = value
        lastScanAt = now
        statusLabel.text = "Okundu • \(displayName(for: object.type))"
        onScan?(value, displayName(for: object.type))
    }

    private func displayName(for type: AVMetadataObject.ObjectType) -> String {
        switch type {
        case .ean8: return "EAN-8"
        case .ean13: return "EAN-13 / UPC-A"
        case .upce: return "UPC-E"
        case .code39, .code39Mod43: return "CODE-39"
        case .code93: return "CODE-93"
        case .code128: return "CODE-128"
        case .interleaved2of5: return "ITF"
        case .itf14: return "ITF-14"
        case .codabar: return "CODABAR"
        case .qr: return "QR"
        case .dataMatrix: return "DATA MATRIX"
        case .pdf417: return "PDF417"
        case .aztec: return "AZTEC"
        default: return "BARKOD"
        }
    }
}
