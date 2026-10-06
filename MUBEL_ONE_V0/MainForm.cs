using MubelOne.Services;

namespace MubelOne;

public sealed class MainForm : Form
{
    private readonly RichTextBox _log = new()
    {
        Dock = DockStyle.Fill,
        ReadOnly = true,
        BackColor = Color.FromArgb(17, 43, 74),
        ForeColor = Color.WhiteSmoke,
        Font = new Font("Consolas", 10.5f),
        BorderStyle = BorderStyle.None
    };

    private readonly Label _header = new()
    {
        Dock = DockStyle.Top,
        Height = 64,
        Text = "MUBEL ONE V0  •  GÜVENLİ ÖĞRENME MODU",
        TextAlign = ContentAlignment.MiddleLeft,
        Padding = new Padding(18, 0, 0, 0),
        Font = new Font("Segoe UI", 16, FontStyle.Bold),
        BackColor = Color.FromArgb(246, 242, 233),
        ForeColor = Color.FromArgb(17, 43, 74)
    };

    private readonly CancellationTokenSource _cts = new();

    public MainForm()
    {
        Text = "MUBEL ONE V0";
        Width = 1120;
        Height = 760;
        StartPosition = FormStartPosition.CenterScreen;
        Controls.Add(_log);
        Controls.Add(_header);
        Shown += async (_, _) => await StartAsync();
        FormClosed += (_, _) => _cts.Cancel();
    }

    private async Task StartAsync()
    {
        Write("Başlatılıyor...");
        Write("KURAL: YILANCIOGLU / VEGADB verisine INSERT, UPDATE, DELETE YOK.");
        Write(@"Yerel kayıt alanı: C:\ProgramData\MUBEL_ONE\mubel_one.db");
        Write("");

        var bootstrap = new BootstrapService(Write);
        await bootstrap.RunAsync(_cts.Token);
    }

    private void Write(string message)
    {
        if (InvokeRequired)
        {
            BeginInvoke(() => Write(message));
            return;
        }

        _log.AppendText($"[{DateTime.Now:HH:mm:ss}] {message}{Environment.NewLine}");
        _log.SelectionStart = _log.TextLength;
        _log.ScrollToCaret();
    }
}
