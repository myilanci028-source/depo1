import UIKit

final class KeyboardViewController: UIInputViewController {
    private let navy = UIColor(red: 8 / 255, green: 20 / 255, blue: 34 / 255, alpha: 1)
    private let panel = UIColor(red: 18 / 255, green: 42 / 255, blue: 70 / 255, alpha: 1)
    private let gold = UIColor(red: 218 / 255, green: 169 / 255, blue: 92 / 255, alpha: 1)

    private let store = SharedBarcodeStore()
    private let recentStack = UIStackView()
    private let statusLabel = UILabel()
    private var heightConstraint: NSLayoutConstraint?

    override func viewDidLoad() {
        super.viewDidLoad()
        buildInterface()
        refreshRecentCodes()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        refreshRecentCodes()
    }

    override func textWillChange(_ textInput: UITextInput?) {
        super.textWillChange(textInput)
        refreshRecentCodes()
    }

    private func buildInterface() {
        view.backgroundColor = navy

        heightConstraint = view.heightAnchor.constraint(equalToConstant: 318)
        heightConstraint?.priority = .defaultHigh
        heightConstraint?.isActive = true

        let root = UIStackView()
        root.axis = .vertical
        root.spacing = 6
        root.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(root)

        NSLayoutConstraint.activate([
            root.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 7),
            root.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -7),
            root.topAnchor.constraint(equalTo: view.topAnchor, constant: 7),
            root.bottomAnchor.constraint(equalTo: view.bottomAnchor, constant: -7)
        ])

        let header = UIStackView()
        header.axis = .horizontal
        header.alignment = .center
        header.spacing = 7

        let title = UILabel()
        title.text = "YILANCIOĞLU  •  BARKOD KLAVYE iOS"
        title.textColor = gold
        title.font = .systemFont(ofSize: 13, weight: .heavy)
        title.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        header.addArrangedSubview(title)

        let globe = makeButton("🌐", action: #selector(nextKeyboard), emphasized: false)
        globe.widthAnchor.constraint(equalToConstant: 48).isActive = true
        header.addArrangedSubview(globe)
        root.addArrangedSubview(header)
        header.heightAnchor.constraint(equalToConstant: 36).isActive = true

        statusLabel.textColor = .white
        statusLabel.font = .systemFont(ofSize: 12, weight: .medium)
        statusLabel.textAlignment = .center
        statusLabel.numberOfLines = 2
        statusLabel.backgroundColor = panel
        statusLabel.layer.cornerRadius = 8
        statusLabel.clipsToBounds = true
        root.addArrangedSubview(statusLabel)
        statusLabel.heightAnchor.constraint(equalToConstant: 36).isActive = true

        recentStack.axis = .vertical
        recentStack.spacing = 5
        recentStack.distribution = .fillEqually
        root.addArrangedSubview(recentStack)
        recentStack.heightAnchor.constraint(equalToConstant: 108).isActive = true

        let row1 = makeKeyRow(["1", "2", "3", "4", "5"])
        let row2 = makeKeyRow(["6", "7", "8", "9", "0"])
        root.addArrangedSubview(row1)
        root.addArrangedSubview(row2)
        row1.heightAnchor.constraint(equalToConstant: 42).isActive = true
        row2.heightAnchor.constraint(equalToConstant: 42).isActive = true

        let actions = UIStackView()
        actions.axis = .horizontal
        actions.spacing = 5
        actions.distribution = .fillEqually

        let dash = makeButton("-", action: #selector(insertDash), emphasized: false)
        let delete = makeButton("SİL", action: #selector(deleteBackward), emphasized: false)
        let tab = makeButton("TAB", action: #selector(insertTab), emphasized: false)
        let enter = makeButton("ENTER", action: #selector(insertEnter), emphasized: true)
        [dash, delete, tab, enter].forEach(actions.addArrangedSubview)
        root.addArrangedSubview(actions)
        actions.heightAnchor.constraint(equalToConstant: 42).isActive = true
    }

    private func refreshRecentCodes() {
        recentStack.arrangedSubviews.forEach {
            recentStack.removeArrangedSubview($0)
            $0.removeFromSuperview()
        }

        guard hasFullAccess else {
            statusLabel.text = "Son okutulan kodları görmek için klavye ayarından Tam Erişim’i aç"
            addPlaceholderButtons()
            return
        }

        let records = Array(store.loadRecords().prefix(3))
        statusLabel.text = records.isEmpty
            ? "Önce YILANCIOĞLU uygulamasında barkodu okut"
            : "Son okunan koda dokun — aktif alana yazılsın"

        if records.isEmpty {
            addPlaceholderButtons()
        } else {
            for record in records {
                let title = "\(record.format)  •  \(shorten(record.value))"
                let button = makeButton(title, action: #selector(insertRecent(_:)), emphasized: true)
                button.contentHorizontalAlignment = .left
                button.titleLabel?.font = .monospacedSystemFont(ofSize: 13, weight: .semibold)
                button.accessibilityIdentifier = record.value
                recentStack.addArrangedSubview(button)
            }
            while recentStack.arrangedSubviews.count < 3 {
                recentStack.addArrangedSubview(makeDisabledButton("—"))
            }
        }
    }

    private func addPlaceholderButtons() {
        recentStack.addArrangedSubview(makeDisabledButton("Barkod geçmişi bekleniyor"))
        recentStack.addArrangedSubview(makeDisabledButton("Sayısal tuşlar Tam Erişim olmadan da çalışır"))
        recentStack.addArrangedSubview(makeDisabledButton("🌐 ile diğer klavyeye geçebilirsin"))
    }

    private func makeKeyRow(_ keys: [String]) -> UIStackView {
        let row = UIStackView()
        row.axis = .horizontal
        row.spacing = 5
        row.distribution = .fillEqually
        for key in keys {
            let button = makeButton(key, action: #selector(insertNumber(_:)), emphasized: false)
            button.accessibilityIdentifier = key
            row.addArrangedSubview(button)
        }
        return row
    }

    private func makeButton(_ title: String, action: Selector, emphasized: Bool) -> UIButton {
        let button = UIButton(type: .system)
        button.setTitle(title, for: .normal)
        button.setTitleColor(.white, for: .normal)
        button.titleLabel?.font = .systemFont(ofSize: 14, weight: .bold)
        button.backgroundColor = emphasized ? gold.withAlphaComponent(0.28) : panel
        button.layer.cornerRadius = 8
        button.layer.borderWidth = 1
        button.layer.borderColor = (emphasized ? gold : UIColor.white.withAlphaComponent(0.12)).cgColor
        button.addTarget(self, action: action, for: .touchUpInside)
        button.titleLabel?.lineBreakMode = .byTruncatingMiddle
        button.contentEdgeInsets = UIEdgeInsets(top: 4, left: 9, bottom: 4, right: 9)
        return button
    }

    private func makeDisabledButton(_ title: String) -> UIButton {
        let button = makeButton(title, action: #selector(noop), emphasized: false)
        button.isEnabled = false
        button.alpha = 0.7
        return button
    }

    @objc private func insertRecent(_ sender: UIButton) {
        guard let value = sender.accessibilityIdentifier else { return }
        textDocumentProxy.insertText(value)
        let suffix = store.suffixMode().insertedText
        if !suffix.isEmpty {
            textDocumentProxy.insertText(suffix)
        }
        UIDevice.current.playInputClick()
    }

    @objc private func insertNumber(_ sender: UIButton) {
        guard let value = sender.accessibilityIdentifier else { return }
        textDocumentProxy.insertText(value)
        UIDevice.current.playInputClick()
    }

    @objc private func insertDash() {
        textDocumentProxy.insertText("-")
    }

    @objc private func deleteBackward() {
        textDocumentProxy.deleteBackward()
    }

    @objc private func insertTab() {
        textDocumentProxy.insertText("\t")
    }

    @objc private func insertEnter() {
        textDocumentProxy.insertText("\n")
    }

    @objc private func nextKeyboard() {
        advanceToNextInputMode()
    }

    @objc private func noop() {}

    private func shorten(_ value: String) -> String {
        let oneLine = value.replacingOccurrences(of: "\n", with: " ")
        if oneLine.count <= 34 { return oneLine }
        return String(oneLine.prefix(31)) + "…"
    }
}
