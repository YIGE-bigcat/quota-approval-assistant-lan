using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.IO;
using System.Net;
using System.Runtime.InteropServices;
using System.Text.RegularExpressions;
using System.Windows.Forms;

internal static class Program
{
    [STAThread]
    private static void Main(string[] args)
    {
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        string projectDirectory = Path.GetFullPath(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, ".."));
        if (args.Length == 2 && (args[0] == "--render" || args[0] == "--render-demo"))
        {
            using (QuotaFloatingWindow widget = new QuotaFloatingWindow(projectDirectory, true))
            using (Bitmap image = new Bitmap(widget.Width, widget.Height))
            {
                if (args[0] == "--render-demo") widget.LoadDemoData();
                widget.CreateControl();
                widget.DrawToBitmap(image, new Rectangle(0, 0, image.Width, image.Height));
                image.Save(args[1], ImageFormat.Png);
            }
            return;
        }
        Application.Run(new QuotaFloatingWindow(projectDirectory));
    }
}

internal sealed class QuotaFloatingWindow : Form
{
    private readonly string projectDirectory;
    private readonly string settingsPath;
    private readonly string configPath;
    private readonly string pidPath;
    private readonly Timer timer;
    private readonly bool renderOnly;
    private bool showShort = true;
    private bool showWeekly = true;
    private bool compact;
    private bool online;
    private bool userMoved;
    private Rectangle closeBounds;
    private string shortValue = "--";
    private string weeklyValue = "--";
    private string shortReset = "暂无额度数据";
    private string weeklyReset = "暂无额度数据";
    private double shortPercent;
    private double weeklyPercent;
    private string pendingText = "等待连接";
    private string updatedText = "读取中";

    [DllImport("user32.dll")]
    private static extern bool ReleaseCapture();
    [DllImport("user32.dll")]
    private static extern IntPtr SendMessage(IntPtr hWnd, int msg, IntPtr wParam, IntPtr lParam);

    public QuotaFloatingWindow(string projectDirectory, bool renderOnly = false)
    {
        this.projectDirectory = projectDirectory;
        this.renderOnly = renderOnly;
        settingsPath = Path.Combine(projectDirectory, "data", "desktop-widget.json");
        configPath = Path.Combine(projectDirectory, "config.local.json");
        pidPath = Path.Combine(projectDirectory, "data", "desktop-widget.pid");
        if (!renderOnly)
        {
            Directory.CreateDirectory(Path.GetDirectoryName(pidPath));
            File.WriteAllText(pidPath, System.Diagnostics.Process.GetCurrentProcess().Id.ToString());
        }

        FormBorderStyle = FormBorderStyle.None;
        ShowInTaskbar = false;
        Text = "Codex 额度助手";
        TopMost = true;
        StartPosition = FormStartPosition.Manual;
        BackColor = Color.FromArgb(7, 17, 14);
        DoubleBuffered = true;
        ApplyLayout();
        UpdateWidget();
        timer = new Timer { Interval = 5000 };
        timer.Tick += (sender, args) => UpdateWidget();
        if (!renderOnly) timer.Start();
    }

    protected override void OnFormClosed(FormClosedEventArgs e)
    {
        timer.Stop();
        if (!renderOnly)
        {
            try { File.Delete(pidPath); } catch { }
        }
        base.OnFormClosed(e);
    }

    private void ApplyLayout()
    {
        string settings = ReadFile(settingsPath);
        showShort = ReadBool(settings, "showShort", true);
        showWeekly = ReadBool(settings, "showWeekly", true);
        compact = ReadString(settings, "density", "comfortable") == "compact";
        if (!showShort && !showWeekly) showWeekly = true;
        bool both = showShort && showWeekly;
        ClientSize = new Size(both ? (compact ? 322 : 372) : (compact ? 216 : 248), compact ? 166 : 192);
        if (!userMoved)
        {
            Rectangle area = Screen.PrimaryScreen.WorkingArea;
            Location = new Point(area.Right - Width - 24, area.Bottom - Height - 32);
        }
        using (GraphicsPath path = Rounded(new Rectangle(0, 0, Width, Height), 22)) Region = new Region(path);
    }

    private void UpdateWidget()
    {
        ApplyLayout();
        try
        {
            string config = ReadFile(configPath);
            string token = ReadString(config, "bridgeToken", "");
            string port = ReadNumberText(config, "port", "8788");
            if (String.IsNullOrWhiteSpace(token)) throw new InvalidOperationException("Missing bridge token");
            using (WebClient client = new WebClient())
            {
                client.Proxy = null;
                string json = client.DownloadString("http://127.0.0.1:" + port + "/api/status?token=" + token);
                QuotaData shortData = ReadQuota(json, "shortTerm");
                QuotaData weeklyData = ReadQuota(json, "weekly");
                shortPercent = shortData.Percent;
                weeklyPercent = weeklyData.Percent;
                shortValue = shortData.Available ? Math.Round(shortPercent).ToString() + "%" : "--";
                weeklyValue = weeklyData.Available ? Math.Round(weeklyPercent).ToString() + "%" : "--";
                shortReset = shortData.Available ? ResetText(shortData.ResetSeconds) : "暂无额度数据";
                weeklyReset = weeklyData.Available ? ResetText(weeklyData.ResetSeconds) : "暂无额度数据";
                int pending = Regex.Matches(json, "\\\"status\\\"\\s*:\\s*\\\"pending\\\"").Count;
                pendingText = pending > 0 ? pending + " 条待审批" : "暂无待审批";
                updatedText = "每 5 秒同步";
                online = true;
            }
        }
        catch
        {
            shortPercent = 0;
            weeklyPercent = 0;
            shortValue = "--";
            weeklyValue = "--";
            shortReset = "暂无额度数据";
            weeklyReset = "暂无额度数据";
            pendingText = "等待电脑端连接";
            updatedText = "连接暂不可用";
            online = false;
        }
        Invalidate();
    }

    public void LoadDemoData()
    {
        shortPercent = 78;
        weeklyPercent = 56;
        shortValue = "78%";
        weeklyValue = "56%";
        shortReset = "4小时后刷新";
        weeklyReset = "6天后刷新";
        pendingText = "2 条待审批";
        updatedText = "刚刚同步";
        online = true;
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        Graphics g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        Rectangle whole = new Rectangle(0, 0, Width - 1, Height - 1);
        using (LinearGradientBrush gradient = new LinearGradientBrush(whole, Color.FromArgb(25, 53, 43), Color.FromArgb(7, 17, 14), 35f))
        using (GraphicsPath path = Rounded(whole, 22))
        using (Pen border = new Pen(Color.FromArgb(80, 126, 243, 192), 1))
        {
            g.FillPath(gradient, path);
            g.DrawPath(border, path);
        }

        Color statusColor = online ? Color.FromArgb(98, 242, 167) : Color.FromArgb(255, 117, 111);
        using (SolidBrush dot = new SolidBrush(statusColor)) g.FillEllipse(dot, 15, 17, 7, 7);
        using (Font micro = new Font("Segoe UI", 8, FontStyle.Bold))
        using (Font title = new Font("Microsoft YaHei UI", 14, FontStyle.Bold))
        using (SolidBrush accent = new SolidBrush(Color.FromArgb(112, 248, 186)))
        using (SolidBrush text = new SolidBrush(Color.FromArgb(244, 250, 247)))
        {
            g.DrawString("CODEX LIVE", micro, accent, 28, 11);
            g.DrawString("额度助手", title, text, 14, 23);
        }

        closeBounds = new Rectangle(Width - 40, 13, 26, 26);
        using (SolidBrush closeBg = new SolidBrush(Color.FromArgb(37, 68, 58)))
        using (Pen closeBorder = new Pen(Color.FromArgb(92, 127, 111)))
        using (Font closeFont = new Font("Segoe UI", 15, FontStyle.Regular))
        using (SolidBrush closeText = new SolidBrush(Color.FromArgb(211, 228, 220)))
        {
            g.FillEllipse(closeBg, closeBounds);
            g.DrawEllipse(closeBorder, closeBounds);
            g.DrawString("×", closeFont, closeText, Width - 35, 11);
        }

        int cardTop = compact ? 53 : 58;
        int cardHeight = compact ? 79 : 92;
        int horizontal = 14;
        int gap = 8;
        bool both = showShort && showWeekly;
        if (showShort)
        {
            int width = both ? (Width - horizontal * 2 - gap) / 2 : Width - horizontal * 2;
            DrawCard(g, new Rectangle(horizontal, cardTop, width, cardHeight), "5 小时", shortValue, shortReset, shortPercent, false);
        }
        if (showWeekly)
        {
            int width = both ? (Width - horizontal * 2 - gap) / 2 : Width - horizontal * 2;
            int left = both ? horizontal + width + gap : horizontal;
            DrawCard(g, new Rectangle(left, cardTop, width, cardHeight), "本周", weeklyValue, weeklyReset, weeklyPercent, true);
        }

        int footerY = Height - 22;
        using (Font footer = new Font("Microsoft YaHei UI", 8, FontStyle.Bold))
        using (SolidBrush pending = new SolidBrush(Color.FromArgb(199, 251, 224)))
        using (SolidBrush updated = new SolidBrush(Color.FromArgb(145, 166, 156)))
        {
            g.DrawString(pendingText, footer, pending, 14, footerY);
            SizeF width = g.MeasureString(updatedText, footer);
            g.DrawString(updatedText, footer, updated, Width - 14 - width.Width, footerY);
        }
    }

    protected override void OnMouseDown(MouseEventArgs e)
    {
        base.OnMouseDown(e);
        if (closeBounds.Contains(e.Location)) { Close(); return; }
        if (e.Button == MouseButtons.Left)
        {
            userMoved = true;
            ReleaseCapture();
            SendMessage(Handle, 0xA1, (IntPtr)2, IntPtr.Zero);
        }
    }

    private void DrawCard(Graphics g, Rectangle rect, string label, string value, string reset, double percent, bool weekly)
    {
        Color fill = Tone(percent, weekly);
        using (GraphicsPath cardPath = Rounded(rect, 15))
        using (SolidBrush cardBg = new SolidBrush(Color.FromArgb(26, 45, 37)))
        using (Pen cardBorder = new Pen(Color.FromArgb(90, fill), 1))
        using (Font labelFont = new Font("Microsoft YaHei UI", 8, FontStyle.Regular))
        using (Font valueFont = new Font("Segoe UI", compact ? 23 : 28, FontStyle.Bold))
        using (Font resetFont = new Font("Microsoft YaHei UI", 8, FontStyle.Regular))
        using (SolidBrush muted = new SolidBrush(Color.FromArgb(169, 190, 179)))
        using (SolidBrush valueBrush = new SolidBrush(Color.FromArgb(245, 250, 248)))
        using (SolidBrush resetBrush = new SolidBrush(Color.FromArgb(151, 174, 163)))
        {
            g.FillPath(cardBg, cardPath);
            g.DrawPath(cardBorder, cardPath);
            g.DrawString(label, labelFont, muted, rect.Left + 11, rect.Top + 9);
            g.DrawString(value, valueFont, valueBrush, rect.Left + 10, rect.Top + 19);
            int progressY = rect.Bottom - (compact ? 29 : 31);
            Rectangle track = new Rectangle(rect.Left + 11, progressY, rect.Width - 22, 7);
            using (GraphicsPath trackPath = Rounded(track, 4))
            using (SolidBrush trackBrush = new SolidBrush(Color.FromArgb(22, 34, 29)))
            using (Pen trackBorder = new Pen(Color.FromArgb(85, 111, 130, 119)))
            {
                g.FillPath(trackBrush, trackPath);
                g.DrawPath(trackBorder, trackPath);
            }
            int fillWidth = (int)Math.Max(4, Math.Min(track.Width, track.Width * Math.Max(0, Math.Min(100, percent)) / 100));
            Rectangle fillRect = new Rectangle(track.Left + 1, track.Top + 1, fillWidth, track.Height - 2);
            using (GraphicsPath fillPath = Rounded(fillRect, 3))
            using (SolidBrush fillBrush = new SolidBrush(fill)) g.FillPath(fillBrush, fillPath);
            g.DrawString(reset, resetFont, resetBrush, rect.Left + 11, rect.Bottom - 17);
        }
    }

    private static QuotaData ReadQuota(string json, string key)
    {
        Match section = Regex.Match(json, "\\\"" + key + "\\\"\\s*:\\s*\\{(?<body>[\\s\\S]*?)\\}");
        if (!section.Success) return new QuotaData();
        string body = section.Groups["body"].Value;
        string percent = ReadNumberText(body, "remainingPercent", "");
        if (String.IsNullOrWhiteSpace(percent)) return new QuotaData();
        double value;
        if (!Double.TryParse(percent, out value)) return new QuotaData();
        int seconds;
        Int32.TryParse(ReadNumberText(body, "resetAfterSeconds", "0"), out seconds);
        return new QuotaData { Available = true, Percent = value, ResetSeconds = seconds };
    }

    private static string ReadFile(string path)
    {
        return File.Exists(path) ? File.ReadAllText(path) : "";
    }

    private static string ReadString(string json, string key, string fallback)
    {
        Match match = Regex.Match(json, "\\\"" + key + "\\\"\\s*:\\s*\\\"(?<value>[^\\\"]*)\\\"");
        return match.Success ? match.Groups["value"].Value : fallback;
    }

    private static string ReadNumberText(string json, string key, string fallback)
    {
        Match match = Regex.Match(json, "\\\"" + key + "\\\"\\s*:\\s*(?<value>-?[0-9]+(?:\\.[0-9]+)?)");
        return match.Success ? match.Groups["value"].Value : fallback;
    }

    private static bool ReadBool(string json, string key, bool fallback)
    {
        Match match = Regex.Match(json, "\\\"" + key + "\\\"\\s*:\\s*(?<value>true|false)", RegexOptions.IgnoreCase);
        return match.Success ? String.Equals(match.Groups["value"].Value, "true", StringComparison.OrdinalIgnoreCase) : fallback;
    }

    private static string ResetText(int seconds)
    {
        if (seconds <= 0) return "暂无刷新时间";
        int minutes = Math.Max(1, (int)Math.Ceiling(seconds / 60.0));
        return minutes >= 60 ? Math.Ceiling(minutes / 60.0) + "小时后刷新" : minutes + "分钟后刷新";
    }

    private static Color Tone(double percent, bool weekly)
    {
        if (percent < 25) return weekly ? Color.FromArgb(226, 76, 84) : Color.FromArgb(255, 145, 137);
        if (percent < 50) return weekly ? Color.FromArgb(214, 166, 43) : Color.FromArgb(245, 209, 111);
        return weekly ? Color.FromArgb(22, 201, 146) : Color.FromArgb(130, 241, 194);
    }

    private static GraphicsPath Rounded(Rectangle rect, int radius)
    {
        int diameter = radius * 2;
        GraphicsPath path = new GraphicsPath();
        path.AddArc(rect.Left, rect.Top, diameter, diameter, 180, 90);
        path.AddArc(rect.Right - diameter, rect.Top, diameter, diameter, 270, 90);
        path.AddArc(rect.Right - diameter, rect.Bottom - diameter, diameter, diameter, 0, 90);
        path.AddArc(rect.Left, rect.Bottom - diameter, diameter, diameter, 90, 90);
        path.CloseFigure();
        return path;
    }

    private sealed class QuotaData
    {
        public bool Available;
        public double Percent;
        public int ResetSeconds;
    }
}
