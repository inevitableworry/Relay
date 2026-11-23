using System.Net;
using System.Text.Json;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace Relay.Windows11;

internal static class Program
{
    [STAThread]
    private static void Main(string[] args)
    {
        ApplicationConfiguration.Initialize();
        var ally = args.Any(a => a.Equals("--rog-ally", StringComparison.OrdinalIgnoreCase)) ||
            File.Exists(Path.Combine(AppContext.BaseDirectory, "rog-ally-fse.marker"));
        Application.Run(new RelayWindow(ally));
    }
}

internal sealed class RelayWindow : Form
{
    private readonly bool ally;
    private readonly WebView2 view = new() { Dock = DockStyle.Fill };
    private readonly HttpClient http = new(new HttpClientHandler { AllowAutoRedirect = false });

    internal RelayWindow(bool rogAlly)
    {
        ally = rogAlly;
        Text = ally ? "Relay — ROG Xbox Ally" : "Relay — Windows 11";
        MinimumSize = new Size(960, 540);
        ClientSize = new Size(1440, 810);
        BackColor = Color.FromArgb(3, 3, 7);
        KeyPreview = true;
        FormBorderStyle = ally ? FormBorderStyle.None : FormBorderStyle.Sizable;
        StartPosition = FormStartPosition.CenterScreen;
        if (ally) WindowState = FormWindowState.Maximized;
        Controls.Add(view);
        Shown += async (_, _) => await InitializeAsync();
        FormClosing += (_, _) => http.Dispose();
        KeyDown += (_, e) =>
        {
            // Users can always leave the fullscreen launcher via F11 or
            // Ctrl+Shift+Q. Never take over Explorer or trap the desktop.
            if (e.KeyCode == Keys.F11)
            {
                FormBorderStyle = FormBorderStyle == FormBorderStyle.None
                    ? FormBorderStyle.Sizable : FormBorderStyle.None;
                WindowState = WindowState == FormWindowState.Maximized
                    ? FormWindowState.Normal : FormWindowState.Maximized;
                e.Handled = true;
            }
            if (e.Control && e.Shift && e.KeyCode == Keys.Q) Close();
        };
    }

    private async Task InitializeAsync()
    {
        string content = Path.Combine(AppContext.BaseDirectory, "wwwroot");
        if (!File.Exists(Path.Combine(content, "default.html")))
        {
            MessageBox.Show("Relay's original UI assets are missing.", "Relay", MessageBoxButtons.OK,
                MessageBoxIcon.Error);
            Close();
            return;
        }
        try
        {
            string dataDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "Relay", ally ? "RogXboxAlly" : "Windows11");
            Directory.CreateDirectory(dataDir);
            var env = await CoreWebView2Environment.CreateAsync(userDataFolder: dataDir);
            await view.EnsureCoreWebView2Async(env);
            var web = view.CoreWebView2;
            var settings = web.Settings;
            settings.AreDevToolsEnabled = false;
            settings.AreDefaultContextMenusEnabled = false;
            settings.AreHostObjectsAllowed = false;
            settings.IsStatusBarEnabled = false;
            web.SetVirtualHostNameToFolderMapping("relay.local", content,
                CoreWebView2HostResourceAccessKind.DenyCors);
            web.NavigationStarting += (_, e) =>
            {
                if (!e.Uri.StartsWith("https://relay.local/", StringComparison.OrdinalIgnoreCase))
                    e.Cancel = true;
            };
            web.NewWindowRequested += (_, e) => e.Handled = true;
            web.WebMessageReceived += async (_, e) => await HandleMessageAsync(e.WebMessageAsJson);
            web.Navigate("https://relay.local/default.html");
            view.Focus();
        }
        catch (Exception e)
        {
            MessageBox.Show("Could not initialize the Windows WebView2 runtime: " + e.Message,
                "Relay", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    private async Task HandleMessageAsync(string json)
    {
        try
        {
            using var document = JsonDocument.Parse(json);
            var root = document.RootElement;
            if (!root.TryGetProperty("type", out var kind) || kind.GetString() != "pbs-config") return;
            string host = root.GetProperty("host").GetString() ?? "";
            var result = await CheckPbsAsync(host);
            await PostAsync(new { type = "pbs-config-result", host, ok = result.Ok, status = result.Status,
                config = result.Config });
        }
        catch (Exception ex)
        {
            await PostAsync(new { type = "pbs-config-result", ok = false,
                status = "PS5 PBS request could not be completed: " + ex.Message });
        }
    }

    private async Task<(bool Ok, string Status, object? Config)> CheckPbsAsync(string host)
    {
        // Restrict this read-only operation to literal private IPv4 addresses,
        // so malicious UI content cannot turn the bridge into an HTTP proxy.
        if (!IPAddress.TryParse(host, out var address) || address.AddressFamily !=
            System.Net.Sockets.AddressFamily.InterNetwork || !IsPrivate(address))
            return (false, "Use a private local IPv4 PS5 address (for example 192.0.2.10).", null);
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(3));
        try
        {
            var url = new UriBuilder("http", host, 9999, "/config.json").Uri;
            using var req = new HttpRequestMessage(HttpMethod.Get, url);
            using var response = await http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, cts.Token);
            if (!response.IsSuccessStatusCode)
                return (false, "PS5 PBS service returned HTTP " + (int)response.StatusCode, null);
            var bytes = await response.Content.ReadAsByteArrayAsync(cts.Token);
            if (bytes.Length > 16_384)
                return (false, "PS5 PBS configuration exceeded its safety limit.", null);
            using var parsed = JsonDocument.Parse(bytes);
            var root = parsed.RootElement;
            if (!root.TryGetProperty("platform", out var platform) || platform.GetString() != "PS5")
                return (false, "Endpoint is not Relay's PS5 PBS service.", null);
            int width = root.TryGetProperty("width", out var w) ? w.GetInt32() : 0;
            int height = root.TryGetProperty("height", out var h) ? h.GetInt32() : 0;
            int fps = root.TryGetProperty("fps", out var f) ? f.GetInt32() : 0;
            string codec = root.TryGetProperty("codec", out var cd) ? cd.GetString() ?? "" : "";
            bool valid = width > 0 && width <= 8192 && height > 0 && height <= 4320 &&
                codec.StartsWith("avc1.", StringComparison.Ordinal);
            return (true, valid ? "PS5 video capture is advertised. Live playback is not yet enabled." :
                "PS5 PBS service responded; no ready H.264 stream was detected.",
                new { width, height, fps, codec, ready = valid });
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException)
        {
            return (false, "PS5 PBS is unavailable. The service may be offline or blocked.", null);
        }
    }

    private static bool IsPrivate(IPAddress address)
    {
        var b = address.GetAddressBytes();
        return b[0] == 10 || (b[0] == 172 && b[1] >= 16 && b[1] <= 31) ||
            (b[0] == 192 && b[1] == 168);
    }

    private async Task PostAsync(object payload)
    {
        if (view.IsDisposed || view.CoreWebView2 == null) return;
        await view.InvokeAsync(() => view.CoreWebView2.PostWebMessageAsJson(JsonSerializer.Serialize(payload)));
    }
}

internal static class ControlInvoke
{
    public static Task InvokeAsync(this Control control, Action action)
    {
        var tcs = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        if (control.IsDisposed) { tcs.SetResult(); return tcs.Task; }
        control.BeginInvoke((Action)(() =>
        {
            try { action(); tcs.TrySetResult(); }
            catch (Exception e) { tcs.TrySetException(e); }
        }));
        return tcs.Task;
    }
}
