using System.IO;

namespace Veyq.Bridge;

/// <summary>
/// Where the app keeps its data on this device (the device store, the log). The packaged app (the MSIX) keeps it in its
/// package's own folder (<c>ApplicationData.Current.LocalFolder</c>: <c>%LOCALAPPDATA%\Packages\&lt;family&gt;\LocalState</c>),
/// so it starts as a clean install does and its uninstall takes it along; an unpackaged build (the development loop's)
/// keeps it in <c>%LOCALAPPDATA%\veyq\</c>. The two never share it: a packaged app left to plain <c>%LOCALAPPDATA%</c>
/// wrote <c>%LOCALAPPDATA%\veyq</c> in place wherever an unpackaged build had made it, so every development pass signed
/// in, out and untrusted the device state of the installed app (2026-11-14).
/// </summary>
public static class DataFolder
{
    /// <summary>The unpackaged build's folder under <paramref name="localAppData"/>.</summary>
    public static string Unpackaged(string localAppData) => Path.Combine(localAppData, "veyq");

    /// <summary>
    /// The data folder: <paramref name="packageLocalFolder"/> (the package's own local folder, given only when the process
    /// has package identity) whenever there is one, whatever is under <paramref name="localAppData"/>; else
    /// <see cref="Unpackaged"/>.
    /// </summary>
    public static string Resolve(string? packageLocalFolder, string localAppData) =>
        string.IsNullOrEmpty(packageLocalFolder) ? Unpackaged(localAppData) : packageLocalFolder;
}
